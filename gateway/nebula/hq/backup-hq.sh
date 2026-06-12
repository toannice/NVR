#!/usr/bin/env bash
# backup-hq.sh — snapshot the HQ trust material so a dead HQ box can be rebuilt.
#
# Losing /etc/nebula-hq/ca/*.key loses the WHOLE overlay (every gateway + NVR cert must be re-issued),
# so this runs on a timer (nvr-hq-backup.timer) and keeps the last N snapshots. It captures:
#   - the CA dir (all <name>.crt/.key — the trust root)
#   - the signing service state (tokens + enrollment requests → which gateway has which overlay IP)
#
# ⚠️ A backup that lives only on the HQ box dies with it. COPY THE TARBALLS OFFSITE (another machine /
# encrypted object storage). The .sha256 lets you verify integrity after the copy.
set -euo pipefail

CA_DIR="${NEBULA_CA_DIR:-/etc/nebula-hq/ca}"
STATE_FILE="${NEBULA_SIGN_STATE:-/var/lib/nebula-hq/signing-state.json}"
BACKUP_DIR="${HQ_BACKUP_DIR:-/var/backups/nebula-hq}"
KEEP="${HQ_BACKUP_KEEP:-14}"

if [[ $EUID -ne 0 ]]; then echo "Run as root (sudo)."; exit 1; fi
if [[ ! -d "$CA_DIR" ]]; then echo "CA dir $CA_DIR missing — is the HQ installed?"; exit 1; fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

ts="$(date -u +%Y%m%dT%H%M%SZ)"
out="$BACKUP_DIR/nebula-hq-$ts.tar.gz"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# CA material (always present); signing state may be absent on a brand-new box — that's fine.
cp -a "$CA_DIR" "$tmp/ca"
if [[ -f "$STATE_FILE" ]]; then cp -a "$STATE_FILE" "$tmp/$(basename "$STATE_FILE")"; fi

tar -czf "$out" -C "$tmp" .
chmod 600 "$out"
sha256sum "$out" > "$out.sha256"

# Retention: keep the newest $KEEP tarballs (+ their .sha256), drop the rest.
mapfile -t old < <(ls -1t "$BACKUP_DIR"/nebula-hq-*.tar.gz 2>/dev/null | tail -n +"$((KEEP + 1))")
for f in "${old[@]:-}"; do
  [[ -n "$f" ]] && rm -f "$f" "$f.sha256"
done

echo "backup -> $out ($(du -h "$out" | cut -f1)); kept last $KEEP. COPY THIS OFFSITE."
