#!/usr/bin/env python3
"""beam-broker — rendezvous between the Beam Android app and browser extensions.

The phone cannot dial into a browser extension, so both ends meet here:

    Android app  --HTTP :8780-->  beam-broker  <--WS :8781--  extension (any PC)

The broker is deliberately dumb. It holds no site credentials and knows nothing
about any website: it registers connected hosts (each a browser running the Beam
extension), routes one JSON-RPC call at a time to the host the phone names, and
hands the reply back. Everything that touches a page lives in the extension,
which runs inside the PC's own logged-in browser and so already has every session.

Bound to loopback + the Tailscale address only, matching every other service on
this box. The bearer token in ~/.config/beam/token is a pairing secret, not a
security boundary -- the tailnet is.
"""
import asyncio
import html
import json
import logging
import os
import pathlib
import secrets
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import websockets
from websockets.asyncio.server import serve

HTTP_PORT = int(os.environ.get("BEAM_HTTP_PORT", "8780"))
WS_PORT = int(os.environ.get("BEAM_WS_PORT", "8781"))
CONF = pathlib.Path(os.environ.get(
    "BEAM_CONF", str(pathlib.Path.home() / ".config/beam")))
TOKEN_FILE = CONF / "token"
RPC_TIMEOUT = float(os.environ.get("BEAM_RPC_TIMEOUT", "25"))

log = logging.getLogger("beam-broker")

PAIR_PAGE = """<!doctype html>
<meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Pair Beam</title>
<style>
 :root{color-scheme:light dark}
 body{font:16px/1.6 system-ui,sans-serif;margin:0;min-height:100vh;display:grid;
      place-items:center;padding:24px;background:#0a0a0a;color:#ececec}
 .card{max-width:22rem;text-align:center}
 h1{font-size:20px;margin:0 0 6px}
 p{color:#a1a1aa;margin:0 0 18px}
 /* Black on white whatever the page theme: an inverted QR is one plenty of
    scanners quietly refuse. */
 #qr{background:#fff;border-radius:12px;padding:10px;width:236px;height:236px;
     box-sizing:border-box;margin:0 auto 14px}
 #qr svg{display:block;width:100%;height:100%}
 .warn{color:#f4a259;font-size:12px;margin:0 0 16px}
 .or{font-size:12px;text-transform:uppercase;letter-spacing:.08em;margin:0 0 14px}
 a.btn{display:block;padding:14px;border-radius:10px;background:#3b82f6;color:#fff;
       font-weight:700;text-decoration:none}
 code{display:block;margin-top:20px;font-size:12px;color:#71717a;word-break:break-all}
</style>
<div class=card>
  <h1>Pair Beam</h1>
  <div id=scan hidden>
    <p>In the app, tap <b>Scan QR code</b> and point the phone at this screen.</p>
    <div id=qr></div>
    <p class=warn>This code carries the pairing token &mdash; don't screenshot or share it.</p>
    <p class=or>or</p>
  </div>
  <!-- data-link, not just href: reading .href back would let the browser
       normalise a URL the app has to see byte for byte. -->
  <a class=btn id=link data-link="__LINK__" href="__LINK__">Tap to pair this phone</a>
  <code>Broker: __BROKER__<br><br>
  Nothing happened? Install the app first, then reload this page.</code>
</div>
<script src="/qrcode.js"></script>
<script>
(function () {
  // No qrcode.js (not found next to the broker) just leaves the tap button.
  if (!window.QR) return;
  var box = document.getElementById("qr");
  try {
    box.innerHTML = QR.svg(document.getElementById("link").dataset.link,
                           {ecl: "M", quiet: 2, label: "Beam pairing code"});
    document.getElementById("scan").hidden = false;
  } catch (e) {
    console.error("could not draw the pairing QR", e);
  }
})();
</script>
"""


def qr_js() -> bytes | None:
    """The vendored QR encoder, shared verbatim with the extension popup.

    Looked up beside the broker first so an install can copy it there, then in
    the repo layout. Missing is not fatal: the /pair page falls back to its
    tap-to-pair button.
    """
    here = pathlib.Path(__file__).resolve().parent
    for cand in (here / "qrcode.js", here.parent / "extension" / "qrcode.js"):
        try:
            return cand.read_bytes()
        except OSError:
            continue
    log.warning("qrcode.js not found - the /pair page will have no QR code")
    return None



def load_token() -> str:
    """Read the shared pairing token, creating one on first run."""
    CONF.mkdir(parents=True, exist_ok=True)
    if TOKEN_FILE.exists():
        tok = TOKEN_FILE.read_text().strip()
        if tok:
            return tok
    tok = secrets.token_urlsafe(24)
    TOKEN_FILE.write_text(tok + "\n")
    TOKEN_FILE.chmod(0o600)
    log.info("generated a new pairing token at %s", TOKEN_FILE)
    return tok


TOKEN = load_token()
QR_JS = qr_js()
# Host:port to advertise when the /pair page is opened on the PC itself --
# a QR saying "localhost" is one the phone can never dial. Filled in at startup.
ADVERTISED_HOST: str | None = None


