#!/bin/bash

# Kiểm tra quyền root
if [[ $EUID -ne 0 ]]; then
   echo "Vui lòng chạy script này với quyền sudo: sudo ./setup_mergerfs.sh"
   exit 1
fi

echo "---------------------------------------------------"
echo "   Hệ thống Tự động Gộp Ổ cứng với MergerFS        "
echo "---------------------------------------------------"

# 1. Tự động xác định User và Thư mục dự án
CURRENT_USER=${SUDO_USER:-$USER}
USER_HOME=$(eval echo ~$CURRENT_USER)
PROJECT_DIR="$USER_HOME/my-nvr"
MERGE_POINT="$PROJECT_DIR/media"

echo "[*] Người dùng hiện tại: $CURRENT_USER"
echo "[*] Thư mục dự án mục tiêu: $PROJECT_DIR"

# 2. Cài đặt mergerfs
echo "[*] Đang cài đặt mergerfs và fuse..."
apt update && apt install -y mergerfs fuse

# 3. Liệt kê và chọn ổ cứng
echo ""
echo "Danh sách các phân vùng ổ cứng khả dụng (loại trừ ổ hệ thống):"
# Lấy danh sách các ổ không phải là phân vùng mount tại /
lsblk -p -no NAME,SIZE,MOUNTPOINT,FSTYPE | grep -v ' /$' | grep -v 'SWAP'

echo ""
echo "Nhập tên các phân vùng bạn muốn gộp (cách nhau bởi dấu cách, ví dụ: /dev/sdb1 /dev/sdc1):"
read -r SELECTED_DRIVES

if [ -z "$SELECTED_DRIVES" ]; then
    echo "Lỗi: Bạn chưa chọn ổ cứng nào."
    exit 1
fi

# 4. Tạo Mount Points cho từng ổ và cấu hình fstab
mkdir -p "$MERGE_POINT"
DRIVE_LIST=""
FSTAB_BACKUP="/etc/fstab.bak.$(date +%F_%H%M%S)"
cp /etc/fstab "$FSTAB_BACKUP"
echo "[*] Đã sao lưu fstab tại $FSTAB_BACKUP"

COUNTER=1
for DRIVE in $SELECTED_DRIVES; do
    MOUNT_PATH="/mnt/disk$COUNTER"
    mkdir -p "$MOUNT_PATH"
    
    # Lấy UUID của ổ cứng để ghi vào fstab cho an toàn
    UUID=$(blkid -s UUID -o value "$DRIVE")
    FSTYPE=$(blkid -s TYPE -o value "$DRIVE")
    
    if [ -z "$UUID" ]; then
        echo "Cảnh báo: Không tìm thấy UUID cho $DRIVE. Bỏ qua."
        continue
    fi

    # Thêm vào fstab nếu chưa có
    if ! grep -q "$UUID" /etc/fstab; then
        echo "UUID=$UUID  $MOUNT_PATH  $FSTYPE  defaults  0  2" >> /etc/fstab
    fi
    
    # Gắn kết ổ cứng ngay lập tức
    mount "$MOUNT_PATH" 2>/dev/null || mount -a
    
    # Xây dựng danh sách cho mergerfs
    if [ -z "$DRIVE_LIST" ]; then
        DRIVE_LIST="$MOUNT_PATH"
    else
        DRIVE_LIST="$DRIVE_LIST:$MOUNT_PATH"
    fi
    
    ((COUNTER++))
done

# 5. Cấu hình MergerFS trong fstab
MFS_OPTIONS="allow_other,use_ino,cache.files=off,dropcacheonclose=true,category.create=mfs,minfreespace=10G,fsname=mergerfsPool"

if ! grep -q "$MERGE_POINT" /etc/fstab; then
    echo "$DRIVE_LIST  $MERGE_POINT  mergerfs  $MFS_OPTIONS  0  0" >> /etc/fstab
    echo "[*] Đã thêm cấu hình MergerFS vào /etc/fstab"
fi

# 6. Thực thi mount và kiểm tra
mount -a
echo "[*] Kiểm tra dung lượng sau khi gộp:"
df -h "$MERGE_POINT"

# 7. Khởi động lại Docker nếu có docker-compose.yml
if [ -f "$PROJECT_DIR/docker-compose.yml" ]; then
    echo "[*] Tìm thấy docker-compose, đang khởi động lại..."
    cd "$PROJECT_DIR" && docker compose restart
else
    echo "[!] Không tìm thấy file docker-compose.yml tại $PROJECT_DIR. Vui lòng restart Docker thủ công."
fi

echo "---------------------------------------------------"
echo "HOÀN TẤT! Các ổ cứng đã được gộp vào $MERGE_POINT"
echo "---------------------------------------------------"
