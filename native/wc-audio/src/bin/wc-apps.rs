//! wc-apps: lists the installed apps the way the Start menu's "All apps" does (desktop programs
//! and Store apps alike), with their icons, so WindowsCraft's on-screen Start menu matches.
//!
//!   wc-apps <icon dir>
//! stdout, one line per app:
//!   app<TAB><name><TAB><launch id><TAB><icon file or empty><TAB><host><TAB><target exe><TAB><admin>
//! host: 1 for Store-style (UWP) apps, 0 for desktop programs; target exe: the program a desktop
//! entry starts (may be empty); admin: 1 if that program asks to run as administrator.
//! The launch id opens the app with `explorer.exe shell:AppsFolder\<launch id>`.
//! Icons are written as raw 48×48 BGRA (straight alpha), top row first.

use std::io::Write;
use windows::core::PWSTR;
use windows::Win32::Foundation::SIZE;
use windows::Win32::Graphics::Gdi::*;
use windows::Win32::System::Com::{CoInitializeEx, CoTaskMemFree, COINIT_APARTMENTTHREADED};
use windows::Win32::UI::Shell::*;
use windows::Win32::Foundation::{FreeLibrary, PROPERTYKEY};
use windows::core::{Interface, GUID, PCWSTR};
use windows::Win32::System::LibraryLoader::*;

/// System.AppUserModel.HostEnvironment (1 = Store-style app) and System.Link.TargetParsingPath.
const PKEY_HOST: PROPERTYKEY = PROPERTYKEY { fmtid: GUID::from_u128(0x9F4C2855_9F79_4B39_A8D0_E1D42DE1D5F3), pid: 14 };
const PKEY_TARGET: PROPERTYKEY = PROPERTYKEY { fmtid: GUID::from_u128(0xB9B4B3FC_2B51_4A42_B5D8_324146AFCF25), pid: 2 };

/// Does the program's manifest ask to run as administrator (or as high as the user can)?
fn needs_admin(path: &str) -> bool {
    let w: Vec<u16> = path.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        let Ok(m) = LoadLibraryExW(PCWSTR(w.as_ptr()), None, LOAD_LIBRARY_AS_DATAFILE | LOAD_LIBRARY_AS_IMAGE_RESOURCE) else { return false };
        let mut admin = false;
        let r = FindResourceW(Some(m), PCWSTR(1 as *const u16), PCWSTR(24 as *const u16));
        if !r.is_invalid() {
            let size = SizeofResource(Some(m), r) as usize;
            if let Ok(g) = LoadResource(Some(m), r) {
                let p = LockResource(g) as *const u8;
                if !p.is_null() && size > 0 {
                    let text = String::from_utf8_lossy(std::slice::from_raw_parts(p, size));
                    admin = text.contains("requireAdministrator") || text.contains("highestAvailable");
                }
            }
        }
        let _ = FreeLibrary(m);
        admin
    }
}

const ICON: i32 = 48;

fn take(p: PWSTR) -> String {
    let s = unsafe { p.to_string() }.unwrap_or_default();
    unsafe { CoTaskMemFree(Some(p.0 as *const _)) };
    s
}

/// The item's icon as straight-alpha BGRA, or None.
fn icon(item: &IShellItem) -> Option<Vec<u8>> {
    let factory: IShellItemImageFactory = windows::core::Interface::cast(item).ok()?;
    let hbm = unsafe { factory.GetImage(SIZE { cx: ICON, cy: ICON }, SIIGBF_ICONONLY | SIIGBF_BIGGERSIZEOK) }.ok()?;
    let mut bmi = BITMAPINFO::default();
    bmi.bmiHeader.biSize = std::mem::size_of::<BITMAPINFOHEADER>() as u32;
    bmi.bmiHeader.biWidth = ICON;
    bmi.bmiHeader.biHeight = -ICON; // top row first
    bmi.bmiHeader.biPlanes = 1;
    bmi.bmiHeader.biBitCount = 32;
    bmi.bmiHeader.biCompression = BI_RGB.0;
    let mut px = vec![0u8; (ICON * ICON * 4) as usize];
    let ok = unsafe {
        let dc = CreateCompatibleDC(None);
        let n = GetDIBits(dc, hbm, 0, ICON as u32, Some(px.as_mut_ptr() as *mut _), &mut bmi, DIB_RGB_COLORS);
        let _ = DeleteDC(dc);
        let _ = DeleteObject(hbm.into());
        n > 0
    };
    if !ok {
        return None;
    }
    // shell images are premultiplied; icons without any alpha at all are opaque
    let any_alpha = px.chunks(4).any(|p| p[3] != 0);
    for p in px.chunks_mut(4) {
        if !any_alpha {
            p[3] = 255;
        } else if p[3] != 0 && p[3] != 255 {
            let a = p[3] as u32;
            for c in 0..3 {
                p[c] = ((p[c] as u32 * 255 + a / 2) / a).min(255) as u8;
            }
        }
    }
    Some(px)
}

fn main() -> std::process::ExitCode {
    let dir = std::path::PathBuf::from(std::env::args().nth(1).unwrap_or_else(|| ".".into()));
    let _ = std::fs::create_dir_all(&dir);
    unsafe {
        let _ = CoInitializeEx(None, COINIT_APARTMENTTHREADED);
    }
    let folder: IShellItem = match unsafe { SHGetKnownFolderItem(&FOLDERID_AppsFolder, KF_FLAG_DEFAULT, None) } {
        Ok(f) => f,
        Err(e) => {
            eprintln!("error=no apps folder ({e})");
            return std::process::ExitCode::from(1);
        }
    };
    let items: IEnumShellItems = match unsafe { folder.BindToHandler(None, &BHID_EnumItems) } {
        Ok(i) => i,
        Err(e) => {
            eprintln!("error=can't list apps ({e})");
            return std::process::ExitCode::from(1);
        }
    };
    let stdout = std::io::stdout();
    let mut out = stdout.lock();
    let mut n = 0;
    loop {
        let mut batch = [None];
        let mut got = 0u32;
        if unsafe { items.Next(&mut batch, Some(&mut got)) }.is_err() || got == 0 {
            break;
        }
        let Some(item) = batch[0].take() else { break };
        let name = unsafe { item.GetDisplayName(SIGDN_NORMALDISPLAY) }.map(take).unwrap_or_default();
        let id = unsafe { item.GetDisplayName(SIGDN_PARENTRELATIVEPARSING) }.map(take).unwrap_or_default();
        if name.is_empty() || id.is_empty() {
            continue;
        }
        let file = match icon(&item) {
            Some(px) => {
                let f = dir.join(format!("{n}.bgra"));
                if std::fs::write(&f, px).is_ok() { f.display().to_string() } else { String::new() }
            }
            None => String::new(),
        };
        let item2: Option<IShellItem2> = item.cast().ok();
        let host = item2.as_ref().and_then(|i| unsafe { i.GetUInt32(&PKEY_HOST) }.ok()).unwrap_or(0);
        let target = item2.as_ref().and_then(|i| unsafe { i.GetString(&PKEY_TARGET) }.ok()).map(take).unwrap_or_default();
        let admin = target.to_lowercase().ends_with(".exe") && needs_admin(&target);
        let clean = |s: &str| s.replace(['\t', '\n', '\r'], " ");
        let _ = writeln!(out, "app\t{}\t{}\t{}\t{}\t{}\t{}", clean(&name), clean(&id), file, host, clean(&target), admin as u8);
        n += 1;
    }
    let _ = out.flush();
    std::process::ExitCode::SUCCESS
}
