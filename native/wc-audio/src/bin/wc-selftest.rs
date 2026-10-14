//! wc-selftest: checks WindowsCraft's Windows side on a real Windows PC, one check for every
//! problem reported so far. It drives the real helpers (wc-vdisplay.exe, wc-input.exe) the way
//! the game does, with real virtual monitors from the Parsec driver, and looks at what Windows
//! actually did: where monitors and windows ended up, where the cursor is, which window has the
//! keyboard, which window got which click.
//!
//!   wc-selftest [--helpers <dir>] [--report <file>] [--shots <dir>]
//!   wc-selftest --window <title>     (internal: a little test app it starts on a monitor)
//!
//! Prints "PASS <name>: …" / "FAIL <name>: …" / "SKIP <name>: …" lines (also into the report)
//! and exits with the number of failures. Your screens flicker while it runs: monitors come and
//! go, and it moves the mouse — leave the PC alone for the minute or two it takes.

use std::collections::HashSet;
use std::io::{BufRead, BufReader, Write};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicIsize, Ordering};
use std::sync::mpsc::{channel, Receiver};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use windows::core::{PCWSTR, PWSTR};
use windows::Win32::Foundation::*;
use windows::Win32::Graphics::Gdi::*;
use windows::Win32::System::Threading::*;
use windows::Win32::UI::HiDpi::*;
use windows::Win32::UI::Input::KeyboardAndMouse::*;
use windows::Win32::UI::WindowsAndMessaging::*;

// ---- little helpers --------------------------------------------------------------------------

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(std::iter::once(0)).collect()
}

fn sleep(ms: u64) {
    std::thread::sleep(Duration::from_millis(ms));
}

fn class_name(h: HWND) -> String {
    let mut buf = [0u16; 128];
    let n = unsafe { GetClassNameW(h, &mut buf) } as usize;
    String::from_utf16_lossy(&buf[..n.min(buf.len())])
}

fn title(h: HWND) -> String {
    let mut buf = [0u16; 256];
    let n = unsafe { GetWindowTextW(h, &mut buf) } as usize;
    String::from_utf16_lossy(&buf[..n.min(buf.len())])
}

fn pid(h: HWND) -> u32 {
    let mut p = 0u32;
    unsafe { GetWindowThreadProcessId(h, Some(&mut p)) };
    p
}

fn exe_of(pid: u32) -> String {
    unsafe {
        let Ok(h) = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) else { return String::new() };
        let mut buf = [0u16; 1024];
        let mut n = buf.len() as u32;
        let ok = QueryFullProcessImageNameW(h, PROCESS_NAME_WIN32, PWSTR(buf.as_mut_ptr()), &mut n).is_ok();
        let _ = CloseHandle(h);
        if !ok {
            return String::new();
        }
        String::from_utf16_lossy(&buf[..n as usize]).rsplit(['\\', '/']).next().unwrap_or("").to_ascii_lowercase()
    }
}

fn alive(pid: u32) -> bool {
    unsafe {
        let Ok(h) = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) else { return false };
        let mut code = 0u32;
        let ok = GetExitCodeProcess(h, &mut code).is_ok() && code == 259; // STILL_ACTIVE
        let _ = CloseHandle(h);
        ok
    }
}

fn rect_of(h: HWND) -> RECT {
    let mut r = RECT::default();
    unsafe {
        let _ = GetWindowRect(h, &mut r);
    }
    r
}

fn center(r: &RECT) -> (i32, i32) {
    ((r.left + r.right) / 2, (r.top + r.bottom) / 2)
}

fn inside(r: &RECT, x: i32, y: i32) -> bool {
    x >= r.left && x < r.right && y >= r.top && y < r.bottom
}

fn fmt(r: &RECT) -> String {
    format!("[{},{} {}×{}]", r.left, r.top, r.right - r.left, r.bottom - r.top)
}

fn cursor() -> (i32, i32) {
    let mut p = POINT::default();
    unsafe {
        let _ = GetCursorPos(&mut p);
    }
    (p.x, p.y)
}

fn clip() -> RECT {
    let mut r = RECT::default();
    unsafe {
        let _ = GetClipCursor(&mut r);
    }
    r
}

fn foreground_root() -> HWND {
    unsafe {
        let f = GetForegroundWindow();
        if f.0.is_null() { f } else { GetAncestor(f, GA_ROOT) }
    }
}

fn root_at(x: i32, y: i32) -> HWND {
    unsafe {
        let h = WindowFromPoint(POINT { x, y });
        if h.0.is_null() { h } else { GetAncestor(h, GA_ROOT) }
    }
}

/// Do two rectangles share a stretch of edge (not just a corner)? Then a window can be dragged across.
fn edge_contact(a: &RECT, b: &RECT) -> bool {
    let ov = |a0: i32, a1: i32, b0: i32, b1: i32| a1.min(b1) - a0.max(b0);
    ((a.right == b.left || b.right == a.left) && ov(a.top, a.bottom, b.top, b.bottom) > 0)
        || ((a.bottom == b.top || b.bottom == a.top) && ov(a.left, a.right, b.left, b.right) > 0)
}

fn monitors() -> Vec<(String, RECT)> {
    unsafe extern "system" fn each(m: HMONITOR, _: HDC, _: *mut RECT, data: LPARAM) -> windows::core::BOOL {
        let list = unsafe { &mut *(data.0 as *mut Vec<(String, RECT)>) };
        let mut info = MONITORINFOEXW::default();
        info.monitorInfo.cbSize = std::mem::size_of::<MONITORINFOEXW>() as u32;
        if unsafe { GetMonitorInfoW(m, &mut info as *mut MONITORINFOEXW as *mut MONITORINFO) }.as_bool() {
            let n = info.szDevice.iter().position(|c| *c == 0).unwrap_or(32);
            list.push((String::from_utf16_lossy(&info.szDevice[..n]), info.monitorInfo.rcMonitor));
        }
        windows::core::BOOL(1)
    }
    let mut list: Vec<(String, RECT)> = Vec::new();
    unsafe {
        let _ = EnumDisplayMonitors(None, None, Some(each), LPARAM(&mut list as *mut _ as isize));
    }
    list
}

fn primary() -> RECT {
    unsafe {
        let m = MonitorFromPoint(POINT { x: 0, y: 0 }, MONITOR_DEFAULTTOPRIMARY);
        let mut mi = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
        let _ = GetMonitorInfoW(m, &mut mi);
        mi.rcMonitor
    }
}

fn work_area_of(r: &RECT) -> RECT {
    unsafe {
        let m = MonitorFromRect(r, MONITOR_DEFAULTTONEAREST);
        let mut mi = MONITORINFO { cbSize: std::mem::size_of::<MONITORINFO>() as u32, ..Default::default() };
        let _ = GetMonitorInfoW(m, &mut mi);
        mi.rcWork
    }
}

fn dpi_of(r: &RECT) -> u32 {
    unsafe {
        let m = MonitorFromRect(r, MONITOR_DEFAULTTONEAREST);
        let (mut x, mut y) = (0u32, 0u32);
        if GetDpiForMonitor(m, MDT_EFFECTIVE_DPI, &mut x, &mut y).is_ok() { x } else { 0 }
    }
}

fn top_windows() -> Vec<HWND> {
    unsafe extern "system" fn each(h: HWND, data: LPARAM) -> windows::core::BOOL {
        let list = unsafe { &mut *(data.0 as *mut Vec<HWND>) };
        if unsafe { IsWindowVisible(h) }.as_bool() {
            list.push(h);
        }
        windows::core::BOOL(1)
    }
    let mut list: Vec<HWND> = Vec::new();
    unsafe {
        let _ = EnumWindows(Some(each), LPARAM(&mut list as *mut _ as isize));
    }
    list
}

/// Waits (up to `ms`) for something to become true.
fn wait_for(ms: u64, mut f: impl FnMut() -> bool) -> bool {
    let t0 = Instant::now();
    while t0.elapsed() < Duration::from_millis(ms) {
        if f() {
            return true;
        }
        sleep(25);
    }
    f()
}

// ---- real input (like your own mouse and keyboard) -------------------------------------------

fn send(inputs: &[INPUT]) {
    unsafe {
        let _ = SendInput(inputs, std::mem::size_of::<INPUT>() as i32);
    }
}

fn mouse(flags: MOUSE_EVENT_FLAGS, dx: i32, dy: i32, data: i32) -> INPUT {
    INPUT { r#type: INPUT_MOUSE, Anonymous: INPUT_0 { mi: MOUSEINPUT { dx, dy, mouseData: data as u32, dwFlags: flags, time: 0, dwExtraInfo: 0 } } }
}

fn move_by(dx: i32, dy: i32) {
    send(&[mouse(MOUSEEVENTF_MOVE, dx, dy, 0)]);
}

fn click(right: bool) {
    let (d, u) = if right { (MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP) } else { (MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP) };
    send(&[mouse(d, 0, 0, 0)]);
    sleep(40);
    send(&[mouse(u, 0, 0, 0)]);
}

fn key_state(vk: VIRTUAL_KEY, up: bool) {
    send(&[INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: vk, wScan: 0, dwFlags: if up { KEYEVENTF_KEYUP } else { KEYBD_EVENT_FLAGS(0) }, time: 0, dwExtraInfo: 0 } },
    }]);
}

fn key(vk: VIRTUAL_KEY) {
    let k = |up: bool| INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 { ki: KEYBDINPUT { wVk: vk, wScan: 0, dwFlags: if up { KEYEVENTF_KEYUP } else { KEYBD_EVENT_FLAGS(0) }, time: 0, dwExtraInfo: 0 } },
    };
    send(&[k(false)]);
    sleep(30);
    send(&[k(true)]);
}

// ---- test windows ----------------------------------------------------------------------------

/// Every message the test windows got that matters here: (window, message, wParam).
static LOG: Mutex<Vec<(isize, u32, usize)>> = Mutex::new(Vec::new());
/// The stand-in game window holds the cursor like Minecraft (GLFW) does while you play.
static GAME_HOLDS: AtomicBool = AtomicBool::new(false);
static GAME_RESTORE: Mutex<(i32, i32)> = Mutex::new((0, 0));
static OPEN_ANOTHER: AtomicIsize = AtomicIsize::new(0);

unsafe extern "system" fn wndproc(h: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    if matches!(msg, WM_LBUTTONDOWN | WM_LBUTTONUP | WM_RBUTTONDOWN | WM_RBUTTONUP | WM_KEYDOWN | WM_KEYUP | WM_CHAR | WM_MOUSEWHEEL
        | WM_CAPTURECHANGED | WM_MOUSEACTIVATE | WM_ACTIVATE | WM_KILLFOCUS | WM_SETFOCUS | WM_CANCELMODE | WM_NCACTIVATE)
    {
        LOG.lock().unwrap().push((h.0 as isize, msg, w.0));
    }
    match msg {
        WM_ACTIVATE if class_name(h) == "GLFW30" && GAME_HOLDS.load(Ordering::SeqCst) => unsafe {
            if (w.0 & 0xFFFF) != 0 {
                // like GLFW's "disabled" cursor: remember where it was, hold it in the middle
                let c = cursor();
                *GAME_RESTORE.lock().unwrap() = c;
                let r = rect_of(h);
                let (x, y) = center(&r);
                let _ = SetCursorPos(x, y);
                let _ = ClipCursor(Some(&r));
            } else {
                // and when it isn't in front any more: puts the cursor back, lets go of the clip
                let (x, y) = *GAME_RESTORE.lock().unwrap();
                let _ = SetCursorPos(x, y);
                let _ = ClipCursor(None);
            }
        },
        x if x == WM_USER + 1 => {
            // (the test app opens another window, as an app would for a new tab or a link)
            OPEN_ANOTHER.store(1, Ordering::SeqCst);
        }
        WM_DESTROY => unsafe { PostQuitMessage(0) },
        _ => {}
    }
    unsafe { DefWindowProcW(h, msg, w, l) }
}

fn module() -> Option<HINSTANCE> {
    unsafe { windows::Win32::System::LibraryLoader::GetModuleHandleW(PCWSTR::null()).ok().map(|m| HINSTANCE(m.0)) }
}

fn register(class: &str) {
    unsafe {
        let c = wide(class);
        let module = module();
        let wc = WNDCLASSW {
            lpfnWndProc: Some(wndproc),
            hInstance: module.unwrap_or_default(),
            lpszClassName: PCWSTR(c.as_ptr()),
            hbrBackground: HBRUSH(GetStockObject(WHITE_BRUSH).0),
            hCursor: LoadCursorW(None, IDC_ARROW).unwrap_or_default(),
            ..Default::default()
        };
        RegisterClassW(&wc);
        std::mem::forget(c);
    }
}

/// A window on a thread of its own (its own input queue, like a separate app).
fn spawn_window(class: &'static str, name: &str, r: RECT, popup: bool) -> HWND {
    let (tx, rx) = channel::<isize>();
    let name = name.to_string();
    std::thread::spawn(move || unsafe {
        register(class);
        let c = wide(class);
        let t = wide(&name);
        let style = if popup { WS_POPUP | WS_VISIBLE } else { WS_OVERLAPPEDWINDOW | WS_VISIBLE };
        let h = CreateWindowExW(WINDOW_EX_STYLE(0), PCWSTR(c.as_ptr()), PCWSTR(t.as_ptr()), style, r.left, r.top, r.right - r.left, r.bottom - r.top,
            None, None, module(), None).unwrap_or_else(|e| {
            eprintln!("error=couldn't make the test window \"{name}\": {e}");
            HWND::default()
        });
        let _ = tx.send(h.0 as isize);
        let mut msg = MSG::default();
        while GetMessageW(&mut msg, None, 0, 0).as_bool() {
            let _ = TranslateMessage(&msg);
            DispatchMessageW(&msg);
        }
    });
    HWND(rx.recv_timeout(Duration::from_secs(5)).unwrap_or(0) as *mut _)
}

