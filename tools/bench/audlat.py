# Viewer sound latency outside the game: feed an MPEG-TS into the viewer's FFmpeg command in real
# time (each PES when its PTS comes due) and time AAC frame in -> its decoded samples out.
#   python audlat.py STREAM.ts [variant ...]
# Decoder output sample n comes from AAC frame n // 1024 (the AAC decoder emits 1024 samples per
# frame, priming included), so each output sample can be traced to the PES that carried its frame.
import os, shutil, socket, subprocess, threading, sys, time
FF = os.environ.get("FFMPEG") or shutil.which("ffmpeg") or "ffmpeg"  # e.g. the mod's own: <game>/windowscraft/ffmpeg/ffmpeg.exe
ts = sys.argv[1]
variants = sys.argv[2:] or ["game"]
data = open(ts, "rb").read()

def pes_list():
    """[(pts_seconds, bytes, aac_frames)] per PES of any stream, in file order."""
    pmt, vpid, apid, out = None, None, None, []
    cur = {}  # pid -> [pts, bytearray]
    def close(pid):
        if pid in cur:
            pts, b = cur.pop(pid)
            out.append((pts, bytes(b), pid))
    for i in range(0, len(data) - 187, 188):
        p = data[i:i + 188]
        pid = ((p[1] & 0x1f) << 8) | p[2]
        start = p[1] & 0x40
        if pid == 0 and start:
            q = 5 + p[4]
            pmt = ((p[q + 10] & 0x1f) << 8) | p[q + 11]
        elif pid == pmt and start and apid is None:
            q = 5 + p[4]; plen = ((p[q + 10] & 0x0f) << 8) | p[q + 11]; q += 12 + plen
            while q + 5 <= 188:
                if p[q] in (0x1b, 0x24): vpid = ((p[q + 1] & 0x1f) << 8) | p[q + 2]
                if p[q] == 0x0f: apid = ((p[q + 1] & 0x1f) << 8) | p[q + 2]
                q += 5 + (((p[q + 3] & 0x0f) << 8) | p[q + 4])
        if pid in (vpid, apid) and start:
            close(pid)
            afc = (p[3] >> 4) & 3; q = 4
            if afc & 2: q += 1 + p[4]
            b = p[q + 9:q + 14]
            pts = (((b[0] >> 1) & 7) << 30 | b[1] << 22 | (b[2] >> 1) << 15 | b[3] << 7 | b[4] >> 1) / 90000
            cur[pid] = [pts, bytearray()]
        if pid in cur or pid in (0, pmt):
            if pid in cur: cur[pid][1] += p
            elif out: out[-1] = (out[-1][0], out[-1][1] + p, out[-1][2])
    for pid in list(cur): close(pid)
    return out, apid

def adts_frames(ts_bytes):
    """AAC frames (ADTS sync words) in one audio PES's TS packets."""
    payload = bytearray()
    for i in range(0, len(ts_bytes) - 187, 188):
        p = ts_bytes[i:i + 188]
        afc = (p[3] >> 4) & 3; q = 4
        if afc & 2: q += 1 + p[4]
        if p[1] & 0x40: q += 9 + p[q + 8]
        payload += p[q:]
    n, k = 0, 0
    while k + 7 <= len(payload):
        if payload[k] == 0xff and (payload[k + 1] & 0xf6) == 0xf0:
            n += 1; k += ((payload[k + 3] & 3) << 11) | (payload[k + 4] << 3) | (payload[k + 5] >> 5)
        else:
            k += 1
    return n

pes, apid = pes_list()
pes.sort(key=lambda e: e[0])  # each PES goes in when its PTS comes due
frames_per = [adts_frames(b) if pid == apid else 0 for _, b, pid in pes]

def run(variant):
    v = socket.socket(); v.bind(("127.0.0.1", 0)); v.listen(1)
    a = socket.socket(); a.bind(("127.0.0.1", 0)); a.listen(1)
    cmd = [FF, "-hide_banner", "-loglevel", "error", "-nostats", "-fflags", "nobuffer+noparse+nofillin" if "noparse" in variant else "nobuffer",
           "-flags", "low_delay", "-probesize", "500000", "-analyzeduration", "500000", "-f", "mpegts", "-i", "pipe:0",
           "-map", "0:v:0", "-vf", "scale=640:360,format=bgra", "-fps_mode", "passthrough", "-f", "rawvideo", f"tcp://127.0.0.1:{v.getsockname()[1]}",
           "-map", "0:a:0", "-ac", "2", "-ar", "48000"] + (["-flush_packets", "1"] if "flush" in variant else []) + ["-f", "s16le", f"tcp://127.0.0.1:{a.getsockname()[1]}"]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE, stderr=subprocess.DEVNULL)
    def drain():
        c, _ = v.accept()
        while c.recv(1 << 20): pass
    threading.Thread(target=drain, daemon=True).start()
    sent_frames = []  # send time of each AAC frame, in order
    recv = []  # (time, total samples received so far)
    def audio():
        c, _ = a.accept(); total = 0
        while True:
            d = c.recv(1 << 16)
            if not d: break
            total += len(d) // 4; recv.append((time.perf_counter(), total))
    t = threading.Thread(target=audio, daemon=True); t.start()
    t0 = time.perf_counter(); base = pes[0][0]
    for (pts, b, pid), nf in zip(pes, frames_per):
        due = t0 + (pts - base)
        while time.perf_counter() < due: time.sleep(0.0005)
        p.stdin.write(b); p.stdin.flush()
        now = time.perf_counter()
        sent_frames += [now] * nf
    time.sleep(1.0)
    p.stdin.close()
    try: p.wait(timeout=10)
    except Exception: p.kill()
    t.join(2)
    # the last sample of each frame j is n = (j+1)*1024 - 1: when did the total first pass it?
    # frames read while FFmpeg sizes up the stream are dropped (-fflags nobuffer): count from the end
    dropped = round(len(sent_frames) - recv[-1][1] / 1024)
    lat, r = [], 0
    for j, s in enumerate(sent_frames[:-60]):
        if j < dropped: continue
        need = (j + 1 - dropped) * 1024
        while r < len(recv) and recv[r][1] < need: r += 1
        if r == len(recv): break
        lat.append((recv[r][0] - s) * 1000)
    lat = lat[30:]
    lat.sort()
    q = lambda f: lat[min(len(lat) - 1, int(f * len(lat)))]
    print(f"{variant:16s} AAC frames {len(sent_frames)} (start dropped {dropped})  sound in->out median {q(0.5):6.1f}  P95 {q(0.95):6.1f}  P99 {q(0.99):6.1f} ms")

for v in variants:
    run(v)
