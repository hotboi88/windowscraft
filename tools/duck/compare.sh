#!/bin/bash
# How whole the sound comes from each helper while it turns the app down ("You hear it from the
# wall too"), on steady sound and on sound with dead-silent gaps: a windowless player
# (play.py), captured with --duck, read by deliver.py (sound per second, gaps over 30 ms).
# Usage: compare.sh <sounds dir (sounds.sh)> <rounds> <wc-audio.exe>...
# Load the PC however the test needs (the game, the bench) while it runs.
H=$(cd "$(dirname "$0")" && pwd)
D=$1; R=$2; shift 2
STATE="$(cygpath -w "$D")\\duck-state.txt"
for r in $(seq 1 "$R"); do
  for wav in steady gaps; do
    for exe in "$@"; do
      coproc P { python -I "$H/play.py" "$(cygpath -w "$D/$wav.wav")" 24 1.5; }
      read -r pid <&"${P[0]}"
      pid=$(printf %s "$pid" | tr -d '\r')
      echo "== $(basename "$(dirname "$exe")")/$(basename "$exe") $wav round $r"
      DUCK="$STATE" python -I "$H/deliver.py" "$exe" "$pid" 27 | grep -v "mode=\|duck=on\|^shortfall\|^final\|^[0-9]*:[+-]"
      taskkill //F //PID "$pid" >/dev/null 2>&1
      wait "$P_PID" 2>/dev/null
      if [ -f "$D/duck-state.txt" ]; then echo "!! state file left: $(cat "$D/duck-state.txt")"; fi
    done
  done
done