# ── host registry ────────────────────────────────────────────────────────────
class Host:
    """One connected browser extension."""

    def __init__(self, hid: str, name: str, ws):
        self.id = hid
        self.name = name
        self.ws = ws
        self.connected_at = time.time()
        self.status = {}            # last status the extension pushed
        self._pending: dict[int, asyncio.Future] = {}
        self._next_id = 0

    def info(self) -> dict:
        return {"id": self.id, "name": self.name,
                "connected_at": self.connected_at, "status": self.status}

    async def call(self, method: str, params: dict) -> dict:
        """Send an RPC to this extension and await its reply."""
        self._next_id += 1
        rid = self._next_id
        fut = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        try:
            await self.ws.send(json.dumps(
                {"id": rid, "method": method, "params": params}))
            return await asyncio.wait_for(fut, timeout=RPC_TIMEOUT)
        except asyncio.TimeoutError:
            raise RuntimeError(f"host {self.name!r} did not answer {method!r} "
                               f"within {RPC_TIMEOUT:g}s")
        finally:
            self._pending.pop(rid, None)

    def resolve(self, msg: dict):
        """Deliver a reply (or an unsolicited status push) from the extension."""
        if msg.get("type") == "status":
            self.status = msg.get("status") or {}
            return
        fut = self._pending.get(msg.get("id"))
        if fut and not fut.done():
            if "error" in msg and msg["error"]:
                fut.set_exception(RuntimeError(str(msg["error"])))
            else:
                fut.set_result(msg.get("result"))


HOSTS: dict[str, Host] = {}
LOOP: asyncio.AbstractEventLoop | None = None


# ── websocket side: extensions connect here ──────────────────────────────────
async def ws_handler(ws):
    q = urllib.parse.parse_qs(urllib.parse.urlparse(ws.request.path).query)
    if (q.get("token") or [""])[0] != TOKEN:
        await ws.close(code=4401, reason="bad token")
        return
    name = (q.get("name") or ["unnamed PC"])[0][:64]
    hid = (q.get("id") or [secrets.token_hex(4)])[0][:32]

    host = Host(hid, name, ws)
    HOSTS[hid] = host
    log.info("host connected: %s (%s) [%d online]", name, hid, len(HOSTS))
    # Tell the extension which address a phone should be handed. A browser on
    # this very machine is usually configured with "127.0.0.1", which is exactly
    # the address a phone cannot use -- and only the broker knows the good one.
    if ADVERTISED_HOST:
        await ws.send(json.dumps({"type": "hello", "pairHost": ADVERTISED_HOST}))
    try:
        async for raw in ws:
            try:
                host.resolve(json.loads(raw))
            except json.JSONDecodeError:
                log.warning("host %s sent non-JSON", name)
    except websockets.ConnectionClosed:
        pass
    finally:
        if HOSTS.get(hid) is host:
            del HOSTS[hid]
        log.info("host disconnected: %s [%d online]", name, len(HOSTS))


# ── HTTP side: the phone talks here ──────────────────────────────────────────
def pick_host(hid: str | None) -> Host:
    if hid:
        h = HOSTS.get(hid)
        if not h:
            raise KeyError(f"no host {hid!r} connected")
        return h
    if not HOSTS:
        raise KeyError("no PC is connected - open a browser with the Beam "
                       "extension installed")
    # Default to the most recently connected host so a freshly-opened PC wins.
    return max(HOSTS.values(), key=lambda h: h.connected_at)


