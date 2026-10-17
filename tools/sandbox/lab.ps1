# Sandboxie-Plus, measured before anything is built on it: exactly which programs a box rule takes,
# and which it leaves alone. Every way own copies went wrong (Steam, Roblox and Store apps boxed;
# the Claude app's pages boxed while it ran on the desk) came from a rule taking more than meant.
# Writes out\sandbox-lab.txt: what happened, not PASS/FAIL — the design follows the facts.
$ErrorActionPreference = "Continue"
$out = "out"; New-Item -ItemType Directory -Force -Path $out | Out-Null
$report = "$out\sandbox-lab.txt"; "" | Set-Content $report
function Say($s) { Add-Content $report $s; Write-Host $s }

# ---- Sandboxie, installed quietly, one box
$inst = Get-ChildItem "$env:RUNNER_TEMP\sbie\*.exe" | Select-Object -First 1
$p = Start-Process $inst.FullName -ArgumentList "/VERYSILENT /SUPPRESSMSGBOXES /NORESTART /ALLUSERS" -Wait -PassThru
$dir = "C:\Program Files\Sandboxie-Plus"
Say "Sandboxie: $($inst.Name), install exit $($p.ExitCode), service $((Get-Service SbieSvc -ErrorAction SilentlyContinue).Status)"
Start-Sleep 3
Get-Process SandMan -ErrorAction SilentlyContinue | Stop-Process -Force
$ini = "$dir\SbieIni.exe"
foreach ($k in @(@("Enabled", "y"), @("BorderColor", "#000000,off,0"), @("BoxNameTitle", "-"), @("AutoDelete", "n"), @("ConfigLevel", "10"))) { & $ini set WCLab $k[0] $k[1] | Out-Null }
& "$dir\Start.exe" /reload | Out-Null
Start-Sleep 2

Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices; using System.Text;
public static class Sbie {
    [DllImport(@"C:\Program Files\Sandboxie-Plus\SbieDll.dll", CharSet = CharSet.Unicode)]
    public static extern int SbieApi_QueryProcess(IntPtr pid, StringBuilder box, StringBuilder image, StringBuilder sid, out uint session);
    [DllImport(@"C:\Program Files\Sandboxie-Plus\SbieDll.dll")] public static extern int SbieApi_ReloadConf(uint session, uint flags);
    public static string BoxOf(int pid) {
        var b = new StringBuilder(64); var i = new StringBuilder(128); var s = new StringBuilder(128); uint ses;
        return SbieApi_QueryProcess(new IntPtr(pid), b, i, s, out ses) == 0 ? b.ToString() : "";
    }
}
"@
$iniPath = "C:\Windows\Sandboxie.ini"
function Rules($lines) {
    $text = Get-Content $iniPath -Encoding Unicode
    $o = @(); $in = $false
    foreach ($l in $text) {
        if ($l -match '^\[') { $in = ($l -eq '[WCLab]') }
        if ($in -and $l -match '^(Force\w*|Breakout\w*)=') { continue }
        $o += $l
        if ($l -eq '[WCLab]') { $o += $lines }
    }
    Set-Content $iniPath $o -Encoding Unicode
    $r = [Sbie]::SbieApi_ReloadConf([uint32]::MaxValue, 0)
    Start-Sleep 1
    Say "  rules now: $(if ($lines) { $lines -join ', ' } else { '(none)' }) (reload $r)"
}
function Box($id) { try { $b = [Sbie]::BoxOf([int]$id); if ($b) { $b } else { "-" } } catch { "?" } }
# every process of that program now: pid, its box, its parent's name
function Who($name) {
    $all = Get-CimInstance Win32_Process
    ($all | Where-Object { $_.Name -eq $name } | ForEach-Object {
        $pp = $all | Where-Object ProcessId -eq $_.ParentProcessId | Select-Object -First 1
        "$($_.ProcessId)[box $(Box $_.ProcessId), parent $(if ($pp) { $pp.Name } else { 'gone' })]"
    }) -join ' '
}
function Kill($name) { Get-Process -Name ([IO.Path]::GetFileNameWithoutExtension($name)) -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue; Start-Sleep 1 }

