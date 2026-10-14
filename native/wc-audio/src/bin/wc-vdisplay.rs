//! wc-vdisplay: in-game monitors for WindowsCraft.
//!
//! Adds and removes virtual monitors with the Parsec Virtual Display Driver (a free, signed
//! IddCx display driver), one at a time, on request. Windows sees each one as a real monitor:
//! apps can live on it, the real mouse and keyboard can reach it, and Windows Graphics Capture
//! captures it like any screen. Nothing shows on your physical monitors.
//!
//! The driver keeps a monitor only while someone pings it (at least every ~100 ms): when this
//! helper stops (game closed, game crashed, helper killed), every monitor it added disappears
//! within about a second, and Windows moves anything that was on it to your main monitor.
//!
//!   wc-vdisplay --parent-pid <pid>
//!
//! stdin, one command per line; stdout, one answer per line. Monitors are named by the driver's
//! index; the helper always looks up where a monitor is *now* (Windows can move them).
//!   status                     -> status <ok|missing|disabled|restart|error> <driver minor version or 0>
//!   add <w> <h> <hz>           -> added <index> <device> <left> <top> <width> <height>  | fail <reason>
//!   remove <index>             -> removed <index>
//!   rects                      -> rect <index> <device> <left> <top> <width> <height> (each healthy one) · mirrored <index> (shown on top of a real screen)  then: rects-end
//!   windows <index>            -> win <hwnd> <pid> <ours 0|1> <title>  (each window on it; ours = started on it)  then: windows-end
//!   deskwindows                -> win <hwnd> <pid> 0 <title>  (each window not on an in-game monitor)  then: windows-end
//!   bring <hwnd> <index>       -> brought <hwnd>   (onto that monitor; remembers where it was)
//!   home <hwnd>                -> homed <hwnd>     (back to your desk)
//!   close <hwnd>               -> closed <hwnd>    (like clicking its ×)
//!   evacuate <index>           -> evacuated <n>    (everything on it back to your desk)
//!   seat <index> <u> <v> <game hwnd>  -> seated | fail <reason>   (your real mouse and keyboard go to it)
//!   beat                       (no answer: "the game is still there" — needed every few seconds while seated)
//!   unseat                     -> unseated
//!   point <index> <game hwnd> <u> <v>  (no answer; event pointok [seated] | pointfail <reason>) your real
//!                              cursor goes onto that monitor at (u, v) without taking the game's keyboard:
//!                              Alt-pointing at it, or someone you allowed using it; your mouse keeps turning
//!                              your view, as "look" events
//!   unpoint                    (no answer)
//!   release                    (no answer) lets go of every button and key pressed for someone you allowed
//!   modes <index>              -> mode <w> <h> <hz> (each size it can show, at its best rate near 60)  then: modes-end
//!   setmode <index> <w> <h> <hz> -> modeset ok <left> <top> <w> <h> | modeset fail <reason>
//!   pointme <1|0>              (no answer) 1: you're the one pointing — your clicks and wheel reach the monitor
//!                              (and Alt, held since before, is let go for the apps); 0: someone else is
//!   ptr <u> <v>                (no answer) the real cursor to that spot of the monitor you point at or sit at
//!   btn <0-4> <down|up>        (no answer) a click there (someone you allowed)
//!   wheel <notches>            (no answer)
//!   key <scancode> <down|up>   (no answer) a key, as if typed on your keyboard (only while you sit there)
//!   launch<TAB><index><TAB><file><TAB><parameters><TAB><exe to match>  -> launched <ok|failed>
//!                              (starts an app straight onto that monitor: its windows go there the
//!                              moment they appear, and it closes when the monitor turns off)
//!   install <installer.exe>    -> install <ok|cancelled|failed> <code>
//!   uninstall                  -> uninstall <ok|cancelled|missing|failed> <code>
//! While seated, events: esc · alt <1|0> · look <dx> <dy> · zoom <notches> · recenter
//! While pointing, events: look <dx> <dy> · altup (you let go of Alt) · mbtn <0-4> <1|0> and mwheel
//! <notches> (your own clicks and wheel while someone else points: they belong to the game) · pointlost
//! ("esc" also means the helper already gave the mouse and keyboard back by itself.)
//! stderr: info=… / error=… for the log. Exits (removing its monitors) when stdin closes or the
//! parent exits.

use std::collections::HashMap;
use std::io::{self, BufRead, Write};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicIsize, Ordering};
use std::sync::mpsc::{channel, Sender};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};
use windows::core::{PCWSTR, PWSTR};
use windows::Win32::Devices::DeviceAndDriverInstallation::*;
use windows::Win32::Foundation::*;
use windows::Win32::Graphics::Dwm::{DwmGetWindowAttribute, DWMWA_CLOAKED};
use windows::Win32::Graphics::Gdi::*;
use windows::Win32::Storage::FileSystem::*;
use windows::Win32::System::Registry::*;
use windows::Win32::System::Threading::*;
use windows::Win32::System::IO::*;
use windows::Win32::UI::Input::KeyboardAndMouse::*;
use windows::Win32::UI::Shell::*;
use windows::Win32::UI::WindowsAndMessaging::*;

// ---- the driver -----------------------------------------------------------------------------

const VDD_HARDWARE_ID: &str = "Root\\Parsec\\VDA";
const VDD_DISPLAY_ID: &str = "PSCCDD0";
const DISPLAY_CLASS: windows::core::GUID = windows::core::GUID::from_u128(0x4d36e968_e325_11ce_bfc1_08002be10318);
const VDD_ADAPTER: windows::core::GUID = windows::core::GUID::from_u128(0x00b41627_04c4_429e_a26e_0265cf50c8fa);
const IOCTL_ADD: u32 = 0x0022e004;
const IOCTL_REMOVE: u32 = 0x0022a008;
const IOCTL_UPDATE: u32 = 0x0022a00c;
const IOCTL_VERSION: u32 = 0x0022e010;

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

fn from_wide(w: &[u16]) -> String {
    let n = w.iter().position(|&c| c == 0).unwrap_or(w.len());
    String::from_utf16_lossy(&w[..n])
}

/// Answers and events share stdout; one line at a time.
fn say(line: &str) {
    let out = io::stdout();
    let mut o = out.lock();
    let _ = writeln!(o, "{line}");
    let _ = o.flush();
}

/// Is the driver installed and running? ("ok", "missing", "disabled", "restart", "error")
fn driver_status() -> &'static str {
    unsafe {
        let Ok(set) = SetupDiGetClassDevsW(Some(&DISPLAY_CLASS), PCWSTR::null(), None, DIGCF_PRESENT) else { return "error" };
        let mut result = "missing";
        let want = VDD_HARDWARE_ID.to_ascii_lowercase();
        let mut i = 0;
        loop {
            let mut info = SP_DEVINFO_DATA { cbSize: std::mem::size_of::<SP_DEVINFO_DATA>() as u32, ..Default::default() };
            if SetupDiEnumDeviceInfo(set, i, &mut info).is_err() {
                break;
            }
            i += 1;
            let mut buf = vec![0u8; 4096];
            let mut ty = 0u32;
            if SetupDiGetDeviceRegistryPropertyW(set, &info, SPDRP_HARDWAREID, Some(&mut ty), Some(&mut buf), None).is_err() {
                continue;
            }
            let ids: Vec<u16> = buf.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
            let found = ids.split(|&c| c == 0).any(|s| String::from_utf16_lossy(s).to_ascii_lowercase() == want);
            if !found {
                continue;
            }
            let mut st = CM_DEVNODE_STATUS_FLAGS(0);
            let mut prob = CM_PROB(0);
            if CM_Get_DevNode_Status(&mut st, &mut prob, info.DevInst, 0) != CR_SUCCESS {
                result = "error";
            } else if st.0 & (DN_DRIVER_LOADED.0 | DN_STARTED.0) != 0 {
                result = "ok";
            } else if st.0 & DN_HAS_PROBLEM.0 != 0 {
                result = match prob {
                    CM_PROB_NEED_RESTART => "restart",
                    CM_PROB_DISABLED | CM_PROB_HARDWARE_DISABLED | CM_PROB_DISABLED_SERVICE => "disabled",
                    _ => "error",
                };
            } else {
                result = "error";
            }
            break;
        }
        let _ = SetupDiDestroyDeviceInfoList(set);
        result
    }
}

/// The driver's device instance id (ROOT\DISPLAY\0000), to restart it with.
fn vdd_instance_id() -> Option<String> {
    unsafe {
        let set = SetupDiGetClassDevsW(Some(&DISPLAY_CLASS), PCWSTR::null(), None, DIGCF_PRESENT).ok()?;
        let want = VDD_HARDWARE_ID.to_ascii_lowercase();
        let mut i = 0;
        let mut out = None;
        loop {
            let mut info = SP_DEVINFO_DATA { cbSize: std::mem::size_of::<SP_DEVINFO_DATA>() as u32, ..Default::default() };
            if SetupDiEnumDeviceInfo(set, i, &mut info).is_err() {
                break;
            }
            i += 1;
            let mut buf = vec![0u8; 4096];
            let mut ty = 0u32;
            if SetupDiGetDeviceRegistryPropertyW(set, &info, SPDRP_HARDWAREID, Some(&mut ty), Some(&mut buf), None).is_err() {
                continue;
            }
            let ids: Vec<u16> = buf.chunks_exact(2).map(|c| u16::from_le_bytes([c[0], c[1]])).collect();
            if !ids.split(|&c| c == 0).any(|s| String::from_utf16_lossy(s).to_ascii_lowercase() == want) {
                continue;
            }
            let mut id = [0u16; 512];
            if CM_Get_Device_IDW(info.DevInst, &mut id, 0) == CR_SUCCESS {
                out = Some(from_wide(&id));
            }
            break;
        }
        let _ = SetupDiDestroyDeviceInfoList(set);
        out
    }
}

/// The driver's control handle (opened for overlapped I/O, like the Parsec app does). Every
/// call has its own OVERLAPPED, so the keep-alive never waits behind a slow call.
struct Vdd {
    handle: HANDLE,
}

unsafe impl Send for Vdd {}
unsafe impl Sync for Vdd {}

impl Vdd {
    fn open() -> Option<Vdd> {
        unsafe {
            let set = SetupDiGetClassDevsW(Some(&VDD_ADAPTER), PCWSTR::null(), None, DIGCF_PRESENT | DIGCF_DEVICEINTERFACE).ok()?;
            let mut found = None;
            let mut i = 0;
            loop {
                let mut itf = SP_DEVICE_INTERFACE_DATA { cbSize: std::mem::size_of::<SP_DEVICE_INTERFACE_DATA>() as u32, ..Default::default() };
                if SetupDiEnumDeviceInterfaces(set, None, &VDD_ADAPTER, i, &mut itf).is_err() {
                    break;
                }
                i += 1;
                let mut need = 0u32;
                let _ = SetupDiGetDeviceInterfaceDetailW(set, &itf, None, 0, Some(&mut need), None);
                if need == 0 {
                    continue;
                }
                let mut raw = vec![0u64; (need as usize + 7) / 8];
                let detail = raw.as_mut_ptr() as *mut SP_DEVICE_INTERFACE_DETAIL_DATA_W;
                (*detail).cbSize = std::mem::size_of::<SP_DEVICE_INTERFACE_DETAIL_DATA_W>() as u32;
                if SetupDiGetDeviceInterfaceDetailW(set, &itf, Some(detail), need, None, None).is_err() {
                    continue;
                }
                let path = PCWSTR((*detail).DevicePath.as_ptr());
                if let Ok(h) = CreateFileW(
                    path,
                    (GENERIC_READ | GENERIC_WRITE).0,
                    FILE_SHARE_READ | FILE_SHARE_WRITE,
                    None,
                    OPEN_EXISTING,
                    FILE_ATTRIBUTE_NORMAL | FILE_FLAG_NO_BUFFERING | FILE_FLAG_OVERLAPPED | FILE_FLAG_WRITE_THROUGH,
                    None,
                ) {
                    if !h.is_invalid() {
                        found = Some(Vdd { handle: h });
                        break;
                    }
                }
            }
            let _ = SetupDiDestroyDeviceInfoList(set);
            found
        }
    }

    fn ioctl(&self, code: u32, data: &[u8]) -> Option<u32> {
        // (on the heap: if the driver never finishes a call, these are left to it — never freed
        // under it — and the helper carries on instead of waiting forever)
        // (WC_VDD_SERIAL=1: one call at a time — an experiment knob)
        let _gate = if *SERIAL { Some(IOCTL_GATE.lock().unwrap_or_else(|e| e.into_inner())) } else { None };
        let t0 = Instant::now();
        let r = self.ioctl_raw(code, data);
        let ms = t0.elapsed().as_millis();
        if code != IOCTL_UPDATE && code != IOCTL_VERSION || ms > 500 {
            eprintln!("info=driver call {code:#x} took {ms} ms");
        }
        r
    }

    fn ioctl_raw(&self, code: u32, data: &[u8]) -> Option<u32> {
        struct Call {
            input: [u8; 32],
            out: u32,
            ov: OVERLAPPED,
        }
        unsafe {
            let ev = CreateEventW(None, true, false, PCWSTR::null()).ok()?;
            let mut c = Box::new(Call { input: [0u8; 32], out: 0, ov: OVERLAPPED { hEvent: ev, ..Default::default() } });
            c.input[..data.len().min(32)].copy_from_slice(&data[..data.len().min(32)]);
            let _ = DeviceIoControl(
                self.handle,
                code,
                Some(c.input.as_ptr() as *const _),
                c.input.len() as u32,
                Some(&mut c.out as *mut u32 as *mut _),
                4,
                None,
                Some(&mut c.ov),
            );
            let mut n = 0u32;
            let first = GetOverlappedResultEx(self.handle, &c.ov, &mut n, 5000, false);
            if let Err(e) = &first {
                *LAST_IOCTL.lock().unwrap() = format!("{code:#x}: {e}");
            }
            if first.is_ok() {
                let _ = CloseHandle(ev);
                return Some(c.out);
            }
            if let Err(e) = &first {
                // the driver answered "no" (rather than not answering): the call is over
                let code_ = (e.code().0 as u32) & 0xFFFF;
                if code_ != WAIT_TIMEOUT.0 && code_ != ERROR_IO_INCOMPLETE.0 && code_ != ERROR_IO_PENDING.0 {
                    let _ = CloseHandle(ev);
                    return None;
                }
            }
            let _ = CancelIoEx(self.handle, Some(&c.ov));
            match GetOverlappedResultEx(self.handle, &c.ov, &mut n, 3000, false) {
                Ok(()) => {
                    let _ = CloseHandle(ev);
                    if code == IOCTL_ADD {
                        // it finished after all, but nobody is waiting for that monitor: take it back
                        let i = c.out as i32;
                        let be = [((i >> 8) & 0xFF) as u8, (i & 0xFF) as u8];
                        let _ = self.ioctl_raw(IOCTL_REMOVE, &be);
                    }
                    None
                }
                Err(_) => {
                    eprintln!("error=the display driver didn't finish a call ({code:#x}); going on without it");
                    std::mem::forget(c); // (the driver may still write into it)
                    None
                }
            }
        }
    }

    fn ping(&self) {
        if self.ioctl(IOCTL_UPDATE, &[]).is_none() {
            PING_FAILS.fetch_add(1, Ordering::SeqCst);
        }
    }

    fn add(&self) -> Option<i32> {
        let idx = self.ioctl(IOCTL_ADD, &[])? as i32;
        self.ping();
        Some(idx)
    }

    fn remove(&self, index: i32) {
        let be = [((index >> 8) & 0xFF) as u8, (index & 0xFF) as u8]; // 16-bit big-endian index
        let _ = self.ioctl(IOCTL_REMOVE, &be);
        self.ping();
    }
}

impl Drop for Vdd {
    fn drop(&mut self) {
        unsafe {
            let _ = CloseHandle(self.handle);
        }
    }
}

// ---- monitors -------------------------------------------------------------------------------

/// The desktop name (\\.\DISPLAYn) of the virtual monitor with this index, once Windows has it.
fn device_for_index(index: i32) -> Option<String> {
    let uid = format!("UID{}", 256 + index);
    // (Windows can list the same monitor under more than one display name — an old, switched-off
    // one among them: the one that's on the desktop is the one)
    let mut best: Option<(u32, String)> = None;
    unsafe {
        let mut i = 0;
        loop {
            let mut ad = DISPLAY_DEVICEW { cb: std::mem::size_of::<DISPLAY_DEVICEW>() as u32, ..Default::default() };
            if !EnumDisplayDevicesW(PCWSTR::null(), i, &mut ad, 0).as_bool() {
                return best.map(|(_, d)| d);
            }
            i += 1;
            let mut j = 0;
            loop {
                let mut mon = DISPLAY_DEVICEW { cb: std::mem::size_of::<DISPLAY_DEVICEW>() as u32, ..Default::default() };
                if !EnumDisplayDevicesW(PCWSTR(ad.DeviceName.as_ptr()), j, &mut mon, EDD_GET_DEVICE_INTERFACE_NAME).as_bool() {
                    break;
                }
                j += 1;
                let id = from_wide(&mon.DeviceID).to_ascii_uppercase();
                if id.contains(VDD_DISPLAY_ID) && id.split(|c| c == '#' || c == '&').any(|p| p == uid) {
                    let score = 1 + if (ad.StateFlags & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP).0 != 0 { 4 } else { 0 }
                        + if (mon.StateFlags & DISPLAY_DEVICE_ACTIVE).0 != 0 { 2 } else { 0 };
                    if best.as_ref().map_or(true, |(b, _)| score > *b) {
                        best = Some((score, from_wide(&ad.DeviceName)));
                    }
                }
            }
        }
    }
}

/// For the log: every display name Windows has for a monitor of the driver, its state and its UID.
fn vdd_listing() -> String {
    let mut out = Vec::new();
    unsafe {
        let mut i = 0;
        loop {
            let mut ad = DISPLAY_DEVICEW { cb: std::mem::size_of::<DISPLAY_DEVICEW>() as u32, ..Default::default() };
            if !EnumDisplayDevicesW(PCWSTR::null(), i, &mut ad, 0).as_bool() {
                break;
            }
            i += 1;
            let mut j = 0;
            loop {
                let mut mon = DISPLAY_DEVICEW { cb: std::mem::size_of::<DISPLAY_DEVICEW>() as u32, ..Default::default() };
                if !EnumDisplayDevicesW(PCWSTR(ad.DeviceName.as_ptr()), j, &mut mon, EDD_GET_DEVICE_INTERFACE_NAME).as_bool() {
                    break;
                }
                j += 1;
                let id = from_wide(&mon.DeviceID).to_ascii_uppercase();
                if id.contains(VDD_DISPLAY_ID) {
                    let uid = id.split(|c| c == '#' || c == '&').find(|p| p.starts_with("UID")).unwrap_or("?").to_string();
                    out.push(format!("{} {uid} adapter {:#x} monitor {:#x}", from_wide(&ad.DeviceName), ad.StateFlags.0, mon.StateFlags.0));
                }
            }
        }
    }
    out.join("; ")
}

/// Every monitor on the desktop: (device name, rect).
fn monitors() -> Vec<(String, RECT)> {
    unsafe extern "system" fn each(m: HMONITOR, _: HDC, _: *mut RECT, data: LPARAM) -> windows::core::BOOL {
        let list = unsafe { &mut *(data.0 as *mut Vec<(String, RECT)>) };
        let mut info = MONITORINFOEXW::default();
        info.monitorInfo.cbSize = std::mem::size_of::<MONITORINFOEXW>() as u32;
        if unsafe { GetMonitorInfoW(m, &mut info as *mut MONITORINFOEXW as *mut MONITORINFO) }.as_bool() {
            list.push((from_wide(&info.szDevice), info.monitorInfo.rcMonitor));
        }
        windows::core::BOOL(1)
    }
    let mut list: Vec<(String, RECT)> = Vec::new();
    unsafe {
        let _ = EnumDisplayMonitors(None, None, Some(each), LPARAM(&mut list as *mut _ as isize));
    }
    list
}

fn rect_of(device: &str) -> Option<RECT> {
    monitors().into_iter().find(|(d, _)| d.eq_ignore_ascii_case(device)).map(|(_, r)| r)
}

/// Gives the new monitor its size, refresh rate and a place on the desktop that touches your own
/// monitors only at a corner (so the mouse doesn't wander onto it), next to other in-game ones.
/// The device's own mode closest to w×h at hz (exact size preferred, then the nearest refresh rate).
fn best_mode(name: &[u16], w: u32, h: u32, hz: u32) -> Option<DEVMODEW> {
    let mut best: Option<(i64, DEVMODEW)> = None;
    unsafe {
        let mut i = 0;
        loop {
            let mut dm = DEVMODEW { dmSize: std::mem::size_of::<DEVMODEW>() as u16, ..Default::default() };
            if !EnumDisplaySettingsW(PCWSTR(name.as_ptr()), ENUM_DISPLAY_SETTINGS_MODE(i), &mut dm).as_bool() {
                break;
            }
            i += 1;
            let size = (dm.dmPelsWidth as i64 - w as i64).abs() + (dm.dmPelsHeight as i64 - h as i64).abs();
            let rate = (dm.dmDisplayFrequency as i64 - hz as i64).abs();
            let score = size * 1000 + rate * 10 + if dm.dmBitsPerPel == 32 { 0 } else { 1 };
            if best.as_ref().map_or(true, |(b, _)| score < *b) {
                best = Some((score, dm));
            }
        }
    }
    best.map(|(_, dm)| dm)
}

/// Is that display part of the desktop (not switched off or duplicated away by Windows)?
fn attached(device: &str) -> (bool, u32) {
    unsafe {
        let mut i = 0;
        loop {
            let mut ad = DISPLAY_DEVICEW { cb: std::mem::size_of::<DISPLAY_DEVICEW>() as u32, ..Default::default() };
            if !EnumDisplayDevicesW(PCWSTR::null(), i, &mut ad, 0).as_bool() {
                return (false, 0);
            }
            i += 1;
            if from_wide(&ad.DeviceName).eq_ignore_ascii_case(device) {
                return (ad.StateFlags.0 & DISPLAY_DEVICE_ATTACHED_TO_DESKTOP.0 != 0, ad.StateFlags.0);
            }
        }
    }
}

/// Every size the monitor offers (each once, at the rate closest to 60).
fn sizes(device: &str) -> Vec<(u32, u32, u32)> {
    let name = wide(device);
    let mut out: Vec<(u32, u32, u32)> = Vec::new();
    unsafe {
        let mut i = 0;
        loop {
            let mut dm = DEVMODEW { dmSize: std::mem::size_of::<DEVMODEW>() as u16, ..Default::default() };
            if !EnumDisplaySettingsW(PCWSTR(name.as_ptr()), ENUM_DISPLAY_SETTINGS_MODE(i), &mut dm).as_bool() {
                break;
            }
            i += 1;
            let (w, h, hz) = (dm.dmPelsWidth, dm.dmPelsHeight, dm.dmDisplayFrequency);
            if w < 640 || h < 480 {
                continue;
            }
            match out.iter_mut().find(|m| m.0 == w && m.1 == h) {
                Some(m) => {
                    if (hz as i64 - 60).abs() < (m.2 as i64 - 60).abs() {
                        m.2 = hz;
                    }
                }
                None => out.push((w, h, hz)),
            }
        }
    }
    out.sort_by_key(|m| (m.0 as u64) * (m.1 as u64));
    out
}

/// Lays the in-game monitors out on the desktop like they stand in the world (see the "arrange"
/// command): joined ones edge to edge, each group of them off on its own — touching nothing else
/// but at a corner, so a window only ever drags across monitors that are joined in the game.
static LAST_SPEC: Mutex<String> = Mutex::new(String::new());
static REFUSED: Mutex<Option<(String, Vec<Option<(i32, i32)>>)>> = Mutex::new(None);

/// After Windows turned a new monitor on (it shuffles the others around when it does): the
/// last layout again, the new one off on its own.
fn rearrange_with(added: &AddedMap, idx: i32) {
    let last = LAST_SPEC.lock().unwrap().clone();
    let mut spec: Vec<String> = last.split_whitespace().filter(|e| !e.starts_with(&format!("{idx}:"))).map(|e| e.to_string()).collect();
    for i in added.lock().unwrap().keys() {
        if !spec.iter().any(|e| e.starts_with(&format!("{i}:"))) {
            spec.push(format!("{i}:-1:-"));
        }
    }
    if let Err(why) = arrange(added, &spec.join(" ")) {
        eprintln!("info=couldn't lay the monitors out again: {why}");
    }
}

fn arrange(added: &AddedMap, spec: &str) -> Result<(), String> {
    *LAST_SPEC.lock().unwrap() = spec.to_string();
    let ours: Vec<String> = added.lock().unwrap().values().map(|a| a.device.clone()).filter(|d| !d.is_empty()).collect();
    let mut size: HashMap<i32, (String, i32, i32)> = HashMap::new();
    let mut links: Vec<(i32, i32, char)> = Vec::new();
    for e in spec.split_whitespace() {
        let f: Vec<&str> = e.split(':').collect();
        let (Some(i), Some(p)) = (f.first().and_then(|x| x.parse::<i32>().ok()), f.get(1).and_then(|x| x.parse::<i32>().ok())) else { continue };
        let side = f.get(2).and_then(|x| x.chars().next()).unwrap_or('-');
        if let Some((device, r)) = our_area(added, i) {
            size.insert(i, (device, r.right - r.left, r.bottom - r.top));
            links.push((i, p, side));
        }
    }
    if size.is_empty() {
        return Ok(());
    }
    // your own screens: the groups start off their bottom-right corner
    let (mut cx, mut cy) = (0i32, 0i32);
    for (d, r) in monitors() {
        if !ours.iter().any(|o| o.eq_ignore_ascii_case(&d)) {
            cx = cx.max(r.right);
            cy = cy.max(r.bottom);
        }
    }
    let overlaps = |a: &RECT, b: &RECT| a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    let mut groups: Vec<Vec<(i32, RECT)>> = Vec::new();
    let mut done: HashMap<i32, usize> = HashMap::new();
    let mut roots: Vec<i32> = links.iter().filter(|l| l.1 < 0 || !size.contains_key(&l.1)).map(|l| l.0).collect();
    let mut k = 0;
    while k < roots.len() {
        let root = roots[k];
        k += 1;
        if done.contains_key(&root) {
            continue;
        }
        let (_, w, h) = size[&root];
        let mut g = vec![(root, RECT { left: 0, top: 0, right: w, bottom: h })];
        done.insert(root, groups.len());
        let mut q = 0;
        while q < g.len() {
            let (pi, pr) = g[q];
            q += 1;
            for &(ci, parent, side) in &links {
                if parent != pi || done.contains_key(&ci) {
                    continue;
                }
                let (_, w, h) = size[&ci];
                let r = match side {
                    'R' => RECT { left: pr.right, top: pr.top, right: pr.right + w, bottom: pr.top + h },
                    'L' => RECT { left: pr.left - w, top: pr.top, right: pr.left, bottom: pr.top + h },
                    'T' => RECT { left: pr.left, top: pr.top - h, right: pr.left + w, bottom: pr.top },
                    _ => RECT { left: pr.left, top: pr.bottom, right: pr.left + w, bottom: pr.bottom + h },
                };
                if g.iter().any(|(_, o)| overlaps(o, &r)) {
                    roots.push(ci); // (no room there: it stands on its own)
                    continue;
                }
                done.insert(ci, groups.len());
                g.push((ci, r));
            }
        }
        groups.push(g);
    }
    for &(i, _, _) in &links {
        if !done.contains_key(&i) {
            let (_, w, h) = size[&i];
            done.insert(i, groups.len());
            groups.push(vec![(i, RECT { left: 0, top: 0, right: w, bottom: h })]);
        }
    }
    {
        let mut map: HashMap<i32, Vec<i32>> = HashMap::new();
        for g in &groups {
            let ids: Vec<i32> = g.iter().map(|(i, _)| *i).collect();
            for i in &ids {
                map.insert(*i, ids.clone());
            }
        }
        *GROUPS.lock().unwrap() = Some(map);
    }
    let mut want: Vec<(String, i32, i32)> = Vec::new();
    for g in &groups {
        let minx = g.iter().map(|(_, r)| r.left).min().unwrap_or(0);
        let miny = g.iter().map(|(_, r)| r.top).min().unwrap_or(0);
        let (dx, dy) = (cx - minx, cy - miny);
        let (mut maxx, mut maxy) = (cx, cy);
        for (i, r) in g {
            want.push((size[i].0.clone(), r.left + dx, r.top + dy));
            maxx = maxx.max(r.right + dx);
            maxy = maxy.max(r.bottom + dy);
        }
        cx = maxx;
        cy = maxy;
    }
    // already like that: nothing to do (moving monitors restarts their pictures)
    if want.iter().all(|(d, x, y)| rect_of(d).map_or(false, |r| r.left == *x && r.top == *y)) {
        return Ok(());
    }
    // asked for this layout before and Windows put them its own way: don't fight it every few
    // seconds (each try makes the screens blink)
    let now_at = |w: &Vec<(String, i32, i32)>| w.iter().map(|(d, _, _)| rect_of(d).map(|r| (r.left, r.top))).collect::<Vec<_>>();
    if let Some((s0, got)) = REFUSED.lock().unwrap().as_ref() {
        if s0 == spec && *got == now_at(&want) {
            return Ok(());
        }
    }
    unsafe {
        for (d, x, y) in &want {
            let mut p = DEVMODEW { dmSize: std::mem::size_of::<DEVMODEW>() as u16, ..Default::default() };
            p.dmFields = DM_POSITION;
            p.Anonymous1.Anonymous2.dmPosition = POINTL { x: *x, y: *y };
            let dn = wide(d);
            let r = ChangeDisplaySettingsExW(PCWSTR(dn.as_ptr()), Some(&p), None, CDS_UPDATEREGISTRY | CDS_NORESET, None);
            if r != DISP_CHANGE_SUCCESSFUL {
                let _ = ChangeDisplaySettingsExW(PCWSTR::null(), None, None, CDS_TYPE(0), None);
                return Err(format!("Windows refused to move {d} ({})", r.0));
            }
        }
        let applied = ChangeDisplaySettingsExW(PCWSTR::null(), None, None, CDS_TYPE(0), None);
        if applied != DISP_CHANGE_SUCCESSFUL {
            return Err(format!("Windows didn't apply it ({})", applied.0));
        }
    }
    let list: Vec<String> = want.iter().map(|(d, x, y)| format!("{d}@{x},{y}")).collect();
    eprintln!("info=arranged the in-game monitors: {}", list.join(" "));
    std::thread::sleep(Duration::from_millis(300));
    let got = now_at(&want);
    if want.iter().zip(got.iter()).any(|((_, x, y), g)| *g != Some((*x, *y))) {
        eprintln!("info=Windows placed them differently ({got:?}); leaving it like that");
        *REFUSED.lock().unwrap() = Some((spec.to_string(), got));
    }
    Ok(())
}

