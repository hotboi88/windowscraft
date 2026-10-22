//! "Hear it in the game": while one app is being shared, its own output is turned down to
//! DUCK (-60 dB) in the Windows volume mixer, and the captured audio is amplified back by the
//! same factor. Windows applies the mixer volume *before* the application-loopback tap and the
//! whole path is 32-bit float, so the stream is unchanged in quality while the person sharing
//! no longer hears the app directly — they hear it from the wall in Minecraft instead.
//!
//! Safety rules (the amplification is 1000x, so a level mismatch would be very loud):
//! * any captured block whose raw level is above what a ducked app can produce is replaced by
//!   silence, and the sessions are re-checked at once;
//! * after any volume change we make, output is held silent briefly while in-flight audio drains;
//! * a session the user turns back up is ducked again (and silenced meanwhile).
//!
//! Looking for the app's sessions (on every device, by its process tree) runs on a thread of its
//! own: on a busy PC one look took 25–60 ms, and done between reads of the sound it lost some of
//! it, 5–20 ms at a time, about once a second. The sound thread still reads the state and volume
//! of the sessions found (~0.05 ms) right before each decision, so a session turned back up in
//! the mixer, or starting to play before it's turned down, is silenced before any of it can be
//! amplified. When sound starts after silence or comes in too loud, what follows waits —
//! undecided — until a look that began after it has finished (a new session may have just
//! appeared), and then goes on: ~10 ms late, but whole. The list of every process (the slow part)
//! is read only when a session we haven't seen before appears, so each new session is told apart
//! by the app's process tree as it is right then.
//!
//! A pause doesn't give the app its volume back: a session stays turned down until it goes away
//! or the share ends. (It used to go back up the moment it stopped playing, and the next song
//! had to wait while it was turned down again, silenced meanwhile: a skip at every song change.)
//! The devices' session lists are kept between looks (refreshed every DEVICES_EVERY): asking
//! every device afresh was most of a look's time, and sound that waits past MAX_WAIT is lost.
//!
//! Restoring: a session goes back to its original volume when it goes away, on any
//! exit, and — should this process ever die without cleaning up — from a small state file the
//! mod replays with `--restore`. (Windows remembers per-app volumes, so this matters.)

use std::collections::{HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::sync::mpsc;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::{Duration, Instant};
use windows::core::{Interface, PWSTR};
use windows::Win32::Foundation::CloseHandle;
use windows::Win32::Media::Audio::*;
use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CoTaskMemFree, CLSCTX_ALL, COINIT_MULTITHREADED};
use windows::Win32::System::Diagnostics::ToolHelp::*;

pub const DUCK: f32 = 0.001;
const HOLD: Duration = Duration::from_millis(200);
/// How often the sessions' state and volume are read (the sound thread used to, every packet).
const STATE_EVERY: Duration = Duration::from_millis(10);
const ENUM_EVERY: Duration = Duration::from_millis(250);
/// Sound that has waited this long for a check goes on silenced (the check thread is stuck).
const MAX_WAIT: Duration = Duration::from_millis(200);
const GIVE_UP_AFTER: Duration = Duration::from_millis(2000);
/// How often the output devices themselves are listed again (one plugged in shows up by then).
const DEVICES_EVERY: Duration = Duration::from_secs(2);

/// What the checks found, for the sound thread — and what it asks of them.
struct Found {
    /// At the last check, every playing session of the app was ducked and read back at DUCK.
    all_ducked: bool,
    /// One of our volume changes is draining: silent until then.
    hold_until: Instant,
    /// When the newest finished look at the sessions began.
    checked_from: Option<Instant>,
    /// Sound started (or came in too loud) then: a look at the sessions is wanted.
    wanted: Option<Instant>,
    /// The sound thread saw a session playing un-ducked: check (and duck it) now.
    state_now: bool,
    give_up: bool,
    stop: bool,
    reducks: u32,
    /// The app's sessions as the check thread left them, for the sound thread to read.
    watch: Vec<Watch>,
    watch_version: u64,
}

/// One of the app's sessions, for the sound thread: read right before each decision.
#[derive(Clone)]
struct Watch {
    ctl: IAudioSessionControl2,
    vol: ISimpleAudioVolume,
    ducked: bool,
}