# ---- the test program: "parent <signal file>" starts another copy of itself each time the file
#      appears (as a browser or an app starts its own helpers); anything else just stays a while
$src = @"
using System; using System.Diagnostics; using System.IO; using System.Threading;
class P { static void Main(string[] a) {
    string me = Process.GetCurrentProcess().MainModule.FileName;
    if (a.Length > 1 && a[0] == "parent") {
        for (int i = 0; i < 6000; i++) {
            if (File.Exists(a[1])) { File.Delete(a[1]); Process.Start(new ProcessStartInfo(a.Length > 2 ? a[2] : me, "child") { UseShellExecute = false }); }
            Thread.Sleep(100);
        }
        return;
    }
    Thread.Sleep(300000);
} }
"@
$csc = "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
New-Item -ItemType Directory -Force C:\lab, C:\lab\apps, C:\lab\games | Out-Null
Set-Content C:\lab\p.cs $src
& $csc /nologo /target:winexe /out:C:\lab\apps\labapp.exe C:\lab\p.cs | Out-Null
foreach ($n in "deskapp", "shellapp", "otherapp") { Copy-Item C:\lab\apps\labapp.exe "C:\lab\apps\$n.exe" }
Copy-Item C:\lab\apps\labapp.exe C:\lab\games\game.exe
Copy-Item C:\lab\apps\labapp.exe C:\lab\apps\game2.exe
Say "test program built: $(Test-Path C:\lab\apps\labapp.exe)"

# ==== 1. a rule by program name (0.15.36's): does it take the new processes a copy of that program
#         running outside the box starts (a browser's pages, an app's helpers)?
Say ""; Say "== 1. ForceProcess=deskapp.exe, with deskapp.exe already running outside the box"
Rules @()
Start-Process C:\lab\apps\deskapp.exe -ArgumentList "parent", "C:\lab\sig1"
Start-Sleep 2
Rules @("ForceProcess=deskapp.exe")
Set-Content C:\lab\sig1 x; Start-Sleep 4
Say "  the running copy started a helper of its own: $(Who 'deskapp.exe')"
Start-Process C:\lab\apps\deskapp.exe -ArgumentList "child"; Start-Sleep 4
Say "  then started by hand (as from a monitor's taskbar): $(Who 'deskapp.exe')"
Kill deskapp.exe

# ==== 2. a rule on what Explorer starts (taskbar, Start menu, desktop, a double-click): only what
#         you open yourself — never what an app running on your desk starts
Say ""; Say "== 2. ForceChildren=explorer.exe"
Rules @("ForceChildren=explorer.exe")
Start-Process explorer.exe -ArgumentList "C:\lab\apps\shellapp.exe"; Start-Sleep 5
Say "  opened through Explorer: $(Who 'shellapp.exe')"
Start-Process C:\lab\apps\otherapp.exe -ArgumentList "child"; Start-Sleep 4
Say "  started by another program (not Explorer): $(Who 'otherapp.exe')"
Start-Process C:\lab\apps\deskapp.exe -ArgumentList "parent", "C:\lab\sig2"; Start-Sleep 3
Set-Content C:\lab\sig2 x; Start-Sleep 4
Say "  an app outside the box starting its own helper: $(Who 'deskapp.exe')"
Kill shellapp.exe; Kill otherapp.exe; Kill deskapp.exe

# ==== 3. with that rule, are games, launchers and Store apps let out?
Say ""; Say "== 3. ForceChildren=explorer.exe + BreakoutFolder=C:\lab\games + BreakoutProcess=game2.exe"
Rules @("ForceChildren=explorer.exe", "BreakoutFolder=C:\lab\games", "BreakoutProcess=game2.exe")
Start-Process explorer.exe -ArgumentList "C:\lab\games\game.exe"; Start-Sleep 5
Say "  a program from an excepted folder, opened through Explorer: $(Who 'game.exe')"
Start-Process explorer.exe -ArgumentList "C:\lab\apps\game2.exe"; Start-Sleep 5
Say "  an excepted program name, opened through Explorer: $(Who 'game2.exe')"
Start-Process explorer.exe -ArgumentList "C:\lab\apps\shellapp.exe"; Start-Sleep 5
Say "  an ordinary one, opened through Explorer meanwhile: $(Who 'shellapp.exe')"
Kill game.exe; Kill game2.exe; Kill shellapp.exe
# a boxed program starting an excepted one (a game from a boxed browser), and an ordinary one
& "$dir\Start.exe" /box:WCLab C:\lab\apps\deskapp.exe parent C:\lab\sig3 C:\lab\games\game.exe; Start-Sleep 5
Say "  (the boxed program: $(Who 'deskapp.exe'))"
Set-Content C:\lab\sig3 x; Start-Sleep 5
Say "  a boxed program starting a program from an excepted folder: $(Who 'game.exe')"
Kill deskapp.exe; Kill game.exe
& "$dir\Start.exe" /box:WCLab C:\lab\apps\deskapp.exe parent C:\lab\sig4 C:\lab\apps\otherapp.exe; Start-Sleep 5
Set-Content C:\lab\sig4 x; Start-Sleep 5
Say "  a boxed program starting an ordinary program: $(Who 'otherapp.exe')"
Kill deskapp.exe; Kill otherapp.exe

