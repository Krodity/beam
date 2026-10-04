/* background.js — the Beam host.
 *
 * Keeps one websocket open to the broker and answers JSON-RPC calls from the
 * phone. The phone browses in its own in-app browser; when it hits a video it
 * sends the page URL here, we open it in "the Beam tab", and from then on every
 * transport command is aimed at that tab's best media element.
 *
 * Deliberately site-agnostic. The only site knowledge is a small YouTube
 * adapter (its player API is more reliable than poking the <video>) and the list
 * of sites whose own "f" key is the right fullscreen.
 *
 * MV3 note: a service worker is normally torn down after ~30s idle. Websocket
 * traffic resets that timer and the broker pings every 20s, so the socket keeps
 * this worker resident; the alarm below revives it if Chrome kills it anyway.
 */

const DEFAULTS = { broker: "ws://localhost:8781", token: "", name: "" };
const RETRY_MIN = 2_000;
const RETRY_MAX = 60_000;

let ws = null;
let retry = RETRY_MIN;
let statusTimer = null;

/** The browser's own brand ("Google Chrome", "Chromium", "Brave"…), so two
 *  browsers on one PC show up as two distinguishable hosts on the phone. */
function brand() {
  const b = (navigator.userAgentData?.brands || [])
    .map((x) => x.brand).find((x) => !/not.?a.?brand|^chromium$/i.test(x));
  return b || "Chromium";
}

/** Optional pre-seeded settings, so an install on the broker's own machine
 *  needs no copy-pasting. local-config.json sits beside this file, is
 *  git-ignored (it carries the token), and is read only while unpaired. */
async function seed() {
  try {
    const r = await fetch(chrome.runtime.getURL("local-config.json"));
    if (!r.ok) return null;
    const c = await r.json();
    if (!c.token) return null;
    await chrome.storage.local.set({
      broker: c.broker || DEFAULTS.broker, token: c.token,
      ...(c.name ? { name: `${c.name} ${brand()}` } : {}),
    });
    return c;
  } catch {
    return null;
  }
}

async function config() {
  let c = await chrome.storage.local.get(DEFAULTS);
  if (!c.token && (await seed())) c = await chrome.storage.local.get(DEFAULTS);
  if (!c.name) {
    const p = await chrome.runtime.getPlatformInfo();
    c.name = `${p.os} ${brand()}`;
  }
  return { ...DEFAULTS, ...c };
}

// Memoised: startup fires connect() from several events at once, and parallel
// first calls would each mint a different id -- one browser, three "PCs".
let hostIdP = null;
function hostId() {
  hostIdP ||= (async () => {
    const { hostId } = await chrome.storage.local.get("hostId");
    if (hostId) return hostId;
    const id = crypto.randomUUID().slice(0, 8);
    await chrome.storage.local.set({ hostId: id });
    return id;
  })();
  return hostIdP;
}

// ── which tab are we driving? ────────────────────────────────────────────────
// The tab the phone last opened is "the Beam tab" and is reused for every new
// video, so casting ten things never leaves ten tabs behind. If the user closed
// it, fall back to whatever is making noise, then to the focused tab -- so the
// remote also works for something started at the PC.
async function beamTab() {
  const { beamTabId } = await chrome.storage.session.get("beamTabId");
  if (beamTabId != null) {
    try {
      return await chrome.tabs.get(beamTabId);
    } catch {
      await chrome.storage.session.remove("beamTabId");
    }
  }
  return null;
}

async function targetTab() {
  const t = await beamTab();
  if (t) return t;
  const audible = await chrome.tabs.query({ audible: true });
  if (audible.length) return audible[0];
  const [active] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
  return active && /^https?:/.test(active.url || "") ? active : null;
}

const isYouTube = (url) => /^https:\/\/(www\.|m\.|music\.)?youtube\.com\//.test(url || "");

// ── talking to the page ──────────────────────────────────────────────────────
async function frames(tabId) {
  try {
    return (await chrome.webNavigation.getAllFrames({ tabId })) || [{ frameId: 0, parentFrameId: -1 }];
  } catch {
    return [{ frameId: 0, parentFrameId: -1 }];
  }
}