fn close(h: HWND) {
    unsafe {
        let _ = PostMessageW(Some(h), WM_CLOSE, WPARAM(0), LPARAM(0));
    }
}

fn bring_forward(h: HWND) -> bool {
    unsafe {
        // (Windows only lets the foreground's owner hand it on: press-and-release Alt first,
        // the usual way around that)
        key(VK_MENU);
        let _ = ShowWindow(h, SW_SHOW);
        let ok = SetForegroundWindow(h).as_bool();
        sleep(150);
        ok && foreground_root() == h
    }
}

/// `--window <title>`: the little test app (a window; WM_USER+1 opens another one).
fn test_app(name: &str) {
    register("WindowsCraftTestApp");
    unsafe {
        let c = wide("WindowsCraftTestApp");
        let t = wide(name);
        let h = CreateWindowExW(WINDOW_EX_STYLE(0), PCWSTR(c.as_ptr()), PCWSTR(t.as_ptr()), WS_OVERLAPPEDWINDOW | WS_VISIBLE,
            CW_USEDEFAULT, CW_USEDEFAULT, 640, 420, None, None, module(), None).unwrap_or_default();
        let _ = h;
        let mut msg = MSG::default();
        let mut n = 1;
        loop {
            while PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).as_bool() {
                if msg.message == WM_QUIT {
                    return;
                }
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
            if OPEN_ANOTHER.swap(0, Ordering::SeqCst) == 1 {
                n += 1;
                let t2 = wide(&format!("{name} {n}"));
                let _ = CreateWindowExW(WINDOW_EX_STYLE(0), PCWSTR(c.as_ptr()), PCWSTR(t2.as_ptr()), WS_OVERLAPPEDWINDOW | WS_VISIBLE,
                    CW_USEDEFAULT, CW_USEDEFAULT, 640, 420, None, None, module(), None);
            }
            sleep(10);
        }
    }
}

// ---- the helpers, spoken to like the game does -----------------------------------------------

const EVENTS: [&str; 19] = ["gkey", "single", "moved", "adminapp", "gone", "esc", "alt", "look", "zoom", "recenter", "mbtn", "mwheel", "altup", "pointlost", "pointok", "pointfail", "seatmove", "lookmode", "mask"];

struct Helper {
    child: Child,
    stdin: Arc<Mutex<ChildStdin>>,
    answers: Receiver<String>,
    events: Arc<Mutex<Vec<(Instant, String)>>>,
    beating: Arc<AtomicBool>,
}

impl Helper {
    fn start(exe: &std::path::Path) -> Result<Helper, String> { Helper::start_for(exe, std::process::id()) }

    /// As if started by that game process (it ends when that process does).
    fn start_for(exe: &std::path::Path, parent: u32) -> Result<Helper, String> {
        let mut child = Command::new(exe)
            .args(["--parent-pid", &parent.to_string()])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| format!("couldn't start {}: {e}", exe.display()))?;
        let stdin = Arc::new(Mutex::new(child.stdin.take().unwrap()));
        let out = child.stdout.take().unwrap();
        let err = child.stderr.take().unwrap();
        let (tx, answers) = channel::<String>();
        let events: Arc<Mutex<Vec<(Instant, String)>>> = Arc::new(Mutex::new(Vec::new()));
        let ev = events.clone();
        std::thread::spawn(move || {
            for line in BufReader::new(out).lines() {
                let Ok(line) = line else { break };
                let first = line.split(' ').next().unwrap_or("");
                if EVENTS.contains(&first) {
                    ev.lock().unwrap().push((Instant::now(), line));
                } else if tx.send(line).is_err() {
                    break;
                }
            }
        });
        std::thread::spawn(move || {
            for line in BufReader::new(err).lines() {
                let Ok(line) = line else { break };
                HELPER_LOG.lock().unwrap().push(line);
            }
        });
        let beating = Arc::new(AtomicBool::new(true));
        let (b, s) = (beating.clone(), stdin.clone());
        std::thread::spawn(move || {
            // "the game is still there", like the game says every tick while you sit at a monitor
            while b.load(Ordering::SeqCst) {
                if writeln!(s.lock().unwrap(), "beat").is_err() {
                    break;
                }
                sleep(500);
            }
        });
        Ok(Helper { child, stdin, answers, events, beating })
    }

    fn send(&self, line: &str) {
        let mut s = self.stdin.lock().unwrap();
        let _ = writeln!(s, "{line}");
        let _ = s.flush();
    }

    /// Sends a command and collects its answer: lines up to the one starting with `end`.
    fn call(&self, line: &str, end: &str, ms: u64) -> Result<Vec<String>, String> {
        while self.answers.try_recv().is_ok() {}
        self.send(line);
        let t0 = Instant::now();
        let mut got = Vec::new();
        loop {
            let left = Duration::from_millis(ms).checked_sub(t0.elapsed()).ok_or_else(|| format!("no answer to \"{line}\" (got {got:?})"))?;
            match self.answers.recv_timeout(left) {
                Ok(l) => {
                    let done = l.starts_with(end) || (end != "fail" && l.starts_with("fail "));
                    got.push(l);
                    if done {
                        return Ok(got);
                    }
                }
                Err(_) => return Err(format!("no answer to \"{line}\" (got {got:?})")),
            }
        }
    }

    fn events_since(&self, t: Instant) -> Vec<String> {
        self.events.lock().unwrap().iter().filter(|(at, _)| *at >= t).map(|(_, e)| e.clone()).collect()
    }

    fn kill(mut self) {
        self.beating.store(false, Ordering::SeqCst);
        let _ = self.child.kill();
        let _ = self.child.wait();
    }

    fn stop(mut self) {
        self.beating.store(false, Ordering::SeqCst);
        drop(self.stdin);
        let t0 = Instant::now();
        while t0.elapsed() < Duration::from_secs(5) {
            if let Ok(Some(_)) = self.child.try_wait() {
                return;
            }
            sleep(50);
        }
        let _ = self.child.kill();
    }
}

static HELPER_LOG: Mutex<Vec<String>> = Mutex::new(Vec::new());

#[derive(Clone, Copy, Debug)]
struct Mon {
    idx: i32,
    r: RECT,
}

fn add(h: &Helper, w: u32, hh: u32) -> Result<Mon, String> {
    let l = h.call(&format!("add {w} {hh} 60"), "added", 30000)?;
    let last = l.last().cloned().unwrap_or_default();
    let p: Vec<&str> = last.split(' ').collect();
    if p[0] != "added" || p.len() < 7 {
        return Err(last);
    }
    let n = |i: usize| p[i].parse::<i32>().unwrap_or(0);
    Ok(Mon { idx: n(1), r: RECT { left: n(3), top: n(4), right: n(3) + n(5), bottom: n(4) + n(6) } })
}

fn rects(h: &Helper) -> Vec<Mon> {
    let Ok(l) = h.call("rects", "rects-end", 8000) else { return Vec::new() };
    l.iter()
        .filter(|x| x.starts_with("rect "))
        .filter_map(|x| {
            let p: Vec<&str> = x.split(' ').collect();
            let n = |i: usize| p.get(i).and_then(|s| s.parse::<i32>().ok());
            Some(Mon { idx: n(1)?, r: RECT { left: n(3)?, top: n(4)?, right: n(3)? + n(5)?, bottom: n(4)? + n(6)? } })
        })
        .collect()
}

fn now_rect(h: &Helper, idx: i32) -> Option<RECT> {
    rects(h).into_iter().find(|m| m.idx == idx).map(|m| m.r)
}

// ---- the report --------------------------------------------------------------------------------

struct Report {
    lines: Vec<String>,
    fails: usize,
}

impl Report {
    fn line(&mut self, s: String) {
        println!("{s}");
        let _ = std::io::stdout().flush();
        self.lines.push(s);
    }
    fn check(&mut self, name: &str, ok: bool, detail: impl AsRef<str>) {
        if !ok {
            self.fails += 1;
        }
        self.line(format!("{} {name}: {}", if ok { "PASS" } else { "FAIL" }, detail.as_ref()));
    }
    fn skip(&mut self, name: &str, why: impl AsRef<str>) {
        self.line(format!("SKIP {name}: {}", why.as_ref()));
    }
    fn note(&mut self, s: impl AsRef<str>) {
        self.line(format!("     {}", s.as_ref()));
    }
}

/// The whole desktop (every screen) as a .bmp, for looking at afterwards.
fn screenshot(path: &std::path::Path) {
    unsafe {
        let (x, y) = (GetSystemMetrics(SM_XVIRTUALSCREEN), GetSystemMetrics(SM_YVIRTUALSCREEN));
        let (w, h) = (GetSystemMetrics(SM_CXVIRTUALSCREEN), GetSystemMetrics(SM_CYVIRTUALSCREEN));
        if w <= 0 || h <= 0 {
            return;
        }
        let screen = GetDC(None);
        let mem = CreateCompatibleDC(Some(screen));
        let bmp = CreateCompatibleBitmap(screen, w, h);
        let old = SelectObject(mem, bmp.into());
        let _ = BitBlt(mem, 0, 0, w, h, Some(screen), x, y, SRCCOPY);
        let mut info = BITMAPINFO::default();
        info.bmiHeader = BITMAPINFOHEADER {
            biSize: std::mem::size_of::<BITMAPINFOHEADER>() as u32,
            biWidth: w,
            biHeight: -h,
            biPlanes: 1,
            biBitCount: 32,
            biCompression: BI_RGB.0,
            ..Default::default()
        };
        let mut px = vec![0u8; (w * h * 4) as usize];
        GetDIBits(mem, bmp, 0, h as u32, Some(px.as_mut_ptr() as *mut _), &mut info, DIB_RGB_COLORS);
        SelectObject(mem, old);
        let _ = DeleteObject(bmp.into());
        let _ = DeleteDC(mem);
        ReleaseDC(None, screen);
        let mut f = Vec::with_capacity(54 + px.len());
        let size = 54 + px.len() as u32;
        f.extend_from_slice(b"BM");
        f.extend_from_slice(&size.to_le_bytes());
        f.extend_from_slice(&[0, 0, 0, 0]);
        f.extend_from_slice(&54u32.to_le_bytes());
        f.extend_from_slice(&40u32.to_le_bytes());
        f.extend_from_slice(&w.to_le_bytes());
        f.extend_from_slice(&(-h).to_le_bytes());
        f.extend_from_slice(&1u16.to_le_bytes());
        f.extend_from_slice(&32u16.to_le_bytes());
        f.extend_from_slice(&[0u8; 24]);
        f.extend_from_slice(&px);
        let _ = std::fs::write(path, f);
    }
}

// ---- the checks ----------------------------------------------------------------------------------

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let arg = |k: &str| args.iter().position(|a| a == k).and_then(|i| args.get(i + 1)).cloned();
    if let Some(t) = arg("--window") {
        test_app(&t);
        return;
    }
    // a copy named wcapp_….exe is a plain test app: its window, titled by its name, needs no arguments
    // (a program Sandboxie moves into a box may be started again without them)
    let stem = std::env::current_exe().ok().and_then(|p| p.file_stem().map(|s| s.to_string_lossy().to_string())).unwrap_or_default();
    if stem.to_ascii_lowercase().starts_with("wcapp") {
        test_app(&stem);
        return;
    }
    unsafe {
        let _ = SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
    let me = std::env::current_exe().unwrap_or_default();
    let dir = arg("--helpers").map(std::path::PathBuf::from).unwrap_or_else(|| me.parent().unwrap().to_path_buf());
    let shots = arg("--shots").map(std::path::PathBuf::from);
    if let Some(s) = &shots {
        let _ = std::fs::create_dir_all(s);
    }
    let shot = |name: &str| {
        if let Some(s) = &shots {
            screenshot(&s.join(format!("{name}.bmp")));
        }
    };
    let mut rep = Report { lines: Vec::new(), fails: 0 };
    if args.iter().any(|a| a == "--guests-only") {
        // (no driver needed: two people's input into two windows on your own screen)
        let p = primary();
        guests(&mut rep, &dir, &RECT { left: p.left, top: p.top, right: p.left + 1700, bottom: p.top + 700 });
        rep.line(format!("{} failed", rep.fails));
        std::process::exit(rep.fails as i32);
    }
    if let Some(n) = arg("--stress").and_then(|v| v.parse::<u32>().ok()) {
        stress(&dir, n, args.iter().any(|a| a == "--with-b"), arg("--pause").and_then(|v| v.parse().ok()).unwrap_or(300));
        return;
    }
    if args.iter().any(|a| a == "--sandbox") {
        // own copies of apps: WindowsCraft's helper driving Sandboxie-Plus (installed beforehand)
        sandbox(&mut rep, &dir);
        rep.line(format!("{} failed", rep.fails));
        if let Some(path) = arg("--report") {
            let mut text = rep.lines.join("\r\n");
            text.push_str("\r\n\r\n---- helper log ----\r\n");
            text.push_str(&HELPER_LOG.lock().unwrap().join("\r\n"));
            let _ = std::fs::write(&path, text);
        }
        std::process::exit(rep.fails as i32);
    }
    rep.line(format!("WindowsCraft self-test · {} · helpers in {}", windows_version(), dir.display()));
    let real: Vec<(String, RECT)> = monitors();
    rep.note(format!("your screens: {}", real.iter().map(|(d, r)| format!("{d} {}", fmt(r))).collect::<Vec<_>>().join(", ")));
    let prim = primary();
    let started = Instant::now();

    // (several rounds: a check that passes only sometimes is a bug too)
    let rounds = arg("--rounds").and_then(|r| r.parse::<u32>().ok()).unwrap_or(1).max(1);
    for round in 1..=rounds {
        if rounds > 1 {
            rep.line(format!("---- round {round} of {rounds}"));
        }
        run(&mut rep, &dir, &real, prim, &me, &shot);
        sleep(2000);
    }

    rep.line(format!("{} failed · {:.0} s", rep.fails, started.elapsed().as_secs_f64()));
    let log = HELPER_LOG.lock().unwrap().clone();
    if let Some(path) = arg("--report") {
        let mut text = rep.lines.join("\r\n");
        text.push_str("\r\n\r\n---- helper log ----\r\n");
        text.push_str(&log.join("\r\n"));
        let _ = std::fs::write(&path, text);
    }
    std::process::exit(rep.fails as i32);
}

