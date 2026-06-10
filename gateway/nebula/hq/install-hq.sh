#!/usr/bin/env bash
# install-hq.sh — stand up the HQ control plane for the Nebula overlay on an always-on box (the
# Nebula replacement for hq-controller/install-controller.sh + ztncui). It installs:
#   - nebula + nebula-cert binaries
#   - a CA (default.{crt,key}) under /etc/nebula-hq/ca   (the trust root; back this up!)
#   - a lighthouse (am_lighthouse + am_relay) at the overlay IP .1, listening UDP 4242
#   - the nvr-nebula-signing service (built from ../signing-service) + systemd unit
#
# Run as root from this directory:  sudo ./install-hq.sh
# Idempotent: re-running won't overwrite an existing CA or lighthouse identity.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
NEBULA_VERSION="${NEBULA_VERSION:-v1.9.5}"
OVERLAY_CIDR="${OVERLAY_CIDR:-10.10.20.0/24}"
LH_OVERLAY_IP="${LH_OVERLAY_IP:-10.10.20.1}"
CA_NAME="${CA_NAME:-AIView-HQ}"

if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)."; exit 1; fi

arch() {
  case "$(uname -m)" in
    x86_64|amd64) echo amd64 ;;
    aarch64|arm64) echo arm64 ;;
    armv7l|armv6l) echo arm ;;
    *) echo "unsupported arch $(uname -m)" >&2; exit 1 ;;
  esac
}

echo "==> Installing nebula ${NEBULA_VERSION} ($(arch))"
if ! command -v nebula >/dev/null 2>&1 || ! command -v nebula-cert >/dev/null 2>&1; then
  tmp="$(mktemp -d)"
  url="https://github.com/slackhq/nebula/releases/download/${NEBULA_VERSION}/nebula-linux-$(arch).tar.gz"
  echo "    downloading $url"
  curl -fsSL "$url" -o "$tmp/nebula.tar.gz"
  tar -xzf "$tmp/nebula.tar.gz" -C "$tmp"
  install -m 0755 "$tmp/nebula" /usr/local/bin/nebula
  install -m 0755 "$tmp/nebula-cert" /usr/local/bin/nebula-cert
  rm -rf "$tmp"
fi

echo "==> Creating CA (trust root) under /etc/nebula-hq/ca"
mkdir -p /etc/nebula-hq/ca /var/lib/nebula-hq /etc/nebula
if [[ ! -f /etc/nebula-hq/ca/default.crt ]]; then
  ( cd /etc/nebula-hq/ca && nebula-cert ca -name "$CA_NAME" -out-crt default.crt -out-key default.key )
  chmod 600 /etc/nebula-hq/ca/default.key
  echo "    -> created. BACK UP /etc/nebula-hq/ca/default.key — losing it loses the whole overlay."
else
  echo "    -> exists, left untouched."
fi

echo "==> Lighthouse identity + config (/etc/nebula)"
if [[ ! -f /etc/nebula/host.crt ]]; then
  ( cd /etc/nebula-hq/ca && nebula-cert sign \
      -ca-crt default.crt -ca-key default.key \
      -name "lighthouse" -ip "${LH_OVERLAY_IP}/${OVERLAY_CIDR##*/}" -groups "lighthouse" \
      -out-crt /etc/nebula/host.crt -out-key /etc/nebula/host.key )
  cp /etc/nebula-hq/ca/default.crt /etc/nebula/ca.crt
fi
# Lighthouse config: am_lighthouse + am_relay so it also relays when a gateway/NVR pair can't punch.
if [[ ! -f /etc/nebula/config.yml ]]; then
  cat > /etc/nebula/config.yml <<EOF
# Nebula LIGHTHOUSE config (HQ). Managed by install-hq.sh.
pki:
  ca: /etc/nebula/ca.crt
  cert: /etc/nebula/host.crt
  key: /etc/nebula/host.key

# A lighthouse keeps an empty static_host_map; everyone reports to it.
static_host_map: {}

lighthouse:
  am_lighthouse: true

listen:
  host: 0.0.0.0
  port: 4242

punchy:
  punch: true
  respond: true

relay:
  am_relay: true
  use_relays: false

tun:
  dev: nebula1
  mtu: 1300

firewall:
  conntrack:
    tcp_timeout: 12m
    udp_timeout: 3m
    default_timeout: 10m
  outbound:
    - port: any
      proto: any
      host: any
  inbound:
    - port: any
      proto: any
      host: any
EOF
fi

echo "==> nebula lighthouse systemd unit"
install -m 0644 "$HERE/nebula-lighthouse.service" /etc/systemd/system/nebula.service

echo "==> Building nvr-nebula-signing"
if command -v go >/dev/null 2>&1; then
  ( cd "$HERE/../signing-service" && go build -o /usr/local/bin/nvr-nebula-signing . )
else
  echo "    go not found — copy a prebuilt nvr-nebula-signing to /usr/local/bin yourself."
fi

echo "==> signing.env"
if [[ ! -f /etc/nebula-hq/signing.env ]]; then
  install -m 0600 "$HERE/signing.env.example" /etc/nebula-hq/signing.env
  echo "    -> created from example; EDIT IT (NEBULA_SIGN_TOKEN + NEBULA_LIGHTHOUSE) then restart."
else
  echo "    -> exists, left untouched."
fi
install -m 0644 "$HERE/nvr-nebula-signing.service" /etc/systemd/system/nvr-nebula-signing.service

systemctl daemon-reload
systemctl enable --now nebula.service
systemctl enable --now nvr-nebula-signing.service || \
  echo "    (signing service not started — set NEBULA_SIGN_TOKEN in /etc/nebula-hq/signing.env first)"

echo
echo "Done. Next:"
echo "  1) Open UDP 4242 to the public internet (lighthouse). Use a static IP or DDNS."
echo "  2) Edit /etc/nebula-hq/signing.env: NEBULA_SIGN_TOKEN (= NVR secrets/nebula_sign_token),"
echo "     NEBULA_LIGHTHOUSE=<public-ip>:4242 ; then: systemctl restart nvr-nebula-signing"
echo "  3) Mint a region token (admin):"
echo "       curl -s -X POST localhost:9443/tokens -H 'Authorization: Bearer <admin-token>' \\"
echo "            -d '{\"region\":\"nha-A\",\"ipCidr\":\"${OVERLAY_CIDR}\"}'"
echo "     Hand the returned token + lighthouse to the field worker as the 'mã khu vực'."
echo "  4) Check: systemctl status nebula nvr-nebula-signing ; curl -s localhost:9443/healthz"
