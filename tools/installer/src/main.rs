//! WindowsCraft installer: the one file to double-click. It carries the WindowsCraft jar and puts
//! it in every Cobbleverse game folder it finds — the official launcher (its own folder and each
//! installation's own game folder), Modrinth App, CurseForge, Prism / MultiMC, ATLauncher, or a
//! game folder of its own near the user's files (like C:\Users\you\Cobbleverse) — taking older
//! WindowsCraft versions out. If the game is open it waits until it has closed (Windows keeps the
//! running jar locked). Needs no admin rights and changes nothing else.
//!
//! Built by build.sh: the jar comes in at compile time (WC_JAR = its path, WC_JAR_NAME = its
//! name). `--list` only shows the folders it would install into.

use std::collections::HashSet;
use std::env;
use std::ffi::OsStr;
use std::fs;
use std::io::{self, BufRead, Write};
use std::os::windows::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::thread::sleep;
use std::time::Duration;

const JAR: &[u8] = include_bytes!(env!("WC_JAR"));
const JAR_NAME: &str = env!("WC_JAR_NAME");
/// What the in-game updater calls a downloaded jar waiting to be swapped in at exit.
const PENDING: &str = ".wcupdate";

#[link(name = "kernel32")]
extern "system" {
    fn GetLogicalDrives() -> u32;
    fn GetDriveTypeW(root: *const u16) -> u32;
    fn SetConsoleTitleW(title: *const u16) -> i32;
}
const DRIVE_FIXED: u32 = 3;

fn wide(s: &str) -> Vec<u16> {
    OsStr::new(s).encode_wide().chain([0]).collect()
}

fn main() {
    let list_only = env::args().any(|a| a == "--list");
    unsafe { SetConsoleTitleW(wide("WindowsCraft installer").as_ptr()) };
    println!();
    println!("  WindowsCraft installer");
    println!("  ----------------------");
    println!();
    let code = if list_only { list() } else { run() };
    if !list_only {
        println!();
        print!("  Press Enter to close this window.");
        let _ = io::stdout().flush();
        let _ = io::stdin().lock().read_line(&mut String::new());
    }
    std::process::exit(code);
}

fn list() -> i32 {
    let found = targets();
    for d in &found {
        println!("  {}", d.display());
    }
    if found.is_empty() {
        println!("  (none found)");
    }
    0
}

fn run() -> i32 {
    println!("  Installing {JAR_NAME}");
    println!();
    let mut todo = targets();
    if todo.is_empty() {
        match ask_folder() {
            Some(d) => todo.push(d),
            None => {
                println!("  Nothing was installed.");
                return 1;
            }
        }
    }
    let mut ok = 0;
    let mut waiting = Vec::new();
    for d in todo {
        match install(&d) {
            Outcome::Locked => {
                println!("  [..] {}  - Minecraft is using it", d.display());
                waiting.push(d);
            }
            o => ok += report(&d, o),
        }
    }
    if !waiting.is_empty() {
        println!();
        println!("  Minecraft is open. Quit the game completely and keep this window open:");
        println!("  WindowsCraft goes in by itself as soon as the game has closed.");
        while !waiting.is_empty() {
            sleep(Duration::from_secs(2));
            waiting.retain(|d| match install(d) {
                Outcome::Locked => true,
                o => {
                    ok += report(d, o);
                    false
                }
            });
        }
    }
    println!();
    if ok == 0 {
        println!("  Nothing was installed.");
        return 1;
    }
    println!("  Done! Start Cobbleverse (restart it if it's open) and join the server as usual.");
    println!("  Screens other players share show up on their black concrete walls.");
    println!("  To share your own: look at a black concrete wall and press U.");
    0
}

