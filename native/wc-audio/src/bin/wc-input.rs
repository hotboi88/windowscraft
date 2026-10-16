//! wc-input: delivers mouse and keyboard input to one app window while Minecraft keeps the real
//! focus. The game reads your mouse and keys as usual and hands them to us; we post them to the
//! app as the same window messages Windows would have sent it, at the matching spot.
//!
//! stdin, one command per line (coordinates are 0..1 across the window's client area):
//!   target <hwnd>                   the window to control (also when it changes)
//!   focus | blur                    attached / detached: the app's "active" state and key state
//!   move <x> <y>
//!   button <l|r|m|x1|x2> <down|up> <x> <y>
//!   wheel <dy> <dx> <x> <y>         wheel clicks (fractions allowed; 1 = one notch)
//!   key <down|up> <scancode>        GLFW (= Windows) scancode; 0x100 = extended key
//!   release                         let go of every held key and button
//!   leave                           the pointer left the window (hover ends)
//!   tab hide|show <hwnd>            take a window's button off the Windows taskbar / put it back
//!   text                            (diagnostics) print "text=<...>" of the focused control
//!   slot <n> <command>              any of the above for person n (their own target, keys and
//!                                   buttons, on their own thread); "slot <n> end" lets them go
//!   slot <n> pad <buttons> <lt> <rt> <lx> <ly> <rx> <ry>
//!                                   person n's game controller (XInput's buttons and values): a
//!                                   controller of their own on this PC, through ViGEmBus (if it's
//!                                   installed), for any game that reads controllers — also one
//!                                   that isn't in front. Unplugged when they go; let go of (left
//!                                   plugged in) when nothing comes for 3 s.
//! stderr: "error=..." / "info=...". Exits when stdin closes or the parent exits.

use std::io::{self, BufRead, Write};
use std::time::Instant;
use windows::Win32::Foundation::{HWND, LPARAM, POINT, RECT, WPARAM};
use windows::Win32::Graphics::Gdi::MapWindowPoints;
use windows::Win32::System::Threading::{AttachThreadInput, GetCurrentThreadId};
use windows::Win32::UI::HiDpi::{GetWindowDpiAwarenessContext, SetThreadDpiAwarenessContext};
use windows::Win32::UI::Input::KeyboardAndMouse::*;
use windows::Win32::UI::WindowsAndMessaging::*;

const MK_LBUTTON: usize = 0x1;
const MK_RBUTTON: usize = 0x2;
const MK_SHIFT: usize = 0x4;
const MK_CONTROL: usize = 0x8;
const MK_MBUTTON: usize = 0x10;
const MK_XBUTTON1: usize = 0x20;
const MK_XBUTTON2: usize = 0x40;

struct Input {
    top: HWND,
    thread: u32,
    attached: bool,
    keys: [u8; 256],
    /// Child window that got the last button press: it keeps getting the mouse until all
    /// buttons are up (what SetCapture would do for real input).
    capture: Option<HWND>,
    buttons: usize,
    last_click: Option<(u32, Instant, i32, i32)>,
    held_scancodes: Vec<u32>,
    hovering: bool,
}

/// One person's own Xbox controller on this PC (ViGEmBus), made with their first "pad".
struct Pad {
    target: Option<vigem_client::Xbox360Wired<vigem_client::Client>>,
    unavailable: bool,
    last: Instant,
    held: bool,
}

impl Pad {
    fn new() -> Pad {
        Pad { target: None, unavailable: false, last: Instant::now(), held: false }
    }

