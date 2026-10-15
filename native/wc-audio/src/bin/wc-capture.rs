//! wc-capture: direct GPU sharing for WindowsCraft.
//!
//! Captures one window or monitor with Windows Graphics Capture and copies every frame, on the
//! GPU, into one of three D3D11 textures that Minecraft opens itself (shared handles) and maps
//! into OpenGL with WGL_NV_DX_interop. The picture never leaves graphics memory.
//!
//!   wc-capture (--hwnd <n> | --hmonitor <n>) [--fps <n>] [--cursor 0|1]
//!              [--adapter-luid <low>:<high>] [--parent-pid <pid>]
//!
//! stdout (one line each):
//!   slots <w> <h> <handle0> <handle1> <handle2>   (again whenever the size changes)
//!   frame <slot>                                  (that slot now holds the newest picture)
//!   gone                                          (the window was closed)
//! stderr: "error=..." / "info=..." for the log. Exits when stdin closes or the parent exits.
//!
//!
//!   --cpu <name>: instead of shared textures, copy each new picture into shared memory that
//!   Minecraft reads (for PCs where OpenGL can't open Direct3D textures). Frames only arrive when
//!   the window actually changes, so a still window costs nothing.
//!     stdout: cpuslots <w> <h> <mapping name> <full w> <full h>   (3 slots of w*h*4 bytes after
//!             a 64-byte header; header = one u64 sequence number per slot, written after the
//!             slot's pixels; the full size is the picture's before any "shrink")
//!             frame <slot>
//!   --box full|client: the whole window as you see it on the desktop (title bar too), or only
//!   the app's content (default).
//!   stdin lines: "fps <n>" changes the frame rate on the fly; "shrink <n>" (--cpu only) halves
//!   the picture n times (0-3) on the graphics card before it's read back — a wall that looks
//!   small on the player's screen doesn't need every pixel copied (fewer halvings come at once,
//!   from the last picture, even while the window doesn't change); "timing 1" (measurements only)
//!   adds when each picture was captured to its "frame" line: "frame <slot> <time>", in 100 ns
//!   units of the system's performance counter (Java's System.nanoTime() / 100 on Windows).
//!
//!   --ext: the pictures are shared as NT handles, copied straight into Minecraft's process
//!   (OpenGL imports them as external memory, GL_EXT_memory_object_win32), and each "frame" line
//!   is only written once the graphics card has finished copying that picture:
//!     stdout: ntslots <w> <h> <handle0> <handle1> <handle2>   (handles valid in the parent process)
//!     stdin:  free <slot> <n>   the game is done with that picture of the n-th "ntslots" (1 = the
//!             first); one of pictures made anew since is ignored
//!
//!   wc-capture --selftest [--adapter-luid <low>:<high>]
//!     tries Direct3D↔OpenGL sharing in this process first (see gpu_selftest.rs)

#[path = "../gpu_selftest.rs"]
mod gpu_selftest;

use std::io::{self, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};
use windows::core::{Interface, IInspectable};
use windows::Foundation::TypedEventHandler;
use windows::Graphics::Capture::{Direct3D11CaptureFramePool, GraphicsCaptureItem};
use windows::Graphics::DirectX::Direct3D11::IDirect3DDevice;
use windows::Graphics::DirectX::DirectXPixelFormat;
use windows::Graphics::SizeInt32;
use windows::Win32::Foundation::{HMODULE, HWND};
use windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_UNKNOWN;
use windows::Win32::Graphics::Direct3D::D3D_DRIVER_TYPE_HARDWARE;
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::Common::{DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC};
use windows::Win32::Graphics::Dxgi::{CreateDXGIFactory1, IDXGIAdapter1, IDXGIDevice, IDXGIFactory1, IDXGIResource};
use windows::Win32::Graphics::Gdi::HMONITOR;
use windows::Win32::System::WinRT::Direct3D11::{CreateDirect3D11DeviceFromDXGIDevice, IDirect3DDxgiInterfaceAccess};
use windows::Win32::System::WinRT::Graphics::Capture::IGraphicsCaptureItemInterop;
use windows::Win32::System::WinRT::{RoInitialize, RO_INIT_MULTITHREADED};

