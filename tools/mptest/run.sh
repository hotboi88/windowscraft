#!/bin/bash
# The two-player test: a dedicated server, "Alice" shares the test pattern, "Bob" watches it,
# hears it, measures the delay and the A/V offset, asks to use Alice's screen and uses it from the
# chair. Fails if either player reports a failed check. Uses tools/movie's server and options.
set -u
cd "$(dirname "$0")/../.."
OUT=${OUT:-build/mptest}
mkdir -p "$OUT"
rm -f run-alice/config/windowscraft/displays.json run-bob/viewer-* run-bob/mods/*.wcupdate run-bob/movie.txt
mkdir -p run-mp-server run-alice run-bob
[ -f run-mp-server/server.properties ] || cp tools/movie/server.properties run-mp-server/server.properties
[ -f run-mp-server/eula.txt ] || echo "eula=true" > run-mp-server/eula.txt
for d in run-alice run-bob; do [ -f $d/options.txt ] || cp tools/movie/options.txt $d/options.txt; done
X=${DISPLAY:+-Pxdisplay=$DISPLAY}
gradle runMpServer ${GRADLE_FLAGS---offline} -q > "$OUT/server.log" 2>&1 &
for i in $(seq 1 240); do grep -q "Done (" "$OUT/server.log" 2>/dev/null && break; sleep 1; done
gradle runMpSharer $X ${GRADLE_FLAGS---offline} -q > "$OUT/alice.log" 2>&1 &
sleep 5
timeout 900 gradle runMpViewer $X -PforceCatchup ${GRADLE_FLAGS---offline} -q > "$OUT/bob.log" 2>&1
for i in $(seq 1 90); do grep -q "SHARER DONE" "$OUT/alice.log" 2>/dev/null && break; sleep 1; done
sleep 3
for p in $(pgrep java 2>/dev/null); do tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q "windowscraft.autotest.server=true" && kill $p; done
sleep 2
grep -ah "CHECK FAILED\|A/V offset\|glass-to-glass\|SHARER DONE\|VIEWER DONE\|input from Bob" "$OUT/alice.log" "$OUT/bob.log" | sed 's/.*\] //' | cut -c1-220
cp run-bob/screenshots/mp_viewer_*.png "$OUT/" 2>/dev/null || true
grep -qa "SHARER DONE" "$OUT/alice.log" && grep -qa "VIEWER DONE" "$OUT/bob.log" || { echo "a player didn't finish"; exit 1; }
! grep -qa "DONE WITH FAILURES" "$OUT/alice.log" "$OUT/bob.log"