/// Windows' "Scale" (Settings › Display) for that monitor, chosen from its height: everything —
/// text, apps, the cursor — stays the same size whatever resolution you pick (a 4K in-game
/// monitor is a sharper 1080p one, not one with a tiny cursor). Best effort; says what it did.
fn fit_scale(device: &str, h: u32) -> String {
    use windows::Win32::Devices::Display::*;
    const SCALES: [u32; 12] = [100, 125, 150, 175, 200, 225, 250, 300, 350, 400, 450, 500];
    #[repr(C)]
    struct DpiGet { header: DISPLAYCONFIG_DEVICE_INFO_HEADER, min_rel: i32, cur_rel: i32, max_rel: i32 }
    #[repr(C)]
    struct DpiSet { header: DISPLAYCONFIG_DEVICE_INFO_HEADER, rel: i32 }
    let want_pct = (h as f64 / 1080.0 * 100.0).max(100.0);
    let want = SCALES.iter().enumerate().min_by(|a, b| (*a.1 as f64 - want_pct).abs().total_cmp(&(*b.1 as f64 - want_pct).abs())).map_or(0, |(i, _)| i) as i32;
    unsafe {
        let (mut np, mut nm) = (0u32, 0u32);
        if GetDisplayConfigBufferSizes(QDC_ONLY_ACTIVE_PATHS, &mut np, &mut nm) != ERROR_SUCCESS {
            return "scale: no display paths".into();
        }
        let mut paths = vec![DISPLAYCONFIG_PATH_INFO::default(); np as usize];
        let mut modes = vec![DISPLAYCONFIG_MODE_INFO::default(); nm as usize];
        if QueryDisplayConfig(QDC_ONLY_ACTIVE_PATHS, &mut np, paths.as_mut_ptr(), &mut nm, modes.as_mut_ptr(), None) != ERROR_SUCCESS {
            return "scale: couldn't read the display paths".into();
        }
        for path in &paths[..np as usize] {
            let mut name = DISPLAYCONFIG_SOURCE_DEVICE_NAME::default();
            name.header.r#type = DISPLAYCONFIG_DEVICE_INFO_GET_SOURCE_NAME;
            name.header.size = std::mem::size_of::<DISPLAYCONFIG_SOURCE_DEVICE_NAME>() as u32;
            name.header.adapterId = path.sourceInfo.adapterId;
            name.header.id = path.sourceInfo.id;
            if DisplayConfigGetDeviceInfo(&mut name.header) != 0 {
                continue;
            }
            let n = name.viewGdiDeviceName.iter().position(|c| *c == 0).unwrap_or(32);
            if !String::from_utf16_lossy(&name.viewGdiDeviceName[..n]).eq_ignore_ascii_case(device) {
                continue;
            }
            let mut get = DpiGet { header: name.header, min_rel: 0, cur_rel: 0, max_rel: 0 };
            get.header.r#type = DISPLAYCONFIG_DEVICE_INFO_TYPE(-3); // (get DPI scale: undocumented, what Settings uses)
            get.header.size = std::mem::size_of::<DpiGet>() as u32;
            if DisplayConfigGetDeviceInfo(&mut get.header) != 0 {
                return "scale: Windows didn't say".into();
            }
            let rec = -get.min_rel; // (its recommended scale, as an index into SCALES)
            let target = want.min(rec + get.max_rel).max(0);
            if rec + get.cur_rel == target {
                return format!("scale {}% already", SCALES[target as usize]);
            }
            let mut set = DpiSet { header: get.header, rel: target - rec };
            set.header.r#type = DISPLAYCONFIG_DEVICE_INFO_TYPE(-4);
            set.header.size = std::mem::size_of::<DpiSet>() as u32;
            let r = DisplayConfigSetDeviceInfo(&set.header);
            return if r == 0 { format!("scale {}%", SCALES[target as usize]) } else { format!("scale: Windows refused ({r})") };
        }
    }
    "scale: monitor not found".into()
}

/// Another size for a monitor that's on, where it is.
fn set_mode(device: &str, area: RECT, w: u32, h: u32, hz: u32, ours: &[String]) -> Result<RECT, String> {
    let name = wide(device);
    let Some(mut m) = best_mode(&name, w, h, hz) else { return Err("it offers no sizes".into()) };
    if m.dmPelsWidth != w || m.dmPelsHeight != h {
        return Err(format!("{w}×{h} isn't one of its sizes"));
    }
    m.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_BITSPERPEL | DM_DISPLAYFREQUENCY | DM_POSITION;
    m.Anonymous1.Anonymous2.dmPosition = POINTL { x: area.left, y: area.top };
    // in-game monitors stand in a row: the ones to its right move along with its new width, so
    // they neither overlap it nor leave a gap (Windows would shuffle them around itself)
    let dx = w as i32 - (area.right - area.left);
    unsafe {
        let r = ChangeDisplaySettingsExW(PCWSTR(name.as_ptr()), Some(&m), None, CDS_UPDATEREGISTRY | CDS_NORESET, None);
        if r != DISP_CHANGE_SUCCESSFUL {
            let _ = ChangeDisplaySettingsExW(PCWSTR::null(), None, None, CDS_TYPE(0), None);
            return Err(format!("Windows refused it ({})", r.0));
        }
        if dx != 0 {
            for (d, o) in monitors() {
                if d.eq_ignore_ascii_case(device) || !ours.iter().any(|x| x.eq_ignore_ascii_case(&d)) || o.left < area.right {
                    continue;
                }
                let mut p = DEVMODEW { dmSize: std::mem::size_of::<DEVMODEW>() as u16, ..Default::default() };
                p.dmFields = DM_POSITION;
                p.Anonymous1.Anonymous2.dmPosition = POINTL { x: o.left + dx, y: o.top };
                let dn = wide(&d);
                let _ = ChangeDisplaySettingsExW(PCWSTR(dn.as_ptr()), Some(&p), None, CDS_UPDATEREGISTRY | CDS_NORESET, None);
            }
        }
        let applied = ChangeDisplaySettingsExW(PCWSTR::null(), None, None, CDS_TYPE(0), None);
        if applied != DISP_CHANGE_SUCCESSFUL {
            return Err(format!("Windows didn't apply it ({})", applied.0));
        }
    }
    let deadline = Instant::now() + Duration::from_secs(4);
    while Instant::now() < deadline {
        if let Some(r) = rect_of(device) {
            if (r.right - r.left) as u32 == w && (r.bottom - r.top) as u32 == h {
                return Ok(r);
            }
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    Err(format!("Windows didn't switch it to {w}×{h}"))
}

fn place(device: &str, w: u32, h: u32, hz: u32, ours: &[String]) -> Result<RECT, String> {
    let all = monitors();
    let (mut right, mut bottom) = (i32::MIN, i32::MIN);
    let mut next_to: Option<RECT> = None;
    let mut ours_bottom = i32::MIN;
    for (d, r) in &all {
        if d.eq_ignore_ascii_case(device) {
            continue;
        }
        if ours.iter().any(|o| o.eq_ignore_ascii_case(d)) {
            // in-game monitors line up in a row, each to the right of the last
            if next_to.map_or(true, |n| r.right > n.right) {
                next_to = Some(*r);
            }
            ours_bottom = ours_bottom.max(r.bottom);
        } else {
            right = right.max(r.right);
            bottom = bottom.max(r.bottom);
        }
    }
    if right == i32::MIN {
        right = 0;
        bottom = 0;
    }
    // where it can go, best first: next to the last in-game monitor (or diagonally off your
    // screens' corner for the first), then other spots that never touch your real screens' edges
    let mut spots: Vec<(i32, i32)> = Vec::new();
    match next_to {
        Some(n) => {
            // off the corner of everything (it touches no other monitor's edge, so no window drags
            // onto it by accident — joined ones are laid out together afterwards, see arrange)
            let ar = all.iter().filter(|(d, _)| !d.eq_ignore_ascii_case(device)).map(|(_, r)| r.right).max().unwrap_or(right);
            let ab = all.iter().filter(|(d, _)| !d.eq_ignore_ascii_case(device)).map(|(_, r)| r.bottom).max().unwrap_or(bottom);
            spots.push((ar, ab));
            spots.push((n.right, n.top));
            spots.push((n.right, n.bottom));
            spots.push((right, ours_bottom));
        }
        None => spots.push((right, bottom)),
    }
    let name = wide(device);
    let (att, flags) = attached(device);
    let mode = best_mode(&name, w, h, hz);
    eprintln!("info=placing {device}: attached {att} (flags {flags:#x}), mode {}",
        mode.map_or("none".to_string(), |m| format!("{}x{}@{} {}bpp", m.dmPelsWidth, m.dmPelsHeight, m.dmDisplayFrequency, m.dmBitsPerPel)));
    let mut placed = false;
    for round in 0..2 {
    if round == 1 {
        if placed {
            break;
        }
        extend_all(ours, device);
    }
    unsafe {
        'spots: for &(x, y) in &spots {
            // its own exact mode first (all fields: needed to switch on a display that isn't on yet), then simpler asks
            let mut tries: Vec<DEVMODEW> = Vec::new();
            if let Some(mut m) = mode {
                m.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_BITSPERPEL | DM_DISPLAYFREQUENCY | DM_POSITION;
                m.Anonymous1.Anonymous2.dmPosition = POINTL { x, y };
                tries.push(m);
                let mut m2 = m;
                m2.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_POSITION;
                tries.push(m2);
            }
            let mut dm = DEVMODEW { dmSize: std::mem::size_of::<DEVMODEW>() as u16, ..Default::default() };
            dm.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_DISPLAYFREQUENCY | DM_POSITION;
            dm.dmPelsWidth = w;
            dm.dmPelsHeight = h;
            dm.dmDisplayFrequency = hz;
            dm.Anonymous1.Anonymous2.dmPosition = POINTL { x, y };
            tries.push(dm);
            let mut pos = dm;
            pos.dmFields = DM_POSITION;
            tries.push(pos);
            let mut codes = Vec::new();
            for t in &tries {
                let r = ChangeDisplaySettingsExW(PCWSTR(name.as_ptr()), Some(t), None, CDS_UPDATEREGISTRY | CDS_NORESET, None);
                codes.push(r.0);
                if r == DISP_CHANGE_SUCCESSFUL {
                    placed = true;
                    if (x, y) != spots[0] {
                        eprintln!("info=placed it at {x},{y} instead");
                    }
                    break 'spots;
                }
            }
            eprintln!("error=couldn't place the monitor at {x},{y} ({codes:?})");
        }
        let _ = ChangeDisplaySettingsExW(PCWSTR::null(), None, None, CDS_TYPE(0), None);
    }
    }
    if !placed {
        let list: Vec<String> = monitors().iter().map(|(d, r)| format!("{d} {},{} {}x{}", r.left, r.top, r.right - r.left, r.bottom - r.top)).collect();
        eprintln!("info=screens now: {}", list.join("; "));
        eprintln!("info=Windows' list of in-game monitors: {}", vdd_listing());
    }
    // it must be a monitor of its own: if Windows mirrors it onto one of yours (duplicate
    // displays), capturing it would show your real screen — never allowed
    let deadline = Instant::now() + Duration::from_secs(3);
    loop {
        if let Some(r) = rect_of(device) {
            let overlaps = monitors().iter().any(|(d, o)| {
                !d.eq_ignore_ascii_case(device) && !ours.iter().any(|x| x.eq_ignore_ascii_case(d))
                    && o.left < r.right && r.left < o.right && o.top < r.bottom && r.top < o.bottom
            });
            if !overlaps && r.right > r.left && r.bottom > r.top {
                return Ok(r);
            }
        }
        if Instant::now() > deadline {
            let where_ = rect_of(device).map_or("not on the desktop".to_string(), |r| format!("at {},{} {}x{}", r.left, r.top, r.right - r.left, r.bottom - r.top));
            eprintln!("error=the new monitor {device} ended up {where_}; attached {:?}", attached(device));
            let on = monitors().len();
            return Err(if rect_of(device).is_none() && on >= 4 {
                format!("your graphics card can't show another screen: {on} are on already, your own monitor included (most can show at most 4) — turn an in-game monitor or tablet off first")
            } else if rect_of(device).is_none() {
                "Windows didn't switch the new monitor on (Settings → System → Display: is it set to \"Disconnect\" or \"Show only on 1\"?)".into()
            } else {
                "Windows put the new monitor on top of one of your screens (Settings → System → Display: set it to \"Extend these displays\")".into()
            });
        }
        std::thread::sleep(Duration::from_millis(50));
    }
}

/// Windows wouldn't switch the new monitor on by itself: ask it to "extend" onto every connected
/// screen (what Win + P → Extend does) — then switch off again any screen of yours that was off
/// before (it was off on purpose).
fn extend_all(ours: &[String], device: &str) {
    let before: Vec<String> = monitors().into_iter().map(|(d, _)| d).collect();
    eprintln!("info=asking Windows to extend onto all screens (it didn't switch {device} on by itself)");
    let r = unsafe {
        windows::Win32::Devices::Display::SetDisplayConfig(None, None,
            windows::Win32::Devices::Display::SDC_TOPOLOGY_EXTEND | windows::Win32::Devices::Display::SDC_APPLY)
    };
    std::thread::sleep(Duration::from_millis(1500));
    for (d, _) in monitors() {
        let mine = d.eq_ignore_ascii_case(device) || ours.iter().any(|o| o.eq_ignore_ascii_case(&d));
        if !mine && !before.iter().any(|b| b.eq_ignore_ascii_case(&d)) {
            eprintln!("info={d} was off before: switching it off again");
            let name = wide(&d);
            let mut off = DEVMODEW { dmSize: std::mem::size_of::<DEVMODEW>() as u16, ..Default::default() };
            off.dmFields = DM_PELSWIDTH | DM_PELSHEIGHT | DM_POSITION;
            unsafe {
                let _ = ChangeDisplaySettingsExW(PCWSTR(name.as_ptr()), Some(&off), None, CDS_UPDATEREGISTRY | CDS_NORESET, None);
                let _ = ChangeDisplaySettingsExW(PCWSTR::null(), None, None, CDS_TYPE(0), None);
            }
        }
    }
    eprintln!("info=extend: {r}, {device} now {:?}", attached(device));
}

// ---- windows on a monitor -----------------------------------------------------------------

fn cloaked(h: HWND) -> bool {
    let mut v: u32 = 0;
    unsafe { DwmGetWindowAttribute(h, DWMWA_CLOAKED, &mut v as *mut u32 as *mut _, 4).is_ok() && v != 0 }
}

fn class_name(h: HWND) -> String {
    let mut buf = [0u16; 128];
    let n = unsafe { GetClassNameW(h, &mut buf) } as usize;
    String::from_utf16_lossy(&buf[..n.min(buf.len())])
}

fn shell_pid() -> u32 {
    let s = unsafe { GetShellWindow() };
    if s.0.is_null() { 0 } else { pid(s) }
}

/// Ordinary app windows (visible or minimized, not tool windows, never the desktop or the
/// taskbar), front to back.
fn app_windows() -> Vec<HWND> {
    unsafe extern "system" fn each(h: HWND, data: LPARAM) -> windows::core::BOOL {
        let list = unsafe { &mut *(data.0 as *mut Vec<HWND>) };
        unsafe {
            if IsWindowVisible(h).as_bool() && GetWindow(h, GW_OWNER).map_or(true, |o| o.0.is_null()) {
                let ex = GetWindowLongW(h, GWL_EXSTYLE) as u32;
                if ex & WS_EX_TOOLWINDOW.0 == 0 && !cloaked(h) && GetWindowTextLengthW(h) > 0 && h != GetShellWindow() {
                    let c = class_name(h);
                    if !matches!(c.as_str(), "Progman" | "WorkerW" | "Shell_TrayWnd" | "Shell_SecondaryTrayWnd") {
                        list.push(h);
                    }
                }
            }
        }
        windows::core::BOOL(1)
    }
    let mut list = Vec::new();
    unsafe {
        let _ = EnumWindows(Some(each), LPARAM(&mut list as *mut _ as isize));
    }
    list
}

fn placement(h: HWND) -> Option<WINDOWPLACEMENT> {
    let mut p = WINDOWPLACEMENT { length: std::mem::size_of::<WINDOWPLACEMENT>() as u32, ..Default::default() };
    unsafe { GetWindowPlacement(h, &mut p).ok().map(|_| p) }
}

/// Is this window on that area? Its middle must be, and most of it. (A minimized one: where it'd come back.)
fn on_area(h: HWND, area: &RECT) -> bool {
    let r = unsafe {
        if IsIconic(h).as_bool() {
            match placement(h) {
                Some(p) => p.rcNormalPosition,
                None => return false,
            }
        } else {
            let mut r = RECT::default();
            if GetWindowRect(h, &mut r).is_err() {
                return false;
            }
            r
        }
    };
    let (cx, cy) = ((r.left + r.right) / 2, (r.top + r.bottom) / 2);
    if !(cx >= area.left && cx < area.right && cy >= area.top && cy < area.bottom) {
        return false;
    }
    let w = (r.right.min(area.right) - r.left.max(area.left)).max(0) as i64;
    let hgt = (r.bottom.min(area.bottom) - r.top.max(area.top)).max(0) as i64;
    let total = ((r.right - r.left).max(1) as i64) * ((r.bottom - r.top).max(1) as i64);
    w * hgt * 2 >= total
}

fn title(h: HWND) -> String {
    // (read as Windows keeps it, without asking the window: an app that's stuck can't hold us up)
    let mut buf = [0u16; 256];
    let n = unsafe { windows::Win32::UI::WindowsAndMessaging::InternalGetWindowText(h, &mut buf) } as usize;
    String::from_utf16_lossy(&buf[..n.min(buf.len())]).replace(['\n', '\r'], " ")
}

fn pid(h: HWND) -> u32 {
    let mut p = 0u32;
    unsafe { GetWindowThreadProcessId(h, Some(&mut p)) };
    p
}

/// The part of that monitor apps use (without its taskbar).
fn work_area(area: &RECT) -> RECT {
    unsafe {
        let m = MonitorFromRect(area, MONITOR_DEFAULTTONEAREST);
        let mut mi = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
        if GetMonitorInfoW(m, &mut mi).as_bool() && mi.rcWork.right > mi.rcWork.left {
            mi.rcWork
        } else {
            *area
        }
    }
}

static SCAN_CLIP: Mutex<Option<RECT>> = Mutex::new(None);
static SCANNER: AtomicBool = AtomicBool::new(false);

/// While you sit at a monitor: a few times a second, windows that slipped past the window events
/// (never on the thread that carries your mouse and keyboard).
fn ensure_seat_scanner() {
    if SCANNER.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(|| {
        let mut checked: std::collections::HashSet<isize> = std::collections::HashSet::new();
        while SEATED.load(Ordering::SeqCst) {
            std::thread::sleep(Duration::from_millis(150));
            let Some(clip) = *SCAN_CLIP.lock().unwrap() else { continue };
            let known = seat_known().lock().unwrap().clone();
            let t0 = Instant::now();
            for h in app_windows() {
                let k = h.0 as isize;
                if known.contains(&k) || checked.contains(&k) {
                    continue;
                }
                unsafe {
                    // (one Windows didn't tell us about — some Store apps: Calculator… — is new too)
                    if !IsIconic(h).as_bool() && !on_area(h, &clip) {
                        on_window_shown(h, true);
                    }
                }
                checked.insert(k); // (each window looked at once: not again and again)
            }
            if t0.elapsed() > Duration::from_millis(50) {
                eprintln!("info=looking for new windows took {} ms", t0.elapsed().as_millis());
            }
            if checked.len() > 5000 {
                checked.clear();
            }
        }
        *SCAN_CLIP.lock().unwrap() = None;
        SCANNER.store(false, Ordering::SeqCst);
    });
}

/// Puts a window onto a monitor at its own size, like Windows does on your desk: a maximized
/// one stays maximized (there), any other keeps its size (never bigger than the screen), a
/// little down and right of the last one, without activating it (the game keeps your keyboard).
fn place_window(h: HWND, area: &RECT) {
    let work = work_area(area);
    let (aw, ah) = (work.right - work.left, work.bottom - work.top);
    unsafe {
        let maximized = IsZoomed(h).as_bool();
        let pl = placement(h);
        let mut r = RECT::default();
        let _ = GetWindowRect(h, &mut r);
        let normal = if maximized { pl.map(|p| p.rcNormalPosition).unwrap_or(r) } else { r };
        let w = (normal.right - normal.left).min(aw * 9 / 10).max(200.min(aw));
        let hh = (normal.bottom - normal.top).min(ah * 9 / 10).max(150.min(ah));
        // maximizing activates the window: only while you sit there (otherwise it'd take the
        // game's keyboard); then it keeps its normal size until you maximize it yourself
        let maximized = maximized && SEATED.load(Ordering::SeqCst);
        // cascade: each window already there moves the next one a step down and right
        let n = app_windows().into_iter().filter(|x| *x != h && on_area(*x, area)).count() as i32;
        let step = 32 * (n % 6);
        let left = (work.left + (aw - w) / 2 - 80 + step).clamp(work.left, work.right - w);
        let top = (work.top + (ah - hh) / 2 - 80 + step).clamp(work.top, work.bottom - hh);
        let spot = RECT { left, top, right: left + w, bottom: top + hh };
        // first onto the monitor at its normal size (Windows won't move a maximized window by its
        // placement alone: it stays maximized wherever it is), then maximized there if it was
        if admin_window(h) {
            // an app running as administrator (that you started that way): Windows doesn't let a
            // program with normal rights move it — it stays where it is (no permission prompts)
            if ADMIN_TOLD.lock().unwrap().insert(pid(h)) {
                eprintln!("info=\"{}\" runs as administrator: Windows doesn't let other programs move it", title(h));
                emit("adminapp");
            }
            return;
        }
        apply_place(h, &spot, maximized);
    }
}

/// Moves a window to that spot (normal size there), maximized after if asked.
fn apply_place(h: HWND, spot: &RECT, maximized: bool) {
    unsafe {
        if let Some(mut p) = placement(h) {
            p.flags = WINDOWPLACEMENT_FLAGS(0);
            p.rcNormalPosition = *spot;
            p.showCmd = SW_SHOWNOACTIVATE.0 as u32;
            let _ = SetWindowPlacement(h, &p);
        }
        let _ = SetWindowPos(h, None, spot.left, spot.top, spot.right - spot.left, spot.bottom - spot.top,
            SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_ASYNCWINDOWPOS);
        if maximized {
            let _ = ShowWindowAsync(h, SW_MAXIMIZE);
        }
    }
}

// ---- admin apps (Task Manager…): moved by a small helper with the same rights ----------------

static ME_ADMIN: std::sync::LazyLock<bool> = std::sync::LazyLock::new(|| unsafe {
    let mut t = HANDLE::default();
    if OpenProcessToken(GetCurrentProcess(), windows::Win32::Security::TOKEN_QUERY, &mut t).is_err() {
        return false;
    }
    let e = token_elevated(t);
    let _ = CloseHandle(t);
    e.unwrap_or(false)
});

unsafe fn token_elevated(t: HANDLE) -> Option<bool> {
    let mut el = windows::Win32::Security::TOKEN_ELEVATION::default();
    let mut n = 0u32;
    unsafe {
        windows::Win32::Security::GetTokenInformation(t, windows::Win32::Security::TokenElevation, Some(&mut el as *mut _ as *mut _),
            std::mem::size_of::<windows::Win32::Security::TOKEN_ELEVATION>() as u32, &mut n).ok()?;
    }
    Some(el.TokenIsElevated != 0)
}

/// Is that window an app running as administrator, while this helper isn't (so Windows won't
/// let us move it)?
fn admin_window(h: HWND) -> bool {
    if *ME_ADMIN {
        return false;
    }
    let p = pid(h);
    unsafe {
        let Ok(proc_) = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, p) else { return true };
        let mut t = HANDLE::default();
        let r = if OpenProcessToken(proc_, windows::Win32::Security::TOKEN_QUERY, &mut t).is_err() {
            true // (we may not even look: it's above us)
        } else {
            let e = token_elevated(t).unwrap_or(false);
            let _ = CloseHandle(t);
            e
        };
        let _ = CloseHandle(proc_);
        r
    }
}

static ADMIN_TOLD: std::sync::LazyLock<Mutex<std::collections::HashSet<u32>>> = std::sync::LazyLock::new(|| Mutex::new(std::collections::HashSet::new()));

/// Where a window goes when it comes back to your desk without a remembered place: your main
/// monitor, same size (shrunk to fit), centered.
fn desk_spot(h: HWND) -> RECT {
    let primary = monitors()
        .into_iter()
        .find(|(_, r)| r.left <= 0 && r.right > 0 && r.top <= 0 && r.bottom > 0)
        .map(|(_, r)| r)
        .unwrap_or(RECT { left: 0, top: 0, right: 1920, bottom: 1080 });
    let mut r = placement(h).map(|p| p.rcNormalPosition).unwrap_or(primary);
    let (pw, ph) = (primary.right - primary.left, primary.bottom - primary.top);
    let w = (r.right - r.left).clamp(200, pw);
    let hgt = (r.bottom - r.top).clamp(150, ph);
    r.left = primary.left + (pw - w) / 2;
    r.top = primary.top + (ph - hgt) / 2;
    r.right = r.left + w;
    r.bottom = r.top + hgt;
    r
}

// ---- sitting at an in-game monitor: the real mouse and keyboard go to it ------------------------
//
// Safety first: the helper gives the mouse and keyboard back by itself on Esc, when the game
// stops saying it's there ("beat"), when you switch to something that isn't on the monitor
// (Task Manager, Ctrl+Alt+Del, a permission prompt), or when the monitor goes away. The hooks
// never write to the game directly: they hand events to a writer thread.

static SEATED: AtomicBool = AtomicBool::new(false);
static LAST_IOCTL: Mutex<String> = Mutex::new(String::new());
static IOCTL_GATE: Mutex<()> = Mutex::new(());
static PING_FAILS: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
static SERIAL: std::sync::LazyLock<bool> = std::sync::LazyLock::new(|| std::env::var("WC_VDD_SERIAL").map_or(false, |v| v == "1"));
static COOLDOWN_MS: std::sync::LazyLock<u64> = std::sync::LazyLock::new(|| std::env::var("WC_VDD_COOLDOWN_MS").ok().and_then(|v| v.parse().ok()).unwrap_or(1500));
static PING_MS: std::sync::LazyLock<u64> = std::sync::LazyLock::new(|| std::env::var("WC_VDD_PING_MS").ok().and_then(|v| v.parse().ok()).unwrap_or(50));
/// When a monitor was last added or removed (the driver needs a moment between them).
static LAST_DRIVER_CHANGE: Mutex<Option<Instant>> = Mutex::new(None);
/// When you last used the Start menu or taskbar of the monitor you sit at.
static SEAT_SHELL_AT: Mutex<Option<Instant>> = Mutex::new(None);
static ALT: AtomicBool = AtomicBool::new(false);
static ESC_HELD: AtomicBool = AtomicBool::new(false);
static SEAT_DEVICE: Mutex<String> = Mutex::new(String::new());
static SEAT_IDX: AtomicI32 = AtomicI32::new(-1);
static CLIP: Mutex<RECT> = Mutex::new(RECT { left: 0, top: 0, right: 0, bottom: 0 });
/// Where the cursor may go while you sit: the monitor, and every monitor joined to it in the game.
static SEAT_CLIP: Mutex<RECT> = Mutex::new(RECT { left: 0, top: 0, right: 0, bottom: 0 });
/// Moving to a joined monitor: when it happened, and the moment after it the cursor stays there.
static SWITCHED_AT: Mutex<Option<Instant>> = Mutex::new(None);
static NARROWED: AtomicBool = AtomicBool::new(false);
const SWITCH_REST: Duration = Duration::from_millis(1200);
/// …and how far (pixels) onto the other monitor the cursor has to go, and how long it has to stay
/// there, before you move to it.
const SWITCH_DEPTH: i32 = 120;
const SWITCH_DWELL: Duration = Duration::from_millis(220);
static SWITCH_CANDIDATE: Mutex<Option<(i32, Instant)>> = Mutex::new(None);
/// Joined monitors (from "arrange"): each index → every index of its group (itself included).
static GROUPS: Mutex<Option<HashMap<i32, Vec<i32>>>> = Mutex::new(None);