const SLOTS: usize = 3;

struct Args {
    hwnd: Option<u64>,
    hmonitor: Option<u64>,
    fps: u32,
    cursor: bool,
    luid: Option<(u32, i32)>,
    parent: Option<u32>,
    selftest: bool,
    cpu: Option<String>,
    full_box: bool,
    ext: bool,
    hires: bool,
}

#[link(name = "winmm")]
extern "system" {
    fn timeBeginPeriod(period: u32) -> u32;
}

fn parse() -> Result<Args, String> {
    let v: Vec<String> = std::env::args().skip(1).collect();
    let mut a = Args { hwnd: None, hmonitor: None, fps: 60, cursor: true, luid: None, parent: None, selftest: false, cpu: None, full_box: false, ext: false, hires: false };
    let mut i = 0;
    while i < v.len() {
        if v[i] == "--selftest" {
            a.selftest = true;
            i += 1;
            continue;
        }
        if v[i] == "--ext" {
            a.ext = true;
            i += 1;
            continue;
        }
        // Step 0 experiment (dev only): 1 ms timer resolution for this helper, so its short waits
        // for the next picture really are short (Windows' default is 15.6 ms)
        if v[i] == "--hires" {
            a.hires = true;
            i += 1;
            continue;
        }
        let val = v.get(i + 1).cloned().ok_or(format!("{} needs a value", v[i]))?;
        match v[i].as_str() {
            "--hwnd" => a.hwnd = Some(val.parse().map_err(|_| "bad hwnd")?),
            "--hmonitor" => a.hmonitor = Some(val.parse().map_err(|_| "bad hmonitor")?),
            "--fps" => a.fps = val.parse().map_err(|_| "bad fps")?,
            "--cursor" => a.cursor = val != "0",
            "--parent-pid" => a.parent = Some(val.parse().map_err(|_| "bad pid")?),
            "--cpu" => a.cpu = Some(val),
            "--box" => a.full_box = val == "full",
            "--adapter-luid" => {
                let (l, h) = val.split_once(':').ok_or("bad luid")?;
                a.luid = Some((l.parse().map_err(|_| "bad luid")?, h.parse().map_err(|_| "bad luid")?));
            }
            other => return Err(format!("unknown argument {other}")),
        }
        i += 2;
    }
    if a.hwnd.is_none() && a.hmonitor.is_none() && !a.selftest {
        return Err("usage: wc-capture --hwnd <n> | --hmonitor <n> [--fps n] [--cursor 0|1] [--adapter-luid l:h] [--parent-pid p]".into());
    }
    Ok(a)
}

/// The same graphics card Minecraft draws with (it tells us which by LUID), else the default one.
fn create_device(luid: Option<(u32, i32)>) -> windows::core::Result<(ID3D11Device, ID3D11DeviceContext)> {
    let mut adapter: Option<IDXGIAdapter1> = None;
    if let Some((low, high)) = luid {
        let factory: IDXGIFactory1 = unsafe { CreateDXGIFactory1()? };
        let mut i = 0;
        while let Ok(a) = unsafe { factory.EnumAdapters1(i) } {
            let d = unsafe { a.GetDesc1()? };
            if d.AdapterLuid.LowPart == low && d.AdapterLuid.HighPart == high {
                adapter = Some(a);
                break;
            }
            i += 1;
        }
        if adapter.is_none() {
            eprintln!("info=adapter {low}:{high} not found, using the default graphics card");
        }
    }
    let mut device = None;
    let mut ctx = None;
    let base: Option<windows::Win32::Graphics::Dxgi::IDXGIAdapter> = match &adapter {
        Some(a) => Some(a.cast()?),
        None => None,
    };
    unsafe {
        D3D11CreateDevice(
            base.as_ref(),
            if adapter.is_some() { D3D_DRIVER_TYPE_UNKNOWN } else { D3D_DRIVER_TYPE_HARDWARE },
            HMODULE::default(),
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            None,
            D3D11_SDK_VERSION,
            Some(&mut device),
            None,
            Some(&mut ctx),
        )?;
    }
    Ok((device.unwrap(), ctx.unwrap()))
}

