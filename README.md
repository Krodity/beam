# Beam

Browse any site on your phone, and whatever you press play on plays in the
browser on your PC instead. The phone then becomes the remote.

```
Beam app (WebView)  --HTTP :8780-->  beam-broker  <--WS :8781--  Beam extension
  browse, tap a video                 (tailnet)                  (Chrome/Chromium, any PC)
```

What gets sent is the page's **URL**, not the video stream. The PC opens it in
its own browser, where you're already logged in, so DRM and members-only
content (Crunchyroll, Netflix, …) plays fine there.

Three parts:

| Part | Where it runs | What it does |
|---|---|---|
| **Beam app** (`android/`) | Android phone | A browser with a start page, a "Play on PC" button, and a full remote |
| **beam-broker** (`broker/`) | Any always-on Linux box on your tailnet | Small Python relay. Phones talk HTTP to it, browsers hold a WebSocket to it |
| **Beam host extension** (`extension/`) | Chrome / Chromium / Edge / Brave on each PC | Opens pages, finds the video element, and drives it |

---

## Contents

1. [Requirements](#requirements)
2. [Install](#install)
3. [Using the app](#using-the-app)
4. [How the phone decides to send something](#how-the-phone-decides-to-send-something)
5. [What the PC side can do on any site](#what-the-pc-side-can-do-on-any-site)
6. [Broker API](#broker-api)
7. [Security model](#security-model)
8. [Building the app](#building-the-app)
9. [Dev helpers](#dev-helpers)
10. [Troubleshooting](#troubleshooting)

---

## Requirements

- **[Tailscale](https://tailscale.com/)** (or any network where the phone can
  reach the broker). The broker binds only to loopback and the Tailscale
  address.
- **Broker host**: Linux with systemd, Python 3.10+, and the `websockets`
  package (`pip install --user websockets` or your distro's
  `python-websockets`).
- **PC browser**: a Chromium-based browser (Manifest V3 extension).
- **Phone**: Android 8.0+ (API 26).

## Install

### 1. Broker

```bash
git clone https://github.com/Krodity/beam.git
cd beam
broker/install.sh          # installs + starts the beam-broker systemd user unit
```

The script checks for `websockets`, installs `beam-broker.service` into
`~/.config/systemd/user/`, starts it, and prints the **pairing token**. The
broker creates that token on first start at `~/.config/beam/token`.

If you use ufw, let the tailnet in:

```bash
sudo ufw allow in on tailscale0 to any port 8780,8781 proto tcp
```

To keep it running while you're logged out: `sudo loginctl enable-linger $USER`.

Ports and paths can be overridden with environment variables in the unit:
`BEAM_HTTP_PORT` (8780), `BEAM_WS_PORT` (8781), `BEAM_CONF` (`~/.config/beam`),
`BEAM_RPC_TIMEOUT` (25 s).

### 2. Extension (on every PC that should play things)

1. Open `chrome://extensions` and turn on **Developer mode**.
2. Click **Load unpacked** and pick the `extension/` folder.
3. Open the extension's popup / options page, enter the broker address
   (`ws://<broker-host>:8781`), the pairing token, and a name for this PC.

On the broker's own machine you can skip step 3. Create
`extension/local-config.json` (it's git-ignored) and the extension pairs
itself:

```json
{"broker": "ws://127.0.0.1:8781", "token": "<token>", "name": "my-pc"}
```

### 3. Phone

Install the APK (see [Building the app](#building-the-app)), open **Beam**, and
either:

- tap **Scan QR code** and point it at the QR in the extension popup, or at
  `http://<broker-host>:8780/pair` opened on the PC, or
- open `http://<broker-host>:8780/pair` *on the phone* and tap the link, or
- type the broker hostname/IP and the pairing token by hand.

## Using the app

### Browse tab

- The **start page** is a grid of sites. A fresh install comes with YouTube,
  Crunchyroll, Twitch, Vimeo, Dailymotion and the Internet Archive. Use **⋮ → Add
  to start page** on any site to pin it, and long-press a tile to remove it.
- The **address bar** takes a URL or a search.
- **Play on PC** sends the current page by hand, along with how far into the
  video you are on the phone.
- **⋮ menu**: *Back*, *Forward*, *Auto-send videos to PC* on/off, *Add to start
  page*, *Choose PC* (when more than one browser is connected), *Unpair*.
- The banner says where videos will play: "Videos will play on the PC" or
  "Videos will play on this phone".

### Remote tab

Once something is playing on the PC:

- Title, thumbnail, progress bar with seek, and a **LIVE** badge for live
  streams
- Play / Pause, Back / Forward 10 s, Previous / Next
- Volume and Mute, Fullscreen
- **Skip ad** (shown while an ad is playing) and **Dismiss "still watching?"**
- **Keyboard**: sends arrows, Space, Enter, Esc, or any key to the page
- **PC tabs**: lists the tabs already open on the PC. Pick one to control it
  from here
- **Close the tab on the PC**

### Share → Beam

Share a link to **Beam** from any app (YouTube, Reddit, a messenger…) and it
opens on the PC straight away.

## How the phone decides to send something

- **Auto-send** (on by default, toggle in the ⋮ menu): a `play` listener is
  injected into every page. Any unmuted `<video>`/`<audio>` that starts is
  paused on the phone and sent to the PC. Muted hover previews are ignored.
- Known **watch pages** (YouTube `/watch` `/shorts` `youtu.be`, Crunchyroll,
  Vimeo, Twitch VODs, Dailymotion, Netflix, archive.org) are sent as soon as
  you open them. That also covers players inside cross-origin iframes.
- **Play on PC** sends the current page by hand.
- **Share → Beam** sends the shared link.

## What the PC side can do on any site

The extension finds the best media element across **every frame** of the tab
(cross-origin iframes and shadow DOM included) and drives it: play/pause,
seek, ±10 s, volume, mute, speed, fullscreen, next/previous, skip ad,
"still watching?", and raw keypresses.

Some of these need a real user gesture: fullscreen, a `play()` that autoplay
would otherwise block, YouTube's skip button. Those go through
`chrome.debugger` as trusted input, so Chrome shows its "is debugging this
browser" bar while that happens. YouTube also gets a small adapter that uses
its own player API for next/previous and volume.

The extension can also list the media in every tab, so a desktop media
widget can switch between tabs, since the browser exposes only one MPRIS
player for all of them.

## Broker API

Everything except `/pair` and `/api/health` needs
`Authorization: Bearer <token>`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/health` | Liveness check |
| GET | `/pair` | Pairing page with a QR code and a tap-to-pair link |
| GET | `/api/hosts` | Connected browsers (id, name) |
| POST | `/api/rpc` | `{"host": "<id>", "method": "open"/"status"/"control"/…, "params": {…}}` → the extension's reply |
| GET | `/api/rpc?method=…&…` | Same thing as a GET, handy for `curl` |

The broker deliberately does very little. It holds no site credentials and
knows nothing about any website. It keeps a list of connected browsers, sends
one JSON-RPC call at a time to the one the phone names, and passes the reply
back.

## Security model

- **The tailnet is the security boundary.** The broker binds only to
  `127.0.0.1` and the machine's Tailscale address, never `0.0.0.0`.
- `/pair` hands out the token without authentication, on purpose: being able
  to reach it already means you're on the tailnet. So anyone on your tailnet
  can pair. Use Tailscale ACLs if your tailnet is shared.
- The bearer token is a **pairing secret** that stops other devices on your
  tailnet from driving your browser by accident. Treat it like a password.
  It's stored in `~/.config/beam/token` and is never committed.
- The extension asks for `<all_urls>`, `scripting` and `debugger`, because it
  has to find and control video on any site. Load it only in a browser profile
  you're comfortable giving that access to.
- The app allows cleartext HTTP only for Tailscale addresses
  (`100.64.0.0/10`) and loopback (see `network_security_config.xml`).

## Building the app

Requirements: JDK 17+ (21 recommended) and the Android SDK with platform 37.

```bash
cd android
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/beam-1.0.0-debug.apk
```

## Dev helpers

- `tools/cdp.py reload`: hot-reloads `extension/` into a Chromium started with
  `--remote-debugging-port=9222`.
- `tools/cdp.py rpc status`, `tools/cdp.py rpc open url=…`,
  `tools/cdp.py rpc control action=pause`: call the broker the same way the
  phone does.
- `tools/sw.py '<js expr>'`: evaluates JavaScript inside the extension's
  service worker.

## Troubleshooting

| Symptom | Check |
|---|---|
| *Can't reach the PC — is Tailscale on?* | Tailscale is connected on the phone, and `curl http://<broker>:8780/api/health` works from another tailnet device |
| *Pairing token rejected* | Copy the token again from `~/.config/beam/token` (it changes if you delete the file) |
| *Not paired with a PC yet* / no PCs listed | The extension isn't connected. Open its popup and check the broker URL and token. `journalctl --user -u beam-broker -f` shows each connect |
| *The PC didn't answer in time* | The tab is still loading, or the browser is asleep. Raise `BEAM_RPC_TIMEOUT` if pages are slow |
| Broker only listens on 127.0.0.1 | Tailscale came up after the broker. Recent versions rebind automatically, otherwise run `systemctl --user restart beam-broker` |
| Fullscreen / play does nothing | The site needs a trusted gesture. Accept the debugger bar, and don't open DevTools on that tab (it blocks `chrome.debugger`) |

## License

MIT. See [LICENSE](LICENSE).
