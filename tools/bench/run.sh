#!/bin/bash
# Step 0 benchmark on one Windows PC (Git Bash): the multiplayer test's server and two players,
# each measuring (see BenchTest and dev.windowscraft.measure). Uses its own ports, so a real
# server on 25565/25585 can keep running.
#   tools/bench/run.sh SOURCE HEIGHT FPS SECONDS [NAME]     SOURCE = pattern | window | idle
# SHARER_EXTRA / VIEWER_EXTRA="-Dwindowscraft.exp.…" pass experiment switches to one player only;
# VIEWERS=2: a second viewer (Carol, run-carol) watches too, starting with Bob.
# BOB_DELAY=SECONDS (default 8): the viewer starts that long after the sharer (60+: the stream has
# paused with nobody watching before the viewer arrives).
# BENCH_INPUT=1: the viewer also uses the screen (pointer moves and clicks), to time the input path
# (tools/bench/clicks.py: each click from the viewer's send to the bench window getting it).
# SOURCE "monitor": the sharer turns on an in-game monitor and puts the bench window on it.
# "window" opens tools/bench/BenchWindow.java (GPU capture path); "idle" = nobody shares (the mod's
# cost while nothing is on). Reports end up in $OUT (default build/bench).
set -u
cd "$(dirname "$0")/../.."
OUT=${OUT:-build/bench}
PORT=${PORT:-25566}
RELAY=${RELAY:-25586}
WARM=${WARM:-25}
GAMEFPS=${GAMEFPS:-120}
SRC=$1 H=$2 F=$3 S=$4
NAME=${5:-$SRC-${H}p$F}
mkdir -p "$OUT" run-mp-server/config run-alice run-bob run-carol
rm -f run-alice/config/windowscraft/displays.json run-bob/bench-* run-carol/bench-* run-alice/bench-* run-bob/mods/*.wcupdate
rm -rf run-alice/windowscraft/measurements run-bob/windowscraft/measurements run-carol/windowscraft/measurements run-mp-server/windowscraft/measurements
[ -f run-mp-server/server.properties ] || sed "s/^server-port=.*/server-port=$PORT/; s/^query.port=.*/query.port=$PORT/" tools/movie/server.properties > run-mp-server/server.properties
grep -q "^server-ip=127.0.0.1" run-mp-server/server.properties || { sed -i '/^server-ip=/d' run-mp-server/server.properties; echo "server-ip=127.0.0.1" >> run-mp-server/server.properties; }
echo "{\"relayPort\": $RELAY, \"relayEnabled\": true}" > run-mp-server/config/windowscraft-server.json
echo "eula=true" > run-mp-server/eula.txt
for d in run-alice run-bob run-carol; do [ -f $d/options.txt ] || cp tools/movie/options.txt $d/options.txt; done
ROLE_SRC=$SRC; [ "$SRC" = idle ] && ROLE_SRC=pattern
COMMON="-Dwindowscraft.bench.input=${BENCH_INPUT:-0} -Dwindowscraft.bench.source=$ROLE_SRC -Dwindowscraft.bench.height=$H -Dwindowscraft.bench.fps=$F -Dwindowscraft.bench.seconds=$S -Dwindowscraft.bench.warmup=$WARM -Dwindowscraft.bench.name=$NAME -Dwindowscraft.bench.gameFps=$GAMEFPS"
BW=
if [ "$SRC" = window ] || [ "$SRC" = monitor ]; then
  java tools/bench/BenchWindow.java "$F" 0 0 $((H * 16 / 9)) "$H" > "$OUT/window-$NAME.log" 2>&1 &
  BW=$!
  sleep 4
fi
gradle runMpServer -q -PwcArgs="-Dwindowscraft.measure=$S@$((WARM + 35))" > "$OUT/server-$NAME.log" 2>&1 &
for i in $(seq 1 240); do grep -q "Done (" "$OUT/server-$NAME.log" 2>/dev/null && break; sleep 1; done
SHARER_ROLE=sharer; [ "$SRC" = idle ] && SHARER_ROLE=idle
TWO=; [ "${VIEWERS:-1}" = 2 ] && TWO="-Dwindowscraft.bench.viewerDir=run-bob,run-carol"
gradle runMpSharer -Pbench -PmpPort=$PORT -q -PwcArgs="-Dwindowscraft.bench.role=$SHARER_ROLE $COMMON $TWO ${SHARER_EXTRA:-}" > "$OUT/alice-$NAME.log" 2>&1 &
ALICE=$!
sleep ${BOB_DELAY:-8}
gradle runMpViewer -Pbench -PmpPort=$PORT -q -PwcArgs="-Dwindowscraft.bench.role=viewer $COMMON ${VIEWER_EXTRA:-}" > "$OUT/bob-$NAME.log" 2>&1 &
BOB=$!
CAROL=
if [ "${VIEWERS:-1}" = 2 ]; then
  sleep 5
  gradle runMpViewer2 -Pbench -PmpPort=$PORT -q -PwcArgs="-Dwindowscraft.bench.role=viewer $COMMON ${VIEWER_EXTRA:-}" > "$OUT/carol-$NAME.log" 2>&1 &
  CAROL=$!
fi
wait $BOB
[ -n "$CAROL" ] && wait $CAROL
for i in $(seq 1 120); do kill -0 $ALICE 2>/dev/null || break; sleep 1; done
sleep 5
powershell -NoProfile -ExecutionPolicy Bypass -File tools/bench/stop-server.ps1
[ -n "$BW" ] && kill $BW 2>/dev/null
sleep 3
for d in run-alice run-bob run-carol run-mp-server; do cp $d/windowscraft/measurements/measure-*.txt $d/windowscraft/measurements/measure-*.json "$OUT/" 2>/dev/null; done
echo "== $NAME"
grep -ah "\[bench" "$OUT/alice-$NAME.log" "$OUT/bob-$NAME.log" $([ -n "$CAROL" ] && echo "$OUT/carol-$NAME.log") | sed 's/.*\[bench/[bench/' | tail -12
ls "$OUT" | grep "$NAME" | grep measure
[ "${BENCH_INPUT:-0}" = 1 ] && [ -n "$BW" ] && python tools/bench/clicks.py "$OUT/bob-$NAME.log" "$OUT/window-$NAME.log"
