//! `wc-capture --selftest`: tries the exact Direct3D 11 → OpenGL sharing steps that direct GPU
//! sharing uses inside Minecraft — but in this separate, throwaway process with its own hidden
//! OpenGL window. If the graphics driver can't take it (or crashes), only this helper is affected
//! and the game simply doesn't turn the feature on.
//!
//! The sharing route is OpenGL's external memory (GL_EXT_memory_object_win32): Direct3D textures
//! made shareable with NT handles are imported into OpenGL as memory objects. Nothing is locked or
//! registered per frame; the capture side waits until each copy is finished before announcing it.
//!
//! stdout, one line:  `selftest=ok|<GL vendor>|<GL renderer>|<rgba|bgra>[+blit]`  or  `selftest=fail|<reason>|<vendor>|<renderer>`
//! (the last field says whether red and blue arrive swapped, so the game can correct it, and
//! "+blit" when the driver refuses glCopyImageSubData from shared pictures and a framebuffer
//! blit — the same GPU-to-GPU copy — has to be used instead)

use std::ffi::{c_char, CStr};
use windows::core::{s, w, Interface, PCSTR, PCWSTR};
use windows::Win32::Foundation::{CloseHandle, HANDLE, HMODULE};
use windows::Win32::Graphics::Direct3D::{D3D_DRIVER_TYPE_HARDWARE, D3D_DRIVER_TYPE_UNKNOWN};
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::Common::{DXGI_FORMAT_B8G8R8A8_UNORM, DXGI_SAMPLE_DESC};
use windows::Win32::Graphics::Dxgi::{CreateDXGIFactory1, IDXGIAdapter, IDXGIAdapter1, IDXGIFactory1, IDXGIResource1, DXGI_SHARED_RESOURCE_READ, DXGI_SHARED_RESOURCE_WRITE};
use windows::Win32::Graphics::Gdi::{GetDC, HDC};
use windows::Win32::Graphics::OpenGL::*;
use windows::Win32::UI::WindowsAndMessaging::{CreateWindowExW, DestroyWindow, WINDOW_EX_STYLE, WS_POPUP};

const GL_TEXTURE_2D: u32 = 0x0DE1;
const GL_RGBA8: u32 = 0x8058;
const GL_RGBA: u32 = 0x1908;
const GL_UNSIGNED_BYTE: u32 = 0x1401;
const GL_VENDOR: u32 = 0x1F00;
const GL_RENDERER: u32 = 0x1F01;
const GL_EXTENSIONS: u32 = 0x1F03;
const GL_DEDICATED_MEMORY_OBJECT_EXT: u32 = 0x9581;
const GL_HANDLE_TYPE_D3D11_IMAGE_EXT: u32 = 0x958B;

type CreateMemObjs = unsafe extern "system" fn(i32, *mut u32);
type DeleteMemObjs = unsafe extern "system" fn(i32, *const u32);
type MemObjParamiv = unsafe extern "system" fn(u32, u32, *const i32);
type ImportWin32 = unsafe extern "system" fn(u32, u64, u32, *mut core::ffi::c_void);
type TexStorageMem2D = unsafe extern "system" fn(u32, i32, u32, i32, i32, u32, u64);
type CopyImage = unsafe extern "system" fn(u32, u32, i32, i32, i32, i32, u32, u32, i32, i32, i32, i32, i32, i32, i32);

unsafe fn proc<T>(name: PCSTR) -> Option<T> {
    let p = unsafe { wglGetProcAddress(name) }?;
    let addr = p as usize;
    // some drivers return small sentinel values instead of null for "not supported"
    if addr <= 3 || addr == usize::MAX {
        return None;
    }
    Some(unsafe { std::mem::transmute_copy::<usize, T>(&addr) })
}

fn device_on(luid: Option<(u32, i32)>) -> windows::core::Result<(ID3D11Device, ID3D11DeviceContext)> {
    let mut adapter: Option<IDXGIAdapter> = None;
    if let Some((low, high)) = luid {
        let factory: IDXGIFactory1 = unsafe { CreateDXGIFactory1()? };
        let mut i = 0;
        while let Ok(a) = unsafe { factory.EnumAdapters1(i) } {
            let a: IDXGIAdapter1 = a;
            let d = unsafe { a.GetDesc1()? };
            if d.AdapterLuid.LowPart == low && d.AdapterLuid.HighPart == high {
                adapter = Some(a.cast()?);
                break;
            }
            i += 1;
        }
    }
    let (mut dev, mut ctx) = (None, None);
    unsafe {
        D3D11CreateDevice(
            adapter.as_ref(),
            if adapter.is_some() { D3D_DRIVER_TYPE_UNKNOWN } else { D3D_DRIVER_TYPE_HARDWARE },
            HMODULE::default(),
            D3D11_CREATE_DEVICE_BGRA_SUPPORT,
            None,
            D3D11_SDK_VERSION,
            Some(&mut dev),
            None,
            Some(&mut ctx),
        )?;
    }
    Ok((dev.unwrap(), ctx.unwrap()))
}