/// Prints how a folder went; 1 if WindowsCraft is in it now.
fn report(dir: &Path, o: Outcome) -> i32 {
    match o {
        Outcome::Installed(replaced) if replaced.is_empty() => println!("  [OK] {}", dir.display()),
        Outcome::Installed(replaced) => println!("  [OK] {}  (replaced {})", dir.display(), replaced.join(", ")),
        Outcome::AlreadyThere => println!("  [OK] {}  (already up to date)", dir.display()),
        Outcome::Failed(e) => {
            println!("  [!!] {}  - {e}", dir.display());
            return 0;
        }
        Outcome::Locked => return 0,
    }
    1
}

/// None found: the user drags the game folder (or its mods folder) into the window.
fn ask_folder() -> Option<PathBuf> {
    println!("  I couldn't find your Cobbleverse game folder by myself.");
    println!("  In your launcher, open the Cobbleverse game folder (Modrinth App: the three dots ->");
    println!("  \"Open folder\"; CurseForge: right-click the modpack -> \"Open Folder\"; Minecraft");
    println!("  Launcher: Installations -> the folder icon), then drag that folder (or the \"mods\"");
    println!("  folder in it) into this window and press Enter.");
    loop {
        println!();
        print!("  Folder: ");
        let _ = io::stdout().flush();
        let mut line = String::new();
        if io::stdin().lock().read_line(&mut line).ok()? == 0 {
            return None;
        }
        let t = line.trim().trim_matches(['"', '\'']).trim();
        if t.is_empty() {
            return None;
        }
        let mut p = PathBuf::from(t);
        if p.file_name().is_some_and(|n| n.eq_ignore_ascii_case("mods")) {
            p.pop();
        }
        if p.join("mods").is_dir() || p.join("options.txt").is_file() {
            return Some(p);
        }
        println!("  That isn't a game folder (it has no \"mods\" folder). Try again, or press Enter to stop.");
    }
}

// ---- installing ---------------------------------------------------------------------------------

enum Outcome {
    /// in now, in place of these older ones
    Installed(Vec<String>),
    AlreadyThere,
    /// the game is open and holds a jar we have to take out
    Locked,
    Failed(String),
}

fn install(dir: &Path) -> Outcome {
    let mods = dir.join("mods");
    if let Err(e) = fs::create_dir_all(&mods) {
        return Outcome::Failed(e.to_string());
    }
    let mine = |p: &Path| name(p).eq_ignore_ascii_case(JAR_NAME);
    // older versions go first: never two WindowsCraft jars side by side (the game won't start)
    let mut replaced = Vec::new();
    for old in ours(&mods, ".jar").into_iter().filter(|p| !mine(p)) {
        match remove(&old) {
            Ok(()) => replaced.push(name(&old)),
            Err(e) if locked(&e) => return Outcome::Locked,
            Err(e) => return Outcome::Failed(format!("couldn't take out {}: {}", name(&old), why(&e))),
        }
    }
    // an in-game download of this version or older would be swapped in later, over this one
    for p in ours(&mods, PENDING) {
        if !newer(&name(&p), JAR_NAME) {
            let _ = remove(&p);
        }
    }
    let target = mods.join(JAR_NAME);
    let already = fs::read(&target).is_ok_and(|b| b == JAR);
    if !already {
        let part = mods.join(format!("{JAR_NAME}.part"));
        if let Err(e) = fs::write(&part, JAR).and_then(|()| fs::rename(&part, &target)) {
            let _ = fs::remove_file(&part);
            return if locked(&e) { Outcome::Locked } else { Outcome::Failed(why(&e)) };
        }
    }
    // exactly one WindowsCraft jar now, and it's this one
    let now = ours(&mods, ".jar");
    if now.len() != 1 || !mine(&now[0]) || !fs::read(&now[0]).is_ok_and(|b| b == JAR) {
        return Outcome::Failed("the mods folder didn't come out right; run this again".into());
    }
    if already && replaced.is_empty() {
        Outcome::AlreadyThere
    } else {
        Outcome::Installed(replaced)
    }
}

