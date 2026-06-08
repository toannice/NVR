#!/bin/bash
# --- ALL-IN-ONE NVR DEPLOYMENT (PHIÊN BẢN 4.0 - CI/CD READY) ---

# -n : không tương tác -> tự trả lời "n" cho Factory Reset & Tailscale
# -r/--rebuild/--dev : CHỈ build lại code + nạp bản mới (vòng lặp dev nhanh), bỏ qua toàn bộ setup
NON_INTERACTIVE=0
REBUILD_ONLY=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        -n|--non-interactive) NON_INTERACTIVE=1 ;;
        -r|--rebuild|--dev) REBUILD_ONLY=1 ;;
        *) echo "[!] Tham số không hợp lệ: $1"; echo "    Dùng: sudo ./run.sh [-n]   |   ./run.sh --rebuild"; exit 1 ;;
    esac
    shift
done

# Full setup cần root; chế độ --rebuild chỉ cần quyền chạy docker.
if [[ "$REBUILD_ONLY" -eq 0 && $EUID -ne 0 ]]; then
   echo "CRITICAL: Vui lòng chạy script này với quyền sudo: sudo ./run.sh"
   exit 1
fi

# ── Hàm dùng chung cho cả full-setup lẫn --rebuild ──────────────────────────────────
# Sau --force-recreate, nvr-wrapper đôi khi kẹt trạng thái Netty: /api/* trả 200 NHƯNG body
# file tĩnh (/, /index.html, /assets...) bị treo -> nginx báo 502. (Hay gặp khi có camera
# offline gây bão lỗi go2rtc lúc khởi động.) Khắc phục đã kiểm chứng: restart SẠCH nvr-wrapper.
wait_backend_ready() {
    # 0 = OK (API + file tĩnh đều 200); 1 = backend không lên; 2 = degraded (API sống, file tĩnh treo)
    local i j api root
    for i in $(seq 1 30); do
        api=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/api/health 2>/dev/null)
        if [ "$api" = "200" ]; then
            for j in 1 2 3; do
                root=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/ 2>/dev/null)
                [ "$root" = "200" ] && return 0
                sleep 1
            done
            return 2
        fi
        sleep 2
    done
    return 1
}

# Nạp bản mới của nvr-wrapper (recreate) + tự chữa kẹt file-tĩnh + đảm bảo nginx chạy.
deploy_wrapper() {
    docker compose up -d --force-recreate --no-deps nvr-wrapper
    echo "[*] Kiểm tra nvr-wrapper phục vụ được giao diện web (không chỉ API)..."
    wait_backend_ready; local RC=$?
    if [ "$RC" = "2" ]; then
        echo "[!] nvr-wrapper kẹt (API sống nhưng file tĩnh treo) -> restart sạch..."
        docker compose restart nvr-wrapper
        wait_backend_ready; RC=$?
    fi
    case "$RC" in
        0) echo "  -> OK: web phục vụ bình thường." ;;
        1) echo "  [!] CẢNH BÁO: backend không phản hồi. Xem: docker compose logs nvr-wrapper" ;;
        2) echo "  [!] CẢNH BÁO: file tĩnh vẫn treo sau restart. Xem: docker compose logs nvr-wrapper" ;;
    esac
    echo "[*] Đảm bảo nginx đang chạy (không tự start khi nvr-wrapper recreate)..."
    docker compose up -d nginx
}

# ── Vòng lặp DEV nhanh: dùng sau mỗi lần sửa code ───────────────────────────────────
#   ./run.sh --rebuild
# KHÔNG --no-cache (Dockerfile đã tách deps/source + cache mount BuildKit) -> chỉ recompile
# phần đổi, không tải lại dependency. Bỏ qua factory-reset/đĩa/env/tailscale.
if [[ "$REBUILD_ONLY" -eq 1 ]]; then
    echo "===================================================="
    echo "   NVR — BUILD LẠI NHANH (dev rebuild)"
    echo "===================================================="
    echo "[*] Build nvr-wrapper từ source (dùng cache)..."
    docker compose build nvr-wrapper || { echo "[!] Build thất bại."; exit 1; }
    deploy_wrapper
    echo "[*] Xong. Truy cập: https://$(hostname -I | awk '{print $1}')"
    exit 0