async function askFrame(tabId, frameId, msg) {
  try {
    return await chrome.tabs.sendMessage(tabId, msg, { frameId });
  } catch {
    // Not injected yet (Beam injects on demand rather than into every page the
    // user ever opens), or the frame navigated. Inject, then try once more.
    try {
      await chrome.scripting.executeScript({
        target: { tabId, frameIds: [frameId] },
        files: ["content.js"],
      });
      return await chrome.tabs.sendMessage(tabId, msg, { frameId });
    } catch {
      return null;
    }
  }
}

/** Ask every frame what it has; return the frame with the best media. */
async function probe(tab) {
  const fr = await frames(tab.id);
  const replies = await Promise.all(
    fr.map(async (f) => ({ frame: f, r: await askFrame(tab.id, f.frameId, { cmd: "status" }) }))
  );
  let top = null;
  for (const { frame, r } of replies) {
    if (r?.hasVideo && (!top || r.score > top.r.score)) top = { frame, r };
  }
  return top;
}

async function requireTab() {
  const tab = await targetTab();
  if (!tab) throw new Error("nothing is open on the PC — send a page from the phone first");
  return tab;
}

async function requireMedia(tab) {
  const p = await probe(tab);
  if (!p) throw new Error("no video on the PC page yet — it may still be loading");
  return p;
}

/** Translate a rect reported by a subframe into top-frame viewport coordinates,
 *  by walking up the frame tree and adding each iframe's offset. The iframe is
 *  matched by src, falling back to the largest visible one. */
async function toTopCoords(tabId, frame, rect) {
  const all = await frames(tabId);
  // Scroll each ancestor so the iframe chain is on-screen, innermost first;
  // offsets are then read top-down after all the scrolling has happened.
  for (let cur = frame; cur && cur.parentFrameId >= 0;) {
    const parent = all.find((f) => f.frameId === cur.parentFrameId);
    if (!parent) break;
    await askFrame(tabId, parent.frameId, { cmd: "iframes", reveal: cur.url });
    cur = parent;
  }
  let x = rect.x, y = rect.y, cur = frame;
  while (cur && cur.parentFrameId >= 0) {
    const parent = all.find((f) => f.frameId === cur.parentFrameId);
    if (!parent) break;
    const { frames: ifr = [] } = (await askFrame(tabId, parent.frameId, { cmd: "iframes" })) || {};
    const exact = ifr.find((f) => f.src && (f.src === cur.url || cur.url?.startsWith(f.src)));
    const host = exact || ifr.sort((a, b) => b.w * b.h - a.w * a.h)[0];
    if (!host) break;
    x += host.left;
    y += host.top;
    cur = parent;
  }
  return { x, y };
}

// ── trusted input ────────────────────────────────────────────────────────────
// Synthetic events are untrusted: requestFullscreen() refuses them, autoplay
// policy ignores them, and several players' buttons check isTrusted. Input sent
// through the debugger API is indistinguishable from the real mouse/keyboard.
// Cost: a "started debugging this browser" infobar for the moment it's attached.
async function withDebugger(tabId, fn) {
  const target = { tabId };
  await chrome.debugger.attach(target, "1.3");
  try {
    return await fn((method, params) => chrome.debugger.sendCommand(target, method, params));
  } finally {
    await chrome.debugger.detach(target).catch(() => {});
  }
}

const KEYS = {
  f: { key: "f", code: "KeyF", keyCode: 70 },
  m: { key: "m", code: "KeyM", keyCode: 77 },
  k: { key: "k", code: "KeyK", keyCode: 75 },
  j: { key: "j", code: "KeyJ", keyCode: 74 },
  l: { key: "l", code: "KeyL", keyCode: 76 },
  n: { key: "N", code: "KeyN", keyCode: 78, shift: true },     // YouTube: next
  p: { key: "P", code: "KeyP", keyCode: 80, shift: true },     // YouTube: previous
  space: { key: " ", code: "Space", keyCode: 32 },
  left: { key: "ArrowLeft", code: "ArrowLeft", keyCode: 37 },
  right: { key: "ArrowRight", code: "ArrowRight", keyCode: 39 },
  up: { key: "ArrowUp", code: "ArrowUp", keyCode: 38 },
  down: { key: "ArrowDown", code: "ArrowDown", keyCode: 40 },
  escape: { key: "Escape", code: "Escape", keyCode: 27 },
  enter: { key: "Enter", code: "Enter", keyCode: 13 },
};

