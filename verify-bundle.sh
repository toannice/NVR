#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# verify-bundle.sh — quét bundle giao khách trước khi gửi đi.
#
# Chạy SAU package.sh, TRƯỚC khi copy cho khách:
#   ./verify-bundle.sh releaseVersion/nvr-bundle-YYYYMMDD.tar.gz
#
# Kiểm tra cả file cấu hình lẫn nội dung image nvr-wrapper (JS bundle đã build).
# Exit 1 nếu tìm thấy bất kỳ dấu hiệu rò rỉ nào.
# ──────────────────────────────────────────────────────────────────────────────
set -uo pipefail

BUNDLE="${1:-}"
[[ -z "$BUNDLE" || ! -f "$BUNDLE" ]] && { echo "Usage: $0 <bundle.tar.gz>"; exit 2; }

ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
bad()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*"; FOUND=1; }
c()    { printf '\033[1;36m%s\033[0m\n' "$*"; }

FOUND=0
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

c "[1/4] Giải nén bundle"
tar -xzf "$BUNDLE" -C "$WORK" || { echo "giải nén lỗi"; exit 2; }
ROOT="$(find "$WORK" -maxdepth 1 -mindepth 1 -type d | head -1)"
ok "$(basename "$ROOT")"

# ── Mẫu secret. Thêm dòng mới khi phát sinh tài khoản/khoá mới ────────────────
# format: <nhãn>|<regex>
PATTERNS=(
  "GitHub PAT|gh[pousr]_[A-Za-z0-9]{20,}"
  "Mật khẩu camera mặc định|Bkav@2020"
  "Gmail app password|[a-z]{16}@?gmail|gmail_app_password"
  "Mật khẩu cloud AiView|DAdHQqIDGI5G9FKPHwj8"
  "TURN credential|credential:[[:space:]]*[A-Za-z0-9+/=]{16,}"
  # Chỉ bắt creds THẬT: host phải là IP đủ 4 octet số. Placeholder UI
  # (rtsp://user:pass@192.168.1.xxx) và template literal (rtsp://${...}) không khớp.
  "RTSP kèm mật khẩu|rtsp://[A-Za-z0-9_.-]{1,32}:[^[:space:]/]{1,64}@[0-9]{1,3}(\\.[0-9]{1,3}){3}"
  "IP overlay Nebula|10\\.10\\.20\\.[0-9]+"
  "IP LAN văn phòng|192\\.168\\.2\\.[0-9]+"
  "Telegram bot token|[0-9]{8,}:AA[A-Za-z0-9_-]{30,}"
  "Private key|-----BEGIN [A-Z ]*PRIVATE KEY-----"
  # Khoá DEV ký gói OTA. Máy khách tin khoá này nghĩa là ai chạm được máy dev cũng đẩy được
  # bản cập nhật vào máy khách đó — loại lỗi chỉ phát hiện được khi đã quá muộn.
  "Khoá OTA dev|ota-dev\\.(pub|key)"
)

scan_dir() { # scan_dir <thư mục> <nhãn vùng>
    local dir="$1" area="$2" p label rx hits
    for p in "${PATTERNS[@]}"; do
        label="${p%%|*}"; rx="${p#*|}"
        hits="$(grep -rIEl "$rx" "$dir" 2>/dev/null | head -5)"
        if [[ -n "$hits" ]]; then
            bad "$area: $label"
            echo "$hits" | sed "s|$dir|    |"
        fi
    done
}

c "[2/4] Quét file cấu hình trong bundle"
scan_dir "$ROOT" "config"
[[ $FOUND -eq 0 ]] && ok "không thấy secret trong config"

c "[3/4] Quét nội dung image nvr-wrapper (JS bundle + jar)"
# package.sh xuất mọi image vào một images.tar duy nhất, định dạng OCI:
# layer nằm trong blobs/sha256/<digest> và KHÔNG có đuôi .tar → phải dò bằng `tar -tf`.
IMG_TAR="$(find "$ROOT" -maxdepth 2 -name 'images.tar' -o -maxdepth 2 -name '*nvr-wrapper*.tar' | head -1)"
if [[ -n "$IMG_TAR" && -f "$IMG_TAR" ]]; then
    IMGDIR="$WORK/img"; APPDIR="$WORK/app"; mkdir -p "$IMGDIR" "$APPDIR"
    tar -xf "$IMG_TAR" -C "$IMGDIR" 2>/dev/null

    # Chỉ bung layer của nvr-wrapper (chứa /app) — bỏ qua layer frigate ~3GB.
    LAYERS=0
    while read -r blob; do
        tar -tf "$blob" 2>/dev/null | head -40 | grep -q '^app/' || continue
        tar -xf "$blob" -C "$APPDIR" app/ 2>/dev/null && LAYERS=$((LAYERS+1))
    done < <(find "$IMGDIR" -type f -size -400M)

    if [[ $LAYERS -eq 0 ]]; then
        bad "không bung được layer /app nào — không xác minh được nội dung image"
    else
        SUB=0
        # Chứng minh việc quét thực sự đọc được frontend đã build, nếu không thì
        # kết quả "sạch" là vô nghĩa.
        if ! grep -rIql 'aiview' "$APPDIR" 2>/dev/null; then
            bad "quét image không đọc được nội dung frontend (kiểm tra lại script)"
        fi
        for p in "${PATTERNS[@]}"; do
            label="${p%%|*}"; rx="${p#*|}"
            if grep -rIEl "$rx" "$APPDIR" 2>/dev/null | head -1 | grep -q .; then
                bad "image: $label"
                grep -rIEl "$rx" "$APPDIR" 2>/dev/null | head -3 | sed "s|$APPDIR|    |"
                SUB=1
            fi
        done
        [[ $SUB -eq 0 ]] && ok "không thấy secret trong image ($LAYERS layer /app đã quét)"
    fi
else
    bad "không tìm thấy images.tar trong bundle để quét"
fi

c "[4/4] Kiểm tra các file KHÔNG được có mặt"
for forbidden in .env secrets/gmail_app_password mosquitto/config/passwd install.sh; do
    if [[ -e "$ROOT/$forbidden" ]] && [[ -s "$ROOT/$forbidden" ]]; then
        bad "bundle chứa $forbidden (không được)"
    fi
done
# secrets/* phải rỗng — deploy-offline.sh sinh trên máy đích
for f in "$ROOT"/secrets/*; do
    [[ -e "$f" ]] || continue
    [[ -s "$f" ]] && bad "secrets/$(basename "$f") KHÔNG rỗng"
done
[[ $FOUND -eq 0 ]] && ok "không có file cấm"

echo ""
if [[ $FOUND -eq 0 ]]; then
    printf '\033[1;32m════ BUNDLE SẠCH — có thể giao khách ════\033[0m\n'
else
    printf '\033[1;31m════ CÓ RÒ RỈ — KHÔNG được giao ════\033[0m\n'
fi
exit $FOUND