/// Three shared pictures, each first filled with opaque black: the game can tell memory it really
/// reads (opaque) from memory it took at a wrong size (it reads nothing but zeros) — even where
/// OpenGL reports no errors at all (Sodium's "no error" context).
fn make_slots(device: &ID3D11Device, ctx: &ID3D11DeviceContext, w: i32, h: i32, nt_target: Option<windows::Win32::Foundation::HANDLE>) -> windows::core::Result<(Vec<ID3D11Texture2D>, Vec<u64>)> {
    let desc = D3D11_TEXTURE2D_DESC {
        Width: w as u32,
        Height: h as u32,
        MipLevels: 1,
        ArraySize: 1,
        Format: DXGI_FORMAT_B8G8R8A8_UNORM,
        SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
        Usage: D3D11_USAGE_DEFAULT,
        BindFlags: (D3D11_BIND_SHADER_RESOURCE.0 | D3D11_BIND_RENDER_TARGET.0) as u32,
        CPUAccessFlags: 0,
        MiscFlags: if nt_target.is_some() {
            (D3D11_RESOURCE_MISC_SHARED.0 | D3D11_RESOURCE_MISC_SHARED_NTHANDLE.0) as u32
        } else {
            D3D11_RESOURCE_MISC_SHARED.0 as u32
        },
    };
    let mut textures = Vec::new();
    let mut handles = Vec::new();
    for _ in 0..SLOTS {
        let mut t = None;
        unsafe { device.CreateTexture2D(&desc, None, Some(&mut t))? };
        let t = t.unwrap();
        if let Some(parent) = nt_target {
            use windows::Win32::Foundation::{CloseHandle, DuplicateHandle, DUPLICATE_SAME_ACCESS, HANDLE};
            use windows::Win32::Graphics::Dxgi::{IDXGIResource1, DXGI_SHARED_RESOURCE_READ, DXGI_SHARED_RESOURCE_WRITE};
            use windows::Win32::System::Threading::GetCurrentProcess;
            let mine = unsafe {
                t.cast::<IDXGIResource1>()?.CreateSharedHandle(None, (DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE).0, windows::core::PCWSTR::null())?
            };
            let mut theirs = HANDLE::default();
            let dup = unsafe { DuplicateHandle(GetCurrentProcess(), mine, parent, &mut theirs, 0, false, DUPLICATE_SAME_ACCESS) };
            unsafe {
                let _ = CloseHandle(mine);
            }
            dup?;
            handles.push(theirs.0 as usize as u64);
        } else {
            let h = unsafe { t.cast::<IDXGIResource>()?.GetSharedHandle()? };
            handles.push(h.0 as usize as u64);
        }
        let mut rtv = None;
        unsafe { device.CreateRenderTargetView(&t, None, Some(&mut rtv))? };
        if let Some(rtv) = rtv {
            unsafe { ctx.ClearRenderTargetView(&rtv, &[0.0, 0.0, 0.0, 1.0]) };
        }
        textures.push(t);
    }
    let mut q = None;
    gpu_selftest::wait_idle(device, ctx, &mut q); // (filled before anyone is told about them)
    Ok((textures, handles))
}

/// The part of the captured window picture that is the app's content (its client area), so a
/// spot on the wall maps to the same spot in the app; the whole picture for monitors.
fn client_box(hwnd: Option<HWND>, content: SizeInt32) -> (u32, u32, u32, u32) {
    let full = (0, 0, content.Width as u32, content.Height as u32);
    let Some(h) = hwnd else { return full };
    unsafe {
        use windows::Win32::Foundation::{POINT, RECT};
        use windows::Win32::Graphics::Dwm::{DwmGetWindowAttribute, DWMWA_EXTENDED_FRAME_BOUNDS};
        use windows::Win32::Graphics::Gdi::ClientToScreen;
        use windows::Win32::UI::WindowsAndMessaging::GetClientRect;
        let mut frame = RECT::default();
        if DwmGetWindowAttribute(h, DWMWA_EXTENDED_FRAME_BOUNDS, &mut frame as *mut _ as *mut _, std::mem::size_of::<RECT>() as u32).is_err() {
            return full;
        }
        let mut cr = RECT::default();
        if GetClientRect(h, &mut cr).is_err() {
            return full;
        }
        let mut origin = POINT { x: 0, y: 0 };
        if !ClientToScreen(h, &mut origin).as_bool() {
            return full;
        }
        let left = (origin.x - frame.left).clamp(0, content.Width);
        let top = (origin.y - frame.top).clamp(0, content.Height);
        let right = (left + cr.right - cr.left).clamp(left, content.Width);
        let bottom = (top + cr.bottom - cr.top).clamp(top, content.Height);
        if right - left < 16 || bottom - top < 16 {
            return full; // minimized or odd: show everything
        }
        (left as u32, top as u32, right as u32, bottom as u32)
    }
}