fi

echo "===================================================="
echo "   Hệ thống Setup NVR Tự động (PHIÊN BẢN 4.0 - CI/CD) "
echo "===================================================="

if [[ "$NON_INTERACTIVE" -eq 1 ]]; then
    DO_RESET="n"
    echo "[?] Factory Reset? (y/n): n  (tự động do -n)"
else
    read -p "[?] Bạn có muốn DỌN SẠCH dữ liệu/cấu hình cũ (Factory Reset) trước khi cài đặt không? Chỉ dùng cho cấu hình máy mới tinh. (y/n): " DO_RESET
fi
if [[ "$DO_RESET" =~ ^[Yy]$ ]]; then
    echo "[*] 0. Khởi tạo lại hệ thống (Factory Reset)..."
    docker compose down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf data/nvr.db* data/*.db media/* mosquitto/data/* mosquitto/log/* logs/*
    
    mkdir -p config
    cat <<EOF > config/config.yml
version: 0.16-0
mqtt:
  enabled: true
  host: mosquitto
detect:
  enabled: true
record:
  enabled: true
cameras: {}
EOF

    for disk in /mnt/nvr_disk*; do
        if [ -d "$disk" ]; then
            echo "  - Đang dọn sạch ổ phụ: $disk"
            find "$disk" -mindepth 1 -delete >/dev/null 2>&1
        fi
    done

    # Xoá các biến cũ nhưng giữ lại file
    [ -f .env ] && sed -i '/FRIGATE_HOST_IP/d' .env
    [ -f .env ] && sed -i '/TAILSCALE_AUTH_KEY/d' .env
    echo "  -> Đã dọn dẹp xong."
fi

# 1. Cài đặt các thành phần nền tảng
echo "[*] Bước 1: Cài đặt Docker, MergerFS và công cụ nền tảng..."
apt-get update
apt-get install -y curl wget git mergerfs fuse3 util-linux jq

# Đảm bảo các thư mục cấu trúc luôn có sẵn
mkdir -p data media mosquitto/data mosquitto/log mosquitto/config config logs nginx/html

# Cài đặt Docker if missing
if ! [ -x "$(command -v docker)" ]; then
    echo "[*] Đang cài đặt Docker engine..."
    curl -fsSL https://get.docker.com | sh
fi

# 2. Storage Pooling (Gộp ổ cứng)
echo "[*] Bước 2: Tự động phát hiện và gộp ổ cứng..."
PROJECT_ROOT=$(pwd)
MERGE_POINT="$PROJECT_ROOT/media"

OS_DISK=$(lsblk -no PKNAME $(findmnt -n -o SOURCE /) | head -n1)
if [ -z "$OS_DISK" ]; then OS_DISK="sda"; fi

DRIVE_LIST=""
COUNTER=1
for dev in $(lsblk -dno NAME | grep -v "^$OS_DISK" | grep -v "^loop"); do
    FULL_PATH="/dev/$dev"
    echo "  - Phát hiện ổ đĩa phụ: $FULL_PATH"
    
    if ! blkid "$FULL_PATH" >/dev/null 2>&1; then
        echo "    [!] Định dạng ổ trống $FULL_PATH sang EXT4..."
        mkfs.ext4 -F "$FULL_PATH"
    fi

    MOUNT_PATH="/mnt/nvr_disk$COUNTER"
    mkdir -p "$MOUNT_PATH"
    UUID=$(blkid -s UUID -o value "$FULL_PATH")
    
    if [ -n "$UUID" ]; then
        if ! grep -q "$UUID" /etc/fstab; then
            echo "UUID=$UUID  $MOUNT_PATH  ext4  defaults,nofail  0  2" >> /etc/fstab
        fi
        mount "$MOUNT_PATH" 2>/dev/null || mount -a
        if [ -z "$DRIVE_LIST" ]; then DRIVE_LIST="$MOUNT_PATH"; else DRIVE_LIST="$DRIVE_LIST:$MOUNT_PATH"; fi
        ((COUNTER++))
    fi
done

if [ -n "$DRIVE_LIST" ]; then
    echo "[*] Đang gộp ổ đĩa vào $MERGE_POINT..."
    MFS_OPTIONS="allow_other,use_ino,cache.files=off,dropcacheonclose=true,category.create=mfs,minfreespace=10G,fsname=nvrPool"
    if ! grep -q "$MERGE_POINT" /etc/fstab; then
        echo "$DRIVE_LIST  $MERGE_POINT  mergerfs  $MFS_OPTIONS  0  0" >> /etc/fstab
    fi
    mount -a
fi

# 3. Mạng và Môi trường
echo "[*] Bước 3: Cấu hình Mạng nội bộ và Biến theo dõi (CI/CD)..."
LAN_IP=$(hostname -I | awk '{print $1}')
echo "[i] Địa chỉ IP máy là: $LAN_IP"

touch .env
if grep -q "^FRIGATE_HOST_IP=" .env; then
    sed -i "s/^FRIGATE_HOST_IP=.*/FRIGATE_HOST_IP=$LAN_IP/" .env