fn windows_version() -> String {
    let out = Command::new("cmd").args(["/c", "ver"]).output().map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).unwrap_or_default();
    out
}

fn run(rep: &mut Report, dir: &std::path::Path, real: &[(String, RECT)], prim: RECT, me: &std::path::Path, shot: &dyn Fn(&str)) {
    let vexe = dir.join("wc-vdisplay.exe");
    let Ok(h) = Helper::start(&vexe).map_err(|e| rep.check("helper starts", false, e)) else { return };
    let t_round = Instant::now();
    // ---- the driver
    match h.call("status", "status", 15000) {
        Ok(l) => {
            let mut s = l.last().cloned().unwrap_or_default();
            if s.starts_with("status error") {
                // the driver got into trouble (in an earlier round, or earlier on this PC): Repair
                let r = h.call("repair", "repair", 120000);
                rep.check("\"Repair driver\" brings a driver with a problem back", r.as_ref().map_or(false, |l| l.last().map_or(false, |x| x.starts_with("repair ok"))),
                    format!("{r:?}"));
                s = h.call("status", "status", 15000).ok().and_then(|l| l.last().cloned()).unwrap_or_default();
            }
            let ok = s.starts_with("status ok");
            rep.check("driver", ok, &s);
            if !ok {
                h.stop();
                return;
            }
        }
        Err(e) => {
            rep.check("driver", false, e);
            h.stop();
            return;
        }
    }
    let real_rects: Vec<RECT> = real.iter().map(|(_, r)| *r).collect();
    let touches_real = |r: &RECT| real_rects.iter().any(|x| edge_contact(x, r));

    // the stand-in game, on your main screen, holding the cursor like Minecraft
    let g = RECT { left: prim.left + 40, top: prim.top + 40, right: prim.left + 40 + 800, bottom: prim.top + 40 + 500 };
    let game = spawn_window("GLFW30", "Minecraft* 1.21.1 (self-test stand-in)", g, false);
    let desk = spawn_window("WindowsCraftDesk", "Desk window (self-test)", RECT { left: prim.right - 520, top: prim.top + 80, right: prim.right - 20, bottom: prim.top + 400 }, false);
    GAME_HOLDS.store(true, Ordering::SeqCst);
    let play = || {
        bring_forward(game);
    };

    // ---- 1. two monitors, each its own station (nothing drags onto them by accident)
    let a = match add(&h, 1920, 1080) {
        Ok(m) => m,
        Err(e) => {
            rep.check("add a monitor", false, e);
            h.stop();
            return;
        }
    };
    let b = match add(&h, 1920, 1080) {
        Ok(m) => m,
        Err(e) => {
            rep.check("add a second monitor", false, e);
            h.stop();
            return;
        }
    };
    rep.check("add two monitors", true, format!("A {} B {}", fmt(&a.r), fmt(&b.r)));
    let ra = now_rect(&h, a.idx).unwrap_or(a.r);
    let rb = now_rect(&h, b.idx).unwrap_or(b.r);
    rep.check("separate monitors never share an edge with your screens", !touches_real(&ra) && !touches_real(&rb), format!("A {} B {}", fmt(&ra), fmt(&rb)));
    rep.check("separate monitors never share an edge with each other", !edge_contact(&ra, &rb), format!("A {} B {}", fmt(&ra), fmt(&rb)));
    rep.check("each monitor has its own taskbar", has_taskbar_on(&ra) && has_taskbar_on(&rb), "Shell_SecondaryTrayWnd on A and B");
    shot("1-two-separate-monitors");

    // ---- 2. joined in the game → joined on the desktop
    let r = h.call(&format!("arrange {}:-1:- {}:{}:R", a.idx, b.idx, a.idx), "arranged", 15000);
    let ra = now_rect(&h, a.idx).unwrap_or(ra);
    let rb = now_rect(&h, b.idx).unwrap_or(rb);
    rep.check("joined monitors sit edge to edge (B right of A)", rb.left == ra.right && rb.top == ra.top,
        format!("{r:?} → A {} B {}", fmt(&ra), fmt(&rb)));
    rep.check("…and still don't touch your screens", !touches_real(&ra) && !touches_real(&rb), format!("A {} B {}", fmt(&ra), fmt(&rb)));
    sleep(1500);
    taskbars_in_place(rep, &h, "right after joining");
    shot("2-joined-monitors");

    // sitting at A: the cursor goes on to B (dragging a window across works)
    play();
    let t = Instant::now();
    let seat = { unsafe { let _ = AllowSetForegroundWindow(ASFW_ANY); } h.call(&format!("seat {} 0.5 0.5 {}", a.idx, game.0 as isize), "seated", 8000) };
    sleep(500);
    let (cx, cy) = cursor();
    let (ax, ay) = center(&ra);
    rep.check("sitting down puts your cursor where you were pointing (not in a corner)", (cx - ax).abs() <= 3 && (cy - ay).abs() <= 3,
        format!("{seat:?}: cursor {cx},{cy}, A's middle {ax},{ay}"));
    for _ in 0..30 {
        move_by(120, 0);
        sleep(15);
    }
    wait_for(2000, || h.events_since(t).iter().any(|e| e == &format!("seatmove {}", b.idx)));
    sleep(200);
    let (cx, cy) = cursor();
    let ev = h.events_since(t);
    rep.check("from a monitor, the cursor goes on to the monitor joined to it", inside(&rb, cx, cy), format!("cursor {cx},{cy}; B {}", fmt(&rb)));
    rep.check("…and you're sitting at that one now", ev.iter().any(|e| e == &format!("seatmove {}", b.idx)), format!("events {ev:?}"));
    // switching has a moment's rest: a slip back over the edge right after doesn't swing you back
    sleep(1300);
    let t_back = Instant::now();
    sleep(400); // (the rest after the last switch is over)
    let mid_y = (rb.top + rb.bottom) / 2;
    unsafe {
        let _ = SetCursorPos(rb.left + 100, mid_y);
    }
    sleep(100);
    unsafe {
        let _ = SetCursorPos(ra.right - 70, mid_y); // (70 px onto A: just over the edge)
    }
    sleep(700);
    // (a glance over the edge isn't a move: only going well onto it and staying)
    let glanced = h.events_since(t_back).iter().any(|e| e.starts_with("seatmove"));
    rep.check("just touching the joined monitor doesn't switch to it", !glanced, format!("{:?}", h.events_since(t_back)));
    unsafe {
        let _ = SetCursorPos(ra.right - 600, mid_y); // (well onto A, and staying)
    }
    let back_ok = wait_for(2000, || h.events_since(t_back).iter().any(|e| e == &format!("seatmove {}", a.idx)));
    let t_slip = Instant::now();
    for _ in 0..6 {
        move_by(80, 0);
        sleep(15);
    }
    sleep(400);
    let (sx, sy) = cursor();
    let slipped = h.events_since(t_slip);
    rep.check("after moving to a joined monitor, slipping back over the edge right away doesn't switch you back",
        back_ok && inside(&ra, sx, sy) && !slipped.iter().any(|e| e.starts_with("seatmove")),
        format!("moved to A: {back_ok}; cursor {sx},{sy}; events {slipped:?}"));
    sleep(1500);
    let t_again = Instant::now();
    for _ in 0..20 {
        move_by(60, 0);
        sleep(15);
    }
    wait_for(2000, || h.events_since(t_again).iter().any(|e| e == &format!("seatmove {}", b.idx)));
    rep.check("…but a moment later it does", h.events_since(t_again).iter().any(|e| e == &format!("seatmove {}", b.idx)),
        format!("events {:?}", h.events_since(t_again)));
    for _ in 0..30 {
        move_by(0, -150);
        move_by(-150, 0);
    }
    sleep(200);
    let (cx, cy) = cursor();
    let all = union(&ra, &rb);
    rep.check("…but never off the joined monitors onto your own screens", inside(&all, cx, cy), format!("cursor {cx},{cy}"));
    // a window drags across
    let win = spawn_window("WindowsCraftTestWin", "Drag me (self-test)", RECT { left: ra.left + 600, top: ra.top + 300, right: ra.left + 1100, bottom: ra.top + 600 }, false);
    sleep(300);
    let wr = rect_of(win);
    unsafe {
        let _ = SetCursorPos(wr.left + 100, wr.top + 12);
    }
    sleep(100);
    send(&[mouse(MOUSEEVENTF_LEFTDOWN, 0, 0, 0)]);
    // (smoothly: the window follows the cursor all the way, never pulled back or thrown about)
    let mut jumps = Vec::new();
    let mut last = rect_of(win);
    for i in 0..40 {
        move_by(40, 0);
        sleep(25);
        let now = rect_of(win);
        if now.left < last.left - 8 || now.left - last.left > 400 || (now.top - last.top).abs() > 60 {
            jumps.push(format!("step {i}: {} → {}", fmt(&last), fmt(&now)));
        }
        last = now;
    }
    let seatmoves_during = h.events_since(t).iter().filter(|e| e.starts_with("seatmove")).count();
    send(&[mouse(MOUSEEVENTF_LEFTUP, 0, 0, 0)]);
    sleep(300);
    rep.check("dragging a window across joined monitors is smooth (it follows the cursor, nothing pulls it back)", jumps.is_empty(),
        format!("{jumps:?}; seat moves {seatmoves_during}"));
    let wr2 = rect_of(win);
    let (wx, wy) = center(&wr2);
    rep.check("a window drags from a monitor onto the one joined to it", inside(&rb, wx, wy), format!("window {} → {}", fmt(&wr), fmt(&wr2)));
    close(win);
    let _ = h.call("unseat", "unseated", 8000);
    sleep(300);

    // not joined any more → nothing drags across
    let _ = h.call(&format!("arrange {}:-1:- {}:-1:-", a.idx, b.idx), "arranged", 15000);
    let ra = now_rect(&h, a.idx).unwrap_or(ra);
    let rb = now_rect(&h, b.idx).unwrap_or(rb);
    rep.check("separated again, they don't touch", !edge_contact(&ra, &rb) && !touches_real(&ra) && !touches_real(&rb), format!("A {} B {}", fmt(&ra), fmt(&rb)));
    play();
    let t = Instant::now();
    let _ = { unsafe { let _ = AllowSetForegroundWindow(ASFW_ANY); } h.call(&format!("seat {} 0.5 0.5 {}", a.idx, game.0 as isize), "seated", 8000) };
    sleep(400);
    for _ in 0..40 {
        move_by(150, 60);
        sleep(10);
    }
    sleep(300);
    let (cx, cy) = cursor();
    rep.check("from a monitor of its own, the cursor can't leave it", inside(&ra, cx, cy) && !h.events_since(t).iter().any(|e| e.starts_with("seatmove")),
        format!("cursor {cx},{cy}; A {}", fmt(&ra)));

    // ---- 3. clicking works while you sit there
    let target = spawn_window("WindowsCraftTestWin", "Click me (self-test)", RECT { left: ra.left + 200, top: ra.top + 200, right: ra.left + 900, bottom: ra.top + 700 }, false);
    sleep(300);
    let tr = rect_of(target);
    let (tx, ty) = center(&tr);
    unsafe {
        let _ = SetCursorPos(tx, ty);
    }
    sleep(100);
    LOG.lock().unwrap().clear();
    let t0 = Instant::now();
    click(false);
    let got = wait_for(1000, || LOG.lock().unwrap().iter().any(|(w, m, _)| *w == target.0 as isize && *m == WM_LBUTTONUP));
    let under = root_at(tx, ty);
    let first_log: Vec<u32> = LOG.lock().unwrap().iter().filter(|(w, _, _)| *w == target.0 as isize).map(|(_, m, _)| *m).collect();
    if !got {
        // (what happens on a second click, once it's in front)
        LOG.lock().unwrap().clear();
        click(false);
        let again = wait_for(1000, || LOG.lock().unwrap().iter().any(|(w, m, _)| *w == target.0 as isize && *m == WM_LBUTTONUP));
        rep.note(format!("first click got {first_log:?}; a second click: {} {:?}; left button down now: {}", again,
            LOG.lock().unwrap().iter().filter(|(w, _, _)| *w == target.0 as isize).map(|(_, m, _)| *m).collect::<Vec<_>>(),
            unsafe { GetAsyncKeyState(VK_LBUTTON.0 as i32) } as u16 & 0x8000 != 0));
    }
    rep.check("clicking on a monitor reaches the app under the cursor", got, format!("{:.0} ms; under the cursor: {} \"{}\"; it got {:?}",
        t0.elapsed().as_secs_f64() * 1000.0, class_name(under), title(under),
        LOG.lock().unwrap().iter().filter(|(w, _, _)| *w == target.0 as isize).map(|(_, m, _)| *m).collect::<Vec<_>>()));
    // typing reaches it too
    LOG.lock().unwrap().clear();
    let t0 = Instant::now();
    key(VK_A);
    let typed = wait_for(1000, || LOG.lock().unwrap().iter().any(|(w, m, _)| *w == target.0 as isize && *m == WM_CHAR));
    rep.check("typing at a monitor reaches the app you clicked", typed, format!("{:.0} ms", t0.elapsed().as_secs_f64() * 1000.0));
    // Alt + Space (look straight again) is the game's: the app gets neither the press nor the release
    LOG.lock().unwrap().clear();
    let t0 = Instant::now();
    key_state(VK_LMENU, false);
    sleep(60);
    key(VK_SPACE);
    sleep(60);
    key_state(VK_LMENU, true);
    sleep(300);
    let leaked: Vec<(u32, usize)> = LOG.lock().unwrap().iter().filter(|(w, m, p)| *w == target.0 as isize && matches!(*m, WM_KEYDOWN | WM_KEYUP | WM_CHAR) && *p == 0x20).map(|(_, m, p)| (*m, *p)).collect();
    let recentered = h.events_since(t0).iter().any(|e| e == "recenter");
    rep.check("Alt + Space while seated looks straight again, and never reaches the app (not even its release)", leaked.is_empty() && recentered,
        format!("app got {leaked:?} · recenter {recentered}"));

    // ---- 3b. File Explorer and Task Manager open on the monitor you sit at (not your desk)
    unsafe {
        let _ = SetCursorPos((ra.left + ra.right) / 2, (ra.top + ra.bottom) / 2);
    }
    sleep(200);
    for (what, class, exe, arg) in [("File Explorer", "CabinetWClass", "explorer.exe", "C:\\Windows"), ("Task Manager", "TaskManagerWindow", "taskmgr.exe", "")] {
        let before: Vec<HWND> = top_windows().into_iter().filter(|w| class_name(*w) == class).collect();
        let mut c = Command::new(exe);
        if !arg.is_empty() {
            c.arg(arg);
        }
        if let Err(e) = c.spawn() {
            rep.note(format!("couldn't start {exe}: {e}"));
        }
        let mut found = None;
        wait_for(10000, || {
            found = top_windows().into_iter().find(|w| class_name(*w) == class && !before.contains(w) && unsafe { IsWindowVisible(*w) }.as_bool());
            found.is_some()
        });
        match found {
            Some(w) => {
                let on = wait_for(5000, || {
                    let (x, y) = center(&rect_of(w));
                    inside(&ra, x, y)
                });
                rep.check(&format!("{what} opened while sitting at a monitor opens on that monitor"), on, format!("at {}", fmt(&rect_of(w))));
                if class == "TaskManagerWindow" {
                    let layer = layer_of_taskmgr();
                    rep.check("…and while monitors are on, Task Manager starts with normal rights (so it can be moved — no permission prompt)",
                        layer.as_deref().map_or(false, |v| v.contains("RUNASINVOKER")), format!("{layer:?}"));
                }
                close(w);
            }
            None => rep.check(&format!("{what} opened while sitting at a monitor opens on that monitor"), false, "it didn't open"),
        }
        sleep(500);
    }
    let _ = Command::new("taskkill").args(["/im", "taskmgr.exe", "/f"]).output();
    sleep(300);

    // ---- 4. right-clicking the monitor's taskbar doesn't throw you out
    let t = Instant::now();
    match taskbar_on(&ra) {
        Some(tb) => {
            let tbr = rect_of(tb);
            unsafe {
                let _ = SetCursorPos(tbr.left + (tbr.right - tbr.left) * 3 / 4, (tbr.top + tbr.bottom) / 2);
            }
            sleep(100);
            click(true);
            sleep(3200); // (the helper calls it "away" after 2 s of something else in front)
            let c = clip();
            let fg = foreground_root();
            let still = !h.events_since(t).iter().any(|e| e == "esc") && c.left >= ra.left && c.right <= ra.right.max(rb.right);
            rep.check("right-clicking a monitor's taskbar keeps you sitting there", still,
                format!("front: {} \"{}\", clip {}", class_name(fg), title(fg), fmt(&c)));
            key(VK_ESCAPE); // (closes the menu)
            sleep(600);
            // an app that only runs once, open on your desk, comes forward when you start it from
            // the monitor: it comes over to the monitor — centred, a modest size, in one move
            play();
            sleep(300);
            let _ = { unsafe { let _ = AllowSetForegroundWindow(ASFW_ANY); } h.call(&format!("seat {} 0.5 0.5 {}", a.idx, game.0 as isize), "seated", 8000) };
            sleep(500);
            let _t1 = Instant::now();
            unsafe {
                let _ = SetCursorPos(tbr.left + (tbr.right - tbr.left) * 3 / 4, (tbr.top + tbr.bottom) / 2);
            }
            sleep(100);
            click(false); // (using the monitor's own taskbar…)
            sleep(400);
            let desk_home = rect_of(desk);
            unsafe {
                let _ = AllowSetForegroundWindow(ASFW_ANY);
                let _ = SetForegroundWindow(desk); // (…and the app brings its one window forward)
            }
            // where it is, every 10 ms: one clean move (no back and forth)
            let mut spots: Vec<RECT> = vec![rect_of(desk)];
            let t2 = Instant::now();
            while t2.elapsed() < Duration::from_millis(1200) {
                let r = rect_of(desk);
                if *spots.last().unwrap() != r {
                    spots.push(r);
                }
                sleep(10);
            }
            let r = rect_of(desk);
            let (dx, dy) = center(&r);
            let wa = work_area_of(&ra);
            let (wx, wy) = center(&wa);
            let (aw, ah) = (wa.right - wa.left, wa.bottom - wa.top);
            let centred = (dx - wx).abs() <= 4 && (dy - wy).abs() <= 4;
            let modest = r.right - r.left >= aw * 2 / 5 && r.right - r.left <= aw * 3 / 4 && r.bottom - r.top >= ah * 2 / 5 && r.bottom - r.top <= ah * 4 / 5;
            let home_visits = spots.iter().skip(1).filter(|x| { let (cx, cy) = center(x); inside(&prim, cx, cy) }).count();
            let fg = foreground_root();
            rep.check("an app that only runs once, open on your desk, comes over to the monitor when you open it there: centred, a modest size, in front",
                inside(&ra, dx, dy) && centred && modest && fg == desk,
                format!("now at {} (monitor's work area {}) · front: {} \"{}\"", fmt(&r), fmt(&wa), class_name(fg), title(fg)));
            rep.check("…in one clean move (it never jumps back)", home_visits == 0 && spots.len() <= 4,
                spots.iter().map(fmt).collect::<Vec<_>>().join(" → "));
            // a Store app (Calculator…): its window already exists, hidden ("cloaked"), and is revealed
            let _ = h.call("unseat", "unseated", 8000);
            sleep(300);
            unsafe {
                let _ = SetCursorPos(prim.left + 300, prim.top + 300); // (made on your desk, before you sit down)
            }
            sleep(200);
            let hidden = spawn_window("WindowsCraftStoreApp", "Calculator (self-test stand-in)", RECT { left: prim.left + 120, top: prim.top + 120, right: prim.left + 520, bottom: prim.top + 620 }, false);
            unsafe {
                let on: windows::core::BOOL = true.into();
                let _ = windows::Win32::Graphics::Dwm::DwmSetWindowAttribute(hidden, windows::Win32::Graphics::Dwm::DWMWA_CLOAK, &on as *const _ as *const _, 4);
            }
            sleep(400);
            // (it was there, hidden, before you sat down — like a Store app's frame)
            play();
            let _ = { unsafe { let _ = AllowSetForegroundWindow(ASFW_ANY); } h.call(&format!("seat {} 0.5 0.5 {}", a.idx, game.0 as isize), "seated", 8000) };
            sleep(500);
            unsafe {
                let _ = SetCursorPos(tbr.left + (tbr.right - tbr.left) * 3 / 4, (tbr.top + tbr.bottom) / 2);
            }
            sleep(100);
            click(false); // (Start on the monitor…)
            sleep(300);
            unsafe {
                let off: windows::core::BOOL = false.into();
                let _ = windows::Win32::Graphics::Dwm::DwmSetWindowAttribute(hidden, windows::Win32::Graphics::Dwm::DWMWA_CLOAK, &off as *const _ as *const _, 4);
                let _ = AllowSetForegroundWindow(ASFW_ANY);
                let _ = SetForegroundWindow(hidden);
            }
            let came = wait_for(2000, || { let (x, y) = center(&rect_of(hidden)); inside(&ra, x, y) });
            rep.check("a Store app (Calculator…) opened from the monitor opens on the monitor", came, format!("its window at {}", fmt(&rect_of(hidden))));
            close(hidden);
            // and back: at your real desktop, you open it from your own taskbar → it comes back to your desk
            let _ = h.call("unseat", "unseated", 8000);
            sleep(4300); // (the monitor keeps a just-placed window there for a few seconds)
            let tray = unsafe { FindWindowW(PCWSTR(wide("Shell_TrayWnd").as_ptr()), PCWSTR::null()) }.unwrap_or_default();
            let trr = rect_of(tray);
            unsafe {
                let _ = SetCursorPos(trr.left + (trr.right - trr.left) * 3 / 4, (trr.top + trr.bottom) / 2);
            }
            sleep(100);
            click(false); // (your own taskbar…)
            sleep(400);
            unsafe {
                let _ = AllowSetForegroundWindow(ASFW_ANY);
                let _ = SetForegroundWindow(desk); // (…and the app brings its one window forward)
            }
            let back = wait_for(1500, || { let (x, y) = center(&rect_of(desk)); inside(&prim, x, y) });
            sleep(500);
            let r = rect_of(desk);
            let (dx, dy) = center(&r);
            let wp = work_area_of(&prim);
            let (wx, wy) = center(&wp);
            rep.check("…and opening it from your own desktop brings it back to your desk, centred", back && (dx - wx).abs() <= 4 && (dy - wy).abs() <= 4,
                format!("now at {} (your work area {})", fmt(&r), fmt(&wp)));
            unsafe {
                let _ = SetWindowPos(desk, None, desk_home.left, desk_home.top, desk_home.right - desk_home.left, desk_home.bottom - desk_home.top, SWP_NOZORDER | SWP_NOACTIVATE);
            }
            sleep(300);
        }
        None => rep.skip("right-clicking a monitor's taskbar keeps you sitting there", "no taskbar on the monitor"),
    }
    let _ = h.call("unseat", "unseated", 8000);
    sleep(300);

    // ---- 5. Esc gets you up and gives the game back (and Start + Esc doesn't pause it)
    play();
    let _ = { unsafe { let _ = AllowSetForegroundWindow(ASFW_ANY); } h.call(&format!("seat {} 0.5 0.5 {}", a.idx, game.0 as isize), "seated", 8000) };
    sleep(600);
    let t = Instant::now();
    key(VK_ESCAPE);
    let back = wait_for(2000, || foreground_root() == game);
    let c = clip();
    rep.check("Esc gets you up: the game is in front again", back && h.events_since(t).iter().any(|e| e == "esc"),
        format!("front {} \"{}\" after {:.0} ms", class_name(foreground_root()), title(foreground_root()), t.elapsed().as_secs_f64() * 1000.0));
    rep.check("…and the cursor is free of the monitor", !(c.left == ra.left && c.top == ra.top && c.right == ra.right && c.bottom == ra.bottom), fmt(&c));
    play();
    let _ = { unsafe { let _ = AllowSetForegroundWindow(ASFW_ANY); } h.call(&format!("seat {} 0.5 0.5 {}", a.idx, game.0 as isize), "seated", 8000) };
    sleep(600);
    key(VK_LWIN); // (a lone tap: the monitor's own Start)
    sleep(1200);
    let start = foreground_root();
    let start_info = format!("{} \"{}\" at {}", class_name(start), title(start), fmt(&rect_of(start)));
    let t = Instant::now();
    key(VK_ESCAPE);
    let back = wait_for(2500, || foreground_root() == game);
    rep.check("Start, then Esc: Start closes and the game comes back in front", back,
        format!("Start was {start_info}; front after: {} \"{}\" ({:.0} ms)", class_name(foreground_root()), title(foreground_root()), t.elapsed().as_secs_f64() * 1000.0));
    sleep(300);

    // ---- 6. a fullscreen app keeps its screen (Esc doesn't bring the taskbar back over it)
    play();
    let _ = { unsafe { let _ = AllowSetForegroundWindow(ASFW_ANY); } h.call(&format!("seat {} 0.5 0.5 {}", a.idx, game.0 as isize), "seated", 8000) };
    sleep(400);
    let full = spawn_window("WindowsCraftTestWin", "Fullscreen (self-test)", ra, true); // (opened while you sit there)
    sleep(400);
    unsafe {
        let _ = SetCursorPos(ra.left + 300, ra.top + 300);
    }
    click(false);
    sleep(600);
    key(VK_ESCAPE);
    sleep(1500);
    let bottom = root_at((ra.left + ra.right) / 2, ra.bottom - 6);
    rep.check("after Esc, a fullscreen app still covers its monitor (no taskbar over it)", bottom == full,
        format!("at the bottom: {} \"{}\"", class_name(bottom), title(bottom)));
    shot("6-fullscreen-after-esc");
    close(full);
    close(target);
    sleep(300);
    // the same with a real browser gone full screen (Edge, like Brave or Chrome: F11 or a video's
    // full-screen button), and still a few seconds later
    edge_fullscreen(rep, &h, &ra, &a, game, shot);

    // ---- 7. your own taskbar is still there
    let tray = unsafe { FindWindowW(PCWSTR(wide("Shell_TrayWnd").as_ptr()), PCWSTR::null()) }.unwrap_or_default();
    let tr = rect_of(tray);
    let (mx, my) = center(&tr);
    let visible = !tray.0.is_null() && unsafe { IsWindowVisible(tray) }.as_bool() && inside(&prim, mx, my) && root_at(mx, my) == tray;
    rep.check("your main taskbar is still there", visible, format!("{} at {}", if tray.0.is_null() { "none" } else { "Shell_TrayWnd" }, fmt(&tr)));

    // ---- 8. new windows open where you opened them
    // from your desk: a new app opens on your desk (not on a monitor)
    play();
    unsafe {
        let _ = SetCursorPos(prim.left + 200, prim.bottom - 200);
    }
    bring_forward(desk);
    let before: HashSet<isize> = top_windows().iter().map(|w| w.0 as isize).collect();
    let note = Command::new("notepad.exe").spawn();
    let new_note = find_new(&before, 8000, |w| exe_of(pid(*w)) == "notepad.exe");
    match new_note {
        Some(n) => {
            sleep(1200);
            let (nx, ny) = center(&rect_of(n));
            rep.check("an app you open on your desk opens on your desk", inside(&prim, nx, ny) || !on_any(&[ra, rb], nx, ny),
                format!("Notepad at {}", fmt(&rect_of(n))));
            close(n);
        }
        None => rep.skip("an app you open on your desk opens on your desk", format!("Notepad didn't show ({:?})", note.err())),
    }
    // an app putting itself back on a monitor by itself (it was last closed there): back to your desk
    bring_forward(desk);
    unsafe {
        let _ = SetCursorPos(prim.left + 200, prim.bottom - 200);
    }
    let stray = spawn_window("WindowsCraftTestWin", "Remembers its last spot (self-test)", RECT { left: ra.left + 100, top: ra.top + 100, right: ra.left + 700, bottom: ra.top + 500 }, false);
    let moved = wait_for(3000, || {
        let (x, y) = center(&rect_of(stray));
        !inside(&ra, x, y)
    });
    rep.check("an app that opens on a monitor by itself goes to your desk", moved, format!("now at {}", fmt(&rect_of(stray))));
    close(stray);

    // started for a monitor: it opens there, and so do the windows it opens while you play
    let app = format!("WindowsCraft test app {}", std::process::id());
    play(); // (you start it from the game: its menu)
    let before: HashSet<isize> = top_windows().iter().map(|w| w.0 as isize).collect();
    let launched = h.call(&format!("launch\t{}\t{}\t--window \"{app}\"\twc-selftest.exe", a.idx, me.display()), "launched", 10000);
    let first = find_new(&before, 8000, |w| title(*w) == app);
    match first {
        Some(w1) => {
            sleep(800);
            let (x, y) = center(&rect_of(w1));
            rep.check("an app started on a monitor opens on that monitor", inside(&ra, x, y), format!("{launched:?}: at {}", fmt(&rect_of(w1))));
            // while you play, it opens another window (someone using that monitor clicked a link)
            play();
            let before: HashSet<isize> = top_windows().iter().map(|w| w.0 as isize).collect();
            unsafe {
                let _ = PostMessageW(Some(w1), WM_USER + 1, WPARAM(0), LPARAM(0));
            }
            match find_new(&before, 5000, |w| title(*w).starts_with(&app)) {
                Some(w2) => {
                    sleep(800);
                    let (x, y) = center(&rect_of(w2));
                    rep.check("…a window it opens while you're playing stays on its monitor", inside(&ra, x, y), format!("at {}", fmt(&rect_of(w2))));
                }
                None => rep.check("…a window it opens while you're playing stays on its monitor", false, "no second window"),
            }
            // and from your desk, its next window opens on your desk
            bring_forward(desk);
            unsafe {
                let _ = SetCursorPos(prim.left + 200, prim.bottom - 200);
            }
            let before: HashSet<isize> = top_windows().iter().map(|w| w.0 as isize).collect();
            unsafe {
                let _ = PostMessageW(Some(w1), WM_USER + 1, WPARAM(0), LPARAM(0));
            }
            match find_new(&before, 5000, |w| title(*w).starts_with(&app)) {
                Some(w3) => {
                    sleep(1000);
                    let (x, y) = center(&rect_of(w3));
                    rep.check("…but a window of it you open from your desk opens on your desk", !inside(&ra, x, y), format!("at {}", fmt(&rect_of(w3))));
                }
                None => rep.check("…but a window of it you open from your desk opens on your desk", false, "no third window"),
            }
            // maximized, it fills its own monitor (like a normal screen)
            unsafe {
                let _ = ShowWindow(w1, SW_MAXIMIZE);
            }
            sleep(500);
            let wa = work_area_of(&ra);
            let m = rect_of(w1);
            let (x, y) = center(&m);
            rep.check("maximized, an app fills its own monitor", inside(&ra, x, y) && (m.right - m.left) >= (wa.right - wa.left) - 2,
                format!("{} on a work area of {}", fmt(&m), fmt(&wa)));
        }
        None => rep.check("an app started on a monitor opens on that monitor", false, format!("{launched:?}, no window")),
    }

    // ---- 9. turning a monitor off ends its apps; windows you brought go home
    let app_pid = top_windows().into_iter().find(|w| title(*w) == app).map(|w| pid(w)).unwrap_or(0);
    let brought = spawn_window("WindowsCraftTestWin", "Brought from the desk (self-test)", RECT { left: prim.left + 100, top: prim.top + 100, right: prim.left + 600, bottom: prim.top + 400 }, false);
    sleep(300);
    let _ = h.call(&format!("bring {} {}", brought.0 as isize, a.idx), "brought", 8000);
    sleep(500);
    let (x, y) = center(&rect_of(brought));
    rep.note(format!("brought a desk window onto A: at {} ({})", fmt(&rect_of(brought)), if inside(&ra, x, y) { "on A" } else { "NOT on A" }));
    let _ = h.call(&format!("remove {}", a.idx), "removed", 15000);
    let gone = app_pid == 0 || wait_for(6000, || !alive(app_pid));
    rep.check("turning a monitor off closes the apps started on it", gone, format!("test app pid {app_pid}"));
    let (x, y) = center(&rect_of(brought));
    rep.check("…and a window you brought from your desk goes back there", unsafe { IsWindow(Some(brought)) }.as_bool() && inside(&prim, x, y),
        format!("at {}", fmt(&rect_of(brought))));
    close(brought);

    // ---- 10. monitors come and go freely (no "Windows didn't switch the new monitor on")
    let mut cycle_errors = Vec::new();
    for i in 0..5 {
        match add(&h, 1920, 1080) {
            Ok(m) => {
                sleep(300);
                if h.call(&format!("remove {}", m.idx), "removed", 15000).is_err() {
                    cycle_errors.push(format!("#{i}: remove didn't answer"));
                }
            }
            Err(e) => cycle_errors.push(format!("#{i}: {e}")),
        }
    }
    rep.check("adding and removing a monitor five times in a row", cycle_errors.is_empty(), format!("{cycle_errors:?}"));
    // (Windows takes a moment to drop a removed monitor from its list: a real leftover stays)
    let t_left = Instant::now();
    let settled = wait_for(3000, || monitors().len() == real.len() + 1);
    let left = monitors().len();
    rep.check("…leaves no monitor behind", settled, format!("{left} screens now: yours + B (settled after {} ms)", t_left.elapsed().as_millis()));
    let strays = stray_taskbars(&real);
    rep.check("no monitor's taskbar ever ends up on your own screens", strays.is_empty(), format!("{strays:?}"));
    let gone: Vec<String> = h.events_since(t_round).into_iter().filter(|e| e.starts_with("gone")).collect();
    rep.check("no monitor ever went away by itself (or it was noticed)", gone.is_empty() && left == real.len() + 1, format!("{gone:?}"));
    // a new monitor is new: Windows mustn't bring back what an old one had (its size, scale, spot)
    match add(&h, 1920, 1080) {
        Ok(m) => {
            let _ = h.call(&format!("setmode {} 2560 1440 60", m.idx), "modeset", 15000);
            sleep(500);
            let _ = h.call(&format!("remove {}", m.idx), "removed", 15000);
            sleep(500);
            match add(&h, 1920, 1080) {
                Ok(n) => {
                    sleep(800);
                    let r = now_rect(&h, n.idx).unwrap_or(n.r);
                    let d = dpi_of(&r);
                    rep.check("a new monitor never comes back with an old one's settings", r.right - r.left == 1920 && r.bottom - r.top == 1080 && d == 96 && !touches_real(&r),
                        format!("same slot {} → {} at {} dpi", n.idx == m.idx, fmt(&r), d));
                    let _ = h.call(&format!("remove {}", n.idx), "removed", 15000);
                }
                Err(e) => rep.check("a new monitor never comes back with an old one's settings", false, e),
            }
        }
        Err(e) => rep.check("a new monitor never comes back with an old one's settings", false, e),
    }

    // ---- 11. the cursor and text stay the same size at any resolution
    let mut sizes = Vec::new();
    let modes = h.call(&format!("modes {}", b.idx), "modes-end", 8000).unwrap_or_default();
    for (w, hh, want) in [(1920, 1080, 96u32), (2560, 1440, 120), (3840, 2160, 192)] {
        if !modes.iter().any(|m| m.starts_with(&format!("mode {w} {hh} "))) {
            sizes.push(format!("{w}×{hh}: not offered"));
            continue;
        }
        let r = h.call(&format!("setmode {} {w} {hh} 60", b.idx), "modeset", 15000);
        sleep(800);
        let rb = now_rect(&h, b.idx).unwrap_or(rb);
        let d = dpi_of(&rb);
        let ok = r.as_ref().map_or(false, |l| l.last().map_or(false, |x| x.starts_with("modeset ok"))) && d == want;
        if !ok {
            rep.fails += 1;
        }
        sizes.push(format!("{w}×{hh}: {} dpi (want {want}){}", d, if ok { "" } else { " ✗" }));
        rep.check(&format!("  {w}×{hh} keeps clear of your screens"), !touches_real(&rb), fmt(&rb));
    }
    rep.line(format!("     cursor/text size per resolution: {}", sizes.join(" · ")));
    // after all those changes: each monitor's taskbar lies whole along its own monitor's edge
    sleep(1500);
    taskbars_in_place(rep, &h, "after joining, resolution changes and moving monitors");
    // pushed half off its monitor on purpose (as Windows sometimes leaves it): put back by itself
    if let Some(rb2) = now_rect(&h, b.idx) {
        if let Some(tb) = taskbar_on(&rb2) {
            let r = rect_of(tb);
            let th = r.bottom - r.top;
            unsafe {
                let _ = SetWindowPos(tb, None, r.left, rb2.bottom - th / 2, r.right - r.left, th, SWP_NOZORDER | SWP_NOACTIVATE);
            }
            sleep(300);
            let moved = rect_of(tb);
            if moved.bottom <= rb2.bottom {
                rep.note(format!("(Windows didn't let the test push the taskbar off: it's at {} — nothing to put back)", fmt(&moved)));
            } else {
                let back = wait_for(6000, || { let t = rect_of(tb); t.bottom <= rb2.bottom && t.top >= rb2.top && t.left == rb2.left && t.right == rb2.right });
                rep.check("a monitor's taskbar pushed half off its monitor goes back in place by itself", back,
                    format!("pushed to {}, now {} (monitor {})", fmt(&moved), fmt(&rect_of(tb)), fmt(&rb2)));
            }
        }
    }

    // ---- 12. your camera never flings: the helper moving the cursor isn't your mouse moving
    let rb = now_rect(&h, b.idx).unwrap_or(rb);
    play();
    let t = Instant::now();
    h.send(&format!("point {} {} 0.5 0.5", b.idx, game.0 as isize));
    let ok = wait_for(3000, || h.events_since(t).iter().any(|e| e.starts_with("pointok") || e.starts_with("pointfail")));
    let ev = h.events_since(t);
    if ok && ev.iter().any(|e| e.starts_with("pointok")) {
        let t1 = Instant::now();
        for i in 0..40 {
            h.send(&format!("ptr {:.3} {:.3}", (i % 10) as f64 / 10.0, (i % 7) as f64 / 7.0));
            sleep(20);
        }
        sleep(300);
        let (lx, ly) = look_sum(&h.events_since(t1));
        rep.check("pointing: the cursor moving by itself doesn't turn your view", lx.abs() + ly.abs() <= 4, format!("view turned by {lx},{ly}"));
        let t2 = Instant::now();
        for _ in 0..10 {
            move_by(20, 0);
            sleep(15);
        }
        sleep(300);
        let (lx, ly) = look_sum(&h.events_since(t2));
        rep.check("pointing: moving your mouse turns your view by that much", lx > 50 && lx < 1000 && ly.abs() < 40, format!("moved 200 right → view {lx},{ly}"));
        // ptr → cursor there: how long
        let target = (rb.left + (rb.right - rb.left) / 4, rb.top + (rb.bottom - rb.top) / 4);
        let t3 = Instant::now();
        h.send("ptr 0.25 0.25");
        let there = wait_for(500, || {
            let c = cursor();
            (c.0 - target.0).abs() <= 2 && (c.1 - target.1).abs() <= 2
        });
        rep.note(format!("pointer latency (game → cursor on the monitor): {} {:.1} ms", if there { "" } else { "never got there," }, t3.elapsed().as_secs_f64() * 1000.0));
        point_clicks(rep, &h, &rb, game);
        h.send("unpoint");
        sleep(400);
    } else {
        rep.check("pointing at a monitor", false, format!("{ev:?}"));
    }

    // ---- 13. two people at once: each their own pointer, window and keys; your desk untouched
    play();
    guests(rep, dir, &rb);

    // ---- 14. a browser of its own for one monitor (its own sound)
    let edge = ["C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe", "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"]
        .into_iter().find(|p| std::path::Path::new(p).exists());
    match edge {
        Some(e) => {
            let profile = std::env::temp_dir().join(format!("wc-selftest-edge-{}", std::process::id()));
            let _ = std::fs::create_dir_all(&profile);
            let others: HashSet<u32> = top_windows().iter().filter(|w| exe_of(pid(**w)) == "msedge.exe").map(|w| pid(*w)).collect();
            let before: HashSet<isize> = top_windows().iter().map(|w| w.0 as isize).collect();
            play();
            let r = h.call(&format!("launch\t{}\t{e}\t--user-data-dir=\"{}\" --no-first-run --no-default-browser-check --new-window about:blank\tmsedge.exe", b.idx, profile.display()), "launched", 10000);
            match find_new(&before, 20000, |w| exe_of(pid(*w)) == "msedge.exe" && !title(*w).is_empty()) {
                Some(w) => {
                    sleep(1500);
                    let (x, y) = center(&rect_of(w));
                    rep.check("\"Own browser\": a separate browser opens on that monitor", inside(&rb, x, y) && !others.contains(&pid(w)),
                        format!("{r:?}: \"{}\" pid {} at {} (other Edge pids {others:?})", title(w), pid(w), fmt(&rect_of(w))));
                    shot("14-own-browser-on-monitor");
                    let p = pid(w);
                    let _ = Command::new("taskkill").args(["/PID", &p.to_string(), "/T", "/F"]).output();
                }
                None => rep.check("\"Own browser\": a separate browser opens on that monitor", false, format!("{r:?}: no Edge window")),
            }
        }
        None => rep.skip("\"Own browser\"", "Edge isn't installed"),
    }

    // ---- 16. the monitor's picture: Windows Graphics Capture sees it (what your wall shows)
    let cap = dir.join("wc-capture.exe");
    if cap.exists() {
        let hmon = unsafe { MonitorFromRect(&rb, MONITOR_DEFAULTTONULL) };
        let name = format!("Local\\wc-selftest-{}", std::process::id());
        match Command::new(&cap).args(["--hmonitor", &(hmon.0 as usize).to_string(), "--cpu", &name, "--box", "full", "--fps", "30", "--cursor", "0",
            "--parent-pid", &std::process::id().to_string()]).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).spawn() {
            Ok(mut c) => {
                let out = c.stdout.take().unwrap();
                let err = c.stderr.take().unwrap();
                let lines: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
                let (l1, l2) = (lines.clone(), lines.clone());
                std::thread::spawn(move || for l in BufReader::new(out).lines().map_while(Result::ok) { l1.lock().unwrap().push(l); });
                std::thread::spawn(move || for l in BufReader::new(err).lines().map_while(Result::ok) { l2.lock().unwrap().push(format!("(log) {l}")); });
                let first = wait_for(6000, || lines.lock().unwrap().iter().any(|l| l.starts_with("frame ")));
                // something changes on the monitor: more pictures come
                let w = spawn_window("WindowsCraftTestWin", "Changing (self-test)", RECT { left: rb.left + 200, top: rb.top + 200, right: rb.left + 900, bottom: rb.top + 700 }, false);
                let n0 = lines.lock().unwrap().iter().filter(|l| l.starts_with("frame ")).count();
                for i in 0..10 {
                    unsafe {
                        let _ = SetWindowPos(w, None, rb.left + 200 + i * 30, rb.top + 200, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
                    }
                    sleep(100);
                }
                sleep(500);
                let n1 = lines.lock().unwrap().iter().filter(|l| l.starts_with("frame ")).count();
                let shown: Vec<String> = lines.lock().unwrap().iter().take(8).cloned().collect();
                rep.check("a monitor's picture is captured (what your wall shows)", first, format!("{shown:?}"));
                rep.check("…and new pictures come as it changes", n1 > n0, format!("{n0} → {n1} pictures"));
                close(w);
                drop(c.stdin.take());
                let _ = c.kill();
            }
            Err(e) => rep.check("a monitor's picture is captured", false, format!("couldn't start wc-capture: {e}")),
        }
    } else {
        rep.skip("a monitor's picture is captured", "wc-capture.exe isn't next to the self-test");
    }

    // ---- 15. a crash leaves nothing behind: the next start is clean
    let _ = h.call(&format!("remove {}", b.idx), "removed", 15000);
    let c = add(&h, 1920, 1080);
    h.kill(); // (like the game crashing)
    sleep(3000);
    let n = monitors().len();
    rep.check("after a crash, its monitors are gone", n == real.len(), format!("{n} screens (yours: {}) — {c:?}", real.len()));
    match Helper::start(&vexe) {
        Ok(h2) => {
            let _ = h2.call("status", "status", 15000);
            let d = add(&h2, 1920, 1080);
            rep.check("…and the next monitor turns on fine", d.is_ok(), format!("{d:?}"));
            if let Ok(d) = d {
                let _ = h2.call(&format!("remove {}", d.idx), "removed", 15000);
            }
            h2.stop();
        }
        Err(e) => rep.check("…and the next monitor turns on fine", false, e),
    }
    sleep(1500);
    let n = monitors().len();
    rep.check("at the end, only your own screens are left", n == real.len(), format!("{n} screens"));
    let layer = layer_of_taskmgr();
    rep.check("…and Task Manager is back to how Windows starts it normally (also after a crash)", layer.is_none(), format!("{layer:?}"));
    GAME_HOLDS.store(false, Ordering::SeqCst);
    unsafe {
        let _ = ClipCursor(None);
    }
    close(game);
    close(desk);
}

