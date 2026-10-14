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
function Shot($name) {
    $b = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap $b.Width, $b.Height
    $g = [System.Drawing.Graphics]::FromImage($bmp); $g.CopyFromScreen($b.Location, [System.Drawing.Point]::Empty, $b.Size)
    $bmp.Save("$out\$name.png"); $g.Dispose(); $bmp.Dispose()
}

# ---- 1. silent install
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
$ini = "$dir\SbieIni.exe"
Check "the config tool is there" (Test-Path $ini) $ini
foreach ($b in "WCMon1", "WCMon2") {
    & $ini set $b Enabled y | Out-Null
    & $ini set $b BorderColor "#000000,off,0" | Out-Null
    & $ini set $b BoxNameTitle n | Out-Null
    & $ini set $b AutoDelete n | Out-Null
    & $ini set $b ConfigLevel 10 | Out-Null
}
& "$dir\Start.exe" /reload | Out-Null
Start-Sleep 2
$q = & $ini query WCMon1 BoxNameTitle
Note "WCMon1 BoxNameTitle = $q"
Note "Sandboxie.ini: $((Get-Content C:\Windows\Sandboxie.ini -ErrorAction SilentlyContinue) -join ' | ')"

# ---- 3. a little "site": sets a sign-in cookie; its page title shows what cookie it got
$port = 18080
$server = Start-Job -ScriptBlock {
    param($port)
    $l = New-Object System.Net.HttpListener; $l.Prefixes.Add("http://127.0.0.1:$port/"); $l.Start()
    while ($true) {
        $c = $l.GetContext()
        $got = $c.Request.Headers["Cookie"]; if (-not $got) { $got = "none" }
        $set = $c.Request.QueryString["login"]
        if ($set) { $c.Response.Headers.Add("Set-Cookie", "wclogin=$set; Max-Age=86400; Path=/") }
        $b = [Text.Encoding]::UTF8.GetBytes("<html><head><title>wc[$got]</title></head><body>$got</body></html>")
        $c.Response.ContentType = "text/html"; $c.Response.OutputStream.Write($b, 0, $b.Length); $c.Response.Close()
    }
} -ArgumentList $port
Start-Sleep 2
$edge = @("C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe", "C:\Program Files\Microsoft\Edge\Application\msedge.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
$flags = "--no-first-run --no-default-browser-check --disable-features=msEdgeWelcomePage"
function Titles { Get-Process msedge -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle } | ForEach-Object { "$($_.Id):$($_.MainWindowTitle)" } }
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
function BoxOf($id) { try { [Sbie]::BoxOf($id) } catch { "?" } }
function BoxPids($b) { Get-Process msedge, powershell -ErrorAction SilentlyContinue | Where-Object { (BoxOf $_.Id) -eq $b } | ForEach-Object Id }
function BoxWins($b) { Get-Process msedge -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -and (BoxOf $_.Id) -eq $b } }
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
$sbWins = Get-Process | Where-Object { $_.MainWindowTitle -match "Sandboxie" } | ForEach-Object { "$($_.ProcessName):$($_.MainWindowTitle)" }
Check "no Sandboxie window or app showed up" (-not $ui -and -not $sbWins) "SandMan: $([bool]$ui); windows: $($sbWins -join ',')"

# ---- 5. a login made in box 1 stays in box 1: after closing it, and only there
& "$dir\Start.exe" /box:WCMon1 /silent $edge "http://127.0.0.1:$port/?login=mon1"
Start-Sleep 6
& "$dir\Start.exe" /box:WCMon1 /terminate | Out-Null
Start-Sleep 4
& "$dir\Start.exe" /box:WCMon1 /silent $edge $flags.Split(' ') "http://127.0.0.1:$port/?again=1"
$again = WaitBox WCMon1 "wc\[wclogin=mon1\]" 40
Check "a login made on monitor 1's box is still there after closing and reopening it" ([bool]$again) "box 1 windows: $((BoxWins WCMon1 | ForEach-Object MainWindowTitle) -join ' ; ')"
Start-Process $edge -ArgumentList "http://127.0.0.1:$port/?deskcheck=1"
Start-Sleep 6
$deskT = Titles | Where-Object { (BoxOf ([int]($_.Split(':')[0]))) -eq "" }
Check "…and it never touched your desk's login" (-not ($deskT -match 'wclogin=mon1')) "$($deskT -join ' ; ')"
& "$dir\Start.exe" /box:WCMon2 /silent $edge "http://127.0.0.1:$port/?mon2check=1"
Start-Sleep 6
$m2 = WaitBox WCMon2 "mon2check" 20
Check "…nor monitor 2's (each box keeps its own logins)" ($m2 -and -not ("$($m2.MainWindowTitle)" -match 'wclogin=mon1')) "box 2: $((BoxWins WCMon2 | ForEach-Object MainWindowTitle) -join ' ; ')"

# ---- 6. an app that only runs once (named mutex): a second copy in a box
$single = @'
$m = New-Object System.Threading.Mutex($false, "Local\WindowsCraftSingleTest")
if (-not $m.WaitOne(0)) { exit 7 }
Start-Sleep 25
'@
Set-Content "$env:RUNNER_TEMP\single.ps1" $single
$a = Start-Process powershell -ArgumentList "-NoProfile -File $env:RUNNER_TEMP\single.ps1" -PassThru -WindowStyle Hidden
Start-Sleep 3
$b2 = Start-Process powershell -ArgumentList "-NoProfile -File $env:RUNNER_TEMP\single.ps1" -PassThru -WindowStyle Hidden -Wait
Note "a second plain copy of the single-instance app: exit $($b2.ExitCode) (7 = refused)"
& "$dir\Start.exe" /box:WCMon1 /silent /wait powershell -NoProfile -File "$env:RUNNER_TEMP\single.ps1"
$code = $LASTEXITCODE
Check "an app that only runs once runs again inside a box (a second copy)" ($b2.ExitCode -eq 7 -and $code -ne 7) "outside: $($b2.ExitCode), in box: $code"

# ---- 7. where the boxes keep things, and their size
$root = "C:\Sandbox"
if (Test-Path $root) {
    Get-ChildItem $root -Directory -Recurse -Depth 1 | ForEach-Object { Note "box folder $($_.FullName): $([int]((Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum / 1MB)) MB" }
}
& "$dir\Start.exe" /terminate_all | Out-Null
Stop-Job $server; Remove-Job $server -Force
Add-Content $report "== $fails failed"
exit 0
