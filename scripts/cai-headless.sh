#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# cai-headless.sh — cài NVR trên một máy Ubuntu headless bằng MỘT lệnh.
#
#   sudo ./cai-headless.sh
#
# Gộp đúng hai bước vẫn phải làm tay trước đây:
#   1. deploy-offline.sh  — dựng stack từ images.tar (hỏi vài câu trên dòng lệnh)
#   2. provision-headless.sh — đặt mã vẽ, lưu mã khôi phục, bật kết nối Cloud
#
# AI của Frigate KHÔNG cần tắt tay: backend tự tắt một lần ở lần khởi động đầu
# (CameraService.disableFrigateAiOnce). Script chỉ chờ và xác nhận là đã tắt thật.
#
# Camera thêm từ dashboard cloud, không thêm ở đây.
# ──────────────────────────────────────────────────────────────────────────────
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }
hdr()  { printf '\n\033[1;36m══ %s\033[0m\n' "$*"; }

[[ $EUID -eq 0 ]] || die "Chạy bằng sudo: sudo ./cai-headless.sh"

CLOUD_CONF="${CLOUD_CONF:-$HERE/secrets/cloud.conf}"
PROV="$HERE/scripts/provision-headless.sh"
RECOVERY_OUT="${RECOVERY_OUT:-$HERE/secrets/recovery-code.txt}"

# ── 1. Dựng stack ────────────────────────────────────────────────────────────
hdr "[1/4] Dựng stack (deploy-offline.sh)"
[[ -x ./deploy-offline.sh ]] || die "không thấy deploy-offline.sh — chạy script này TRONG thư mục bundle đã giải nén"
./deploy-offline.sh || die "deploy-offline.sh thất bại — xem log phía trên, chưa sang bước sau"
ok "stack đã dựng"

# ── 2. Chờ backend ───────────────────────────────────────────────────────────
hdr "[2/4] Chờ backend trả lời"
i=0
until curl -s -o /dev/null -m 3 -f http://127.0.0.1:3000/api/health 2>/dev/null; do
    (( i >= 180 )) && die "backend không lên sau 180s — kiểm: docker compose logs --tail 80 nvr-wrapper"
    sleep 5; i=$((i+5))
done
ok "backend sống sau ${i}s"

# ── 3. Chờ lượt tự tắt AI xong ───────────────────────────────────────────────
# Lượt này ghi config.yml rồi restart Frigate một lần (~30s mất hình). Phải chờ nó
# xong TRƯỚC khi provision, không thì hai lượt ghi config.yml chen nhau.
hdr "[3/4] Chờ AI Frigate tự tắt (một lần, có restart Frigate)"
i=0
while (( i < 300 )); do
    if docker compose logs --tail 400 nvr-wrapper 2>/dev/null \
         | grep -qE "AI_DISABLE_MIGRATION\] \[(APPLIED|ALREADY_OFF|FAIL)"; then break; fi
    sleep 5; i=$((i+5))
done
LINE="$(docker compose logs --tail 400 nvr-wrapper 2>/dev/null | grep -oE "AI_DISABLE_MIGRATION\] \[[A-Z_]+" | tail -1)"
case "$LINE" in
    *APPLIED*)     ok "đã tắt AI Frigate và Frigate lên lại bình thường" ;;
    *ALREADY_OFF*) ok "AI Frigate vốn đã tắt" ;;
    *FAIL*)        warn "lượt tắt AI THẤT BẠI — AI vẫn đang bật và sẽ không tự thử lại."
                   warn "  Tắt tay sau khi cài xong (xem cuối script)" ;;
    *)             warn "chưa thấy kết quả tắt AI sau ${i}s — kiểm: docker compose logs nvr-wrapper | grep AI_DISABLE" ;;
esac

# ── 4. Mã vẽ + mã khôi phục + Cloud ──────────────────────────────────────────
hdr "[4/4] Mã vẽ, mã khôi phục, kết nối Cloud"
[[ -x "$PROV" ]] || die "không thấy $PROV"