else
    echo "FRIGATE_HOST_IP=$LAN_IP" >> .env
fi
grep -q "^TZ=" .env || echo "TZ=Asia/Ho_Chi_Minh" >> .env
grep -q "^FRIGATE_VERSION=" .env || echo "FRIGATE_VERSION=0.16.3" >> .env

# --- CẤU HÌNH GHCR / TELEGRAM / WATCHTOWER ---
if ! grep -q "GITHUB_TOKEN=" .env; then
    echo ""
    echo "--- CẤU HÌNH LIÊN KẾT GITHUB & TELEGRAM ---"
    read -p "[?] Nhập GitHub Username (Của bạn hoặc Tổ chức) [Mặc định: toannice]: " GH_USER
    GH_USER=${GH_USER:-toannice}
    
    read -p "[?] Nhập GitHub Personal Access Token (Bắt buộc để pull từ ghcr.io): " GH_PAT
    
    # Trích xuất mặc định từ deploy/edge_ota_update.sh (Hoặc hardcode theo thông tin đã quét được)
    DEFAULT_TG_BOT="8637751033:AAFTEi0-kdVIjTYCwRrb4xLVVFqCShipi6s"
    DEFAULT_TG_CHAT="6655437760"

    read -p "[?] Nhập Telegram Bot Token [Mặc định: $DEFAULT_TG_BOT]: " TELEGRAM_TOKEN
    TELEGRAM_TOKEN=${TELEGRAM_TOKEN:-$DEFAULT_TG_BOT}
    
    read -p "[?] Nhập Telegram Chat ID [Mặc định: $DEFAULT_TG_CHAT]: " TELEGRAM_CHAT_ID
    TELEGRAM_CHAT_ID=${TELEGRAM_CHAT_ID:-$DEFAULT_TG_CHAT}

    echo "GITHUB_USERNAME=\"$GH_USER\"" >> .env
    echo "GITHUB_TOKEN=\"$GH_PAT\"" >> .env
    echo "TELEGRAM_BOT_TOKEN=\"$TELEGRAM_TOKEN\"" >> .env
    echo "TELEGRAM_CHAT_ID=\"$TELEGRAM_CHAT_ID\"" >> .env
    # Tạo Token ngẫu nhiên bảo mật cho API cập nhật thủ công
    echo "WATCHTOWER_TOKEN=\"$(date +%s | sha256sum | base64 | tr -dc 'a-zA-Z0-9' | head -c 24)\"" >> .env
    echo "WATCHTOWER_SCHEDULE=\"0 0 3 * * *\"" >> .env  # Tự động cập nhật 3h sáng