async function trustedKey(tabId, name) {
  const k = KEYS[name];
  if (!k) throw new Error(`unknown key ${name}`);
  await withDebugger(tabId, async (send) => {
    const base = {
      key: k.key, code: k.code,
      windowsVirtualKeyCode: k.keyCode, nativeVirtualKeyCode: k.keyCode,
      modifiers: k.shift ? 8 : 0,
    };
    const printable = k.key.length === 1;
    await send("Input.dispatchKeyEvent", { ...base, type: printable ? "keyDown" : "rawKeyDown",
                                           ...(printable ? { text: k.key } : {}) });
    await send("Input.dispatchKeyEvent", { ...base, type: "keyUp" });
  });
}

async function trustedClick(tabId, { x, y }) {
  await withDebugger(tabId, async (send) => {
    const at = { x: Math.round(x), y: Math.round(y) };
    await send("Input.dispatchMouseEvent", { type: "mouseMoved", ...at });
    await send("Input.dispatchMouseEvent", { type: "mousePressed", button: "left", clickCount: 1, ...at });
    await send("Input.dispatchMouseEvent", { type: "mouseReleased", button: "left", clickCount: 1, ...at });
  });
}

/** Run a function in the page's own JS world (not the extension's isolated
 *  one), where site player objects like YouTube's #movie_player live. */
async function inPage(tabId, func, args = []) {
  const [r] = await chrome.scripting.executeScript({
    target: { tabId, frameIds: [0] }, world: "MAIN", func, args,
  });
  return r?.result;
}

// ── YouTube adapter ──────────────────────────────────────────────────────────
// The <video> works for transport, but YouTube's player API also covers what a
// bare element can't: next/previous in the queue, and a volume that its own UI
// agrees with afterwards.
async function ytCall(tabId, fn, arg) {
  return inPage(tabId, (fn, arg) => {
    const p = document.getElementById("movie_player");
    if (!p || typeof p[fn] !== "function") return { ok: false };
    p[fn](...(arg === null ? [] : [arg]));
    return { ok: true };
  }, [fn, arg ?? null]);          // executeScript can't serialise undefined
}

// ── metadata ─────────────────────────────────────────────────────────────────
/** Title/artist/artwork as the page itself published it to the OS media
 *  controls. Read from the MAIN world: the isolated world has its own
 *  navigator and never sees the page's mediaSession. */
async function mediaMeta(tabId) {
  try {
    return await inPage(tabId, () => {
      const m = navigator.mediaSession?.metadata;
      if (!m) return null;
      const art = [...(m.artwork || [])].sort(
        (a, b) => parseInt(b.sizes || "0") - parseInt(a.sizes || "0"))[0];
      return { title: m.title || "", artist: m.artist || "", album: m.album || "",
               artwork: art?.src || "" };
    });
  } catch {
    return null;                                    // chrome:// pages, PDFs, etc.
  }
}

async function status() {
  const tab = await targetTab();
  if (!tab) return { hasVideo: false, reason: "nothing open on the PC" };
  const [p, meta] = await Promise.all([probe(tab), mediaMeta(tab.id)]);
  const base = {
    tabId: tab.id,
    pageTitle: tab.title || "",
    url: tab.url || "",
    favicon: tab.favIconUrl || "",
    site: (() => { try { return new URL(tab.url).hostname.replace(/^www\./, ""); } catch { return ""; } })(),
    title: meta?.title || "",
    artist: meta?.artist || "",
    artwork: meta?.artwork || "",
    loading: tab.status === "loading",
  };
  if (!p) return { ...base, hasVideo: false, reason: tab.status === "loading" ? "loading…" : "no video on this page" };
  const { score, frameUrl, ...r } = p.r;
  return { ...base, ...r };
}

// ── opening a page ───────────────────────────────────────────────────────────
function pcUrl(raw) {
  let u;
  try { u = new URL(raw); } catch { throw new Error("that isn't a valid URL"); }
  if (!/^https?:$/.test(u.protocol)) throw new Error("only http(s) pages can be sent to the PC");
  // The phone browses mobile sites; the PC should get the desktop one.
  if (u.hostname === "m.youtube.com") u.hostname = "www.youtube.com";
  if (u.hostname === "youtu.be") {
    const id = u.pathname.slice(1);
    u = new URL(`https://www.youtube.com/watch?v=${id}${u.search ? "&" + u.search.slice(1) : ""}`);
  }
  if (u.hostname === "m.twitch.tv") u.hostname = "www.twitch.tv";
  if (u.hostname === "mobile.twitter.com") u.hostname = "x.com";
  return u;
}