/// Shared memory with three picture slots, plus a staging texture to read the GPU picture back.
struct CpuSlots {
    name: String,
    mapping: windows::Win32::Foundation::HANDLE,
    view: windows::Win32::System::Memory::MEMORY_MAPPED_VIEW_ADDRESS,
    staging: ID3D11Texture2D,
    w: usize,
    h: usize,
    seq: u64,
}

const HEADER: usize = 64;

impl CpuSlots {
    fn new(device: &ID3D11Device, prefix: &str, generation: u32, w: i32, h: i32) -> windows::core::Result<CpuSlots> {
        use windows::Win32::System::Memory::*;
        let (w, h) = (w.max(1) as usize, h.max(1) as usize);
        let bytes = HEADER + SLOTS * w * h * 4;
        let name = format!("{prefix}-{generation}");
        let wide: Vec<u16> = name.encode_utf16().chain(std::iter::once(0)).collect();
        let mapping = unsafe {
            CreateFileMappingW(
                windows::Win32::Foundation::INVALID_HANDLE_VALUE,
                None,
                PAGE_READWRITE,
                (bytes as u64 >> 32) as u32,
                bytes as u32,
                windows::core::PCWSTR(wide.as_ptr()),
            )?
        };
        let view = unsafe { MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, bytes) };
        if view.Value.is_null() {
            return Err(windows::core::Error::from_thread());
        }
        let desc = D3D11_TEXTURE2D_DESC {
            Width: w as u32,
            Height: h as u32,
            MipLevels: 1,
            ArraySize: 1,
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_STAGING,
            BindFlags: 0,
            CPUAccessFlags: D3D11_CPU_ACCESS_READ.0 as u32,
            MiscFlags: 0,
        };
        let mut t = None;
        unsafe { device.CreateTexture2D(&desc, None, Some(&mut t))? };
        Ok(CpuSlots { name, mapping, view, staging: t.unwrap(), w, h, seq: 0 })
    }

    fn copy(&mut self, ctx: &ID3D11DeviceContext, src: &ID3D11Texture2D, sub: u32, region: Option<&D3D11_BOX>, slot: usize) -> windows::core::Result<()> {
        unsafe {
            ctx.CopySubresourceRegion(&self.staging, 0, 0, 0, 0, src, sub, region.map(|r| r as *const D3D11_BOX));
            let mut m = D3D11_MAPPED_SUBRESOURCE::default();
            ctx.Map(&self.staging, 0, D3D11_MAP_READ, 0, Some(&mut m))?;
            let base = self.view.Value as *mut u8;
            let row = self.w * 4;
            let dst = base.add(HEADER + slot * row * self.h);
            let seq_ptr = (base as *mut u64).add(slot);
            std::ptr::write_volatile(seq_ptr, 0); // being written
            for y in 0..self.h {
                std::ptr::copy_nonoverlapping((m.pData as *const u8).add(y * m.RowPitch as usize), dst.add(y * row), row);
            }
            ctx.Unmap(&self.staging, 0);
            self.seq += 1;
            std::sync::atomic::fence(Ordering::SeqCst);
            std::ptr::write_volatile(seq_ptr, self.seq);
        }
        Ok(())
    }
}

/// "shrink": the picture's own copy with smaller versions of itself (mip levels), made by the
/// graphics card in a fraction of a millisecond; level n is the picture halved n times.
struct Shrinker {
    tex: ID3D11Texture2D,
    srv: ID3D11ShaderResourceView,
    w: u32,
    h: u32,
    levels: u32,
}