/// The box around that monitor and the ones joined to it (None: it isn't on).
fn group_area(idx: i32) -> Option<RECT> {
    let members = GROUPS.lock().unwrap().as_ref().and_then(|g| g.get(&idx).cloned()).unwrap_or_else(|| vec![idx]);
    let mut out: Option<RECT> = None;
    for m in members {
        if let Some(r) = monitor_area(m) {
            out = Some(match out {
                None => r,
                Some(o) => RECT { left: o.left.min(r.left), top: o.top.min(r.top), right: o.right.max(r.right), bottom: o.bottom.max(r.bottom) },
            });
        }
    }
    out
}

/// Are these two joined (in the same group)?
fn joined(a: i32, b: i32) -> bool {
    a == b || GROUPS.lock().unwrap().as_ref().and_then(|g| g.get(&a)).map_or(false, |m| m.contains(&b))
}
static SAVED: Mutex<POINT> = Mutex::new(POINT { x: 0, y: 0 });
static BEAT: Mutex<Option<Instant>> = Mutex::new(None);
static SEAT_START: Mutex<Option<Instant>> = Mutex::new(None);
static GAME: AtomicIsize = AtomicIsize::new(0);
static HOOK_THREAD: AtomicI32 = AtomicI32::new(0);
static HOOKS_OK: AtomicI32 = AtomicI32::new(0); // 0 starting, 1 ok, -1 failed
static SINK: AtomicIsize = AtomicIsize::new(0);
static LOOK_X: AtomicI32 = AtomicI32::new(0);
static LOOK_Y: AtomicI32 = AtomicI32::new(0);
/// Pointing: your mouse's moves as the hook saw them (held back from the cursor), and whether raw
/// input arrives too (then that's what turns the view, not these).
static LOOK_HX: AtomicI32 = AtomicI32::new(0);
static LOOK_HY: AtomicI32 = AtomicI32::new(0);
static RAW_SEEN: AtomicBool = AtomicBool::new(false);
/// Pointing, after a click on the monitor: the game's title bar keeps looking active.
static KEEP_GAME_LOOK: AtomicBool = AtomicBool::new(false);
static LAST_FG: AtomicIsize = AtomicIsize::new(0);
/// Seated: keys pressed with Alt held (kept for the game): their releases are kept too.
static KEYS_KEPT: Mutex<Vec<u32>> = Mutex::new(Vec::new());
/// Keys handed to the game that way and still held (let go of when pointing ends).
static GAME_KEYS: Mutex<Vec<u32>> = Mutex::new(Vec::new());

fn release_game_keys() {
    let held: Vec<u32> = std::mem::take(&mut *GAME_KEYS.lock().unwrap());
    for vk in held {
        emit(&format!("gkey {vk} 0 0"));
    }
}
static EVENTS: OnceLock<Mutex<Sender<String>>> = OnceLock::new();

// pointing: the real cursor on a monitor while the game keeps the keyboard
static POINTING: AtomicBool = AtomicBool::new(false);
static POINT_ME: AtomicBool = AtomicBool::new(false);
static ALT_SWALLOW: AtomicBool = AtomicBool::new(false);
static TARGET: Mutex<Option<POINT>> = Mutex::new(None);
static POINT_LOCK: Mutex<()> = Mutex::new(());
const NO: AtomicBool = AtomicBool::new(false);
/// Per mouse button (left, right, middle, back, forward), while pointing: your press reached the
/// monitor (so must its release) / your press went to the game (so must its release)
static PASSED: [AtomicBool; 5] = [NO; 5];
static SWALLOWED: [AtomicBool; 5] = [NO; 5];
/// Buttons and keys pressed for someone you allowed: let go of whatever is still down when they stop
static INJ_DOWN: [AtomicBool; 5] = [NO; 5];
static INJ_KEYS: Mutex<Vec<u32>> = Mutex::new(Vec::new());
static WIN_VK: AtomicI32 = AtomicI32::new(0);
static GAME_BACK_AT: Mutex<Option<Instant>> = Mutex::new(None);
// the Windows key while seated: a lone tap opens this monitor's Start
static WIN_PENDING: AtomicBool = AtomicBool::new(false);
static WIN_COMBO: AtomicBool = AtomicBool::new(false);
const WM_APP_START: u32 = WM_APP + 2;
const LLKHF_EXTENDED_BIT: u32 = 0x01;

const LLKHF_INJECTED_BIT: u32 = 0x10;
const LLMHF_INJECTED_BIT: u32 = 0x01;
/// Marks the input this helper makes itself (so its hooks pass it by). Input other programs
/// make — remote-desktop apps, accessibility tools, the self-test — counts like your own.
const OUR_INPUT: usize = 0x5743_4D4F; // "WCMO"
const WM_APP_RELEASE: u32 = WM_APP + 1;
const MASK_KEY: u16 = 0xFF; // "no key": keeps a lone Alt from opening the app's menu bar

fn emit(e: &str) {
    if let Some(tx) = EVENTS.get() {
        let _ = tx.lock().unwrap().send(e.to_string());
    }
}

/// Writes events (and the summed-up mouse movement for looking around) a hundred times a second.
fn event_writer(rx: std::sync::mpsc::Receiver<String>) {
    loop {
        match rx.recv_timeout(Duration::from_millis(10)) {
            Ok(e) => {
                if e == "lookmode 1" || e == "lookmode 0" {
                    look_mode(e == "lookmode 1");
                } else if e == "mask" {
                    tap_mask_key();
                } else if e == "switch" || e == "switch-back" {
                    switch_window(e == "switch-back");
                } else {
                    say(&e);
                }
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
            Err(_) => return,
        }
        let (mut dx, mut dy) = (LOOK_X.swap(0, Ordering::SeqCst), LOOK_Y.swap(0, Ordering::SeqCst));
        let (hx, hy) = (LOOK_HX.swap(0, Ordering::SeqCst), LOOK_HY.swap(0, Ordering::SeqCst));
        if !RAW_SEEN.load(Ordering::SeqCst) {
            // pointing, and no raw input from the mouse: the moves the hook held back turn the view
            dx += hx;
            dy += hy;
        }
        if dx != 0 || dy != 0 {
            say(&format!("look {dx} {dy}"));
        }
    }
}

/// Alt+Tab at a monitor: the next of its own windows comes to the front (minimized ones too).
fn switch_window(back: bool) {
    let area = *CLIP.lock().unwrap();
    let wins: Vec<HWND> = app_windows().into_iter().filter(|h| on_area(*h, &area)).collect();
    if wins.is_empty() {
        return;
    }
    let fg = unsafe { GetAncestor(GetForegroundWindow(), GA_ROOT) };
    let at = wins.iter().position(|h| *h == fg);
    let next = match (at, back) {
        (Some(i), false) => wins[(i + 1) % wins.len()],
        (Some(i), true) => wins[(i + wins.len() - 1) % wins.len()],
        (None, _) => wins[0],
    };
    unsafe {
        if IsIconic(next).as_bool() {
            let _ = ShowWindow(next, SW_RESTORE);
        }
    }
    // the window that was in front goes to the back of the line, like Windows' own Alt+Tab
    if !back {
        if let Some(i) = at {
            unsafe {
                let _ = SetWindowPos(wins[i], Some(HWND_BOTTOM), 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE);
            }
        }
    }
    foreground(next);
}

fn tap_mask_key() {
    let key = |up: bool| INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT { wVk: VIRTUAL_KEY(MASK_KEY), wScan: 0, dwFlags: if up { KEYEVENTF_KEYUP } else { KEYBD_EVENT_FLAGS(0) }, time: 0, dwExtraInfo: OUR_INPUT },
        },
    };
    unsafe {
        let _ = SendInput(&[key(false), key(true)], std::mem::size_of::<INPUT>() as i32);
    }
}

/// From the hook thread: stop being seated and give everything back (done by the hook thread's
/// loop). `to_game`: the game comes to the front (Esc); otherwise (you switched to Task Manager,
/// a permission prompt…) only the mouse is let go, nothing is pulled away from what you chose.
fn release_from_hook(to_game: bool) {
    if SEATED.swap(false, Ordering::SeqCst) {
        unsafe {
            let _ = PostThreadMessageW(GetCurrentThreadId(), WM_APP_RELEASE, WPARAM(to_game as usize), LPARAM(0));
        }
        emit("esc");
    }
}

unsafe extern "system" fn keyboard_hook(code: i32, w: WPARAM, l: LPARAM) -> LRESULT {
    if code == HC_ACTION as i32 && SEATED.load(Ordering::SeqCst) {
        let k = unsafe { &*(l.0 as *const KBDLLHOOKSTRUCT) };
        if k.flags.0 & LLKHF_INJECTED_BIT == 0 || k.dwExtraInfo != OUR_INPUT {
            let msg = w.0 as u32;
            let down = msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN;
            let vk = k.vkCode as u16;
            // Esc gets up (Shift+Esc reaches the app) — checked first, whatever else is held
            if vk == VK_ESCAPE.0 {
                let shift = unsafe { GetAsyncKeyState(VK_SHIFT.0 as i32) } as u16 & 0x8000 != 0;
                if down && !shift {
                    ESC_HELD.store(true, Ordering::SeqCst);
                    release_from_hook(true);
                    return LRESULT(1);
                }
            }
            // the Windows key: a lone tap opens *this* monitor's Start (Windows would open the one
            // on your main screen); with another key it's Windows' own shortcut, as always
            if vk == VK_LWIN.0 || vk == VK_RWIN.0 {
                if down {
                    if !WIN_COMBO.load(Ordering::SeqCst) {
                        WIN_PENDING.store(true, Ordering::SeqCst);
                        WIN_VK.store(vk as i32, Ordering::SeqCst);
                        return LRESULT(1);
                    }
                } else {
                    if WIN_PENDING.swap(false, Ordering::SeqCst) {
                        unsafe {
                            let _ = PostThreadMessageW(GetCurrentThreadId(), WM_APP_START, WPARAM(0), LPARAM(0));
                        }
                        return LRESULT(1);
                    }
                    WIN_COMBO.store(false, Ordering::SeqCst);
                }
            } else if down && WIN_PENDING.swap(false, Ordering::SeqCst) {
                // Win + a key: the Windows key goes down first after all, then this key
                WIN_COMBO.store(true, Ordering::SeqCst);
                let ext = if k.flags.0 & LLKHF_EXTENDED_BIT != 0 { KEYEVENTF_EXTENDEDKEY } else { KEYBD_EVENT_FLAGS(0) };
                // the same Windows key you hold (its real release then matches)
                let win = WIN_VK.load(Ordering::SeqCst) as u16;
                let ins = [key_input(if win == 0 { VK_LWIN.0 } else { win }, 0, KEYEVENTF_EXTENDEDKEY), key_input(vk, k.scanCode as u16, ext)];
                unsafe {
                    let _ = SendInput(&ins, std::mem::size_of::<INPUT>() as i32);
                }
                return LRESULT(1);
            }
            // left Alt: looking around (it still reaches the app, so nothing sticks; the right
            // Alt stays AltGr for typing)
            if vk == VK_LMENU.0 {
                if ALT.swap(down, Ordering::SeqCst) != down {
                    unsafe {
                        if down {
                            let mut p = POINT::default();
                            let _ = GetCursorPos(&mut p);
                            *SAVED.lock().unwrap() = p;
                            let c = *CLIP.lock().unwrap();
                            let _ = SetCursorPos((c.left + c.right) / 2, (c.top + c.bottom) / 2);
                            emit("mask");
                            emit("lookmode 1");
                        } else {
                            let p = *SAVED.lock().unwrap();
                            let _ = SetCursorPos(p.x, p.y);
                            emit("lookmode 0");
                        }
                    }
                    emit(if down { "alt 1" } else { "alt 0" });
                }
            } else if ALT.load(Ordering::SeqCst) && down {
                if vk == VK_SPACE.0 {
                    emit("recenter");
                } else if vk == VK_TAB.0 {
                    // Alt+Tab: only the windows on this monitor (Windows' own would list everything)
                    let shift = unsafe { GetAsyncKeyState(VK_SHIFT.0 as i32) } as u16 & 0x8000 != 0;
                    emit(if shift { "switch-back" } else { "switch" });
                }
                // Alt + a key belongs to the game — its release too (an app that acts when a key
                // is let go, a video player's Space, never sees half of it)
                let mut held = KEYS_KEPT.lock().unwrap();
                if !held.contains(&(vk as u32)) {
                    held.push(vk as u32);
                }
                return LRESULT(1);
            } else if !down && KEYS_KEPT.lock().unwrap().contains(&(vk as u32)) {
                KEYS_KEPT.lock().unwrap().retain(|x| *x != vk as u32);
                return LRESULT(1); // (its press never reached the app: nor does its release)
            }
        }
    } else if code == HC_ACTION as i32 && POINTING.load(Ordering::SeqCst) {
        // pointing with Alt: Alt was let go for the apps already; your real Alt's release ends it
        let k = unsafe { &*(l.0 as *const KBDLLHOOKSTRUCT) };
        let vk = k.vkCode as u16;
        let real = k.flags.0 & LLKHF_INJECTED_BIT == 0 || k.dwExtraInfo != OUR_INPUT;
        if real && ALT_SWALLOW.load(Ordering::SeqCst) && (vk == VK_LMENU.0 || vk == VK_RMENU.0 || vk == VK_MENU.0) {
            let msg = w.0 as u32;
            if msg == WM_KEYUP || msg == WM_SYSKEYUP {
                ALT_SWALLOW.store(false, Ordering::SeqCst);
                POINT_ME.store(false, Ordering::SeqCst);
                emit("altup");
            }
            return LRESULT(1);
        }
        // you clicked an app on the monitor (it's in front now), but you're still pointing at it
        // from the game: your keys are the game's — walking, sneaking — not typing into the app
        if real && POINT_ME.load(Ordering::SeqCst) && KEEP_GAME_LOOK.load(Ordering::SeqCst) && !game_in_front() && vk != VK_LWIN.0 && vk != VK_RWIN.0 {
            let msg = w.0 as u32;
            let down = msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN;
            let mut held = GAME_KEYS.lock().unwrap();
            if down {
                if !held.contains(&(vk as u32)) {
                    held.push(vk as u32);
                }
            } else {
                held.retain(|x| *x != vk as u32);
            }
            drop(held);
            emit(&format!("gkey {} {} {}", vk, k.scanCode, if down { 1 } else { 0 }));
            return LRESULT(1);
        }
    } else if code == HC_ACTION as i32 && ESC_HELD.load(Ordering::SeqCst) {
        // the Esc that got you up: its release is ours too
        let k = unsafe { &*(l.0 as *const KBDLLHOOKSTRUCT) };
        if k.vkCode as u16 == VK_ESCAPE.0 && (w.0 as u32 == WM_KEYUP || w.0 as u32 == WM_SYSKEYUP) {
            ESC_HELD.store(false, Ordering::SeqCst);
            return LRESULT(1);
        }
    }
    unsafe { CallNextHookEx(None, code, w, l) }
}

/// Which button a mouse message is about (GLFW's numbers: left 0, right 1, middle 2, back 3, forward 4) and whether it's a press.
fn button_of(msg: u32, data: u32) -> Option<(u8, bool)> {
    match msg {
        WM_LBUTTONDOWN => Some((0, true)),
        WM_LBUTTONUP => Some((0, false)),
        WM_RBUTTONDOWN => Some((1, true)),
        WM_RBUTTONUP => Some((1, false)),
        WM_MBUTTONDOWN => Some((2, true)),
        WM_MBUTTONUP => Some((2, false)),
        WM_XBUTTONDOWN | WM_XBUTTONUP => Some((if (data >> 16) as u16 == 2 { 4 } else { 3 }, msg == WM_XBUTTONDOWN)),
        _ => None,
    }
}

unsafe extern "system" fn mouse_hook(code: i32, w: WPARAM, l: LPARAM) -> LRESULT {
    if code == HC_ACTION as i32 && POINTING.load(Ordering::SeqCst) && !SEATED.load(Ordering::SeqCst) {
        let m = unsafe { &*(l.0 as *const MSLLHOOKSTRUCT) };
        if m.flags & LLMHF_INJECTED_BIT == 0 || m.dwExtraInfo != OUR_INPUT {
            let msg = w.0 as u32;
            if msg == WM_MOUSEMOVE {
                // your mouse keeps turning your view — measured from the mouse itself (raw input,
                // see `raw_mouse`), never from where the cursor is: the cursor is moved to where you
                // look all the time, and counting those moves as yours turned the view further,
                // which moved the cursor further… (the camera flung itself around).
                // The move itself doesn't move the cursor: it jumped there and straight back to
                // where you look, which made whatever was under it flicker between hovered and not.
                // (While you hold a button down on the monitor — dragging — it does go on.)
                let dragging = PASSED.iter().any(|p| p.load(Ordering::SeqCst));
                if !dragging {
                    let mut cur = POINT::default();
                    if unsafe { GetCursorPos(&mut cur) }.is_ok() {
                        // (if Windows holds raw input back with the move, this is what turns the view)
                        LOOK_HX.fetch_add(m.pt.x - cur.x, Ordering::SeqCst);
                        LOOK_HY.fetch_add(m.pt.y - cur.y, Ordering::SeqCst);
                    }
                    return LRESULT(1);
                }
                return unsafe { CallNextHookEx(None, code, w, l) };
            }
            let me = POINT_ME.load(Ordering::SeqCst);
            if msg == WM_MOUSEWHEEL || msg == WM_MOUSEHWHEEL {
                if !me {
                    if msg == WM_MOUSEWHEEL {
                        emit(&format!("mwheel {}", ((m.mouseData >> 16) as u16 as i16) as f64 / 120.0));
                    }
                    return LRESULT(1);
                }
            } else if let Some((b, down)) = button_of(msg, m.mouseData) {
                let i = b as usize;
                if down {
                    if !me {
                        SWALLOWED[i].store(true, Ordering::SeqCst);
                        emit(&format!("mbtn {b} 1")); // yours, for the game
                        return LRESULT(1);
                    }
                    PASSED[i].store(true, Ordering::SeqCst); // yours, a real click on the monitor
                    // the app you click comes to the front, as on a real desktop, and stays there
                    // until you let go of Alt (bouncing the game back after every click flickered
                    // its title bar, and a quick next click could land on the game instead). Your
                    // keys still reach the game meanwhile ("gkey"); its title bar keeps looking active.
                    KEEP_GAME_LOOK.store(true, Ordering::SeqCst);
                } else {
                    // a release goes wherever its press went (a press from before pointing: through)
                    if SWALLOWED[i].swap(false, Ordering::SeqCst) {
                        emit(&format!("mbtn {b} 0"));
                        return LRESULT(1);
                    }
                    PASSED[i].store(false, Ordering::SeqCst);
                }
            }
        }
    }
    if code == HC_ACTION as i32 && SEATED.load(Ordering::SeqCst) && ALT.load(Ordering::SeqCst) {
        let m = unsafe { &*(l.0 as *const MSLLHOOKSTRUCT) };
        if m.flags & LLMHF_INJECTED_BIT == 0 || m.dwExtraInfo != OUR_INPUT {
            match w.0 as u32 {
                WM_MOUSEMOVE => {
                    let mut cur = POINT::default();
                    let _ = unsafe { GetCursorPos(&mut cur) };
                    LOOK_X.fetch_add(m.pt.x - cur.x, Ordering::SeqCst);
                    LOOK_Y.fetch_add(m.pt.y - cur.y, Ordering::SeqCst);
                    return LRESULT(1);
                }
                WM_MOUSEWHEEL => {
                    let notches = ((m.mouseData >> 16) as u16 as i16) as f64 / 120.0;
                    emit(&format!("zoom {notches}"));
                    return LRESULT(1);
                }
                WM_LBUTTONDOWN | WM_RBUTTONDOWN | WM_MBUTTONDOWN | WM_XBUTTONDOWN => return LRESULT(1),
                _ => {} // releases always reach the app (nothing stays held)
            }
        }
    }
    unsafe { CallNextHookEx(None, code, w, l) }
}

/// Raw mouse movement (counts from the mouse itself, unaffected by where the cursor is put):
/// while pointing, this turns your view.
fn raw_mouse(l: LPARAM) {
    use windows::Win32::UI::Input::*;
    unsafe {
        let mut size = 0u32;
        let header = std::mem::size_of::<RAWINPUTHEADER>() as u32;
        let _ = GetRawInputData(HRAWINPUT(l.0 as *mut _), RID_INPUT, None, &mut size, header);
        if size == 0 || size as usize > 256 {
            return;
        }
        let mut buf = [0u64; 32];
        if GetRawInputData(HRAWINPUT(l.0 as *mut _), RID_INPUT, Some(buf.as_mut_ptr() as *mut _), &mut size, header) == u32::MAX {
            return;
        }
        let ri = &*(buf.as_ptr() as *const RAWINPUT);
        if ri.header.dwType != RIM_TYPEMOUSE.0 {
            return;
        }
        let m = &ri.data.mouse;
        if m.usFlags.0 & MOUSE_MOVE_ABSOLUTE.0 != 0 {
            return; // (a pen tablet or a remote desktop: no relative movement to turn by)
        }
        if POINTING.load(Ordering::SeqCst) && !SEATED.load(Ordering::SeqCst) {
            RAW_SEEN.store(true, Ordering::SeqCst);
            LOOK_X.fetch_add(m.lLastX, Ordering::SeqCst);
            LOOK_Y.fetch_add(m.lLastY, Ordering::SeqCst);
        }
    }
}

/// A hidden window that receives raw mouse input even while the game is in front.
fn make_raw_window() -> HWND {
    use windows::Win32::UI::Input::*;
    unsafe {
        let class = wide("STATIC");
        let Ok(h) = CreateWindowExW(WINDOW_EX_STYLE(0), PCWSTR(class.as_ptr()), PCWSTR::null(), WINDOW_STYLE(0), 0, 0, 0, 0,
            Some(HWND_MESSAGE), None, None, None) else {
            eprintln!("error=no window for raw mouse input");
            return HWND::default();
        };
        let dev = [RAWINPUTDEVICE { usUsagePage: 1, usUsage: 2, dwFlags: RIDEV_INPUTSINK, hwndTarget: h }];
        if let Err(e) = RegisterRawInputDevices(&dev, std::mem::size_of::<RAWINPUTDEVICE>() as u32) {
            eprintln!("error=raw mouse input not available ({e})");
        }
        h
    }
}

unsafe extern "system" fn sink_proc(h: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    unsafe { DefWindowProcW(h, msg, w, l) }
}

/// A one-pixel window of ours in the monitor's corner: when nothing else is on the monitor, it
/// takes the keyboard (instead of your real desktop, where Del or F2 would touch your files).
fn make_sink(area: &RECT) -> HWND {
    unsafe {
        let class = wide("WindowsCraftMonitorSink");
        let module = windows::Win32::System::LibraryLoader::GetModuleHandleW(PCWSTR::null()).ok();
        let wc = WNDCLASSW {
            lpfnWndProc: Some(sink_proc),
            hInstance: module.map(|m| HINSTANCE(m.0)).unwrap_or_default(),
            lpszClassName: PCWSTR(class.as_ptr()),
            hbrBackground: HBRUSH(GetStockObject(BLACK_BRUSH).0),
            ..Default::default()
        };
        RegisterClassW(&wc);
        let title = wide("WindowsCraft");
        CreateWindowExW(
            WS_EX_TOOLWINDOW,
            PCWSTR(class.as_ptr()),
            PCWSTR(title.as_ptr()),
            WS_POPUP | WS_VISIBLE,
            area.right - 1,
            area.bottom - 1,
            1,
            1,
            None,
            None,
            module.map(|m| HINSTANCE(m.0)),
            None,
        )
        .unwrap_or_default()
    }
}

fn hook_thread() {
    unsafe {
        let module = windows::Win32::System::LibraryLoader::GetModuleHandleW(PCWSTR::null()).ok().map(|m| HINSTANCE(m.0));
        let kb = SetWindowsHookExW(WH_KEYBOARD_LL, Some(keyboard_hook), module, 0);
        let ms = SetWindowsHookExW(WH_MOUSE_LL, Some(mouse_hook), module, 0);
        let area = *CLIP.lock().unwrap();
        let pointing = POINTING.load(Ordering::SeqCst);
        let sink = if pointing { HWND::default() } else { make_sink(&area) };
        let raw = if pointing { make_raw_window() } else { HWND::default() };
        let me = GetCurrentThreadId() as i32;
        SINK.store(sink.0 as isize, Ordering::SeqCst);
        HOOK_THREAD.store(me, Ordering::SeqCst);
        HOOKS_OK.store(if kb.is_ok() && ms.is_ok() { 1 } else { -1 }, Ordering::SeqCst);
        let timer = SetTimer(None, 0, 100, None);
        let fast = if pointing { SetTimer(None, 0, 10, None) } else { 0 };
        let mut away_ticks = 0;
        let mut msg = MSG::default();
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            if msg.message == WM_INPUT {
                raw_mouse(msg.lParam);
                let _ = DefWindowProcW(msg.hwnd, msg.message, msg.wParam, msg.lParam);
                continue;
            }
            if msg.message == WM_APP_RELEASE {
                give_back(msg.wParam.0 != 0);
                break;
            }
            if msg.message == WM_APP_START {
                let a = *CLIP.lock().unwrap();
                std::thread::spawn(move || open_start(a));
                continue;
            }
            if msg.message == WM_TIMER && msg.wParam.0 == fast && POINTING.load(Ordering::SeqCst) {
                point_upkeep();
                continue;
            }
            if msg.message == WM_TIMER && POINTING.load(Ordering::SeqCst) && !SEATED.load(Ordering::SeqCst) {
                if BEAT.lock().unwrap().map_or(true, |b| b.elapsed() > Duration::from_secs(4)) {
                    eprintln!("info=pointing ended (the game stopped answering)");
                    POINTING.store(false, Ordering::SeqCst);
                    end_point();
                    emit("pointlost");
                    break;
                }
                continue;
            }
            if msg.message == WM_TIMER && SEATED.load(Ordering::SeqCst) {
                let device = SEAT_DEVICE.lock().unwrap().clone();
                let Some(area) = rect_of(&device) else {
                    release_from_hook(true); // the monitor went away
                    continue;
                };
                *CLIP.lock().unwrap() = area;
                let dragging_now = (GetAsyncKeyState(VK_LBUTTON.0 as i32) as u16 & 0x8000) != 0 || (GetAsyncKeyState(VK_RBUTTON.0 as i32) as u16 & 0x8000) != 0;
                // right after moving to a joined monitor, the cursor stays on it for a moment (so a
                // little slip back over the edge doesn't swing you straight back) — not while dragging
                // a window across, though
                // (the rest holds while dragging too; only the cursor isn't held back then — changing
                // its limits under a window being dragged makes the window jitter)
                let resting = SWITCHED_AT.lock().unwrap().map_or(false, |t| t.elapsed() < SWITCH_REST);
                let group = group_area(SEAT_IDX.load(Ordering::SeqCst)).unwrap_or(area);
                let clip = if resting && !dragging_now { area } else { group };
                *SEAT_CLIP.lock().unwrap() = clip;
                if !resting && NARROWED.swap(false, Ordering::SeqCst) {
                    let _ = ClipCursor(Some(&group)); // (the rest is over: the joined monitors are open again)
                }
                // the cursor went on (a little way, not just onto the edge) to a monitor joined to
                // this one: you sit at that one now
                let on_now = cursor_monitor();
                if on_now.map_or(true, |i| i == SEAT_IDX.load(Ordering::SeqCst)) {
                    *SWITCH_CANDIDATE.lock().unwrap() = None; // (back where you sit: start over)
                }
                if let Some(i) = on_now.filter(|_| !resting) {
                    let at = SEAT_IDX.load(Ordering::SeqCst);
                    if i != at && joined(at, i) {
                        if let Some(added) = AREAS.get() {
                            if let Some((dev, r)) = our_area(added, i) {
                                let mut c = POINT::default();
                                let _ = GetCursorPos(&mut c);
                                let depth = if r.left == area.right { c.x - r.left } else if r.right == area.left { r.right - 1 - c.x }
                                    else if r.top == area.bottom { c.y - r.top } else if r.bottom == area.top { r.bottom - 1 - c.y } else { i32::MAX };
                                // well onto it, and staying there a moment (not just passing over)
                                let mut cand = SWITCH_CANDIDATE.lock().unwrap();
                                let stayed = if depth >= SWITCH_DEPTH {
                                    match *cand {
                                        Some((ci, since)) if ci == i => since.elapsed() >= SWITCH_DWELL,
                                        _ => {
                                            *cand = Some((i, Instant::now()));
                                            false
                                        }
                                    }
                                } else {
                                    *cand = None;
                                    false
                                };
                                if stayed {
                                    *cand = None;
                                    SEAT_IDX.store(i, Ordering::SeqCst);
                                    *SEAT_DEVICE.lock().unwrap() = dev;
                                    *CLIP.lock().unwrap() = r;
                                    *SWITCHED_AT.lock().unwrap() = Some(Instant::now());
                                    if !dragging_now {
                                        let _ = ClipCursor(Some(&r));
                                        *SEAT_CLIP.lock().unwrap() = r;
                                        NARROWED.store(true, Ordering::SeqCst);
                                    }
                                    emit(&format!("seatmove {i}"));
                                }
                            }
                        }
                    }
                }
                let beat_old = BEAT.lock().unwrap().map_or(true, |b| b.elapsed() > Duration::from_secs(4));
                let settled = SEAT_START.lock().unwrap().map_or(true, |t| t.elapsed() > Duration::from_millis(1500));
                let fg = GetForegroundWindow();
                let fg_root = if fg.0.is_null() { fg } else { GetAncestor(fg, GA_ROOT) };
                // "away" only when you really switched to something of yours elsewhere (Task Manager,
                // a desk app): the taskbar, Start, the desktop and new windows (they're being put
                // onto the monitor) all count as here
                let popup = !fg_root.0.is_null()
                    && (class_name(fg_root) == "#32768" // a menu
                        || GetWindow(fg_root, GW_OWNER).map_or(false, |o| !o.0.is_null()) // a window's own pop-up
                        || (GetWindowLongW(fg_root, GWL_EXSTYLE) as u32 & (WS_EX_TOOLWINDOW.0 | WS_EX_NOACTIVATE.0)) != 0);
                let fg_here = fg_root.0.is_null()
                    || fg_root.0 as isize == SINK.load(Ordering::SeqCst)
                    || on_area(fg_root, &clip)
                    || is_shell(fg_root)
                    || popup // a menu or pop-up (the taskbar's right-click menu, a tray menu): not "something else"
                    || !seat_known().lock().unwrap().contains(&(fg_root.0 as isize));
                away_ticks = if fg_here { 0 } else { away_ticks + 1 };
                if beat_old || (settled && away_ticks >= 20) {
                    eprintln!("info=giving the mouse and keyboard back ({})", if beat_old { "the game stopped answering" } else { "something else has the keyboard" });
                    release_from_hook(false);
                    continue;
                }
                // Alt's release can go unseen (Ctrl+Alt+Del, Win+L): don't stay in look mode
                if ALT.load(Ordering::SeqCst) && (GetAsyncKeyState(VK_LMENU.0 as i32) as u16 & 0x8000) == 0 {
                    ALT.store(false, Ordering::SeqCst);
                    emit("lookmode 0");
                    let p = *SAVED.lock().unwrap();
                    let _ = SetCursorPos(p.x, p.y);
                    emit("alt 0");
                }
                // Windows lets go of the clip now and then: take it back. But an app's own, tighter
                // clip inside the monitor stays (a game holding the cursor still while you drag
                // its camera — Roblox — would otherwise rubber-band)
                // (never in the middle of a drag: moving or resizing a window, Windows sets its own
                // limits for the pointer, and changing them under it makes the window jitter)
                let mut now = RECT::default();
                let dragging = (GetAsyncKeyState(VK_LBUTTON.0 as i32) as u16 & 0x8000) != 0 || (GetAsyncKeyState(VK_RBUTTON.0 as i32) as u16 & 0x8000) != 0;
                if !dragging && GetClipCursor(&mut now).is_ok() {
                    let inside = now.left >= clip.left && now.top >= clip.top && now.right <= clip.right && now.bottom <= clip.bottom;
                    if !inside {
                        let _ = ClipCursor(Some(&clip));
                    }
                }
                // and in case a window slipped past the window events: anything new that isn't on
                // the monitor yet goes there — looked for on a thread of its own: this thread
                // carries every move of your mouse, and must never be kept busy (that's input lag)
                *SCAN_CLIP.lock().unwrap() = Some(clip);
                ensure_seat_scanner();
            }
            if msg.message == WM_QUIT {
                break;
            }
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
        let _ = KillTimer(None, timer);
        if fast != 0 {
            let _ = KillTimer(None, fast);
        }
        if let Ok(k) = kb {
            let _ = UnhookWindowsHookEx(k);
        }
        if let Ok(m) = ms {
            let _ = UnhookWindowsHookEx(m);
        }
        if !sink.0.is_null() {
            let _ = DestroyWindow(sink);
        }
        if !raw.0.is_null() {
            use windows::Win32::UI::Input::*;
            let dev = [RAWINPUTDEVICE { usUsagePage: 1, usUsage: 2, dwFlags: RIDEV_REMOVE, hwndTarget: HWND::default() }];
            let _ = RegisterRawInputDevices(&dev, std::mem::size_of::<RAWINPUTDEVICE>() as u32);
            let _ = DestroyWindow(raw);
        }
        // only our own: a newer seat may already have its thread running
        let _ = SINK.compare_exchange(sink.0 as isize, 0, Ordering::SeqCst, Ordering::SeqCst);
        let _ = HOOK_THREAD.compare_exchange(me, 0, Ordering::SeqCst, Ordering::SeqCst);
    }
}

