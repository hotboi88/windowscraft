# Viewer decode latency outside the game: feed an MPEG-TS into the viewer's FFmpeg command in real
# time (each picture's packets when its PTS comes due) and time picture-in -> decoded-picture-out.
#   python declat.py STREAM.ts W H [variant ...]
# Variants are the viewer's command (game) with parts changed, joined with "-": noparse (0.15.1's
# -fflags on the viewer), sw (no -hwaccel), t1 (one decoder thread), ft1, half, nv12, noaudio,
# flush, direct. A game-like stream (as StreamPublisher writes it):
#   ffmpeg -f lavfi -i testsrc2=s=1920x1080:r=30 -f lavfi -i sine=r=48000 -t 12 -map 0:v -map 1:a
#     -c:v h264_nvenc -preset p1 -tune ull -zerolatency 1 -delay 0 -rc cbr -b:v 10000k -g 30 -bf 0
#     -c:a aac -b:a 192k -max_interleave_delta 1 -f mpegts -mpegts_flags +resend_headers
#     -omit_video_pes_length 0 -muxdelay 0 -muxpreload 0 s30.ts
# (Step 0: the delay is counted in pictures — run the same stream at 60/30/10 per second.)
import os, shutil, socket, subprocess, threading, sys, time, statistics
FF = os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg"  # e.g. the mod's own: <game>/windowscraft/ffmpeg/ffmpeg.exe
ts, W, H = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
variants = sys.argv[4:] or ["game"]
data = open(ts, "rb").read()

def pictures():
    """(pts_seconds, bytes) per video picture: every packet up to the next video PES start."""
    pmt, vpid, out, cur, cur_pts = None, None, [], bytearray(), None
    for i in range(0, len(data) - 187, 188):
        p = data[i:i + 188]
        pid = ((p[1] & 0x1f) << 8) | p[2]
        start = p[1] & 0x40
        if pid == 0 and start:
            q = 5 + p[4]
            pmt = ((p[q + 10] & 0x1f) << 8) | p[q + 11]
        elif pid == pmt and start and vpid is None:
            q = 5 + p[4]; plen = ((p[q + 10] & 0x0f) << 8) | p[q + 11]; q += 12 + plen
            while q + 5 <= 188:
                if p[q] in (0x1b, 0x24): vpid = ((p[q + 1] & 0x1f) << 8) | p[q + 2]; break
                q += 5 + (((p[q + 3] & 0x0f) << 8) | p[q + 4])
        if pid == vpid and start:
            afc = (p[3] >> 4) & 3; q = 4
            if afc & 2: q += 1 + p[4]
            if cur_pts is not None: out.append((cur_pts, bytes(cur)))
            b = p[q + 9:q + 14]
            cur_pts = (((b[0] >> 1) & 7) << 30 | b[1] << 22 | (b[2] >> 1) << 15 | b[3] << 7 | b[4] >> 1) / 90000
            cur = bytearray()
        cur += p
    if cur_pts is not None: out.append((cur_pts, bytes(cur)))
    return out

pics = pictures()

def run(variant):
    def listener():
        s = socket.socket(); s.bind(("127.0.0.1", 0)); s.listen(1); return s
    v, a = listener(), listener()
    ow, oh = (W, H) if "half" not in variant else (W // 2, H // 2)
    vf = f"scale={ow}:{oh},format=bgra"
    cmd = [FF, "-hide_banner", "-loglevel", "error", "-nostats", "-fflags", "nobuffer+noparse+nofillin" if "noparse" in variant else "nobuffer", "-flags", "low_delay", "-probesize", "500000", "-analyzeduration", "500000"]
    if "sw" not in variant: cmd += ["-hwaccel", "auto"]
    if "t1" in variant: cmd += ["-threads", "1"]
    if "direct" in variant: cmd += ["-avioflags", "direct"]
    cmd += ["-f", "mpegts", "-i", "pipe:0"]
    if "ft1" in variant: cmd += ["-filter_threads", "1"]
    if "nv12" in variant: vf = f"scale={ow}:{oh},format=nv12"
    cmd += ["-map", "0:v:0", "-vf", vf, "-fps_mode", "passthrough", "-enc_time_base:v", "demux"] + (["-flush_packets", "1"] if "flush" in variant else []) + ["-f", "rawvideo", f"tcp://127.0.0.1:{v.getsockname()[1]}"]
    if "noaudio" not in variant:
        cmd += ["-map", "0:a:0?", "-ac", "2", "-ar", "48000", "-f", "s16le", f"tcp://127.0.0.1:{a.getsockname()[1]}"]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
    size = ow * oh * (4 if "nv12" not in variant else 1.5)
    size = int(size)
    sent, got = [], []
    def pump():
        c, _ = v.accept()
        buf = 0
        while True:
            d = c.recv(1 << 20)
            if not d: break
            buf += len(d)
            while buf >= size:
                buf -= size; got.append(time.perf_counter())
    def drain():
        try:
            c, _ = a.accept()
            while c.recv(65536): pass
        except Exception: pass
    threading.Thread(target=pump, daemon=True).start()
    threading.Thread(target=drain, daemon=True).start()
    t0 = time.perf_counter(); base = pics[0][0]
    for pts, b in pics:
        due = t0 + (pts - base)
        while time.perf_counter() < due: time.sleep(0.0005)
        p.stdin.write(b); p.stdin.flush(); sent.append(time.perf_counter())
    time.sleep(1.0)
    p.stdin.close()
    try: p.wait(timeout=10)
    except Exception: p.kill()
    time.sleep(0.3)
    n = min(len(sent), len(got))
    # pictures read while FFmpeg sizes up the stream at the start are dropped (-fflags nobuffer):
    # match from the end instead (everything after the start comes out, in order)
    lat = [(got[len(got) - 1 - k] - sent[len(sent) - 1 - k]) * 1000 for k in range(n - 30)]
    lat.sort()
    q = lambda f: lat[min(len(lat) - 1, int(f * len(lat)))]
    print(f"{variant:16s} in {len(sent)} out {len(got)}  decode latency median {q(0.5):6.1f}  P95 {q(0.95):6.1f}  P99 {q(0.99):6.1f} ms")

for v in variants:
    run(v)
