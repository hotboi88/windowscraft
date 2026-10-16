#!/bin/bash
# Makes the test sounds (float WAVs, quiet: nothing you'd notice if one plays un-ducked) into <dir>:
#   steady.wav  a faint hiss with a beep at the start of every second (never digital silence)
#   gaps.wav    the same beep with dead silence between (sound starts after silence every second)
#   tone.wav    a steady -63 dB tone: below the "too loud" guard, so if it were ever amplified
#               un-ducked it would come out near full scale (safety.py looks for that)
#   faint.wav   -100 dB hiss: another app, playing at the same time
# Usage: sounds.sh <ffmpeg.exe> <dir>
set -e
FF=$1; D=$2; mkdir -p "$D"
beep="sine=frequency=1000:sample_rate=48000:duration=60,volume='if(lt(mod(t,1),0.1),1,0)':eval=frame"
"$FF" -v error -y -f lavfi -i "$beep" -f lavfi -i "anoisesrc=color=pink:amplitude=0.0002:sample_rate=48000:duration=60" \
  -filter_complex "[0][1]amix=inputs=2:normalize=0,volume=0.02,aformat=channel_layouts=stereo" -c:a pcm_f32le "$D/steady.wav"
"$FF" -v error -y -f lavfi -i "$beep" -af "volume=0.02,aformat=channel_layouts=stereo" -c:a pcm_f32le "$D/gaps.wav"
"$FF" -v error -y -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=60" -af "volume=0.008,aformat=channel_layouts=stereo" -c:a pcm_f32le "$D/tone.wav"
"$FF" -v error -y -f lavfi -i "anoisesrc=color=pink:amplitude=0.00001:sample_rate=48000:duration=60" -af "aformat=channel_layouts=stereo" -c:a pcm_f32le "$D/faint.wav"
ls "$D"