let openSeq = 0;

/** After navigation: wait for media, seek to the phone's position, and make
 *  sure it is actually playing. Many sites autoplay by themselves; the ones
 *  that don't get a play(), and if autoplay policy refuses that, a trusted
 *  click on the player. Cancelled if another open supersedes this one. */
async function settle(tabId, seq, { time, autoplay }) {
  const deadline = Date.now() + 25_000;
  let sought = !(time > 3);
  let firstSeen = 0;
  while (Date.now() < deadline && seq === openSeq) {
    await new Promise((r) => setTimeout(r, 1000));
    let tab;
    try { tab = await chrome.tabs.get(tabId); } catch { return; }
    const p = await probe(tab);
    if (!p || !(p.r.duration > 0 || p.r.live)) continue;
    firstSeen ||= Date.now();
    if (!sought && p.r.duration > time) {
      await askFrame(tabId, p.frame.frameId, { cmd: "control", action: "seek", value: time });
      sought = true;
    }
    if (!autoplay || p.r.playing) { if (sought) return; else continue; }
    // Give the site ~2s to autoplay on its own before we intervene; YouTube
    // in particular fights a play() issued during its own startup.
    if (Date.now() - firstSeen < 2000 || p.r.ad) continue;
    const r = await askFrame(tabId, p.frame.frameId, { cmd: "control", action: "play" });
    if (r?.blocked) {
      await forcePlay(tabId, p.frame, r.rect).catch((e) => console.warn("beam: autoplay click failed", e));
    }
    if (sought) return;
  }
}

async function open({ url, time, autoplay = true, newTab = false }) {
  const u = pcUrl(url);
  if (time > 3 && isYouTube(u.href) && !u.searchParams.has("t")) {
    u.searchParams.set("t", `${Math.floor(time)}s`);
  }
  const existing = newTab ? null : await beamTab();
  let tab;
  if (existing) {
    tab = await chrome.tabs.update(existing.id, { url: u.href, active: true });
  } else {
    const [win] = await chrome.windows.getAll({ windowTypes: ["normal"] })
      .then((ws) => ws.sort((a, b) => Number(b.focused) - Number(a.focused)));
    tab = await chrome.tabs.create({ url: u.href, active: true, ...(win ? { windowId: win.id } : {}) });
  }
  await chrome.storage.session.set({ beamTabId: tab.id });
  await chrome.windows.update(tab.windowId, { focused: true }).catch(() => {});
  const seq = ++openSeq;
  settle(tab.id, seq, { time: Number(time) || 0, autoplay: autoplay !== false && autoplay !== "false" });
  return { ok: true, tabId: tab.id, url: u.href };
}

// ── transport ────────────────────────────────────────────────────────────────
const F_KEY_SITES = /(^|\.)(youtube\.com|crunchyroll\.com|vimeo\.com|twitch\.tv|netflix\.com|disneyplus\.com|max\.com|primevideo\.com|hulu\.com|dailymotion\.com)$/;

async function fullscreen(tab) {
  const host = (() => { try { return new URL(tab.url).hostname; } catch { return ""; } })();
  await chrome.tabs.update(tab.id, { active: true });
  await chrome.windows.update(tab.windowId, { focused: true }).catch(() => {});
  // Sites with an "f" hotkey: press it, so their own player UI goes fullscreen.
  if (F_KEY_SITES.test(host)) {
    await trustedKey(tab.id, "f");
    return { ok: true, method: "key-f" };
  }
  // Everything else: requestFullscreen() with a real gesture attached. Target
  // the player's container rather than the bare <video> so the site's own
  // controls come along; for an iframe player, fullscreen the iframe itself.
  const r = await withDebugger(tab.id, (send) => send("Runtime.evaluate", {
    userGesture: true, awaitPromise: true, returnByValue: true,
    expression: `(async () => {
      if (document.fullscreenElement) { await document.exitFullscreen(); return "exit"; }
      const vis = (e) => { const r = e.getBoundingClientRect(); return r.width * r.height; };
      const vids = [...document.querySelectorAll("video")].sort((a, b) => vis(b) - vis(a));
      let el = vids[0] && vis(vids[0]) > 0 ? vids[0] : null;
      if (el) {
        const a = vis(el);
        for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
          if (vis(p) > a * 1.6) break;
          el = p;
        }
      } else {
        el = [...document.querySelectorAll("iframe")].sort((a, b) => vis(b) - vis(a))[0] || null;
      }
      if (!el) return "none";
      await el.requestFullscreen();
      return "enter";
    })()`,
  }));
  const how = r?.result?.value;
  if (how === "enter" || how === "exit") return { ok: true, method: `request-${how}` };
  // Nothing we could fullscreen: fall back to the "f" key, then the window.
  try {
    await trustedKey(tab.id, "f");
    return { ok: true, method: "key-f" };
  } catch {
    const win = await chrome.windows.get(tab.windowId);
    await chrome.windows.update(tab.windowId, { state: win.state === "fullscreen" ? "normal" : "fullscreen" });
    return { ok: true, method: "window" };
  }
}

