#!/bin/bash
# "Watching a movie from far away", on Windows (Git Bash): the two-player test in movie mode — the
# test pattern with sync beeps, full data rate — with relay.NetSim's bad line between the relay
# and the viewer (both ways). Test ports (25566 / relay 25586): a real server on 25565 keeps running.
#   tools/movie/windows.sh "rtt=100,jitter=60,loss=0.5" LABEL      ("" = no simulated line)
# Prints the viewer's MOVIE RESULT (pauses, damaged pictures, pictures/s) and MOVIE LIP SYNC
# (sound after picture over every beep, how often the sound card ran dry).
set -u
cd "$(dirname "$0")/../.."
OUT=${OUT:-build/movie}
PORT=${PORT:-25566}
RELAY=${RELAY:-25586}
mkdir -p "$OUT" run-mp-server/config run-alice run-bob
rm -f run-alice/config/windowscraft/displays.json run-bob/viewer-* run-bob/mods/*.wcupdate
[ -f run-mp-server/server.properties ] || sed "s/^server-port=.*/server-port=$PORT/; s/^query.port=.*/query.port=$PORT/" tools/movie/server.properties > run-mp-server/server.properties
grep -q "^server-ip=127.0.0.1" run-mp-server/server.properties || { sed -i '/^server-ip=/d' run-mp-server/server.properties; echo "server-ip=127.0.0.1" >> run-mp-server/server.properties; }
echo "{\"relayPort\": $RELAY, \"relayEnabled\": true}" > run-mp-server/config/windowscraft-server.json
echo "eula=true" > run-mp-server/eula.txt
for d in run-alice run-bob; do [ -f $d/options.txt ] || cp tools/movie/options.txt $d/options.txt; done
echo "0 0 0 0 0 ${FREEZE:-true}" > run-bob/movie.txt # (no imitation link in the viewer: the server's NetSim)
SIM=""; [ -n "$1" ] && SIM="-Dwindowscraft.exp.netsim=$1"
gradle runMpServer -q -PwcArgs="$SIM" > "$OUT/server-$2.log" 2>&1 &
for i in $(seq 1 240); do grep -q "Done (" "$OUT/server-$2.log" 2>/dev/null && break; sleep 1; done
QUIET="-Dwindowscraft.test.silentSpeakers=true" # (the test players' sound timed as always, not out of the speakers)
gradle runMpSharer -PmpPort=$PORT -q -PwcArgs="$QUIET ${SHARER_EXTRA:-}" > "$OUT/alice-$2.log" 2>&1 &
sleep 5
timeout 600 gradle runMpViewer -PmpPort=$PORT -q -PwcArgs="$QUIET ${VIEWER_ARGS:-}" > "$OUT/bob-$2.log" 2>&1
for i in $(seq 1 60); do grep -q "SHARER DONE" "$OUT/alice-$2.log" 2>/dev/null && break; sleep 1; done
sleep 3
powershell -NoProfile -ExecutionPolicy Bypass -File tools/bench/stop-server.ps1 > /dev/null
sleep 3
rm -f run-bob/movie.txt
echo "== $2 (${1:-no simulated line})"
grep -a "MOVIE RESULT\|MOVIE LIP SYNC" "$OUT/bob-$2.log" | sed 's/.*MOVIE/MOVIE/' || echo "no result"
