#!/usr/bin/env python3
"""Evaluate an async JS expression inside the Beam service worker (dev only)."""
import json, sys, urllib.request, websocket
tg = [t for t in json.load(urllib.request.urlopen("http://127.0.0.1:9222/json/list"))
      if t.get("type") == "service_worker" and t["url"].endswith("/background.js")
      and "ajcillno" in t["url"]]
ws = websocket.create_connection(tg[0]["webSocketDebuggerUrl"], suppress_origin=True)
ws.send(json.dumps({"id": 1, "method": "Runtime.evaluate", "params": {
    "expression": f"(async()=>JSON.stringify(await ({sys.argv[1]})))()",
    "awaitPromise": True, "returnByValue": True}}))
while True:
    m = json.loads(ws.recv())
    if m.get("id") == 1:
        break
r = m.get("result", {}).get("result", {})
print(r.get("value") or m)
