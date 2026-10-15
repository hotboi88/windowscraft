#!/bin/bash
# A copy of a modded server — its mods, config and libraries, never its world — on its own ports,
# with offline logins and a flat test world, plus the test mod (it builds the test scene). For two
# launcher-style clients (run.sh with SERVER=127.0.0.1:PORT) to test with the modpack both sides.
#   tools/prodclient/server.sh SERVER_DIR [JVM args]   (PORT 25567, RELAY 25587, XMX 6G, RUN run-prodserver)
set -eu
cd "$(dirname "$0")/../.."
SRC=$1
shift
RUN=${RUN:-run-prodserver}
PORT=${PORT:-25567}
RELAY=${RELAY:-25587}
gradle -q build -x test remapTestmodJar
VER=$(sed -n 's/^mod_version=//p' gradle.properties)
mkdir -p "$RUN"
rm -rf "$RUN/mods" "$RUN/config"
cp -r "$SRC/mods" "$RUN/mods"
rm -f "$RUN"/mods/windowscraft-*.jar
cp "build/libs/windowscraft-$VER+mc1.21.1.jar" "build/libs/windowscraft-$VER+mc1.21.1-testmod.jar" "$RUN/mods/"
cp -r "$SRC/config" "$RUN/config"
for d in libraries versions defaultconfigs datapacks; do [ -d "$SRC/$d" ] && [ ! -d "$RUN/$d" ] && cp -r "$SRC/$d" "$RUN/$d"; done
cp "$SRC/fabric-server-launch.jar" "$RUN/"
[ -f "$SRC/fabric-server-launcher.properties" ] && cp "$SRC/fabric-server-launcher.properties" "$RUN/"
echo "eula=true" > "$RUN/eula.txt"
# its own ports, local only, offline logins, nobody's whitelist, a flat test world
sed -e "s/^server-port=.*/server-port=$PORT/" -e "s/^query.port=.*/query.port=$PORT/" -e "s/^online-mode=.*/online-mode=false/" \
    -e "s/^level-name=.*/level-name=testworld/" -e "s/^view-distance=.*/view-distance=8/" -e "s/^simulation-distance=.*/simulation-distance=6/" \
    -e "s/^white-list=.*/white-list=false/" -e "s/^enforce-whitelist=.*/enforce-whitelist=false/" -e "/^server-ip=/d" -e "/^level-type=/d" \
    "$SRC/server.properties" > "$RUN/server.properties"
printf 'server-ip=127.0.0.1\nlevel-type=minecraft\\:flat\n' >> "$RUN/server.properties"
echo "{\"relayPort\": $RELAY, \"relayEnabled\": true}" > "$RUN/config/windowscraft-server.json"
cd "$RUN" # (mods write next to wherever the server was started)
exec java -Xmx${XMX:-6G} -Dwindowscraft.autotest.server=true "$@" -jar fabric-server-launch.jar nogui
