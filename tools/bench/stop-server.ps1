# Ends the benchmark's test server (only the JVM started with -Dwindowscraft.autotest.server=true).
$procs = Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'java*' -and $_.CommandLine -like '*windowscraft.autotest.server=true*' }
foreach ($p in $procs) {
    "stopping test server pid $($p.ProcessId)"
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}
if (-not $procs) { "no test server running" }
