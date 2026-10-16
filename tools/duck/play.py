"""Prints its PID, waits a moment (so the helper is ready before any sound), then plays a WAV on
a loop with no window for N seconds."""
import os, sys, time, winsound
print(os.getpid(), flush=True)
time.sleep(float(sys.argv[3]) if len(sys.argv) > 3 else 1.5)
winsound.PlaySound(sys.argv[1], winsound.SND_FILENAME | winsound.SND_ASYNC | winsound.SND_LOOP)
time.sleep(float(sys.argv[2]))
winsound.PlaySound(None, 0)
