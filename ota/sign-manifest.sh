#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# sign-manifest.sh — ký manifest.json. CHẠY TRÊN MÁY AIR-GAP.
#
# Chỉ manifest.json (~1KB) đi qua USB vào máy này. Image 200MB-3GB không bao giờ chạm máy
# giữ khoá — đó là lợi ích quyết định của việc chữ ký chỉ phủ manifest, còn manifest thì
# chứa sha256 của từng payload (phủ toàn bộ gói một cách bắc cầu, giống file Release của apt).
#
# Script này KHÔNG BIẾT và KHÔNG CẦN BIẾT nó đang chạy trên máy air-gap hay trong một thư mục
# trên máy dev. Air-gap là tính chất VẬT LÝ/QUY TRÌNH, không phải tính chất của code — nên
# cùng script này dùng được cho cả lúc dev (khoá dev) và lúc phát hành thật (khoá air-gap).
#
# Chạy:
#   bash sign-manifest.sh /media/usb/manifest.json                    # dùng ~/nvr-ota-keys
#   bash sign-manifest.sh manifest.json --key ~/keys/ota-backup.key   # chỉ định khoá
#   bash sign-manifest.sh manifest.json --yes                         # bỏ bước hỏi (CHỈ khi dev)
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

MANIFEST=""
KEY=""
ASSUME_YES=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --key)  KEY="$2"; shift 2 ;;
        --yes)  ASSUME_YES=1; shift ;;
        -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
        *)      MANIFEST="$1"; shift ;;
    esac
done

c()    { printf '\033[1;36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

[[ -n "$MANIFEST" ]]  || die "thiếu đường dẫn manifest.json. Xem: $0 --help"
[[ -f "$MANIFEST" ]]  || die "không thấy $MANIFEST"
command -v openssl >/dev/null || die "không có openssl"

# Khoá mặc định: ota-primary trong thư mục airgap-setup.sh đã tạo
if [[ -z "$KEY" ]]; then
    for cand in "$HOME/nvr-ota-keys/ota-primary.key" "./ota-primary.key"; do
        [[ -f "$cand" ]] && { KEY="$cand"; break; }
    done
fi
[[ -n "$KEY" && -f "$KEY" ]] || die "không tìm thấy private key. Dùng --key <đường-dẫn>"

# Khoá phải là Ed25519 — nếu không thì -rawin sẽ lỗi khó hiểu ở dưới
openssl pkey -in "$KEY" -noout -text 2>/dev/null | grep -qi "ED25519" \
    || die "$KEY không phải khoá Ed25519"

KEYID="$(openssl pkey -in "$KEY" -pubout -outform DER | sha256sum | cut -c1-8)"

# ── Đọc lại bằng mắt trước khi ký ────────────────────────────────────────────
# Đây là LỚP CHỐNG LỖI NGƯỜI DUY NHẤT ở bước này. Máy air-gap không có cách nào tự biết
# file nó đang ký có đúng là bản bạn định phát hành hay không — nó không thấy cây source,
# không thấy image, không nối mạng để đối chiếu. Chỉ mắt bạn biết.
c "════════════════════════════════════════════════════════════════"
c " SẮP KÝ — ĐỌC LẠI CHO KỸ"
c "════════════════════════════════════════════════════════════════"

# jq nếu có (đẹp hơn), không có thì grep — máy air-gap cài tối giản là chuyện thường
if command -v jq >/dev/null; then
    jq -r '
      "  model          : \(.model)",
      "  version        : \(.version)",
      "  minFrom        : \(.minFrom // "(không)")",
      "  minApplier     : \(.minApplier // "(không)")",
      "  expiresAt      : \(.expiresAt // "(không hạn)")",
      "  risk           : \(.risk)   autoApply: \(.autoApply)",
      "  schema         : \(.schema)",
      "  images         : \([.images[]? | "\(.name)[\(.source)]"] | join(", "))",
      "  files          : \(.files | length) file",
      "  package.url    : \(.package.url // "(không có — gói upload tay)")"
    ' "$MANIFEST" || die "manifest.json không phải JSON hợp lệ"
else
    warn "không có jq — hiển thị thô"
    grep -oE '"(model|version|minFrom|minApplier|expiresAt|risk|autoApply|schema)"[[:space:]]*:[[:space:]]*[^,}]+' "$MANIFEST" \
        | sed 's/^/  /'
fi

echo
echo "  Khoá ký       : $(basename "$KEY")  (keyId $KEYID)"
echo "  sha256 manifest: $(sha256sum "$MANIFEST" | cut -c1-16)…"
c "════════════════════════════════════════════════════════════════"

if [[ $ASSUME_YES -eq 0 ]]; then
    cat <<'EOF'

  Kiểm ba điểm dễ sai nhất:
    · version ĐÚNG bản bạn vừa build (không phải bản cũ còn sót trên USB)?
    · minFrom có chặn đúng những máy field cũ cần đi qua bản trung gian?
    · risk=high (có Frigate) thì autoApply đã đúng ý bạn?

EOF
    printf '\033[1;33m  → Ký? Gõ đúng version ở trên để xác nhận: \033[0m'
    read -r typed
    actual="$(grep -oE '"version"[[:space:]]*:[[:space:]]*"[^"]+"' "$MANIFEST" | head -1 | sed -E 's/.*"([^"]+)"$/\1/')"
    [[ "$typed" == "$actual" ]] || die "gõ '$typed' nhưng manifest là '$actual' — dừng lại, không ký"
fi

# ── Ký ───────────────────────────────────────────────────────────────────────
# -rawin là BẮT BUỘC với Ed25519: nó ký toàn bộ message chứ không pre-hash như RSA/ECDSA.
# Đây chính là thứ phải khớp với Signature.getInstance("Ed25519").update(bytes) ở phía JDK.
SIG="${MANIFEST}.sig"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

openssl pkeyutl -sign -rawin -inkey "$KEY" -in "$MANIFEST" -out "$TMP"

# Tự verify lại NGAY, bằng public key dẫn xuất từ chính khoá vừa ký. Bắt trường hợp khoá
# hỏng hoặc openssl có vấn đề — thà biết ở đây còn hơn để máy field từ chối gói.
PUB="$(mktemp)"; trap 'rm -f "$TMP" "$PUB"' EXIT
openssl pkey -in "$KEY" -pubout -out "$PUB"
openssl pkeyutl -verify -rawin -pubin -inkey "$PUB" -in "$MANIFEST" -sigfile "$TMP" >/dev/null \
    || die "vừa ký xong mà verify lại không khớp — KHÔNG dùng chữ ký này"

printf '{"keyId":"%s","alg":"ed25519","sig":"%s"}\n' "$KEYID" "$(base64 -w0 "$TMP")" > "$SIG"

ok "đã ký: $SIG"
ok "tự verify lại: khớp"
echo
echo "  Mang $(basename "$SIG") về máy dev, rồi chạy:"
echo "    ./ota/finalize-package.sh <thư-mục-gói>"