# Cấu hình Cloud KHÔNG đi kèm bundle: mật khẩu nằm trong bundle thì mọi đường gửi bundle
# (email, drive, USB để quên) đều là đường lộ, và verify-bundle.sh chặn thẳng. Hỏi tại máy,
# ghi chmod 600, chỉ máy này có.
if [[ ! -r "$CLOUD_CONF" ]]; then
    MAU="$HERE/scripts/cloud.conf.template"
    DEF_DOMAIN="$( [[ -r "$MAU" ]] && grep -m1 '^CLOUD_DOMAIN=' "$MAU" | cut -d= -f2- || echo '' )"
    DEF_PORT="$(   [[ -r "$MAU" ]] && grep -m1 '^CLOUD_PORT='   "$MAU" | cut -d= -f2- || echo '' )"
    echo "  Chưa có $CLOUD_CONF — nhập thông tin Cloud cho MÁY NÀY."
    echo "  (Enter để bỏ qua hẳn bước Cloud, cấu hình sau bằng scripts/provision-headless.sh)"
    printf '\033[1;33m  → Serial máy này (VMSAV + 6 chữ số): \033[0m'; read -r C_SERIAL
    if [[ -n "$C_SERIAL" ]]; then
        # File mẫu không còn điền sẵn địa chỉ (nó theo git), nên ở đây thường KHÔNG có
        # mặc định — phải hỏi tới khi có. Ghi cấu hình cloud với domain rỗng thì máy nhìn
        # như đã cấu hình xong mà không bao giờ kết nối được.
        while :; do
            if [[ -n "$DEF_DOMAIN" ]]; then
                printf '\033[1;33m  → Domain cloud [%s]: \033[0m' "$DEF_DOMAIN"
            else
                printf '\033[1;33m  → Domain cloud (kèm http:// nếu cloud chạy HTTP thuần): \033[0m'
            fi
            read -r C_DOMAIN
            C_DOMAIN="${C_DOMAIN:-$DEF_DOMAIN}"
            [[ -n "$C_DOMAIN" ]] && break
            warn "phải nhập domain cloud"
        done
        printf '\033[1;33m  → Port [%s]: \033[0m' "$DEF_PORT"; read -r C_PORT
        C_PORT="${C_PORT:-$DEF_PORT}"
        printf '\033[1;33m  → Username: \033[0m'; read -r C_USER
        printf '\033[1;33m  → Password: \033[0m'; read -rs C_PASS; echo ""
        mkdir -p "$(dirname "$CLOUD_CONF")"
        umask 077
        { echo "# Sinh bởi cai-headless.sh $(date '+%d-%m-%Y %H:%M:%S') — CÓ MẬT KHẨU, chmod 600"
          echo "CLOUD_DOMAIN=$C_DOMAIN"
          echo "CLOUD_PORT=$C_PORT"
          echo "CLOUD_USER=$C_USER"
          echo "CLOUD_PASSWORD=$C_PASS"
          echo "CLOUD_SERIAL=$C_SERIAL"; } > "$CLOUD_CONF"
        chmod 600 "$CLOUD_CONF"
        ok "đã ghi $CLOUD_CONF (chmod 600)"
    else
        warn "bỏ qua bước Cloud (không nhập serial)"
    fi
fi
"$PROV" --cloud-conf "$CLOUD_CONF" --recovery-out "$RECOVERY_OUT" || warn "provision có lỗi — đọc dòng ✗ phía trên"

LAN_IP="$(./scripts/nvr-check.sh lan-ip 2>/dev/null || hostname -I 2>/dev/null | awk '{print $1}')"
cat <<EOF

════════════════════════════════════════════════════════════════
  CÀI XONG
════════════════════════════════════════════════════════════════
  Mã khôi phục : $RECOVERY_OUT  (chmod 600 — CHÉP RA CHỖ KHÁC NGAY)
  Web UI       : https://${LAN_IP:-<ip-máy>}   (từ máy khác trong LAN, cert tự ký)
  API tại máy  : http://127.0.0.1:3000

  Kiểm lại toàn máy:
    sudo $HERE/scripts/nvr-check.sh sau

  Xem trạng thái Cloud (chờ CONNECTED):
    JAR=~/.nvr-cookie; umask 077
    curl -sc \$JAR -X POST http://127.0.0.1:3000/api/lock/verify \\
      -H 'Content-Type: application/json' -d '{"pattern":"<mã vẽ>"}'
    curl -sb \$JAR http://127.0.0.1:3000/api/cloud/config | jq .connectionState
    tail -f logs/cloud.log

  AI Frigate đang TẮT. Bật lại khi cần:
    curl -sb \$JAR -X POST http://127.0.0.1:3000/api/settings/detect \\
      -H 'Content-Type: application/json' -d '{"enabled":true}'
    curl -sb \$JAR -X POST http://127.0.0.1:3000/api/settings/detect/apply

  Camera: thêm từ dashboard cloud. Mọi việc khác làm qua terminal —
  bảng việc→lệnh đầy đủ trong VAN-HANH-HEADLESS.md
════════════════════════════════════════════════════════════════
EOF
