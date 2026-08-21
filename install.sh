#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# install.sh — one-command bring-up on a fresh machine, FROM SOURCE.
#
# Builds nvr-wrapper locally from this working tree. No container registry is
# involved: no GHCR, no docker login, no GitHub token anywhere on the machine.
# Idempotent: safe to re-run. It fills every gap that stops a clean tree from
# starting:
#   1. installs Docker if missing
#   2. .env  from .env.template (+ validates the REQUIRED values)
#   3. self-signed TLS cert  (nginx/server.{crt,key})  if missing
#   4. Docker secret files    (secrets/*)               if missing
#   5. docker compose build && up   (+ nginx, which doesn't auto-start)
#
# Customer machines that get a prebuilt bundle (zip + images.tar) use
# ./deploy-offline.sh instead — that path builds nothing and also never touches
# a registry.
#
# Usage:  sudo ./install.sh              # full install
#         sudo ./install.sh -n kho-hanoi # full install, force HOSTNAME (shown in Telegram alerts)
#         ./install.sh --check           # validate env/secrets/cert, change nothing
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

CHECK_ONLY=0
FORCE_HOSTNAME=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --check) CHECK_ONLY=1; shift ;;
        -n) FORCE_HOSTNAME="${2:-}"; shift 2 ;;
        *) shift ;;
    esac
done

