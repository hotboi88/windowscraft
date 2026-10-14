# Ends every benchmark JVM (test server and test players) — only ones started with the benchmark's
# own properties, never another Minecraft or server on this PC.
$procs = Get-CimInstance Win32_Process | Where-Object {
    $_.Name -like 'java*' -and ($_.CommandLine -like '*windowscraft.autotest.server=true*' -or $_.CommandLine -like '*windowscraft.bench.role=*')
}
foreach ($p in $procs) {
    "stopping pid $($p.ProcessId)"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
if (-not $procs) { "nothing to stop" }
