#!/usr/bin/env bash
# Install beam-broker as a systemd *user* service (no root required).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
CONF_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/beam"

command -v python3 >/dev/null || { echo "python3 is required" >&2; exit 1; }
python3 -c 'import websockets' 2>/dev/null || {
  echo "The 'websockets' package is required:" >&2
  echo "  pip install --user websockets    # or your distro's python-websockets" >&2
  exit 1
}

mkdir -p "$UNIT_DIR" "$CONF_DIR"

# %h expands to the home directory, so the unit stays portable between users.
sed "s|%REPO%|${REPO}|g" "$REPO/broker/systemd/beam-broker.service" > "$UNIT_DIR/beam-broker.service"

systemctl --user daemon-reload
systemctl --user enable --now beam-broker

sleep 2
if curl -fsS "http://localhost:8780/api/health" >/dev/null 2>&1; then
  echo "✓ beam-broker is running"
else
  echo "! beam-broker did not answer on :8780 — check: journalctl --user -u beam-broker -n 30" >&2
fi

echo
echo "Pairing token (needed by the extension and the app):"
echo "    $(cat "$CONF_DIR/token" 2>/dev/null || echo '<generated on first start>')"
echo
echo "Next: load extension/ at chrome://extensions, then open"
echo "    http://<this-host>:8780/pair"
echo "on your phone."
echo
echo "To keep the broker running when you are logged out:"
echo "    sudo loginctl enable-linger $USER"
