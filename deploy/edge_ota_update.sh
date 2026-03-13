#!/bin/bash
# Kế hoạch thực thi tại máy trạm (Edge Node)
# Chạy thủ công: ./edge_ota_update.sh v1.2.0

TARGET_VERSION=$1
TELEGRAM_BOT_TOKEN="8637751033:AAFTEi0-kdVIjTYCwRrb4xLVVFqCShipi6s"
TELEGRAM_CHAT_ID="6655437760" # Đã cập nhật Chat ID của bạn
HOSTNAME=$(hostname)

# Đường dẫn vật lý trên máy trạm
BASE_DIR="/home/$USER/my-nvr" # Bạn có thể sửa thành /home/toan/my-nvr nếu chạy bằng root
DATA_VOLUME="$BASE_DIR/data"
CONFIG_VOLUME="$BASE_DIR/config"

IMAGE="ghcr.io/toannice/nvr:$TARGET_VERSION"

if [ -z "$TARGET_VERSION" ]; then
    echo "Lỗi: Vui lòng cung cấp Tag phiên bản cập nhật (VD: v1.2.0)."
    exit 1
fi

send_telegram() {
    curl -s -X POST "https://api.telegram.org/bot$TELEGRAM_BOT_TOKEN/sendMessage" \
        -d chat_id="$TELEGRAM_CHAT_ID" -d text="$1"
}

echo "=== 1. PRE-FLIGHT CHECKS ==="
DISK_USAGE=$(df / | grep / | awk '{ print $5 }' | sed 's/%//g')
if [ "$DISK_USAGE" -gt 90 ]; then
    msg="🚨 [MÁY TRẠM $HOSTNAME] Ổ cứng đã đầy ${DISK_USAGE}%. Huỷ quá trình cập nhật $TARGET_VERSION để tránh sập hệ thống!"
    send_telegram "$msg"
    exit 1
fi

if ! ping -c 3 github.com &> /dev/null; then
    echo "❌ Không có kết nối mạng tới GitHub. Huỷ cập nhật."
    exit 1
fi

echo "=== 2. PULL IMAGE VER MỚI ==="
# Login to Github Container Registry (Cần chạy docker login ghcr.io bằng terminal trước 1 lần)
echo ">> Kéo Docker Image bản mới ($TARGET_VERSION)..."
if ! docker pull $IMAGE; then
    send_telegram "❌ [MÁY TRẠM $HOSTNAME] Lỗi không thể tải Docker image $TARGET_VERSION từ ghcr.io."
    exit 1
fi

echo "=== 3. KHỞI CHẠY BẢN BETA (Với các Volume chỉ được đọc - ro) ==="
# Map các volume nhưng chỉ cho phép đọc để không làm hỏng dữ liệu bản chính
docker run -d --name nvr_beta_temp \
    -p 8081:8080 \
    -v ${DATA_VOLUME}:/app/data:ro \
    -v ${CONFIG_VOLUME}:/app/config:ro \
    --device /dev/dri:/dev/dri \
    --restart no \
    $IMAGE

echo ">> Đợi Backend khởi động và check Health..."
MAX_RETRIES=12
RETRY_COUNT=0
HEALTHY=false

while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
    HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/api/health 2>/dev/null || echo "000")
    if [ "$HTTP_STATUS" == "200" ]; then
        echo ">> Backend đã sẵn sàng sau $((RETRY_COUNT * 5)) giây!"
        HEALTHY=true
        break
    fi
    echo ">> Chưa sẵn sàng, đợi thêm 5s..."
    sleep 5
    ((RETRY_COUNT++))
done

TEST_PASS=$HEALTHY

# Test 1: Container có nhận card đồ họa/trình cắm phần cứng (GPU) không?
if ! docker exec nvr_beta_temp ls /dev/dri &> /dev/null; then
    echo "❌ Lỗi: Không thể map Hardware Acceleration (/dev/dri) vào máy ảo Beta!"
    TEST_PASS=false
fi

echo "=== 4. QUYẾT ĐỊNH ROLLBACK HAY SWAP ==="
if [ "$TEST_PASS" = true ]; then
    echo "✅ Toàn bộ Test OK. Bắt đầu hoán đổi Port và kết liễu bản cũ..."
    
    # Dừng bản Beta nháp
    docker stop nvr_beta_temp; docker rm nvr_beta_temp
    
    # Dừng NVR cũ
    docker stop nvr_main || true
    docker rm nvr_main || true
    
    # Restart ở mode production đích thực
    docker run -d --name nvr_main \
        -p 8080:8080 \
        -v ${DATA_VOLUME}:/app/data \
        -v ${CONFIG_VOLUME}:/app/config \
        --device /dev/dri:/dev/dri \
        --restart unless-stopped \
        $IMAGE
    
    # DỌN DẸP HỆ THỐNG (GARBAGE COLLECTION)
    docker system prune -af --filter "until=168h"
    
    send_telegram "✅ [MÁY TRẠM $HOSTNAME] Cập nhật mượt mà lên bản $TARGET_VERSION. Hệ thống đang tiếp tục theo dõi camera."
else
    echo "⚠️ RỦI RO! Cập nhật bị lỗi (Test FAIL)! Tiến hành TỰ ĐỘNG ROLLBACK."
    
    ERROR_LOG=$(docker logs --tail 200 nvr_beta_temp 2>&1 | grep -iE "error|fatal|exception|traceback" | tail -n 15)
    
    # Kết liễu bản lỗi vừa đem về
    docker stop nvr_beta_temp
    docker rm nvr_beta_temp
    
    msg="🚨 [MÁY TRẠM $HOSTNAME] Cập nhật thất bại bản $TARGET_VERSION! Đã rollback về bản cũ thành công. NVR không bị mất luồng camera.
Chi tiết Log:
${ERROR_LOG}"
    send_telegram "$msg"
fi