class Handler(BaseHTTPRequestHandler):
    server_version = "beam-broker/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        log.debug("http %s", fmt % args)

    def _send(self, code: int, payload: dict):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authed(self) -> bool:
        auth = self.headers.get("Authorization", "")
        tok = auth[7:].strip() if auth.lower().startswith("bearer ") else ""
        if not tok:
            tok = (urllib.parse.parse_qs(
                urllib.parse.urlparse(self.path).query).get("token") or [""])[0]
        if secrets.compare_digest(tok, TOKEN):
            return True
        self._send(401, {"error": "bad or missing token"})
        return False

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except json.JSONDecodeError:
            return {}

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        if u.path == "/api/health":          # unauthenticated liveness probe
            return self._send(200, {"ok": True, "hosts": len(HOSTS)})
        if u.path in ("/pair", "/pair/"):
            # One-tap pairing: hands the phone a deep link so the token never has
            # to be retyped. Unauthenticated on purpose -- reaching this page at
            # all already means the visitor is on the tailnet.
            host = self.headers.get("Host") or f"127.0.0.1:{HTTP_PORT}"
            # Scanning changes who opens this page: it is now usually the PC,
            # where Host is "localhost". Hand out the reachable address instead.
            if (ADVERTISED_HOST and host.split(":")[0] in
                    ("localhost", "127.0.0.1", "[::1]", "::1")):
                host = ADVERTISED_HOST
            link = (f"beam://pair?broker={urllib.parse.quote(host)}"
                    f"&token={urllib.parse.quote(TOKEN)}")
            # Host is caller-controlled, so it is escaped on the way into the
            # markup; the DOM hands the link back to the QR encoder decoded.
            page = (PAIR_PAGE.replace("__LINK__", html.escape(link))
                             .replace("__BROKER__", html.escape(host)))
            body = page.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        if u.path == "/qrcode.js" and QR_JS:
            # Served unauthenticated for the same reason /pair is: it is a static
            # asset, and being on the tailnet is the access check.
            self.send_response(200)
            self.send_header("Content-Type", "application/javascript; charset=utf-8")
            self.send_header("Content-Length", str(len(QR_JS)))
            self.end_headers()
            return self.wfile.write(QR_JS)
        if not self._authed():
            return
        if u.path == "/api/hosts":
            return self._send(200, {"hosts": [h.info() for h in
                                              sorted(HOSTS.values(),
                                                     key=lambda x: -x.connected_at)]})
        q = urllib.parse.parse_qs(u.query)
        if u.path == "/api/rpc":             # GET form, handy for curl
            params = {k: v[0] for k, v in q.items()
                      if k not in ("token", "host", "method")}
            return self._rpc((q.get("host") or [None])[0],
                             (q.get("method") or [""])[0], params)
        self._send(404, {"error": "no such endpoint"})

    def do_POST(self):
        if not self._authed():
            return
        u = urllib.parse.urlparse(self.path)
        if u.path != "/api/rpc":
            return self._send(404, {"error": "no such endpoint"})
        b = self._body()
        self._rpc(b.get("host"), b.get("method") or "", b.get("params") or {})

    def _rpc(self, hid, method: str, params: dict):
        if not method:
            return self._send(400, {"error": "missing method"})
        try:
            host = pick_host(hid)
        except KeyError as e:
            return self._send(503, {"error": e.args[0]})
        fut = asyncio.run_coroutine_threadsafe(host.call(method, params), LOOP)
        try:
            return self._send(200, {"host": host.id, "result":
                                    fut.result(timeout=RPC_TIMEOUT + 5)})
        except Exception as e:
            return self._send(502, {"error": str(e)})


# ── bind helpers ─────────────────────────────────────────────────────────────
def tailscale_ip() -> str | None:
    """The tailnet address to bind, so the phone can reach us but the LAN can't."""
    try:
        import subprocess
        out = subprocess.run(["tailscale", "ip", "-4"], capture_output=True,
                             text=True, timeout=5).stdout.strip().splitlines()
        return out[0].strip() if out else None
    except Exception:
        return None


class DualStackHTTP(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def serve_http(bind: str):
    srv = DualStackHTTP((bind, HTTP_PORT), Handler)
    log.info("http api on %s:%d", bind, HTTP_PORT)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


async def bind_tailnet_later(ws_servers: list):
    """Tailscale came up after us (normal at boot: the user unit can start
    before tailscaled has an address). Keep looking, and add the tailnet
    listeners the moment an address appears -- without this the broker sat
    loopback-only for days and the phone could never reach it."""
    global ADVERTISED_HOST
    while True:
        await asyncio.sleep(15)
        ts = tailscale_ip()
        if not ts:
            continue
        try:
            serve_http(ts)
            ws_servers.append(await serve(ws_handler, ts, WS_PORT, ping_interval=20,
                                          ping_timeout=20, max_size=4 * 1024 * 1024))
        except OSError as e:
            log.error("tailscale is up at %s but binding failed - %s", ts, e)
            continue
        ADVERTISED_HOST = f"{ts}:{HTTP_PORT}"
        log.info("tailscale came up - now also listening on %s", ts)
        return


async def main():
    global LOOP, ADVERTISED_HOST
    LOOP = asyncio.get_running_loop()
    logging.basicConfig(
        level=os.environ.get("BEAM_LOG", "INFO").upper(),
        format="%(asctime)s %(levelname)s %(message)s")

    binds = ["127.0.0.1"]
    # At boot the unit often starts before tailscaled has an address; give it
    # a short grace period before settling for loopback.
    ts = None
    for _ in range(10):
        ts = tailscale_ip()
        if ts:
            break
        await asyncio.sleep(3)
    if ts:
        binds.append(ts)
        ADVERTISED_HOST = f"{ts}:{HTTP_PORT}"
    else:
        log.warning("no tailscale address yet - binding loopback, will add the "
                    "tailnet address when it appears")

    for b in binds:
        try:
            serve_http(b)
        except OSError as e:
            log.error("cannot bind http %s:%d - %s", b, HTTP_PORT, e)
            sys.exit(1)

    log.info("ws endpoint on %s:%d", ",".join(binds), WS_PORT)
    log.info("pairing token: %s", TOKEN_FILE)
    late: list = []
    async with serve(ws_handler, binds, WS_PORT, ping_interval=20,
                     ping_timeout=20, max_size=4 * 1024 * 1024):
        if not ts:
            asyncio.create_task(bind_tailnet_later(late))
        await asyncio.Future()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
