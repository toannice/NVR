#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# install-ip-guard.sh — cài lớp tự dò IP lúc khởi động cho MÁY DEV (cây source).
#
# Vì sao cần: compose publish cổng restream theo ${FRIGATE_HOST_IP}. Máy đổi mạng mà
# .env còn IP cũ thì docker báo "cannot assign requested address", frigate không lên,
# mất cả live view lẫn ghi hình — trong khi container khác vẫn "healthy" nên nhìn bảng
# trạng thái không thấy sai ở đâu. Đã xảy ra thật 15-08-2026 (boot 09:34 fail 09:35).
#
# Cách làm: nvr-stack.service gọi nvr-ip-guard.sh --env-only ở ExecStartPre, tức là
# sửa .env theo IP hiện tại NGAY TRƯỚC khi `docker compose up -d`. Kiểm tra một lần
# lúc khởi động là đủ — máy ghi hình đặt cố định, IP chỉ đổi khi có người cắm lại dây
# hoặc đổi router, và lúc đó đằng nào cũng khởi động lại.
#
# Máy khách cài từ bundle: deploy-offline.sh lo phần này bằng nvr-ip-guard.service
# (chạy lúc boot, bản đầy đủ vì bên đó không có systemd unit cho stack).
#
# Chạy:  sudo ./scripts/install-ip-guard.sh
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo "Cần chạy bằng sudo: sudo $0" >&2; exit 1; }

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GUARD="$PROJECT_DIR/scripts/nvr-ip-guard.sh"
UNIT_SRC="$PROJECT_DIR/nvr-stack.service"

[[ -f "$GUARD" ]]            || { echo "Không thấy $GUARD" >&2; exit 1; }
[[ -f "$UNIT_SRC" ]]         || { echo "Không thấy $UNIT_SRC" >&2; exit 1; }
[[ -f "$PROJECT_DIR/.env" ]] || { echo "Không thấy $PROJECT_DIR/.env" >&2; exit 1; }

grep -q 'nvr-ip-guard.sh --env-only' "$UNIT_SRC" \
    || { echo "$UNIT_SRC chưa có ExecStartPre gọi ip-guard — dừng" >&2; exit 1; }

chmod +x "$GUARD"

ok() { printf '  [OK] %s\n' "$*"; }

install -m 644 "$UNIT_SRC" /etc/systemd/system/nvr-stack.service
ok "nvr-stack.service (ExecStartPre dò IP trước khi up)"

# Bản trước từng cài timer quét mỗi 2 phút — bỏ hẳn, quét liên tục cả đời máy để bắt
# một sự kiện vài năm mới xảy ra là không đáng.
for unit in nvr-ip-guard.timer nvr-ip-guard.service; do
    if [[ -f "/etc/systemd/system/$unit" ]]; then
        systemctl disable --now "$unit" >/dev/null 2>&1 || true
        rm -f "/etc/systemd/system/$unit"
        ok "đã gỡ $unit (máy dev dùng ExecStartPre của nvr-stack)"
    fi
done

systemctl daemon-reload
systemctl enable nvr-stack.service >/dev/null 2>&1 || true
ok "daemon-reload xong"

echo ""
echo "Kiểm tra:"
echo "  sudo NVR_INSTALL_DIR=$PROJECT_DIR $GUARD --check   # so IP hiện tại với .env"
echo "  systemctl cat nvr-stack | grep ExecStartPre"
echo "  tail -f $PROJECT_DIR/logs/ip-guard.log"
