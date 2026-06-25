#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# install.sh — one-command PRODUCTION bring-up on a fresh machine.
#
# Pulls the nvr-wrapper image from GHCR (does NOT build from source — that's the
# dev path, ./run.sh). Idempotent: safe to re-run. It fills every gap that stops a
# clean `git clone` from starting:
#   1. installs Docker if missing
#   2. .env  from .env.template (+ prompts for the REQUIRED values)
#   3. self-signed TLS cert  (nginx/server.{crt,key})  if missing
#   4. Docker secret files    (secrets/*)               if missing
#   5. `docker login ghcr.io`  (fails fast on a bad token)
#   6. docker compose pull && up   (+ nginx, which doesn't auto-start)
#
# Dev machines that want to build locally use ./run.sh and a (git-ignored)
# docker-compose.override.yml instead — do NOT create that override here.
#
# Usage:  sudo ./install.sh              # full install
#         ./install.sh --check           # validate env/secrets/cert, change nothing
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

c() { printf '\033[1;36m%s\033[0m\n' "$*"; }   # info
ok() { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die() { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

need_root() { [[ $EUID -eq 0 ]] || die "Run as root: sudo ./install.sh"; }

# ── 1. Docker ────────────────────────────────────────────────────────────────
ensure_docker() {
    c "[1/6] Docker engine"
    if command -v docker >/dev/null 2>&1; then ok "docker present ($(docker --version | awk '{print $3}' | tr -d ,))"; return; fi
    [[ $CHECK_ONLY -eq 1 ]] && { warn "docker missing (would install)"; return; }
    need_root
    curl -fsSL https://get.docker.com | sh
    ok "docker installed"
}

# ── 2. .env ──────────────────────────────────────────────────────────────────
# ── Shared credentials (baked in — no user input needed) ─────────────────────
_GITHUB_USERNAME="toannice"
_GITHUB_TOKEN="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"
_GMAIL_USER="nvr.bkav@gmail.com"
_GMAIL_APP_PASSWORD="nbeqpzxcjnujccvu"

REQUIRED_VARS=(WRAPPER_TAG FRIGATE_HOST_IP GITHUB_USERNAME GITHUB_TOKEN GMAIL_USER)

ensure_env() {
    c "[2/6] .env"
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

    # Bake shared credentials — always inject after fresh template copy; skip if already set on re-run
    local v val
    for v in GITHUB_USERNAME GITHUB_TOKEN GMAIL_USER; do
        local baked="_${v}"
        # Strip inline comments + whitespace to get actual value
        val="$(grep -E "^${v}=" .env 2>/dev/null | head -1 | cut -d= -f2- | sed 's/#.*//' | tr -d '"'"'"' \r\t ' || true)"
        if [[ -z "$val" ]]; then
            sed -i "s|^${v}=.*|${v}=${!baked}|" .env
            ok "$v auto-filled (shared account)"
        fi
    done

    # Validate required values are non-empty (strip optional surrounding quotes)
    local missing=() v val
    for v in "${REQUIRED_VARS[@]}"; do
        val="$(grep -E "^${v}=" .env 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"'"'"' \r' || true)"
        [[ -z "$val" ]] && missing+=("$v")
    done
    if (( ${#missing[@]} )); then
        warn "Required .env values not set: ${missing[*]}"
        warn "Edit .env and fill them in, then re-run. (GITHUB_TOKEN needs read:packages on ghcr.io)"
        [[ $CHECK_ONLY -eq 1 ]] || die "Aborting until required .env values are set."
    else
        ok "all required .env values present"
    fi
}

# ── 3. TLS cert ──────────────────────────────────────────────────────────────
ensure_cert() {
    c "[3/6] nginx TLS cert"
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
    c "[4/6] secrets/"
    mkdir -p secrets

    # gmail_app_password — shared account, baked in
    if [[ ! -s secrets/gmail_app_password ]]; then
        if [[ $CHECK_ONLY -eq 1 ]]; then warn "secrets/gmail_app_password missing (would auto-fill)"; else
            printf '%s' "$_GMAIL_APP_PASSWORD" > secrets/gmail_app_password
            chmod 600 secrets/gmail_app_password
            ok "gmail_app_password auto-filled (shared account)"
        fi
    else ok "gmail_app_password present"; fi

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
}

# ── 5. GHCR login ────────────────────────────────────────────────────────────
ghcr_login() {
    c "[5/6] GHCR login"
    set -a; source .env; set +a
    [[ -z "${GITHUB_TOKEN:-}" || -z "${GITHUB_USERNAME:-}" ]] && { warn "GITHUB_* not set — skipping login (pull will fail for private images)"; return; }
    if [[ $CHECK_ONLY -eq 1 ]]; then warn "would docker login ghcr.io as $GITHUB_USERNAME"; return; fi
    echo "$GITHUB_TOKEN" | docker login ghcr.io -u "$GITHUB_USERNAME" --password-stdin >/dev/null 2>&1 \
        && ok "logged in to ghcr.io as $GITHUB_USERNAME" \
        || die "GHCR login failed — check GITHUB_USERNAME / GITHUB_TOKEN (needs read:packages)"
}

# ── 6. Bring up ──────────────────────────────────────────────────────────────
bring_up() {
    c "[6/6] Pull & start"
    if [[ ! -s data/dc-bridge ]]; then
        warn "data/dc-bridge binary missing — cloud Device-info/Ethernet/Time (WebRTC DATA) will be"
        warn "  unavailable until you build it (see connection_to_server.md). LIVE view still works."
    fi
    if [[ $CHECK_ONLY -eq 1 ]]; then warn "would: docker compose pull && up -d && up -d nginx"; return; fi
    mkdir -p data media mosquitto/data mosquitto/log config logs
    docker compose pull
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
ghcr_login
bring_up
echo "════════════════════════════════════════════"
if [[ $CHECK_ONLY -eq 1 ]]; then
    c "Check complete — nothing was changed."
else
    LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
    c "Done. Open: https://${LAN_IP:-<host-ip>}  (self-signed cert → accept the browser warning)"
    echo "  OTA channel: WRAPPER_TAG=$(grep -E '^WRAPPER_TAG=' .env 2>/dev/null | cut -d= -f2- | tr -d '\"' || echo latest)  (latest=stable, staging=beta)"
    echo "  Dev rebuild from source instead:  ./run.sh --rebuild"
fi