// SAFETY: both threads are in the multithreaded apartment (main: initialize_mta, the check
// thread: CoInitializeEx MTA), where audio session objects may be called from any thread.
unsafe impl Send for Watch {}

struct Shared {
    found: Mutex<Found>,
    wake: Condvar,
}

impl Shared {
    fn lock(&self) -> MutexGuard<'_, Found> {
        self.found.lock().unwrap_or_else(|e| e.into_inner())
    }
}

struct Tracked {
    ctl: IAudioSessionControl2,
    vol: ISimpleAudioVolume,
    ident: String,
    original: f32,
    ducked: bool,
}

/// The check thread's side: finds the app's sessions, ducks the playing ones, restores the rest.
struct Checks {
    root: u32,
    tree: HashSet<u32>,
    sessions: HashMap<String, Tracked>, // by session instance id
    /// Sessions of other apps (by instance id), so they're told apart only once.
    foreign: HashSet<String>,
    /// Every app volume we turned down and haven't confirmed restored: identifier → original.
    /// Written to the state file *before* ducking, cleared only after a verified restore.
    records: HashMap<String, f32>,
    enumerator: Option<IMMDeviceEnumerator>,
    managers: Vec<IAudioSessionManager2>,
    managers_at: Option<Instant>,
    state_path: PathBuf,
    last_state: Instant,
    last_enum: Instant,
    /// What the sound thread was last given (instance id, ducked), to give it news only.
    watched: Vec<(String, bool)>,
    shared: Arc<Shared>,
}

/// The sound thread's side: amplifies what it knows is ducked, silences the rest.
pub struct Ducker {
    shared: Arc<Shared>,
    done: mpsc::Receiver<()>,
    loud_blocks: Vec<Instant>,
    gain: f32,
    was_silent: bool,
    /// Blocks not decided yet, oldest first.
    waiting: VecDeque<Block>,
    /// Until a check is in: the newest moment one is wanted from.
    want: Option<Instant>,
    watch: Vec<Watch>,
    watch_version: u64,
    passthrough: bool,
    pub gave_up: Option<String>,
    /// One block held back, so that if the next one has to be muted this one can fade out first
    /// (cutting sound off mid-wave is a loud click).
    pending: Vec<f32>,
    pending_muted: bool,
    /// Counters for the log (reset by whoever prints them).
    pub muted_loud: u32,
    pub muted_wait: u32,
    pub longest_wait_ms: u128,
}

struct Block {
    samples: Vec<f32>,
    peak: f32,
    loud: bool,
    at: Instant,
    /// Decided only after a look at the sessions that began no earlier than this.
    after: Option<Instant>,
}

fn pwstr_take(p: PWSTR) -> String {
    let s = unsafe { p.to_string() }.unwrap_or_default();
    unsafe { CoTaskMemFree(Some(p.0 as *const _)) };
    s
}

fn near(a: f32, b: f32) -> bool {
    (a - b).abs() <= b * 0.01 + 1e-7
}

/// Every session on every active output device (an app may play on a non-default device).
fn new_enumerator() -> windows::core::Result<IMMDeviceEnumerator> {
    unsafe { CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL) }
}

fn all_sessions(en: &IMMDeviceEnumerator) -> windows::core::Result<Vec<IAudioSessionControl2>> {
    sessions_of(&session_managers(en)?)
}

/// Each active output device's session manager.
fn session_managers(en: &IMMDeviceEnumerator) -> windows::core::Result<Vec<IAudioSessionManager2>> {
    let mut out = Vec::new();
    unsafe {
        let devices = en.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE)?;
        for i in 0..devices.GetCount()? {
            let Ok(device) = devices.Item(i) else { continue };
            if let Ok(mgr) = device.Activate::<IAudioSessionManager2>(CLSCTX_ALL, None) {
                out.push(mgr);
            }
        }
    }
    Ok(out)
}