/// WindowsCraft's files in a mods folder ending in `suffix`.
fn ours(mods: &Path, suffix: &str) -> Vec<PathBuf> {
    let Ok(rd) = fs::read_dir(mods) else { return Vec::new() };
    rd.flatten()
        .filter(|e| e.file_type().is_ok_and(|t| t.is_file()))
        .map(|e| e.path())
        .filter(|p| {
            let n = name(p).to_ascii_lowercase();
            n.starts_with("windowscraft") && n.ends_with(suffix)
        })
        .collect()
}

fn name(p: &Path) -> String {
    p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()
}

fn remove(p: &Path) -> io::Result<()> {
    match fs::remove_file(p) {
        Err(e) if e.raw_os_error() == Some(5) => {
            // read-only? clear that and try again
            let mut perm = fs::metadata(p)?.permissions();
            if !perm.readonly() {
                return Err(e);
            }
            #[allow(clippy::permissions_set_readonly_false)]
            perm.set_readonly(false);
            fs::set_permissions(p, perm)?;
            fs::remove_file(p)
        }
        r => r,
    }
}

/// In use by another program (sharing / lock violation): the game is open.
fn locked(e: &io::Error) -> bool {
    matches!(e.raw_os_error(), Some(32) | Some(33))
}

fn why(e: &io::Error) -> String {
    if e.raw_os_error() == Some(5) {
        "Windows says access denied (if Minecraft is open, close it and run this again)".into()
    } else {
        e.to_string()
    }
}

/// Is the version in file name a newer than the one in b? (the numbers before the "+")
fn newer(a: &str, b: &str) -> bool {
    let (x, y) = (version(a), version(b));
    for i in 0..x.len().max(y.len()) {
        let (p, q) = (x.get(i).copied().unwrap_or(0), y.get(i).copied().unwrap_or(0));
        if p != q {
            return p > q;
        }
    }
    false
}

fn version(file: &str) -> Vec<u32> {
    let s = file.to_ascii_lowercase();
    let s = s.trim_start_matches("windowscraft").trim_start_matches('-');
    let core = s.split(['+', '-']).next().unwrap_or("");
    core.split('.')
        .map(|p| p.chars().filter(char::is_ascii_digit).collect::<String>().parse().unwrap_or(0))
        .collect()
}

// ---- finding the game folders -------------------------------------------------------------------

/// Cobbleverse game folders, each once.
fn targets() -> Vec<PathBuf> {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for (d, known) in candidates() {
        if !is_target(&d, known) {
            continue;
        }
        let key = fs::canonicalize(&d).unwrap_or_else(|_| d.clone()).to_string_lossy().to_lowercase();
        if seen.insert(key) {
            out.push(d);
        }
    }
    out
}

/// A client game folder with WindowsCraft already, or Cobbleverse: Cobblemon for 1.21.1 in its
/// mods folder, and "Cobbleverse" in the folder's name or in a file's in it (the pack brings
/// "COBBLEVERSE - Third-Party Licenses.pdf" and LegendaryMonuments-Cobbleverse.jar) — another
/// Cobblemon modpack is left alone. Not a server, not a backup; one only found by looking around
/// must have been played.
fn is_target(dir: &Path, known: bool) -> bool {
    let mods = dir.join("mods");
    if !mods.is_dir() || dir.join("server.properties").exists() {
        return false;
    }
    if dir.components().any(|c| c.as_os_str().to_string_lossy().to_lowercase().contains("backup")) {
        return false;
    }
    if !known && !dir.join("options.txt").is_file() {
        return false;
    }
    let names = |d: &Path| -> Vec<String> {
        fs::read_dir(d).map_or(Vec::new(), |rd| rd.flatten().map(|e| e.file_name().to_string_lossy().to_lowercase()).collect())
    };
    let jars = names(&mods);
    if jars.iter().any(|n| n.starts_with("windowscraft") && n.ends_with(".jar")) {
        return true;
    }
    if !jars.iter().any(|n| n.starts_with("cobblemon-") && n.contains("1.21.1") && n.ends_with(".jar")) {
        return false;
    }
    // the folder's name, or the instance's for a .minecraft / minecraft folder inside one
    let named = dir.components().rev().take(2).any(|c| c.as_os_str().to_string_lossy().to_lowercase().contains("cobbleverse"));
    named || names(dir).iter().chain(&jars).any(|n| n.contains("cobbleverse"))
}

