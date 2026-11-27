#!/usr/bin/env python3
"""Screenshot an Android emulator via its console port (no adb). Usage: emu_shot.py <console_port> <outdir>
Prints the PNG path. Requires ~/.emulator_console_auth_token."""
import socket, os, sys, glob, time
port, out = int(sys.argv[1]), sys.argv[2]; os.makedirs(out, exist_ok=True)
tok = open(os.path.expanduser("~/.emulator_console_auth_token")).read().strip()
before = set(glob.glob(out + "/*.png"))
s = socket.create_connection(("127.0.0.1", port), timeout=5)
def rd(t=3):
    s.settimeout(t); b = b""
    try:
        while True:
            c = s.recv(4096)
            if not c: break
            b += c
            if b.rstrip().endswith(b"OK") or b"KO" in b: break
    except socket.timeout: pass
    return b
rd(1)
for c in ("auth " + tok, "screenrecord screenshot " + out, "quit"):
    s.sendall((c + "\r\n").encode()); rd()
time.sleep(0.5)
new = sorted(set(glob.glob(out + "/*.png")) - before, key=os.path.getmtime)
print(new[-1] if new else "NO_SCREENSHOT")