    /// "pad <buttons> <lt> <rt> <lx> <ly> <rx> <ry>"
    fn set(&mut self, p: &[&str]) {
        let n = |i: usize| p.get(i).and_then(|s| s.parse::<i32>().ok()).unwrap_or(0);
        let g = vigem_client::XGamepad {
            buttons: vigem_client::XButtons(n(1) as u16),
            left_trigger: n(2).clamp(0, 255) as u8,
            right_trigger: n(3).clamp(0, 255) as u8,
            thumb_lx: n(4).clamp(-32768, 32767) as i16,
            thumb_ly: n(5).clamp(-32768, 32767) as i16,
            thumb_rx: n(6).clamp(-32768, 32767) as i16,
            thumb_ry: n(7).clamp(-32768, 32767) as i16,
        };
        if self.target.is_none() && !self.unavailable {
            match vigem_client::Client::connect() {
                Ok(c) => {
                    let mut t = vigem_client::Xbox360Wired::new(c, vigem_client::TargetId::XBOX360_WIRED);
                    match t.plugin().and_then(|_| t.wait_ready()) {
                        Ok(()) => {
                            eprintln!("info=a game controller of their own (a virtual Xbox controller)");
                            self.target = Some(t);
                        }
                        Err(e) => {
                            eprintln!("info=couldn't add a game controller ({e:?})");
                            self.unavailable = true;
                        }
                    }
                }
                Err(e) => {
                    eprintln!("info=no game controllers for guests: ViGEmBus isn't there ({e:?})");
                    self.unavailable = true;
                }
            }
        }
        if let Some(t) = &mut self.target {
            if let Err(e) = t.update(&g) {
                eprintln!("info=a game controller update didn't go through ({e:?})");
            }
        }
        self.last = Instant::now();
        self.held = g != vigem_client::XGamepad::default();
    }

    /// Nothing from them for 3 s (their connection went): let go of everything, stay plugged in.
    fn tick(&mut self) {
        if self.held && self.last.elapsed() > std::time::Duration::from_secs(3) {
            if let Some(t) = &mut self.target {
                let _ = t.update(&vigem_client::XGamepad::default());
            }
            self.held = false;
        }
    }
}

fn lp(x: i32, y: i32) -> LPARAM {
    LPARAM(((y as u16 as u32) << 16 | (x as u16 as u32)) as isize)
}

impl Input {
    fn new() -> Input {
        Input { top: HWND::default(), thread: 0, attached: false, keys: [0; 256], capture: None, buttons: 0, last_click: None, held_scancodes: Vec::new(), hovering: false }
    }

    fn set_target(&mut self, hwnd: HWND) {
        if self.attached {
            self.blur();
            self.leave();
            self.detach();
        }
        self.top = hwnd;
        unsafe {
            self.thread = GetWindowThreadProcessId(hwnd, None);
            // speak the window's own coordinate language (per-monitor DPI or not)
            let ctx = GetWindowDpiAwarenessContext(hwnd);
            if !ctx.0.is_null() {
                SetThreadDpiAwarenessContext(ctx);
            }
        }
        self.capture = None;
        self.buttons = 0;
        self.attach();
    }

    /// Share the app's input state (key state, focus, mouse capture) with this helper thread.
    fn attach(&mut self) {
        // never the window you're using yourself right now (sharing its key state would mix their
        // keys into yours): theirs then goes as messages only
        if unsafe { GetWindowThreadProcessId(GetForegroundWindow(), None) } == self.thread {
            return;
        }
        if !self.attached && self.valid() {
            self.attached = unsafe { AttachThreadInput(GetCurrentThreadId(), self.thread, true) }.as_bool();
        }
    }

    /// The person at the PC is using this app right now (it's in front): our shared key state
    /// mustn't mix into their typing (a guest's held Shift would be theirs too), so we let go of
    /// it — the guest's input then goes as messages only.
    fn yield_to_owner(&mut self) {
        if self.attached && self.valid() && unsafe { GetWindowThreadProcessId(GetForegroundWindow(), None) } == self.thread {
            // (the keys the guest holds come up in the shared state first: nothing of theirs stays)
            let mut state = [0u8; 256];
            unsafe {
                let _ = GetKeyboardState(&mut state);
                for vk in 0..256 {
                    if self.keys[vk] & 0x80 != 0 {
                        state[vk] &= 0x01;
                    }
                }
                let _ = SetKeyboardState(&state);
            }
            self.detach();
        }
    }