fn sessions_of(managers: &[IAudioSessionManager2]) -> windows::core::Result<Vec<IAudioSessionControl2>> {
    let mut out = Vec::new();
    unsafe {
        for mgr in managers {
            let Ok(list) = mgr.GetSessionEnumerator() else { continue };
            for j in 0..list.GetCount().unwrap_or(0) {
                if let Ok(s) = list.GetSession(j) {
                    if let Ok(c2) = s.cast::<IAudioSessionControl2>() {
                        out.push(c2);
                    }
                }
            }
        }
    }
    Ok(out)
}

/// The process and all its descendants (browsers play sound from a child process).
fn process_tree(root: u32) -> HashSet<u32> {
    let mut tree = HashSet::from([root]);
    let mut children: HashMap<u32, Vec<u32>> = HashMap::new();
    unsafe {
        if let Ok(snap) = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0) {
            let mut e = PROCESSENTRY32W { dwSize: std::mem::size_of::<PROCESSENTRY32W>() as u32, ..Default::default() };
            if Process32FirstW(snap, &mut e).is_ok() {
                loop {
                    if e.th32ProcessID != e.th32ParentProcessID {
                        children.entry(e.th32ParentProcessID).or_default().push(e.th32ProcessID);
                    }
                    if Process32NextW(snap, &mut e).is_err() {
                        break;
                    }
                }
            }
            let _ = CloseHandle(snap);
        }
    }
    let mut stack = vec![root];
    while let Some(p) = stack.pop() {
        if let Some(kids) = children.get(&p) {
            for &k in kids {
                if tree.insert(k) {
                    stack.push(k);
                }
            }
        }
    }
    tree
}

impl Checks {
    fn new(root: u32, state_path: PathBuf, shared: Arc<Shared>) -> Checks {
        let now = Instant::now();
        Checks {
            root,
            tree: HashSet::from([root]),
            sessions: HashMap::new(),
            foreign: HashSet::new(),
            records: load_records(&state_path),
            enumerator: None,
            managers: Vec::new(),
            managers_at: None,
            state_path,
            last_state: now - STATE_EVERY,
            last_enum: now - ENUM_EVERY,
            watched: Vec::new(),
            shared,
        }
    }

    /// Checks every STATE_EVERY — looking at the sessions at once when the sound thread wants
    /// that — until told to stop.
    fn run(&mut self) {
        let mut gave_up = false;
        loop {
            let full = {
                let mut f = self.shared.lock();
                loop {
                    if f.stop {
                        return;
                    }
                    if f.give_up && !gave_up {
                        gave_up = true;
                        drop(f);
                        self.restore_all();
                        f = self.shared.lock();
                        continue;
                    }
                    if gave_up {
                        f = self.shared.wake.wait_timeout(f, Duration::from_secs(1)).unwrap_or_else(|e| e.into_inner()).0;
                        continue;
                    }
                    if f.state_now {
                        f.state_now = false;
                        break false;
                    }
                    // (one that began since the sound started already counts)
                    let checked = f.checked_from;
                    let wanted = f.wanted.take().is_some_and(|w| checked.map_or(true, |c| c < w));
                    let since = Instant::now().saturating_duration_since(self.last_state);
                    if wanted || since >= STATE_EVERY {
                        break wanted;
                    }
                    f = self.shared.wake.wait_timeout(f, STATE_EVERY - since).unwrap_or_else(|e| e.into_inner()).0;
                }
            };
            self.check(full);
        }
    }