/// Two people using windows at once through wc-input (as guests do): each their own target,
/// held buttons and keys — and your own cursor and front window never move.
fn guests(rep: &mut Report, dir: &std::path::Path, rb: &RECT) {
    let rb = *rb;
    let w1 = spawn_window("WindowsCraftTestWin", "Guest 1's app (self-test)", RECT { left: rb.left + 100, top: rb.top + 100, right: rb.left + 800, bottom: rb.top + 600 }, false);
    let w2 = spawn_window("WindowsCraftTestWin", "Guest 2's app (self-test)", RECT { left: rb.left + 900, top: rb.top + 100, right: rb.left + 1600, bottom: rb.top + 600 }, false);
    sleep(400);
    let my_cursor = cursor();
    let my_front = foreground_root();
    match Command::new(dir.join("wc-input.exe")).args(["--parent-pid", &std::process::id().to_string()]).stdin(Stdio::piped()).spawn() {
        Ok(mut inp) => {
            LOG.lock().unwrap().clear();
            {
                let s = inp.stdin.as_mut().unwrap();
                let mut say = |l: String| {
                    let _ = writeln!(s, "{l}");
                    let _ = s.flush();
                    sleep(40);
                };
                say(format!("slot 1 target {}", w1.0 as isize));
                say("slot 1 focus".into());
                say("slot 1 button l down 0.5 0.5".into());
                say(format!("slot 2 target {}", w2.0 as isize));
                say("slot 2 focus".into());
                say("slot 2 button l down 0.5 0.5".into());
                say("slot 2 button l up 0.5 0.5".into());
                say("slot 1 key down 30".into()); // A
                say("slot 2 key down 31".into()); // S
                say("slot 1 key up 30".into());
                say("slot 2 key up 31".into());
                say("slot 1 button l up 0.5 0.5".into());
            }
            sleep(600);
            let log = LOG.lock().unwrap().clone();
            let of = |w: HWND| log.iter().filter(|(h, _, _)| *h == w.0 as isize).map(|(_, m, p)| (*m, *p)).collect::<Vec<_>>();
            let (l1, l2) = (of(w1), of(w2));
            let has = |l: &Vec<(u32, usize)>, m: u32, p: Option<usize>| l.iter().any(|(mm, pp)| *mm == m && p.map_or(true, |p| *pp == p));
            let ok1 = has(&l1, WM_LBUTTONDOWN, None) && has(&l1, WM_LBUTTONUP, None) && has(&l1, WM_KEYDOWN, Some(0x41)) && !has(&l1, WM_KEYDOWN, Some(0x53));
            let ok2 = has(&l2, WM_LBUTTONDOWN, None) && has(&l2, WM_LBUTTONUP, None) && has(&l2, WM_KEYDOWN, Some(0x53)) && !has(&l2, WM_KEYDOWN, Some(0x41));
            // guest 1 held their button the whole time: it only came up at the very end
            let up1 = l1.iter().position(|(m, _)| *m == WM_LBUTTONUP);
            let key1 = l1.iter().position(|(m, p)| *m == WM_KEYDOWN && *p == 0x41);
            let held = matches!((up1, key1), (Some(u), Some(k)) if u > k);
            rep.check("two people at once: each one's clicks and keys reach only their own window", ok1 && ok2, format!("guest 1 got {l1:?}; guest 2 got {l2:?}"));
            rep.check("…and one clicking never lets go of the other's held button", held, format!("{l1:?}"));
            rep.check("…and your own cursor and window never moved", cursor() == my_cursor && foreground_root() == my_front,
                format!("cursor {:?} → {:?}; front {} → {}", my_cursor, cursor(), title(my_front), title(foreground_root())));
            // a guest still holds Shift on their window when you sit down and type there yourself:
            // your typing is yours (no stuck Shift from them)
            {
                let s = inp.stdin.as_mut().unwrap();
                let _ = writeln!(s, "slot 1 key down 42"); // left Shift, held
                let _ = s.flush();
            }
            sleep(300);
            bring_forward(w1);
            let r1 = rect_of(w1);
            unsafe {
                let _ = SetCursorPos((r1.left + r1.right) / 2, (r1.top + r1.bottom) / 2);
            }
            click(false);
            sleep(200);
            LOG.lock().unwrap().clear();
            key(VK_A);
            sleep(400);
            let chars: Vec<usize> = LOG.lock().unwrap().iter().filter(|(h, m, _)| *h == w1.0 as isize && *m == WM_CHAR).map(|(_, _, c)| *c).collect();
            rep.check("a guest's held key never mixes into your own typing", chars == vec![0x61], format!("you typed a, the app got {chars:?}"));
            drop(inp.stdin.take());
            let _ = inp.wait();
        }
        Err(e) => rep.check("guests' input", false, format!("couldn't start wc-input: {e}")),
    }
    close(w1);
    close(w2);

}