    fn detach(&mut self) {
        if self.attached {
            unsafe {
                let _ = AttachThreadInput(GetCurrentThreadId(), self.thread, false);
            }
            self.attached = false;
        }
    }

    /// Chromium apps (Chrome, Edge, Discord, Spotify…) end a hover as soon as they see the real
    /// mouse isn't over them (it's over Minecraft) — unless the window holds the mouse capture,
    /// the way it does while you drag. So while the pointer hovers the app, it holds the capture
    /// in the app's own (background) input queue. Real mouse input never goes there: the
    /// window is never under the real cursor. Never taken from anything else (e.g. an open menu).
    fn hold_hover(&mut self) {
        if !self.attached || self.buttons != 0 {
            return;
        }
        unsafe {
            if GetCapture().0.is_null() {
                let _ = SetCapture(self.top);
                self.hovering = true;
            }
        }
    }

    fn leave(&mut self) {
        if !self.valid() {
            return;
        }
        unsafe {
            if self.hovering && GetCapture() == self.top {
                let _ = ReleaseCapture();
            }
        }
        self.hovering = false;
        self.post(self.top, 0x02A3 /* WM_MOUSELEAVE */, 0, LPARAM(0));
    }

    fn valid(&self) -> bool {
        !self.top.0.is_null() && unsafe { IsWindow(Some(self.top)) }.as_bool()
    }

    /// Normalized position → (deepest child under it, point in that child's client coordinates).
    fn locate(&self, nx: f64, ny: f64) -> (HWND, i32, i32) {
        let mut rc = RECT::default();
        unsafe {
            let _ = GetClientRect(self.top, &mut rc);
        }
        let w = (rc.right - rc.left).max(1);
        let h = (rc.bottom - rc.top).max(1);
        let px = ((nx.clamp(0.0, 1.0)) * w as f64).floor().min((w - 1) as f64) as i32;
        let py = ((ny.clamp(0.0, 1.0)) * h as f64).floor().min((h - 1) as f64) as i32;
        let target = self.capture.unwrap_or_else(|| self.child_at(px, py));
        let mut pts = [POINT { x: px, y: py }];
        if target != self.top {
            unsafe { MapWindowPoints(Some(self.top), Some(target), &mut pts) };
        }
        (target, pts[0].x, pts[0].y)
    }

    fn child_at(&self, x: i32, y: i32) -> HWND {
        let mut parent = self.top;
        let mut pt = POINT { x, y };
        for _ in 0..32 {
            let child = unsafe { ChildWindowFromPointEx(parent, pt, CWP_SKIPINVISIBLE | CWP_SKIPDISABLED | CWP_SKIPTRANSPARENT) };
            if child.0.is_null() || child == parent {
                return parent;
            }
            let mut p = [pt];
            unsafe { MapWindowPoints(Some(parent), Some(child), &mut p) };
            pt = p[0];
            parent = child;
        }
        parent
    }

    fn modifiers(&self) -> usize {
        let mut m = self.buttons;
        if self.keys[VK_SHIFT.0 as usize] & 0x80 != 0 {
            m |= MK_SHIFT;
        }
        if self.keys[VK_CONTROL.0 as usize] & 0x80 != 0 {
            m |= MK_CONTROL;
        }
        m
    }

    fn post(&self, hwnd: HWND, msg: u32, w: usize, l: LPARAM) {
        unsafe {
            let _ = PostMessageW(Some(hwnd), msg, WPARAM(w), l);
        }
    }

    fn mouse_move(&mut self, nx: f64, ny: f64) {
        self.hold_hover();
        let (h, x, y) = self.locate(nx, ny);
        self.post(h, WM_MOUSEMOVE, self.modifiers(), lp(x, y));
    }