/// Where game folders may be; true = a launcher says so (no need to have been played).
fn candidates() -> Vec<(PathBuf, bool)> {
    let var = |k: &str| env::var_os(k).filter(|v| !v.is_empty()).map(PathBuf::from);
    let (appdata, local, home) = (var("APPDATA"), var("LOCALAPPDATA"), var("USERPROFILE"));
    let mut known = Vec::new();

    // the official launcher: its own folder, and each installation's own game folder
    let mut dot = Vec::new();
    if let Some(a) = &appdata {
        dot.push(a.join(".minecraft"));
    }
    if let Some(l) = &local {
        dot.push(l.join(r"Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Roaming\.minecraft"));
    }
    for d in &dot {
        known.push(d.clone());
        for f in ["launcher_profiles.json", "launcher_profiles_microsoft_store.json"] {
            if let Ok(s) = fs::read_to_string(d.join(f)) {
                known.extend(game_dirs_in(&s).into_iter().map(|g| if g.is_relative() { d.join(g) } else { g }));
            }
        }
    }
    // launchers with a folder per instance
    if let Some(a) = &appdata {
        for p in [r"ModrinthApp\profiles", r"com.modrinth.theseus\profiles", r"ATLauncher\instances", r"gdlauncher_next\instances"] {
            subfolders(&a.join(p), &mut known, false);
        }
        for p in [r"PrismLauncher\instances", r"MultiMC\instances", r"PolyMC\instances"] {
            subfolders(&a.join(p), &mut known, true);
        }
    }
    if let Some(h) = &home {
        for p in [r"curseforge\minecraft\Instances", r"Documents\Curse\Minecraft\Instances", r"ATLauncher\instances"] {
            subfolders(&h.join(p), &mut known, false);
        }
    }

    // a game folder of its own near the user's files or on a drive (like C:\Users\you\Cobbleverse)
    let mut near = Vec::new();
    if let Some(h) = &home {
        for s in ["", "Desktop", "Documents", "Downloads", "Games", r"OneDrive\Desktop", r"OneDrive\Documents"] {
            near.push(h.join(s));
        }
    }
    if let Some(o) = var("OneDrive") {
        near.push(o.join("Desktop"));
        near.push(o.join("Documents"));
    }
    let drives = unsafe { GetLogicalDrives() };
    for i in 2..26 {
        let root = format!("{}:\\", (b'A' + i as u8) as char);
        if drives & (1 << i) != 0 && unsafe { GetDriveTypeW(wide(&root).as_ptr()) } == DRIVE_FIXED {
            near.push(PathBuf::from(root));
        }
    }
    let mut found = Vec::new();
    for n in near {
        look_near(&n, &mut found, true);
    }
    known.into_iter().map(|d| (d, true)).chain(found.into_iter().map(|d| (d, false))).collect()
}

/// Each subfolder of dir (with mc: its .minecraft / minecraft folder, as Prism keeps them).
fn subfolders(dir: &Path, out: &mut Vec<PathBuf>, mc: bool) {
    let Ok(rd) = fs::read_dir(dir) else { return };
    for e in rd.flatten().filter(|e| e.file_type().is_ok_and(|t| t.is_dir())) {
        if mc {
            out.push(e.path().join(".minecraft"));
            out.push(e.path().join("minecraft"));
        } else {
            out.push(e.path());
        }
    }
}

