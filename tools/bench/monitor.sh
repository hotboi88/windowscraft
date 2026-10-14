#!/bin/bash
# Step 0 benchmark of your own in-game monitor on one Windows PC (Git Bash): a singleplayer test
# world, the real helper and driver, the bench window on the monitor, measured standing in front
# of it and sitting at it (BenchMonitor). It adds a virtual monitor and sits down (your mouse is
# the game's for a minute), so run it when nobody is using the PC.
#   tools/bench/monitor.sh [SECONDS] [NAME]      (EXTRA="-Dwindowscraft.exp.…" for experiments)
set -u
cd "$(dirname "$0")/../.."
OUT=${OUT:-build/bench}
S=${1:-45}
NAME=${2:-monitor}
mkdir -p "$OUT" run-bench/saves/bench
[ -f run-bench/saves/bench/level.dat ] || cp tools/wintest/level.dat run-bench/saves/bench/level.dat
[ -f run-bench/options.txt ] || cp tools/wintest/options.txt run-bench/options.txt
rm -rf run-bench/windowscraft/measurements
# (a clean GPU sharing verdict each time: what a first start on this PC would decide)
rm -f run-bench/windowscraft/gpu-selftest-ext.txt run-bench/windowscraft/gpu-sharing-ext-crashed.txt
java tools/bench/BenchWindow.java 60 80 80 1920 1080 > "$OUT/window-$NAME.log" 2>&1 &
BW=$!
sleep 4
timeout 900 gradle runBenchMonitor -q -PwcArgs="-Dwindowscraft.bench.seconds=$S -Dwindowscraft.bench.name=$NAME ${EXTRA:-}" > "$OUT/host-$NAME.log" 2>&1
kill $BW 2>/dev/null
cp run-bench/windowscraft/measurements/measure-* "$OUT/" 2>/dev/null
echo "== $NAME"
grep -ah "\[bench monitor\]" "$OUT/host-$NAME.log" | sed 's/.*\[bench monitor\]/[bench monitor]/' | tail -12
