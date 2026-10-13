#!/bin/bash
# "Watching a movie over a home internet link": the two-player test with an imitation link in
# front of the viewer. $1 = "kbps queueMs delayMs jitterMs loss freezeOnLoss", $2 = a label.
# Prints the viewer's MOVIE RESULT line (pauses, damaged pictures, pictures per second).
set -u
cd "$(dirname "$0")/../.."
OUT=${OUT:-build/movie}
mkdir -p "$OUT"
rm -f run-alice/config/windowscraft/displays.json run-bob/viewer-* run-bob/mods/*.wcupdate
echo "$1" > run-bob/movie.txt
gradle runMpServer ${GRADLE_FLAGS---offline} -q > "$OUT/server-$2.log" 2>&1 &
for i in $(seq 1 240); do grep -q "Done (" "$OUT/server-$2.log" 2>/dev/null && break; sleep 1; done
gradle runMpSharer -Pxdisplay="$DISPLAY" ${GRADLE_FLAGS---offline} -q > "$OUT/alice-$2.log" 2>&1 &
sleep 5
timeout 600 gradle runMpViewer -Pxdisplay="$DISPLAY" ${GRADLE_FLAGS---offline} -q > "$OUT/bob-$2.log" 2>&1 &
BOB=$!
wait $BOB
for i in $(seq 1 60); do grep -q "SHARER DONE" "$OUT/alice-$2.log" 2>/dev/null && break; sleep 1; done
sleep 3
for p in $(pgrep java); do tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q "windowscraft.autotest.server=true" && kill $p; done
sleep 3
rm -f run-bob/movie.txt
echo "== $2 ($1)"
grep -a "MOVIE RESULT" "$OUT/bob-$2.log" | sed 's/.*MOVIE RESULT/MOVIE RESULT/' || { echo "no result; the viewer's last words:"; grep -a "mp-test\|Exception\|ERROR" "$OUT/bob-$2.log" | tail -15; echo "the sharer's:"; grep -a "mp-test\|Exception\|ERROR" "$OUT/alice-$2.log" | tail -15; tail -5 "$OUT/server-$2.log"; }
grep -a "lowering quality\|trying quality\|watching\|part of your upload\|Streaming '" "$OUT/alice-$2.log" | sed 's/.*(WindowsCraft) /  /' | head -8
cp run-bob/screenshots/mp_viewer_movie.png "$OUT/movie-$2.png" 2>/dev/null || true