c() { printf '\033[1;36m%s\033[0m\n' "$*"; }   # info
ok() { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

need_root() { [[ $EUID -eq 0 ]] || die "Run as root: sudo ./install.sh"; }

# ── 1. Docker ────────────────────────────────────────────────────────────────
ensure_docker() {
    c "[1/5] Docker engine"
    if command -v docker >/dev/null 2>&1; then ok "docker present ($(docker --version | awk '{print $3}' | tr -d ,))"; return; fi
    [[ $CHECK_ONLY -eq 1 ]] && { warn "docker missing (would install)"; return; }
    need_root
    curl -fsSL https://get.docker.com | sh
    ok "docker installed"
}

# ── 2. .env ──────────────────────────────────────────────────────────────────
# NO shared credentials are baked into this script. Everything a device needs is
# either generated per-machine (MQTT passwords, fleet/overlay tokens, TLS key) or
# left empty. Rationale: a token in here lands plaintext in .env on every machine
# we ship, so one lost box = one lost shared account. Password recovery is offline
# (recovery code + security questions), image comes from this source tree, so there
# is nothing left that has to travel with the product.

REQUIRED_VARS=(FRIGATE_HOST_IP)

ensure_env() {
    c "[2/5] .env"
    if [[ ! -f .env ]]; then
        [[ $CHECK_ONLY -eq 1 ]] && die ".env missing (run without --check to create from template)"
        cp .env.template .env
        ok ".env created from template"
        # Auto-detect host IP so FRIGATE_HOST_IP isn't left blank
        local ip; ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
        [[ -n "$ip" ]] && sed -i "s|^FRIGATE_HOST_IP=.*|FRIGATE_HOST_IP=$ip|" .env && ok "FRIGATE_HOST_IP auto-set to $ip"
    else
        ok ".env exists"
    fi

    # HOSTNAME shows in every Telegram/watchdog alert ([hostname] prefix) so you can tell which
    # physical device is reporting — without this every machine reports the generic
    # "nvr-edge-node" fallback. Rules: -n <name> always forces it (fresh install or re-run);
    # otherwise auto-fill from the OS hostname ONLY if not already set (never clobber a name
    # someone already chose). No interactive prompt — stays scriptable/non-interactive.
    grep -qE '^HOSTNAME=' .env || printf '\nHOSTNAME=   # Shown in Telegram/watchdog alerts — rename to something recognizable (e.g. kho-hanoi)\n' >> .env
    local current_hn; current_hn="$(grep -E '^HOSTNAME=' .env | head -1 | cut -d= -f2- | tr -d '"'"'"' \r\t ')"
    if [[ -n "$FORCE_HOSTNAME" ]]; then
        sed -i "s|^HOSTNAME=.*|HOSTNAME=$FORCE_HOSTNAME|" .env
        ok "HOSTNAME set to '$FORCE_HOSTNAME' (via -n)"
    elif [[ -z "$current_hn" ]]; then
        local hn; hn="$(hostname 2>/dev/null || echo nvr-edge-node)"
        sed -i "s|^HOSTNAME=.*|HOSTNAME=$hn|" .env
        ok "HOSTNAME auto-set to '$hn' (rerun with -n <name> to rename, e.g. -n kho-hanoi)"
    else
        ok "HOSTNAME already set to '$current_hn' (unchanged; pass -n <name> to rename)"
    fi

    # Strip credentials left over from older installs that baked a GHCR account into .env.
    # The image is built locally now, so these only sit there waiting to leak.
    if grep -qE '^(GITHUB_USERNAME|GITHUB_TOKEN)=.*[^=[:space:]]' .env 2>/dev/null; then
        [[ $CHECK_ONLY -eq 1 ]] && warn "GITHUB_USERNAME/GITHUB_TOKEN present in .env (would remove — no registry is used)" || {
            sed -i -E '/^(GITHUB_USERNAME|GITHUB_TOKEN)=/d' .env
            warn "GITHUB_* removed from .env (legacy GHCR creds — REVOKE that token on GitHub)"; }
    fi

    # .env holds the per-machine MQTT passwords — 644 (the default) lets any local user read them.
    [[ $CHECK_ONLY -eq 1 ]] || { chmod 600 .env; ok ".env permissions 600 (owner-only)"; }

    # Validate required values are non-empty (strip optional surrounding quotes)
    local missing=() v val
    for v in "${REQUIRED_VARS[@]}"; do
        val="$(grep -E "^${v}=" .env 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"'"'"' \r' || true)"
        [[ -z "$val" ]] && missing+=("$v")
    done
    if (( ${#missing[@]} )); then
        warn "Required .env values not set: ${missing[*]}"
        warn "Edit .env and fill them in, then re-run."
        [[ $CHECK_ONLY -eq 1 ]] || die "Aborting until required .env values are set."
    else
        ok "all required .env values present"
    fi
}

# ── 3. TLS cert ──────────────────────────────────────────────────────────────
ensure_cert() {
    c "[3/5] nginx TLS cert"
    if [[ -s nginx/server.crt && -s nginx/server.key ]]; then ok "cert present"; return; fi
    [[ $CHECK_ONLY -eq 1 ]] && { warn "nginx/server.{crt,key} missing (would issue from internal CA)"; return; }
    mkdir -p nginx
    local ip; ip="$(hostname -I 2>/dev/null | awk '{print $1}')"; ip="${ip:-127.0.0.1}"
    # Internal-CA cert (SAN = LAN IP + nvr.local) so Boxes can verify TLS strictly after
    # trusting nginx/ca/ca.crt. Re-run nginx/gen-tls.sh with new SANs if the address changes.
    bash nginx/gen-tls.sh "$ip" nvr.local >/dev/null 2>&1
    ok "cert issued from internal CA (SAN: IP:$ip,DNS:nvr.local). Trust nginx/ca/ca.crt on each Box."
}

# ── 4. Docker secrets ────────────────────────────────────────────────────────
rand_token() { head -c 32 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 40; }

ensure_secrets() {
    c "[4/5] secrets/"
    mkdir -p secrets

    # Legacy: older installs baked a shared Gmail App Password here. Password recovery is
    # offline now, so the file is dead weight and a liability on a customer machine.
    if [[ -f secrets/gmail_app_password ]]; then
        [[ $CHECK_ONLY -eq 1 ]] && warn "secrets/gmail_app_password present (would remove — no longer used)" || {
            rm -f secrets/gmail_app_password; ok "gmail_app_password removed (no longer used)"; }
    fi

    # auto-generated fleet/overlay tokens
    local s
    for s in edge_agent_token nebula_sign_token; do
        if [[ ! -s "secrets/$s" ]]; then
            [[ $CHECK_ONLY -eq 1 ]] && { warn "secrets/$s missing (would generate)"; continue; }
            rand_token > "secrets/$s"; chmod 600 "secrets/$s"; ok "$s generated"
        else ok "$s present"; fi
    done

    # zt_controller_token — ZeroTier is legacy/dormant; an empty file satisfies the compose mount
    if [[ ! -f secrets/zt_controller_token ]]; then
        [[ $CHECK_ONLY -eq 1 ]] && warn "secrets/zt_controller_token missing (would touch empty)" || { : > secrets/zt_controller_token; chmod 600 secrets/zt_controller_token; ok "zt_controller_token (empty — ZeroTier dormant)"; }
    else ok "zt_controller_token present"; fi

    ensure_mqtt_auth
}

# ── 4b. MQTT internal-bus auth ───────────────────────────────────────────────
# mosquitto chạy allow_anonymous=false: cần 2 mật khẩu trong .env (frigate + nvr-backend)
# và file hash mosquitto/config/passwd khớp với chúng. Tự sinh nếu thiếu.
ensure_mqtt_auth() {
    local fp bp regen=0
    fp="$(grep -E '^MQTT_FRIGATE_PASSWORD=' .env 2>/dev/null | head -1 | cut -d= -f2- | sed 's/#.*//' | tr -d '"'"'"' \r\t ')"
    bp="$(grep -E '^MQTT_BACKEND_PASSWORD=' .env 2>/dev/null | head -1 | cut -d= -f2- | sed 's/#.*//' | tr -d '"'"'"' \r\t ')"
    grep -qE '^MQTT_FRIGATE_PASSWORD=' .env || printf '\nMQTT_FRIGATE_PASSWORD=\n' >> .env
    grep -qE '^MQTT_BACKEND_PASSWORD=' .env || printf 'MQTT_BACKEND_PASSWORD=\n' >> .env
    if [[ -z "$fp" ]]; then
        [[ $CHECK_ONLY -eq 1 ]] && warn "MQTT_FRIGATE_PASSWORD empty (would generate)" || {
            fp="$(rand_token)"; sed -i "s|^MQTT_FRIGATE_PASSWORD=.*|MQTT_FRIGATE_PASSWORD=$fp|" .env; regen=1; ok "MQTT_FRIGATE_PASSWORD generated"; }
    fi
    if [[ -z "$bp" ]]; then
        [[ $CHECK_ONLY -eq 1 ]] && warn "MQTT_BACKEND_PASSWORD empty (would generate)" || {
            bp="$(rand_token)"; sed -i "s|^MQTT_BACKEND_PASSWORD=.*|MQTT_BACKEND_PASSWORD=$bp|" .env; regen=1; ok "MQTT_BACKEND_PASSWORD generated"; }
    fi
    if [[ ! -s mosquitto/config/passwd || $regen -eq 1 ]]; then
        [[ $CHECK_ONLY -eq 1 ]] && { warn "mosquitto/config/passwd missing/stale (would generate)"; return; }
        docker run --rm -v "$PWD/mosquitto/config:/mc" eclipse-mosquitto:2 sh -c \
            "mosquitto_passwd -c -b /mc/passwd frigate '$fp' && mosquitto_passwd -b /mc/passwd nvr-backend '$bp' && chown mosquitto:mosquitto /mc/passwd && chmod 600 /mc/passwd" \
            && ok "mosquitto passwd file generated (frigate + nvr-backend)" \
            || die "mosquitto_passwd generation failed"
    else ok "mosquitto passwd present"; fi
}

# ── 5. Build & bring up ──────────────────────────────────────────────────────
bring_up() {
    c "[5/5] Build & start"
    if [[ ! -s data/dc-bridge ]]; then
        warn "data/dc-bridge binary missing — cloud Device-info/Ethernet/Time (WebRTC DATA) will be"
        warn "  unavailable until you build it (see connection_to_server.md). LIVE view still works."
    fi
    # nvr-wrapper is built here, not pulled — the tree must be the full source checkout.
    [[ -f Dockerfile && -d backend && -d frontend ]] \
        || die "Dockerfile/backend/frontend missing — install.sh builds from source. For a prebuilt bundle use ./deploy-offline.sh"
    if [[ $CHECK_ONLY -eq 1 ]]; then warn "would: docker compose build && up -d && up -d nginx"; return; fi
    mkdir -p data media mosquitto/data mosquitto/log config logs
    docker compose build nvr-wrapper nvr-watchdog
    docker compose up -d
    docker compose up -d nginx   # nginx does not auto-start when nvr-wrapper recreates
    ok "stack started"
}

# ── Run ──────────────────────────────────────────────────────────────────────
echo "════════════════════════════════════════════"
echo "  NVR production install  $([[ $CHECK_ONLY -eq 1 ]] && echo '(--check, read-only)')"
echo "════════════════════════════════════════════"
ensure_docker
ensure_env
ensure_cert
ensure_secrets
bring_up
echo "════════════════════════════════════════════"
if [[ $CHECK_ONLY -eq 1 ]]; then
    c "Check complete — nothing was changed."
else
    LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
    c "Done. Open: https://${LAN_IP:-<host-ip>}  (self-signed cert → accept the browser warning)"
    echo "  Image: nvr-wrapper:latest built from this tree (no registry, no OTA pull)"
    echo "  Rebuild after a code change:  ./run.sh --rebuild"
fi
