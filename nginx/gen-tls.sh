#!/usr/bin/env bash
# nginx/gen-tls.sh — Internal-CA TLS for the NVR↔Box integration (and the NVR Web UI).
#
# Creates a long-lived internal CA ONCE (nginx/ca/ca.{key,crt}), then issues an nginx
# server (leaf) cert signed by it with proper SAN entries. A Box that trusts ca.crt will
# verify the NVR strictly — no more "self-signed / cannot connect".
#
# Re-running to add or change an address re-issues the leaf from the SAME CA, so every Box
# that already installed ca.crt keeps working WITHOUT re-provisioning/reflash.
#
# Usage:
#   nginx/gen-tls.sh                       # auto: primary LAN IP + nvr.local
#   nginx/gen-tls.sh 192.168.2.107 nvr.local 192.168.2.50   # explicit SAN list
#
# Distribute nginx/ca/ca.crt to each Box's trust store. NEVER share ca.key.
set -euo pipefail
cd "$(dirname "$0")"                       # → nginx/

CA_DIR=ca
CA_KEY="$CA_DIR/ca.key"
CA_CRT="$CA_DIR/ca.crt"
SRV_KEY=server.key
SRV_CRT=server.crt

# ── Collect SAN hosts ────────────────────────────────────────────────────────
HOSTS=("$@")
if [[ ${#HOSTS[@]} -eq 0 ]]; then
    ip="$(hostname -I 2>/dev/null | awk '{print $1}')"; ip="${ip:-127.0.0.1}"
    HOSTS=("$ip" "nvr.local")
fi
HOSTS+=("127.0.0.1" "localhost")          # always allow local health checks

# de-dupe + classify IP vs DNS
san=""; declare -A seen
for h in "${HOSTS[@]}"; do
    [[ -z "$h" || -n "${seen[$h]:-}" ]] && continue
    seen[$h]=1
    if [[ "$h" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then kind=IP; else kind=DNS; fi
    san+="${san:+,}${kind}:${h}"
done

mkdir -p "$CA_DIR"

# ── 1) Internal CA (create once, reuse forever) ──────────────────────────────
if [[ -s "$CA_KEY" && -s "$CA_CRT" ]]; then
    echo "CA: reusing existing $CA_CRT"
else
    openssl genrsa -out "$CA_KEY" 4096 2>/dev/null
    chmod 600 "$CA_KEY"
    openssl req -x509 -new -nodes -key "$CA_KEY" -sha256 -days 7300 \
        -subj "/C=VN/O=AIVIEW/CN=AIVIEW NVR Internal CA" -out "$CA_CRT" 2>/dev/null
    echo "CA: created $CA_CRT (valid 20y)"
fi

# ── 2) Server leaf cert signed by the CA, with SAN ───────────────────────────
csr="$(mktemp)"; ext="$(mktemp)"
trap 'rm -f "$csr" "$ext"' EXIT

openssl genrsa -out "$SRV_KEY" 2048 2>/dev/null
chmod 600 "$SRV_KEY"
openssl req -new -key "$SRV_KEY" -subj "/C=VN/O=AIVIEW/CN=nvr.local" -out "$csr" 2>/dev/null

cat > "$ext" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=$san
EOF

openssl x509 -req -in "$csr" -CA "$CA_CRT" -CAkey "$CA_KEY" -CAcreateserial \
    -days 825 -sha256 -extfile "$ext" -out "$SRV_CRT" 2>/dev/null

echo "Server cert issued: $SRV_CRT (valid 825d)"
echo "  SAN = $san"
echo "  Install on each Box: $(pwd)/$CA_CRT"