/// Waits (briefly) until the graphics card has finished everything queued so far.
pub fn wait_idle(dev: &ID3D11Device, ctx: &ID3D11DeviceContext, query: &mut Option<ID3D11Query>) {
    unsafe {
        if query.is_none() {
            let desc = D3D11_QUERY_DESC { Query: D3D11_QUERY_EVENT, MiscFlags: 0 };
            let mut q = None;
            if dev.CreateQuery(&desc, Some(&mut q)).is_err() {
                ctx.Flush();
                return;
            }
            *query = q;
        }
        let q = query.as_ref().unwrap();
        ctx.End(q);
        ctx.Flush();
        let start = std::time::Instant::now();
        loop {
            let mut done: i32 = 0;
            // S_FALSE (still busy) leaves `done` untouched; TRUE once the work is finished
            let r = ctx.GetData(q, Some(&mut done as *mut i32 as *mut _), 4, 0);
            if r.is_err() || done != 0 {
                break;
            }
            if start.elapsed().as_millis() > 100 {
                break;
            }
            std::thread::yield_now();
        }
    }
}

pub fn run(luid: Option<(u32, i32)>) -> String {
    let mut vendor = String::new();
    let mut renderer = String::new();
    let result = unsafe { test(luid, &mut vendor, &mut renderer) };
    match result {
        Ok(mode) => format!("selftest=ok|{vendor}|{renderer}|{mode}"),
        Err(why) => format!("selftest=fail|{why}|{vendor}|{renderer}"),
    }
}