/// Mouse and keyboard back: no clip; with `to_game`, the pointer over the game and the game in front.
fn give_back(to_game: bool) {
    let t0 = Instant::now();
    SEATED.store(false, Ordering::SeqCst);
    ALT.store(false, Ordering::SeqCst);
    KEYS_KEPT.lock().unwrap().clear();
    look_mode(false);
    WIN_PENDING.store(false, Ordering::SeqCst);
    WIN_COMBO.store(false, Ordering::SeqCst);
    release_injected();
    unsafe {
        let _ = ClipCursor(None);
        let game = HWND(GAME.load(Ordering::SeqCst) as *mut _);
        // Start (or search) still open on the monitor: it holds on to the keyboard and the game
        // can't come back in front (it paused) — close it first, as its own Esc would
        let fg = GetForegroundWindow();
        let fg_root = if fg.0.is_null() { fg } else { GetAncestor(fg, GA_ROOT) };
        if to_game && !fg_root.0.is_null() && fg_root != game && is_shell(fg_root) && !matches!(class_name(fg_root).as_str(), "Progman" | "WorkerW") {
            send(&[key_input(VK_ESCAPE.0, 0, KEYBD_EVENT_FLAGS(0)), key_input(VK_ESCAPE.0, 0, KEYEVENTF_KEYUP)]);
            std::thread::sleep(Duration::from_millis(120));
        }
        if to_game && !game.0.is_null() && IsWindow(Some(game)).as_bool() {
            let mut r = RECT::default();
            if GetWindowRect(game, &mut r).is_ok() {
                let _ = SetCursorPos((r.left + r.right) / 2, (r.top + r.bottom) / 2);
            }
            let tf = Instant::now();
            if !foreground(game) {
                eprintln!("info=the game didn't get the keyboard back straight away");
            }
            if tf.elapsed() > Duration::from_millis(300) {
                eprintln!("info=bringing the game back in front took {} ms", tf.elapsed().as_millis());
            }
        }
    }
    if t0.elapsed() > Duration::from_millis(300) {
        eprintln!("info=giving the mouse and keyboard back took {} ms", t0.elapsed().as_millis());
    }
    // a full-screen video there stays above its taskbar once you're back in the game: right away,
    // and again a few times while Windows settles who's in front (off the hook thread)
    std::thread::spawn(|| {
        for _ in 0..30 {
            tidy_taskbars();
            std::thread::sleep(Duration::from_millis(100));
        }
    });
}

/// Brings a window in front, with the keyboard. Done on a thread of its own, waited for only so
/// long: handing the keyboard over involves the window in front now, and if that app is stuck
/// (or Windows holds it up — a boxed app can), whoever asked must not get stuck with it. That's
/// the thread carrying your mouse, or the one answering the game.
fn foreground(h: HWND) -> bool {
    unsafe {
        if GetForegroundWindow() == h {
            return true;
        }
    }
    let hv = h.0 as isize;
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let _ = tx.send(foreground_now(HWND(hv as *mut _)));
    });
    let t0 = Instant::now();
    let mut msg = MSG::default();
    while t0.elapsed() < Duration::from_millis(700) {
        if let Ok(r) = rx.try_recv() {
            return r;
        }
        // (meanwhile, whatever Windows asks of this thread still gets answered — on the input
        // thread, that's every move of your mouse — without taking anything off its queue)
        unsafe {
            let _ = PeekMessageW(&mut msg, None, 0xBFFE, 0xBFFE, PM_NOREMOVE);
        }
        std::thread::sleep(Duration::from_millis(1));
    }
    unsafe {
        let fg = GetForegroundWindow();
        let fp = pid(fg);
        eprintln!("info=bringing \"{}\" ({}) in front got stuck on the window in front, \"{}\" ({}, process {fp}{}): going on without waiting",
            title(h), class_name(h), title(fg), class_name(fg), { let b = box_of(fp); if b.is_empty() { String::new() } else { format!(", box {b}") } });
        GetForegroundWindow() == h
    }
}

fn foreground_now(h: HWND) -> bool {
    unsafe {
        if GetForegroundWindow() == h {
            return true;
        }
        let fg = GetForegroundWindow();
        let mut fg_pid = 0u32;
        let fg_thread = GetWindowThreadProcessId(fg, Some(&mut fg_pid));
        let me = GetCurrentThreadId();
        // (not to a boxed app's thread: Windows can hold that up for good)
        let attached = fg_thread != 0 && fg_thread != me && box_of(fg_pid).is_empty() && AttachThreadInput(me, fg_thread, true).as_bool();
        let _ = SetForegroundWindow(h);
        let _ = BringWindowToTop(h);
        if attached {
            let _ = AttachThreadInput(me, fg_thread, false);
        }
        if GetForegroundWindow() != h {
            // Windows only hands the foreground to whoever had the last input: a key nobody has
            // (it does nothing) makes that us, then ask again
            send(&[key_input(MASK_KEY, 0, KEYBD_EVENT_FLAGS(0)), key_input(MASK_KEY, 0, KEYEVENTF_KEYUP)]);
            let _ = SetForegroundWindow(h);
            let _ = BringWindowToTop(h);
        }
        if GetForegroundWindow() != h {
            SwitchToThisWindow(h, true);
        }
        GetForegroundWindow() == h
    }
}

fn stop_hook_thread() {
    let t = HOOK_THREAD.load(Ordering::SeqCst);
    if t != 0 {
        unsafe {
            let _ = PostThreadMessageW(t as u32, WM_QUIT, WPARAM(0), LPARAM(0));
        }
        let t0 = Instant::now();
        while HOOK_THREAD.load(Ordering::SeqCst) != 0 && t0.elapsed() < Duration::from_secs(2) {
            std::thread::sleep(Duration::from_millis(5));
        }
    }
}

fn seat(idx: i32, device: &str, area: RECT, u: f64, v: f64, game: HWND) -> Result<(), String> {
    let _g = POINT_LOCK.lock().unwrap(); // not while pointing is starting or ending
    if POINTING.swap(false, Ordering::SeqCst) {
        stop_hook_thread();
        end_point_clip_only();
    }
    stop_hook_thread();
    ensure_placer();
    SEAT_IDX.store(idx, Ordering::SeqCst);
    *seat_known().lock().unwrap() = all_top_windows().into_iter().collect();
    if HOOK_THREAD.load(Ordering::SeqCst) != 0 {
        return Err("still letting go of the last seat — try again".into());
    }
    ESC_HELD.store(false, Ordering::SeqCst);
    *SEAT_DEVICE.lock().unwrap() = device.to_string();
    *CLIP.lock().unwrap() = area;
    *BEAT.lock().unwrap() = Some(Instant::now());
    *SEAT_START.lock().unwrap() = Some(Instant::now());
    GAME.store(game.0 as isize, Ordering::SeqCst);
    ALT.store(false, Ordering::SeqCst);
    LOOK_X.store(0, Ordering::SeqCst);
    LOOK_Y.store(0, Ordering::SeqCst);
    HOOKS_OK.store(0, Ordering::SeqCst);
    std::thread::spawn(hook_thread);
    let t0 = Instant::now();
    while HOOKS_OK.load(Ordering::SeqCst) == 0 && t0.elapsed() < Duration::from_secs(3) {
        std::thread::sleep(Duration::from_millis(5));
    }
    if HOOKS_OK.load(Ordering::SeqCst) != 1 {
        stop_hook_thread();
        return Err("Windows didn't let the game watch for Esc and Alt".into());
    }
    let x = area.left + ((area.right - area.left - 1) as f64 * u.clamp(0.0, 1.0)).round() as i32;
    let y = area.top + ((area.bottom - area.top - 1) as f64 * v.clamp(0.0, 1.0)).round() as i32;
    // (the cursor may go on to the monitors joined to this one: a window drags across)
    let clip = group_area(idx).unwrap_or(area);
    *SEAT_CLIP.lock().unwrap() = clip;
    unsafe {
        let _ = SetCursorPos(x, y);
        let _ = ClipCursor(Some(&clip));
        // whatever is under the pointer on that monitor gets the keyboard (our corner window if nothing is)
        let under = WindowFromPoint(POINT { x, y });
        let mut target = if under.0.is_null() { HWND::default() } else { GetAncestor(under, GA_ROOT) };
        let apps = app_windows();
        if target.0.is_null() || !apps.contains(&target) || !on_area(target, &area) {
            target = apps.into_iter().find(|h| !IsIconic(*h).as_bool() && on_area(*h, &area))
                .unwrap_or(HWND(SINK.load(Ordering::SeqCst) as *mut _));
        }
        SEATED.store(true, Ordering::SeqCst);
        *BEAT.lock().unwrap() = Some(Instant::now());
        if !foreground(target) {
            eprintln!("info=the monitor's app didn't take the keyboard straight away");
        }
        if !SEATED.load(Ordering::SeqCst) && !game.0.is_null() {
            foreground(game); // Esc came in while the monitor's app was being brought forward
        }
        // Minecraft lets go of its hold on the cursor once it isn't the foreground window any more:
        // it puts the cursor back where it took it from and drops the clip (so the cursor ended up
        // in the monitor's corner once clipped again). Put it back where it belongs after that.
        for wait in [40u64, 90, 180] {
            std::thread::sleep(Duration::from_millis(wait));
            if !SEATED.load(Ordering::SeqCst) {
                break;
            }
            let mut c = POINT::default();
            let _ = GetCursorPos(&mut c);
            let inside = c.x > clip.left && c.x < clip.right - 1 && c.y > clip.top && c.y < clip.bottom - 1;
            if !inside {
                let _ = SetCursorPos(x, y);
            }
            let _ = ClipCursor(Some(&clip));
        }
    }
    Ok(())
}

fn key_input(vk: u16, scan: u16, flags: KEYBD_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: VIRTUAL_KEY(vk), wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: OUR_INPUT } },
    }
}

fn mouse_input(flags: MOUSE_EVENT_FLAGS, data: i32) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 { mi: MOUSEINPUT { dx: 0, dy: 0, mouseData: data as u32, dwFlags: flags, time: 0, dwExtraInfo: OUR_INPUT } },
    }
}

fn send(inputs: &[INPUT]) {
    unsafe {
        let _ = SendInput(inputs, std::mem::size_of::<INPUT>() as i32);
    }
}

/// Is the real cursor ours to move right now (sitting at a monitor, or pointing at one)?
fn cursor_ours() -> bool {
    SEATED.load(Ordering::SeqCst) || POINTING.load(Ordering::SeqCst)
}

/// The real cursor to (u, v) of the monitor you sit at or point at.
fn put_cursor(u: f64, v: f64) {
    if !cursor_ours() {
        return;
    }
    let a = *CLIP.lock().unwrap();
    // pointing (not sitting): your mouse's movement is read from where Windows moves the cursor,
    // and Windows stops it at the monitor's edges (they're the desktop's edges). A few pixels in,
    // your view keeps turning however you move.
    let inset = if SEATED.load(Ordering::SeqCst) { 0 } else { 12 };
    let x = (a.left + ((a.right - a.left - 1) as f64 * u.clamp(0.0, 1.0)).round() as i32).clamp(a.left + inset, a.right - 1 - inset);
    let y = (a.top + ((a.bottom - a.top - 1) as f64 * v.clamp(0.0, 1.0)).round() as i32).clamp(a.top + inset, a.bottom - 1 - inset);
    *TARGET.lock().unwrap() = Some(POINT { x, y });
    unsafe {
        let _ = SetCursorPos(x, y);
    }
}

/// A click by someone you allowed, where the cursor is.
fn click(b: u8, down: bool) {
    let b = b.min(4);
    if down {
        // only where the cursor really is theirs, at the spot they point at
        let Some(t) = *TARGET.lock().unwrap() else { return };
        if !cursor_ours() {
            return;
        }
        unsafe {
            let _ = SetCursorPos(t.x, t.y);
        }
        INJ_DOWN[b as usize].store(true, Ordering::SeqCst);
    } else if !INJ_DOWN[b as usize].swap(false, Ordering::SeqCst) {
        return; // never pressed for them: nothing to let go
    }
    let (flags, data) = match (b, down) {
        (0, true) => (MOUSEEVENTF_LEFTDOWN, 0),
        (0, false) => (MOUSEEVENTF_LEFTUP, 0),
        (1, true) => (MOUSEEVENTF_RIGHTDOWN, 0),
        (1, false) => (MOUSEEVENTF_RIGHTUP, 0),
        (2, true) => (MOUSEEVENTF_MIDDLEDOWN, 0),
        (2, false) => (MOUSEEVENTF_MIDDLEUP, 0),
        (3, d) => (if d { MOUSEEVENTF_XDOWN } else { MOUSEEVENTF_XUP }, 1),
        (_, d) => (if d { MOUSEEVENTF_XDOWN } else { MOUSEEVENTF_XUP }, 2),
    };
    send(&[mouse_input(flags, data)]);
    // while you're not sitting there, the game gets the keyboard back once the click is done
    if !down && !SEATED.load(Ordering::SeqCst) {
        *GAME_BACK_AT.lock().unwrap() = Some(Instant::now() + Duration::from_millis(80));
    }
}

/// Lets go of every button and key still pressed for someone you allowed.
fn release_injected() {
    let mut ins = Vec::new();
    for b in 0..5u8 {
        if INJ_DOWN[b as usize].swap(false, Ordering::SeqCst) {
            let (f, d) = match b {
                0 => (MOUSEEVENTF_LEFTUP, 0),
                1 => (MOUSEEVENTF_RIGHTUP, 0),
                2 => (MOUSEEVENTF_MIDDLEUP, 0),
                3 => (MOUSEEVENTF_XUP, 1),
                _ => (MOUSEEVENTF_XUP, 2),
            };
            ins.push(mouse_input(f, d));
        }
    }
    for sc in INJ_KEYS.lock().unwrap().drain(..) {
        let mut flags = KEYEVENTF_SCANCODE | KEYEVENTF_KEYUP;
        if sc & 0x100 != 0 {
            flags |= KEYEVENTF_EXTENDEDKEY;
        }
        ins.push(key_input(0, (sc & 0xFF) as u16, flags));
    }
    if !ins.is_empty() {
        send(&ins);
    }
}

/// Commands that must never wait behind a slow one (answered by nobody, done at once).
fn quick_command(line: &str) -> bool {
    let p: Vec<&str> = line.split_whitespace().collect();
    let f = |i: usize| p.get(i).and_then(|s| s.parse::<f64>().ok()).unwrap_or(0.0);
    match p.first().copied() {
        Some("ptr") => put_cursor(f(1), f(2)),
        Some("btn") => click(f(1) as u8, p.get(2) == Some(&"down")),
        Some("wheel") => {
            if cursor_ours() {
                if let Some(t) = *TARGET.lock().unwrap() {
                    unsafe {
                        let _ = SetCursorPos(t.x, t.y);
                    }
                }
                send(&[mouse_input(MOUSEEVENTF_WHEEL, (f(1) * 120.0).round() as i32)]);
            }
        }
        Some("key") => {
            // as if typed on your keyboard: only while you sit at the monitor (its app has the keyboard)
            let sc = f(1) as u32;
            let down = p.get(2) == Some(&"down");
            let held = INJ_KEYS.lock().unwrap().contains(&sc);
            if (down && SEATED.load(Ordering::SeqCst)) || (!down && held) {
                let mut flags = KEYEVENTF_SCANCODE;
                if sc & 0x100 != 0 {
                    flags |= KEYEVENTF_EXTENDEDKEY;
                }
                if !down {
                    flags |= KEYEVENTF_KEYUP;
                }
                {
                    let mut k = INJ_KEYS.lock().unwrap();
                    if down {
                        if !k.contains(&sc) {
                            k.push(sc);
                        }
                    } else {
                        k.retain(|x| *x != sc);
                    }
                }
                send(&[key_input(0, (sc & 0xFF) as u16, flags)]);
            }
        }
        Some("release") => release_injected(),
        Some("point") => {
            // answered as an event (pointok / pointfail), so it never waits behind a slow command
            let (idx, game, u, v) = (f(1) as i32, HWND(p.get(2).and_then(|x| x.parse::<usize>().ok()).unwrap_or(0) as *mut _), f(3), f(4));
            let game = game.0 as isize;
            std::thread::spawn(move || {
                let _g = POINT_LOCK.lock().unwrap();
                let area = AREAS.get().and_then(|a| our_area(a, idx));
                match area {
                    None => emit("pointfail that in-game monitor isn't there any more"),
                    Some((device, area)) => match point(idx, &device, area, HWND(game as *mut _)) {
                        Ok(how) => {
                            put_cursor(u, v);
                            emit(if how.ends_with("seated") { "pointok seated" } else { "pointok" });
                        }
                        Err(why) => emit(&format!("pointfail {why}")),
                    },
                }
            });
        }
        Some("unpoint") => {
            std::thread::spawn(|| {
                let _g = POINT_LOCK.lock().unwrap();
                unpoint();
            });
        }
        Some("pointme") => {
            let me = p.get(1) == Some(&"1");
            if me && POINTING.load(Ordering::SeqCst) && !POINT_ME.swap(true, Ordering::SeqCst) {
                // Alt has been held since before (it's how you point): the apps must not see it held,
                // or a click would be Alt+click (a download, in a browser). Your real release ends pointing.
                let held = |vk: VIRTUAL_KEY| unsafe { GetAsyncKeyState(vk.0 as i32) } as u16 & 0x8000 != 0;
                let mut ins = vec![key_input(MASK_KEY, 0, KEYBD_EVENT_FLAGS(0)), key_input(MASK_KEY, 0, KEYEVENTF_KEYUP)];
                if held(VK_LMENU) {
                    ins.push(key_input(VK_LMENU.0, 0, KEYEVENTF_KEYUP));
                }
                if held(VK_RMENU) {
                    ins.push(key_input(VK_RMENU.0, 0, KEYEVENTF_KEYUP | KEYEVENTF_EXTENDEDKEY));
                    if held(VK_LCONTROL) {
                        ins.push(key_input(VK_LCONTROL.0, 0, KEYEVENTF_KEYUP)); // AltGr's part of it
                    }
                }
                if !held(VK_LMENU) && !held(VK_RMENU) {
                    // you let go of Alt already
                    POINT_ME.store(false, Ordering::SeqCst);
                    emit("altup");
                    return true;
                }
                ALT_SWALLOW.store(true, Ordering::SeqCst);
                send(&ins);
            } else if !me {
                POINT_ME.store(false, Ordering::SeqCst);
                ALT_SWALLOW.store(false, Ordering::SeqCst);
            }
        }
        _ => return false,
    }
    true
}

/// Point at a monitor: the real cursor goes onto it, the game keeps the keyboard, your mouse
/// keeps turning your view (reported as "look").
fn point(idx: i32, device: &str, area: RECT, game: HWND) -> Result<&'static str, String> {
    if SEATED.load(Ordering::SeqCst) {
        return if SEAT_IDX.load(Ordering::SeqCst) == idx { Ok("pointing seated") } else { Err("you're sitting at another monitor".into()) };
    }
    if POINTING.load(Ordering::SeqCst) && SEAT_IDX.load(Ordering::SeqCst) == idx {
        return Ok("pointing");
    }
    if POINTING.swap(false, Ordering::SeqCst) {
        stop_hook_thread();
        end_point_clip_only();
    }
    stop_hook_thread();
    if HOOK_THREAD.load(Ordering::SeqCst) != 0 {
        return Err("still letting go of the last seat — try again".into());
    }
    SEAT_IDX.store(idx, Ordering::SeqCst);
    *SEAT_DEVICE.lock().unwrap() = device.to_string();
    *CLIP.lock().unwrap() = area;
    *BEAT.lock().unwrap() = Some(Instant::now());
    GAME.store(game.0 as isize, Ordering::SeqCst);
    *TARGET.lock().unwrap() = None;
    *GAME_BACK_AT.lock().unwrap() = None;
    POINT_ME.store(false, Ordering::SeqCst);
    ALT_SWALLOW.store(false, Ordering::SeqCst);
    LOOK_X.store(0, Ordering::SeqCst);
    LOOK_Y.store(0, Ordering::SeqCst);
    LOOK_HX.store(0, Ordering::SeqCst);
    LOOK_HY.store(0, Ordering::SeqCst);
    RAW_SEEN.store(false, Ordering::SeqCst);
    KEEP_GAME_LOOK.store(false, Ordering::SeqCst);
    LAST_FG.store(0, Ordering::SeqCst);
    for b in 0..5 {
        PASSED[b].store(false, Ordering::SeqCst);
        SWALLOWED[b].store(false, Ordering::SeqCst);
    }
    unsafe {
        let mut p = POINT::default();
        let _ = GetCursorPos(&mut p);
        *SAVED.lock().unwrap() = p;
    }
    POINTING.store(true, Ordering::SeqCst);
    look_mode(true);
    HOOKS_OK.store(0, Ordering::SeqCst);
    std::thread::spawn(hook_thread);
    let t0 = Instant::now();
    while HOOKS_OK.load(Ordering::SeqCst) == 0 && t0.elapsed() < Duration::from_secs(3) {
        std::thread::sleep(Duration::from_millis(5));
    }
    if HOOKS_OK.load(Ordering::SeqCst) != 1 {
        POINTING.store(false, Ordering::SeqCst);
        stop_hook_thread();
        end_point_clip_only();
        return Err("Windows didn't let the game watch the mouse".into());
    }
    unsafe {
        let _ = ClipCursor(None);
    }
    Ok("pointing")
}

/// Every 10 ms while pointing: the cursor stays where it should be (the game moves it back to
/// itself now and then, e.g. when it gets the keyboard back), and after a click on the
/// monitor the game gets the keyboard back.
fn point_upkeep() {
    unsafe {
        let mut c = RECT::default();
        let virt = RECT {
            left: GetSystemMetrics(SM_XVIRTUALSCREEN),
            top: GetSystemMetrics(SM_YVIRTUALSCREEN),
            right: GetSystemMetrics(SM_XVIRTUALSCREEN) + GetSystemMetrics(SM_CXVIRTUALSCREEN),
            bottom: GetSystemMetrics(SM_YVIRTUALSCREEN) + GetSystemMetrics(SM_CYVIRTUALSCREEN),
        };
        // the cursor stays on the monitor (your mouse moves it a little before it's put back where
        // you look: never off the monitor, so a click always lands there)
        let _ = virt;
        let a = *CLIP.lock().unwrap();
        if a.right > a.left && GetClipCursor(&mut c).is_ok() && c != a {
            let _ = ClipCursor(Some(&a));
        }
        let held = |vk: VIRTUAL_KEY| GetAsyncKeyState(vk.0 as i32) as u16 & 0x8000 != 0;
        let buttons = held(VK_LBUTTON) || held(VK_RBUTTON) || held(VK_MBUTTON);
        if let Some(t) = *TARGET.lock().unwrap() {
            let mut cur = POINT::default();
            if GetCursorPos(&mut cur).is_ok() && (cur.x != t.x || cur.y != t.y) && !buttons {
                let _ = SetCursorPos(t.x, t.y);
            }
        }
        // (the title bar: also from here, in case the window-event hook isn't running)
        if KEEP_GAME_LOOK.load(Ordering::SeqCst) {
            let fg = GetAncestor(GetForegroundWindow(), GA_ROOT).0 as isize;
            if LAST_FG.swap(fg, Ordering::SeqCst) != fg {
                keep_game_looking_active(HWND(fg as *mut _));
            }
        }
        let due = GAME_BACK_AT.lock().unwrap().map_or(false, |at| Instant::now() >= at);
        if due && !buttons {
            *GAME_BACK_AT.lock().unwrap() = None;
            let game = GAME.load(Ordering::SeqCst);
            if game != 0 && GetForegroundWindow().0 as isize != game {
                // (never on the hook thread: a busy app could hold it up, and with it every mouse move)
                std::thread::spawn(move || {
                    let mut msg = MSG::default();
                    let _ = PeekMessageW(&mut msg, None, 0, 0, PM_NOREMOVE);
                    foreground(HWND(game as *mut _));
                });
            }
        }
    }
}