/** play() refused by autoplay policy: click the player for real. That grants
 *  the frame user activation; a player with its own click handler starts on the
 *  click itself, a bare <video> doesn't -- so if it is still paused, play() again,
 *  which now succeeds. */
async function forcePlay(tabId, frame, rect) {
  const at = await toTopCoords(tabId, frame, rect);
  await trustedClick(tabId, at);
  await new Promise((r) => setTimeout(r, 300));
  const s = await askFrame(tabId, frame.frameId, { cmd: "status" });
  let again = null;
  if (s?.hasVideo && !s.playing) {
    again = await askFrame(tabId, frame.frameId, { cmd: "control", action: "play" });
  }
  console.debug("beam: forcePlay", JSON.stringify({ frame: frame.frameId, rect, at, after: s?.playing, again: again?.blocked ? "blocked" : again?.playing }));
}

/** Find a labelled button in whichever frame has it and click it for real. */
async function clickButton(tab, which) {
  const fr = await frames(tab.id);
  for (const f of fr) {
    const r = await askFrame(tab.id, f.frameId, { cmd: "locate", which });
    if (r?.found) {
      await trustedClick(tab.id, await toTopCoords(tab.id, f, r.rect));
      return true;
    }
  }
  return false;
}

async function control({ action, value }) {
  const tab = await requireTab();
  const v = value === undefined || value === null || value === "" ? null : Number(value);
  const yt = isYouTube(tab.url);

  switch (action) {
    case "fullscreen":
      return fullscreen(tab);
    case "next":
    case "prev": {
      if (yt) {
        const r = await ytCall(tab.id, action === "next" ? "nextVideo" : "previousVideo");
        if (!r?.ok) await trustedKey(tab.id, action === "next" ? "n" : "p");
        return { ok: true };
      }
      if (await clickButton(tab, action)) return { ok: true };
      throw new Error(`this site has no ${action === "next" ? "next" : "previous"} button I can find`);
    }
    case "skipAd":
    case "dismiss":
      return { ok: true, clicked: await clickButton(tab, action) };
    case "key":
      await trustedKey(tab.id, String(value));
      return { ok: true };
    case "close":
      await chrome.tabs.remove(tab.id);
      await chrome.storage.session.remove("beamTabId");
      return { ok: true };
  }

  const p = await requireMedia(tab);
  if (yt && action === "volume" && v !== null) {
    // Through the player so YouTube's slider and saved volume agree with us.
    await ytCall(tab.id, "setVolume", Math.round(v * 100));
    if (v > 0) await ytCall(tab.id, "unMute");
  }
  const r = await askFrame(tab.id, p.frame.frameId, { cmd: "control", action, value: v });
  if (!r) throw new Error("the PC page stopped responding — it may have navigated");
  if (r.error) throw new Error(r.error);
  if (r.blocked) {
    // Autoplay policy refused a scripted play(); a real click on the player is
    // a user gesture and gets through.
    await forcePlay(tab.id, p.frame, r.rect);
  }
  return status();
}

// ── per-tab media (the desktop shell's media card) ───────────────────────────
// The browser shows every tab through ONE MPRIS player, which follows whichever
// tab played last. These let the shell list each tab's media and switch between
// them: start one tab and the OS player moves to it.

