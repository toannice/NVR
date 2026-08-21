#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# verify-package.sh — kiểm một file .nvrup ĐÚNG CÁCH MÁY FIELD SẼ KIỂM.
#
# Vì sao cần script riêng thay vì tin finalize-package.sh: finalize kiểm thư mục gói TRƯỚC
# khi nén. Script này kiểm chính FILE ĐÃ NÉN, tức là kiểm luôn cả bước tar — bắt được lỗi
# kiểu tar làm hỏng quyền, thiếu file, hoặc manifest trong gói khác bản đã ký.
#
# Kiểm đúng thứ tự máy field làm:
#   1. Chữ ký Ed25519 trên byte thô của manifest.json   (VERIFY TRƯỚC, PARSE SAU)
#   2. sha256 của từng payload khớp manifest
#   3. Đường dẫn overlay nằm trong whitelist
#   4. manifest trong gói BYTE-IDENTICAL với manifest deploy cạnh nó (nếu có)
#
# Bước 3 KHÔNG được bỏ, dù chữ ký đã hợp lệ. Đó là lớp phòng thủ cho đúng tình huống khoá ký
# bị lộ: lúc đó chữ ký không còn bảo vệ được gì, chỉ còn whitelist. Bản đầu của script này
# thiếu bước đó và đã báo "GÓI HỢP LỆ" cho một gói ghi đè .env (file chứa TELEGRAM_BOT_TOKEN
# và MQTT_BACKEND_PASSWORD của máy khách) — máy field vẫn chặn được, nhưng công cụ kiểm trước
# khi phát hành lại nói dối, đúng loại lỗi làm người ta mất tin vào cái check.
#
# Chạy:
#   ./ota/verify-package.sh releaseVersion/ota/nvr-AIVIEW-NVR-X1-1.0.0.3.nvrup
#   ./ota/verify-package.sh <gói> --remote-manifest releaseVersion/ota/deploy-1.0.0.3/manifest.json
# ──────────────────────────────────────────────────────────────────────────────
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

PKG=""
REMOTE_MANIFEST=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --remote-manifest) REMOTE_MANIFEST="$2"; shift 2 ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *) PKG="$1"; shift ;;
    esac
