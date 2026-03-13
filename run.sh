#!/bin/bash
# --- ALL-IN-ONE NVR DEPLOYMENT (PHIÊN BẢN 3.0) ---

if [[ $EUID -ne 0 ]]; then
   echo "CRITICAL: Vui lòng chạy script này với quyền sudo: sudo ./run.sh"
   exit 1
fi

echo "===================================================="
echo "   Hệ thống Setup NVR Tự động (PHIÊN BẢN 3.0)        "
echo "===================================================="

read -p "[?] Bạn có muốn DỌN SẠCH dữ liệu/cấu hình cũ (Factory Reset) trước khi cài đặt không? Chỉ dùng cho máy mới. (y/n): " DO_RESET
if [[ "$DO_RESET" =~ ^[Yy]$ ]]; then
    echo "[*] 0. Khởi tạo lại hệ thống (Factory Reset)..."
    docker compose down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf data/nvr.db* data/*.db media/* mosquitto/data/* mosquitto/log/* logs/*
    
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

    [ -f .env ] && sed -i '/FRIGATE_HOST_IP/d' .env
    [ -f .env ] && sed -i '/TAILSCALE_AUTH_KEY/d' .env
    echo "  -> Đã dọn dẹp xong."
fi

# 1. Cài đặt các thành phần nền tảng
echo "[*] Bước 1: Cài đặt Docker, MergerFS và công cụ nền tảng..."
apt-get update
apt-get install -y curl wget git mergerfs fuse3 util-linux

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
echo "[*] Bước 3: Cấu hình Mạng nội bộ..."
LAN_IP=$(hostname -I | awk '{print $1}')
echo "[i] Địa chỉ IP máy là: $LAN_IP"

if [ -f ".env" ]; then
    if grep -q "^FRIGATE_HOST_IP=" .env; then
        sed -i "s/^FRIGATE_HOST_IP=.*/FRIGATE_HOST_IP=$LAN_IP/" .env
    else
        echo "FRIGATE_HOST_IP=$LAN_IP" >> .env
    fi
else
    echo "FRIGATE_HOST_IP=$LAN_IP" > .env
    echo "TZ=Asia/Ho_Chi_Minh" >> .env
    echo "FRIGATE_VERSION=0.16.3" >> .env
fi

# 4. Tailscale & SSH
echo ""
echo "[*] Bước 4: Cài đặt Quản trị từ xa (Tailscale & SSH)..."
if ! command -v sshd >/dev/null; then
    echo "  - Cài đặt OpenSSH Server để tự động hóa bằng Ansible..."
    apt-get install -y openssh-server
    systemctl enable --now ssh
fi

TS_KEY_FROM_ENV=$(grep "^TAILSCALE_AUTH_KEY=" .env 2>/dev/null | cut -d'=' -f2- | tr -d '\r')
if [ -n "$TS_KEY_FROM_ENV" ]; then
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

# 5. Fix Permissions (Đảm bảo không lỗi ghi thư mục)
echo "[*] Bước 5: Mở khóa thư mục (Fix Permissions)..."
chmod -R 777 media data logs mosquitto config
if [ ! -z "$SUDO_USER" ]; then
    chown -R $SUDO_USER:$SUDO_USER .
fi

# 6. Khởi chạy Dịch vụ (QUAN TRỌNG)
echo "[*] Bước 6: Đang kích hoạt hệ thống NVR bằng Docker..."
docker compose pull
docker compose up -d

# 7. Thông báo
echo "===================================================="
echo "Hệ thống NVR đã sẵn sàng!"
echo "Truy cập tại: https://$LAN_IP"
echo "Lưu ý: Bạn MUST dùng HTTPS (vì hệ thống đã cài SSL bảo mật)"
echo "===================================================="

if [ -n "$DISPLAY" ]; then
    xdg-open "https://localhost" &>/dev/null &
fi