    /// The button as the app sees it when it asks Windows (GetKeyState): held while the guest
    /// holds it. Many apps check that before they take a click or start a drag, and a posted
    /// message alone left it up. Only in the app's own shared input state (attached), never
    /// while you're using that app yourself (that state would be yours).
    fn button_state(&mut self, vk: u16, down: bool) {
        if !self.attached || !self.valid() {
            return;
        }
        let mut state = [0u8; 256];
        unsafe {
            if GetKeyboardState(&mut state).is_err() {
                return;
            }
            if down {
                state[vk as usize] |= 0x80;
            } else {
                state[vk as usize] &= !0x80;
            }
            let _ = SetKeyboardState(&state);
        }
    }

    fn button(&mut self, which: &str, down: bool, nx: f64, ny: f64) {
        let (flag, dn, up, dbl, xbtn, vk) = match which {
            "l" => (MK_LBUTTON, WM_LBUTTONDOWN, WM_LBUTTONUP, WM_LBUTTONDBLCLK, 0, VK_LBUTTON.0),
            "r" => (MK_RBUTTON, WM_RBUTTONDOWN, WM_RBUTTONUP, WM_RBUTTONDBLCLK, 0, VK_RBUTTON.0),
            "m" => (MK_MBUTTON, WM_MBUTTONDOWN, WM_MBUTTONUP, WM_MBUTTONDBLCLK, 0, VK_MBUTTON.0),
            "x1" => (MK_XBUTTON1, WM_XBUTTONDOWN, WM_XBUTTONUP, WM_XBUTTONDBLCLK, 1, VK_XBUTTON1.0),
            "x2" => (MK_XBUTTON2, WM_XBUTTONDOWN, WM_XBUTTONUP, WM_XBUTTONDBLCLK, 2, VK_XBUTTON2.0),
            _ => return,
        };
        let (h, x, y) = self.locate(nx, ny);
        self.button_state(vk, down);
        if down {
            if self.buttons == 0 {
                self.capture = Some(h);
            }
            self.buttons |= flag;
            // a second press soon after, close by, on a window that wants double-clicks
            let now = Instant::now();
            let wants_dbl = unsafe { GetClassLongPtrW(h, GCL_STYLE) } & CS_DBLCLKS.0 as usize != 0;
            let is_dbl = match self.last_click {
                Some((m, t, lx, ly)) => {
                    m == dn
                        && now.duration_since(t).as_millis() <= unsafe { GetDoubleClickTime() } as u128
                        && (lx - x).abs() <= unsafe { GetSystemMetrics(SM_CXDOUBLECLK) } / 2 + 1
                        && (ly - y).abs() <= unsafe { GetSystemMetrics(SM_CYDOUBLECLK) } / 2 + 1
                }
                None => false,
            };
            let msg = if is_dbl && wants_dbl { dbl } else { dn };
            self.last_click = if is_dbl { None } else { Some((dn, now, x, y)) };
            self.post(h, msg, self.modifiers() | (xbtn << 16), lp(x, y));
        } else {
            self.buttons &= !flag;
            self.post(h, up, self.modifiers() | (xbtn << 16), lp(x, y));
            if self.buttons == 0 {
                self.capture = None;
            }
        }
    }

    fn wheel(&mut self, dy: f64, dx: f64, nx: f64, ny: f64) {
        let (h, x, y) = self.locate(nx, ny);
        // wheel messages carry screen coordinates
        let mut pts = [POINT { x, y }];
        unsafe { MapWindowPoints(Some(h), None, &mut pts) };
        let l = lp(pts[0].x, pts[0].y);
        if dy != 0.0 {
            let d = (dy * 120.0).round() as i32 as i16 as u16 as usize;
            self.post(h, WM_MOUSEWHEEL, (d << 16) | self.modifiers(), l);
        }
        if dx != 0.0 {
            let d = (dx * 120.0).round() as i32 as i16 as u16 as usize;
            self.post(h, WM_MOUSEHWHEEL, (d << 16) | self.modifiers(), l);
        }
    }

