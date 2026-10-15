//! A stand-in game: a window that reads game controllers one way (XInput or
//! Windows.Gaming.Input) and prints each A press it sees, with the time (QPC ns) and
//! whether its window was the one in front.
use std::io::Write;
use windows::core::{w, PCWSTR};
use windows::Win32::Foundation::*;
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};
use windows::Win32::UI::WindowsAndMessaging::*;

unsafe extern "system" fn proc(h: HWND, m: u32, w: WPARAM, l: LPARAM) -> LRESULT {
    if m == WM_DESTROY {
        PostQuitMessage(0);
        return LRESULT(0);
    }
    DefWindowProcW(h, m, w, l)
}

fn ns() -> i64 {
    unsafe {
        let (mut c, mut f) = (0i64, 0i64);
        let _ = QueryPerformanceCounter(&mut c);
        let _ = QueryPerformanceFrequency(&mut f);
        (c as i128 * 1_000_000_000 / f as i128) as i64
    }
}

fn main() -> windows::core::Result<()> {
    let a: Vec<String> = std::env::args().collect();
    let mode = a.get(1).cloned().unwrap_or("xinput".into());
    let title: Vec<u16> = a.get(2).cloned().unwrap_or("padgame".into()).encode_utf16().chain(Some(0)).collect();
    let x: i32 = a.get(3).and_then(|v| v.parse().ok()).unwrap_or(100);
    let y: i32 = a.get(4).and_then(|v| v.parse().ok()).unwrap_or(100);
    unsafe {
        if mode == "wgi" {
            let _ = windows::Win32::System::WinRT::RoInitialize(windows::Win32::System::WinRT::RO_INIT_MULTITHREADED);
        }
        let inst = GetModuleHandleW(None)?;
        let wc = WNDCLASSW { lpfnWndProc: Some(proc), hInstance: inst.into(), lpszClassName: w!("PadGame"), hbrBackground: windows::Win32::Graphics::Gdi::HBRUSH(6 as _), ..Default::default() };
        RegisterClassW(&wc);
        let h = CreateWindowExW(WINDOW_EX_STYLE(0), w!("PadGame"), PCWSTR(title.as_ptr()), WS_OVERLAPPEDWINDOW | WS_VISIBLE, x, y, 360, 220, None, None, Some(inst.into()), None)?;
        println!("ready {} hwnd {}", mode, h.0 as usize);
        let _ = std::io::stdout().flush();
        let mut held = false;
        let mut pads: Vec<windows::Gaming::Input::Gamepad> = Vec::new();
        let mut listed = std::time::Instant::now() - std::time::Duration::from_secs(10);
        loop {
            let mut msg = MSG::default();
            while PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).as_bool() {
                if msg.message == WM_QUIT {
                    return Ok(());
                }
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
            let a_down = if mode == "front" {
                false
            } else if mode == "wgi" {
                if listed.elapsed().as_millis() > 500 {
                    listed = std::time::Instant::now();
                    pads.clear();
                    if let Ok(v) = windows::Gaming::Input::Gamepad::Gamepads() {
                        for p in v {
                            pads.push(p);
                        }
                    }
                }
                pads.iter().any(|p| p.GetCurrentReading().map_or(false, |r| r.Buttons.contains(windows::Gaming::Input::GamepadButtons::A)))
            } else {
                use windows::Win32::UI::Input::XboxController::*;
                (0..4).any(|i| {
                    let mut s = XINPUT_STATE::default();
                    XInputGetState(i, &mut s) == 0 && (s.Gamepad.wButtons.0 & 0x1000) != 0
                })
            };
            if a_down != held {
                held = a_down;
                println!("A {} {} fg={}", if held { "down" } else { "up" }, ns(), (GetForegroundWindow() == h) as u8);
                let _ = std::io::stdout().flush();
            }
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
    }
}
