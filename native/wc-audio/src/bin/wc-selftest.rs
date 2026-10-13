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
        | WM_CAPTURECHANGED | WM_MOUSEACTIVATE | WM_ACTIVATE | WM_KILLFOCUS | WM_SETFOCUS | WM_CANCELMODE)
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

const EVENTS: [&str; 14] = ["esc", "alt", "look", "zoom", "recenter", "mbtn", "mwheel", "altup", "pointlost", "pointok", "pointfail", "seatmove", "lookmode", "mask"];

struct Helper {
    child: Child,
    stdin: Arc<Mutex<ChildStdin>>,
    answers: Receiver<String>,
    events: Arc<Mutex<Vec<(Instant, String)>>>,
    beating: Arc<AtomicBool>,
}

impl Helper {
    fn start(exe: &std::path::Path) -> Result<Helper, String> {
        let mut child = Command::new(exe)
            .args(["--parent-pid", &std::process::id().to_string()])
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
    // ---- the driver
    match h.call("status", "status", 15000) {
        Ok(l) => {
            let s = l.last().cloned().unwrap_or_default();
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
    sleep(400);
    let (cx, cy) = cursor();
    let ev = h.events_since(t);
    rep.check("from a monitor, the cursor goes on to the monitor joined to it", inside(&rb, cx, cy), format!("cursor {cx},{cy}; B {}", fmt(&rb)));
    rep.check("…and you're sitting at that one now", ev.iter().any(|e| e == &format!("seatmove {}", b.idx)), format!("events {ev:?}"));
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
    for _ in 0..20 {
        move_by(80, 0);
        sleep(20);
    }
    send(&[mouse(MOUSEEVENTF_LEFTUP, 0, 0, 0)]);
    sleep(300);
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
            key(VK_ESCAPE); // (closes the menu — and gets you up)
            sleep(600);
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
    let left = monitors().len();
    rep.check("…leaves no monitor behind", left == real.len() + 1, format!("{left} screens now: yours + B"));
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

fn has_taskbar_on(r: &RECT) -> bool {
    taskbar_on(r).is_some()
}