    /// Where typing goes: the control that has the keyboard focus inside the app.
    fn key_target(&self) -> HWND {
        let mut info = GUITHREADINFO { cbSize: std::mem::size_of::<GUITHREADINFO>() as u32, ..Default::default() };
        if unsafe { GetGUIThreadInfo(self.thread, &mut info) }.is_ok() && !info.hwndFocus.0.is_null() {
            return info.hwndFocus;
        }
        self.capture.unwrap_or(self.top)
    }

    fn key(&mut self, down: bool, glfw_scancode: u32) {
        let sc = glfw_scancode & 0xFF;
        let ext = glfw_scancode & 0x100 != 0;
        let vk = unsafe { MapVirtualKeyW(sc | if ext { 0xE000 } else { 0 }, MAPVK_VSC_TO_VK_EX) };
        if vk == 0 {
            return;
        }
        let was_down = self.keys[vk as usize] & 0x80 != 0;
        self.set_key(vk, down);
        if down && !self.held_scancodes.contains(&glfw_scancode) {
            self.held_scancodes.push(glfw_scancode);
        } else if !down {
            self.held_scancodes.retain(|s| *s != glfw_scancode);
        }
        // left/right modifier keys also count as the generic one
        match vk as u16 {
            v if v == VK_LSHIFT.0 || v == VK_RSHIFT.0 => self.set_key(VK_SHIFT.0 as u32, down),
            v if v == VK_LCONTROL.0 || v == VK_RCONTROL.0 => self.set_key(VK_CONTROL.0 as u32, down),
            v if v == VK_LMENU.0 || v == VK_RMENU.0 => self.set_key(VK_MENU.0 as u32, down),
            _ => {}
        }
        self.sync_key_state();
        // the message carries the generic modifier code, like real input does
        let msg_vk = match vk as u16 {
            v if v == VK_LSHIFT.0 || v == VK_RSHIFT.0 => VK_SHIFT.0 as u32,
            v if v == VK_LCONTROL.0 || v == VK_RCONTROL.0 => VK_CONTROL.0 as u32,
            v if v == VK_LMENU.0 || v == VK_RMENU.0 => VK_MENU.0 as u32,
            _ => vk,
        };
        let alt = self.keys[VK_MENU.0 as usize] & 0x80 != 0;
        let ctrl = self.keys[VK_CONTROL.0 as usize] & 0x80 != 0;
        let sys = alt && !ctrl; // Alt+key (but AltGr = Ctrl+Alt types characters)
        let mut l: u32 = 1 | (sc << 16);
        if ext {
            l |= 1 << 24;
        }
        if alt {
            l |= 1 << 29;
        }
        if down && was_down {
            l |= 1 << 30; // auto-repeat
        }
        if !down {
            l |= (1 << 30) | (1 << 31);
        }
        let msg = match (down, sys || msg_vk == VK_MENU.0 as u32) {
            (true, false) => WM_KEYDOWN,
            (true, true) => WM_SYSKEYDOWN,
            (false, false) => WM_KEYUP,
            (false, true) => WM_SYSKEYUP,
        };
        // the app's own message loop turns key presses into typed characters (TranslateMessage),
        // using the key state we share with it, so layouts, Shift and AltGr come out right
        self.post(self.key_target(), msg, msg_vk as usize, LPARAM(l as i32 as isize));
    }

    fn set_key(&mut self, vk: u32, down: bool) {
        if vk < 256 {
            self.keys[vk as usize] = if down { 0x80 } else { 0 };
        }
    }

