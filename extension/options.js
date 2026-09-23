const $ = (id) => document.getElementById(id);
const FIELDS = ["broker", "token", "name"];

(async () => {
  const c = await chrome.storage.local.get(["broker", "token", "name"]);
  $("broker").value = c.broker || "ws://localhost:8781";
  $("token").value = c.token || "";
  $("name").value = c.name || "";
  render();
})();

$("save").onclick = async () => {
  const vals = Object.fromEntries(FIELDS.map((f) => [f, $(f).value.trim()]));
  if (!/^wss?:\/\//.test(vals.broker)) {
    return flag("bad", "Broker must start with ws:// or wss://");
  }
  if (!vals.token) return flag("bad", "Pairing token is required");
  await chrome.storage.local.set(vals);
  flag("wait", "connecting…");
  setTimeout(render, 1200);
};

async function render() {
  const c = await chrome.storage.local.get(["lastConnected", "lastError", "token"]);
  if (!c.token) return flag("bad", "Not paired yet");
  if (c.lastError) return flag("bad", c.lastError);
  if (!c.lastConnected) return flag("wait", "connecting…");
  const age = Date.now() - c.lastConnected;
  flag(age < 120_000 ? "ok" : "wait",
       age < 120_000 ? "Connected" : `Last connected ${Math.round(age / 60000)} min ago`);
}

// ── pairing QR ───────────────────────────────────────────────────────────────
// The popup holds the *websocket* address; the phone dials the HTTP API, which
// the broker serves on the port just below it (8780 alongside 8781).
function pairLink(brokerWs, token) {
  const u = new URL(brokerWs);
  const host = `${u.hostname}:${(Number(u.port) || 8781) - 1}`;
  const link = `beam://pair?broker=${encodeURIComponent(host)}` +
               `&token=${encodeURIComponent(token)}`;
  return { hostname: u.hostname, host, link };
}

const LOOPBACK = ["localhost", "127.0.0.1", "[::1]", "::1"];

// Hidden until asked for: the code is the token in visual form, and the popup
// is often open while a screen is being shared.
$("qrtoggle").onclick = async () => {
  const box = $("qrbox");
  if (!box.hidden) {
    box.hidden = true;
    $("qr").innerHTML = "";
    $("qrtoggle").textContent = "Show pairing QR";
    return;
  }
  const broker = $("broker").value.trim();
  const token = $("token").value.trim();
  if (!/^wss?:\/\//.test(broker)) return flag("bad", "Broker must start with ws:// or wss://");
  if (!token) return flag("bad", "Pairing token is required");

  let info;
  try {
    info = pairLink(broker, token);
  } catch {
    return flag("bad", "Broker address is not a valid URL");
  }

  // The broker announces its own reachable address on connect. Trust that over
  // the ws URL: a browser running beside the broker is configured with
  // "127.0.0.1", which is the one address a phone cannot dial.
  const { pairHost } = await chrome.storage.local.get("pairHost");
  const host = pairHost || info.host;
  const link = pairHost
    ? `beam://pair?broker=${encodeURIComponent(pairHost)}&token=${encodeURIComponent(token)}`
    : info.link;

  // Rendered fresh on every open, so a token edited above is the one encoded.
  $("qr").innerHTML = QR.svg(link, { ecl: "M", quiet: 2, label: "Beam pairing code" });
  $("qraddr").textContent = !pairHost && LOOPBACK.includes(info.hostname)
    ? `Pairs to ${host} — the phone can't reach that. Use this PC's Tailscale name or IP.`
    : `Pairs to ${host}. In the app: Scan QR code.`;
  box.hidden = false;
  $("qrtoggle").textContent = "Hide pairing QR";
};

function flag(kind, msg) {
  $("msg").textContent = msg;
  document.querySelector(".dot").style.background =
    kind === "ok" ? "var(--ok)" : kind === "bad" ? "var(--bad)" : "var(--mut)";
}

chrome.storage.onChanged.addListener(render);