/// Alt held, pointing at an app on the monitor from the game: every click reaches it, the game
/// isn't flicked back to the front after each one (its title bar stays looking active), your mouse
/// doesn't jiggle the cursor (hover flicker), and your keys still belong to the game.
fn point_clicks(rep: &mut Report, h: &Helper, rb: &RECT, game: HWND) {
    let (cx, cy) = center(rb);
    let app = spawn_window("WindowsCraftTest", "Point-and-click target (self-test)", RECT { left: cx - 400, top: cy - 250, right: cx + 400, bottom: cy + 250 }, false);
    sleep(700);
    let ar = rect_of(app);
    let (ax, ay) = center(&ar);
    if !inside(rb, ax, ay) {
        rep.check("pointing: an app on the monitor to click", false, format!("it's at {}", fmt(&ar)));
        close(app);
        return;
    }
    key_state(VK_MENU, false); // (you hold Alt to point)
    sleep(60);
    h.send("pointme 1");
    sleep(250);
    let (u, v) = ((ax - rb.left) as f64 / (rb.right - rb.left) as f64, (ay - rb.top) as f64 / (rb.bottom - rb.top) as f64);
    h.send(&format!("ptr {u:.4} {v:.4}"));
    sleep(200);
    LOG.lock().unwrap().clear();
    let mut jiggles = 0;
    for _ in 0..6 {
        click(false);
        sleep(140);
        let c0 = cursor();
        move_by(4, 0); // (your hand on the mouse)
        sleep(2);
        if cursor() != c0 {
            jiggles += 1;
        }
        sleep(60);
    }
    sleep(300);
    let log = LOG.lock().unwrap().clone();
    let clicks = log.iter().filter(|(w, m, _)| *w == app.0 as isize && *m == WM_LBUTTONDOWN).count();
    let game_back = log.iter().filter(|(w, m, p)| *w == game.0 as isize && *m == WM_ACTIVATE && (p & 0xFFFF) != 0).count();
    let last_nc = log.iter().rev().find(|(w, m, _)| *w == game.0 as isize && *m == WM_NCACTIVATE).map(|(_, _, p)| *p);
    rep.check("pointing with Alt: every click reaches the app on the monitor", clicks == 6, format!("{clicks} of 6 clicks"));
    rep.check("…the game isn't flicked to the front after each click (no title bar flicker)", game_back == 0, format!("game came to the front {game_back}×"));
    rep.check("…its title bar keeps looking active meanwhile", last_nc != Some(0), format!("last title bar state sent: {last_nc:?}"));
    rep.check("…your mouse moving doesn't jiggle the cursor on the monitor (no hover flicker)", jiggles == 0, format!("cursor jumped {jiggles} of 6 times"));
    // your keys meanwhile: the game's (walking), not typed into the app
    let t = Instant::now();
    LOG.lock().unwrap().clear();
    key(VK_W);
    sleep(300);
    let typed = LOG.lock().unwrap().iter().any(|(w, m, p)| *w == app.0 as isize && *m == WM_KEYDOWN && *p == 0x57);
    let to_game = h.events_since(t).iter().any(|e| e.starts_with("gkey 87 ") && e.ends_with(" 1"));
    rep.check("…and your keys go to the game, not into the app", to_game && !typed, format!("game got W: {to_game} · app got W: {typed}"));
    let t = Instant::now();
    key_state(VK_MENU, true);
    let up = wait_for(1000, || h.events_since(t).iter().any(|e| e == "altup"));
    rep.check("…letting go of Alt ends pointing", up, format!("{:?}", h.events_since(t)));
    close(app);
}

