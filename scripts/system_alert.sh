#!/bin/bash
# scripts/system_alert.sh
# Kịch bản cảnh báo sự cố tực tuyến (Real-time). Chạy mỗi 5 phút.

PROJECT_DIR="/home/toan/my-nvr"
cd $PROJECT_DIR || exit 1

if [ -f .env ]; then
    set -a
    source <(grep -v '^#' .env | sed -e 's/\r//g')
    set +a
fi

if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ -z "$TELEGRAM_CHAT_ID" ]; then
    exit 0
fi

HOSTNAME=$(hostname)
ALERTS=""

# Cảnh báo 1 lần duy nhất trong vòng 30 phút (tránh làm cháy máy điện thoại)
check_cooldown() {
    local alert_name=$1
    local cooldown_file="/tmp/nvr_alert_${alert_name}"
    local current_time=$(date +%s)
    
    if [ -f "$cooldown_file" ]; then
        local last_alert=$(cat "$cooldown_file")
        local diff=$((current_time - last_alert))
        if [ "$diff" -lt 1800 ]; then
            return 1 # Chưa qua 30 phút, im lặng
        fi
    fi
    echo "$current_time" > "$cooldown_file"
    return 0 # Cho phép gửi
}

# 1. Ổ Cứng > 95%
DISK_P=$(df / | awk 'NR==2 {print $5}' | sed 's/%//')
if [ "$DISK_P" -ge 95 ]; then
    if check_cooldown "disk"; then
        ALERTS+="\n🚨 <b>[NGUY HIỂM] Ổ Cứng Gần Đầy:</b> Đã dùng $DISK_P%"
    fi
fi

# 2. RAM > 90%
RAM_T=$(free -m | awk '/Mem:/ {print $2}')
RAM_U=$(free -m | awk '/Mem:/ {print $3}')
if [ "$RAM_T" -gt 0 ]; then
    RAM_P=$(( 100 * RAM_U / RAM_T ))
    if [ "$RAM_P" -ge 90 ]; then
        if check_cooldown "ram"; then
            ALERTS+="\n💥 <b>[QUÁ TẢI] RAM:</b> Đang dùng $RAM_P% (${RAM_U}MB)"
        fi
    fi
fi

# 3. CPU > 95%
CPU_IDLE=$(top -bn1 | grep "Cpu(s)" | sed "s/.*, *\([0-9.]*\)%* id.*/\1/" | awk '{print int($1)}')
if [ -n "$CPU_IDLE" ]; then
    CPU_USAGE=$(( 100 - CPU_IDLE ))
    if [ "$CPU_USAGE" -ge 95 ]; then
        if check_cooldown "cpu"; then
            ALERTS+="\n🔥 <b>[QUÁ TẢI] CPU:</b> Đang chạy ở mức $CPU_USAGE%"
        fi
    fi
fi

# 4. Containers / Engine sập
if ! docker info >/dev/null 2>&1; then
    if check_cooldown "docker_daemon"; then
        ALERTS+="\n💀 <b>[SẬP HỆ THỐNG] DOCKER DAEMON ĐÃ TẮT. NVR mất kết nối hoàn toàn!</b>"
    fi
else
    for service in "frigate" "nvr-wrapper" "mosquitto" "nvr-nginx"; do
        STATUS=$(docker inspect -f '{{.State.Status}}' $service 2>/dev/null)
        if [ "$STATUS" != "running" ]; then
            if check_cooldown "container_$service"; then
                ALERTS+="\n🛑 <b>[MẤT KẾT NỐI] Dịch vụ $service đang TẮT hoặc LỖI (Status: $STATUS)!</b>"
            fi
        fi
    done
fi

if [ -n "$ALERTS" ]; then
    TEXT="⚠️ <b>HỆ THỐNG NVR PHÁT HIỆN SỰ CỐ!</b>
💻 <b>Máy Trạm:</b> <code>$HOSTNAME</code>
🕛 <b>Thời gian:</b> $(date "+%H:%M | %d-%m-%Y")
$ALERTS"

    curl -s -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
        -d chat_id="$TELEGRAM_CHAT_ID" \
        -d parse_mode="HTML" \
        -d text="$TEXT"
fi