    /// Finds new sessions of the app (`look`: now), ducks the playing ones, restores the idle ones.
    fn check(&mut self, look: bool) {
        let began = Instant::now();
        self.last_state = began;
        let looked = look || began - self.last_enum >= ENUM_EVERY;
        if looked {
            self.last_enum = began;
            if self.enumerator.is_none() {
                self.enumerator = new_enumerator().ok();
            }
            if self.managers_at.map_or(true, |t| began - t >= DEVICES_EVERY) {
                match self.enumerator.as_ref().map(session_managers) {
                    Some(Ok(m)) => {
                        self.managers = m;
                        self.managers_at = Some(began);
                    }
                    _ => {
                        self.enumerator = None; // devices changed under us: start over next time
                        self.managers.clear();
                        self.managers_at = None;
                    }
                }
            }
            let list = match sessions_of(&self.managers) {
                Ok(l) => l,
                Err(_) => {
                    self.managers_at = None;
                    Vec::new()
                }
            };
            let mut tree_read = false;
            let mut seen = HashSet::new();
            for c2 in list {
                let Ok(pid) = (unsafe { c2.GetProcessId() }) else { continue };
                let Ok(inst) = (unsafe { c2.GetSessionInstanceIdentifier() }).map(pwstr_take) else { continue };
                seen.insert(inst.clone());
                if self.sessions.contains_key(&inst) || self.foreign.contains(&inst) {
                    continue;
                }
                // one we haven't seen: whose is it? (a new child process may have just started playing)
                if !tree_read {
                    self.tree = process_tree(self.root);
                    tree_read = true;
                }
                if !self.tree.contains(&pid) {
                    self.foreign.insert(inst);
                    continue;
                }
                let Ok(vol) = c2.cast::<ISimpleAudioVolume>() else { continue };
                let ident = unsafe { c2.GetSessionIdentifier() }.map(pwstr_take).unwrap_or_default();
                let current = unsafe { vol.GetMasterVolume() }.unwrap_or(1.0);
                // still at our level from before (a restart or crash): its real volume is in our records
                let original = if near(current, DUCK) { self.records.get(&ident).copied().unwrap_or(1.0) } else { current };
                self.sessions.insert(inst, Tracked { ctl: c2, vol, ident, original, ducked: false });
            }
            self.foreign.retain(|i| seen.contains(i));
        }
        let mut changed = false;
        let mut all_ducked = true;
        let mut expired = Vec::new();
        let mut restored = Vec::new();
        for (key, t) in self.sessions.iter_mut() {
            let state = match unsafe { t.ctl.GetState() } {
                Ok(s) => s,
                Err(_) => { all_ducked = false; continue; } // unknown right now (device change?): not "gone"
            };
            let current = unsafe { t.vol.GetMasterVolume() }.unwrap_or(-1.0);
            if state == AudioSessionStateActive {
                if !t.ducked || !near(current, DUCK) {
                    if t.ducked {
                        eprintln!("info=app volume was changed in the mixer; keeping it in the game");
                    }
                    // nothing is amplified from here until it reads back at DUCK and in-flight audio drained
                    {
                        let mut f = self.shared.lock();
                        f.all_ducked = false;
                        f.hold_until = Instant::now() + HOLD;
                        f.reducks += 1;
                    }
                    // note it down first, so even a crash right now can't lose the original volume
                    if !t.ident.is_empty() && !self.records.contains_key(&t.ident) {
                        self.records.insert(t.ident.clone(), t.original);
                        save_records(&self.state_path, &self.records);
                    }
                    let ok = unsafe { t.vol.SetMasterVolume(DUCK, std::ptr::null()) }.is_ok();
                    t.ducked = ok;
                    changed |= ok;
                    self.shared.lock().hold_until = Instant::now() + HOLD;
                    all_ducked = false; // only trust it once it reads back at DUCK on a later check
                }
            }
            // (stopped playing: it stays turned down — the next song plays on at once)
            if state == AudioSessionStateExpired {
                if t.ducked {
                    // the app went away while ducked: try to put it back (if this fails, the record
                    // stays and the next time this app plays — or `--restore` — fixes it)
                    if unsafe { t.vol.SetMasterVolume(t.original, std::ptr::null()) }.is_ok() {
                        restored.push(t.ident.clone());
                    }
                    t.ducked = false;
                    changed = true;
                }
                expired.push(key.clone());
            }
        }
        for k in expired {
            self.sessions.remove(&k);
        }
        // (put back as it went: its record goes, unless another of its sessions is still down)
        for ident in restored {
            if !ident.is_empty() && !self.sessions.values().any(|t| t.ident == ident && t.ducked) && self.records.remove(&ident).is_some() {
                save_records(&self.state_path, &self.records);
            }
        }
        let mut watched: Vec<(String, bool)> = self.sessions.iter().map(|(k, t)| (k.clone(), t.ducked)).collect();
        watched.sort();
        {
            let mut f = self.shared.lock();
            f.all_ducked = all_ducked;
            if looked {
                f.checked_from = Some(began);
            }
            if watched != self.watched {
                f.watch = self.sessions.values().map(|t| Watch { ctl: t.ctl.clone(), vol: t.vol.clone(), ducked: t.ducked }).collect();
                f.watch_version += 1;
            }
        }
        self.watched = watched;
        if changed {
            self.settle_records();
        }
    }