/** One cheap look at every frame of a tab: its media elements + mediaSession. */
async function tabMedia(tab) {
  let results;
  try {
    results = await chrome.scripting.executeScript({
      target: { tabId: tab.id, allFrames: true }, world: "MAIN",
      func: () => {
        // Skip decoration: looping hero clips and muted autoplay previews.
        const els = [...document.querySelectorAll("video, audio")]
          .filter((m) => (m.readyState > 0 || m.currentSrc || m.src)
            && !(m.loop && (m.duration || 0) < 120) && !(m.muted && m.autoplay));
        const m = navigator.mediaSession?.metadata;
        // Only media that's been started (or that the page published to the OS):
        // unplayed demo clips on a landing page aren't "a tab playing something".
        if (!els.some((e) => !e.paused || e.currentTime > 0) && !m?.title) return null;
        const playing = els.find((e) => !e.paused && !e.ended);
        const best = playing || els.sort((a, b) => (b.duration || 0) - (a.duration || 0))[0];
        return {
          playing: !!playing,
          position: best ? best.currentTime : 0,
          duration: best && Number.isFinite(best.duration) ? best.duration : 0,
          title: m?.title || "", artist: m?.artist || "",
          hasElement: !!best,
        };
      },
    });
  } catch {
    return null;                                   // chrome://, PDFs, blocked hosts
  }
  const frames = results.map((r) => r.result).filter(Boolean);
  if (!frames.some((f) => f.hasElement)) return null;
  const pick = frames.find((f) => f.playing) || frames.find((f) => f.hasElement);
  const meta = frames.find((f) => f.title) || {};
  return { ...pick, title: meta.title || "", artist: meta.artist || "" };
}

async function mediaTabs() {
  const all = (await chrome.tabs.query({}))
    .filter((t) => /^https?:/.test(t.url || "") && !t.discarded);
  const looked = await Promise.all(all.map(async (t) => ({ t, m: await tabMedia(t) })));
  return {
    tabs: looked.filter((x) => x.m).map(({ t, m }) => ({
      id: t.id, pageTitle: t.title || "", url: t.url || "", favicon: t.favIconUrl || "",
      site: (() => { try { return new URL(t.url).hostname.replace(/^www\./, ""); } catch { return ""; } })(),
      audible: !!t.audible, active: !!t.active,
      title: m.title, artist: m.artist, playing: m.playing, position: m.position, duration: m.duration,
    })).sort((a, b) => Number(b.playing) - Number(a.playing) || Number(b.audible) - Number(a.audible)),
  };
}

/** play / pause / toggle one tab's best media. `only` pauses every other tab first. */
async function tabControl(tabId, action) {
  const tab = await chrome.tabs.get(Number(tabId));
  const p = await requireMedia(tab);
  // play() can stay pending for a long time (a tab that was never shown defers its media until it is),
  // while the element already reports !paused. Don't hold the caller hostage to it.
  const r = await Promise.race([
    askFrame(tab.id, p.frame.frameId, { cmd: "control", action }),
    new Promise((res) => setTimeout(() => res({ ok: true, pending: true }), 3000)),
  ]);
  if (!r) throw new Error("that tab stopped responding — it may have navigated");
  if (r.error) throw new Error(r.error);
  if (r.blocked) await forcePlay(tab.id, p.frame, r.rect);
  return { ok: true };
}

async function pauseOthers(keepId) {
  const others = (await mediaTabs()).tabs.filter((t) => t.playing && t.id !== keepId);
  await Promise.all(others.map((t) => tabControl(t.id, "pause").catch(() => {})));
}

