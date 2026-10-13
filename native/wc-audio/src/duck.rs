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
//! Restoring: a session goes back to its original volume as soon as it stops playing, on any
//! exit, and — should this process ever die without cleaning up — from a small state file the
//! mod replays with `--restore`. (Windows remembers per-app volumes, so this matters.)

use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::time::{Duration, Instant};
use windows::core::{Interface, PWSTR};
use windows::Win32::Foundation::CloseHandle;
use windows::Win32::Media::Audio::*;
use windows::Win32::System::Com::{CoCreateInstance, CoTaskMemFree, CLSCTX_ALL};
use windows::Win32::System::Diagnostics::ToolHelp::*;

pub const DUCK: f32 = 0.001;
const HOLD: Duration = Duration::from_millis(200);
const ENUM_EVERY: Duration = Duration::from_millis(250);
const TREE_EVERY: Duration = Duration::from_millis(1000);
const GIVE_UP_AFTER: Duration = Duration::from_millis(2000);

struct Tracked {
    ctl: IAudioSessionControl2,
    vol: ISimpleAudioVolume,
    ident: String,
    original: f32,
    ducked: bool,
}

pub struct Ducker {
    root: u32,
    tree: HashSet<u32>,
    sessions: HashMap<String, Tracked>, // by session instance id
    /// Every app volume we turned down and haven't confirmed restored: identifier → original.
    /// Written to the state file *before* ducking, cleared only after a verified restore.
    records: HashMap<String, f32>,
    enumerator: Option<IMMDeviceEnumerator>,
    /// At the last poll, every playing session of the app was ducked and read back at DUCK.
    all_ducked: bool,
    last_forced: Instant,
    loud_blocks: Vec<Instant>,
    gain: f32,
    state_path: PathBuf,
    last_tree: Instant,
    last_enum: Instant,
    hold_until: Instant,
    was_silent: bool,
    pub gave_up: Option<String>,
    /// One block held back, so that if the next one has to be muted this one can fade out first
    /// (cutting sound off mid-wave is a loud click).
    pending: Vec<f32>,
    pending_muted: bool,
    /// Counters for the log (reset by whoever prints them).
    pub muted_loud: u32,
    pub muted_wait: u32,
    pub reducks: u32,
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
    let mut out = Vec::new();
    unsafe {
        let devices = en.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE)?;
        for i in 0..devices.GetCount()? {
            let Ok(device) = devices.Item(i) else { continue };
            let Ok(mgr) = device.Activate::<IAudioSessionManager2>(CLSCTX_ALL, None) else { continue };
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

impl Ducker {
    pub fn new(root: u32, state_path: PathBuf) -> Ducker {
        let now = Instant::now();
        Ducker {
            root,
            tree: process_tree(root),
            sessions: HashMap::new(),
            records: load_records(&state_path),
            enumerator: None,
            all_ducked: false,
            last_forced: now - ENUM_EVERY,
            loud_blocks: Vec::new(),
            gain: 0.0,
            state_path,
            last_tree: now,
            last_enum: now - ENUM_EVERY,
            hold_until: now,
            was_silent: true,
            gave_up: None,
            pending: Vec::new(),
            pending_muted: true,
            muted_loud: 0,
            muted_wait: 0,
            reducks: 0,
        }
    }

    /// Finds new sessions of the app, ducks the playing ones, restores the idle ones.
    /// `force`: something looks off (sound appeared or is too loud) — re-scan now, at most 10×/s.
    pub fn poll(&mut self, force: bool) {
        if self.gave_up.is_some() {
            return;
        }
        let now = Instant::now();
        let forced = force && now - self.last_forced >= Duration::from_millis(100);
        if forced {
            self.last_forced = now;
        }
        if forced || now - self.last_tree >= TREE_EVERY {
            self.tree = process_tree(self.root); // a new child process may have just started playing
            self.last_tree = now;
        }
        if forced || now - self.last_enum >= ENUM_EVERY {
            self.last_enum = now;
            if self.enumerator.is_none() {
                self.enumerator = new_enumerator().ok();
            }
            let list = match self.enumerator.as_ref().map(all_sessions) {
                Some(Ok(l)) => l,
                _ => {
                    self.enumerator = None; // devices changed under us: start over next time
                    Vec::new()
                }
            };
            for c2 in list {
                let Ok(pid) = (unsafe { c2.GetProcessId() }) else { continue };
                if !self.tree.contains(&pid) {
                    continue;
                }
                let Ok(inst) = (unsafe { c2.GetSessionInstanceIdentifier() }).map(pwstr_take) else { continue };
                if self.sessions.contains_key(&inst) {
                    continue;
                }
                let Ok(vol) = c2.cast::<ISimpleAudioVolume>() else { continue };
                let ident = unsafe { c2.GetSessionIdentifier() }.map(pwstr_take).unwrap_or_default();
                let current = unsafe { vol.GetMasterVolume() }.unwrap_or(1.0);
                // still at our level from before (a restart or crash): its real volume is in our records
                let original = if near(current, DUCK) { self.records.get(&ident).copied().unwrap_or(1.0) } else { current };
                self.sessions.insert(inst, Tracked { ctl: c2, vol, ident, original, ducked: false });
            }
        }
        let mut changed = false;
        let mut all_ducked = true;
        let mut expired = Vec::new();
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
                    self.reducks += 1;
                    // note it down first, so even a crash right now can't lose the original volume
                    if !t.ident.is_empty() && !self.records.contains_key(&t.ident) {
                        self.records.insert(t.ident.clone(), t.original);
                        save_records(&self.state_path, &self.records);
                    }
                    let ok = unsafe { t.vol.SetMasterVolume(DUCK, std::ptr::null()) }.is_ok();
                    t.ducked = ok;
                    changed |= ok;
                    self.hold_until = Instant::now() + HOLD;
                    self.gain = 0.0;
                    all_ducked = false; // only trust it once it reads back at DUCK on a later poll
                }
            } else if t.ducked {
                // stopped playing (or the app is closing): give it its volume back now, while we still can
                t.ducked = false;
                changed = true;
            }
            if state == AudioSessionStateExpired {
                if t.ducked {
                    // the app went away while ducked: try to put it back (if this fails, the record
                    // stays and the next time this app plays — or `--restore` — fixes it)
                    let _ = unsafe { t.vol.SetMasterVolume(t.original, std::ptr::null()) };
                    t.ducked = false;
                    changed = true;
                }
                expired.push(key.clone());
            }
        }
        for k in expired {
            self.sessions.remove(&k);
        }
        self.all_ducked = all_ducked;
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