    /// Restores every session that's no longer ducked, and forgets a record only once every
    /// session of that app reads back its original volume.
    fn settle_records(&mut self) {
        let mut confirmed: HashMap<String, bool> = HashMap::new();
        for t in self.sessions.values() {
            if t.ident.is_empty() {
                continue;
            }
            let entry = confirmed.entry(t.ident.clone()).or_insert(true);
            if t.ducked {
                *entry = false;
                continue;
            }
            let now = unsafe { t.vol.GetMasterVolume() }.unwrap_or(-1.0);
            if near(now, DUCK) {
                let _ = unsafe { t.vol.SetMasterVolume(t.original, std::ptr::null()) };
            }
            let back = unsafe { t.vol.GetMasterVolume() }.unwrap_or(-1.0);
            if !((back - t.original).abs() < 0.01) {
                *entry = false;
            }
        }
        let before = self.records.len();
        self.records.retain(|ident, _| !confirmed.get(ident).copied().unwrap_or(false));
        if self.records.len() != before || !self.records.is_empty() {
            save_records(&self.state_path, &self.records);
        }
    }

    fn restore_all(&mut self) {
        for t in self.sessions.values_mut() {
            t.ducked = false;
        }
        self.settle_records(); // records that couldn't be confirmed stay in the file for --restore
        if self.records.is_empty() {
            let _ = std::fs::remove_file(&self.state_path);
        }
    }
}

impl Drop for Checks {
    fn drop(&mut self) {
        self.restore_all();
    }
}

impl Ducker {
    pub fn new(root: u32, state_path: PathBuf) -> Ducker {
        let found = Found {
            all_ducked: false,
            hold_until: Instant::now(),
            checked_from: None,
            wanted: None,
            state_now: false,
            give_up: false,
            stop: false,
            reducks: 0,
            watch: Vec::new(),
            watch_version: 0,
        };
        let shared = Arc::new(Shared { found: Mutex::new(found), wake: Condvar::new() });
        let (finished, done) = mpsc::channel();
        let theirs = shared.clone();
        std::thread::Builder::new()
            .name("app checks".into())
            .spawn(move || {
                let _ = unsafe { CoInitializeEx(None, COINIT_MULTITHREADED) };
                let mut checks = Checks::new(root, state_path, theirs);
                checks.run();
                drop(checks); // the app gets its volume back
                let _ = finished.send(());
            })
            .expect("the app checks thread");
        Ducker {
            shared,
            done,
            loud_blocks: Vec::new(),
            gain: 0.0,
            was_silent: true,
            waiting: VecDeque::new(),
            want: None,
            watch: Vec::new(),
            watch_version: 0,
            passthrough: false,
            gave_up: None,
            pending: Vec::new(),
            pending_muted: true,
            muted_loud: 0,
            muted_wait: 0,
            longest_wait_ms: 0,
        }
    }

    pub fn take_reducks(&mut self) -> u32 {
        std::mem::take(&mut self.shared.lock().reducks)
    }

    /// Takes captured sound (f32 stereo, possibly none) and returns what can go on: amplified, or
    /// silenced where it isn't safe to amplify. Sound waits here while a check it needs is under
    /// way, and goes on one block (~10 ms) late, so that a block right before a muted one can
    /// fade out smoothly instead of stopping dead.
    pub fn process(&mut self, samples: Vec<f32>) -> Vec<f32> {
        let now = Instant::now();
        if !self.passthrough && !samples.is_empty() {
            self.arrive(samples, now);
            if !self.passthrough {
                return self.release(now);
            }
            return self.pass(Vec::new());
        }
        if self.passthrough {
            return self.pass(samples);
        }
        self.release(now)
    }

