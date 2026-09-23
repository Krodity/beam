# Beam

Browse any site on your phone, and whatever you press play on plays in the
browser on your PC instead. The phone then becomes the remote.

```
Beam app (WebView)  --HTTP :8780-->  beam-broker  <--WS :8781--  Beam extension
  browse, tap a video                 (tailnet)                  (Chrome/Chromium, any PC)
```

The page's **URL** is what gets sent, not the video stream. The PC opens it in
its own browser, where you're already logged in, so DRM and members-only
content (Crunchyroll, Netflix, …) plays fine there.

## How the phone decides to send something

- **Auto-send** (on by default, toggle in the ⋮ menu): a `play` listener is
  injected into every page, and any unmuted `<video>`/`<audio>` that starts is
  paused on the phone and sent to the PC. Muted hover previews are ignored.
- Known **watch pages** (YouTube `/watch` `/shorts` `youtu.be`, Crunchyroll,
  Vimeo, Twitch VODs, Dailymotion, Netflix, archive.org) are sent as soon as you
  navigate to them. This covers players inside cross-origin iframes too.
- **Play on PC** button: sends the current page by hand, along with the phone's
  playback position.
- **Share → Beam** from any other app sends the shared link.

## What the PC side can do on any site

The extension finds the best media element across **every frame** of the tab
(cross-origin iframes and shadow DOM included) and drives it: play/pause, seek,
±10 s, volume, mute, speed, fullscreen, next/previous, skip ad,
"still watching?", and raw keypresses. Anything that needs a real user gesture
(fullscreen, autoplay-blocked `play()`, YouTube's skip button) goes through
`chrome.debugger` as trusted input. YouTube also gets a small adapter that uses
its player API for next/previous and volume.

The remote can also take over **any tab already open on the PC** (Remote → tab
icon).

## Install

```bash
broker/install.sh                     # systemd user unit, ports 8780/8781
sudo ufw allow in on tailscale0 to any port 8780,8781 proto tcp
```

Extension: open `chrome://extensions`, turn on Developer mode, click
*Load unpacked* and pick `extension/`. On the broker's own machine, drop an
`extension/local-config.json`
(`{"broker":"ws://127.0.0.1:8781","token":"…","name":"aepc"}`, git-ignored) and
the extension pairs itself. Otherwise, paste the token into the popup.

Phone: build with
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --offline assembleDebug` in
`android/`, install it, then scan the QR from the extension popup or from
`http://<pc>:8780/pair`.

## Dev helpers

- `tools/cdp.py reload`: hot-reload `extension/` into a Chromium running with
  `--remote-debugging-port=9222`.
- `tools/cdp.py rpc status`, `tools/cdp.py rpc open url=…`,
  `tools/cdp.py rpc control action=pause`: call the broker the way the phone does.
- `tools/sw.py '<js expr>'`: evaluate inside the extension's service worker.
