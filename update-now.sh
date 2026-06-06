#!/bin/bash
if [ -f .env ]; then source .env; fi
echo "[*] Đang gửi lệnh cập nhật tức thì tới Watchtower..."
curl -H "Authorization: Bearer $WATCHTOWER_TOKEN" http://localhost:8080/v1/update
echo ""
echo "[*] Đã gửi lệnh! Kiểm tra quá trình tải tại: docker logs -f watchtower"