    /// After giving up: what waited goes on silenced (it was never checked), the rest as it came.
    fn pass(&mut self, samples: Vec<f32>) -> Vec<f32> {
        let mut out = std::mem::take(&mut self.pending);
        for b in self.waiting.drain(..) {
            out.resize(out.len() + b.samples.len(), 0.0);
        }
        out.extend_from_slice(&samples);
        out
    }

    fn arrive(&mut self, samples: Vec<f32>, now: Instant) {
        let peak = samples.iter().fold(0f32, |m, s| m.max(s.abs()));
        // louder than a ducked app can be: something isn't ducked (yet) — never amplify this
        let loud = peak > DUCK * 2.0;
        if loud || (peak > 0.0 && self.was_silent) {
            // sound just started (a new session may have appeared, to duck right away) or is too
            // loud: look at once, and decide nothing from here on until a look begun since is in
            self.want = Some(now);
            let mut f = self.shared.lock();
            f.wanted = Some(now);
            drop(f);
            self.shared.wake.notify_one();
        }
        self.was_silent = peak == 0.0;
        if loud {
            self.muted_loud += 1;
            self.loud_blocks.push(now);
            self.loud_blocks.retain(|t| now - *t < GIVE_UP_AFTER);
            // most of the last 2 s too loud, despite re-ducking: Windows won't let us turn it down
            if self.loud_blocks.len() > 60 {
                self.give_up("this app's sound couldn't be turned down in Windows");
            }
        }
        self.waiting.push_back(Block { samples, peak, loud, at: now, after: self.want });
    }

    /// Decides every block whose check is in (or that waited too long), oldest first.
    fn release(&mut self, now: Instant) -> Vec<f32> {
        if self.waiting.is_empty() {
            return Vec::new();
        }
        let (all_ducked, hold_until, checked_from) = {
            let f = self.shared.lock();
            if f.watch_version != self.watch_version {
                self.watch = f.watch.clone();
                self.watch_version = f.watch_version;
            }
            (f.all_ducked, f.hold_until, f.checked_from)
        };
        // read now, after this sound was: a session turned back up in the mixer (or playing
        // before it's turned down) shows here before its sound can be amplified
        let sessions_ok = self.watch.iter().all(|w| match unsafe { w.ctl.GetState() } {
            Err(_) => false,
            Ok(s) if s != AudioSessionStateActive => true,
            Ok(_) => w.ducked && unsafe { w.vol.GetMasterVolume() }.is_ok_and(|v| near(v, DUCK)),
        });
        if !sessions_ok {
            self.shared.lock().state_now = true;
            self.shared.wake.notify_one();
        }
        let checked = |t: Instant| checked_from.is_some_and(|c| c >= t);
        let mut out = Vec::new();
        while let Some(after) = self.waiting.front().map(|b| b.after) {
            let ready = after.map_or(true, checked);
            if !ready && !after.is_some_and(|t| now.saturating_duration_since(t) > MAX_WAIT) {
                break;
            }
            let mut b = self.waiting.pop_front().unwrap();
            if after.is_some() {
                self.longest_wait_ms = self.longest_wait_ms.max(now.saturating_duration_since(b.at).as_millis());
            }
            let muted = self.decide(&mut b, ready && sessions_ok && all_ducked && now >= hold_until);
            let mut prev = std::mem::replace(&mut self.pending, b.samples);
            if muted && !self.pending_muted {
                fade_out_tail(&mut prev);
            }
            self.pending_muted = muted;
            out.extend_from_slice(&prev);
        }
        if self.want.is_some_and(checked) {
            self.want = None;
        }
        out
    }

    /// Amplifies the block in place, or silences it (`safe`: everything playing is known to be
    /// ducked); returns true if it was silenced.
    fn decide(&mut self, b: &mut Block, safe: bool) -> bool {
        if b.loud || !safe {
            b.samples.iter_mut().for_each(|s| *s = 0.0);
            self.gain = 0.0;
            if !b.loud && b.peak > 0.0 {
                self.muted_wait += 1;
            }
            return true;
        }
        // fade in over ~50 ms rather than jumping straight to x1000
        let full = 1.0 / DUCK;
        let step = full / (0.05 * 48_000.0);
        for (i, s) in b.samples.iter_mut().enumerate() {
            if i % 2 == 0 && self.gain < full {
                self.gain = (self.gain + step).min(full);
            }
            *s = (*s * self.gain).clamp(-1.0, 1.0);
        }
        false
    }

