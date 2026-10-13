//! wc-audio: captures Windows audio and writes raw PCM (signed 16-bit little-endian, 48 kHz,
//! stereo) to stdout until stdin or stdout closes (the mod closes stdin to stop it; if the
//! game dies, Windows closes both).
//!
//!   wc-audio --include-pid <PID> [--duck <state file>]
//!                                  audio of that process and its children (one app). With
//!                                  --duck, the app is turned down in the volume mixer while it's
//!                                  shared and the capture is amplified back (see duck.rs), so the
//!                                  sharer hears it in the game instead of directly.
//!   wc-audio --exclude-pid <PID>   everything except that process tree (whole-screen shares
//!                                  exclude Minecraft itself, so there's no echo)
//!   wc-audio --system              plain loopback of the default output device
//!   wc-audio --restore <file>      put back app volumes a crashed run left turned down
//!
//! If application loopback isn't available (older Windows 10), it falls back to --system and
//! says so on stderr ("mode=system-fallback"). Status lines on stderr start with "mode=",
//! "duck=", "info=" or "error=" so the mod can show them.

mod duck;

use std::collections::VecDeque;
use std::io::{self, Read, Write};
use std::path::PathBuf;
use std::process::ExitCode;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use wasapi::*;

const RATE: usize = 48_000;
const CHANNELS: usize = 2;

enum Target {
    Include(u32),
    Exclude(u32),
    System,
}

struct Args {
    target: Option<Target>,
    duck: Option<PathBuf>,
    restore: Option<PathBuf>,
    parent: Option<u32>,
}

fn parse_args() -> Result<Args, String> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mut a = Args { target: None, duck: None, restore: None, parent: None };
    let mut i = 0;
    let value = |i: usize| args.get(i + 1).cloned().ok_or_else(|| format!("{} needs a value", args[i]));
    while i < args.len() {
        let pid = |i: usize| -> Result<u32, String> { value(i)?.parse().map_err(|_| "bad PID".to_string()) };
        match args[i].as_str() {
            "--include-pid" => a.target = Some(Target::Include(pid(i)?)),
            "--exclude-pid" => a.target = Some(Target::Exclude(pid(i)?)),
            "--system" => { a.target = Some(Target::System); i += 1; continue; }
            "--duck" => a.duck = Some(PathBuf::from(value(i)?)),
            "--restore" => a.restore = Some(PathBuf::from(value(i)?)),
            "--parent-pid" => a.parent = Some(pid(i)?),
            other => return Err(format!("unknown argument {other}")),
        }
        i += 2;
    }
    if a.target.is_none() && a.restore.is_none() {
        return Err("usage: wc-audio --include-pid <pid> [--duck <file>] | --exclude-pid <pid> | --system | --restore <file>".into());
    }
    Ok(a)
}

fn open_client(target: &Target) -> Result<(AudioClient, &'static str), Box<dyn std::error::Error>> {
    match target {
        Target::Include(pid) => Ok((AudioClient::new_application_loopback_client(*pid, true)?, "include")),
        Target::Exclude(pid) => Ok((AudioClient::new_application_loopback_client(*pid, false)?, "exclude")),
        Target::System => {
            let device = DeviceEnumerator::new()?.get_default_device(&Direction::Render)?;
            Ok((device.get_iaudioclient()?, "system"))
        }
    }
}

fn open_and_init(target: &Target) -> Result<(AudioClient, &'static str), Box<dyn std::error::Error>> {
    let (mut client, name) = open_client(target)?;
    // Float in, converted to s16 below: every WASAPI mode accepts float with autoconvert, and
    // the in-game mode needs float (it amplifies a deliberately quiet signal back up).
    let format = WaveFormat::new(32, 32, &SampleType::Float, RATE, CHANNELS, None);
    // 500 ms of room: if this loop is ever held up (e.g. scanning the app's sessions, or the
    // game being slow to read our output), audio waits here instead of being lost — lost audio
    // is an audible crackle. It adds no delay: we still read every packet as soon as it lands.
    let mode = StreamMode::EventsShared { autoconvert: true, buffer_duration_hns: 5_000_000 };
    // Capture direction on a render device = loopback.
    client.initialize_client(&format, &Direction::Capture, &mode)?;
    Ok((client, name))
}