/// A real browser (Edge) on the monitor, gone full screen while you sit there; Esc gets you up:
/// its monitor's taskbar never comes up over it — right away, and still a few seconds later.
fn edge_fullscreen(rep: &mut Report, h: &Helper, ra: &RECT, a: &Mon, game: HWND, shot: &dyn Fn(&str)) {
    let Some(e) = ["C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe", "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe"]
        .into_iter().find(|p| std::path::Path::new(p).exists()) else {
        rep.skip("a full-screen browser keeps its monitor after Esc", "Edge isn't installed");
        return;
    };
    let profile = std::env::temp_dir().join(format!("wc-selftest-edge-full-{}", std::process::id()));
    let _ = std::fs::create_dir_all(&profile);
    let before: HashSet<isize> = top_windows().iter().map(|w| w.0 as isize).collect();
    let r = h.call(&format!("launch\t{}\t{e}\t--user-data-dir=\"{}\" --no-first-run --no-default-browser-check --new-window about:blank\tmsedge.exe", a.idx, profile.display()), "launched", 10000);
    // (its browser window: not one of the little pop-ups Edge sometimes shows first)
    let big = |w: &HWND| { let r = rect_of(*w); r.right - r.left >= 500 && r.bottom - r.top >= 350 };
    let Some(w) = find_new(&before, 20000, |w| exe_of(pid(*w)) == "msedge.exe" && !title(*w).is_empty() && big(w)) else {
        rep.check("a full-screen browser keeps its monitor after Esc", false, format!("{r:?}: no Edge window"));
        return;
    };
    sleep(2500);
    unsafe {
        let _ = AllowSetForegroundWindow(ASFW_ANY);
    }
    let _ = h.call(&format!("seat {} 0.5 0.5 {}", a.idx, game.0 as isize), "seated", 8000);
    sleep(400);
    let (x, y) = center(&rect_of(w));
    unsafe {
        let _ = SetCursorPos(x, y);
    }
    click(false); // (into it, then full screen)
    sleep(400);
    key(VK_F11);
    sleep(2000);
    let wr = rect_of(w);
    let full = wr.left <= ra.left && wr.top <= ra.top && wr.right >= ra.right && wr.bottom >= ra.bottom;
    key(VK_ESCAPE); // (gets you up — Edge stays full screen)
    sleep(1200);
    let at = |w: HWND| unsafe { GetAncestor(w, GA_ROOT) };
    let b1 = at(root_at((ra.left + ra.right) / 2, ra.bottom - 6));
    sleep(3500);
    let b2 = at(root_at((ra.left + ra.right) / 2, ra.bottom - 6));
    shot("6b-browser-fullscreen-after-esc");
    rep.check("a real browser gone full screen on a monitor keeps it after Esc: no taskbar over it, then or later",
        full && b1 == w && b2 == w,
        format!("Edge at {} (full screen: {full}); at the bottom right after: {} \"{}\", 5 s later: {} \"{}\"", fmt(&wr), class_name(b1), title(b1), class_name(b2), title(b2)));
    let p = pid(w);
    let _ = Command::new("taskkill").args(["/PID", &p.to_string(), "/T", "/F"]).output();
    sleep(500);
}