    fn give_up(&mut self, why: &str) {
        eprintln!("duck=failed: {why}");
        self.gave_up = Some(why.to_string());
        self.passthrough = true;
        self.shared.lock().give_up = true; // (the check thread gives the app its volume back)
        self.shared.wake.notify_one();
    }
}

impl Drop for Ducker {
    fn drop(&mut self) {
        self.shared.lock().stop = true;
        self.shared.wake.notify_all();
        // the app gets its volume back on the check thread: wait for that, but never hang here
        let _ = self.done.recv_timeout(Duration::from_secs(3));
    }
}

/// Fades the last 5 ms (or the whole block, if shorter) of stereo samples down to silence.
pub fn fade_out_tail(block: &mut [f32]) {
    let frames = block.len() / 2;
    let n = frames.min(240);
    if n == 0 {
        return;
    }
    let start = frames - n;
    for f in 0..n {
        let g = 1.0 - (f as f32 + 1.0) / n as f32;
        block[(start + f) * 2] *= g;
        block[(start + f) * 2 + 1] *= g;
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn fade_reaches_silence_smoothly() {
        let mut b = vec![0.5f32; 960];
        super::fade_out_tail(&mut b);
        assert_eq!(b[0], 0.5);
        assert!(b[958].abs() < 1e-6 && b[959].abs() < 1e-6);
        for i in (2..960).step_by(2) {
            assert!((b[i] - b[i - 2]).abs() < 0.01, "step at {i}");
        }
    }
}

fn load_records(path: &PathBuf) -> HashMap<String, f32> {
    std::fs::read_to_string(path).unwrap_or_default().lines().filter_map(|l| {
        let (id, v) = l.rsplit_once('\t')?;
        Some((id.to_string(), v.trim().parse().ok()?))
    }).collect()
}

/// Lines "<session identifier>\t<original volume>".
fn save_records(path: &PathBuf, records: &HashMap<String, f32>) {
    if records.is_empty() {
        let _ = std::fs::remove_file(path);
        return;
    }
    let lines: Vec<String> = records.iter().map(|(i, v)| format!("{i}\t{v}")).collect();
    let tmp = path.with_extension("tmp");
    if std::fs::write(&tmp, lines.join("\n")).is_ok() {
        let _ = std::fs::rename(&tmp, path);
    }
}

/// `--restore <file>`: put back volumes a previous run left turned down. Lines whose app isn't
/// running right now stay in the file for next time.
pub fn restore_from(path: &PathBuf) -> Result<(), Box<dyn std::error::Error>> {
    if !path.exists() {
        return Ok(());
    }
    let mut pending: Vec<(String, f32)> = load_records(path).into_iter().collect();
    let sessions = all_sessions(&new_enumerator()?)?;
    pending.retain(|(ident, original)| {
        let mut handled = false;
        for c2 in &sessions {
            let id = unsafe { c2.GetSessionIdentifier() }.map(pwstr_take).unwrap_or_default();
            if &id != ident {
                continue;
            }
            if let Ok(vol) = c2.cast::<ISimpleAudioVolume>() {
                let current = unsafe { vol.GetMasterVolume() }.unwrap_or(-1.0);
                if current < 0.0 {
                    continue;
                }
                if near(current, DUCK) {
                    let _ = unsafe { vol.SetMasterVolume(original.clamp(0.0, 1.0), std::ptr::null()) };
                    let back = unsafe { vol.GetMasterVolume() }.unwrap_or(-1.0);
                    if (back - original.clamp(0.0, 1.0)).abs() < 0.01 {
                        eprintln!("info=restored the volume of a previously shared app");
                        handled = true;
                    }
                } else {
                    handled = true; // the user already changed it themselves
                }
            }
        }
        !handled
    });
    if pending.is_empty() {
        let _ = std::fs::remove_file(path);
    } else {
        save_records(path, &pending.into_iter().collect());
    }
    Ok(())
}
