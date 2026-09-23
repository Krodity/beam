#!/usr/bin/env python3
"""Tiny CDP helper for development: reload the unpacked extension in a running
Chromium (--remote-debugging-port) and call the broker like the phone does.

    tools/cdp.py reload              # (re)load extension/ into Chromium on :9222
    tools/cdp.py rpc status          # POST /api/rpc {"method": "status"}
    tools/cdp.py rpc open url=https://…
"""
import json, os, pathlib, sys, urllib.request
import websocket

ROOT = pathlib.Path(__file__).resolve().parent.parent
PORT = int(os.environ.get("CDP_PORT", "9222"))


def cdp(method, params=None):
    v = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json/version"))
    ws = websocket.create_connection(v["webSocketDebuggerUrl"], suppress_origin=True)
    ws.send(json.dumps({"id": 1, "method": method, "params": params or {}}))
    while True:
        m = json.loads(ws.recv())
        if m.get("id") == 1:
            return m


def rpc(method, **params):
    tok = (pathlib.Path.home() / ".config/beam/token").read_text().strip()
    host = params.pop("host", None)
    body = json.dumps({"method": method, "params": params, **({"host": host} if host else {})}).encode()
    req = urllib.request.Request("http://127.0.0.1:8780/api/rpc", body,
                                 {"Authorization": f"Bearer {tok}", "Content-Type": "application/json"})
    try:
        return json.load(urllib.request.urlopen(req, timeout=40))
    except urllib.error.HTTPError as e:
        return json.load(e)


if __name__ == "__main__":
    if sys.argv[1] == "reload":
        print(cdp("Extensions.loadUnpacked", {"path": str(ROOT / "extension")}))
    elif sys.argv[1] == "rpc":
        kv = dict(a.split("=", 1) for a in sys.argv[3:])
        print(json.dumps(rpc(sys.argv[2], **kv), indent=1))