/// Own copies of apps, end to end through WindowsCraft's helper: each monitor maps to its own box;
/// sitting at a monitor, what you start lands in *that* monitor's box, on that monitor; exceptions
/// stay out; getting up, turning own copies off, stopping the helper, the helper crashing and the
/// game going away all leave no rule behind that would box your desk's apps.
fn sandbox(rep: &mut Report, dir: &std::path::Path) {
    let vexe = dir.join("wc-vdisplay.exe");
    let Ok(h) = Helper::start(&vexe).map_err(|e| rep.check("helper starts", false, e)) else { return };
    let st = h.call("sbiestatus", "sbie", 15000).ok().and_then(|l| l.last().cloned()).unwrap_or_default();
    rep.check("the helper finds Sandboxie running", st == "sbie ok", &st);
    if st != "sbie ok" {
        h.stop();
        return;
    }
    let _ = h.call("status", "status", 15000);
    // test apps, each in a folder of its own (like installed apps): a, b, c (ordinary), x (excluded
    // by name), h (excluded by folder)
    let apps = std::path::Path::new("C:\\wcapps");
    let app = |n: &str| -> std::path::PathBuf {
        let sub = if n == "wcapp_h" { "exc".to_string() } else { n.trim_start_matches("wcapp_").to_string() };
        apps.join(sub).join(format!("{n}.exe"))
    };
    for n in ["wcapp_a", "wcapp_b", "wcapp_c", "wcapp_x", "wcapp_h"] {
        let _ = std::fs::create_dir_all(app(n).parent().unwrap());
        let _ = std::fs::copy(dir.join("wc-selftest.exe"), app(n));
    }
    let tag = std::process::id();
    let setup = |h: &Helper, idx: i32, key: &str| {
        h.send(&format!("profilekey {idx} {key}"));
        h.send(&format!("isolate {idx} 1"));
    };
    let exceptions = |h: &Helper| h.send(&format!("isoexclude\twcapp_x.exe\t|\t{}\t|\t{}", apps.join("exc").display(), apps.display()));
    let (Ok(ma), Ok(mb)) = (add(&h, 1920, 1080), add(&h, 1920, 1080)) else {
        rep.check("two monitors for the test", false, "couldn't add them");
        h.stop();
        return;
    };
    let (ka, kb) = (format!("sta{tag}"), format!("stb{tag}"));
    setup(&h, ma.idx, &ka);
    setup(&h, mb.idx, &kb);
    exceptions(&h);
    sleep(400);
    let prim = primary();
    let game = spawn_window("GLFW30", "Minecraft* 1.21.1 (self-test stand-in)", RECT { left: prim.left + 40, top: prim.top + 40, right: prim.left + 840, bottom: prim.top + 540 }, false);
    GAME_HOLDS.store(true, Ordering::SeqCst);
    let ini_forced = || -> Vec<String> {
        let b = std::fs::read(std::path::Path::new(&std::env::var("WINDIR").unwrap_or_default()).join("Sandboxie.ini")).unwrap_or_default();
        let t = if b.len() > 2 && b[0] == 0xFF { String::from_utf16_lossy(&b[2..].chunks(2).map(|c| u16::from_le_bytes([c[0], *c.get(1).unwrap_or(&0)])).collect::<Vec<_>>()) } else { String::from_utf8_lossy(&b).into_owned() };
        let mut sec = String::new();
        let mut out = Vec::new();
        for l in t.lines() {
            let l = l.trim();
            if l.starts_with('[') { sec = l.to_string(); } else if l.to_lowercase().starts_with("forcefolder") { out.push(format!("{sec} {l}")); }
        }
        out
    };
    let in_box = |h: &Helper, idx: i32| -> Vec<u32> {
        h.call(&format!("boxapps {idx}"), "windows-end", 10000).unwrap_or_default().iter().filter(|l| l.starts_with("win "))
            .filter_map(|l| l.split(' ').nth(2).and_then(|p| p.parse().ok())).collect()
    };
    // starts a test app the normal way (as from a monitor's own Start menu); its window
    let start = |exe: &std::path::Path| -> Option<HWND> {
        let stem = exe.file_stem().map(|s| s.to_string_lossy().to_string()).unwrap_or_default();
        use std::os::windows::process::CommandExt;
        let _ = Command::new(exe).current_dir("C:\\").creation_flags(0x0800_0000).spawn(); // (no console window)
        // (in a box its title could carry Sandboxie's [#] marker: that's checked on its own)
        (0..48).find_map(|_| { sleep(250); top_windows().into_iter().find(|w| title(*w).contains(&stem) && class_name(*w) != "ConsoleWindowClass") })
    };
    let finish = |w: Option<HWND>| if let Some(w) = w { let p = pid(w); let _ = Command::new("taskkill").args(["/PID", &p.to_string(), "/T", "/F"]).output(); };
    let rep_times = std::sync::Mutex::new(Vec::<u128>::new());
    let seat = |h: &Helper, idx: i32| {
        // (right after getting up, the helper can still be letting go: the game tries again too)
        let mut r = Err(String::new());
        for _ in 0..6 {
            bring_forward(game);
            unsafe { let _ = AllowSetForegroundWindow(ASFW_ANY); }
            r = h.call(&format!("seat {idx} 0.5 0.5 {}", game.0 as isize), "seated", 8000);
            if r.as_ref().map_or(false, |l| l.last().map_or(false, |x| x == "seated")) {
                break;
            }
            sleep(500);
        }
        // (the rule follows within ~100 ms — the very first time, once the app folders are worked out)
        let t = Instant::now();
        sleep(200);
        // (as soon as Sandboxie itself goes by it — the helper checks with it)
        let _ = wait_for(5000, || h.call("forcestate", "forcestate", 3000).ok().and_then(|l| l.last().cloned()).map_or(false, |l| l != "forcestate -"));
        rep_times.lock().unwrap().push(t.elapsed().as_millis());
        r
    };
    let on = |w: Option<HWND>, r: &RECT| w.map_or(false, |w| { let (x, y) = center(&rect_of(w)); inside(r, x, y) });
    let ra = now_rect(&h, ma.idx).unwrap_or(ma.r);
    let rb = now_rect(&h, mb.idx).unwrap_or(mb.r);

    // 1. not sitting anywhere: no rule, apps start normally
    let w = start(&app("wcapp_a"));
    rep.check("not sitting anywhere: no rule, apps start normally", w.is_some() && ini_forced().is_empty() && !in_box(&h, ma.idx).contains(&w.map_or(0, pid)),
        format!("window {:?} · rules {:?}", w.map(pid), ini_forced()));
    finish(w);
    // 2. each monitor its own box: sitting at A → A's box, on A
    let s = seat(&h, ma.idx);
    let rules = ini_forced();
    rep.check("sitting at monitor A: the rule is on for A's box only", !rules.is_empty() && rules.iter().all(|l| l.contains(&format!("[WC{ka}]"))), format!("{s:?} · {} folders", rules.len()));
    let mine: Vec<&String> = rules.iter().filter(|l| l.to_lowercase().contains("wcapps")).collect();
    let low = |l: &String| l.to_lowercase().replace('/', "\\");
    rep.check("…it covers the ordinary apps' folders, and is carved around the exceptions (not their folders, nor a folder above them)",
        ["\\wcapps\\a", "\\wcapps\\b", "\\wcapps\\c"].iter().all(|f| mine.iter().any(|l| low(l).ends_with(f)))
            && !mine.iter().any(|l| { let l = low(l); l.ends_with("\\wcapps") || l.ends_with("\\wcapps\\x") || l.ends_with("\\wcapps\\exc") }),
        format!("{mine:?}"));
    let wa = start(&app("wcapp_a"));
    let (ina, inb) = (in_box(&h, ma.idx), in_box(&h, mb.idx));
    rep.check("…an app you start there runs in A's box", wa.is_some() && ina.contains(&wa.map_or(0, pid)) && !inb.contains(&wa.map_or(0, pid)),
        format!("window {:?} · A's box {ina:?} · B's box {inb:?}", wa.map(pid)));
    rep.check("…and opens on monitor A", on(wa, &ra), format!("at {} (A {})", wa.map(|w| fmt(&rect_of(w))).unwrap_or_default(), fmt(&ra)));
    rep.check("…looking like itself: no Sandboxie [#] marker in its title", wa.map_or(false, |w| !title(w).contains("[#]")), format!("title {:?}", wa.map(title)));
    // 3. exceptions stay out, while sitting there
    let wx = start(&app("wcapp_x"));
    let wh = start(&app("wcapp_h"));
    let ina = in_box(&h, ma.idx);
    rep.check("an app on the always-normal list stays out of the box", wx.is_some() && !ina.contains(&wx.map_or(0, pid)), format!("{:?} · A's box {ina:?}", wx.map(pid)));
    rep.check("an app in an excepted folder (WindowsCraft, the game, anti-cheat games) stays out too", wh.is_some() && !ina.contains(&wh.map_or(0, pid)), format!("{:?}", wh.map(pid)));
    finish(wx);
    finish(wh);
    // 4. over to B: B's box, on B — and A's app stays A's
    let _ = h.call("unseat", "unseated", 8000);
    let s = seat(&h, mb.idx);
    let rules = ini_forced();
    rep.check("sitting at monitor B: the rule moved to B's box", !rules.is_empty() && rules.iter().all(|l| l.contains(&format!("[WC{kb}]"))), format!("{s:?} · {rules:?}"));
    let wb = start(&app("wcapp_b"));
    let (ina, inb) = (in_box(&h, ma.idx), in_box(&h, mb.idx));
    rep.check("…an app started at B runs in B's box, on B", wb.is_some() && inb.contains(&wb.map_or(0, pid)) && !ina.contains(&wb.map_or(0, pid)) && on(wb, &rb),
        format!("window {:?} at {} · A's box {ina:?} · B's box {inb:?}", wb.map(pid), wb.map(|w| fmt(&rect_of(w))).unwrap_or_default()));
    rep.check("…and A's app is still A's, on A", wa.map_or(false, |w| ina.contains(&pid(w)) && !inb.contains(&pid(w))) && on(wa, &ra), format!("A's box {ina:?}"));
    // 5. own copies off everywhere: no rule even while sitting
    h.send(&format!("isolate {} 0", ma.idx));
    h.send(&format!("isolate {} 0", mb.idx));
    sleep(600);
    let rules = ini_forced();
    let w = start(&app("wcapp_c"));
    rep.check("own copies turned off on every monitor: no rule left, even sitting there; apps start normally", rules.is_empty() && w.is_some() && !in_box(&h, mb.idx).contains(&w.map_or(0, pid)),
        format!("rules {rules:?} · {:?}", w.map(pid)));
    finish(w);
    // 6. the helper stopped (the game closing it) while sitting at a monitor with own copies on
    h.send(&format!("isolate {} 1", mb.idx));
    sleep(600);
    let before = ini_forced();
    rep.note(format!("(sitting at B, own copies back on: {} folders)", before.len()));
    h.stop();
    sleep(1500);
    let after = ini_forced();
    rep.check("the helper stopping while you sit there leaves no rule behind", !before.is_empty() && after.is_empty(), format!("before {before:?} · after {after:?}"));
    let w = start(&app("wcapp_c"));
    let off = w.is_some() && ini_forced().is_empty();
    rep.check("…and apps start normally afterwards", off, format!("{:?}", w.map(pid)));
    finish(w);
    // 7. the helper crashing while you sit there: the rule stays until a helper starts again, which clears it
    let Ok(h2) = Helper::start(&vexe).map_err(|e| rep.check("helper starts again", false, e)) else { return };
    let _ = h2.call("status", "status", 15000);
    if let Ok(m) = add(&h2, 1920, 1080) {
        setup(&h2, m.idx, &ka);
        exceptions(&h2);
        sleep(300);
        let sr = seat(&h2, m.idx);
        let before = ini_forced();
        rep.note(format!("(sat down for this check: {sr:?} · {} folders)", before.len()));
        h2.kill();
        sleep(800);
        let stuck = ini_forced();
        let t = Instant::now();
        let h3 = Helper::start(&vexe);
        let cleared = wait_for(5000, || ini_forced().is_empty());
        rep.check("after the helper crashes, the next helper clears the leftover rule as it starts", !before.is_empty() && cleared,
            format!("before {before:?} · after the crash {stuck:?} · cleared {cleared} in {} ms", t.elapsed().as_millis()));
        if let Ok(h3) = h3 { h3.stop(); }
    } else {
        rep.check("a monitor for the crash check", false, "couldn't add one");
    }
    // 8. the game going away (the helper's game process ends) while you sit there
    let mut dummy = Command::new("cmd").args(["/c", "ping -n 120 127.0.0.1 >nul"]).spawn().ok();
    if let Some(d) = dummy.as_mut() {
        if let Ok(h4) = Helper::start_for(&vexe, d.id()) {
            let _ = h4.call("status", "status", 15000);
            if let Ok(m) = add(&h4, 1920, 1080) {
                setup(&h4, m.idx, &kb);
                exceptions(&h4);
                sleep(300);
                let sr = seat(&h4, m.idx);
                let before = ini_forced();
                rep.note(format!("(sat down for this check: {sr:?} · {} folders)", before.len()));
                let _ = d.kill();
                let cleared = wait_for(8000, || ini_forced().is_empty());
                rep.check("the game going away while you sit there leaves no rule behind", !before.is_empty() && cleared, format!("before {before:?} · cleared {cleared}"));
            }
            h4.kill();
        }
    }
    // 9. "open another copy here" (not sitting) and turning a monitor off
    if let Ok(h5) = Helper::start(&vexe) {
        let _ = h5.call("status", "status", 15000);
        if let Ok(m) = add(&h5, 1920, 1080) {
            setup(&h5, m.idx, &ka);
            exceptions(&h5);
            sleep(300);
            let rm = now_rect(&h5, m.idx).unwrap_or(m.r);
            let r = h5.call(&format!("boxlaunch\t{}\t{}\t", m.idx, app("wcapp_c").display()), "boxlaunched", 20000);
            let c = (0..80).find_map(|_| { sleep(250); top_windows().into_iter().find(|w| title(*w).contains("wcapp_c") && class_name(*w) != "ConsoleWindowClass") });
            sleep(600);
            let inb = in_box(&h5, m.idx);
            let boxed = c.map_or(false, |w| inb.contains(&pid(w)));
            let theirs: Vec<String> = top_windows().into_iter().filter(|w| inb.contains(&pid(*w))).map(|w| format!("{}:{:?}/{}", pid(w), title(w), class_name(w))).collect();
            rep.check("\"Open another copy here\" starts it in that monitor's box, on that monitor", boxed && on(c, &rm),
                format!("{r:?} · {:?} at {} · in the box: {inb:?} · their windows: {theirs:?}", c.map(pid), c.map(|w| fmt(&rect_of(w))).unwrap_or_default()));
            // another copy of an app that otherwise always runs normally (a second Discord)
            let r = h5.call(&format!("boxlaunch\t{}\t{}\t", m.idx, app("wcapp_x").display()), "boxlaunched", 20000);
            let x = (0..80).find_map(|_| { sleep(250); top_windows().into_iter().find(|w| title(*w).contains("wcapp_x") && class_name(*w) != "ConsoleWindowClass") });
            sleep(600);
            let boxed = x.map_or(false, |w| in_box(&h5, m.idx).contains(&pid(w)));
            rep.check("…also for an app that otherwise always runs normally (a second Discord, on purpose)", boxed && on(x, &rm),
                format!("{r:?} · {:?} at {}", x.map(pid), x.map(|w| fmt(&rect_of(w))).unwrap_or_default()));
            sleep(3500);
            let ini = std::fs::read(std::path::Path::new(&std::env::var("WINDIR").unwrap_or_default()).join("Sandboxie.ini")).unwrap_or_default();
            let ini = String::from_utf16_lossy(&ini.get(2..).unwrap_or(&[]).chunks(2).map(|c| u16::from_le_bytes([c[0], *c.get(1).unwrap_or(&0)])).collect::<Vec<_>>());
            rep.check("…and right after, it's back on the always-normal list (a boxed app starting it gets your real one)",
                ini.to_lowercase().contains("breakoutprocess=wcapp_x.exe"), "");
            let _ = h5.call(&format!("remove {}", m.idx), "removed", 30000);
            sleep(1500);
            let left = c.map_or(false, |w| unsafe { IsWindow(Some(w)).as_bool() });
            rep.check("turning that monitor off closes its own copies", !left, format!("still open: {left}"));
        }
        h5.stop();
    }
    sleep(1000);
    rep.note(format!("(the rule was there this many ms after sitting down: {:?})", rep_times.lock().unwrap()));
    rep.check("at the end, no rule is left that would box your desk's apps", ini_forced().is_empty(), format!("{:?}", ini_forced()));
    if let Some(sb) = ["C:\\Program Files\\Sandboxie-Plus\\Start.exe"].into_iter().find(|p| std::path::Path::new(p).exists()) {
        let _ = Command::new(sb).arg("/terminate_all").output();
    }
    close(game);
}

