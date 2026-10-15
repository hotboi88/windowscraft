#!/bin/bash
# A launcher-style client — the real Minecraft jar, Fabric Loader remapping it at start, mods
# loaded the way the Minecraft launcher loads them — with a COPY of a modpack instance, so
# WindowsCraft can be tested with mods the dev client can't load (Sodium, Iris + a shader pack…).
# Offline, singleplayer, in its own folder: the instance's saves and settings are never touched.
#   tools/prodclient/run.sh INSTANCE_DIR [-Dwindowscraft.… JVM args for the test mod]
# e.g. the in-game monitor bench with the owner's mods:
#   BW=1 tools/prodclient/run.sh ~/Cobbleverse -Dwindowscraft.bench.monitor=true
# RUN (default run-prod) is the copy; XMX (default 8G) its memory; BW=1 opens tools/bench's window;
# PLAYER (default WCTest) the offline name; SERVER=HOST:PORT joins that server (tools/prodclient/
# server.sh) instead of opening the test world.
set -eu
cd "$(dirname "$0")/../.."
SRC=$1
shift
RUN=${RUN:-run-prod}
gradle -q build -x test prodClientLibs remapTestmodJar
VER=$(sed -n 's/^mod_version=//p' gradle.properties)
mkdir -p "$RUN"
# the instance's mods (theirs, with this build's WindowsCraft and the test mod), config, shader and
# resource packs and options — fresh each time (a test run changes config); never its saves
rm -rf "$RUN/mods" "$RUN/config"
cp -r "$SRC/mods" "$RUN/mods"
rm -f "$RUN"/mods/windowscraft-*.jar
cp "build/libs/windowscraft-$VER+mc1.21.1.jar" "build/libs/windowscraft-$VER+mc1.21.1-testmod.jar" "$RUN/mods/"
cp -r "$SRC/config" "$RUN/config"
# SODIUM_NO_ERROR=0: the copy without Sodium's "no error" OpenGL context (OpenGL then reports errors)
[ "${SODIUM_NO_ERROR:-}" = 0 ] && [ -f "$RUN/config/sodium-options.json" ] && sed -i 's/"use_no_error_g_l_context": *true/"use_no_error_g_l_context": false/' "$RUN/config/sodium-options.json"
# GPU_SHARING=off: the copy with direct GPU sharing turned off (Advanced, Off) — the CPU route
if [ "${GPU_SHARING:-}" = off ] && [ -f "$RUN/config/windowscraft/settings.json" ]; then
  python - "$RUN/config/windowscraft/settings.json" <<'EOF'
import json, sys
p = sys.argv[1]; o = json.load(open(p))
perf = o.setdefault('perf', {'mode': 'advanced', 'picks': {}})
perf['mode'] = 'advanced'; picks = perf.setdefault('picks', {})
picks['gpuSharing'] = False
for k in ('offscreenPause', 'distanceFpsLimit'): # (an old file's own picks, as WindowsCraft would read them)
    if o.get(k) is False: picks.setdefault(k, False)
json.dump(o, open(p, 'w'))
EOF
fi
for d in shaderpacks resourcepacks; do [ -d "$SRC/$d" ] && [ ! -d "$RUN/$d" ] && cp -r "$SRC/$d" "$RUN/$d"; done
[ -f "$SRC/options.txt" ] && sed 's/^fullscreen:.*/fullscreen:false/; s/^pauseOnLostFocus:.*/pauseOnLostFocus:false/' "$SRC/options.txt" > "$RUN/options.txt"
# a flat test world of its own; GPU sharing decided afresh, as on a first start
mkdir -p "$RUN/saves/bench"
[ -f "$RUN/saves/bench/level.dat" ] || cp tools/wintest/level.dat "$RUN/saves/bench/level.dat"
rm -f "$RUN"/windowscraft/gpu-selftest*.txt "$RUN"/windowscraft/gpu-sharing*-crashed.txt
LOOM=~/.gradle/caches/fabric-loom
INTER=$(ls ~/.gradle/caches/modules-2/files-2.1/net.fabricmc/intermediary/1.21.1/*/intermediary-1.21.1-v2.jar | head -1)
CP=""
for j in build/prodclient/libs/*.jar "$LOOM/1.21.1/minecraft-client.jar" "$INTER"; do CP="$CP;$(cygpath -w "$(realpath "$j")")"; done
GAMEDIR=$(cygpath -w "$(realpath "$RUN")") ASSETS=$(cygpath -w "$(realpath "$LOOM/assets")")
BWPID=
if [ "${BW:-0}" = 1 ]; then
  java tools/bench/BenchWindow.java 60 80 80 ${BW_W:-1920} ${BW_H:-1080} > "$RUN/bench-window.log" 2>&1 &
  BWPID=$!
  sleep 3
fi
set +e
cd "$RUN" # (some mods write next to wherever the game was started, not into its folder)
# (the test mod's ProdStart opens the world once the game has finished loading: a modpack's own
# loading screen and --quickPlaySingleplayer can wait on each other for ever)
PLAYER=${PLAYER:-WCTest}
UUID=$(python -c "import hashlib,sys,uuid; h=bytearray(hashlib.md5(('OfflinePlayer:'+sys.argv[1]).encode()).digest()); h[6]=h[6]&0x0f|0x30; h[8]=h[8]&0x3f|0x80; print(uuid.UUID(bytes=bytes(h)).hex)" "$PLAYER")
START="-Dwindowscraft.prod.world=bench"
[ -n "${SERVER:-}" ] && START="-Dwindowscraft.prod.server=$SERVER"
java -Xmx${XMX:-8G} -Xms3G -XX:+UseG1GC -XX:MaxGCPauseMillis=50 $START "$@" \
  -Dorg.lwjgl.system.SharedLibraryExtractPath="$GAMEDIR\\lwjgl-natives" \
  -cp "${CP:1}" net.fabricmc.loader.impl.launch.knot.KnotClient \
  --username "$PLAYER" --uuid "$UUID" --accessToken 0 --userType legacy \
  --version 1.21.1 --versionType release --gameDir "$GAMEDIR" \
  --assetsDir "$ASSETS" --assetIndex 1.21.1-17 \
  --width 1600 --height 900
code=$?
[ -n "$BWPID" ] && kill $BWPID 2>/dev/null
exit $code