/// Folders in dir (and their .minecraft / minecraft), one level further in ones named like games.
fn look_near(dir: &Path, out: &mut Vec<PathBuf>, deeper: bool) {
    let Ok(rd) = fs::read_dir(dir) else { return };
    // (a junction, like "My Documents", isn't a plain folder: skipped, so no loops)
    for e in rd.flatten().filter(|e| e.file_type().is_ok_and(|t| t.is_dir())) {
        let p = e.path();
        out.push(p.join(".minecraft"));
        out.push(p.join("minecraft"));
        out.push(p.clone());
        let n = e.file_name().to_string_lossy().to_lowercase();
        if deeper && ["minecraft", "game", "cobble", "modpack", "instance", "launcher"].iter().any(|k| n.contains(k)) {
            look_near(&p, out, false);
        }
    }
}

/// Each installation's game folder ("gameDir") in the official launcher's profiles file.
fn game_dirs_in(json: &str) -> Vec<PathBuf> {
    let mut out = Vec::new();
    let mut rest = json;
    while let Some(i) = rest.find("\"gameDir\"") {
        rest = &rest[i + "\"gameDir\"".len()..];
        let Some(t) = rest.trim_start().strip_prefix(':') else { continue };
        let Some(t) = t.trim_start().strip_prefix('"') else { continue };
        if let Some((s, used)) = json_string(t) {
            out.push(PathBuf::from(s));
            rest = &t[used..];
        }
    }
    out
}

/// A JSON string's text (from just after its opening quote), and how many bytes it took.
fn json_string(s: &str) -> Option<(String, usize)> {
    let mut out = String::new();
    let mut it = s.char_indices();
    let hex4 = |it: &mut std::str::CharIndices| -> Option<u32> {
        let h: String = (0..4).filter_map(|_| it.next().map(|c| c.1)).collect();
        u32::from_str_radix(&h, 16).ok()
    };
    while let Some((i, c)) = it.next() {
        match c {
            '"' => return Some((out, i + 1)),
            '\\' => match it.next()?.1 {
                'n' => out.push('\n'),
                't' => out.push('\t'),
                'r' => out.push('\r'),
                'b' => out.push('\u{8}'),
                'f' => out.push('\u{c}'),
                'u' => {
                    let mut u = hex4(&mut it)?;
                    if (0xD800..0xDC00).contains(&u) {
                        // the first half of a pair: \uD83D\uDE00
                        let mut look = it.clone();
                        if look.next().map(|c| c.1) == Some('\\') && look.next().map(|c| c.1) == Some('u') {
                            if let Some(lo) = hex4(&mut look).filter(|lo| (0xDC00..0xE000).contains(lo)) {
                                u = 0x10000 + ((u - 0xD800) << 10) + (lo - 0xDC00);
                                it = look;
                            }
                        }
                    }
                    out.push(char::from_u32(u).unwrap_or('\u{fffd}'));
                }
                c => out.push(c),
            },
            c => out.push(c),
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_game_dirs() {
        let j = r#"{"profiles":{"a":{"gameDir" : "C:\\Users\\Zo\u00eb\\Cobbleverse","name":"x"},"b":{"name":"y"},"c":{"gameDir":"D:/Games/\"Q\"/mc"}}}"#;
        let d = game_dirs_in(j);
        assert_eq!(d, vec![PathBuf::from("C:\\Users\\Zoë\\Cobbleverse"), PathBuf::from("D:/Games/\"Q\"/mc")]);
        assert_eq!(json_string(r#"\ud83d\ude00!" tail"#), Some(("😀!".to_string(), 14)));
    }

    #[test]
    fn versions() {
        assert!(newer("windowscraft-0.15.13+mc1.21.1.jar.wcupdate", "windowscraft-0.15.12+mc1.21.1.jar"));
        assert!(!newer("windowscraft-0.15.12+mc1.21.1.jar.wcupdate", "windowscraft-0.15.13+mc1.21.1.jar"));
        assert!(!newer("windowscraft-0.15.13+mc1.21.1.jar.wcupdate", "windowscraft-0.15.13+mc1.21.1.jar"));
        assert!(newer("windowscraft-0.16.0.jar", "windowscraft-0.15.13+mc1.21.1.jar"));
    }
}