    /// While attached our thread shares the app's input state, so Shift/Ctrl/Alt held here are
    /// what the app sees when it checks the keyboard (shortcuts like Ctrl+C then work).
    fn sync_key_state(&self) {
        if !self.attached {
            return;
        }
        let mut state = [0u8; 256];
        unsafe {
            let _ = GetKeyboardState(&mut state);
        }
        for vk in 0..256 {
            state[vk] = (state[vk] & 0x01) | self.keys[vk]; // keep toggle bits (Caps Lock)
        }
        unsafe {
            let _ = SetKeyboardState(&state);
        }
    }

    fn focus(&mut self) {
        if !self.valid() {
            return;
        }
        self.attach();
        if !self.attached {
            eprintln!("info=couldn't share key state with the app; shortcuts with Ctrl/Alt may not work");
        }
        self.post(self.top, WM_ACTIVATE, 1 /* WA_ACTIVE */, LPARAM(0));
        let t = self.key_target();
        self.post(t, WM_SETFOCUS, 0, LPARAM(0));
    }

    fn release(&mut self) {
        for sc in self.held_scancodes.clone() {
            self.key(false, sc);
        }
        for (b, f) in [("l", MK_LBUTTON), ("r", MK_RBUTTON), ("m", MK_MBUTTON), ("x1", MK_XBUTTON1), ("x2", MK_XBUTTON2)] {
            if self.buttons & f != 0 {
                self.button(b, false, 0.5, 0.5);
            }
        }
        self.keys = [0; 256];
        self.sync_key_state();
    }

    fn blur(&mut self) {
        self.release();
        if self.valid() {
            let t = self.key_target();
            self.post(t, WM_KILLFOCUS, 0, LPARAM(0));
            self.post(self.top, WM_ACTIVATE, 0 /* WA_INACTIVE */, LPARAM(0));
        }
    }