/// Pointing is over: the clip the game had, the cursor back where it was, the game in front.
fn end_point() {
    release_injected();
    release_game_keys();
    look_mode(false);
    KEEP_GAME_LOOK.store(false, Ordering::SeqCst);
    POINT_ME.store(false, Ordering::SeqCst);
    ALT_SWALLOW.store(false, Ordering::SeqCst);
    *TARGET.lock().unwrap() = None;
    *GAME_BACK_AT.lock().unwrap() = None;
    unsafe {
        let p = *SAVED.lock().unwrap();
        let _ = SetCursorPos(p.x, p.y);
        // no clip: the game puts its own back when it takes the mouse again (a menu it opened meanwhile must not trap the cursor)
        let _ = ClipCursor(None);
        let game = HWND(GAME.load(Ordering::SeqCst) as *mut _);
        if !game.0.is_null() && IsWindow(Some(game)).as_bool() && GetForegroundWindow() != game {
            let fg = GetAncestor(GetForegroundWindow(), GA_ROOT);
            let a = *CLIP.lock().unwrap();
            // only if something on the monitor took it (a click there): never from what you chose yourself
            if fg.0.is_null() || on_area(fg, &a) || is_shell(fg) {
                foreground(game);
            }
        }
    }
}

fn end_point_clip_only() {
    release_injected();
    release_game_keys();
    KEEP_GAME_LOOK.store(false, Ordering::SeqCst);
    look_mode(false);
    POINT_ME.store(false, Ordering::SeqCst);
    ALT_SWALLOW.store(false, Ordering::SeqCst);
    *TARGET.lock().unwrap() = None;
    unsafe {
        let _ = ClipCursor(None);
    }
}

fn unpoint() {
    if POINTING.swap(false, Ordering::SeqCst) {
        stop_hook_thread();
        end_point();
    }
}

/// Opens the Start menu of the taskbar on that monitor (UI Automation: its Start button).
/// Falls back to an ordinary Windows key press.
fn open_start(area: RECT) {
    use windows::Win32::System::Com::*;
    use windows::Win32::UI::Accessibility::*;
    let ok = unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
        (|| -> windows::core::Result<bool> {
            let class = wide("Shell_SecondaryTrayWnd");
            let mut prev: Option<HWND> = None;
            let mut tray = None;
            loop {
                let Ok(t) = FindWindowExW(None, prev, PCWSTR(class.as_ptr()), PCWSTR::null()) else { break };
                if t.0.is_null() {
                    break;
                }
                prev = Some(t);
                let mut r = RECT::default();
                if GetWindowRect(t, &mut r).is_ok() {
                    let (cx, cy) = ((r.left + r.right) / 2, (r.top + r.bottom) / 2);
                    if cx >= area.left && cx < area.right && cy >= area.top && cy < area.bottom {
                        tray = Some(t);
                        break;
                    }
                }
            }
            let Some(tray) = tray else { return Ok(false) };
            let uia: IUIAutomation = CoCreateInstance(&CUIAutomation, None, CLSCTX_INPROC_SERVER)?;
            let el = uia.ElementFromHandle(tray)?;
            let all = el.FindAll(TreeScope_Descendants, &uia.CreateTrueCondition()?)?;
            for i in 0..all.Length()? {
                let e = all.GetElement(i)?;
                let id = e.CurrentAutomationId().map(|b| b.to_string()).unwrap_or_default();
                if id == "StartButton" || id == "Start" {
                    let inv: IUIAutomationInvokePattern = e.GetCurrentPatternAs(UIA_InvokePatternId)?;
                    inv.Invoke()?;
                    return Ok(true);
                }
            }
            Ok(false)
        })()
        .unwrap_or_else(|e| {
            eprintln!("info=Start button not reachable ({e})");
            false
        })
    };
    if ok {
        eprintln!("info=opened the Start menu on the in-game monitor");
    } else {
        eprintln!("info=couldn't find this monitor's Start button; using the normal Windows key");
        send(&[key_input(VK_LWIN.0, 0, KEYEVENTF_EXTENDEDKEY), key_input(VK_LWIN.0, 0, KEYEVENTF_EXTENDEDKEY | KEYEVENTF_KEYUP)]);
    }
}

fn unseat() {
    if POINTING.load(Ordering::SeqCst) && !SEATED.load(Ordering::SeqCst) {
        return; // not sitting, only pointing: that ends with "unpoint"
    }
    if SEATED.load(Ordering::SeqCst) || HOOK_THREAD.load(Ordering::SeqCst) != 0 {
        let was = SEATED.swap(false, Ordering::SeqCst);
        let t = HOOK_THREAD.load(Ordering::SeqCst);
        if t != 0 {
            unsafe {
                let _ = PostThreadMessageW(t as u32, WM_APP_RELEASE, WPARAM(was as usize), LPARAM(0));
            }
        }
        let t0 = Instant::now();
        while HOOK_THREAD.load(Ordering::SeqCst) != 0 && t0.elapsed() < Duration::from_secs(2) {
            std::thread::sleep(Duration::from_millis(5));
        }
        if HOOK_THREAD.load(Ordering::SeqCst) != 0 {
            eprintln!("info=getting up: the input thread is still busy after 2 s");
        }
    }
    unsafe {
        let _ = ClipCursor(None);
    }
}

// ---- installing the driver --------------------------------------------------------------------

/// Runs a program as administrator (Windows asks you first) and waits for it.
fn run_elevated(file: &str, params: &str) -> (&'static str, i64) {
    let verb = wide("runas");
    let f = wide(file);
    let p = wide(params);
    let mut info = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_NOASYNC | SEE_MASK_FLAG_NO_UI,
        lpVerb: PCWSTR(verb.as_ptr()),
        lpFile: PCWSTR(f.as_ptr()),
        lpParameters: PCWSTR(p.as_ptr()),
        nShow: SW_HIDE.0,
        ..Default::default()
    };
    unsafe {
        if let Err(e) = ShellExecuteExW(&mut info) {
            return if e.code() == ERROR_CANCELLED.to_hresult() { ("cancelled", 0) } else { ("failed", e.code().0 as i64) };
        }
        if info.hProcess.is_invalid() {
            return ("ok", 0);
        }
        let _ = WaitForSingleObject(info.hProcess, 300_000);
        let mut code = 0u32;
        let _ = GetExitCodeProcess(info.hProcess, &mut code);
        let _ = CloseHandle(info.hProcess);
        (if code == 0 { "ok" } else { "failed" }, code as i64)
    }
}

/// The driver's own uninstaller, from the list of installed programs.
fn uninstaller() -> Option<String> {
    for view in [KEY_WOW64_64KEY, KEY_WOW64_32KEY] {
        unsafe {
            let base = wide("SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall");
            let mut key = HKEY::default();
            if RegOpenKeyExW(HKEY_LOCAL_MACHINE, PCWSTR(base.as_ptr()), None, KEY_READ | view, &mut key) != ERROR_SUCCESS {
                continue;
            }
            let mut i = 0;
            loop {
                let mut name = [0u16; 256];
                let mut len = name.len() as u32;
                if RegEnumKeyExW(key, i, Some(PWSTR(name.as_mut_ptr())), &mut len, None, None, None, None) != ERROR_SUCCESS {
                    break;
                }
                i += 1;
                let mut sub = HKEY::default();
                if RegOpenKeyExW(key, PCWSTR(name.as_ptr()), None, KEY_READ | view, &mut sub) != ERROR_SUCCESS {
                    continue;
                }
                let read = |value: &str| -> String {
                    let v = wide(value);
                    let mut buf = [0u16; 1024];
                    let mut size = (buf.len() * 2) as u32;
                    if RegQueryValueExW(sub, PCWSTR(v.as_ptr()), None, None, Some(buf.as_mut_ptr() as *mut u8), Some(&mut size)) == ERROR_SUCCESS {
                        from_wide(&buf)
                    } else {
                        String::new()
                    }
                };
                let display = read("DisplayName");
                let cmd = read("UninstallString");
                let _ = RegCloseKey(sub);
                if display.contains("Parsec Virtual Display") && !cmd.is_empty() {
                    let _ = RegCloseKey(key);
                    return Some(cmd);
                }
            }
            let _ = RegCloseKey(key);
        }
    }
    None
}

// ---- apps started on a monitor --------------------------------------------------------------------
//
// An app started for a monitor opens there: a window event hook sees each window the moment it's
// shown, and windows of a process started for a monitor (or, for a program that was already
// running, the new window it opens right after) go straight onto it, without being activated.

struct Launch {
    idx: i32,
    since: u64,             // FILETIME: processes created after this belong to the launch
    exe: String,            // lowercase file name of the program, for apps that were already running
    before: std::collections::HashSet<isize>,
    until: Instant,
}

static AREAS: OnceLock<AddedMap> = OnceLock::new();
static HOMES: OnceLock<Homes> = OnceLock::new();
static LAUNCHES: Mutex<Vec<Launch>> = Mutex::new(Vec::new());
static OWNED: OnceLock<Mutex<HashMap<u32, i32>>> = OnceLock::new();     // process started for a monitor → its index
static PLACED: OnceLock<Mutex<HashMap<isize, i32>>> = OnceLock::new();  // window placed by a launch → index
static PLACER: AtomicBool = AtomicBool::new(false);
/// Windows that already existed when you sat down (they stay where they are).
static SEAT_KNOWN: OnceLock<Mutex<std::collections::HashSet<isize>>> = OnceLock::new();
/// Windows just put on a monitor: for a few seconds they're put back if the app moves them away
/// again (browsers restore their last spot right after showing a new window).
static WATCH: OnceLock<Mutex<Vec<(isize, i32, Instant)>>> = OnceLock::new();

fn seat_known() -> &'static Mutex<std::collections::HashSet<isize>> { SEAT_KNOWN.get_or_init(|| Mutex::new(Default::default())) }
fn watch() -> &'static Mutex<Vec<(isize, i32, Instant)>> { WATCH.get_or_init(|| Mutex::new(Vec::new())) }

/// Taskbars of ours we hid (a full-screen video on that monitor): shown again when it isn't.
static HIDDEN_TRAYS: OnceLock<Mutex<std::collections::HashSet<isize>>> = OnceLock::new();
fn hidden_trays() -> &'static Mutex<std::collections::HashSet<isize>> { HIDDEN_TRAYS.get_or_init(|| Mutex::new(Default::default())) }

static RAISED: OnceLock<Mutex<std::collections::HashSet<isize>>> = OnceLock::new();
fn raised() -> &'static Mutex<std::collections::HashSet<isize>> { RAISED.get_or_init(|| Mutex::new(Default::default())) }

/// While you're not sitting at a monitor, a full-screen window on it stays above its taskbar
/// (Windows only keeps a full-screen app above the taskbar while you're using that app).
fn keep_fullscreen_on_top(apps: &[HWND], rects: &[RECT]) {
    let seated = SEATED.load(Ordering::SeqCst);
    let mut now = std::collections::HashSet::new();
    if !seated {
        for &h in apps {
            let mut r = RECT::default();
            let _ = &mut r;
            let full = rects.iter().any(|a| is_full_screen(h, a));
            if full {
                now.insert(h.0 as isize);
            }
        }
    }
    let dropped: Vec<isize> = {
        let mut was = raised().lock().unwrap();
        let d = was.iter().copied().filter(|h| !now.contains(h)).collect();
        *was = now.clone();
        d
    };
    let flags = SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_ASYNCWINDOWPOS;
    for &h in now.iter() {
        // every time, not only the first: the taskbar puts itself on top again whenever the
        // active window changes (e.g. when you get up and the game comes back)
        unsafe {
            let _ = SetWindowPos(HWND(h as *mut _), Some(HWND_TOPMOST), 0, 0, 0, 0, flags);
        }
    }
    for h in dropped {
        unsafe {
            let w = HWND(h as *mut _);
            if IsWindow(Some(w)).as_bool() {
                let _ = SetWindowPos(w, Some(HWND_NOTOPMOST), 0, 0, 0, 0, flags);
            }
        }
    }
}

/// A real full-screen window on that area: covers all of it and has no title bar (a video or game
/// gone full screen). Not a maximized window (once the taskbar is hidden, a maximized one covers
/// the whole monitor too, and the taskbar would never come back), not a see-through overlay.
fn is_full_screen(h: HWND, a: &RECT) -> bool {
    unsafe {
        let mut r = RECT::default();
        if IsIconic(h).as_bool() || IsZoomed(h).as_bool() || GetWindowRect(h, &mut r).is_err() {
            return false;
        }
        if !(r.left <= a.left && r.top <= a.top && r.right >= a.right && r.bottom >= a.bottom) {
            return false;
        }
        let style = GetWindowLongW(h, GWL_STYLE) as u32;
        let ex = GetWindowLongW(h, GWL_EXSTYLE) as u32;
        // (some players and Store apps keep their title-bar style in full screen: exactly the
        // monitor's size, not maximized, is full screen too)
        let exact = r.right - r.left <= a.right - a.left + 16 && r.bottom - r.top <= a.bottom - a.top + 16;
        if style & WS_CAPTION.0 == WS_CAPTION.0 && !exact {
            return false;
        }
        if ex & WS_EX_LAYERED.0 != 0 && ex & WS_EX_TRANSPARENT.0 != 0 {
            return false; // (an overlay you can click through)
        }
        !is_overlay(h)
    }
}

/// Screen overlays that cover everything for a moment (the Snipping Tool's): never moved onto an
/// in-game monitor, never counted as full screen.
fn is_overlay(h: HWND) -> bool {
    let c = class_name(h);
    matches!(c.as_str(), "SnipOverlayRootWindow" | "ScreenClippingHost" | "Microsoft.ScreenSketch" | "ShellScreenSketchHostWindow")
        || title(h).contains("Snipping Tool Overlay")
}

/// After the window in front changed: the taskbars are tidied right away and once more a moment
/// later (Windows decides who's on top in a few steps) — on a thread of their own.
fn tidy_soon() {
    static BUSY: AtomicBool = AtomicBool::new(false);
    if BUSY.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(|| {
        for ms in [0u64, 60, 250, 700] {
            std::thread::sleep(Duration::from_millis(ms));
            tidy_taskbars();
        }
        BUSY.store(false, Ordering::SeqCst);
    });
}

/// A full-screen video or game on an in-game monitor hides that monitor's taskbar, even while you
/// play (Windows only hides it for the app you're using, and you're in Minecraft).
fn tidy_taskbars() {
    let Some(areas) = AREAS.get() else { return };
    let devices: Vec<String> = areas.lock().unwrap().values().map(|a| a.device.clone()).filter(|d| !d.is_empty()).collect();
    let rects: Vec<RECT> = devices.iter().filter_map(|d| rect_of(d)).collect();
    stray_taskbars(&devices, &rects);
    let apps = app_windows();
    let on_ours: Vec<HWND> = apps.iter().copied().filter(|h| rects.iter().any(|a| on_area(*h, a))).collect();
    keep_fullscreen_on_top(&on_ours, &rects);
    unsafe {
        let class = wide("Shell_SecondaryTrayWnd");
        let mut prev: Option<HWND> = None;
        loop {
            let Ok(tray) = FindWindowExW(None, prev, PCWSTR(class.as_ptr()), PCWSTR::null()) else { break };
            if tray.0.is_null() {
                break;
            }
            prev = Some(tray);
            let mut tr = RECT::default();
            if GetWindowRect(tray, &mut tr).is_err() {
                continue;
            }
            // its monitor: the one of ours it covers most (a taskbar pushed half off its monitor
            // has its middle right on the edge)
            let overlap = |a: &RECT| ((tr.right.min(a.right) - tr.left.max(a.left)).max(0) as i64) * ((tr.bottom.min(a.bottom) - tr.top.max(a.top)).max(0) as i64);
            let Some(a) = rects.iter().filter(|a| overlap(a) > 0).max_by_key(|a| overlap(a)) else { continue };
            if IsWindowVisible(tray).as_bool() {
                fix_misplaced_tray(tray, &tr, a);
            }
            let full = apps.iter().any(|&h| is_full_screen(h, a));
            let mut hidden = hidden_trays().lock().unwrap();
            if full && IsWindowVisible(tray).as_bool() && !hidden.contains(&(tray.0 as isize)) {
                eprintln!("info=a full-screen window on an in-game monitor: hiding its taskbar");
            }
            if full && IsWindowVisible(tray).as_bool() {
                let _ = ShowWindow(tray, SW_HIDE);
                hidden.insert(tray.0 as isize);
            } else if !full && hidden.remove(&(tray.0 as isize)) {
                let _ = ShowWindow(tray, SW_SHOWNA);
            }
        }
    }
}

static TRAY_FIXED: Mutex<Option<HashMap<isize, (Instant, u32)>>> = Mutex::new(None);

/// A monitor's taskbar must lie along its monitor's edge, its full width, entirely on it. Windows
/// sometimes leaves it where an earlier layout had it (after monitors are joined, moved or change
/// size): half off the bottom, too narrow, sticking out. Then Windows is asked to lay its
/// taskbars out again; if that doesn't do it, the taskbar is put in place directly.
fn fix_misplaced_tray(tray: HWND, tr: &RECT, a: &RECT) {
    let (h, w) = (tr.bottom - tr.top, tr.right - tr.left);
    let full_width = (tr.left - a.left).abs() <= 1 && (tr.right - a.right).abs() <= 1;
    let full_height = (tr.top - a.top).abs() <= 1 && (tr.bottom - a.bottom).abs() <= 1;
    let along = (full_width && ((tr.bottom - a.bottom).abs() <= 1 || (tr.top - a.top).abs() <= 1))
        || (full_height && ((tr.left - a.left).abs() <= 1 || (tr.right - a.right).abs() <= 1));
    let inside = tr.left >= a.left - 1 && tr.top >= a.top - 1 && tr.right <= a.right + 1 && tr.bottom <= a.bottom + 1;
    if (along && inside) || h <= 0 || w <= 0 {
        return;
    }
    let key = tray.0 as isize;
    let mut fixed = TRAY_FIXED.lock().unwrap();
    let m = fixed.get_or_insert_with(HashMap::new);
    let (last, tries) = m.get(&key).copied().unwrap_or((Instant::now() - Duration::from_secs(60), 0));
    if last.elapsed() < Duration::from_millis(1500) {
        return;
    }
    m.insert(key, (Instant::now(), tries + 1));
    drop(fixed);
    eprintln!("info=a monitor's taskbar is out of place ({} on a monitor at {}): {}", rect_str(tr), rect_str(a),
        if tries % 2 == 0 { "Windows asked to lay it out again" } else { "putting it in place" });
    if tries % 2 == 0 {
        tell_taskbar();
        return;
    }
    // along the bottom (where Windows puts it), its own height — never taller than a quarter of the screen
    let th = (if w >= h { h } else { 48 }).clamp(24, ((a.bottom - a.top) / 4).max(24));
    unsafe {
        let _ = SetWindowPos(tray, None, a.left, a.bottom - th, a.right - a.left, th, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_ASYNCWINDOWPOS);
    }
}

fn rect_str(r: &RECT) -> String { format!("[{},{} {}×{}]", r.left, r.top, r.right - r.left, r.bottom - r.top) }

static STRAY_TRAYS: std::sync::LazyLock<Mutex<std::collections::HashSet<isize>>> = std::sync::LazyLock::new(|| Mutex::new(std::collections::HashSet::new()));
static STRAY_TOLD: Mutex<Option<Instant>> = Mutex::new(None);

/// When monitors come, go or move, Windows now and then leaves an in-game monitor's taskbar on
/// one of your own screens — tucked under your real taskbar. A screen of yours never has two
/// taskbars: the extra one is hidden (and Windows asked to lay its taskbars out again); it shows
/// again as soon as it's back on its monitor.
fn stray_taskbars(ours: &[String], our_rects: &[RECT]) {
    let center_in = |r: &RECT, a: &RECT| {
        let (cx, cy) = ((r.left + r.right) / 2, (r.top + r.bottom) / 2);
        cx >= a.left && cx < a.right && cy >= a.top && cy < a.bottom
    };
    let real: Vec<RECT> = monitors().into_iter().filter(|(d, _)| !ours.iter().any(|o| o.eq_ignore_ascii_case(d))).map(|(_, r)| r).collect();
    let mut trays: Vec<(HWND, RECT, bool)> = Vec::new(); // (window, where, the main one)
    unsafe {
        for (cls, main) in [("Shell_TrayWnd", true), ("Shell_SecondaryTrayWnd", false)] {
            let class = wide(cls);
            let mut prev: Option<HWND> = None;
            loop {
                let Ok(t) = FindWindowExW(None, prev, PCWSTR(class.as_ptr()), PCWSTR::null()) else { break };
                if t.0.is_null() {
                    break;
                }
                prev = Some(t);
                let mut r = RECT::default();
                if GetWindowRect(t, &mut r).is_ok() {
                    trays.push((t, r, main));
                }
            }
        }
        let mut stray = STRAY_TRAYS.lock().unwrap();
        // back on one of our monitors: it shows again
        for &(t, r, _) in &trays {
            if stray.contains(&(t.0 as isize)) && our_rects.iter().any(|a| center_in(&r, a)) {
                stray.remove(&(t.0 as isize));
                let _ = ShowWindow(t, SW_SHOWNA);
            }
        }
        let mut found = false;
        for screen in &real {
            let here: Vec<&(HWND, RECT, bool)> = trays.iter().filter(|(_, r, _)| center_in(r, screen)).collect();
            if here.len() < 2 {
                continue;
            }
            // keep your main taskbar (or, on another screen of yours, the one along its edge)
            let keep = here.iter().find(|(_, _, main)| *main).or_else(|| {
                here.iter().find(|(_, r, _)| r.right - r.left >= screen.right - screen.left - 2 && (r.bottom == screen.bottom || r.top == screen.top))
            }).map(|(t, _, _)| *t).unwrap_or(here[0].0);
            for &&(t, _, main) in &here {
                if t == keep || main || !IsWindowVisible(t).as_bool() {
                    continue;
                }
                let _ = ShowWindow(t, SW_HIDE);
                stray.insert(t.0 as isize);
                found = true;
            }
        }
        drop(stray);
        if found {
            let mut told = STRAY_TOLD.lock().unwrap();
            if told.map_or(true, |t| t.elapsed() > Duration::from_secs(10)) {
                *told = Some(Instant::now());
                eprintln!("info=an in-game monitor's taskbar ended up on one of your screens: hidden there, Windows asked to put it back");
                tell_taskbar();
            }
        }
    }
}

fn show_hidden_trays() {
    let list: Vec<isize> = hidden_trays().lock().unwrap().drain().collect();
    for t in list {
        unsafe {
            let h = HWND(t as *mut _);
            if IsWindow(Some(h)).as_bool() {
                let _ = ShowWindow(h, SW_SHOWNA);
            }
        }
    }
}

/// Every visible top-level window right now (a hidden one that shows up later counts as new).
fn all_top_windows() -> Vec<isize> {
    unsafe extern "system" fn each(h: HWND, data: LPARAM) -> windows::core::BOOL {
        let list = unsafe { &mut *(data.0 as *mut Vec<isize>) };
        // (a "cloaked" window — a Store app's hidden frame, kept for later — isn't really
        // shown: when it's revealed, that's a window opening)
        if unsafe { IsWindowVisible(h) }.as_bool() && !cloaked(h) {
            list.push(h.0 as isize);
        }
        windows::core::BOOL(1)
    }
    let mut list = Vec::new();
    unsafe {
        let _ = EnumWindows(Some(each), LPARAM(&mut list as *mut _ as isize));
    }
    list
}

/// Windows' own shell (taskbar, Start, search, the desktop itself): never "something else".
fn is_shell(h: HWND) -> bool {
    let c = class_name(h);
    // File Explorer (and Control Panel) windows belong to explorer.exe, but they're windows you
    // use like any app: they go where you open them
    if matches!(c.as_str(), "CabinetWClass" | "ExploreWClass") {
        return false;
    }
    if matches!(c.as_str(), "Progman" | "WorkerW" | "Shell_TrayWnd" | "Shell_SecondaryTrayWnd" | "NotifyIconOverflowWindow"
        | "TopLevelWindowForOverflowXamlIsland" | "XamlExplorerHostIslandWindow") || c.starts_with("Windows.UI.Core")
    {
        return true;
    }
    let p = pid(h);
    p == shell_pid()
        || process_info(p).map_or(false, |(_, e)| {
            matches!(e.as_str(), "startmenuexperiencehost.exe" | "searchhost.exe" | "searchapp.exe" | "shellexperiencehost.exe"
                | "textinputhost.exe" | "explorer.exe" | "shellhost.exe" | "lockapp.exe")
        })
}

fn owned() -> &'static Mutex<HashMap<u32, i32>> { OWNED.get_or_init(|| Mutex::new(HashMap::new())) }
fn placed() -> &'static Mutex<HashMap<isize, i32>> { PLACED.get_or_init(|| Mutex::new(HashMap::new())) }

fn now_filetime() -> u64 {
    let ft = unsafe { windows::Win32::System::SystemInformation::GetSystemTimeAsFileTime() };
    ((ft.dwHighDateTime as u64) << 32) | ft.dwLowDateTime as u64
}

/// When the process was created (FILETIME) and its program's file name (lowercase).
fn process_info(pid: u32) -> Option<(u64, String)> {
    unsafe {
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?;
        let (mut c, mut e, mut k, mut u) = (FILETIME::default(), FILETIME::default(), FILETIME::default(), FILETIME::default());
        let created = GetProcessTimes(h, &mut c, &mut e, &mut k, &mut u).ok().map(|_| ((c.dwHighDateTime as u64) << 32) | c.dwLowDateTime as u64);
        let mut buf = [0u16; 1024];
        let mut n = buf.len() as u32;
        let name = QueryFullProcessImageNameW(h, PROCESS_NAME_WIN32, PWSTR(buf.as_mut_ptr()), &mut n).ok().map(|_| {
            let full = String::from_utf16_lossy(&buf[..n as usize]);
            full.rsplit(['\\', '/']).next().unwrap_or("").to_ascii_lowercase()
        });
        let _ = CloseHandle(h);
        Some((created?, name.unwrap_or_default()))
    }
}

fn monitor_area(idx: i32) -> Option<RECT> {
    AREAS.get().and_then(|a| our_area(a, idx)).map(|(_, r)| r)
}

/// A window that just appeared: does it belong on a monitor? Then it goes there, not activated.
fn on_window_shown(h: HWND, fresh: bool) {
    unsafe {
        if GetAncestor(h, GA_ROOT) != h || !IsWindowVisible(h).as_bool() {
            return;
        }
        if GetWindow(h, GW_OWNER).map_or(false, |o| !o.0.is_null()) {
            return; // an app's dialog: Windows puts it by its owner
        }
        let ex = GetWindowLongW(h, GWL_EXSTYLE) as u32;
        if ex & WS_EX_TOOLWINDOW.0 != 0 || h.0 as isize == SINK.load(Ordering::SeqCst) {
            return;
        }
    }
    let p = pid(h);
    let mut target: Option<i32> = None;
    let at = cursor_monitor();
    // a program in a monitor's own box: its windows belong on that monitor
    if fresh && sbie_dir().is_some() {
        if let Some(i) = monitor_of_box(&box_of(p)) {
            target = Some(i);
        }
    }
    let on_desk = at.is_none() && !SEATED.load(Ordering::SeqCst) && !POINTING.load(Ordering::SeqCst) && !game_in_front();
    // 1. started for a monitor by WindowsCraft (its Start menu, "Own Brave"…): its first window
    // goes there — once (what you open yourself afterwards follows the rules below)
    if fresh {
        let mut launches = LAUNCHES.lock().unwrap();
        launches.retain(|l| Instant::now() < l.until);
        // (on your desk by now: only a launch from the last few seconds still counts)
        if on_desk {
            launches.retain(|l| l.until.saturating_duration_since(Instant::now()) > Duration::from_secs(15));
        }
        if !launches.is_empty() {
            if let Some((created, exe)) = process_info(p) {
                let hit = launches.iter().position(|l| {
                    let same_exe = !l.exe.is_empty() && exe == l.exe;
                    // a program started since (its own process: for Store apps, whose program we
                    // don't know, any program that isn't part of Windows' shell)
                    (created >= l.since && exe != "explorer.exe" && (same_exe || (l.exe.is_empty() && !is_shell(h))))
                        // or one already running, opening the window for this start
                        || (same_exe && !l.before.contains(&(h.0 as isize)))
                });
                if let Some(i) = hit {
                    let l = launches.remove(i);

                    if created >= l.since {
                        owned().lock().unwrap().insert(p, l.idx); // a program started for that monitor: it lives there
                    }
                    target = Some(l.idx);
                    eprintln!("info=\"{}\" is the app started for in-game monitor {}", title(h), l.idx);
                }
            }
        }
    }
    // 2. where YOU opened it: your real cursor is on an in-game monitor (you sit at it, or point
    // at it) → that monitor; it's on your own screens (Start, taskbar, desktop) → your desk, even
    // for an app that also has windows on a monitor, or one that remembers it was last on one
    if target.is_none() && fresh {
        if let Some(i) = at {
            let mut r = RECT::default();
            let big = unsafe { GetWindowRect(h, &mut r).is_ok() } && r.right - r.left >= 160 && r.bottom - r.top >= 120;
            if big && h.0 as isize != GAME.load(Ordering::SeqCst) && !seat_known().lock().unwrap().contains(&(h.0 as isize)) && !is_shell(h) {
                target = Some(i);
            }
        }
    }
    // 3. you're playing (the game in front): something on a monitor opened it — a person using
    // it, or the app itself — so it stays with the monitor its program lives on
    if target.is_none() && !on_desk && fresh {
        target = owned().lock().unwrap().get(&p).copied().or_else(|| {
            let mine = monitor_of_window(h)?;
            let area = monitor_area(mine)?;
            app_windows().into_iter().any(|o| o != h && pid(o) == p && on_area(o, &area)).then_some(mine)
        });
    }
    if fresh && monitor_of_window(h).is_some() != target.is_some() {
        eprintln!("info=new window \"{}\" ({}): {} (cursor on {:?}, seated {}, pointing {}, game in front {}, on your desk {})", title(h), class_name(h),
            target.map_or("your desk".to_string(), |i| format!("in-game monitor {i}")), at, SEATED.load(Ordering::SeqCst),
            POINTING.load(Ordering::SeqCst), game_in_front(), on_desk);
    }
    match target {
        Some(idx) => place_on(h, idx),
        None => {
            // nothing says it belongs on an in-game monitor, yet it opened on one (an app putting
            // itself back where it was last time): it goes to your desk
            if fresh && monitor_of_window(h).is_some() && !is_overlay(h) && !is_shell(h) {
                let home = primary_area();
                eprintln!("info=\"{}\" opened on an in-game monitor by itself: moving it to your desk", title(h));
                place_window(h, &home);
            }
        }
    }
}

