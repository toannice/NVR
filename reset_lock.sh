#!/bin/bash

# --- RESET NVR LOCK PATTERN SCRIPT ---
# Script này dùng để xóa khóa vẽ (pattern lock) cũ 
# Để bạn có thể tạo khóa mới khi di chuyển sang máy tính khác.
# ----------------------------------------

# Xác định đường dẫn DB
DB_PATH="$(pwd)/data/nvr.db"

if [ ! -f "$DB_PATH" ]; then
    echo "Lỗi: Không tìm thấy file dữ liệu tại $DB_PATH"
    exit 1
fi

echo "[*] Đang tiến hành xóa khóa cũ..."

# Chạy lệnh xóa trong SQLite
# Sử dụng sudo nếu cần quyền ghi vào thư mục data
sqlite3 "$DB_PATH" "DELETE FROM lock_config;"

if [ $? -eq 0 ]; then
    echo "===================================================="
    echo "         XÓA KHÓA THÀNH CÔNG!                       "
    echo "===================================================="
    echo "[i] Vui lòng F5 lại trình duyệt."
    echo "[i] Hệ thống sẽ yêu cầu bạn vẽ Khóa mới (Setup mode)."
    echo "===================================================="
    
    # Khởi động lại container nvr-wrapper để xóa cache nếu cần
    docker restart nvr-wrapper >/dev/null 2>&1
else
    echo "Lỗi: Không thể truy cập cơ sở dữ liệu. Hãy thử chạy: sudo ./reset_lock.sh"
fi

exit 0