fn union(a: &RECT, b: &RECT) -> RECT {
    RECT { left: a.left.min(b.left), top: a.top.min(b.top), right: a.right.max(b.right), bottom: a.bottom.max(b.bottom) }
}

fn on_any(rs: &[RECT], x: i32, y: i32) -> bool {
    rs.iter().any(|r| inside(r, x, y))
}

fn look_sum(ev: &[String]) -> (i64, i64) {
    let mut s = (0i64, 0i64);
    for e in ev {
        let p: Vec<&str> = e.split(' ').collect();
        if p[0] == "look" && p.len() >= 3 {
            s.0 += p[1].parse::<i64>().unwrap_or(0);
            s.1 += p[2].parse::<i64>().unwrap_or(0);
        }
    }
    s
}

fn find_new(before: &HashSet<isize>, ms: u64, pick: impl Fn(&HWND) -> bool) -> Option<HWND> {
    let t0 = Instant::now();
    while t0.elapsed() < Duration::from_millis(ms) {
        if let Some(w) = top_windows().into_iter().find(|w| !before.contains(&(w.0 as isize)) && unsafe { GetAncestor(*w, GA_ROOT) } == *w && pick(w)) {
            return Some(w);
        }
        sleep(100);
    }
    None
}

fn taskbar_on(r: &RECT) -> Option<HWND> {
    let mut found = None;
    let class = wide("Shell_SecondaryTrayWnd");
    let mut after: Option<HWND> = None;
    loop {
        let h = unsafe { FindWindowExW(None, after, PCWSTR(class.as_ptr()), PCWSTR::null()) }.unwrap_or_default();
        if h.0.is_null() {
            break;
        }
        let (x, y) = center(&rect_of(h));
        if inside(r, x, y) {
            found = Some(h);
            break;
        }
        after = Some(h);
    }
    found
}

/// Every in-game monitor's taskbar: whole, along its own monitor's edge, its full width.
fn taskbars_in_place(rep: &mut Report, h: &Helper, when: &str) {
    let mut bad = Vec::new();
    let mut n = 0;
    for m in rects(h) {
        let a = now_rect(h, m.idx).unwrap_or(m.r);
        let Some(tb) = taskbar_on(&a).or_else(|| {
            // (half off: its middle may be just past the edge)
            let class = wide("Shell_SecondaryTrayWnd");
            let mut after: Option<HWND> = None;
            loop {
                let t = unsafe { FindWindowExW(None, after, PCWSTR(class.as_ptr()), PCWSTR::null()) }.unwrap_or_default();
                if t.0.is_null() { return None; }
                let r = rect_of(t);
                if r.left < a.right && r.right > a.left && r.top < a.bottom && r.bottom > a.top { return Some(t); }
                after = Some(t);
            }
        }) else { continue };
        if unsafe { !IsWindowVisible(tb).as_bool() } { continue; }
        n += 1;
        let r = rect_of(tb);
        let ok = r.left == a.left && r.right == a.right && r.top >= a.top && r.bottom <= a.bottom && (r.bottom == a.bottom || r.top == a.top);
        if !ok {
            bad.push(format!("monitor {} at {}: taskbar at {}", m.idx, fmt(&a), fmt(&r)));
        }
    }
    rep.check(&format!("every monitor's taskbar lies whole along its own monitor ({when})"), bad.is_empty() && n > 0, format!("{n} taskbars; {bad:?}"));
}

fn has_taskbar_on(r: &RECT) -> bool {
    taskbar_on(r).is_some()
}


/// An experiment: add and remove a monitor over and over, timing each, until the driver stops answering.
fn stress(dir: &std::path::Path, n: u32, with_b: bool, pause: u64) {
    let env: Vec<String> = std::env::vars().filter(|(k, _)| k.starts_with("WC_VDD")).map(|(k, v)| format!("{k}={v}")).collect();
    println!("STRESS {n} cycles, other monitor on: {with_b}, pause {pause} ms, {env:?}");
    let Ok(h) = Helper::start(&dir.join("wc-vdisplay.exe")) else { println!("STRESS helper didn't start"); return };
    let st = h.call("status", "status", 15000).ok().and_then(|l| l.last().cloned()).unwrap_or_default();
    if !st.starts_with("status ok") {
        let r = h.call("repair", "repair", 120000);
        println!("STRESS driver was {st:?}; repair: {r:?}");
    }
    let b = if with_b { add(&h, 1920, 1080).ok() } else { None };
    println!("STRESS other monitor: {:?}", b.map(|b| b.idx));
    let t_all = Instant::now();
    let mut times = Vec::new();
    let mut failed_at = None;
    for i in 0..n {
        let t = Instant::now();
        match add(&h, 1920, 1080) {
            Ok(m) => {
                times.push(t.elapsed().as_millis());
                let b_now = b.map(|b| now_rect(&h, b.idx).is_some());
                println!("STRESS #{i}: got monitor {} in {} ms; other monitor there: {b_now:?}", m.idx, t.elapsed().as_millis());
                sleep(pause);
                if h.call(&format!("remove {}", m.idx), "removed", 30000).is_err() {
                    failed_at = Some(format!("#{i}: remove didn't answer"));
                    break;
                }
                sleep(pause);
            }
            Err(e) => {
                failed_at = Some(format!("#{i} after {} ms: {e}", t.elapsed().as_millis()));
                break;
            }
        }
    }
    let b_alive = b.map(|b| now_rect(&h, b.idx).is_some());
    println!("STRESS result: {} ok cycles in {:.0} s; failed: {failed_at:?}; other monitor still there: {b_alive:?}; add times ms {times:?}",
        times.len(), t_all.elapsed().as_secs_f64());
    let st = h.call("status", "status", 15000).ok().and_then(|l| l.last().cloned()).unwrap_or_default();
    println!("STRESS driver now: {st}");
    if !st.starts_with("status ok") {
        let r = h.call("repair", "repair", 120000);
        println!("STRESS repair: {r:?}");
    }
    h.stop();
    sleep(1000);
    for l in HELPER_LOG.lock().unwrap().iter().filter(|l| l.contains("driver") || l.contains("error")) {
        println!("  helper: {l}");
    }
    HELPER_LOG.lock().unwrap().clear();
}


/// What Windows is told about how to start Task Manager (the "Run as invoker" setting), if anything.
fn layer_of_taskmgr() -> Option<String> {
    let out = Command::new("reg").args(["query", "HKCU\\Software\\Microsoft\\Windows NT\\CurrentVersion\\AppCompatFlags\\Layers"]).output().ok()?;
    let text = String::from_utf8_lossy(&out.stdout).to_string();
    text.lines().find(|l| l.to_ascii_lowercase().contains("taskmgr.exe")).map(|l| l.trim().to_string())
}


/// Monitor taskbars (Shell_SecondaryTrayWnd) showing on one of your own screens that already has a taskbar.
fn stray_taskbars(real: &[(String, RECT)]) -> Vec<String> {
    let trays: Vec<(HWND, RECT)> = top_windows().into_iter().filter(|w| matches!(class_name(*w).as_str(), "Shell_TrayWnd" | "Shell_SecondaryTrayWnd")).map(|w| (w, rect_of(w))).collect();
    let mut out = Vec::new();
    for (_, sr) in real {
        let here: Vec<&(HWND, RECT)> = trays.iter().filter(|(_, r)| { let (x, y) = center(r); inside(sr, x, y) }).collect();
        if here.len() > 1 {
            out.push(format!("{} taskbars on your screen {}: {:?}", here.len(), fmt(sr), here.iter().map(|(w, r)| format!("{} {}", class_name(*w), fmt(r))).collect::<Vec<_>>()));
        }
    }
    out
}
