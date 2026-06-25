#!/usr/bin/env bash
# Install the Nebula nvr-edge-agent on a Raspberry Pi gateway (Debian). Run as root from this dir,
# with the cross-compiled `nvr-edge-agent` binary present alongside this script.
#   sudo ./install.sh
# This is the Nebula sibling of gateway/edge-agent/install.sh — it installs nebula (not zerotier-one);
# the nginx L4 proxy + agent are otherwise identical.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BIN="$HERE/nvr-edge-agent"
NEBULA_VERSION="${NEBULA_VERSION:-v1.9.5}"

if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)."; exit 1; fi
if [[ ! -f "$BIN" ]]; then
  echo "Missing binary: $BIN — build it first (./build.sh) and copy this dir to the Pi."
  exit 1
fi

arch() {
  case "$(uname -m)" in
    x86_64|amd64) echo amd64 ;;
    aarch64|arm64) echo arm64 ;;
    armv7l|armv6l) echo arm ;;
    *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
  esac
}

echo "==> Installing nebula ${NEBULA_VERSION} ($(arch)) — overlay so NVRs reach this gateway"
# The agent only needs the `nebula` daemon + `nebula-cert` (for local keygen). The cert itself is
# obtained at enroll time from the HQ signing service; nothing overlay-specific is baked here.
if ! command -v nebula >/dev/null 2>&1 || ! command -v nebula-cert >/dev/null 2>&1; then
  command -v curl >/dev/null 2>&1 || { apt-get update -qq && apt-get install -y curl; }
  tmp="$(mktemp -d)"
  url="https://github.com/slackhq/nebula/releases/download/${NEBULA_VERSION}/nebula-linux-$(arch).tar.gz"
  curl -fsSL "$url" -o "$tmp/nebula.tar.gz"
  tar -xzf "$tmp/nebula.tar.gz" -C "$tmp"
  install -m 0755 "$tmp/nebula" /usr/local/bin/nebula
  install -m 0755 "$tmp/nebula-cert" /usr/local/bin/nebula-cert
  rm -rf "$tmp"
fi
mkdir -p /etc/nebula

echo "==> Installing nginx (with stream module)"
if ! command -v nginx >/dev/null 2>&1; then
  apt-get update -qq
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
[[ -f /etc/nginx/stream.d/cameras.conf ]] || echo "# Managed by nvr-edge-agent" > /etc/nginx/stream.d/cameras.conf

echo "==> nginx boot-race fix (proxy binds the overlay IP, which may not exist yet at boot)"
echo 'net.ipv4.ip_nonlocal_bind = 1' > /etc/sysctl.d/99-nvr-edge-nginx.conf
sysctl -q -w net.ipv4.ip_nonlocal_bind=1 || true
mkdir -p /etc/systemd/system/nginx.service.d
cat > /etc/systemd/system/nginx.service.d/override.conf <<'EOF'
# Added by nvr-edge-agent — nginx stream proxies bind the Nebula overlay IP; wait for it + retry.
[Unit]
After=network-online.target nebula.service
Wants=network-online.target

[Service]
Restart=on-failure
RestartSec=3
EOF

echo "==> nebula systemd unit (idle until the agent writes config.yml on enroll)"
install -m 0644 "$HERE/nebula.service" /etc/systemd/system/nebula.service

echo "==> Installing binary -> /usr/local/bin/nvr-edge-agent"
install -m 0755 "$BIN" /usr/local/bin/nvr-edge-agent

echo "==> Config -> /etc/nvr-edge-agent/agent.env"
mkdir -p /etc/nvr-edge-agent /var/lib/nvr-edge-agent
if [[ ! -f /etc/nvr-edge-agent/agent.env ]]; then
  install -m 0600 "$HERE/agent.env.example" /etc/nvr-edge-agent/agent.env
  echo "    -> created from example; EDIT IT (set EDGE_AGENT_TOKEN = fleet token) then restart."
else
  echo "    -> exists, left untouched."
fi

echo "==> G4: dedicated unprivileged user + narrow sudoers (agent no longer runs as root)"
# System user with no login shell/home — owns the dirs the agent writes; root only via the 2 sudoers cmds.
if ! id -u nvragent >/dev/null 2>&1; then
  useradd --system --no-create-home --shell /usr/sbin/nologin nvragent
fi
# The agent (as nvragent) writes these; nebula/nginx run as root and only READ them.
# (agent.env stays 0600 root — systemd reads EnvironmentFile as root before dropping to nvragent.)
chown -R nvragent:nvragent /var/lib/nvr-edge-agent /etc/nebula /etc/nginx/stream.d
# Narrow sudoers: nginx reload + nebula restart ONLY. Validate before installing so a typo can't lock sudo.
install -m 0440 "$HERE/nvr-edge-agent.sudoers" /etc/sudoers.d/nvr-edge-agent
if ! visudo -cf /etc/sudoers.d/nvr-edge-agent >/dev/null; then
  echo "    !! sudoers file invalid — removing it; agent would fail to reload nginx/nebula." >&2
  rm -f /etc/sudoers.d/nvr-edge-agent
  exit 1
fi

echo "==> systemd unit (agent)"
install -m 0644 "$HERE/nvr-edge-agent.service" /etc/systemd/system/nvr-edge-agent.service
systemctl daemon-reload
# nebula is enabled so it auto-starts once the agent writes config.yml (ConditionPathExists guards it).
systemctl enable nebula.service 2>/dev/null || true
nginx -t && systemctl reload nginx || true
systemctl enable --now nvr-edge-agent.service

echo
echo "Done. Next:"
echo "  1) edit /etc/nvr-edge-agent/agent.env (EDGE_AGENT_TOKEN = fleet token), then: systemctl restart nvr-edge-agent"
echo "  2) field worker: app on the same field LAN -> scan -> nhập 'mã khu vực' (signing URL + token) -> POST /enroll"
echo "  3) NVR Web UI -> tab Gateway -> 'Chờ cấp phép' -> 'Cấp phép' (NVR calls the HQ signing service to sign the cert)"
echo "  4) check: systemctl status nvr-edge-agent nebula ; curl -s localhost:9797/health | head"
