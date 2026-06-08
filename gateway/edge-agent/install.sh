#!/usr/bin/env bash
# Install nvr-edge-agent on a Raspberry Pi gateway (Debian). Run as root from this directory,
# with the cross-compiled `nvr-edge-agent` binary present alongside this script.
#   sudo ./install.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="$HERE/nvr-edge-agent"

if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)."; exit 1; fi
if [[ ! -f "$BIN" ]]; then
  echo "Missing binary: $BIN"
  echo "Build it first (on your dev machine):  ./build.sh   then copy the edge-agent/ dir to the Pi."
  exit 1
fi

echo "==> Installing ZeroTier (overlay so NVRs reach this gateway across networks)"
# Idempotent: skip if already present. The agent only needs zerotier-one running + the CLI; the
# network it joins is NOT baked here — the Android app sets it per-region via POST /enroll (cách A).
if ! command -v zerotier-cli >/dev/null 2>&1; then
  if command -v curl >/dev/null 2>&1; then
    curl -s https://install.zerotier.com | bash
  else
    apt-get update -qq && apt-get install -y curl
    curl -s https://install.zerotier.com | bash
  fi
fi
systemctl enable --now zerotier-one.service 2>/dev/null || true

echo "==> Installing nginx (with stream module)"
if ! command -v nginx >/dev/null 2>&1; then
  apt-get update -qq
  # nginx-full ships ngx_stream; nginx-light does NOT.
  apt-get install -y nginx-full
fi

echo "==> Ensuring stream{} include in /etc/nginx/nginx.conf"
mkdir -p /etc/nginx/stream.d
if ! grep -q "stream.d/\*.conf" /etc/nginx/nginx.conf; then
  cat >> /etc/nginx/nginx.conf <<'EOF'

# Added by nvr-edge-agent — L4 proxy for field cameras.
stream {
    include /etc/nginx/stream.d/*.conf;
}
EOF
fi
# Start with an empty (valid) managed file so `nginx -t` passes before any camera is added.
[[ -f /etc/nginx/stream.d/cameras.conf ]] || echo "# Managed by nvr-edge-agent" > /etc/nginx/stream.d/cameras.conf

echo "==> nginx boot-race fix (proxy binds the ZeroTier IP, which may not exist yet at boot)"
# The managed stream{} blocks listen on <ztIP>:port. At boot nginx can start BEFORE zerotier-one
# brings that IP up -> "cannot assign requested address" -> nginx fails -> proxies dead until a
# manual start. Two defences:
#   1) ip_nonlocal_bind=1 — let nginx bind an address that isn't on any interface yet.
#   2) systemd drop-in — order nginx after zerotier-one and auto-restart if it still loses the race.
echo 'net.ipv4.ip_nonlocal_bind = 1' > /etc/sysctl.d/99-nvr-edge-nginx.conf
sysctl -q -w net.ipv4.ip_nonlocal_bind=1 || true

mkdir -p /etc/systemd/system/nginx.service.d
cat > /etc/systemd/system/nginx.service.d/override.conf <<'EOF'
# Added by nvr-edge-agent — nginx stream proxies bind the ZeroTier IP; wait for it + retry.
[Unit]
After=network-online.target zerotier-one.service
Wants=network-online.target

[Service]
Restart=on-failure
RestartSec=3
EOF

echo "==> Installing binary -> /usr/local/bin/nvr-edge-agent"
install -m 0755 "$BIN" /usr/local/bin/nvr-edge-agent

echo "==> Config -> /etc/nvr-edge-agent/agent.env"
mkdir -p /etc/nvr-edge-agent /var/lib/nvr-edge-agent
if [[ ! -f /etc/nvr-edge-agent/agent.env ]]; then
  install -m 0600 "$HERE/agent.env.example" /etc/nvr-edge-agent/agent.env
  echo "    -> created from example; EDIT IT (set EDGE_AGENT_TOKEN + ZT_NETWORK_ID) then restart."
else
  echo "    -> exists, left untouched."
fi

echo "==> systemd unit"
install -m 0644 "$HERE/nvr-edge-agent.service" /etc/systemd/system/nvr-edge-agent.service
systemctl daemon-reload
nginx -t && systemctl reload nginx || true
systemctl enable --now nvr-edge-agent.service

echo
echo "Done. Next:"
echo "  1) edit /etc/nvr-edge-agent/agent.env  (set EDGE_AGENT_TOKEN = fleet token),  then: systemctl restart nvr-edge-agent"
echo "     (leave ZT_NETWORK_ID empty — the Android app sets the region per-gateway via /enroll)"
echo "  2) field worker: open the app on the same field LAN -> scan -> nhập Network ID khu vực (POST /enroll)"
echo "  3) on the NVR Web UI -> tab Gateway -> mục 'Chờ cấp phép' -> 'Cấp phép' (Plan A; no ztncui)"
echo "  4) check:  systemctl status nvr-edge-agent ;  curl -s localhost:9797/health | head"