    /// Takes one captured block (f32 stereo) and returns the block before it, amplified — or
    /// silenced if it isn't safe to amplify. The one-block hold (~10 ms) lets a block that comes
    /// right before a muted one fade out smoothly instead of stopping dead.
    pub fn process(&mut self, mut samples: Vec<f32>) -> Vec<f32> {
        if self.gave_up.is_some() {
            let mut out = std::mem::take(&mut self.pending);
            out.extend_from_slice(&samples);
            return out;
        }
        let muted = self.decide(&mut samples);
        let mut out = std::mem::replace(&mut self.pending, samples);
        if muted && !self.pending_muted {
            fade_out_tail(&mut out);
        }
        self.pending_muted = muted;
        out
    }

    /// Amplifies the block in place, or silences it; returns true if it was silenced.
    fn decide(&mut self, samples: &mut [f32]) -> bool {
        let peak = samples.iter().fold(0f32, |m, s| m.max(s.abs()));
        let now = Instant::now();
        if peak > 0.0 && self.was_silent {
            self.poll(true); // sound just started: a new session may have appeared, duck it right away
        }
        self.was_silent = peak == 0.0;
        if peak > DUCK * 2.0 {
            // louder than a ducked app can be: something isn't ducked (yet) — never amplify this
            samples.iter_mut().for_each(|s| *s = 0.0);
            self.gain = 0.0;
            self.muted_loud += 1;
            self.loud_blocks.push(now);
            self.loud_blocks.retain(|t| now - *t < GIVE_UP_AFTER);
            self.poll(true);
            // most of the last 2 s too loud, despite re-ducking: Windows won't let us turn it down
            if self.loud_blocks.len() > 60 {
                self.give_up("this app's sound couldn't be turned down in Windows");
            }
            return true;
        }
        // amplify only what we know is ducked: every playing session confirmed at DUCK, hold over
        if !self.all_ducked || now < self.hold_until {
            samples.iter_mut().for_each(|s| *s = 0.0);
            self.gain = 0.0;
            if peak > 0.0 {
                self.muted_wait += 1;
            }
            return true;
        }
        // fade in over ~50 ms rather than jumping straight to x1000
        let full = 1.0 / DUCK;
        let step = full / (0.05 * 48_000.0);
        for (i, s) in samples.iter_mut().enumerate() {
            if i % 2 == 0 && self.gain < full {
                self.gain = (self.gain + step).min(full);
            }
            *s = (*s * self.gain).clamp(-1.0, 1.0);
        }
        false
    }

    fn give_up(&mut self, why: &str) {
        self.restore_all();
        self.gave_up = Some(why.to_string());
        eprintln!("duck=failed: {why}");
    }

    pub fn restore_all(&mut self) {
        for t in self.sessions.values_mut() {
            t.ducked = false;
        }
        self.settle_records(); // records that couldn't be confirmed stay in the file for --restore
        if self.records.is_empty() {
            let _ = std::fs::remove_file(&self.state_path);
        }
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

impl Drop for Ducker {
    fn drop(&mut self) {
        self.restore_all();
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