// ---- own copies of apps: each in-game monitor's own Sandboxie box ----------------------------------
//
// Windows gives sound per program, and many apps refuse a second copy of themselves. Each
// in-game monitor (with "own copies" on) has its own Sandboxie box: while you sit at it (or point
// at it), whatever you start lands in that box — its own copy of the app, its own sound, its own
// logins and data, kept. Your desk is never touched. Sandboxie's own window never shows, nor does
// any border or marker; WindowsCraft drives it from here.

static ISOLATE: Mutex<Option<HashMap<i32, bool>>> = Mutex::new(None);
/// What never goes in a box: program names, and folders (WindowsCraft's own, games with anti-cheat…).
static ISO_EXES: Mutex<Vec<String>> = Mutex::new(Vec::new());
static ISO_DIRS: Mutex<Vec<String>> = Mutex::new(Vec::new());
/// More folders apps live in (game libraries on other drives): forced along with the usual ones.
static ISO_APPDIRS: Mutex<Vec<String>> = Mutex::new(Vec::new());
/// The box the force rule is on right now (where new programs land), if any.
static FORCED: Mutex<Option<String>> = Mutex::new(None);
static BOXES_MADE: Mutex<Vec<String>> = Mutex::new(Vec::new());
static ISO_THREAD: AtomicBool = AtomicBool::new(false);
static ISO_DIRTY: AtomicBool = AtomicBool::new(false);
const ALWAYS_NORMAL: [&str; 22] = ["explorer.exe", "javaw.exe", "java.exe", "steam.exe", "steamwebhelper.exe", "steamservice.exe",
    "epicgameslauncher.exe", "epicwebhelper.exe", "battle.net.exe", "agent.exe", "riotclientservices.exe", "riotclientux.exe",
    "easyanticheat.exe", "easyanticheat_eos.exe", "beservice.exe", "beservice_x64.exe", "start_protected_game.exe", "vgc.exe",
    "taskmgr.exe", "msiexec.exe", "systemsettings.exe", "minecraftlauncher.exe"];

fn sbie_dir() -> Option<std::path::PathBuf> {
    let d = std::path::PathBuf::from(std::env::var("ProgramW6432").or_else(|_| std::env::var("ProgramFiles")).unwrap_or_else(|_| "C:\\Program Files".into())).join("Sandboxie-Plus");
    d.join("Start.exe").exists().then_some(d)
}

/// "ok", "missing" (not installed) or "stopped" (installed, its service isn't running).
fn sbie_status() -> &'static str {
    if sbie_dir().is_none() {
        "missing"
    } else if service_running("SbieSvc") {
        "ok"
    } else {
        "stopped"
    }
}

fn service_running(name: &str) -> bool {
    std::process::Command::new("sc").args(["query", name]).output().map_or(false, |o| String::from_utf8_lossy(&o.stdout).contains("RUNNING"))
}

fn box_name(idx: i32) -> String {
    let k: String = profile_key(idx).chars().filter(|c| c.is_ascii_alphanumeric()).take(28).collect();
    format!("WC{k}")
}

fn isolated(idx: i32) -> bool { ISOLATE.lock().unwrap().as_ref().and_then(|m| m.get(&idx).copied()).unwrap_or(false) }

