# WindowsCraft installer: copies the WindowsCraft jar that sits next to this script into every
# Cobbleverse instance it finds (Modrinth App, CurseForge, Prism, ATLauncher, plain .minecraft),
# replacing older WindowsCraft versions. Needs no admin rights and changes nothing else.
# Written for Windows PowerShell 5.1 (built into Windows 10/11).

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
if (-not $here) { $here = Split-Path -Parent $MyInvocation.MyCommand.Path }

function Say($text, $color) {
    if ($color) { Write-Host $text -ForegroundColor $color } else { Write-Host $text }
}

Say ''
Say '  WindowsCraft installer' Cyan
Say '  ----------------------' Cyan
Say ''

$jar = Get-ChildItem -LiteralPath $here -Filter 'windowscraft-*.jar' -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike '*-sources.jar' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) {
    Say "  I can't find the WindowsCraft .jar next to this installer." Red
    Say '  If you opened the zip by double-clicking it, close this window, right-click the zip,'
    Say '  choose "Extract All...", then run "Install WindowsCraft.bat" from the extracted folder.'
    exit 1
}
Say ("  Installing " + $jar.Name)
Say ''

# --- find Minecraft instances -------------------------------------------------------------
$appData = $env:APPDATA
$home_ = $env:USERPROFILE
$gameDirs = New-Object System.Collections.Generic.List[string]
function Add-Instances($parent, $sub) {
    if (-not $parent -or -not (Test-Path -LiteralPath $parent)) { return }
    foreach ($inst in Get-ChildItem -LiteralPath $parent -Directory -ErrorAction SilentlyContinue) {
        $dir = $inst.FullName
        if ($sub) {
            $found = $null
            foreach ($s in $sub) { $p = Join-Path $dir $s; if (Test-Path -LiteralPath $p) { $found = $p; break } }
            if (-not $found) { continue }
            $dir = $found
        }
        $gameDirs.Add($dir)
    }
}
Add-Instances (Join-Path $appData 'ModrinthApp\profiles') $null
Add-Instances (Join-Path $appData 'com.modrinth.theseus\profiles') $null
Add-Instances (Join-Path $home_ 'curseforge\minecraft\Instances') $null
Add-Instances (Join-Path $appData 'PrismLauncher\instances') @('.minecraft', 'minecraft')
Add-Instances (Join-Path $appData 'MultiMC\instances') @('.minecraft', 'minecraft')
Add-Instances (Join-Path $appData 'ATLauncher\instances') $null
Add-Instances (Join-Path $home_ 'ATLauncher\instances') $null
$vanilla = Join-Path $appData '.minecraft'
if (Test-Path -LiteralPath $vanilla) { $gameDirs.Add($vanilla) }

# a Cobbleverse instance: named like it, or has Cobblemon in its mods folder
function Test-Cobbleverse($dir) {
    $mods = Join-Path $dir 'mods'
    if (-not (Test-Path -LiteralPath $mods)) { return $false }
    $name = Split-Path -Leaf $dir
    if ($name -in @('.minecraft', 'minecraft')) { $name = Split-Path -Leaf (Split-Path -Parent $dir) }
    if ($name -match 'cobble') { return $true }
    $hit = Get-ChildItem -LiteralPath $mods -Filter '*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^cobblemon' } | Select-Object -First 1
    return [bool]$hit
}
$targets = @($gameDirs | Select-Object -Unique | Where-Object { Test-Cobbleverse $_ })

if ($targets.Count -eq 0) {
    Say "  I couldn't find your Cobbleverse installation automatically." Yellow
    Say '  In your launcher, open the Cobbleverse instance folder (Modrinth App: the three dots ->'
    Say '  "Open folder"; CurseForge: right-click the modpack -> "Open Folder"), then drag the'
    Say '  "mods" folder into this window and press Enter.'
    Say ''
    $answer = (Read-Host '  mods folder').Trim().Trim('"').Trim("'")
    if (-not $answer -or -not (Test-Path -LiteralPath $answer -PathType Container)) {
        Say "  That folder doesn't exist. Nothing was changed." Red
        exit 1
    }
    if ((Split-Path -Leaf $answer) -ieq 'mods') { $answer = Split-Path -Parent $answer }
    $targets = @($answer)
}

# --- install ------------------------------------------------------------------------------
$ok = 0
$locked = $false
foreach ($dir in $targets) {
    $mods = Join-Path $dir 'mods'
    if (-not (Test-Path -LiteralPath $mods)) { New-Item -ItemType Directory -Path $mods | Out-Null }
    $label = $dir
    try {
        $old = @(Get-ChildItem -LiteralPath $mods -Filter 'windowscraft-*.jar' -File -ErrorAction SilentlyContinue)
        foreach ($o in $old) { if ($o.Name -ne $jar.Name) { Remove-Item -LiteralPath $o.FullName -Force } }
        Copy-Item -LiteralPath $jar.FullName -Destination (Join-Path $mods $jar.Name) -Force
        $removed = @($old | Where-Object { $_.Name -ne $jar.Name } | ForEach-Object { $_.Name })
        if ($removed.Count -gt 0) {
            Say ("  [OK] " + $label + "  (replaced " + ($removed -join ', ') + ")") Green
        } else {
            Say ("  [OK] " + $label) Green
        }
        $ok++
    } catch {
        $e = $_.Exception
        if ($e -is [System.IO.IOException] -or $e.Message -match 'used by another process|being used') {
            $locked = $true
            Say ("  [!!] " + $label + "  - a file is in use") Red
        } else {
            Say ("  [!!] " + $label + "  - " + $e.Message) Red
        }
    }
}

Say ''
if ($locked) {
    Say '  Minecraft seems to be running. Close it completely and run this installer again.' Yellow
    exit 1
}
if ($ok -gt 0) {
    Say '  Done! Start (or restart) Cobbleverse and join the server as usual.' Green
    Say '  Screens other players share will show up on their black concrete walls.'
    Say '  To share your own: look at a black concrete wall and press U.'
    exit 0
}
Say '  Nothing was installed.' Red
exit 1
