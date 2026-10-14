# Sandboxie-Plus as WindowsCraft's per-monitor isolation: does it do what we need?
# Writes out\sandbox-report.txt (PASS/FAIL lines) and screenshots.
$ErrorActionPreference = "Continue"
$out = "out"; New-Item -ItemType Directory -Force -Path $out | Out-Null
$report = "$out\sandbox-report.txt"; "" | Set-Content $report
$fails = 0
function Check($name, $ok, $detail) {
    $line = $(if ($ok) { "PASS " } else { "FAIL " }) + $name + ": " + $detail
    Add-Content $report $line; Write-Host $line
    if (-not $ok) { $script:fails++ }
}
function Note($s) { Add-Content $report "     $s"; Write-Host "     $s" }
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
# Window titles read without asking the window (Get-Process's MainWindowTitle asks it, and waits
# forever if that app hangs): visible top-level windows, by process.
Add-Type -TypeDefinition @"
using System; using System.Collections.Generic; using System.Runtime.InteropServices; using System.Text;
public static class Wins {
    delegate bool EnumProc(IntPtr h, IntPtr l);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc f, IntPtr l);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr h);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int InternalGetWindowText(IntPtr h, StringBuilder s, int n);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr h, out uint pid);
    [DllImport("user32.dll")] static extern bool PostMessageW(IntPtr h, uint m, IntPtr w, IntPtr l);
    public static List<object[]> All() {
        var r = new List<object[]>();
        EnumWindows((h, l) => {
            if (!IsWindowVisible(h)) return true;
            var sb = new StringBuilder(512); InternalGetWindowText(h, sb, 512);
            if (sb.Length == 0) return true;
            uint pid; GetWindowThreadProcessId(h, out pid);
            r.Add(new object[] { (int)pid, sb.ToString(), h });
            return true;
        }, IntPtr.Zero);
        return r;
    }
    public static void Close(IntPtr h) { PostMessageW(h, 0x0010, IntPtr.Zero, IntPtr.Zero); }
}
"@
# like Get-Process, but only processes with a titled window: Id, ProcessName, MainWindowTitle, Hwnd
function Procs([string[]]$names) {
    $seen = @{}
    foreach ($w in [Wins]::All()) {
        $id = [int]$w[0]
        if ($seen.ContainsKey($id)) { continue }
        $p = Get-Process -Id $id -ErrorAction SilentlyContinue
        if (-not $p) { continue }
        if ($names -and -not ($names -contains $p.ProcessName)) { continue }
        $seen[$id] = 1
        [pscustomobject]@{ Id = $id; ProcessName = $p.ProcessName; MainWindowTitle = [string]$w[1]; Hwnd = $w[2] }
    }
}
function Section($s) { Write-Host "== $s (at $((Get-Date).ToString('HH:mm:ss')))" }
function Shot($name) {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
    $bmp.Save("$out\$name.png"); $g.Dispose(); $bmp.Dispose()
}