unsafe fn test(luid: Option<(u32, i32)>, vendor: &mut String, renderer: &mut String) -> Result<&'static str, String> {
    // a hidden window with an OpenGL context, like Minecraft's (but never shown)
    let hwnd = unsafe { CreateWindowExW(WINDOW_EX_STYLE(0), w!("STATIC"), w!("wc-selftest"), WS_POPUP, 0, 0, 16, 16, None, None, None, None) }
        .map_err(|e| format!("window: {e}"))?;
    let hdc = unsafe { GetDC(Some(hwnd)) };
    let pfd = PIXELFORMATDESCRIPTOR {
        nSize: std::mem::size_of::<PIXELFORMATDESCRIPTOR>() as u16,
        nVersion: 1,
        dwFlags: PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER,
        iPixelType: PFD_TYPE_RGBA,
        cColorBits: 32,
        cDepthBits: 24,
        cStencilBits: 8,
        ..Default::default()
    };
    let fmt = unsafe { ChoosePixelFormat(hdc, &pfd) };
    if fmt == 0 {
        return Err("no OpenGL pixel format".into());
    }
    unsafe { SetPixelFormat(hdc, fmt, &pfd) }.map_err(|e| format!("pixel format: {e}"))?;
    let glrc = unsafe { wglCreateContext(hdc) }.map_err(|e| format!("OpenGL context: {e}"))?;
    unsafe { wglMakeCurrent(hdc, glrc) }.map_err(|e| format!("OpenGL context: {e}"))?;
    let gl_str = |n| unsafe {
        let p = glGetString(n);
        if p.is_null() { String::new() } else { CStr::from_ptr(p as *const c_char).to_string_lossy().replace('|', "/") }
    };
    *vendor = gl_str(GL_VENDOR);
    *renderer = gl_str(GL_RENDERER);
    let exts = gl_str(GL_EXTENSIONS);
    let has = |e: &str| exts.split_whitespace().any(|x| x == e);
    if !has("GL_EXT_memory_object") || !has("GL_EXT_memory_object_win32") {
        return Err("the graphics driver can't take Direct3D pictures as OpenGL memory (no GL_EXT_memory_object_win32)".into());
    }
    let create: CreateMemObjs = unsafe { proc(s!("glCreateMemoryObjectsEXT")) }.ok_or("glCreateMemoryObjectsEXT missing")?;
    let delete: DeleteMemObjs = unsafe { proc(s!("glDeleteMemoryObjectsEXT")) }.ok_or("glDeleteMemoryObjectsEXT missing")?;
    let param: MemObjParamiv = unsafe { proc(s!("glMemoryObjectParameterivEXT")) }.ok_or("glMemoryObjectParameterivEXT missing")?;
    let import: ImportWin32 = unsafe { proc(s!("glImportMemoryWin32HandleEXT")) }.ok_or("glImportMemoryWin32HandleEXT missing")?;
    let storage: TexStorageMem2D = unsafe { proc(s!("glTexStorageMem2DEXT")) }.ok_or("glTexStorageMem2DEXT missing")?;
    let copy: CopyImage = unsafe { proc(s!("glCopyImageSubData")) }.ok_or("glCopyImageSubData missing (needs OpenGL 4.3)")?;

    // the capture side: three shareable pictures, exactly like wc-capture makes them
    let (dev, ctx) = device_on(luid).map_err(|e| format!("Direct3D: {e}"))?;
    let (w, h) = (640u32, 360u32);
    let desc = D3D11_TEXTURE2D_DESC {
        Width: w,
        Height: h,
        MipLevels: 1,
        ArraySize: 1,
        Format: DXGI_FORMAT_B8G8R8A8_UNORM,
        SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
        Usage: D3D11_USAGE_DEFAULT,
        BindFlags: (D3D11_BIND_SHADER_RESOURCE.0 | D3D11_BIND_RENDER_TARGET.0) as u32,
        CPUAccessFlags: 0,
        MiscFlags: (D3D11_RESOURCE_MISC_SHARED.0 | D3D11_RESOURCE_MISC_SHARED_NTHANDLE.0) as u32,
    };
    let mut textures = Vec::new();
    let mut rtvs = Vec::new();
    let mut handles: Vec<HANDLE> = Vec::new();
    for _ in 0..3 {
        let mut t = None;
        unsafe { dev.CreateTexture2D(&desc, None, Some(&mut t)) }.map_err(|e| format!("texture: {e}"))?;
        let t = t.unwrap();
        let mut rtv = None;
        unsafe { dev.CreateRenderTargetView(&t, None, Some(&mut rtv)) }.map_err(|e| format!("texture view: {e}"))?;
        let hnd = unsafe { t.cast::<IDXGIResource1>().and_then(|r| r.CreateSharedHandle(None, (DXGI_SHARED_RESOURCE_READ | DXGI_SHARED_RESOURCE_WRITE).0, PCWSTR::null())) }
            .map_err(|e| format!("share handle: {e}"))?;
        handles.push(hnd);
        textures.push(t);
        rtvs.push(rtv.unwrap());
    }

    // the game side: each picture becomes OpenGL memory, and a texture on top of it
    // How big the picture's memory is, as the driver sees it, isn't something Direct3D tells us:
    // the plain size first, then the sizes drivers round pictures up to (some refuse a texture
    // on memory they think is too small — "invalid value"); dedicated memory first, then not.
    let base = (w * h * 4) as u64;
    let up = |v: u64, a: u64| (v + a - 1) / a * a;
    let pitch = up((w * 4) as u64, 256);
    let mut sizes: Vec<u64> = Vec::new();
    for v in [base, up(base, 65536), up(pitch * h as u64, 65536), base * 2, base * 4] {
        if !sizes.contains(&v) {
            sizes.push(v);
        }
    }
    let mut mems = [0u32; 3];
    let mut names = [0u32; 4];
    unsafe { glGenTextures(4, names.as_mut_ptr()) };
    let mut worked: Option<(u64, i32)> = None; // (whatever worked for the first is tried first for the others)
    for i in 0..3 {
        let mut tries: Vec<(u64, i32)> = Vec::new();
        if let Some(t) = worked {
            tries.push(t);
        }
        for &sz in &sizes {
            for ded in [1i32] { // (Direct3D pictures are dedicated memory: anything else imports, but shows black)
                if !tries.contains(&(sz, ded)) {
                    tries.push((sz, ded));
                }
            }
        }
        let mut last = String::new();
        for (sz, ded) in tries {
            unsafe {
                while glGetError() != 0 {}
                let mut m = 0u32;
                create(1, &mut m);
                param(m, GL_DEDICATED_MEMORY_OBJECT_EXT, &ded);
                import(m, sz, GL_HANDLE_TYPE_D3D11_IMAGE_EXT, handles[i].0);
                let e = glGetError();
                if e != 0 {
                    delete(1, &m);
                    last = format!("OpenGL error {e:#x} taking the picture's memory");
                    continue;
                }
                glBindTexture(GL_TEXTURE_2D, names[i]);
                storage(GL_TEXTURE_2D, 1, GL_RGBA8, w as i32, h as i32, m, 0);
                let e = glGetError();
                if e != 0 {
                    delete(1, &m);
                    glDeleteTextures(1, &names[i]); // a fresh texture for the next try
                    glGenTextures(1, &mut names[i]);
                    last = format!("OpenGL error {e:#x} making a texture on the picture's memory");
                    continue;
                }
                mems[i] = m;
                worked = Some((sz, ded));
                last.clear();
                break;
            }
        }
        if !last.is_empty() {
            return Err(last);
        }
        let _ = unsafe { CloseHandle(handles[i]) }; // OpenGL has its own reference now
    }
    let target = names[3];
    unsafe {
        glBindTexture(GL_TEXTURE_2D, target);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8 as i32, w as i32, h as i32, 0, GL_RGBA, GL_UNSIGNED_BYTE, std::ptr::null());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR as i32);
        glTexParameteri(GL_TEXTURE_2D, 0x813D /* GL_TEXTURE_MAX_LEVEL */, 0);
    }
    let gen_fb: Option<unsafe extern "system" fn(i32, *mut u32)> = unsafe { proc(s!("glGenFramebuffers")) };
    let bind_fb: Option<unsafe extern "system" fn(u32, u32)> = unsafe { proc(s!("glBindFramebuffer")) };
    let attach: Option<unsafe extern "system" fn(u32, u32, u32, u32, i32)> = unsafe { proc(s!("glFramebufferTexture2D")) };
    let blit_fn: Option<unsafe extern "system" fn(i32, i32, i32, i32, i32, i32, i32, i32, u32, u32)> = unsafe { proc(s!("glBlitFramebuffer")) };
    let mut blit = false;
    let mut fbos = [0u32; 2];
    // 90 frames: the capture side draws (and waits until it's done), the game side copies
    let mut query = None;
    let mut last = (0f32, 0f32, 0f32);
    for frame in 0..90usize {
        let slot = frame % 3;
        let c = (0.9f32, 0.5f32, 0.1f32 + (frame % 2) as f32 * 0.02);
        last = c;
        unsafe { ctx.ClearRenderTargetView(&rtvs[slot], &[c.0, c.1, c.2, 1.0]) };
        wait_idle(&dev, &ctx, &mut query);
        if !blit {
            unsafe { copy(names[slot], GL_TEXTURE_2D, 0, 0, 0, 0, target, GL_TEXTURE_2D, 0, 0, 0, 0, w as i32, h as i32, 1) };
            let e = unsafe { glGetError() };
            if e != 0 {
                // some drivers refuse this copy from shared pictures: a framebuffer blit is the same GPU copy
                let (Some(g), Some(_), Some(_), Some(_)) = (gen_fb, bind_fb, attach, blit_fn) else {
                    return Err(format!("OpenGL error {e:#x} while copying"));
                };
                blit = true;
                unsafe { g(2, fbos.as_mut_ptr()) };
            }
        }
        if blit {
            let (b, a, bl) = (bind_fb.unwrap(), attach.unwrap(), blit_fn.unwrap());
            unsafe {
                b(0x8CA8 /* READ */, fbos[0]);
                a(0x8CA8, 0x8CE0, GL_TEXTURE_2D, names[slot], 0);
                b(0x8CA9 /* DRAW */, fbos[1]);
                a(0x8CA9, 0x8CE0, GL_TEXTURE_2D, target, 0);
                bl(0, 0, w as i32, h as i32, 0, 0, w as i32, h as i32, 0x4000 /* COLOR_BUFFER_BIT */, 0x2600 /* NEAREST */);
                b(0x8CA8, 0);
                b(0x8CA9, 0);
            }
            let e = unsafe { glGetError() };
            if e != 0 {
                return Err(format!("OpenGL error {e:#x} while copying (both ways)"));
            }
        }
    }
    unsafe { glFinish() };
    let mut px = vec![0u8; (w * h * 4) as usize];
    unsafe {
        glBindTexture(GL_TEXTURE_2D, target);
        glGetTexImage(GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, px.as_mut_ptr() as *mut _);
    }
    let near = |v: u8, f: f32| (v as f32 - f * 255.0).abs() < 12.0;
    let p = &px[((h / 2 * w + w / 2) * 4) as usize..][..4];
    let mode = if near(p[0], last.0) && near(p[1], last.1) && near(p[2], last.2) {
        if blit { "rgba+blit" } else { "rgba" }
    } else if near(p[2], last.0) && near(p[1], last.1) && near(p[0], last.2) {
        if blit { "bgra+blit" } else { "bgra" }
    } else {
        return Err(format!("the shared picture came out wrong ({:?})", p));
    };
    unsafe {
        glDeleteTextures(4, names.as_ptr());
        delete(3, mems.as_ptr());
        let _ = wglMakeCurrent(HDC::default(), HGLRC::default());
        let _ = wglDeleteContext(glrc);
        let _ = DestroyWindow(hwnd);
    }
    drop(rtvs);
    drop(textures);
    Ok(mode)
}