done

ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
bad()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*"; FAIL=1; }
c()    { printf '\033[1;36m%s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 2; }

[[ -n "$PKG" && -f "$PKG" ]] || die "dùng: $0 <file.nvrup>"
command -v jq >/dev/null || die "thiếu jq"

FAIL=0
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

c "[1/6] Giải nén $(basename "$PKG") ($(( $(stat -c%s "$PKG") / 1048576 ))MB)"
tar -xzf "$PKG" -C "$WORK" || die "giải nén thất bại — file hỏng hoặc không phải tar.gz"
[[ -f "$WORK/manifest.json" ]]     || die "trong gói không có manifest.json"
[[ -f "$WORK/manifest.json.sig" ]] || die "trong gói không có manifest.json.sig — gói chưa ký"
ok "giải nén được, có manifest + sig"

c "[2/6] Chữ ký Ed25519 (verify TRƯỚC khi parse)"
SIG_ALG="$(jq -r '.alg' "$WORK/manifest.json.sig" 2>/dev/null)"
[[ "$SIG_ALG" == "ed25519" ]] || die "alg='$SIG_ALG', chỉ nhận ed25519"
jq -r '.sig' "$WORK/manifest.json.sig" | base64 -d > "$WORK/raw.sig" 2>/dev/null \
    || die "trường sig không phải base64"
SB="$(stat -c%s "$WORK/raw.sig")"
[[ "$SB" -eq 64 ]] || die "chữ ký dài $SB byte, Ed25519 phải đúng 64"

MATCHED=""
for pub in "$HERE"/ota/keys/*.pub; do
    [[ -f "$pub" ]] || continue
    if openssl pkeyutl -verify -rawin -pubin -inkey "$pub" \
           -in "$WORK/manifest.json" -sigfile "$WORK/raw.sig" >/dev/null 2>&1; then
        MATCHED="$(basename "$pub")"
        kid="$(openssl pkey -pubin -in "$pub" -pubout -outform DER | sha256sum | cut -c1-8)"
        ok "chữ ký khớp $MATCHED (keyId $kid)"
        break
    fi
done
if [[ -z "$MATCHED" ]]; then
    bad "chữ ký KHÔNG khớp public key nào trong ota/keys/"
    die "dừng — không verify được thì không kiểm tiếp được gì"
fi
[[ "$MATCHED" == ota-dev* ]] && printf '\033[1;33m  ! ký bằng KHOÁ DEV — gói test, KHÔNG giao khách\033[0m\n'

c "[3/6] Nội dung manifest"
SCHEMA="$(jq -r '.schema' "$WORK/manifest.json")"
[[ "$SCHEMA" == "1" ]] || bad "schema=$SCHEMA, watchdog hiện chỉ đọc schema=1"
jq -r '"  model=\(.model)  version=\(.version)  minFrom=\(.minFrom // "-")  risk=\(.risk)  autoApply=\(.autoApply)"' \
    "$WORK/manifest.json"
jq -r '"  expiresAt=\(.expiresAt // "-")  requiresFreeBytes=\(.requiresFreeBytes / 1048576 | floor)MB"' \
    "$WORK/manifest.json"

# Hạn dùng đã qua chưa — bắt ở đây thì sửa được, để máy field bắt thì mất một đêm
EXP="$(jq -r '.expiresAt // ""' "$WORK/manifest.json")"
if [[ -n "$EXP" ]]; then
    if [[ "$(date -u -d "$EXP" +%s 2>/dev/null || echo 0)" -lt "$(date -u +%s)" ]]; then
        bad "manifest ĐÃ HẾT HẠN ($EXP) — máy field sẽ từ chối"
    else
        ok "còn hạn $(( ($(date -u -d "$EXP" +%s) - $(date -u +%s)) / 86400 )) ngày"
    fi
fi

c "[4/6] sha256 từng payload"
while IFS=$'\t' read -r p want; do
    [[ -z "$p" ]] && continue
    f="$WORK/$p"
    if [[ ! -f "$f" ]]; then bad "thiếu $p trong gói"; continue; fi
    got="$(sha256sum "$f" | cut -d' ' -f1)"
    if [[ "$got" == "$want" ]]; then ok "$p ($(( $(stat -c%s "$f") / 1048576 ))MB)"
    else bad "$p sha256 lệch (manifest ${want:0:16}… ≠ thật ${got:0:16}…)"; fi
done < <(jq -r '(.images[]? | select(.source=="tarball") | "\(.path)\t\(.sha256)"),
                (.files[]?  | "files/\(.path)\t\(.sha256)")' "$WORK/manifest.json")

# Image đi đường registry phải ghim digest — tag thuần bỏ mất phần toàn vẹn miễn phí
while read -r ref; do
    [[ -z "$ref" ]] && continue
    if [[ "$ref" == *"@sha256:"* ]]; then ok "registry ghim digest: ${ref:0:60}…"
    else bad "registry KHÔNG ghim digest: $ref"; fi
done < <(jq -r '.images[]? | select(.source=="registry") | .ref' "$WORK/manifest.json")

c "[5/6] Whitelist đường dẫn overlay"
# Trích thẳng từ OtaLayout.kt để không thể lệch với lớp chạy trên máy field.
source "$HERE/ota/lib/overlay-whitelist.sh"
NFILES=0
while read -r p; do
    [[ -z "$p" ]] && continue
    NFILES=$((NFILES + 1))
    if ota_whitelist_check "$p"; then
        ok "$p"
    else
        bad "$p — NGOÀI WHITELIST, máy field sẽ từ chối ghi file này"
    fi
done < <(jq -r '.files[]?.path' "$WORK/manifest.json")
[[ $NFILES -gt 0 ]] || printf '  – gói không có file overlay nào\n'

c "[6/6] Byte-identical với manifest deploy"
if [[ -n "$REMOTE_MANIFEST" ]]; then
    if [[ ! -f "$REMOTE_MANIFEST" ]]; then bad "không thấy $REMOTE_MANIFEST"
    elif cmp -s "$WORK/manifest.json" "$REMOTE_MANIFEST"; then
        ok "khớp từng byte với $(basename "$(dirname "$REMOTE_MANIFEST")")/manifest.json"
    else
        bad "manifest trong gói KHÁC manifest deploy — máy field sẽ từ chối
      (đây chính là gate chặn ghép manifest-của-bản-này với gói-của-bản-khác)"
    fi
else
    # Tự tìm thư mục deploy cạnh gói
    AUTO="$(dirname "$PKG")/deploy-$(jq -r '.version' "$WORK/manifest.json")/manifest.json"
    if [[ -f "$AUTO" ]]; then
        if cmp -s "$WORK/manifest.json" "$AUTO"; then ok "khớp từng byte với deploy-*/manifest.json"
        else bad "manifest trong gói KHÁC $AUTO"; fi
    else
        printf '  – bỏ qua (không có thư mục deploy cạnh gói)\n'
    fi
fi

echo
if [[ $FAIL -eq 0 ]]; then
    printf '\033[1;32m══ GÓI HỢP LỆ ══\033[0m\n'
    exit 0
else
    printf '\033[1;31m══ GÓI CÓ VẤN ĐỀ — KHÔNG deploy ══\033[0m\n'
    exit 1
fi