# ---- 1. silent install
Section '1. silent install'
$inst = Get-ChildItem "$env:RUNNER_TEMP\sbie\*.exe" | Select-Object -First 1
Note "installer: $($inst.Name)"
$t0 = Get-Date
$p = Start-Process $inst.FullName -ArgumentList "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /ALLUSERS" -Wait -PassThru
$dir = "C:\Program Files\Sandboxie-Plus"
Check "silent install" ($p.ExitCode -eq 0 -and (Test-Path "$dir\Start.exe")) "exit $($p.ExitCode) in $([int]((Get-Date)-$t0).TotalSeconds) s; files: $((Get-ChildItem $dir -Filter *.exe | ForEach-Object Name) -join ', ')"
$svc = Get-Service SbieSvc -ErrorAction SilentlyContinue
Check "its service runs" ($svc -and $svc.Status -eq "Running") "SbieSvc: $($svc.Status)"
Start-Sleep 3
$ui = Get-Process SandMan -ErrorAction SilentlyContinue
$run = (Get-ItemProperty HKCU:\Software\Microsoft\Windows\CurrentVersion\Run -ErrorAction SilentlyContinue).PSObject.Properties | Where-Object { $_.Value -match "SandMan" }
Note "after install: SandMan running: $([bool]$ui); autostart entry: $(($run | ForEach-Object Name) -join ',')"
if ($ui) { $ui | Stop-Process -Force }
foreach ($r in $run) { Remove-ItemProperty HKCU:\Software\Microsoft\Windows\CurrentVersion\Run -Name $r.Name }
$hklmRun = (Get-ItemProperty HKLM:\Software\Microsoft\Windows\CurrentVersion\Run -ErrorAction SilentlyContinue).PSObject.Properties | Where-Object { $_.Value -match "SandMan" }
foreach ($r in $hklmRun) { Remove-ItemProperty HKLM:\Software\Microsoft\Windows\CurrentVersion\Run -Name $r.Name }
Note "HKLM autostart entries removed: $(($hklmRun | ForEach-Object Name) -join ',')"

# ---- 2. two boxes, configured without the UI: no border, no [#] in titles, kept (not deleted)
Section '2. two boxes, configured without the UI: no border, n'
$ini = "$dir\SbieIni.exe"
Check "the config tool is there" (Test-Path $ini) $ini
foreach ($b in "WCMon1", "WCMon2") {
    & $ini set $b Enabled y | Out-Null
    & $ini set $b BorderColor "#000000,off,0" | Out-Null
    & $ini set $b BoxNameTitle $(if ($env:WC_TITLE) { $env:WC_TITLE } else { "-" }) | Out-Null
    & $ini set $b AutoDelete n | Out-Null
    & $ini set $b ConfigLevel 10 | Out-Null
}
& "$dir\Start.exe" /reload | Out-Null
Start-Sleep 2
$q = & $ini query WCMon1 BoxNameTitle
Note "WCMon1 BoxNameTitle = $q"
Note "Sandboxie.ini: $((Get-Content C:\Windows\Sandboxie.ini -ErrorAction SilentlyContinue) -join ' | ')"