# a Store app opened from the Start menu
$calc = Get-AppxPackage *WindowsCalculator* -ErrorAction SilentlyContinue | Select-Object -First 1
if ($calc) {
    Start-Process explorer.exe -ArgumentList "shell:AppsFolder\$($calc.PackageFamilyName)!App"; Start-Sleep 8
    Say "  a Store app (Calculator) opened through Explorer: $(Who 'CalculatorApp.exe') $(Who 'Calculator.exe')"
    Get-Process CalculatorApp, Calculator -ErrorAction SilentlyContinue | Stop-Process -Force
} else { Say "  (no Calculator here to try a Store app)" }

# ==== 4. a real browser, open on the desk, opened again through Explorer with that rule on
Say ""; Say "== 4. Edge on the desk, then Edge opened through Explorer with ForceChildren=explorer.exe"
$edge = @("C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", "C:\Program Files\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($edge) {
    Rules @()
    $prof = "C:\lab\edgeprofile"
    Start-Process $edge -ArgumentList "--user-data-dir=$prof", "--no-first-run", "--no-default-browser-check", "about:blank"; Start-Sleep 8
    $deskEdge = (Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'msedge.exe' -and $_.CommandLine -notmatch '--type=' } | Select-Object -First 1).ProcessId
    Say "  desk Edge (browser process $deskEdge), its processes: $((Get-CimInstance Win32_Process | Where-Object Name -eq 'msedge.exe').Count)"
    Rules @("ForceChildren=explorer.exe")
    # the desk Edge opens a new tab meanwhile (a link from another app): its new page process
    Start-Process $edge -ArgumentList "--user-data-dir=$prof", "https://example.com/"; Start-Sleep 6
    $mine = Get-CimInstance Win32_Process | Where-Object Name -eq 'msedge.exe'
    $boxedDesk = @($mine | Where-Object { (Box $_.ProcessId) -ne '-' })
    Say "  the desk Edge opening a tab meanwhile: Edge processes in a box: $($boxedDesk.Count) of $($mine.Count)"
    # opened through Explorer (the monitor's taskbar): its own copy in the box?
    Start-Process explorer.exe -ArgumentList "`"$edge`""; Start-Sleep 10
    $all = Get-CimInstance Win32_Process | Where-Object Name -eq 'msedge.exe'
    $inBox = @($all | Where-Object { (Box $_.ProcessId) -eq 'WCLab' })
    Say "  opened through Explorer: Edge processes in the box: $($inBox.Count) (of $($all.Count)); desk Edge browser process still there: $([bool](Get-Process -Id $deskEdge -ErrorAction SilentlyContinue))"
    Get-Process msedge -ErrorAction SilentlyContinue | Stop-Process -Force
} else { Say "  (no Edge here)" }

# ==== 5. can ForceChildren be several programs, and does it reach grandchildren?
Say ""; Say "== 5. what a program opened through Explorer starts itself (stays in the box?)"
Rules @("ForceChildren=explorer.exe")
Start-Process explorer.exe -ArgumentList "C:\lab\apps\deskapp.exe"; Start-Sleep 4
Kill deskapp.exe
Copy-Item C:\lab\apps\labapp.exe C:\lab\apps\launcher.exe -Force
Set-Content C:\lab\launch.cmd "@start `"`" C:\lab\apps\launcher.exe parent C:\lab\sig5"
Start-Process explorer.exe -ArgumentList "C:\lab\launch.cmd"; Start-Sleep 5
Set-Content C:\lab\sig5 x; Start-Sleep 4
Say "  through a .cmd (explorer → cmd → launcher → its helper): $(Who 'launcher.exe')"
Kill launcher.exe

Rules @()
& "$dir\Start.exe" /terminate_all | Out-Null
Say ""; Say "== done"
exit 0