fn run(args: Args) -> Result<(), Box<dyn std::error::Error>> {
    initialize_mta().ok()?;
    if let Some(path) = &args.restore {
        duck::restore_from(path)?;
        if args.target.is_none() {
            return Ok(());
        }
    }
    let target = args.target.unwrap();
    let (client, mode_name) = match open_and_init(&target) {
        Ok(opened) => opened,
        Err(e) if !matches!(target, Target::System) => {
            eprintln!("error=application loopback unavailable ({e}); falling back to system loopback");
            let (c, _) = open_and_init(&Target::System)?;
            (c, "system-fallback")
        }
        Err(e) => return Err(e),
    };
    eprintln!("mode={mode_name}");

    // only an app capture can be moved into the game (never the whole system's sound)
    let mut ducker = match (&target, mode_name, &args.duck) {
        (Target::Include(pid), "include", Some(path)) => {
            eprintln!("duck=on");
            Some(duck::Ducker::new(*pid, path.clone()))
        }
        (_, _, Some(_)) => {
            eprintln!("duck=failed: this Windows version can't capture a single app");
            None
        }
        _ => None,
    };

    // the mod closes our stdin to stop us (and Windows closes it if the game dies)
    let stop = Arc::new(AtomicBool::new(false));
    {
        let stop = stop.clone();
        std::thread::spawn(move || {
            let mut buf = [0u8; 64];
            let mut stdin = io::stdin();
            while matches!(stdin.read(&mut buf), Ok(n) if n > 0) {}
            stop.store(true, Ordering::SeqCst);
        });
    }

    // ...and if the game is killed outright, notice that too (so the app gets its volume back)
    if let Some(ppid) = args.parent {
        let stop = stop.clone();
        std::thread::spawn(move || unsafe {
            use windows::Win32::System::Threading::{OpenProcess, WaitForSingleObject, INFINITE, PROCESS_SYNCHRONIZE};
            if let Ok(h) = OpenProcess(PROCESS_SYNCHRONIZE, false, ppid) {
                WaitForSingleObject(h, INFINITE);
                stop.store(true, Ordering::SeqCst);
            }
        });
    }

    let event = client.set_get_eventhandle()?;
    let capture = client.get_audiocaptureclient()?;
    client.start_stream()?;

    let mut queue: VecDeque<u8> = VecDeque::with_capacity(RATE * CHANNELS * 4);
    let mut packet: Vec<u8> = vec![0u8; RATE * CHANNELS * 4]; // room for 1 s; packets are ~10 ms
    let mut out: Vec<u8> = Vec::with_capacity(RATE * CHANNELS * 2 / 10);
    // Raw, unbuffered stdout. Rust's normal stdout is line-buffered: it splits a write after any
    // byte that happens to equal '\n', so sound arrived in pieces that didn't end on whole
    // samples — and the game dropping the odd byte turned the rest of that piece into loud noise.
    // (The game now also stitches pieces back together; this keeps each block in one write.)
    let mut stdout = {
        use std::os::windows::io::{AsRawHandle, FromRawHandle};
        std::mem::ManuallyDrop::new(unsafe { std::fs::File::from_raw_handle(io::stdout().as_raw_handle()) })
    };
    // what happened in the last 10 s, for the game's log (helps track down crackles)
    let mut silent_packets = 0u32;
    let mut glitches = 0u32;
    let mut slowest_ms = 0u128;
    let mut last_stats = std::time::Instant::now();
    while !stop.load(Ordering::SeqCst) {
        // Application loopback can go quiet when the app is silent; the mod fills gaps with
        // silence on its own clock, so a timeout here is not an error.
        let _ = event.wait_for_event(20);
        let t0 = std::time::Instant::now();
        if let Some(d) = ducker.as_mut() {
            d.poll(false);
            if let Some(why) = d.gave_up.take() {
                eprintln!("info=sound stays in the app: {why}");
                ducker = None;
            }
        }
        loop {
            let frames = capture.get_next_packet_size()?.unwrap_or(0) as usize;
            if frames == 0 {
                break;
            }
            let need = frames * CHANNELS * 4;
            if packet.len() < need {
                packet.resize(need, 0);
            }
            let (got, info) = capture.read_from_device(&mut packet[..need])?;
            let bytes = got as usize * CHANNELS * 4;
            if info.flags.silent {
                // "treat as silence and ignore the data": the buffer may hold stale sound, which
                // played back would be a burst of noise
                packet[..bytes].iter_mut().for_each(|b| *b = 0);
                silent_packets += 1;
            }
            if info.flags.data_discontinuity {
                glitches += 1;
            }
            queue.extend(&packet[..bytes]);
        }
        if queue.len() < 4 {
            continue;
        }
        let mut samples: Vec<f32> = Vec::with_capacity(queue.len() / 4);
        while queue.len() >= 4 {
            let b = [queue.pop_front().unwrap(), queue.pop_front().unwrap(), queue.pop_front().unwrap(), queue.pop_front().unwrap()];
            samples.push(f32::from_le_bytes(b));
        }
        if let Some(d) = ducker.as_mut() {
            samples = d.process(samples);
        }
        slowest_ms = slowest_ms.max(t0.elapsed().as_millis());
        out.clear();
        for s in &samples {
            out.extend_from_slice(&((s.clamp(-1.0, 1.0) * 32767.0) as i16).to_le_bytes());
        }
        if !out.is_empty() && stdout.write_all(&out).is_err() {
            break; // the mod closed the pipe: we're done
        }
        if last_stats.elapsed().as_secs() >= 10 {
            let (loud, wait, reducks) = match ducker.as_mut() {
                Some(d) => {
                    let r = (d.muted_loud, d.muted_wait, d.reducks);
                    d.muted_loud = 0;
                    d.muted_wait = 0;
                    d.reducks = 0;
                    r
                }
                None => (0, 0, 0),
            };
            if glitches + loud + wait + reducks > 0 || slowest_ms > 50 {
                eprintln!("stats=last 10 s: {glitches} capture glitch(es), {silent_packets} silent packet(s), {loud} block(s) muted as too loud, {wait} muted while turning the app down, {reducks} re-duck(s), slowest loop {slowest_ms} ms");
            }
            silent_packets = 0;
            glitches = 0;
            slowest_ms = 0;
            last_stats = std::time::Instant::now();
        }
    }
    let _ = client.stop_stream();
    drop(ducker); // restores the app's volume
    Ok(())
}

fn main() -> ExitCode {
    let args = match parse_args() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("error={e}");
            return ExitCode::from(2);
        }
    };
    match run(args) {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error={e}");
            ExitCode::from(1)
        }
    }
}