// ── RPC methods the phone can call ───────────────────────────────────────────
const METHODS = {
  ping: async () => ({ ok: true, at: Date.now() }),
  open,
  control,
  status,

  /** Tabs that look like media, so the phone can pick one started at the PC. */
  tabs: async () => {
    const all = await chrome.tabs.query({});
    const beam = await beamTab();
    return {
      tabs: all
        .filter((t) => /^https?:/.test(t.url || ""))
        .map((t) => ({
          id: t.id, title: t.title || "", url: t.url || "", favicon: t.favIconUrl || "",
          audible: !!t.audible, active: !!t.active, beam: t.id === beam?.id,
        }))
        .sort((a, b) => Number(b.beam) - Number(a.beam) || Number(b.audible) - Number(a.audible)),
    };
  },

  /** Every tab with media (for the desktop shell), playing ones first. */
  mediaTabs,

  /** Switch the browser's media to this tab: pause the others, play this one. */
  mediaPlay: async ({ tabId }) => {
    await pauseOthers(Number(tabId));
    return tabControl(tabId, "play");
  },

  mediaPause: async ({ tabId }) => tabControl(tabId, "pause"),

  /** Bring a tab to the front (doesn't change what the phone remote drives). */
  showTab: async ({ tabId }) => {
    const tab = await chrome.tabs.update(Number(tabId), { active: true });
    await chrome.windows.update(tab.windowId, { focused: true }).catch(() => {});
    return { ok: true };
  },

  /** Make an existing PC tab the one the remote drives. */
  focusTab: async ({ tabId }) => {
    const tab = await chrome.tabs.get(Number(tabId));
    await chrome.storage.session.set({ beamTabId: tab.id });
    await chrome.tabs.update(tab.id, { active: true });
    await chrome.windows.update(tab.windowId, { focused: true }).catch(() => {});
    return { ok: true };
  },
};

// ── broker link ──────────────────────────────────────────────────────────────
async function dispatch(msg) {
  const fn = METHODS[msg.method];
  if (!fn) throw new Error(`unknown method ${msg.method}`);
  return (await fn(msg.params || {})) ?? { ok: true };
}

let connecting = false;

async function connect() {
  // One socket at a time: install, startup, the alarm and a settings change can
  // all ask for a connection within the same second.
  if (connecting || ws?.readyState === WebSocket.OPEN || ws?.readyState === WebSocket.CONNECTING) return;
  connecting = true;
  try {
    await dial();
  } finally {
    connecting = false;
  }
}

async function dial() {
  const cfg = await config();
  if (!cfg.token) {
    setBadge("!", "#b91c1c");
    return schedule();
  }
  const url =
    `${cfg.broker.replace(/\/+$/, "")}/host` +
    `?token=${encodeURIComponent(cfg.token)}` +
    `&name=${encodeURIComponent(cfg.name)}` +
    `&id=${encodeURIComponent(await hostId())}`;

  try {
    ws = new WebSocket(url);
  } catch {
    return schedule();
  }

  ws.onopen = () => {
    retry = RETRY_MIN;
    setBadge("", "#16a34a");
    chrome.storage.local.set({ lastConnected: Date.now(), lastError: "" });
    startStatusPush();
  };

  ws.onmessage = async (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    if (msg.type === "hello") {
      chrome.storage.local.set({ pairHost: msg.pairHost || "" });
      return;
    }
    try {
      ws.send(JSON.stringify({ id: msg.id, result: await dispatch(msg) }));
    } catch (e) {
      ws.send(JSON.stringify({ id: msg.id, error: e?.message || String(e) }));
    }
  };

  const sock = ws;
  ws.onclose = () => {
    if (ws !== sock) return;               // an older socket we already replaced
    stopStatusPush();
    setBadge("!", "#b91c1c");
    schedule();
  };
  ws.onerror = () => {
    chrome.storage.local.set({ lastError: `cannot reach broker at ${cfg.broker}` });
  };
}

function schedule() {
  retry = Math.min(retry * 2, RETRY_MAX);
  chrome.alarms.create("reconnect", { when: Date.now() + retry });
}

/** Push status so the broker's /api/hosts shows what each PC is playing. */
function startStatusPush() {
  stopStatusPush();
  statusTimer = setInterval(async () => {
    if (ws?.readyState !== WebSocket.OPEN) return;
    try {
      ws.send(JSON.stringify({ type: "status", status: await status() }));
    } catch { /* page between navigations */ }
  }, 5000);
}

function stopStatusPush() {
  if (statusTimer) clearInterval(statusTimer);
  statusTimer = null;
}

function setBadge(text, color) {
  chrome.action.setBadgeText({ text });
  if (color) chrome.action.setBadgeBackgroundColor({ color });
}

chrome.alarms.onAlarm.addListener((a) => {
  if (a.name === "reconnect" && ws?.readyState !== WebSocket.OPEN) connect();
});
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(connect);
chrome.storage.onChanged.addListener((ch, area) => {
  if (area === "local" && (ch.broker || ch.token || ch.name)) {
    const old = ws;
    ws = null;
    try { old?.close(); } catch {}
    retry = RETRY_MIN;
    connect();
  }
});
chrome.alarms.create("reconnect", { periodInMinutes: 1 });
connect();
