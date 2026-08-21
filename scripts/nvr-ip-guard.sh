#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# nvr-ip-guard.sh — giữ FRIGATE_HOST_IP trong .env luôn khớp IP LAN thật của máy.
#
# Vì sao cần: WebRTC live view và cổng restream 8554 bind theo FRIGATE_HOST_IP.
# Máy đổi IP (DHCP cấp lại, cắm sang mạng khác, đổi router) mà .env còn IP cũ thì
# live view ĐEN toàn bộ dù mọi container vẫn "healthy" — rất khó đoán ra nguyên nhân.
#
# Chạy: MỘT LẦN mỗi lần khởi động, để lần bật nào cũng lấy IP hiện tại chứ không tin
# giá trị cũ trong .env. Không quét định kỳ — máy ghi hình đặt cố định, IP chỉ đổi khi
# có người cắm lại dây/đổi router, mà lúc đó đằng nào cũng khởi động lại.
#   · máy dev:   ExecStartPre của nvr-stack.service, dùng --env-only (stack chưa lên)
#   · máy khách: nvr-ip-guard.service lúc boot, bản đầy đủ (container đã lên sẵn bằng
#                restart:unless-stopped nên phải recreate frigate)
#
# Thủ công:  sudo /opt/nvr/nvr-ip-guard.sh             # sửa .env rồi áp dụng ngay
#            sudo /opt/nvr/nvr-ip-guard.sh --check     # chỉ báo, không đổi gì
#            sudo /opt/nvr/nvr-ip-guard.sh --env-only  # chỉ sửa .env, KHÔNG đụng container
#
# --env-only dùng cho ExecStartPre: lúc đó stack chưa lên, `docker compose up` chạy
# ngay sau đó sẽ tự đọc .env mới — recreate ở đây vừa thừa vừa dễ kẹt (guard gọi
# docker compose trong khi systemd đang khởi động chính unit đó).
# ──────────────────────────────────────────────────────────────────────────────
set -uo pipefail

INSTALL_DIR="${NVR_INSTALL_DIR:-/opt/nvr}"
ENV_FILE="$INSTALL_DIR/.env"
LOG_DIR="$INSTALL_DIR/logs"
LOG="$LOG_DIR/ip-guard.log"
CHECK_ONLY=0
ENV_ONLY=0
case "${1:-}" in
    --check)    CHECK_ONLY=1 ;;
    --env-only) ENV_ONLY=1 ;;
    "")         ;;
    *)          echo "Tham số không hợp lệ: $1 (dùng --check | --env-only)" >&2; exit 2 ;;
esac

mkdir -p "$LOG_DIR"
log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" | tee -a "$LOG"; }

[[ -f "$ENV_FILE" ]] || { log "SKIP: không tìm thấy $ENV_FILE"; exit 0; }

# ── Dò IP LAN thật ────────────────────────────────────────────────────────────
# Bỏ mọi interface ảo: docker, bridge, veth, và overlay/VPN (nebula, zerotier,
# wireguard, tailscale, tun). Địa chỉ overlay nhìn cũng "private" (10.x) nhưng
# KHÔNG máy nào trong LAN route tới được — đây chính là bẫy hay dính nhất.
# Ưu tiên 192.168/172.16-31 hơn 10.x thuần vì lý do trên.
detect_ip() {
    local line ifname ip best10=""
    while read -r line; do
        ifname="$(awk '{print $2}' <<<"$line")"
        ip="$(awk '{print $4}' <<<"$line" | cut -d/ -f1)"
        case "$ifname" in
            lo|docker*|br-*|veth*|nebula*|zt*|tun*|tailscale*|wg*) continue ;;
        esac
        case "$ip" in
            192.168.*|172.1[6-9].*|172.2[0-9].*|172.3[01].*) echo "$ip"; return 0 ;;
            10.*) [[ -z "$best10" ]] && best10="$ip" ;;
        esac
    done < <(ip -4 -o addr show scope global 2>/dev/null)
    [[ -n "$best10" ]] && { echo "$best10"; return 0; }
    return 1
}

CURRENT_IP="$(detect_ip)" || { log "SKIP: chưa dò được IP LAN (mạng chưa lên?)"; exit 0; }
ENV_IP="$(grep -E '^FRIGATE_HOST_IP=' "$ENV_FILE" | head -1 | cut -d= -f2- | sed 's/#.*//' | tr -d ' \r\t')"

if [[ "$CURRENT_IP" == "$ENV_IP" ]]; then
    [[ $CHECK_ONLY -eq 1 ]] && log "OK: FRIGATE_HOST_IP=$ENV_IP khớp IP hiện tại"
    exit 0
fi

log "IP ĐỔI: .env='$ENV_IP' → thực tế='$CURRENT_IP'"
if [[ $CHECK_ONLY -eq 1 ]]; then
    log "--check: không thay đổi gì. Chạy lại không có --check để áp dụng."
    exit 1
fi

# ── Cập nhật .env ─────────────────────────────────────────────────────────────
cp "$ENV_FILE" "$ENV_FILE.bak.$(date +%Y%m%d%H%M%S)"
if grep -qE '^FRIGATE_HOST_IP=' "$ENV_FILE"; then
    sed -i "s|^FRIGATE_HOST_IP=.*|FRIGATE_HOST_IP=$CURRENT_IP|" "$ENV_FILE"
else
    echo "FRIGATE_HOST_IP=$CURRENT_IP" >> "$ENV_FILE"
fi
log "Đã ghi FRIGATE_HOST_IP=$CURRENT_IP"

if [[ $ENV_ONLY -eq 1 ]]; then
    log "--env-only: đã sửa .env, để lệnh compose up phía sau tự áp dụng."
    exit 0
fi

# ── Áp dụng ───────────────────────────────────────────────────────────────────
# frigate: publish 8554 theo IP mới → phải recreate container.
# nvr-wrapper: ghi lại candidates trong config.yml → chỉ cần restart.
cd "$INSTALL_DIR" || exit 1
COMPOSE_FILES="-f docker-compose.yml"
# Chỉ ghép offline.yml khi đây THẬT SỰ là máy cài từ bundle. Cây source dev cũng có
# file này (nó là nguồn để đóng bundle), mà offline.yml `!reset` khoá `build:` — ghép
# vào trên máy dev là recreate bằng cấu hình sai. Dấu hiệu phân biệt: bundle không có
# Dockerfile/source, máy dev thì có.
if [[ -f docker-compose.offline.yml && ! -f Dockerfile ]]; then
    COMPOSE_FILES="$COMPOSE_FILES -f docker-compose.offline.yml"
fi

if docker compose $COMPOSE_FILES up -d --force-recreate frigate >>"$LOG" 2>&1; then
    log "frigate recreate OK"
else
    log "LỖI: frigate recreate thất bại — xem $LOG"
fi
docker compose $COMPOSE_FILES restart nvr-wrapper >>"$LOG" 2>&1 && log "nvr-wrapper restarted"
# nginx không tự khởi động lại khi các container khác recreate
docker compose $COMPOSE_FILES up -d nginx >>"$LOG" 2>&1

log "Hoàn tất — live view dùng IP $CURRENT_IP"