const MAX_SHRINK: u32 = 3;

impl Shrinker {
    fn new(device: &ID3D11Device, w: u32, h: u32, levels: u32) -> windows::core::Result<Shrinker> {
        let desc = D3D11_TEXTURE2D_DESC {
            Width: w,
            Height: h,
            MipLevels: levels + 1,
            ArraySize: 1,
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            Usage: D3D11_USAGE_DEFAULT,
            BindFlags: (D3D11_BIND_SHADER_RESOURCE.0 | D3D11_BIND_RENDER_TARGET.0) as u32,
            CPUAccessFlags: 0,
            MiscFlags: D3D11_RESOURCE_MISC_GENERATE_MIPS.0 as u32,
        };
        let mut t = None;
        unsafe { device.CreateTexture2D(&desc, None, Some(&mut t))? };
        let tex = t.unwrap();
        let mut v = None;
        unsafe { device.CreateShaderResourceView(&tex, None, Some(&mut v))? };
        Ok(Shrinker { tex, srv: v.unwrap(), w, h, levels })
    }
}

/// How many halvings are possible for a picture this size (each side stays at least 16 pixels).
fn shrink_for(w: u32, h: u32, wanted: u32) -> u32 {
    let mut n = wanted.min(MAX_SHRINK);
    while n > 0 && ((w >> n) < 16 || (h >> n) < 16) {
        n -= 1;
    }
    n
}

impl Drop for CpuSlots {
    fn drop(&mut self) {
        unsafe {
            let _ = windows::Win32::System::Memory::UnmapViewOfFile(self.view);
            let _ = windows::Win32::Foundation::CloseHandle(self.mapping);
        }
    }
}

fn say(out: &mut impl Write, line: &str) -> bool {
    out.write_all(line.as_bytes()).and_then(|_| out.write_all(b"\n")).and_then(|_| out.flush()).is_ok()
}

