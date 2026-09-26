#!/usr/bin/env python3
"""Minimal Chrome DevTools Protocol client (stdlib only) for debugging Steam's CEF UI.

usage: cdp.py <ws-url> <js expression> [--console SECONDS]
Evaluates the expression in the page and prints the result; with --console, also
enables the Runtime/Log domains and prints console messages and exceptions for a while.
"""
import base64, json, os, socket, struct, sys, time, urllib.parse

def connect(url):
    u = urllib.parse.urlparse(url)
    s = socket.create_connection((u.hostname, u.port), timeout=30)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall((f"GET {u.path} HTTP/1.1\r\nHost: {u.hostname}:{u.port}\r\nUpgrade: websocket\r\n"
               f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        resp += s.recv(1)
    if b" 101 " not in resp.split(b"\r\n")[0]:
        sys.exit(resp.decode(errors="replace"))
    return s

def send(s, obj):
    data = json.dumps(obj).encode()
    mask = os.urandom(4)
    n = len(data)
    hdr = bytes([0x81]) + (bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n) if n < 65536
                           else bytes([0x80 | 127]) + struct.pack(">Q", n))
    s.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

def recv_exact(s, n):
    buf = b""
    while len(buf) < n:
        chunk = s.recv(n - len(buf))
        if not chunk:
            raise EOFError
        buf += chunk
    return buf

def recv(s):
    b0, b1 = recv_exact(s, 2)
    n = b1 & 0x7F
    if n == 126:
        n = struct.unpack(">H", recv_exact(s, 2))[0]
    elif n == 127:
        n = struct.unpack(">Q", recv_exact(s, 8))[0]
    data = recv_exact(s, n)
    return json.loads(data) if b0 & 0x0F == 1 else None

url, expr = sys.argv[1], sys.argv[2]
watch = float(sys.argv[4]) if len(sys.argv) > 4 and sys.argv[3] == "--console" else 0
s = connect(url)
if watch:
    send(s, {"id": 1, "method": "Runtime.enable"})
    send(s, {"id": 2, "method": "Log.enable"})
send(s, {"id": 99, "method": "Runtime.evaluate", "params": {"expression": expr, "returnByValue": True}})
end = time.time() + max(watch, 20)
s.settimeout(5)
while time.time() < end:
    try:
        m = recv(s)
    except (socket.timeout, EOFError):
        continue
    if not m:
        continue
    if m.get("id") == 99:
        print("RESULT:", json.dumps(m.get("result"), ensure_ascii=False)[:4000])
        if not watch:
            break
    elif m.get("method") == "Runtime.consoleAPICalled":
        p = m["params"]
        print("console." + p["type"] + ":", " ".join(str(a.get("value", a.get("description", "")))[:300] for a in p["args"]))
    elif m.get("method") == "Runtime.exceptionThrown":
        d = m["params"]["exceptionDetails"]
        print("EXCEPTION:", d.get("text"), json.dumps(d.get("exception", {}).get("description", ""))[:1500])
    elif m.get("method") == "Log.entryAdded":
        e = m["params"]["entry"]
        print("log." + e["level"] + ":", e.get("text", "")[:300], e.get("url", ""))
