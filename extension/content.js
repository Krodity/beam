/* content.js — injected on demand into every frame of the Beam tab, and the
 * only thing that actually touches a media element.
 *
 * Nothing here knows about any particular site. Each frame answers only for
 * itself: "here is the best media element I can see, and how good it is". The
 * service worker asks every frame, keeps the highest score, and sends commands
 * to that one frame alone -- so a page with a muted hero clip and a real player
 * in an iframe controls the player, not the clip.
 */
(() => {
  if (window.__beamInstalled) return;
  window.__beamInstalled = true;

  const SEEK_STEP = 10;

  /** Every <video>/<audio>, including ones inside open shadow roots (custom
   *  player elements hide theirs there). The shadow walk only runs when the
   *  light DOM has nothing, because it touches every element on the page. */
  function mediaElements() {
    const found = [...document.querySelectorAll("video, audio")];
    return found.length ? found : deepQuery("video, audio");
  }

  /** querySelectorAll that also looks inside open shadow roots. Web-component
   *  pages (MDN's live examples, many custom players) keep their iframes and
   *  media there, invisible to a plain query. */
  function deepQuery(sel) {
    const out = [...document.querySelectorAll(sel)];
    const walk = (root) => {
      const it = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
      for (let n = it.currentNode; n; n = it.nextNode()) {
        if (n.shadowRoot) {
          out.push(...n.shadowRoot.querySelectorAll(sel));
          walk(n.shadowRoot);
        }
      }
    };
    walk(document);
    return out;
  }

  function area(el) {
    const r = el.getBoundingClientRect();
    return Math.max(0, r.width) * Math.max(0, r.height);
  }

  /** How likely this element is "the thing the user came to watch". Playing
   *  beats paused, real media beats an unloaded placeholder, long beats a
   *  looping preview, big beats a thumbnail. */
  function score(m) {
    if (!(m.readyState > 0 || m.currentSrc || m.src)) return 0;
    const d = Number.isFinite(m.duration) ? m.duration : 3600;   // live = long
    let s = 1;
    if (!m.paused && !m.ended) s += 1000;
    if (!m.muted && m.volume > 0) s += 50;
    if (m.loop && d < 60) s -= 40;                  // background loops, hover previews
    s += Math.min(d, 7200) / 36;                     // up to +200 for a 2 h film
    if (m.tagName === "VIDEO") s += Math.min(area(m) / 5000, 300);
    else s += 20;                                    // audio has no size; don't bury it
    return Math.max(s, 1);
  }

  function best() {
    let top = null, topScore = 0;
    for (const m of mediaElements()) {
      const s = score(m);
      if (s > topScore) { top = m; topScore = s; }
    }
    return top ? { el: top, score: topScore } : null;
  }

  const isYouTube = /(^|\.)youtube\.com$/.test(location.hostname);

  function adShowing() {
    if (!isYouTube) return false;
    return !!document.querySelector("#movie_player.ad-showing, .html5-video-player.ad-showing");
  }

  function snapshot() {
    const b = best();
    if (!b) return { hasVideo: false, score: 0, frameUrl: location.href };
    const m = b.el;
    const live = !Number.isFinite(m.duration);
    return {
      hasVideo: true,
      kind: m.tagName.toLowerCase(),
      score: b.score,
      playing: !m.paused && !m.ended,
      paused: m.paused,
      ended: m.ended,
      position: Number.isFinite(m.currentTime) ? m.currentTime : 0,
      duration: live ? 0 : m.duration || 0,
      live,
      buffered: m.buffered.length ? m.buffered.end(m.buffered.length - 1) : 0,
      volume: m.volume,
      muted: m.muted,
      rate: m.playbackRate,
      fullscreen: !!document.fullscreenElement,
      ad: adShowing(),
      frameUrl: location.href,
    };
  }

  /** Viewport rect of something worth clicking, for a trusted click from the
   *  service worker. In a subframe these coordinates are frame-relative; the
   *  worker offsets them by the iframe's own position. */
  function rectOf(el) {
    const r = el.getBoundingClientRect();
    return { x: r.left + r.width / 2, y: r.top + r.height / 2, w: r.width, h: r.height };
  }

  const shown = (el) => {
    const r = el.getBoundingClientRect();
    return r.width > 4 && r.height > 4 && getComputedStyle(el).visibility !== "hidden";
  };

  /** Find a visible button whose label matches. Used for "next", "skip ad" and
   *  "are you still watching?" -- every site spells these differently, but
   *  nearly all of them label the button in words or aria. */
  function findButton(rx, selectors = "button, [role=button], a, .ytp-button") {
    for (const el of document.querySelectorAll(selectors)) {
      const label = [el.getAttribute("aria-label"), el.getAttribute("title"),
                     el.getAttribute("data-title-no-tooltip"), el.textContent]
        .filter(Boolean).join(" ").trim();
      if (label && rx.test(label) && shown(el)) return el;
    }
    return null;
  }

  const BUTTONS = {
    next: /^(play )?next( episode| video| track| song)?\b|skip to next|next episode|^up next$/i,
    prev: /^(play )?previous( episode| video| track| song)?\b|skip to previous/i,
    skipAd: /skip( ad| ads)?\b|^skip$/i,
    dismiss: /still watching|continue watching|keep watching|i'?m here|^continue$|^yes$/i,
  };
  const YT_SKIP = ".ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern";

  /** Locate a labelled button and hand back where it is. The worker clicks it
   *  with a trusted event, because several players (YouTube's skip button among
   *  them) ignore a synthetic element.click(). */
  function locate(which) {
    let el = null;
    if (which === "skipAd" && isYouTube) el = document.querySelector(YT_SKIP);
    if (el && !shown(el)) el = null;
    el ||= findButton(BUTTONS[which]);
    return el ? { found: true, rect: rectOf(el) } : { found: false };
  }

  /** Rects of this frame's iframes, keyed by src, so the worker can translate a
   *  subframe's coordinates into the top frame's. */
  function iframeRects(reveal) {
    const all = deepQuery("iframe, frame").filter(shown);
    // A click can only land on-screen: bring the frame holding the player into
    // view first. Matched by src like the worker does, else the largest.
    if (reveal) {
      const hit = all.find((f) => f.src && (f.src === reveal || reveal.startsWith(f.src))) ||
        [...all].sort((a, b) => area(b) - area(a))[0];
      hit?.scrollIntoView({ block: "center", behavior: "instant" });
    }
    return all.map((f) => ({
      src: f.src || "", ...rectOf(f),
      left: f.getBoundingClientRect().left, top: f.getBoundingClientRect().top,
    }));
  }

  async function control(action, value) {
    const b = best();
    if (!b) throw new Error("no media in this frame");
    const m = b.el;
    switch (action) {
      case "play":
        try {
          await m.play();
        } catch (e) {
          // NotAllowedError = autoplay policy. The worker retries with a
          // trusted click on the player, which does count as a gesture.
          if (e?.name === "NotAllowedError") {
            m.scrollIntoView({ block: "center", behavior: "instant" });
            return { blocked: true, rect: rectOf(m) };
          }
          throw new Error(`could not start playback: ${e?.message || e}`);
        }
        break;
      case "pause":
        m.pause();
        break;
      case "toggle":
        if (m.paused || m.ended) return control("play");
        m.pause();
        break;
      case "seek":
        m.currentTime = Math.max(0, Math.min(value ?? 0, m.duration || value || 0));
        break;
      case "forward":
        m.currentTime = Math.min(m.currentTime + (value || SEEK_STEP), m.duration || Infinity);
        break;
      case "back":
        m.currentTime = Math.max(m.currentTime - (value || SEEK_STEP), 0);
        break;
      case "restart":
        m.currentTime = 0;
        break;
      case "volume":
        m.volume = Math.max(0, Math.min(1, value ?? m.volume));
        if (m.volume > 0 && m.muted) m.muted = false;
        break;
      case "mute":
        m.muted = value === null ? !m.muted : !!value;
        break;
      case "rate":
        m.playbackRate = value || 1;
        break;
      default:
        throw new Error(`unknown action ${action}`);
    }
    return { ok: true, ...snapshot() };
  }

  chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
    (async () => {
      try {
        switch (msg?.cmd) {
          case "status": return reply(snapshot());
          case "control": return reply(await control(msg.action, msg.value));
          case "locate": return reply(locate(msg.which));
          case "mediaRect": {
            const b = best();
            return reply(b ? { found: true, rect: rectOf(b.el) } : { found: false });
          }
          case "iframes": return reply({ frames: iframeRects(msg.reveal) });
          default: return reply({ error: "unknown cmd" });
        }
      } catch (e) {
        reply({ error: e?.message || String(e) });
      }
    })();
    return true;                                   // async reply
  });
})();