fi
echo "DOCKER_API_VERSION=\"1.44\"" >> .env

# 4. Tailscale & SSH
echo ""
echo "[*] Bước 4: Cài đặt Quản trị từ xa (Tailscale & SSH)..."
if ! command -v sshd >/dev/null; then
    echo "  - Cài đặt OpenSSH Server để tự động hóa bằng Ansible..."
    apt-get install -y openssh-server
    systemctl enable --now ssh
fi

TS_KEY_FROM_ENV=$(grep "^TAILSCALE_AUTH_KEY=" .env 2>/dev/null | cut -d'=' -f2- | tr -d '\r')
if [[ "$NON_INTERACTIVE" -eq 1 ]]; then
    INSTALL_TS="n"
    echo "[?] Cài Tailscale? (y/n): n  (tự động do -n)"
elif [ -n "$TS_KEY_FROM_ENV" ]; then
    read -p "[?] Đã có Key Tailscale trong .env, bạn có muốn kết nối tự động? (y/n): " INSTALL_TS
    TS_KEY=$TS_KEY_FROM_ENV
else
    read -p "[?] Bạn có muốn cài đặt Tailscale để nối vào mạng trạm không? (y/n): " INSTALL_TS
fi

if [[ "$INSTALL_TS" =~ ^[Yy]$ ]]; then
    if ! command -v tailscale >/dev/null; then
        curl -fsSL https://tailscale.com/install.sh | sh
    fi
    [ -z "$TS_KEY" ] && read -p "[?] Nhập Tailscale Auth Key: " TS_KEY
    [ -n "$TS_KEY" ] && tailscale up --authkey="$TS_KEY"
fi

# 5. Fix Permissions
echo "[*] Bước 5: Mở khóa thư mục (Fix Permissions)..."
chmod -R 777 media data logs mosquitto config
if [ ! -z "$SUDO_USER" ]; then
    chown -R $SUDO_USER:$SUDO_USER .
fi

# 6. Build & Khởi chạy Dịch vụ
echo "[*] Bước 6: Build image staging và kích hoạt hệ thống NVR..."
source .env
if [ -n "$GITHUB_TOKEN" ] && [ -n "$GITHUB_USERNAME" ]; then
    echo "[*] Đăng nhập GitHub Container Registry..."
    echo "$GITHUB_TOKEN" | docker login ghcr.io -u "$GITHUB_USERNAME" --password-stdin
fi

echo "[*] Build nvr-wrapper từ source code..."
docker compose build --no-cache nvr-wrapper

echo "[*] Pull các image còn lại (frigate, mosquitto, nginx...)..."
docker compose pull --ignore-buildable 2>/dev/null || docker compose pull

echo "[*] Khởi chạy toàn bộ stack..."
docker compose up -d
deploy_wrapper   # force-recreate nvr-wrapper + tự chữa kẹt file-tĩnh + đảm bảo nginx chạy

echo "[*] Cấu hình tự khởi động (Auto Boot) khi có mạng hoặc sau kết nối..."
cp nvr-stack.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now nvr-stack.service

# 7. Thông báo
echo "===================================================="
echo "Hệ thống NVR đã sẵn sàng!"
echo "Truy cập tại: https://$LAN_IP"
echo "Lưu ý: Bạn MUST dùng HTTPS (vì hệ thống đã cài SSL bảo mật)"
echo "Sau khi sửa code, build lại nhanh bằng: ./run.sh --rebuild"
echo "===================================================="

if [ -n "$DISPLAY" ]; then
    xdg-open "https://localhost" &>/dev/null &
fi