fn run(a: Args) -> windows::core::Result<()> {
    unsafe { RoInitialize(RO_INIT_MULTITHREADED)? };
    // real pixels everywhere (the window's frame and client area are compared below)
    unsafe {
        use windows::Win32::UI::HiDpi::{SetProcessDpiAwarenessContext, DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2};
        let _ = SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    }
    let window = a.hwnd.map(|h| HWND(h as usize as *mut _));
    let (device, ctx) = create_device(a.luid)?;
    let dxgi: IDXGIDevice = device.cast()?;
    let winrt_device: IDirect3DDevice = unsafe { CreateDirect3D11DeviceFromDXGIDevice(&dxgi)? }.cast()?;

    let interop = windows::core::factory::<GraphicsCaptureItem, IGraphicsCaptureItemInterop>()?;
    let item: GraphicsCaptureItem = unsafe {
        match (a.hwnd, a.hmonitor) {
            (Some(h), _) => interop.CreateForWindow(HWND(h as usize as *mut _))?,
            (_, Some(m)) => interop.CreateForMonitor(HMONITOR(m as usize as *mut _))?,
            _ => unreachable!(),
        }
    };
    let gone = Arc::new(AtomicBool::new(false));
    {
        let gone = gone.clone();
        item.Closed(&TypedEventHandler::<GraphicsCaptureItem, IInspectable>::new(move |_, _| {
            gone.store(true, Ordering::SeqCst);
            Ok(())
        }))?;
    }
    let mut size = item.Size()?;
    let fmt = DirectXPixelFormat::B8G8R8A8UIntNormalized;
    let pool = Direct3D11CaptureFramePool::CreateFreeThreaded(&winrt_device, fmt, 2, size)?;
    let session = pool.CreateCaptureSession(&item)?;
    let _ = session.SetIsCursorCaptureEnabled(a.cursor);
    let _ = session.SetIsBorderRequired(false); // Windows 11: no yellow frame (Windows 10 always shows it)
    session.StartCapture()?;

    let stop = Arc::new(AtomicBool::new(false));
    let fps_now = Arc::new(std::sync::atomic::AtomicU32::new(a.fps.max(1)));
    let timing = Arc::new(AtomicBool::new(false));
    let shrink = Arc::new(std::sync::atomic::AtomicU32::new(0));
    // --ext: a picture the game may still be copying is never written over; the game says
    // "free <slot>" once it's done with one (until then the slot is skipped, or the frame dropped).
    // New pictures start as the game's: it first checks they hold the opaque fill (memory taken
    // at a wrong size reads zeros), then frees them — a captured frame copied in sooner could be
    // see-through (a window partly off the screen) and fail that check
    let free: Arc<[AtomicBool; SLOTS]> = Arc::new([AtomicBool::new(!a.ext), AtomicBool::new(!a.ext), AtomicBool::new(!a.ext)]);
    // which "ntslots" the pictures are (1 = the first): a "free" still on its way for pictures made
    // anew since must not hand the game's unchecked new picture back
    let slot_gen = Arc::new(std::sync::Mutex::new(1u32));
    {
        let stop = stop.clone();
        let fps_now = fps_now.clone();
        let free = free.clone();
        let slot_gen = slot_gen.clone();
        let timing = timing.clone();
        let shrink = shrink.clone();
        std::thread::spawn(move || {
            use std::io::BufRead;
            let stdin = io::stdin();
            for line in stdin.lock().lines() {
                let Ok(line) = line else { break };
                if let Some(v) = line.trim().strip_prefix("free ") {
                    let mut it = v.split_whitespace();
                    if let Some(Ok(n)) = it.next().map(|w| w.parse::<usize>()) {
                        let of = it.next().and_then(|w| w.parse::<u32>().ok());
                        let now = slot_gen.lock().unwrap_or_else(|e| e.into_inner());
                        if n < SLOTS && of.map_or(true, |g| g == *now) {
                            free[n].store(true, Ordering::SeqCst);
                        }
                    }
                } else if let Some(v) = line.trim().strip_prefix("fps ") {
                    if let Ok(n) = v.trim().parse::<u32>() {
                        fps_now.store(n.clamp(1, 240), Ordering::SeqCst);
                    }
                } else if let Some(v) = line.trim().strip_prefix("shrink ") {
                    if let Ok(n) = v.trim().parse::<u32>() {
                        shrink.store(n.min(MAX_SHRINK), Ordering::SeqCst);
                    }
                } else if let Some(v) = line.trim().strip_prefix("timing ") {
                    timing.store(v.trim() == "1", Ordering::SeqCst);
                }
            }
            stop.store(true, Ordering::SeqCst);
        });
    }
    if let Some(ppid) = a.parent {
        let stop = stop.clone();
        std::thread::spawn(move || unsafe {
            use windows::Win32::System::Threading::{OpenProcess, WaitForSingleObject, INFINITE, PROCESS_SYNCHRONIZE};
            if let Ok(h) = OpenProcess(PROCESS_SYNCHRONIZE, false, ppid) {
                WaitForSingleObject(h, INFINITE);
                stop.store(true, Ordering::SeqCst);
            }
        });
    }

    let stdout = io::stdout();
    let mut out = stdout.lock();
    // --ext: the shared pictures' handles go straight into Minecraft's process
    let nt_target = if a.ext {
        use windows::Win32::System::Threading::{OpenProcess, PROCESS_DUP_HANDLE};
        let Some(ppid) = a.parent else {
            eprintln!("error=--ext needs --parent-pid");
            return Ok(());
        };
        match unsafe { OpenProcess(PROCESS_DUP_HANDLE, false, ppid) } {
            Ok(h) => Some(h),
            Err(e) => {
                eprintln!("error=can't hand pictures to the game ({e})");
                return Ok(());
            }
        }
    } else {
        None
    };
    let slots_word = if a.ext { "ntslots" } else { "slots" };
    let mut query: Option<ID3D11Query> = None;
    let pick_box = |size: SizeInt32| if a.full_box { (0, 0, size.Width as u32, size.Height as u32) } else { client_box(window, size) };
    let first = pick_box(size);
    let mut slot_size = SizeInt32 { Width: (first.2 - first.0) as i32, Height: (first.3 - first.1) as i32 };
    let mut slots = Vec::new();
    let mut cpu: Option<CpuSlots> = None;
    if let Some(name) = &a.cpu {
        let c = CpuSlots::new(&device, name, 0, slot_size.Width, slot_size.Height)?;
        if !say(&mut out, &format!("cpuslots {} {} {} {} {}", slot_size.Width, slot_size.Height, c.name, slot_size.Width, slot_size.Height)) {
            return Ok(());
        }
        cpu = Some(c);
    } else {
        let (s, handles) = make_slots(&device, &ctx, slot_size.Width, slot_size.Height, nt_target)?;
        slots = s;
        if !say(&mut out, &format!("{slots_word} {} {} {} {} {}", slot_size.Width, slot_size.Height, handles[0], handles[1], handles[2])) {
            return Ok(());
        }
    }
    let mut last = Instant::now() - Duration::from_secs(1);
    let mut next_slot = 0usize;
    let mut generation = 0u32;
    let mut shrinker: Option<Shrinker> = None;
    let mut shrink_broken = false;
    let mut slot_level = 0u32; // the halvings of the picture now in the slots
    while !stop.load(Ordering::SeqCst) {
        if gone.load(Ordering::SeqCst) {
            say(&mut out, "gone");
            break;
        }
        let mut frame = match pool.TryGetNextFrame() {
            Ok(f) => f,
            Err(_) => {
                // a still picture (no new frames come) copied smaller than now wanted — the player
                // came closer: the sharper copy at once, from the last picture kept on the card
                if let (Some(c), Some(sh)) = (&mut cpu, &shrinker) {
                    let want = shrink_for(sh.w, sh.h, shrink.load(Ordering::SeqCst));
                    if want < slot_level {
                        let size = SizeInt32 { Width: (sh.w >> want).max(1) as i32, Height: (sh.h >> want).max(1) as i32 };
                        generation += 1;
                        *c = CpuSlots::new(&device, a.cpu.as_ref().unwrap(), generation, size.Width, size.Height)?;
                        if !say(&mut out, &format!("cpuslots {} {} {} {} {}", size.Width, size.Height, c.name, sh.w, sh.h)) {
                            break;
                        }
                        slot_size = size;
                        slot_level = want;
                        match c.copy(&ctx, &sh.tex, want, None, next_slot) {
                            Ok(()) => {
                                if !say(&mut out, &format!("frame {next_slot}")) {
                                    break;
                                }
                                next_slot = (next_slot + 1) % SLOTS;
                            }
                            Err(e) => eprintln!("info=read-back failed: {e}"),
                        }
                        continue;
                    }
                }
                std::thread::sleep(Duration::from_millis(2));
                continue;
            }
        };
        let interval = Duration::from_secs_f64(1.0 / fps_now.load(Ordering::SeqCst) as f64);
        let since = last.elapsed();
        if since < interval {
            // faster than asked: wait, then take the newest picture (the last change is never lost)
            std::thread::sleep(interval - since);
            while let Ok(newer) = pool.TryGetNextFrame() {
                frame = newer;
            }
        }
        let content = frame.ContentSize()?;
        if content.Width <= 0 || content.Height <= 0 {
            continue;
        }
        if content.Width != size.Width || content.Height != size.Height {
            // resized: new buffers at the new size (from the next frame on)
            size = SizeInt32 { Width: content.Width, Height: content.Height };
            pool.Recreate(&winrt_device, fmt, 2, size)?;
            continue;
        }
        last = Instant::now();
        let (bl, bt, br, bb) = pick_box(size);
        let (fw, fh) = (br - bl, bb - bt);
        let level = if cpu.is_some() && !shrink_broken { shrink_for(fw, fh, shrink.load(Ordering::SeqCst)) } else { 0 };
        let want = SizeInt32 { Width: (fw >> level).max(1) as i32, Height: (fh >> level).max(1) as i32 };
        if slot_size.Width != want.Width || slot_size.Height != want.Height {
            slot_size = want;
            if let Some(c) = &mut cpu {
                generation += 1;
                *c = CpuSlots::new(&device, a.cpu.as_ref().unwrap(), generation, want.Width, want.Height)?;
                if !say(&mut out, &format!("cpuslots {} {} {} {} {}", want.Width, want.Height, c.name, fw, fh)) {
                    break;
                }
            } else {
                let (s, h) = make_slots(&device, &ctx, want.Width, want.Height, nt_target)?;
                slots = s;
                {
                    let mut g = slot_gen.lock().unwrap_or_else(|e| e.into_inner());
                    *g += 1;
                    for f in free.iter() {
                        f.store(!a.ext, Ordering::SeqCst); // (--ext: the game's until it has checked them)
                    }
                }
                if !say(&mut out, &format!("{slots_word} {} {} {} {} {}", want.Width, want.Height, h[0], h[1], h[2])) {
                    break;
                }
            }
        }
        if a.ext {
            // a slot the game isn't using; none free = the game is behind (or not looking): skip this picture
            let mut pick = None;
            for i in 0..SLOTS {
                let s = (next_slot + i) % SLOTS;
                if free[s].load(Ordering::SeqCst) {
                    pick = Some(s);
                    break;
                }
            }
            match pick {
                Some(s) => next_slot = s,
                None => {
                    drop(frame);
                    std::thread::sleep(Duration::from_millis(2));
                    continue;
                }
            }
        }
        let tex: ID3D11Texture2D = unsafe { frame.Surface()?.cast::<IDirect3DDxgiInterfaceAccess>()?.GetInterface()? };
        let region = D3D11_BOX { left: bl, top: bt, front: 0, right: br, bottom: bb, back: 1 };
        if let Some(c) = &mut cpu {
            let copied = if level > 0 {
                if !shrinker.as_ref().is_some_and(|s| s.w == fw && s.h == fh && s.levels == level) {
                    shrinker = None;
                    match Shrinker::new(&device, fw, fh, level) {
                        Ok(s) => shrinker = Some(s),
                        Err(e) => {
                            // this graphics card can't make the smaller copies: full size from now on
                            eprintln!("info=can't shrink here ({e}); full size");
                            shrink_broken = true;
                            continue;
                        }
                    }
                }
                let s = shrinker.as_ref().unwrap();
                unsafe {
                    ctx.CopySubresourceRegion(&s.tex, 0, 0, 0, 0, &tex, 0, Some(&region));
                    ctx.GenerateMips(&s.srv);
                }
                c.copy(&ctx, &s.tex, level, None, next_slot)
            } else {
                shrinker = None;
                c.copy(&ctx, &tex, 0, Some(&region), next_slot)
            };
            if let Err(e) = copied {
                eprintln!("info=read-back failed: {e}");
                continue;
            }
            slot_level = level;
        } else {
            unsafe {
                ctx.CopySubresourceRegion(&slots[next_slot], 0, 0, 0, 0, &tex, 0, Some(&region));
                ctx.Flush();
            }
            // OpenGL reads it without any lock: announce it only once the copy is really done
            if a.ext {
                gpu_selftest::wait_idle(&device, &ctx, &mut query);
            }
        }
        let captured = if timing.load(Ordering::SeqCst) { frame.SystemRelativeTime().map(|t| t.Duration).unwrap_or(0) } else { 0 };
        drop(frame);
        if a.ext {
            free[next_slot].store(false, Ordering::SeqCst); // the game's until it says "free"
        }
        let line = if captured > 0 { format!("frame {next_slot} {captured}") } else { format!("frame {next_slot}") };
        if !say(&mut out, &line) {
            break;
        }
        next_slot = (next_slot + 1) % SLOTS;
    }
    let _ = session.Close();
    let _ = pool.Close();
    Ok(())
}

fn main() -> std::process::ExitCode {
    let a = match parse() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("error={e}");
            return std::process::ExitCode::from(2);
        }
    };
    // Windows' default timer is 15.6 ms: the 2 ms waits for the next picture (and the pacing
    // sleeps) would really last up to 15.6 ms — measured: up to ~10 ms later on the wall at P99,
    // ~1 ms with this. Only this helper's timer changes (Windows 10 2004+ keeps it per process).
    let _ = a.hires; // (the old experiment switch: now always on)
    unsafe { timeBeginPeriod(1) };
    if a.selftest {
        println!("{}", gpu_selftest::run(a.luid));
        return std::process::ExitCode::SUCCESS;
    }
    match run(a) {
        Ok(()) => std::process::ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("error={e}");
            std::process::ExitCode::from(1)
        }
    }
}