    fn text(&self) -> String {
        let t = self.key_target();
        let mut buf = vec![0u16; 4096];
        let n = unsafe { SendMessageW(t, WM_GETTEXT, Some(WPARAM(buf.len())), Some(LPARAM(buf.as_mut_ptr() as isize))) };
        String::from_utf16_lossy(&buf[..(n.0.max(0) as usize).min(buf.len())])
    }
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if let Some(i) = args.iter().position(|a| a == "--parent-pid") {
        if let Some(pid) = args.get(i + 1).and_then(|p| p.parse::<u32>().ok()) {
            std::thread::spawn(move || unsafe {
                use windows::Win32::System::Threading::{OpenProcess, WaitForSingleObject, INFINITE, PROCESS_SYNCHRONIZE};
                if let Ok(h) = OpenProcess(PROCESS_SYNCHRONIZE, false, pid) {
                    WaitForSingleObject(h, INFINITE);
                    std::process::exit(0);
                }
            });
        }
    }
    // a thread only gets an input queue once it touches the window system; sharing input state
    // with an app (AttachThreadInput) needs one on both sides
    unsafe {
        let mut msg = MSG::default();
        let _ = PeekMessageW(&mut msg, None, 0, 0, PM_NOREMOVE);
    }
    let taskbar: Option<windows::Win32::UI::Shell::ITaskbarList> = unsafe {
        use windows::Win32::System::Com::*;
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
        CoCreateInstance(&windows::Win32::UI::Shell::TaskbarList, None, CLSCTX_INPROC_SERVER)
            .ok()
            .and_then(|t: windows::Win32::UI::Shell::ITaskbarList| t.HrInit().ok().map(|_| t))
    };
    let mut input = Input::new();
    // each person using your monitors gets a slot of their own ("slot <n> <command>"): their own
    // target window, held keys and buttons, on a thread of its own (its own input queue), so two
    // people never let go of each other's buttons or type into each other's window
    let mut slots: std::collections::HashMap<u32, std::sync::mpsc::Sender<String>> = std::collections::HashMap::new();
    let stdin = io::stdin();
    for line in stdin.lock().lines() {
        let Ok(line) = line else { break };
        let p: Vec<&str> = line.split_whitespace().collect();
        match p.first().copied() {
            Some("slot") => {
                let Some(n) = p.get(1).and_then(|s| s.parse::<u32>().ok()) else { continue };
                let rest = p[2.min(p.len())..].join(" ");
                if rest == "end" {
                    slots.remove(&n); // (its thread lets go of everything and ends)
                    continue;
                }
                let tx = slots.entry(n).or_insert_with(|| {
                    let (tx, rx) = std::sync::mpsc::channel::<String>();
                    std::thread::spawn(move || {
                        unsafe {
                            let mut msg = MSG::default();
                            let _ = PeekMessageW(&mut msg, None, 0, 0, PM_NOREMOVE);
                        }
                        let mut mine = Input::new();
                        let mut pad = Pad::new();
                        loop {
                            match rx.recv_timeout(std::time::Duration::from_millis(50)) {
                                Ok(l) => {
                                    let q: Vec<&str> = l.split_whitespace().collect();
                                    if q.first() == Some(&"pad") {
                                        pad.set(&q); // (no window needed: the controller is the whole PC's)
                                    } else {
                                        handle(&mut mine, &q);
                                    }
                                }
                                // (nothing from them: still, the moment you use that app yourself, let go)
                                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => mine.yield_to_owner(),
                                Err(_) => break,
                            }
                            pad.tick();
                        }
                        drop(pad); // (unplugged)
                        mine.blur();
                        mine.leave();
                        mine.detach();
                    });
                    tx
                });
                let _ = tx.send(rest);
            }
            Some("find") => {
                // (diagnostics) find a top-level window by class name
                let class: Vec<u16> = p.get(1).unwrap_or(&"").encode_utf16().chain(std::iter::once(0)).collect();
                let h = unsafe { FindWindowW(windows::core::PCWSTR(class.as_ptr()), windows::core::PCWSTR::null()) }.unwrap_or_default();
                println!("hwnd={}", h.0 as usize);
                let _ = io::stdout().flush();
            }
            Some("tab") => {
                let h = p.get(2).and_then(|s| s.parse::<u64>().ok()).unwrap_or(0);
                if let Some(t) = &taskbar {
                    let w = HWND(h as usize as *mut _);
                    unsafe {
                        let _ = if p.get(1) == Some(&"hide") { t.DeleteTab(w) } else { t.AddTab(w) };
                    }
                }
            }
            _ => handle(&mut input, &p),
        }
    }
    drop(slots);
    input.blur();
    input.leave();
    input.detach();
    std::thread::sleep(std::time::Duration::from_millis(100)); // (the slots let go too)
}

/// One command for one person's input.
fn handle(input: &mut Input, p: &[&str]) {
    let f = |i: usize| p.get(i).and_then(|s| s.parse::<f64>().ok()).unwrap_or(0.0);
    input.yield_to_owner();
    match p.first().copied() {
        Some("target") => {
            let h = p.get(1).and_then(|s| s.parse::<u64>().ok()).unwrap_or(0);
            input.set_target(HWND(h as usize as *mut _));
            if !input.valid() {
                eprintln!("error=no such window {h}");
            }
        }
        _ if !input.valid() => {}
        Some("focus") => input.focus(),
        Some("blur") => input.blur(),
        Some("move") => input.mouse_move(f(1), f(2)),
        Some("button") => input.button(p.get(1).copied().unwrap_or(""), p.get(2) == Some(&"down"), f(3), f(4)),
        Some("wheel") => input.wheel(f(1), f(2), f(3), f(4)),
        Some("key") => {
            let sc = p.get(2).and_then(|s| s.parse::<u32>().ok()).unwrap_or(0);
            input.key(p.get(1) == Some(&"down"), sc);
        }
        Some("release") => input.release(),
        Some("leave") => input.leave(),
        Some("text") => {
            println!("text={}", input.text().replace('\n', "\\n").replace('\r', ""));
            let _ = io::stdout().flush();
        }
        _ => {}
    }
}