/// Runs Sandboxie's launcher (never while a force rule could box it: only for "open another copy",
/// closing and wiping a box). Its output isn't kept: a program it starts would hold that open.
fn sbie_start(args: &[&str]) -> bool {
    let Some(d) = sbie_dir() else { return false };
    let mut c = std::process::Command::new(d.join("Start.exe"));
    c.args(args).stdin(std::process::Stdio::null()).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null());
    use std::os::windows::process::CommandExt;
    c.creation_flags(0x0800_0000);
    let Ok(mut child) = c.spawn() else { return false };
    let t0 = Instant::now();
    while t0.elapsed() < Duration::from_secs(15) {
        if let Ok(Some(st)) = child.try_wait() {
            eprintln!("info=Sandboxie's launcher ({}) finished: {st} after {} ms", args.join(" "), t0.elapsed().as_millis());
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    true // (still starting the program: fine)
}

// The rules live in Sandboxie.ini, in our own sections only ([WC…]); they're written straight into
// the file and applied through Sandboxie's own interface (SbieApi_ReloadConf). Its tools are never
// started for this: a program started while a force rule is on could itself land in the box.

fn ini_path() -> std::path::PathBuf {
    std::path::Path::new(&std::env::var("WINDIR").unwrap_or_else(|_| "C:\\Windows".into())).join("Sandboxie.ini")
}

fn read_ini() -> Vec<String> {
    let Ok(b) = std::fs::read(ini_path()) else { return Vec::new() };
    let text = if b.len() >= 2 && b[0] == 0xFF && b[1] == 0xFE {
        String::from_utf16_lossy(&b[2..].chunks(2).map(|c| u16::from_le_bytes([c[0], *c.get(1).unwrap_or(&0)])).collect::<Vec<_>>())
    } else {
        String::from_utf8_lossy(&b).into_owned()
    };
    text.lines().map(|l| l.trim_end_matches('\r').to_string()).collect()
}

fn write_ini(lines: &[String]) -> bool {
    let mut out: Vec<u8> = vec![0xFF, 0xFE];
    for l in lines {
        for u in format!("{l}\r\n").encode_utf16() {
            out.extend_from_slice(&u.to_le_bytes());
        }
    }
    let tmp = ini_path().with_extension("ini.wc");
    std::fs::write(&tmp, &out).is_ok() && std::fs::rename(&tmp, ini_path()).is_ok()
}

/// Sets that box's section to exactly these settings (made if it isn't there), keeping the rest of the file.
fn set_section(name: &str, settings: &[(String, String)]) {
    let lines = read_ini();
    let mut out = Vec::new();
    let mut i = 0;
    let header = format!("[{name}]");
    let mut found = false;
    while i < lines.len() {
        if lines[i].trim().eq_ignore_ascii_case(&header) {
            found = true;
            i += 1;
            while i < lines.len() && !lines[i].trim_start().starts_with('[') {
                i += 1; // (our section: replaced as a whole)
            }
            out.push(header.clone());
            out.extend(settings.iter().map(|(k, v)| format!("{k}={v}")));
            out.push(String::new());
            continue;
        }
        out.push(lines[i].clone());
        i += 1;
    }
    if !found {
        out.push(String::new());
        out.push(header);
        out.extend(settings.iter().map(|(k, v)| format!("{k}={v}")));
    }
    write_ini(&out);
}

fn reload_conf() -> bool {
    static RELOAD: OnceLock<Option<unsafe extern "system" fn(u32, u32) -> i32>> = OnceLock::new();
    let f = RELOAD.get_or_init(|| unsafe {
        let d = sbie_dir()?;
        let dll = wide(&d.join("SbieDll.dll").display().to_string());
        let h = windows::Win32::System::LibraryLoader::LoadLibraryW(PCWSTR(dll.as_ptr())).ok()?;
        let p = windows::Win32::System::LibraryLoader::GetProcAddress(h, windows::core::s!("SbieApi_ReloadConf"))?;
        Some(std::mem::transmute::<_, unsafe extern "system" fn(u32, u32) -> i32>(p))
    });
    match f {
        Some(f) => unsafe { f(u32::MAX, 0) == 0 },
        None => false,
    }
}

/// Where your apps are installed: what's forced into the monitor's box while you sit there
/// (never all of a drive — Windows, Sandboxie and WindowsCraft stay out).
fn app_folders() -> Vec<String> {
    let mut v = Vec::new();
    for k in ["ProgramW6432", "ProgramFiles(x86)", "LOCALAPPDATA", "APPDATA"] {
        if let Ok(d) = std::env::var(k) {
            if !v.contains(&d) && std::path::Path::new(&d).is_dir() {
                v.push(d);
            }
        }
    }
    v.extend(ISO_APPDIRS.lock().unwrap().iter().cloned()); // (game libraries elsewhere: D:\SteamLibrary…)
    v
}

/// Programs that always run normally (never in a box), lower-case names.
fn breakout_exes() -> Vec<String> {
    let mut exes: Vec<String> = ALWAYS_NORMAL.iter().map(|s| s.to_string()).collect();
    exes.extend(ISO_EXES.lock().unwrap().iter().cloned());
    exes.iter().map(|e| e.to_lowercase()).collect()
}

/// Folders whose programs always run normally: the game's, Java's, anti-cheat games', yours,
/// WindowsCraft's. (Not Sandboxie's own: its launcher has to run inside a box to start a program
/// there — let out, it never gets there. Nor Windows': a boxed app's own little helpers from there
/// belong in its box with it. Neither folder is ever forced into a box anyway.)
fn breakout_dirs() -> Vec<String> {
    let mut dirs: Vec<String> = ISO_DIRS.lock().unwrap().clone();
    if let Ok(me) = std::env::current_exe() {
        if let Some(d) = me.parent() {
            dirs.push(d.display().to_string());
        }
    }
    dirs
}

// Sandboxie can't make an exception inside a forced folder (its driver has none: a program in a
// forced folder is always boxed). So the rule is "carved": an app folder is forced whole unless an
// exception lives in it — then its sub-folders are, one by one, around the exception. Worked out
// in the background and kept; new folders are picked up on the next pass.
static CARVED: Mutex<Option<(String, Instant, Vec<String>)>> = Mutex::new(None);
static CARVING: AtomicBool = AtomicBool::new(false);

fn norm_dir(p: &str) -> String {
    p.replace('/', "\\").trim_end_matches('\\').to_lowercase()
}

fn carve_sig() -> String {
    format!("{:?}|{:?}|{:?}", app_folders(), breakout_dirs(), breakout_exes())
}

/// None: nothing in here is an exception (force all of it). Some(list): the parts to force.
fn carve_walk(dir: &std::path::Path, lower: &str, depth: u32, max_depth: u32, ex: &[String], names: &std::collections::HashSet<String>, budget: &mut usize) -> Option<Vec<String>> {
    use std::os::windows::fs::MetadataExt;
    if ex.iter().any(|e| e == lower) {
        return Some(Vec::new());
    }
    let pre = format!("{lower}\\");
    let ex_inside = ex.iter().any(|e| e.starts_with(&pre));
    if (depth > max_depth || *budget == 0) && !ex_inside {
        return None;
    }
    *budget = budget.saturating_sub(1);
    let Ok(rd) = std::fs::read_dir(dir) else { return if ex_inside { Some(Vec::new()) } else { None } };
    let mut kids = Vec::new();
    for e in rd.flatten() {
        let Ok(md) = e.metadata() else { continue };
        if md.is_dir() {
            if md.file_attributes() & 0x400 == 0 {
                kids.push(e.path()); // (not links: they point elsewhere)
            }
        } else if names.contains(&e.file_name().to_string_lossy().to_lowercase()) {
            return Some(Vec::new()); // an always-normal program lives here: none of this folder
        }
    }
    let mut parts = Vec::new();
    let mut clean = !ex_inside;
    for k in kids {
        let kl = format!("{pre}{}", k.file_name().map(|n| n.to_string_lossy().to_lowercase()).unwrap_or_default());
        match carve_walk(&k, &kl, depth + 1, max_depth, ex, names, budget) {
            None => parts.push(k.display().to_string()),
            Some(v) => {
                clean = false;
                parts.extend(v);
            }
        }
    }
    if clean { None } else { Some(parts) }
}

/// The app folders, carved around the exceptions — looking `max_depth` folders deep for programs
/// that must run normally (deeper ones are assumed not to be there).
fn carve(max_depth: u32, budget: usize) -> Vec<String> {
    let t0 = Instant::now();
    let ex: Vec<String> = breakout_dirs().iter().map(|d| norm_dir(d)).filter(|d| !d.is_empty()).collect();
    let names: std::collections::HashSet<String> = breakout_exes().into_iter().collect();
    let mut left = budget;
    let mut out: Vec<String> = Vec::new();
    for root in app_folders() {
        let rl = norm_dir(&root);
        if ex.iter().any(|e| rl == *e || rl.starts_with(&format!("{e}\\"))) {
            continue;
        }
        match carve_walk(std::path::Path::new(root.trim_end_matches('\\')), &rl, 0, max_depth, &ex, &names, &mut left) {
            None => out.push(root.trim_end_matches('\\').to_string()),
            Some(v) => out.extend(v),
        }
    }
    // (an app folder inside another: once is enough)
    out.sort_by_key(|d| norm_dir(d));
    out.dedup_by_key(|d| norm_dir(d));
    let mut kept: Vec<String> = Vec::new();
    for d in out {
        let l = norm_dir(&d);
        if !kept.iter().any(|k| l.starts_with(&format!("{}\\", norm_dir(k)))) {
            kept.push(d);
        }
    }
    eprintln!("info=own copies: {} app folders to box (looked {max_depth} deep, {} folders, {} ms{})", kept.len(), budget - left,
        t0.elapsed().as_millis(), if left == 0 { ", stopped there" } else { "" });
    kept
}

/// The folders whose programs land in the box while you sit there (worked out ahead of time;
/// only if that hasn't happened yet is it waited for, briefly).
fn force_folders() -> Vec<String> {
    let sig = carve_sig();
    if let Some((s, at, v)) = CARVED.lock().unwrap().as_ref() {
        if *s == sig {
            if at.elapsed() > Duration::from_secs(300) {
                refresh_carve();
            }
            return v.clone();
        }
    }
    refresh_carve();
    let t0 = Instant::now();
    while t0.elapsed() < Duration::from_secs(3) {
        std::thread::sleep(Duration::from_millis(20));
        if let Some((s, _, v)) = CARVED.lock().unwrap().as_ref() {
            if *s == sig {
                return v.clone();
            }
        }
    }
    carve(1, 400) // (still not there: a shallow look now)
}

/// Works the folders out (again) in the background, at low priority: a quick look first, then a
/// deeper one. When they've changed while you sit somewhere, the rule is rewritten.
fn refresh_carve() {
    if CARVING.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(|| {
        unsafe {
            let _ = windows::Win32::System::Threading::SetThreadPriority(windows::Win32::System::Threading::GetCurrentThread(),
                windows::Win32::System::Threading::THREAD_MODE_BACKGROUND_BEGIN);
        }
        loop {
            let sig = carve_sig();
            for (depth, budget) in [(2u32, 4_000usize), (5, 40_000)] {
                let v = carve(depth, budget);
                if carve_sig() != sig {
                    break; // (the exceptions changed meanwhile: start over)
                }
                let mut c = CARVED.lock().unwrap();
                let changed = c.as_ref().map_or(true, |(s, _, old)| *s != sig || *old != v);
                *c = Some((sig.clone(), Instant::now(), v));
                drop(c);
                if changed {
                    ISO_DIRTY.store(true, Ordering::SeqCst);
                }
            }
            if carve_sig() == sig {
                break;
            }
        }
        CARVING.store(false, Ordering::SeqCst);
    });
}

/// A monitor's box: no border, no [#] in titles, kept (never wiped on its own), its exceptions —
/// and, while you sit at it, its force rule.
fn box_settings(name: &str, forced: bool) -> Vec<(String, String)> {
    let mut s: Vec<(String, String)> = [("Enabled", "y"), ("BorderColor", "#000000,off,0"), ("BoxNameTitle", "-"), ("AutoDelete", "n"), ("ConfigLevel", "10")]
        .iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
    // (these keep programs a boxed app starts out of the box; forced folders are carved around them)
    for e in breakout_exes() {
        s.push(("BreakoutProcess".into(), e));
    }
    for d in breakout_dirs() {
        s.push(("BreakoutFolder".into(), d));
    }
    if forced {
        for d in force_folders() {
            s.push(("ForceFolder".into(), d));
        }
    }
    let _ = name;
    s
}

fn ensure_box(name: &str) {
    if !BOXES_MADE.lock().unwrap().iter().any(|b| b == name) {
        BOXES_MADE.lock().unwrap().push(name.to_string());
    }
    let forced = FORCED.lock().map(|f| f.as_deref() == Some(name)).unwrap_or(false);
    set_section(name, &box_settings(name, forced));
}

/// The box new programs should land in now: the monitor you sit at (or point at), if it has
/// "own copies" on.
fn wanted_force() -> Option<String> {
    if sbie_dir().is_none() {
        return None;
    }
    let idx = if SEATED.load(Ordering::SeqCst) || (POINTING.load(Ordering::SeqCst) && POINT_ME.load(Ordering::SeqCst)) {
        SEAT_IDX.load(Ordering::SeqCst)
    } else {
        return None;
    };
    isolated(idx).then(|| box_name(idx))
}

fn set_force(to: Option<String>) {
    let mut cur = FORCED.lock().unwrap();
    if *cur == to && !ISO_DIRTY.swap(false, Ordering::SeqCst) {
        return;
    }
    let t0 = Instant::now();
    let old = cur.clone();
    *cur = to.clone();
    drop(cur);
    if let Some(o) = old.as_ref() {
        if Some(o) != to.as_ref() {
            set_section(o, &box_settings(o, false));
        }
    }
    if let Some(b) = to.as_ref() {
        if !BOXES_MADE.lock().unwrap().iter().any(|x| x == b) {
            BOXES_MADE.lock().unwrap().push(b.clone());
        }
        set_section(b, &box_settings(b, true));
    }
    let ok = reload_conf();
    eprintln!("info=new programs now start {} ({} ms{})", to.as_ref().map(|b| format!("in box {b}")).unwrap_or_else(|| "normally".into()),
        t0.elapsed().as_millis(), if ok { "" } else { ", Sandboxie didn't take it" });
}

/// Follows where you are (sitting, pointing, which monitor) and moves the force rule with you.
fn ensure_iso_thread() {
    if ISO_THREAD.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(|| loop {
        std::thread::sleep(Duration::from_millis(60));
        let want = wanted_force();
        set_force(want);
    });
}

/// No force rule left behind (after a crash, at the start, at the end): every WindowsCraft box.
fn clear_all_force() {
    if sbie_dir().is_none() {
        return;
    }
    let lines = read_ini();
    let mut section = String::new();
    let mut out = Vec::with_capacity(lines.len());
    let mut any = false;
    for l in &lines {
        let t = l.trim();
        if t.starts_with('[') && t.ends_with(']') {
            section = t[1..t.len() - 1].to_string();
        } else if section.starts_with("WC") && t.to_ascii_lowercase().starts_with("forcefolder=") {
            any = true;
            continue;
        }
        out.push(l.clone());
    }
    if any {
        write_ini(&out);
        reload_conf();
        eprintln!("info=a leftover rule that boxed new programs was removed");
    }
    *FORCED.lock().unwrap() = None;
}

/// The box a process runs in ("" if none), from Sandboxie itself.
fn box_of(pid: u32) -> String {
    static QUERY: OnceLock<Option<unsafe extern "system" fn(HANDLE, *mut u16, *mut u16, *mut u16, *mut u32) -> i32>> = OnceLock::new();
    let f = QUERY.get_or_init(|| unsafe {
        let d = sbie_dir()?;
        let dll = wide(&d.join("SbieDll.dll").display().to_string());
        let h = windows::Win32::System::LibraryLoader::LoadLibraryW(PCWSTR(dll.as_ptr())).ok()?;
        let p = windows::Win32::System::LibraryLoader::GetProcAddress(h, windows::core::s!("SbieApi_QueryProcess"))?;
        Some(std::mem::transmute::<_, unsafe extern "system" fn(HANDLE, *mut u16, *mut u16, *mut u16, *mut u32) -> i32>(p))
    });
    let Some(f) = f else { return String::new() };
    let (mut b, mut i, mut s) = ([0u16; 64], [0u16; 128], [0u16; 128]);
    let mut ses = 0u32;
    let r = unsafe { f(HANDLE(pid as usize as *mut _), b.as_mut_ptr(), i.as_mut_ptr(), s.as_mut_ptr(), &mut ses) };
    if r != 0 {
        return String::new();
    }
    String::from_utf16_lossy(&b[..b.iter().position(|c| *c == 0).unwrap_or(0)])
}

/// The monitor a box belongs to.
fn monitor_of_box(name: &str) -> Option<i32> {
    if name.is_empty() {
        return None;
    }
    let ids: Vec<i32> = AREAS.get()?.lock().unwrap().keys().copied().collect();
    ids.into_iter().find(|i| box_name(*i).eq_ignore_ascii_case(name))
}

/// Starts a program in that monitor's box ("Open another copy here").
fn box_launch(idx: i32, file: &str, params: &str) -> bool {
    if sbie_dir().is_none() || monitor_area(idx).is_none() {
        return false;
    }
    let b = box_name(idx);
    ensure_box(&b);
    reload_conf();
    let exe = file.rsplit(['\\', '/']).next().unwrap_or("").to_ascii_lowercase();
    LAUNCHES.lock().unwrap().push(Launch {
        idx,
        since: now_filetime().saturating_sub(10_000_000),
        exe: exe.clone(),
        before: app_windows().into_iter().map(|h| h.0 as isize).collect(),
        until: Instant::now() + Duration::from_secs(30),
    });
    ensure_placer();
    let mut args: Vec<String> = vec![format!("/box:{b}"), "/silent".into(), file.to_string()];
    if !params.trim().is_empty() {
        args.extend(split_args(params));
    }
    let refs: Vec<&str> = args.iter().map(|s| s.as_str()).collect();
    let ok = sbie_start(&refs);
    eprintln!("info=opening {exe} in box {b}: {}", if ok { "started" } else { "Sandboxie's launcher didn't start" });
    ok
}

fn split_args(s: &str) -> Vec<String> {
    let mut v = Vec::new();
    let mut cur = String::new();
    let mut q = false;
    for c in s.chars() {
        match c {
            '"' => q = !q,
            ' ' if !q => {
                if !cur.is_empty() {
                    v.push(std::mem::take(&mut cur));
                }
            }
            _ => cur.push(c),
        }
    }
    if !cur.is_empty() {
        v.push(cur);
    }
    v
}

/// Every program in that monitor's box asked to close (as you'd close them), the rest ended after a while.
fn close_box(idx: i32, wait: Duration) {
    if sbie_dir().is_none() {
        return;
    }
    let b = box_name(idx);
    let wins: Vec<HWND> = app_windows().into_iter().filter(|w| box_of(pid(*w)).eq_ignore_ascii_case(&b)).collect();
    for w in &wins {
        unsafe {
            let _ = PostMessageW(Some(*w), WM_CLOSE, WPARAM(0), LPARAM(0));
        }
    }
    let t0 = Instant::now();
    while t0.elapsed() < wait && app_windows().into_iter().any(|w| box_of(pid(w)).eq_ignore_ascii_case(&b)) {
        std::thread::sleep(Duration::from_millis(200));
    }
    sbie_start(&[&format!("/box:{b}"), "/terminate"]);
}

// ---- a monitor's own browser ("Own Brave" in its menu) -----------------------------------------
//
// A separate copy of that browser for this monitor: its own program (so its own sound), its own
// profile kept for this wall. Nothing is copied from your desk browser, and it's never restarted.

static PROFILE_KEYS: Mutex<Option<HashMap<i32, String>>> = Mutex::new(None);

fn profile_key(idx: i32) -> String {
    PROFILE_KEYS.lock().unwrap().as_ref().and_then(|m| m.get(&idx).cloned()).unwrap_or_else(|| format!("monitor{idx}"))
}

fn own_browser(idx: i32, exe_path: &str) -> bool {
    let exe = exe_path.rsplit(['\\', '/']).next().unwrap_or("").to_ascii_lowercase();
    let base = std::env::var("LOCALAPPDATA").unwrap_or_else(|_| std::env::temp_dir().display().to_string());
    let dir = std::path::Path::new(&base).join("WindowsCraft").join("profiles").join(format!("{}-{}", exe.trim_end_matches(".exe"), profile_key(idx)));
    let _ = std::fs::create_dir_all(&dir);
    let params = format!("--user-data-dir=\"{}\" --no-first-run --no-default-browser-check --new-window", dir.display());
    launch(idx, exe_path, &params, &exe)
}

/// Which in-game monitor your real cursor is on (None: your own screens).
fn cursor_monitor() -> Option<i32> {
    let mut c = POINT::default();
    unsafe {
        let _ = GetCursorPos(&mut c);
    }
    let added = AREAS.get()?;
    let ids: Vec<i32> = added.lock().unwrap().keys().copied().collect();
    ids.into_iter().find(|i| monitor_area(*i).map_or(false, |r| c.x >= r.left && c.x < r.right && c.y >= r.top && c.y < r.bottom))
}

/// Which in-game monitor that window is on, if any.
fn monitor_of_window(h: HWND) -> Option<i32> {
    let added = AREAS.get()?;
    let ids: Vec<i32> = added.lock().unwrap().keys().copied().collect();
    ids.into_iter().find(|i| monitor_area(*i).map_or(false, |r| on_area(h, &r)))
}

/// Is Minecraft the window in front (you're playing, not on your desktop)?
fn game_in_front() -> bool {
    unsafe {
        let f = GetForegroundWindow();
        if f.0.is_null() {
            return false;
        }
        let root = GetAncestor(f, GA_ROOT);
        root.0 as isize == GAME.load(Ordering::SeqCst) || class_name(root) == "GLFW30"
    }
}

/// Your main screen (where the taskbar with the clock is).
fn primary_area() -> RECT {
    unsafe {
        let m = MonitorFromPoint(POINT { x: 0, y: 0 }, MONITOR_DEFAULTTOPRIMARY);
        let mut mi = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
        let _ = GetMonitorInfoW(m, &mut mi);
        mi.rcMonitor
    }
}

/// While you sit at a monitor, an app that was already open on your desk comes forward (you
/// started it again from the monitor's Start menu or taskbar, and it's one that only ever runs
/// once — Discord, Spotify, WhatsApp…): its window comes onto the monitor instead of pulling you
/// to your desk. It goes back to exactly where it was when the monitor turns off (or with Move →
/// Your desk), and it's never closed for it — it was yours before.
fn on_window_activated(h: HWND) {
    if !SEATED.load(Ordering::SeqCst) || h.0 as isize == GAME.load(Ordering::SeqCst) || h.0 as isize == SINK.load(Ordering::SeqCst) {
        return;
    }
    unsafe {
        if !IsWindowVisible(h).as_bool() || IsIconic(h).as_bool() || GetWindow(h, GW_OWNER).map_or(false, |o| !o.0.is_null()) {
            return;
        }
        let ex = GetWindowLongW(h, GWL_EXSTYLE) as u32;
        if ex & WS_EX_TOOLWINDOW.0 != 0 {
            return;
        }
    }
    if is_shell(h) || !seat_known().lock().unwrap().contains(&(h.0 as isize)) {
        return; // the shell itself, or a new window (that one goes there by itself)
    }
    let idx = SEAT_IDX.load(Ordering::SeqCst);
    if cursor_monitor() != Some(idx) {
        return; // (you're on your own screens: what you bring forward there stays there)
    }
    // only when you started it from the monitor's own Start or taskbar just now (not Alt+Tab,
    // not an app bringing itself forward)
    if SEAT_SHELL_AT.lock().unwrap().map_or(true, |t| t.elapsed() > Duration::from_secs(8)) {
        return;
    }
    let Some(area) = monitor_area(idx) else { return };
    if on_area(h, &area) {
        *LAST_SEAT_FRONT.lock().unwrap() = h.0 as isize;
        return;
    }
    if !app_windows().contains(&h) {
        return;
    }
    if sbie_dir().is_some() && !box_of(pid(h)).is_empty() {
        return; // (a copy in another monitor's box: that one stays where it is)
    }
    // an app that only runs once (Discord, WhatsApp, Calculator… any of them), already open on
    // your desk or another monitor, came forward there instead of opening here: it comes over to
    // this monitor — in the middle, at a modest size, in front — in one move
    let elsewhere = if monitor_of_window(h).is_some() { "another in-game monitor" } else { "your desk" };
    eprintln!("info=\"{}\" only runs once and was open on {elsewhere}: bringing it over to in-game monitor {idx}", title(h));
    bring_over(h, idx);
    *LAST_SEAT_FRONT.lock().unwrap() = h.0 as isize;
}

/// Brings a window that's somewhere else onto monitor `idx`: centred, a modest size (about
/// 60 × 65 % of the screen; the app's own minimum if it needs more), not maximized.
fn bring_over(h: HWND, idx: i32) {
    let Some(area) = monitor_area(idx) else { return };
    if admin_window(h) {
        if ADMIN_TOLD.lock().unwrap().insert(pid(h)) {
            emit("adminapp");
        }
        return;
    }
    let work = work_area(&area);
    let (aw, ah) = (work.right - work.left, work.bottom - work.top);
    let spot = move |w: i32, hh: i32| {
        let (w, hh) = (w.min(aw), hh.min(ah));
        let left = work.left + (aw - w) / 2;
        let top = work.top + (ah - hh) / 2;
        RECT { left, top, right: left + w, bottom: top + hh }
    };
    // like "Bring here": from your desk, it remembers its spot there (and goes back to it, not
    // closed, when the monitor turns off); from another monitor, it lives on this one now
    if monitor_of_window(h).is_some() {
        let mut pl = placed().lock().unwrap();
        if pl.contains_key(&(h.0 as isize)) {
            pl.insert(h.0 as isize, idx);
        }
    } else if let Some(homes) = HOMES.get() {
        let mut homes = homes.lock().unwrap();
        if !homes.contains_key(&(h.0 as isize)) {
            if let Some(p) = placement(h) {
                homes.insert(h.0 as isize, p);
            }
        }
    }
    {
        let mut w = watch().lock().unwrap();
        w.retain(|(x, _, _)| *x != h.0 as isize);
        w.push((h.0 as isize, idx, Instant::now() + Duration::from_secs(4)));
    }
    apply_place(h, &spot(aw * 3 / 5, ah * 13 / 20), false);
    // once it's there (an app may insist on a bigger minimum, or resize for the monitor's
    // scaling): centred again by the size it really has
    let hv = h.0 as isize;
    std::thread::spawn(move || {
        for _ in 0..3 {
            std::thread::sleep(Duration::from_millis(120));
            let h = HWND(hv as *mut _);
            let mut r = RECT::default();
            if unsafe { GetWindowRect(h, &mut r) }.is_err() {
                return;
            }
            let s = spot(r.right - r.left, r.bottom - r.top);
            if (s.left - r.left).abs() > 2 || (s.top - r.top).abs() > 2 {
                unsafe {
                    let _ = SetWindowPos(h, None, s.left, s.top, 0, 0, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_NOSIZE | SWP_ASYNCWINDOWPOS);
                }
            }
        }
    });
}

/// When you last used your own Start menu, taskbar or desktop (on your real screens).
static DESK_SHELL_AT: Mutex<Option<Instant>> = Mutex::new(None);

/// You're at your real desktop: you start (from your own Start menu, taskbar or desktop) an app
/// that only runs once and is open on an in-game monitor — it comes over to your desk, in the
/// middle at a modest size (the same as the other way round).
fn on_desk_activated(h: HWND) {
    if cursor_monitor().is_some() {
        return; // (the cursor's on a monitor: not you at your desk)
    }
    if is_shell(h) {
        if monitor_of_window(h).is_none() {
            *DESK_SHELL_AT.lock().unwrap() = Some(Instant::now());
        }
        return;
    }
    if h.0 as isize == GAME.load(Ordering::SeqCst) || h.0 as isize == SINK.load(Ordering::SeqCst) || game_in_front_root(h) {
        return;
    }
    if DESK_SHELL_AT.lock().unwrap().map_or(true, |t| t.elapsed() > Duration::from_secs(8)) {
        return;
    }
    let Some(idx) = monitor_of_window(h) else { return };
    if !app_windows().contains(&h) {
        return;
    }
    *DESK_SHELL_AT.lock().unwrap() = None;
    eprintln!("info=\"{}\" only runs once and was open on in-game monitor {idx}: bringing it over to your desk", title(h));
    placed().lock().unwrap().remove(&(h.0 as isize));
    owned().lock().unwrap().remove(&pid(h)); // (it's yours on your desk now: the monitor turning off leaves it be)
    watch().lock().unwrap().retain(|(x, _, _)| *x != h.0 as isize);
    if let Some(homes) = HOMES.get() {
        homes.lock().unwrap().remove(&(h.0 as isize)); // (it's at home now)
    }
    let home = primary_area();
    place_centred(h, &home);
}

fn game_in_front_root(h: HWND) -> bool { class_name(h) == "GLFW30" }

/// Centred on that screen's work area, a modest size (about 60 × 65 %; more if the app needs it).
fn place_centred(h: HWND, area: &RECT) {
    if admin_window(h) {
        return;
    }
    let work = work_area(area);
    let (aw, ah) = (work.right - work.left, work.bottom - work.top);
    let spot = move |w: i32, hh: i32| {
        let (w, hh) = (w.min(aw), hh.min(ah));
        let left = work.left + (aw - w) / 2;
        let top = work.top + (ah - hh) / 2;
        RECT { left, top, right: left + w, bottom: top + hh }
    };
    apply_place(h, &spot(aw * 3 / 5, ah * 13 / 20), false);
    let hv = h.0 as isize;
    std::thread::spawn(move || {
        for _ in 0..3 {
            std::thread::sleep(Duration::from_millis(120));
            let h = HWND(hv as *mut _);
            let mut r = RECT::default();
            if unsafe { GetWindowRect(h, &mut r) }.is_err() {
                return;
            }
            let s = spot(r.right - r.left, r.bottom - r.top);
            if (s.left - r.left).abs() > 2 || (s.top - r.top).abs() > 2 {
                unsafe {
                    let _ = SetWindowPos(h, None, s.left, s.top, 0, 0, SWP_NOZORDER | SWP_NOACTIVATE | SWP_NOOWNERZORDER | SWP_NOSIZE | SWP_ASYNCWINDOWPOS);
                }
            }
        }
    });
}

/// The window last in front on the monitor you sit at (where the keyboard goes back to).
static LAST_SEAT_FRONT: Mutex<isize> = Mutex::new(0);

/// Puts a window onto monitor `idx` (not activated) and keeps an eye on it for a few seconds.
fn place_on(h: HWND, idx: i32) {
    let Some(area) = monitor_area(idx) else { return };
    placed().lock().unwrap().insert(h.0 as isize, idx);
    {
        let mut w = watch().lock().unwrap();
        if !w.iter().any(|(x, _, _)| *x == h.0 as isize) {
            w.push((h.0 as isize, idx, Instant::now() + Duration::from_secs(4)));
        }
    }
    if on_area(h, &area) || is_overlay(h) {
        return;
    }
    eprintln!("info=moving \"{}\" ({}) onto in-game monitor {idx}", title(h), class_name(h));
    place_window(h, &area);
}

unsafe extern "system" fn win_event(_: windows::Win32::UI::Accessibility::HWINEVENTHOOK, event: u32, h: HWND, obj: i32, child: i32, _: u32, _: u32) {
    if h.0.is_null() {
        return;
    }
    if event == 0x8002 && obj == 0 && class_name(h) == "Shell_SecondaryTrayWnd" {
        // Windows put a monitor's taskbar up again: over a full-screen video it goes straight away
        tidy_taskbars();
        return;
    }
    if event == 0x0003 {
        tidy_soon();
        let root = unsafe { GetAncestor(h, GA_ROOT) };
        if !root.0.is_null() && !SEATED.load(Ordering::SeqCst) && !POINTING.load(Ordering::SeqCst) {
            on_desk_activated(root);
        }
        if !root.0.is_null() && POINTING.load(Ordering::SeqCst) && !SEATED.load(Ordering::SeqCst) && KEEP_GAME_LOOK.load(Ordering::SeqCst) {
            keep_game_looking_active(root);
        }
        if !root.0.is_null() && SEATED.load(Ordering::SeqCst) {
            if is_shell(root) && on_area(root, &*CLIP.lock().unwrap()) {
                *SEAT_SHELL_AT.lock().unwrap() = Some(Instant::now()); // (you're using the monitor's own Start or taskbar)
            }
            on_window_shown(root, false);
            on_window_activated(root);
        }
    } else if obj == 0 && child == 0 {
        on_window_shown(h, true);
    }
}

/// Pointing: an app on the monitor came to the front from your click. The game keeps its active
/// title bar (it still gets your keys), instead of flashing to the "not in front" colour.
fn keep_game_looking_active(front: HWND) {
    let game = GAME.load(Ordering::SeqCst);
    if game == 0 || front.0 as isize == game {
        return;
    }
    unsafe {
        let _ = PostMessageW(Some(HWND(game as *mut _)), WM_NCACTIVATE, WPARAM(1), LPARAM(0));
    }
}

/// The hook that sees windows appear (started once, lives with the helper).
fn ensure_placer() {
    if PLACER.swap(true, Ordering::SeqCst) {
        return;
    }
    std::thread::spawn(|| unsafe {
        const EVENT_OBJECT_SHOW: u32 = 0x8002;
        const EVENT_OBJECT_UNCLOAKED: u32 = 0x8018;
        const EVENT_SYSTEM_FOREGROUND: u32 = 0x0003;
        let hook = windows::Win32::UI::Accessibility::SetWinEventHook(EVENT_OBJECT_SHOW, EVENT_OBJECT_SHOW, None, Some(win_event), 0, 0,
            0 /* WINEVENT_OUTOFCONTEXT */ | 2 /* WINEVENT_SKIPOWNPROCESS */);
        // a window that comes forward (some apps show a window first and only bring it up later)
        let hook2 = windows::Win32::UI::Accessibility::SetWinEventHook(EVENT_SYSTEM_FOREGROUND, EVENT_SYSTEM_FOREGROUND, None, Some(win_event), 0, 0,
            0 | 2);
        // Store apps (Calculator, Settings…) keep a hidden "cloaked" frame and reveal it: that's it opening
        let hook3 = windows::Win32::UI::Accessibility::SetWinEventHook(EVENT_OBJECT_UNCLOAKED, EVENT_OBJECT_UNCLOAKED, None, Some(win_event), 0, 0,
            0 | 2);
        let _ = hook3;
        if hook.is_invalid() || hook2.is_invalid() {
            eprintln!("error=can't watch for new windows");
        }
        let _timer = SetTimer(None, 0, 150, None);
        let mut msg = MSG::default();
        let mut ticks = 0u32;
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            if msg.message == WM_TIMER {
                ticks += 1;
                if ticks % 3 == 0 {
                    tidy_taskbars();
                }

                // windows just placed: if the app moved one away again, back it goes
                let now = Instant::now();
                let list: Vec<(isize, i32, Instant)> = {
                    let mut w = watch().lock().unwrap();
                    w.retain(|(_, _, until)| now < *until);
                    w.clone()
                };
                // (never while you're dragging it — and once you've taken it elsewhere yourself,
                // a joined monitor or anywhere, it stays where you put it)
                let mouse_down = (GetAsyncKeyState(VK_LBUTTON.0 as i32) as u16 & 0x8000) != 0;
                for (h, idx, _) in list {
                    let hw = HWND(h as *mut _);
                    if !IsWindow(Some(hw)).as_bool() {
                        continue;
                    }
                    if mouse_down && SEATED.load(Ordering::SeqCst) {
                        watch().lock().unwrap().retain(|(x, _, _)| *x != h);
                        continue;
                    }
                    if let Some(area) = monitor_area(idx) {
                        if !IsIconic(hw).as_bool() && !on_area(hw, &area) {
                            if monitor_of_window(hw).map_or(false, |m| joined(idx, m)) {
                                watch().lock().unwrap().retain(|(x, _, _)| *x != h);
                                continue;
                            }
                            place_on(hw, idx);
                        }
                    }
                }
            }
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    });
}

fn launch(idx: i32, file: &str, params: &str, exe: &str) -> bool {
    let Some(area) = monitor_area(idx) else { return false };
    ensure_placer();
    LAUNCHES.lock().unwrap().push(Launch {
        idx,
        since: now_filetime().saturating_sub(10_000_000), // a second of slack
        exe: exe.to_ascii_lowercase(),
        before: app_windows().into_iter().map(|h| h.0 as isize).collect(),
        until: Instant::now() + Duration::from_secs(20),
    });
    let f = wide(file);
    let p = wide(params);
    unsafe {
        let mon = MonitorFromPoint(POINT { x: (area.left + area.right) / 2, y: (area.top + area.bottom) / 2 }, MONITOR_DEFAULTTONULL);
        let mut info = SHELLEXECUTEINFOW {
            cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
            fMask: SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI | SEE_MASK_HMONITOR,
            lpFile: PCWSTR(f.as_ptr()),
            lpParameters: PCWSTR(p.as_ptr()),
            nShow: SW_SHOWNOACTIVATE.0,
            ..Default::default()
        };
        info.Anonymous.hMonitor = HANDLE(mon.0);
        if ShellExecuteExW(&mut info).is_err() {
            return false;
        }
        if !info.hProcess.is_invalid() {
            let p = GetProcessId(info.hProcess);
            if p != 0 && process_info(p).map_or(false, |(_, e)| e != "explorer.exe") {
                owned().lock().unwrap().insert(p, idx);
            }
            let _ = CloseHandle(info.hProcess);
        }
    }
    true
}

/// Was this window started on monitor `idx` (it closes when the monitor turns off)?
fn started_on(h: HWND, idx: i32) -> bool {
    placed().lock().unwrap().get(&(h.0 as isize)) == Some(&idx) || owned().lock().unwrap().get(&pid(h)) == Some(&idx)
}

// ---- each taskbar shows only its own screen's windows ---------------------------------------------
//
// Windows' own setting (Settings → Personalization → Taskbar → "Show my taskbar on all displays",
// "…show my taskbar apps on: taskbar where window is open"), on while an in-game monitor exists.
// Your own values are kept in HKCU\Software\WindowsCraft and put back when the last monitor goes
// (or, after a crash, the next time the helper starts).

const ADVANCED: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced";
const BACKUP: &str = "Software\\WindowsCraft\\TaskbarBackup";

fn reg_get(path: &str, name: &str) -> Option<u32> {
    unsafe {
        let mut key = HKEY::default();
        let p = wide(path);
        if RegOpenKeyExW(HKEY_CURRENT_USER, PCWSTR(p.as_ptr()), None, KEY_READ, &mut key) != ERROR_SUCCESS {
            return None;
        }
        let n = wide(name);
        let mut v: u32 = 0;
        let mut size = 4u32;
        let ok = RegQueryValueExW(key, PCWSTR(n.as_ptr()), None, None, Some(&mut v as *mut u32 as *mut u8), Some(&mut size)) == ERROR_SUCCESS;
        let _ = RegCloseKey(key);
        ok.then_some(v)
    }
}

fn reg_set(path: &str, name: &str, value: Option<u32>) {
    unsafe {
        let mut key = HKEY::default();
        let p = wide(path);
        if RegCreateKeyExW(HKEY_CURRENT_USER, PCWSTR(p.as_ptr()), None, PCWSTR::null(), REG_OPTION_NON_VOLATILE, KEY_WRITE, None, &mut key, None) != ERROR_SUCCESS {
            return;
        }
        let n = wide(name);
        match value {
            Some(v) => {
                let bytes = v.to_le_bytes();
                let _ = RegSetValueExW(key, PCWSTR(n.as_ptr()), None, REG_DWORD, Some(&bytes));
            }
            None => {
                let _ = RegDeleteValueW(key, PCWSTR(n.as_ptr()));
            }
        }
        let _ = RegCloseKey(key);
    }
}

fn reg_get_str(path: &str, name: &str) -> Option<String> {
    unsafe {
        let mut key = HKEY::default();
        let p = wide(path);
        if RegOpenKeyExW(HKEY_CURRENT_USER, PCWSTR(p.as_ptr()), None, KEY_READ, &mut key) != ERROR_SUCCESS {
            return None;
        }
        let n = wide(name);
        let mut buf = [0u16; 1024];
        let mut size = (buf.len() * 2) as u32;
        let mut ty = REG_VALUE_TYPE::default();
        let ok = RegQueryValueExW(key, PCWSTR(n.as_ptr()), None, Some(&mut ty), Some(buf.as_mut_ptr() as *mut u8), Some(&mut size)) == ERROR_SUCCESS;
        let _ = RegCloseKey(key);
        ok.then(|| from_wide(&buf))
    }
}

fn reg_set_str(path: &str, name: &str, value: Option<&str>) {
    unsafe {
        let mut key = HKEY::default();
        let p = wide(path);
        if RegCreateKeyExW(HKEY_CURRENT_USER, PCWSTR(p.as_ptr()), None, PCWSTR::null(), REG_OPTION_NON_VOLATILE, KEY_WRITE, None, &mut key, None) != ERROR_SUCCESS {
            return;
        }
        let n = wide(name);
        match value {
            Some(v) => {
                let w = wide(v);
                let bytes = std::slice::from_raw_parts(w.as_ptr() as *const u8, w.len() * 2);
                let _ = RegSetValueExW(key, PCWSTR(n.as_ptr()), None, REG_SZ, Some(bytes));
            }
            None => {
                let _ = RegDeleteValueW(key, PCWSTR(n.as_ptr()));
            }
        }
        let _ = RegCloseKey(key);
    }
}

// Task Manager normally runs as administrator (without asking), and Windows doesn't let a program
// with normal rights move an administrator's window — so it could only ever open on your desk.
// While in-game monitors are on, Task Manager is started with your normal rights instead (the
// same Windows setting "Run as invoker" that you could set yourself); then it goes where you open
// it, like any app. It's put back the moment the monitors go (and after a crash, the next start).
const LAYERS: &str = "Software\\Microsoft\\Windows NT\\CurrentVersion\\AppCompatFlags\\Layers";
const LAYER_BACKUP: &str = "Software\\WindowsCraft\\TaskmgrBackup";

fn taskmgr_path() -> String {
    let mut buf = [0u16; 260];
    let n = unsafe { windows::Win32::System::SystemInformation::GetSystemDirectoryW(Some(&mut buf)) } as usize;
    format!("{}\\Taskmgr.exe", String::from_utf16_lossy(&buf[..n.min(buf.len())]))
}

fn taskmgr_normal_rights(on: bool) {
    let path = taskmgr_path();
    if on {
        if reg_get(LAYER_BACKUP, "Saved").is_none() {
            match reg_get_str(LAYERS, &path) {
                Some(v) => reg_set_str(LAYER_BACKUP, "Value", Some(&v)),
                None => reg_set_str(LAYER_BACKUP, "Value", None),
            }
            reg_set(LAYER_BACKUP, "Saved", Some(1));
        }
        reg_set_str(LAYERS, &path, Some("~ RUNASINVOKER"));
    } else if reg_get(LAYER_BACKUP, "Saved").is_some() {
        let old = reg_get_str(LAYER_BACKUP, "Value");
        reg_set_str(LAYERS, &path, old.as_deref());
        reg_set_str(LAYER_BACKUP, "Value", None);
        reg_set(LAYER_BACKUP, "Saved", None);
    }
}

/// Tells the taskbar its settings changed (it picks the change up without signing out).
fn tell_taskbar() {
    let what = wide("TraySettings");
    unsafe {
        let _ = SendMessageTimeoutW(HWND_BROADCAST, WM_SETTINGCHANGE, WPARAM(0), LPARAM(what.as_ptr() as isize), SMTO_ABORTIFHUNG, 1000, None);
    }
}

const NONE: u32 = 0xFFFF_FFFF; // "you didn't have this value"

fn taskbars_separate(on: bool) {
    taskmgr_normal_rights(on); // (same moments: monitors on / all gone / after a crash)
    if on {
        if reg_get(BACKUP, "Saved").is_none() {
            reg_set(BACKUP, "MMTaskbarEnabled", Some(reg_get(ADVANCED, "MMTaskbarEnabled").unwrap_or(NONE)));
            reg_set(BACKUP, "MMTaskbarMode", Some(reg_get(ADVANCED, "MMTaskbarMode").unwrap_or(NONE)));
            reg_set(BACKUP, "Saved", Some(1));
        }
        reg_set(ADVANCED, "MMTaskbarEnabled", Some(1)); // a taskbar on every display
        reg_set(ADVANCED, "MMTaskbarMode", Some(2));    // …showing only the windows on that display
        tell_taskbar();
    } else if reg_get(BACKUP, "Saved").is_some() {
        for name in ["MMTaskbarEnabled", "MMTaskbarMode"] {
            let v = reg_get(BACKUP, name).unwrap_or(NONE);
            reg_set(ADVANCED, name, if v == NONE { None } else { Some(v) });
            reg_set(BACKUP, name, None);
        }
        reg_set(BACKUP, "Saved", None);
        tell_taskbar();
    }
}

// ---- looking around with Alt: your mouse as Minecraft feels it ---------------------------------
//
// While you look around with Alt (seated, or pointing at your monitor), your mouse's movement is
// read from where Windows would move the pointer — which includes your Windows pointer speed and
// "Enhance pointer precision". Minecraft reads the mouse raw. So for exactly that time Windows'
// pointer speed is neutral (1:1) and the acceleration off; your own settings come back the
// moment you stop (and, after a crash, the next time the helper starts). Not saved to your
// settings at all: a restart of Windows brings yours back by itself too.

const MOUSE_BACKUP: &str = "Software\\WindowsCraft\\MouseBackup";
static LOOK_RAW: AtomicBool = AtomicBool::new(false);

fn look_mode(on: bool) {
    use windows::Win32::UI::WindowsAndMessaging::{SystemParametersInfoW, SPI_GETMOUSE, SPI_GETMOUSESPEED, SPI_SETMOUSE, SPI_SETMOUSESPEED, SYSTEM_PARAMETERS_INFO_UPDATE_FLAGS};
    if LOOK_RAW.swap(on, Ordering::SeqCst) == on {
        return;
    }
    let none = SYSTEM_PARAMETERS_INFO_UPDATE_FLAGS(0);
    unsafe {
        if on {
            let mut speed: u32 = 10;
            let mut accel = [0i32; 3];
            let _ = SystemParametersInfoW(SPI_GETMOUSESPEED, 0, Some(&mut speed as *mut u32 as *mut _), none);
            let _ = SystemParametersInfoW(SPI_GETMOUSE, 0, Some(accel.as_mut_ptr() as *mut _), none);
            if reg_get(MOUSE_BACKUP, "Saved").is_none() {
                reg_set(MOUSE_BACKUP, "Speed", Some(speed));
                for (i, v) in accel.iter().enumerate() {
                    reg_set(MOUSE_BACKUP, &format!("Accel{i}"), Some(*v as u32));
                }
                reg_set(MOUSE_BACKUP, "Saved", Some(1));
            }
            let mut flat = [0i32; 3];
            let _ = SystemParametersInfoW(SPI_SETMOUSESPEED, 0, Some(10usize as *mut _), none);
            let _ = SystemParametersInfoW(SPI_SETMOUSE, 0, Some(flat.as_mut_ptr() as *mut _), none);
        } else {
            restore_mouse();
        }
    }
}

/// Your own pointer speed and acceleration back (also after a crash: called at start).
fn restore_mouse() {
    use windows::Win32::UI::WindowsAndMessaging::{SystemParametersInfoW, SPI_SETMOUSE, SPI_SETMOUSESPEED, SYSTEM_PARAMETERS_INFO_UPDATE_FLAGS};
    if reg_get(MOUSE_BACKUP, "Saved").is_none() {
        return;
    }
    let none = SYSTEM_PARAMETERS_INFO_UPDATE_FLAGS(0);
    let speed = reg_get(MOUSE_BACKUP, "Speed").unwrap_or(10).clamp(1, 20);
    let mut accel = [0i32; 3];
    for i in 0..3 {
        accel[i] = reg_get(MOUSE_BACKUP, &format!("Accel{i}")).unwrap_or(0) as i32;
    }
    unsafe {
        let _ = SystemParametersInfoW(SPI_SETMOUSESPEED, 0, Some(speed as usize as *mut _), none);
        let _ = SystemParametersInfoW(SPI_SETMOUSE, 0, Some(accel.as_mut_ptr() as *mut _), none);
    }
    for n in ["Speed", "Accel0", "Accel1", "Accel2", "Saved"] {
        reg_set(MOUSE_BACKUP, n, None);
    }
}

// ---- main ---------------------------------------------------------------------------------------

struct Added {
    device: String,
    /// When it was turned on (FILETIME): programs started after that "live on it"
    since: u64,
}

type Driver = Arc<Mutex<Option<Arc<Vdd>>>>;
type AddedMap = Arc<Mutex<HashMap<i32, Added>>>;
type Homes = Arc<Mutex<HashMap<isize, WINDOWPLACEMENT>>>;

fn driver_handle(vdd: &Driver) -> Option<Arc<Vdd>> {
    let mut g = vdd.lock().unwrap();
    if g.is_none() && driver_status() == "ok" {
        *g = Vdd::open().map(Arc::new);
    }
    g.clone()
}

/// The monitor with this index, where it is now — only while it's one of ours and a monitor of
/// its own (never mirrored onto one of your real screens).
fn our_area(added: &AddedMap, idx: i32) -> Option<(String, RECT)> {
    let device = added.lock().unwrap().get(&idx).map(|a| a.device.clone()).filter(|d| !d.is_empty())?;
    let r = rect_of(&device)?;
    let ours: Vec<String> = added.lock().unwrap().values().map(|a| a.device.clone()).collect();
    let mirrored = monitors().iter().any(|(d, o)| {
        !ours.iter().any(|x| x.eq_ignore_ascii_case(d)) && o.left < r.right && r.left < o.right && o.top < r.bottom && r.top < o.bottom
    });
    if mirrored { None } else { Some((device, r)) }
}

/// Is that monitor of ours showing on top of one of your real screens (Windows duplicating displays)?
fn mirrored(added: &AddedMap, idx: i32) -> bool {
    let Some(device) = added.lock().unwrap().get(&idx).map(|a| a.device.clone()).filter(|d| !d.is_empty()) else { return false };
    let Some(r) = rect_of(&device) else { return false };
    let ours: Vec<String> = added.lock().unwrap().values().map(|a| a.device.clone()).collect();
    monitors().iter().any(|(d, o)| !ours.iter().any(|x| x.eq_ignore_ascii_case(d)) && o.left < r.right && r.left < o.right && o.top < r.bottom && r.top < o.bottom)
}

fn main() {
    unsafe {
        let _ = windows::Win32::UI::HiDpi::SetProcessDpiAwarenessContext(windows::Win32::UI::HiDpi::DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        // a thread needs an input queue before it can share input state with another (foreground)
        let mut msg = MSG::default();
        let _ = PeekMessageW(&mut msg, None, 0, 0, PM_NOREMOVE);
    }
    let (tx, rx) = channel::<String>();
    let _ = EVENTS.set(Mutex::new(tx));
    std::thread::spawn(move || event_writer(rx));

    let vdd: Driver = Arc::new(Mutex::new(None));
    let added: AddedMap = Arc::new(Mutex::new(HashMap::new()));
    let _ = AREAS.set(added.clone());
    taskbars_separate(false); // a crash may have left the taskbar setting on: yours comes back
    restore_mouse(); // …or your pointer speed neutral
    clear_all_force(); // …or new programs going into a box
    let homes: Homes = Arc::new(Mutex::new(HashMap::new()));
    let _ = HOMES.set(homes.clone());

    // when the game goes away, so do its monitors (the driver also drops them within a second
    // once the pings stop — this just makes it immediate and tidy)
    let parent = std::env::args().skip_while(|a| a != "--parent-pid").nth(1).and_then(|p| p.parse::<u32>().ok());
    {
        let (vdd, added, homes) = (vdd.clone(), added.clone(), homes.clone());
        std::thread::spawn(move || {
            let Some(pid) = parent else { return };
            unsafe {
                let Ok(h) = OpenProcess(PROCESS_SYNCHRONIZE, false, pid) else {
                    eprintln!("error=can't watch the game process");
                    return;
                };
                WaitForSingleObject(h, INFINITE);
            }
            shutdown(&vdd, &added, &homes);
            std::process::exit(0);
        });
    }
    // the keep-alive: well within the driver's ~100 ms while any monitor is ours (never waits
    // behind another call: each has its own OVERLAPPED)
    {
        let (vdd, added) = (vdd.clone(), added.clone());
        std::thread::spawn(move || {
            // (first in line for the processor: a busy PC — a game drawing flat out — must never
            // starve the pings, or the driver quietly drops a monitor)
            unsafe {
                let _ = windows::Win32::System::Threading::SetThreadPriority(
                    windows::Win32::System::Threading::GetCurrentThread(), windows::Win32::System::Threading::THREAD_PRIORITY_TIME_CRITICAL);
            }
            let mut missing: HashMap<i32, u32> = HashMap::new();
            let mut last_look = Instant::now();
            loop {
                std::thread::sleep(Duration::from_millis(*PING_MS));
                if added.lock().unwrap().is_empty() {
                    missing.clear();
                    continue;
                }
                let v = vdd.lock().unwrap().clone();
                if let Some(v) = v {
                    v.ping();
                }
                // a monitor of ours that Windows doesn't have any more went away by itself: say so
                // (never let a new one quietly take its place under the old one's name)
                if last_look.elapsed() >= Duration::from_millis(700) {
                    last_look = Instant::now();
                    let ours: Vec<(i32, String)> = added.lock().unwrap().iter().filter(|(_, a)| !a.device.is_empty()).map(|(i, a)| (*i, a.device.clone())).collect();
                    for (i, dev) in ours {
                        let now = device_for_index(i);
                        if now.as_deref().map_or(false, |d| d.eq_ignore_ascii_case(&dev)) {
                            missing.remove(&i);
                            continue;
                        }
                        // Windows gave it another display name (it settles on one a moment after a
                        // monitor comes): the same monitor — the game picks it up under its new name
                        if let Some(d) = now.filter(|d| rect_of(d).is_some()) {
                            let mut g = added.lock().unwrap();
                            if let Some(a) = g.get_mut(&i).filter(|a| a.device == dev) {
                                a.device = d.clone();
                                drop(g);
                                missing.remove(&i);
                                eprintln!("info=in-game monitor {i} is {d} now (was {dev})");
                                emit("moved");
                                continue;
                            }
                        }
                        let n = missing.entry(i).or_insert(0);
                        *n += 1;
                        if *n >= 4 {
                            missing.remove(&i);
                            let mut g = added.lock().unwrap();
                            if g.get(&i).map_or(false, |a| a.device == dev) {
                                g.remove(&i);
                                drop(g);
                                let pings = PING_FAILS.load(Ordering::SeqCst);
                                eprintln!("error=in-game monitor {i} ({dev}) went away by itself (driver {}, Windows has {} screens, {} failed pings so far, last call: {})",
                                    driver_status(), monitors().len(), pings, LAST_IOCTL.lock().unwrap());
                                emit(&format!("gone {i}"));
                            }
                        }
                    }
                }
            }
        });
    }

    // "beat" is handled as soon as it arrives, even while a slow command runs
    let (cmd_tx, cmd_rx) = channel::<String>();
    std::thread::spawn(move || {
        let stdin = io::stdin();
        for line in stdin.lock().lines() {
            let Ok(line) = line else { break };
            if line.trim() == "beat" {
                *BEAT.lock().unwrap() = Some(Instant::now());
            } else if quick_command(&line) {
                // done
            } else if cmd_tx.send(line).is_err() {
                break;
            }
        }
    });
    for line in cmd_rx {
        let p: Vec<&str> = line.split_whitespace().collect();
        let n = |i: usize| p.get(i).and_then(|s| s.parse::<i64>().ok()).unwrap_or(0);
        let f = |i: usize| p.get(i).and_then(|s| s.parse::<f64>().ok()).unwrap_or(0.5);
        match p.first().copied() {
            Some("beat") => {
                *BEAT.lock().unwrap() = Some(Instant::now());
            }
            Some("status") => {
                let st = driver_status();
                let mut ver = 0;
                if st == "ok" {
                    match driver_handle(&vdd) {
                        // (a driver that doesn't answer is in trouble even if Windows says it's fine)
                        Some(v) => match v.ioctl(IOCTL_VERSION, &[]) {
                            Some(x) => ver = x,
                            None => {
                                say("status error 0");
                                continue;
                            }
                        },
                        None => {
                            say("status error 0");
                            continue;
                        }
                    }
                }
                say(&format!("status {st} {ver}"));
            }
            Some("add") => {
                let (w, h, hz) = (n(1).max(640) as u32, n(2).max(480) as u32, n(3).clamp(24, 240) as u32);
                let Some(v) = driver_handle(&vdd) else {
                    say("fail the in-game monitor driver isn't ready");
                    continue;
                };
                // (a monitor that Windows doesn't switch on, or that goes away again right after,
                // is given back and a fresh one asked for — once)
                let mut answer = String::new();
                for attempt in 1..=2 {
                    match add_monitor(&v, &added, w, h, hz) {
                        Ok(a) => {
                            answer = a;
                            break;
                        }
                        Err((why, again)) => {
                            answer = format!("fail {why}");
                            if !again || attempt == 2 {
                                break;
                            }
                            eprintln!("info=trying a fresh monitor ({why})");
                        }
                    }
                }
                say(&answer);
            }
            Some("remove") => {
                let idx = n(1) as i32;
                remove_one(&vdd, &added, &homes, idx);
                *LAST_DRIVER_CHANGE.lock().unwrap() = Some(Instant::now());
                if added.lock().unwrap().is_empty() {
                    taskbars_separate(false);
                }
                say(&format!("removed {idx}"));
            }
            Some("rects") => {
                let list: Vec<i32> = added.lock().unwrap().keys().copied().collect();
                for i in list {
                    if let Some((d, r)) = our_area(&added, i) {
                        say(&format!("rect {i} {d} {} {} {} {}", r.left, r.top, r.right - r.left, r.bottom - r.top));
                    } else if mirrored(&added, i) {
                        say(&format!("mirrored {i}"));
                    }
                }
                say("rects-end");
            }
            Some("windows") => {
                if let Some((_, a)) = our_area(&added, n(1) as i32) {
                    // File Explorer windows belong to the shell, whose "process tree" is nearly
                    // every app you started: their pid is never offered for sound capture
                    let shell = shell_pid();
                    let idx = n(1) as i32;
                    for h in app_windows() {
                        if on_area(h, &a) {
                            let id = pid(h);
                            say(&format!("win {} {} {} {}", h.0 as usize, if id == shell { 0 } else { id }, started_on(h, idx) as u8, title(h)));
                        }
                    }
                }
                say("windows-end");
            }
            Some("deskwindows") => {
                let devices: Vec<String> = added.lock().unwrap().values().map(|a| a.device.clone()).collect();
                let areas: Vec<RECT> = devices.iter().filter_map(|d| rect_of(d)).collect();
                for h in app_windows() {
                    if !areas.iter().any(|a| on_area(h, a)) {
                        say(&format!("win {} {} 0 {}", h.0 as usize, pid(h), title(h)));
                    }
                }
                say("windows-end");
            }
            Some("bring") => {
                let h = HWND(n(1) as usize as *mut _);
                if let Some((_, a)) = our_area(&added, n(2) as i32) {
                    if unsafe { IsWindow(Some(h)).as_bool() } {
                        // from your desk: remember where it was. From another in-game monitor:
                        // keep its desk spot (if any) and let the new monitor count as its home
                        let areas: Vec<RECT> = added.lock().unwrap().values().filter_map(|x| rect_of(&x.device)).collect();
                        let from_ours = areas.iter().any(|r| on_area(h, r));
                        if from_ours {
                            let mut pl = placed().lock().unwrap();
                            if pl.contains_key(&(h.0 as isize)) {
                                pl.insert(h.0 as isize, n(2) as i32);
                            }
                        }
                        let mut homes = homes.lock().unwrap();
                        if !homes.contains_key(&(h.0 as isize)) && !from_ours {
                            if let Some(p) = placement(h) {
                                homes.insert(h.0 as isize, p);
                            }
                        }
                        drop(homes);
                        place_window(h, &a);
                    }
                }
                say(&format!("brought {}", h.0 as usize));
            }
            Some("close") => {
                // an app started on a monitor, closed like with its × (it may ask to save first)
                let h = HWND(n(1) as usize as *mut _);
                unsafe {
                    if IsWindow(Some(h)).as_bool() {
                        let _ = PostMessageW(Some(h), WM_CLOSE, WPARAM(0), LPARAM(0));
                    }
                }
                say(&format!("closed {}", h.0 as usize));
            }
            Some("home") => {
                let h = HWND(n(1) as usize as *mut _);
                if unsafe { IsWindow(Some(h)).as_bool() } {
                    send_home(h, &homes);
                }
                say(&format!("homed {}", h.0 as usize));
            }
            Some("evacuate") => {
                let moved = our_area(&added, n(1) as i32).map_or(0, |(_, a)| evacuate(n(1) as i32, &a, &homes).len());
                say(&format!("evacuated {moved}"));
            }
            Some("seat") => match our_area(&added, n(1) as i32) {
                None => say("fail that in-game monitor isn't there any more"),
                Some((device, area)) => match seat(n(1) as i32, &device, area, f(2), f(3), HWND(n(4) as usize as *mut _)) {
                    Ok(()) => say("seated"),
                    Err(why) => say(&format!("fail {why}")),
                },
            },
            Some("unseat") => {
                unseat();
                say("unseated");
            }
            Some("modes") => {
                if let Some((device, _)) = our_area(&added, n(1) as i32) {
                    for (w, h, hz) in sizes(&device) {
                        say(&format!("mode {w} {h} {hz}"));
                    }
                }
                say("modes-end");
            }
            Some("setmode") => match our_area(&added, n(1) as i32) {
                None => say("modeset fail that in-game monitor isn't there any more"),
                Some((device, area)) => match set_mode(&device, area, n(2) as u32, n(3) as u32, n(4).max(24) as u32,
                    &added.lock().unwrap().values().map(|a| a.device.clone()).filter(|d| !d.is_empty()).collect::<Vec<_>>()) {
                    Ok(r) => {
                        eprintln!("info={}", fit_scale(&device, (r.bottom - r.top) as u32));
                        let r = rect_of(&device).unwrap_or(r);
                        say(&format!("modeset ok {} {} {} {}", r.left, r.top, r.right - r.left, r.bottom - r.top))
                    }
                    Err(why) => say(&format!("modeset fail {why}")),
                },
            },

            Some("arrange") => {
                let spec = line.splitn(2, ' ').nth(1).unwrap_or("").to_string();
                match arrange(&added, &spec) {
                    Ok(()) => say("arranged ok"),
                    Err(why) => say(&format!("arranged fail {why}")),
                }
            }
            Some("taskbar") => {
                // (tests) the separate-taskbars setting by hand
                taskbars_separate(p.get(1) == Some(&"on"));
                say("taskbar done");
            }
            Some("isolate") => {
                // "own copies" for a monitor on or off
                ISOLATE.lock().unwrap().get_or_insert_with(HashMap::new).insert(n(1) as i32, p.get(2) == Some(&"1"));
                ISO_DIRTY.store(true, Ordering::SeqCst);
                if sbie_dir().is_some() {
                    ensure_iso_thread();
                }
            }
            Some("isoexclude") => {
                // tab-separated: program names, then "|", then folders
                let f: Vec<&str> = line.splitn(2, '\t').nth(1).unwrap_or("").split('\t').collect();
                let groups: Vec<Vec<String>> = f.split(|x| *x == "|").map(|g| g.iter().filter(|x| !x.is_empty()).map(|x| x.to_string()).collect()).collect();
                *ISO_EXES.lock().unwrap() = groups.first().cloned().unwrap_or_default().into_iter().map(|x| x.to_ascii_lowercase()).collect();
                *ISO_DIRS.lock().unwrap() = groups.get(1).cloned().unwrap_or_default();
                *ISO_APPDIRS.lock().unwrap() = groups.get(2).cloned().unwrap_or_default();
                ISO_DIRTY.store(true, Ordering::SeqCst);
                refresh_carve(); // (worked out now, before anyone sits down)
                for b in BOXES_MADE.lock().unwrap().clone() {
                    ensure_box(&b);
                }
                reload_conf();
            }
            Some("sbiestatus") => say(&format!("sbie {}", sbie_status())),
            Some("installsbie") => {
                // Sandboxie-Plus, quietly (Windows asks for permission once): its own window never starts
                let file = line.splitn(2, ' ').nth(1).unwrap_or("").trim().to_string();
                let (r, code) = run_elevated(&file, "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /ALLUSERS");
                for root in [HKEY_CURRENT_USER, HKEY_LOCAL_MACHINE] {
                    unsafe {
                        let path = wide("Software\\Microsoft\\Windows\\CurrentVersion\\Run");
                        let mut k = HKEY::default();
                        if RegOpenKeyExW(root, PCWSTR(path.as_ptr()), None, KEY_READ | KEY_SET_VALUE, &mut k) == ERROR_SUCCESS {
                            for name in ["SandboxiePlus_AutoRun", "SandboxieControl", "SandMan"] {
                                let n = wide(name);
                                let _ = RegDeleteValueW(k, PCWSTR(n.as_ptr()));
                            }
                            let _ = RegCloseKey(k);
                        }
                    }
                }
                let t0 = Instant::now();
                while r == "ok" && sbie_status() != "ok" && t0.elapsed() < Duration::from_secs(20) {
                    std::thread::sleep(Duration::from_millis(300));
                }
                for pr in ["SandMan.exe"] {
                    let _ = std::process::Command::new("taskkill").args(["/IM", pr, "/F"]).output();
                }
                say(&format!("installsbie {r} {code} {}", sbie_status()));
            }
            Some("boxlaunch") => {
                let f: Vec<&str> = line.split('\t').collect();
                let idx = f.get(1).and_then(|s| s.trim().parse::<i32>().ok()).unwrap_or(-1);
                let (file, params) = (f.get(2).copied().unwrap_or("").to_string(), f.get(3).copied().unwrap_or("").to_string());
                std::thread::spawn(move || say(if box_launch(idx, &file, &params) { "boxlaunched ok" } else { "boxlaunched failed" }));
            }
            Some("boxreset") => {
                let idx = n(1) as i32;
                std::thread::spawn(move || {
                    close_box(idx, Duration::from_secs(8));
                    let b = box_name(idx);
                    sbie_start(&[&format!("/box:{b}"), "delete_sandbox_silent"]);
                    say("boxreset ok");
                });
            }
            Some("boxapps") => {
                // the programs running in that monitor's box (for the menu)
                let b = box_name(n(1) as i32);
                for w in app_windows() {
                    if box_of(pid(w)).eq_ignore_ascii_case(&b) {
                        say(&format!("win {} {} 0 {}", w.0 as usize, pid(w), title(w)));
                    }
                }
                say("windows-end");
            }
            Some("profilekey") => {
                // which wall a monitor is: its own browser copies are kept per wall
                let key: String = p.get(2).unwrap_or(&"").chars().filter(|c| c.is_ascii_alphanumeric()).collect();
                if !key.is_empty() {
                    PROFILE_KEYS.lock().unwrap().get_or_insert_with(HashMap::new).insert(n(1) as i32, key);
                }
            }
            Some("ownbrowser") => {
                // "Own Brave" from the monitor's menu: its own copy opens there
                let f: Vec<&str> = line.split('\t').collect();
                let idx = f.get(1).and_then(|s| s.trim().parse::<i32>().ok()).unwrap_or(-1);
                let exe = f.get(2).copied().unwrap_or("").to_string();
                std::thread::spawn(move || {
                    let ok = own_browser(idx, &exe);
                    say(if ok { "ownbrowser ok" } else { "ownbrowser failed" });
                });
            }
            Some("launch") => {
                let f: Vec<&str> = line.split('\t').collect();
                let idx = f.get(1).and_then(|s| s.trim().parse::<i32>().ok()).unwrap_or(-1);
                let ok = launch(idx, f.get(2).copied().unwrap_or(""), f.get(3).copied().unwrap_or(""), f.get(4).copied().unwrap_or(""));
                say(if ok { "launched ok" } else { "launched failed" });
            }
            Some("repair") => {
                // the driver got into trouble (Windows shows it with a problem): restart it, the
                // way Device Manager's "disable, enable" would (Windows asks for permission)
                shutdown_monitors(&vdd, &added, &homes);
                *vdd.lock().unwrap() = None;
                match vdd_instance_id() {
                    None => say("repair failed missing"),
                    Some(id) => {
                        let (r, code) = run_elevated("pnputil.exe", &format!("/restart-device \"{id}\""));
                        let t0 = Instant::now();
                        while r == "ok" && driver_status() != "ok" && t0.elapsed() < Duration::from_secs(15) {
                            std::thread::sleep(Duration::from_millis(250));
                        }
                        eprintln!("info=restarting the display driver ({id}): {r} {code}, now {}", driver_status());
                        say(&format!("repair {} {}", if driver_status() == "ok" { "ok" } else { r }, driver_status()));
                    }
                }
            }
            Some("install") => {
                let file = line.splitn(2, ' ').nth(1).unwrap_or("").trim().to_string();
                let (r, code) = run_elevated(&file, "/S");
                say(&format!("install {r} {code}"));
            }
            Some("uninstall") => {
                // never with our own monitors still plugged in
                shutdown_monitors(&vdd, &added, &homes);
                *vdd.lock().unwrap() = None;
                match uninstaller() {
                    None => say("uninstall missing 0"),
                    Some(cmd) => {
                        let exe = uninstaller_exe(&cmd);
                        // an NSIS uninstaller relaunches itself unless told where it lives (_?=)
                        let dir = std::path::Path::new(&exe).parent().map(|d| d.display().to_string()).unwrap_or_default();
                        let (r, code) = run_elevated(&exe, &format!("/S _?={dir}"));
                        if r == "ok" {
                            let t0 = Instant::now();
                            while driver_status() != "missing" && t0.elapsed() < Duration::from_secs(20) {
                                std::thread::sleep(Duration::from_millis(250));
                            }
                        }
                        say(&format!("uninstall {r} {code}"));
                    }
                }
            }
            _ => {}
        }
    }
    shutdown(&vdd, &added, &homes);
}

/// "C:\Program Files\X\uninstall.exe" /arg  →  C:\Program Files\X\uninstall.exe
fn uninstaller_exe(cmd: &str) -> String {
    let c = cmd.trim();
    if let Some(rest) = c.strip_prefix('"') {
        return rest.split('"').next().unwrap_or("").to_string();
    }
    match c.to_ascii_lowercase().find(".exe") {
        Some(i) => c[..i + 4].to_string(),
        None => c.to_string(),
    }
}

/// The driver's monitors this helper doesn't know of (left from before: a removal that didn't take,
/// or an earlier game): they'd stay on as long as we keep the driver alive, and take a screen
/// Windows could use for a new one.
/// One new monitor, placed: the "added …" answer, or why not (and whether a fresh one may work).
fn add_monitor(v: &Vdd, added: &AddedMap, w: u32, h: u32, hz: u32) -> Result<String, (String, bool)> {
    remove_orphans(v, added);
    // (right after a monitor went away the driver is still busy with it: asking it for a new one
    // too soon can upset it — so a moment's rest first, and gentle retries)
    let since_change = LAST_DRIVER_CHANGE.lock().unwrap().map_or(Duration::from_secs(60), |t| t.elapsed());
    let rest = Duration::from_millis(*COOLDOWN_MS);
    if since_change < rest {
        std::thread::sleep(rest - since_change);
    }
    let mut got = None;
    let mut failed_tries = 0;
    for attempt in 1..=3u64 {
        if let Some(i) = v.add() {
            got = Some(i);
            break;
        }
        failed_tries += 1;
        let last = LAST_IOCTL.lock().unwrap().clone();
        eprintln!("info=the driver didn't add a monitor (try {attempt}: {last})");
        if driver_status() != "ok" {
            break; // (it has a problem now: retrying won't help)
        }
        if last.contains("0x80070102") {
            // it didn't answer at all (rather than saying no): it's stuck, and only Repair helps
            return Err(("the display driver stopped answering (\"Repair driver\" restarts it)".into(), false));
        }
        std::thread::sleep(Duration::from_millis(1500 * attempt));
    }
    *LAST_DRIVER_CHANGE.lock().unwrap() = Some(Instant::now());
    let Some(idx) = got else { return Err(("the driver didn't add a monitor".into(), false)) };
    eprintln!("info=the driver added monitor {idx} (UID{})", 256 + idx);
    // (the driver gave us a number we still had: that monitor is gone already)
    let old = added.lock().unwrap().remove(&idx);
    if let Some(o) = old {
        eprintln!("error=in-game monitor {idx} ({}) had gone away by itself", o.device);
        emit(&format!("gone {idx}"));
    }
    // the keep-alive covers it from now on
    added.lock().unwrap().insert(idx, Added { device: String::new(), since: now_filetime() });
    // (a try the driver said no to may still have made a monitor: never leave one behind)
    if failed_tries > 0 {
        std::thread::sleep(Duration::from_millis(500));
        remove_orphans(v, added);
    }
    let give_back = |why: String| {
        v.remove(idx);
        added.lock().unwrap().remove(&idx);
        *LAST_DRIVER_CHANGE.lock().unwrap() = Some(Instant::now());
        Err((why, true))
    };
    // (Windows can show a new monitor under a passing display name first and settle on its real
    // one a moment later: wait for the one it switches on)
    let deadline = Instant::now() + Duration::from_secs(8);
    let mut device = None;
    let mut seen: Option<Instant> = None;
    while Instant::now() < deadline {
        if let Some(d) = device_for_index(idx) {
            let since = *seen.get_or_insert_with(Instant::now);
            if attached(&d).0 || since.elapsed() > Duration::from_millis(1500) {
                device = Some(d);
                break;
            }
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    let Some(mut device) = device else { return give_back("Windows didn't show the new monitor".into()) };
    let ours: Vec<String> = added.lock().unwrap().values().map(|a| a.device.clone()).filter(|d| !d.is_empty()).collect();
    let r = match place(&device, w, h, hz, &ours) {
        Ok(r) => r,
        Err(why) => {
            // it may have moved to its real name meanwhile (and be on already)
            match device_for_index(idx).filter(|d| !d.eq_ignore_ascii_case(&device)) {
                Some(d2) => {
                    eprintln!("info=the new monitor is {d2} now (not {device})");
                    device = d2;
                    match place(&device, w, h, hz, &ours) {
                        Ok(r) => r,
                        Err(why) => return give_back(why),
                    }
                }
                None => return give_back(why),
            }
        }
    };
    eprintln!("info={}", fit_scale(&device, (r.bottom - r.top) as u32));
    // (now and then Windows lets a brand-new monitor go again a moment later — most of all the
    // first one in a while, while Windows sets itself up for monitors: make sure it stays)
    let first = added.lock().unwrap().values().all(|a| a.device.is_empty());
    let watch = Duration::from_millis(if first { 3000 } else { 800 });
    // (while Windows rearranges its screens, a monitor can drop out of its lists for a moment
    // and come straight back: only one that stays gone for over a second has really gone)
    let t_watch = Instant::now();
    let mut missing_since: Option<Instant> = None;
    while t_watch.elapsed() < watch || missing_since.is_some() {
        std::thread::sleep(Duration::from_millis(200));
        let listed = device_for_index(idx).map_or(false, |d| d.eq_ignore_ascii_case(&device));
        let placed_ = rect_of(&device).is_some();
        if listed && placed_ {
            if missing_since.take().is_some() {
                eprintln!("info=the new monitor {device} dropped out of Windows' list for a moment and came back");
            }
            continue;
        }
        let since = *missing_since.get_or_insert_with(Instant::now);
        if since.elapsed() > Duration::from_millis(1200) {
            eprintln!("error=the new monitor {device} went away again right after it came on (listed {listed}, on the desktop {placed_})");
            return give_back("Windows switched the new monitor off again".into());
        }
    }
    let since = added.lock().unwrap().get(&idx).map_or_else(now_filetime, |a| a.since);
    added.lock().unwrap().insert(idx, Added { device: device.clone(), since });
    taskbars_separate(true);
    ensure_placer();
    rearrange_with(added, idx);
    let r = rect_of(&device).unwrap_or(r);
    Ok(format!("added {idx} {device} {} {} {} {}", r.left, r.top, r.right - r.left, r.bottom - r.top))
}

fn remove_orphans(v: &Vdd, added: &AddedMap) {
    for i in 0..16 {
        if added.lock().unwrap().contains_key(&i) {
            continue;
        }
        if let Some(d) = device_for_index(i) {
            eprintln!("info=removing a left-over in-game monitor ({d}, index {i})");
            v.remove(i);
            let deadline = Instant::now() + Duration::from_secs(3);
            while Instant::now() < deadline && device_for_index(i).is_some() {
                std::thread::sleep(Duration::from_millis(50));
            }
        }
    }
}

fn remove_one(vdd: &Driver, added: &AddedMap, homes: &Homes, idx: i32) {
    if SEATED.load(Ordering::SeqCst) {
        let seated_dev = SEAT_DEVICE.lock().unwrap().clone();
        if added.lock().unwrap().get(&idx).map_or(false, |a| a.device.eq_ignore_ascii_case(&seated_dev)) {
            unseat();
        }
    }
    if POINTING.load(Ordering::SeqCst) && SEAT_IDX.load(Ordering::SeqCst) == idx {
        let _g = POINT_LOCK.lock().unwrap();
        unpoint();
    }
    let since = added.lock().unwrap().get(&idx).map(|a| a.since);
    if isolated(idx) {
        close_box(idx, Duration::from_secs(8)); // (its own copies close, as you'd close them: they keep what they saved)
    }
    if let Some((_, a)) = our_area(added, idx) {
        let left = evacuate(idx, &a, homes);
        if let Some(since) = since {
            end_leftovers(left, since);
        }
    }
    let had = added.lock().unwrap().remove(&idx);
    *LAST_DRIVER_CHANGE.lock().unwrap() = Some(Instant::now());
    if had.is_some() {
        let v = vdd.lock().unwrap().clone();
        if let Some(v) = v {
            v.remove(idx);
        }
        let deadline = Instant::now() + Duration::from_secs(3);
        while Instant::now() < deadline && device_for_index(idx).is_some() {
            std::thread::sleep(Duration::from_millis(50));
        }
        if device_for_index(idx).is_some() {
            // didn't take: ask again (it must never be left behind)
            eprintln!("info=the in-game monitor {idx} is still there; removing it again");
            if let Some(v) = vdd.lock().unwrap().clone() {
                v.remove(idx);
            }
            let deadline = Instant::now() + Duration::from_secs(3);
            while Instant::now() < deadline && device_for_index(idx).is_some() {
                std::thread::sleep(Duration::from_millis(50));
            }
            if device_for_index(idx).is_some() {
                eprintln!("error=the in-game monitor {idx} wouldn't go away");
            }
        }
    }
}

/// The monitor is turning off: whatever lives on it closes — except windows you brought over
/// from your desk, which just go back. Everything comes to your desk first, so a "save changes?"
/// question shows up where you can answer it. Returns the programs whose windows were closed.
fn evacuate(idx: i32, a: &RECT, homes: &Homes) -> Vec<u32> {
    let _ = idx;
    let mut closed = Vec::new();
    for h in app_windows() {
        if on_area(h, a) {
            let brought = homes.lock().unwrap().contains_key(&(h.0 as isize));
            send_home(h, homes);
            if !brought {
                unsafe {
                    let _ = PostMessageW(Some(h), WM_CLOSE, WPARAM(0), LPARAM(0));
                }
                let p = pid(h);
                if !closed.contains(&p) {
                    closed.push(p);
                }
            }
        }
    }
    owned().lock().unwrap().retain(|_, i| *i != idx);
    placed().lock().unwrap().retain(|_, i| *i != idx);
    closed
}

/// A few seconds later: a program started while the monitor was on that's still running with no
/// window anywhere (it went to the system tray, or keeps running in the background) is ended.
/// Never one you had running before, never one with a window still open (a "save changes?"
/// question waits for you), never Windows' own.
fn end_leftovers(pids: Vec<u32>, since: u64) {
    if pids.is_empty() {
        return;
    }
    let me = std::process::id();
    let parent = std::env::args().skip_while(|a| a != "--parent-pid").nth(1).and_then(|p| p.parse::<u32>().ok()).unwrap_or(0);
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_secs(6));
        let shell = shell_pid();
        // any visible window at all (a "save changes?" dialog counts)
        let windows: Vec<u32> = all_top_windows().into_iter().map(|h| pid(HWND(h as *mut _))).collect();
        for p in pids {
            if p == 0 || p == shell || p == me || p == parent || windows.contains(&p) {
                continue;
            }
            let Some((created, exe)) = process_info(p) else { continue }; // gone already
            if created < since || matches!(exe.as_str(), "explorer.exe" | "dwm.exe" | "csrss.exe" | "winlogon.exe" | "svchost.exe" | "java.exe" | "javaw.exe") {
                continue;
            }
            unsafe {
                if let Ok(h) = OpenProcess(PROCESS_TERMINATE, false, p) {
                    let ok = TerminateProcess(h, 0).is_ok();
                    let _ = CloseHandle(h);
                    eprintln!("info={} {exe} (it kept running after its in-game monitor turned off)", if ok { "ended" } else { "couldn't end" });
                }
            }
        }
    });
}

/// One window back to your desk: where it was before it was brought over, or onto your main
/// monitor. The game keeps the keyboard.
fn send_home(h: HWND, homes: &Homes) {
    let home = homes.lock().unwrap().remove(&(h.0 as isize));
    let before = unsafe { GetForegroundWindow() };
    unsafe {
        match home {
            Some(mut p) => {
                if p.showCmd != SW_SHOWMAXIMIZED.0 as u32 && p.showCmd != SW_SHOWMINIMIZED.0 as u32 {
                    p.showCmd = SW_SHOWNOACTIVATE.0 as u32;
                }
                let _ = SetWindowPlacement(h, &p);
            }
            None => {
                let r = desk_spot(h);
                if let Some(mut p) = placement(h) {
                    p.flags = WINDOWPLACEMENT_FLAGS(0);
                    p.rcNormalPosition = r;
                    if !IsIconic(h).as_bool() {
                        p.showCmd = SW_SHOWNOACTIVATE.0 as u32;
                    }
                    let _ = SetWindowPlacement(h, &p);
                }
            }
        }
        // a window put back maximized gets activated by Windows: the game keeps the keyboard
        if !before.0.is_null() && GetForegroundWindow() != before && !SEATED.load(Ordering::SeqCst) {
            foreground(before);
        }
    }
}

fn shutdown_monitors(vdd: &Driver, added: &AddedMap, homes: &Homes) {
    unpoint();
    look_mode(false);
    unseat();
    let mut list: Vec<i32> = added.lock().unwrap().keys().copied().collect();
    // right to left: removing one in the middle can make Windows reshuffle the others
    list.sort_by_key(|i| -i);
    for idx in list {
        remove_one(vdd, added, homes, idx);
    }
    show_hidden_trays();
    keep_fullscreen_on_top(&[], &[]);
    taskbars_separate(false);
}

fn shutdown(vdd: &Driver, added: &AddedMap, homes: &Homes) {
    clear_all_force();
    shutdown_monitors(vdd, added, homes);
    taskbars_separate(false);
    unsafe {
        let _ = ClipCursor(None);
    }
}