# ---- 3. a little "site": sets a sign-in cookie; its page title shows what cookie it got
Section '3. a little "site": sets a sign-in cookie; its page t'
$port = 18080
$server = Start-Job -ScriptBlock {
    param($port)
    $l = New-Object System.Net.HttpListener; $l.Prefixes.Add("http://127.0.0.1:$port/"); $l.Start()
    while ($true) {
        $c = $l.GetContext()
        $got = $c.Request.Headers["Cookie"]; if (-not $got) { $got = "none" }
        $set = $c.Request.QueryString["login"]
        if ($set) { $c.Response.Headers.Add("Set-Cookie", "wclogin=$set; Max-Age=86400; Path=/") }
        $b = [Text.Encoding]::UTF8.GetBytes("<html><head><title>wc[$got]#$($c.Request.Url.Query)</title></head><body>$got</body></html>")
        $c.Response.ContentType = "text/html"; $c.Response.OutputStream.Write($b, 0, $b.Length); $c.Response.Close()
    }
} -ArgumentList $port
Start-Sleep 2
$edge = @("C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", "C:\Program Files\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
$flags = "--no-first-run --no-default-browser-check --disable-features=msEdgeWelcomePage"
function Titles { Procs msedge | ForEach-Object { "$($_.Id):$($_.MainWindowTitle)" } }
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class Sbie {
    [DllImport(@"C:\Program Files\Sandboxie-Plus\SbieDll.dll", CharSet = CharSet.Unicode)]
    public static extern int SbieApi_QueryProcess(IntPtr pid, StringBuilder box, StringBuilder image, StringBuilder sid, out uint session);
    public static string BoxOf(int pid) {
        var b = new StringBuilder(64); var i = new StringBuilder(128); var s = new StringBuilder(128); uint ses;
        int r = SbieApi_QueryProcess(new IntPtr(pid), b, i, s, out ses);
        return r == 0 ? b.ToString() : "";
    }
}
"@
function IniNow() { (Get-Content C:\Windows\Sandboxie.ini -ErrorAction SilentlyContinue | Where-Object { $_ -match '^(\[|Force|Breakout)' }) -join ' | ' }
function BoxOf($id) { try { [Sbie]::BoxOf($id) } catch { "?" } }
function BoxPids($b) { Get-Process msedge, powershell -ErrorAction SilentlyContinue | Where-Object { (BoxOf $_.Id) -eq $b } | ForEach-Object Id }
function BoxWins($b) { Procs msedge | Where-Object { (BoxOf $_.Id) -eq $b } }
function WaitBox($b, $pattern, $sec) { $t = (Get-Date).AddSeconds($sec); while ((Get-Date) -lt $t) { $w = BoxWins $b | Where-Object { $_.MainWindowTitle -match $pattern }; if ($w) { return $w }; Start-Sleep 1 }; return $null }
function WaitTitle($pattern, $sec) { $t = (Get-Date).AddSeconds($sec); while ((Get-Date) -lt $t) { $w = Titles | Where-Object { $_ -match $pattern }; if ($w) { return $w }; Start-Sleep 1 }; return $null }

# your desk Edge, signed in
Start-Process $edge -ArgumentList "$flags http://127.0.0.1:$port/?login=desk"
Start-Sleep 6
Start-Process $edge -ArgumentList "http://127.0.0.1:$port/"
$desk = WaitTitle "wc\[wclogin=desk\]" 30
Check "desk Edge signed in to the test site" ([bool]$desk) "$((Titles) -join ' ; ')"
$deskPids = (Get-Process msedge).Id
$deskWin = [int]("$desk".Split(':')[0])
Note "desk Edge window's box: '$(BoxOf $deskWin)' (empty = not boxed)"

# ---- 4. Edge in box 1 and box 2, while your desk Edge keeps running
Section '4. Edge in box 1 and box 2, while your desk Edge keep'
& "$dir\Start.exe" /box:WCMon1 /silent $edge $flags.Split(' ') "http://127.0.0.1:$port/?probe=1"
$null = WaitBox WCMon1 "wc\[" 40
& "$dir\Start.exe" /box:WCMon2 /silent $edge $flags.Split(' ') "http://127.0.0.1:$port/?probe=2"
$null = WaitBox WCMon2 "wc\[" 40
Start-Sleep 3
$p1 = BoxPids WCMon1; $p2 = BoxPids WCMon2
Note "box 1 pids: $($p1 -join ','); box 2 pids: $($p2 -join ','); desk pids: $($deskPids -join ',')"
Note "Edge windows now: $((Titles) -join ' ; ')"
$mine1 = @(BoxWins WCMon1); $mine2 = @(BoxWins WCMon2)
Check "box 1 runs its own Edge (separate program from the desk's, so separate sound)" ($mine1 -and -not ($deskPids -contains $mine1[0].Id)) "$($mine1 | ForEach-Object { "$($_.Id):$($_.MainWindowTitle)" })"
Check "box 2 runs its own Edge too, at the same time" ($mine2 -and $mine1 -and $mine2[0].Id -ne $mine1[0].Id) "$($mine2 | ForEach-Object { "$($_.Id):$($_.MainWindowTitle)" })"
Check "the desk Edge kept running, untouched" ((Get-Process -Id $deskWin -ErrorAction SilentlyContinue) -ne $null) "desk window pid $deskWin"
$t1 = "$($mine1.MainWindowTitle)"
Check "a boxed window's title has no [#] marker" ($t1 -and $t1 -notmatch '\[#\]') "'$t1'"
Note "box 1 starts with your desk login: $(if ($t1 -match 'wclogin=desk') { 'yes' } else { 'no' }) ('$t1')"
Shot "1-desk-and-two-boxes"
$ui = Get-Process SandMan -ErrorAction SilentlyContinue
$sbWins = Procs | Where-Object { $_.MainWindowTitle -match "Sandboxie" } | ForEach-Object { "$($_.ProcessName):$($_.MainWindowTitle)" }
Check "no Sandboxie window or app showed up" (-not $ui -and -not $sbWins) "SandMan: $([bool]$ui); windows: $($sbWins -join ',')"

# ---- 5. a login made in box 1 stays in box 1: after closing it, and only there
Section '5. a login made in box 1 stays in box 1: after closin'
& "$dir\Start.exe" /box:WCMon1 /silent $edge "http://127.0.0.1:$port/?login=mon1"
$null = WaitBox WCMon1 "login=mon1" 20
Start-Sleep 3
# closed the way you'd close it (as the monitor turning off does), not killed: it saves what it has
foreach ($w in BoxWins WCMon1) { [Wins]::Close($w.Hwnd) }
$t = (Get-Date).AddSeconds(15); while ((Get-Date) -lt $t -and (BoxPids WCMon1)) { Start-Sleep 1 }
Note "box 1 closed by itself: $(-not (BoxPids WCMon1))"
& "$dir\Start.exe" /box:WCMon1 /terminate | Out-Null
Start-Sleep 2
& "$dir\Start.exe" /box:WCMon1 /silent $edge $flags.Split(' ') "http://127.0.0.1:$port/?again=1"
$again = WaitBox WCMon1 "wc\[wclogin=mon1\]" 40
Check "a login made on monitor 1's box is still there after closing and reopening it" ([bool]$again) "box 1 windows: $((BoxWins WCMon1 | ForEach-Object MainWindowTitle) -join ' ; ')"
Start-Process $edge -ArgumentList "http://127.0.0.1:$port/?deskcheck=1"
Start-Sleep 6
$deskT = Titles | Where-Object { (BoxOf ([int]($_.Split(':')[0]))) -eq "" }
Check "…and it never touched your desk's login" (-not ($deskT -match 'wclogin=mon1')) "$($deskT -join ' ; ')"
& "$dir\Start.exe" /box:WCMon2 /silent $edge "http://127.0.0.1:$port/?mon2check=1"
Start-Sleep 6
$m2 = WaitBox WCMon2 "mon2check" 25
Check "…nor monitor 2's (each box keeps its own logins)" ($m2 -and -not ("$($m2.MainWindowTitle)" -match 'wclogin=mon1')) "box 2: $((BoxWins WCMon2 | ForEach-Object MainWindowTitle) -join ' ; ')"

# ---- 6. an app that only runs once (named mutex): a second copy in a box
Section '6. an app that only runs once (named mutex): a second'
$single = @'
$m = New-Object System.Threading.Mutex($false, "Local\WindowsCraftSingleTest")
if (-not $m.WaitOne(0)) { exit 7 }
Start-Sleep 40
'@
Set-Content "$env:RUNNER_TEMP\single.ps1" $single
$a = Start-Process powershell -ArgumentList "-NoProfile -File $env:RUNNER_TEMP\single.ps1" -PassThru -WindowStyle Hidden
Start-Sleep 9
$b2 = Start-Process powershell -ArgumentList "-NoProfile -File $env:RUNNER_TEMP\single.ps1" -PassThru -WindowStyle Hidden -Wait
Note "a second plain copy of the single-instance app: exit $($b2.ExitCode) (7 = refused)"
& "$dir\Start.exe" /box:WCMon1 /silent /wait powershell -NoProfile -File "$env:RUNNER_TEMP\single.ps1"
$code = $LASTEXITCODE
Check "an app that only runs once runs again inside a box (a second copy)" ($b2.ExitCode -eq 7 -and $code -ne 7) "outside: $($b2.ExitCode), in box: $code"

# ---- 8. "force" rules: anything started while you sit at monitor 1 lands in its box by itself
Section '8. "force" rules: anything started while you sit at m'
#         (switched on and off as you sit down and get up)
& "$dir\Start.exe" /terminate_all | Out-Null
Start-Sleep 2
$t0 = Get-Date
$edgeDir = Split-Path (Split-Path $edge)   # (…\Microsoft\Edge)
& $ini set WCMon1 ForceFolder $edgeDir | Out-Null
& "$dir\Start.exe" /reload | Out-Null
$ms = [int]((Get-Date) - $t0).TotalMilliseconds
Note "turning the force rule on took $ms ms"
$before = (Get-Process msedge -ErrorAction SilentlyContinue).Id
Start-Process $edge -ArgumentList "$flags http://127.0.0.1:$port/?forced=1"   # (as if from the monitor's own Start menu)
Start-Sleep 8
$forced = Procs msedge | Where-Object { $_.MainWindowTitle -match "forced=1" }
Check "with the force rule on, an app started the normal way opens in monitor 1's box by itself" ($forced -and (BoxOf $forced[0].Id) -eq "WCMon1") "$($forced | ForEach-Object { "$($_.Id) box '$(BoxOf $_.Id)': $($_.MainWindowTitle)" })"
$np = Start-Process notepad -PassThru; Start-Sleep 3
Note "notepad started then: box '$(BoxOf $np.Id)'"
$np | Stop-Process -Force -ErrorAction SilentlyContinue
$shellBox = Get-Process explorer -ErrorAction SilentlyContinue | ForEach-Object { BoxOf $_.Id }
Check "…and the Windows shell itself stays out of it" (-not ($shellBox -match "WCMon")) "explorer boxes: '$($shellBox -join ',')'"
$t0 = Get-Date
& $ini delete WCMon1 ForceFolder | Out-Null
& "$dir\Start.exe" /reload | Out-Null
Note "turning it off took $([int]((Get-Date) - $t0).TotalMilliseconds) ms; config now: $(IniNow)"
$np = Start-Process notepad -PassThru; Start-Sleep 3
Check "with it off again, apps start normally (on your desk)" ((BoxOf $np.Id) -eq "") "notepad box '$(BoxOf $np.Id)'"
$np | Stop-Process -Force -ErrorAction SilentlyContinue
foreach ($w in BoxWins WCMon1) { [Wins]::Close($w.Hwnd) }
Start-Sleep 5

# ---- 10. exceptions while the force rule is on: programs that must never be boxed
Section '10. exceptions while the force rule is on: programs th'
#          (Sandboxie's own tools, WindowsCraft's helpers, games with anti-cheat, apps you picked)
#          The rules are written straight into Sandboxie.ini and reloaded through Sandboxie's own
#          programming interface (as WindowsCraft's helper does), never by starting its tools.
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices;
public static class SbieConf {
    [DllImport(@"C:\Program Files\Sandboxie-Plus\SbieDll.dll")] public static extern int SbieApi_ReloadConf(uint session, uint flags);
}
"@
$iniPath = "C:\Windows\Sandboxie.ini"
function SetBoxRules($lines) {
    $text = Get-Content $iniPath -Encoding Unicode
    $out = @(); $in = $false
    foreach ($l in $text) {
        if ($l -match '^\[') { $in = ($l -eq '[WCMon1]') }
        if ($in -and $l -match '^(ForceFolder|ForceProcess|BreakoutFolder|BreakoutProcess)=') { continue }
        $out += $l
        if ($l -eq '[WCMon1]') { $out += $lines }
    }
    Set-Content $iniPath $out -Encoding Unicode
    $r = [SbieConf]::SbieApi_ReloadConf([uint32]::MaxValue, 0)
    return $r
}
$gui = (Resolve-Path "src\main\resources\windowscraft\natives\wc-selftest.exe").Path
foreach ($d in "C:\wcforce", "C:\wcforce\exc") { New-Item -ItemType Directory -Force -Path $d | Out-Null }
Copy-Item $gui "C:\wcforce\wcapp_a.exe"; Copy-Item $gui "C:\wcforce\wcapp_b.exe"; Copy-Item $gui "C:\wcforce\exc\wcapp_h.exe"
# (a forced program is restarted inside the box under a new process id: find it by its window —
#  titled by the arguments if they came along, by its name if not)
function StartGui($exe, $t) {
    $stem = [IO.Path]::GetFileNameWithoutExtension($exe)
    $null = Start-Process $exe -ArgumentList "--window", $t -PassThru
    $w = $null; $end = (Get-Date).AddSeconds(12)
    while (-not $w -and (Get-Date) -lt $end) { Start-Sleep -Milliseconds 300; $w = Procs | Where-Object { $_.MainWindowTitle -like "*$t*" -or $_.MainWindowTitle -like "*$stem*" } | Select-Object -First 1 }
    if (-not $w) { return "no window" }
    $b = BoxOf $w.Id
    Note "($stem → box '$b', window title '$($w.MainWindowTitle)')"
    Procs | Where-Object { $_.MainWindowTitle -like "*$t*" -or $_.MainWindowTitle -like "*$stem*" } | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    return $b
}
$n0 = StartGui "C:\wcforce\wcapp_a.exe" "wc-plain"
Check "a test app starts and shows its window (no rules yet)" ($n0 -eq "") "box '$n0'"
$t0 = Get-Date
$r = SetBoxRules @("ForceFolder=C:\wcforce")
Note "force rule written + reloaded through Sandboxie's interface: result $r in $([int]((Get-Date)-$t0).TotalMilliseconds) ms · $(IniNow)"
$a1 = StartGui "C:\wcforce\wcapp_a.exe" "wc-force-a"
Check "with the force rule on, a program from that folder started the normal way lands in the box" ($a1 -eq "WCMon1") "box '$a1'"
$r = SetBoxRules @("ForceFolder=C:\wcforce", "BreakoutFolder=C:\wcforce\exc", "BreakoutProcess=wcapp_b.exe")
Note "with exceptions: result $r · $(IniNow)"
$h1 = StartGui "C:\wcforce\exc\wcapp_h.exe" "wc-force-h"
$b1 = StartGui "C:\wcforce\wcapp_b.exe" "wc-force-b"
$a2 = StartGui "C:\wcforce\wcapp_a.exe" "wc-force-a2"
# (Sandboxie's driver has no exceptions inside a forced folder — Breakout* only covers programs a
#  boxed program starts — so WindowsCraft carves its forced folders around the exceptions instead;
#  recorded here, not a check)
Note "Breakout* inside a forced folder: excepted folder → box '$h1', excepted name → box '$b1'"
Check "…while others still are" ($a2 -eq "WCMon1") "box '$a2'"
$t0 = Get-Date
$r = SetBoxRules @()
Note "rules removed through the interface: result $r in $([int]((Get-Date)-$t0).TotalMilliseconds) ms · $(IniNow)"
$z = StartGui "C:\wcforce\wcapp_a.exe" "wc-force-after"
Check "with the rules removed, nothing is boxed any more" ($z -eq "") "box '$z'"

# ---- 9. the installer: its signature and fingerprint (to check downloads against)
Section '9. the installer: its signature and fingerprint (to c'
$sig = Get-AuthenticodeSignature $inst.FullName
Note "installer signature: $($sig.Status) by $($sig.SignerCertificate.Subject)"
Note "installer sha256: $((Get-FileHash $inst.FullName -Algorithm SHA256).Hash)"

# ---- 7. where the boxes keep things, and their size
Section '7. where the boxes keep things, and their size'
$root = "C:\Sandbox"
if (Test-Path $root) {
    Get-ChildItem $root -Directory -Recurse -Depth 1 | ForEach-Object { Note "box folder $($_.FullName): $([int]((Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum / 1MB)) MB" }
}
& "$dir\Start.exe" /terminate_all | Out-Null
Stop-Job $server; Remove-Job $server -Force
Add-Content $report "== $fails failed"
exit 0
