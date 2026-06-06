#!/bin/bash
# scripts/telegram_report.sh
# Gửi báo cáo tình trạng hệ thống NVR hàng ngày vào Telegram.

# Thay đổi bằng thư mục gốc cài đặt dự án của bạn (hard-coded tuyệt đối để chạy cron an toàn)
PROJECT_DIR="/home/toan/my-nvr"
cd $PROJECT_DIR || exit 1

# Đọc các cấu hình Bot Token
if [ -f .env ]; then
    set -a
    source <(grep -v '^#' .env | sed -e 's/\r//g')
    set +a
else
    echo "Lỗi: Không tìm thấy file .env tại $PROJECT_DIR"
    exit 1
fi

if [ -z "$TELEGRAM_BOT_TOKEN" ] || [ -z "$TELEGRAM_CHAT_ID" ]; then
    echo "Lỗi: TELEGRAM_BOT_TOKEN hoặc TELEGRAM_CHAT_ID chưa được cài đặt trong .env"
    exit 1
fi

HOSTNAME=$(hostname)
REPORT_TIME=$(date "+%H:%M | %d-%m-%Y")

# Tính toán các thông số
# CPU Load (1 phút gần nhất)
CPU_LOAD=$(uptime | awk -F'load average:' '{ print $2 }' | awk -F',' '{ print $1 }' | xargs)

# RAM
RAM_TOTAL=$(free -m | awk '/Mem:/ { print $2 }')
RAM_USED=$(free -m | awk '/Mem:/ { print $3 }')
if [ "$RAM_TOTAL" -gt 0 ]; then
    RAM_PERCENT=$(( 100 * RAM_USED / RAM_TOTAL ))
else
    RAM_PERCENT=0
fi

# Disk Usage ở phân vùng /
DISK_USAGE=$(df -h / | awk 'NR==2 {print $5}')

# System Uptime
UPTIME_STR=$(uptime -p)

# Lấy phiên bản thực tế của image NVR đang chạy
NVR_IMAGE=$(docker ps --filter "name=nvr-wrapper" --format "{{.Image}}" | head -n 1)
if [ -z "$NVR_IMAGE" ]; then
    NVR_IMAGE="Không hoạt động"
fi

# Trạng thái các Docker Container (NVR)
CONTAINERS_STATUS=$(docker ps -a --format "- {{.Names}}: {{.Status}}" | grep -iE "frigate|mosquitto|watchtower|nvr-wrapper|nginx|cloudflared")

TEXT="📊 <b>BÁO CÁO NHẬT KÝ HỆ THỐNG NVR</b>
💻 <b>Máy Trạm:</b> <code>$HOSTNAME</code>
🗓 <b>Thời gian:</b> $REPORT_TIME

<b>1. Thông tin phần mềm:</b>
- Phiên bản NVR: <code>$NVR_IMAGE</code>
- Uptime Hệ thống: $UPTIME_STR

<b>2. Thông số phần cứng:</b>
- Tải CPU (1m): $CPU_LOAD
- RAM Used: ${RAM_USED}MB / ${RAM_TOTAL}MB ($RAM_PERCENT%)
- Đĩa Cứng (Disk): $DISK_USAGE

<b>3. Trạng thái Vùng Chứa (Containers):</b>
<code>$CONTAINERS_STATUS</code>"

curl -s -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
    -d chat_id="$TELEGRAM_CHAT_ID" \
    -d parse_mode="HTML" \
    -d text="$TEXT"

echo "Đã gửi báo cáo Telegram thành công!"
