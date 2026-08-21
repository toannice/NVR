#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# finalize-package.sh — kiểm chữ ký rồi nén thư mục gói thành file .nvrup.
#
# Chạy trên máy DEV, sau khi manifest.json.sig từ máy air-gap đã về.
#
# `.nvrup` LÀ một tar.gz — đuôi chỉ là nhãn cho người và cho UI biết đây là gói cập nhật NVR,
# không phải định dạng mới. `tar -tzf x.nvrup` chạy bình thường.
#
# Script này verify chữ ký bằng ĐÚNG cách máy field sẽ verify, TRƯỚC khi đóng gói. Bắt được
# ở đây thì mất 10 giây; để máy field bắt thì mất một đêm của cả fleet.
#
# Chạy:  ./ota/finalize-package.sh releaseVersion/ota/1.1.0.0
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$HERE"

DIR="${1:-}"

c()    { printf '\033[1;36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }
step() { printf '\n\033[1;35m━━━ [%s] %s ━━━\033[0m\n' "$1" "$2"; }

[[ -n "$DIR" ]]      || die "dùng: $0 <thư-mục-gói>   (vd releaseVersion/ota/1.1.0.0)"
[[ -d "$DIR" ]]      || die "không thấy thư mục $DIR"
DIR="${DIR%/}"

MANIFEST="$DIR/manifest.json"
SIG="$DIR/manifest.json.sig"

[[ -f "$MANIFEST" ]] || die "không thấy $MANIFEST — chạy build-package.sh trước"
[[ -f "$SIG" ]]      || die "không thấy $SIG — chưa ký. Xem hướng dẫn ở cuối build-package.sh"

command -v jq >/dev/null || die "thiếu jq"

MODEL="$(jq -r '.model'   "$MANIFEST")"
VERSION="$(jq -r '.version' "$MANIFEST")"
PKG="$(cd "$DIR/.." && pwd)/nvr-${MODEL}-${VERSION}.nvrup"

# ── 1. Verify chữ ký bằng mọi public key tin cậy trong repo ──────────────────
step "1/4" "Verify chữ ký"

KEYS_DIR="ota/keys"
[[ -d "$KEYS_DIR" ]] || die "không thấy $KEYS_DIR — cần public key để verify"
mapfile -t PUBS < <(find "$KEYS_DIR" -name '*.pub' | sort)
[[ ${#PUBS[@]} -gt 0 ]] || die "không có file .pub nào trong $KEYS_DIR"

SIG_KEYID="$(jq -r '.keyId' "$SIG")"
SIG_ALG="$(jq -r '.alg'   "$SIG")"
[[ "$SIG_ALG" == "ed25519" ]] || die "alg='$SIG_ALG', chỉ nhận ed25519"

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
jq -r '.sig' "$SIG" | base64 -d > "$TMP/raw.sig"

SIG_BYTES="$(stat -c%s "$TMP/raw.sig")"
[[ "$SIG_BYTES" -eq 64 ]] || die "chữ ký dài $SIG_BYTES byte, Ed25519 phải đúng 64"

MATCHED=""
for pub in "${PUBS[@]}"; do
    kid="$(openssl pkey -pubin -in "$pub" -pubout -outform DER 2>/dev/null | sha256sum | cut -c1-8)"
    if openssl pkeyutl -verify -rawin -pubin -inkey "$pub" \
           -in "$MANIFEST" -sigfile "$TMP/raw.sig" >/dev/null 2>&1; then
        MATCHED="$pub"
        ok "chữ ký khớp $(basename "$pub")  keyId=$kid"
        [[ "$kid" == "$SIG_KEYID" ]] || warn "file .sig khai keyId=$SIG_KEYID nhưng khoá thật là $kid (vô hại, chỉ là gợi ý)"
        break
    fi
done
[[ -n "$MATCHED" ]] || die "chữ ký KHÔNG khớp public key nào trong $KEYS_DIR
      đã thử: $(printf '%s ' "${PUBS[@]##*/}")
      → ký bằng khoá không có public key trong repo? Hoặc manifest đã bị sửa sau khi ký?"

# CHỐT AN TOÀN: khoá dev không được đi ra bản phát hành thật. Máy khách tin khoá dev nghĩa là
# bất cứ ai chạm được máy dev đều đẩy được bản cập nhật vào máy khách đó.
if [[ "$(basename "$MATCHED")" == ota-dev* ]]; then
    if [[ "${OTA_ALLOW_DEV_KEY:-0}" == "1" ]]; then
        warn "KÝ BẰNG KHOÁ DEV ($(basename "$MATCHED")) — gói này CHỈ dùng để test, KHÔNG giao khách"
    else
        die "gói được ký bằng KHOÁ DEV ($(basename "$MATCHED")).
      Khoá dev không được dùng cho bản phát hành thật. Ký lại bằng ota-primary trên máy air-gap.
      Nếu đang test thật: OTA_ALLOW_DEV_KEY=1 $0 $DIR"
    fi
fi

# ── 2. Đối chiếu sha256 của từng payload với manifest ────────────────────────
step "2/4" "Đối chiếu sha256 payload"

FAIL=0
while IFS=$'\t' read -r p want; do
    [[ -z "$p" ]] && continue
    f="$DIR/$p"
    [[ -f "$f" ]] || { printf '\033[1;31m  ✗ thiếu %s\033[0m\n' "$p"; FAIL=1; continue; }
    got="$(sha256sum "$f" | cut -d' ' -f1)"
    if [[ "$got" == "$want" ]]; then ok "$p"
    else printf '\033[1;31m  ✗ %s sha256 lệch\n      manifest: %s\n      thật    : %s\033[0m\n' "$p" "$want" "$got"; FAIL=1
    fi
done < <(jq -r '(.images[]? | select(.source=="tarball") | "\(.path)\t\(.sha256)"),
                (.files[]?  | "files/\(.path)\t\(.sha256)")' "$MANIFEST")

[[ $FAIL -eq 0 ]] || die "payload không khớp manifest — build lại, đừng sửa tay manifest sau khi ký"

# ── 3. Kiểm đường dẫn overlay nằm trong whitelist ────────────────────────────
step "3/4" "Kiểm whitelist đường dẫn overlay"

# Whitelist trích THẲNG từ OtaLayout.kt — không giữ bản copy ở đây, vì hai bản danh sách
# riêng sẽ lệch nhau theo hướng tệ nhất: bash nói "hợp lệ", máy field từ chối ở tận hiện trường.
source "$HERE/ota/lib/overlay-whitelist.sh"

BAD=0
while read -r p; do
    [[ -z "$p" ]] && continue
    if ota_whitelist_check "$p"; then
        ok "$p"
    else
        printf '\033[1;31m  ✗ %s — ngoài whitelist, máy field sẽ từ chối\033[0m\n' "$p"; BAD=1
    fi
done < <(jq -r '.files[]?.path' "$MANIFEST")
[[ $BAD -eq 0 ]] || die "có đường dẫn overlay ngoài whitelist
      Whitelist hiện tại (từ OtaLayout.kt):
$(ota_whitelist_prefixes | sed 's/^/        /')"

# ── 4. Nén ───────────────────────────────────────────────────────────────────
step "4/4" "Nén → $(basename "$PKG")"

mkdir -p "$(dirname "$PKG")"
# --sort=name + mtime cố định: hai lần build cùng nội dung cho ra file giống nhau, nên so
# sánh được bản đã phát hành với bản build lại. Payload đã nén zstd nên tar không nén thêm (-c
# không -z) sẽ nhanh hơn mà gần như cùng kích thước... nhưng dùng gzip để `tar -tzf` và các
# công cụ thông thường nhận ra ngay đây là tar.gz.
tar --sort=name --mtime='2020-01-01 00:00:00Z' --owner=0 --group=0 --numeric-owner \
    -czf "$PKG" -C "$DIR" .

PKG_SHA="$(sha256sum "$PKG" | cut -d' ' -f1)"
PKG_SIZE="$(stat -c%s "$PKG")"
ok "$(basename "$PKG")  $((PKG_SIZE / 1048576))MB"

# Thư mục deploy = ĐÚNG ba file, không thêm bớt gì.
#
# manifest.json ở đây BYTE-IDENTICAL với bản trong gói — đó chính là thứ máy field đối chiếu
# sau khi giải nén, nên không được sinh ra một biến thể nào khác. Cụ thể: KHÔNG thêm sha256
# hay size của .nvrup vào (vừa vòng tròn vì gói chứa manifest, vừa dư thừa vì từng payload đã
# có sha256 và manifest đã có chữ ký). Một file "manifest có thêm chút thông tin" mà chưa ký,
# nằm cạnh file đã ký, là mời người ta deploy nhầm.
REMOTE_DIR="$(cd "$DIR/.." && pwd)/deploy-$VERSION"
rm -rf "$REMOTE_DIR"; mkdir -p "$REMOTE_DIR"
cp "$MANIFEST" "$SIG" "$REMOTE_DIR/"
cp "$PKG" "$REMOTE_DIR/"
ok "thư mục deploy: $REMOTE_DIR"

cat <<EOF

$(c "══ XONG ══")

  Gói:     $PKG
  Kích cỡ: $((PKG_SIZE / 1048576)) MB
  sha256:  $PKG_SHA
  Ký bởi:  $(basename "$MATCHED")

$(c "══ Đưa ra máy field ══")

  A. Upload tay tại hiện trường (không cần internet):
       copy $(basename "$PKG") ra USB → mở https://<ip-nvr> → Thiết lập → Cập nhật → kéo file vào

  B. OTA tự động (máy tự tải lúc 2h sáng):
       Đưa cả 3 file trong $REMOTE_DIR/ lên
         $(jq -r '.package.url' "$MANIFEST" | sed 's|/[^/]*$||')/
       Cả ba, không thiếu file nào:
         manifest.json      (đã ký, byte-identical với bản trong gói)
         manifest.json.sig
         $(basename "$PKG")

  $(warn "Trước khi giao khách: ./verify-bundle.sh $PKG")
EOF
