#!/bin/bash
# Chạy NVR AI Visual Tester
# Dùng: ./ai-test/run.sh

set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Load và export tất cả biến từ .env
if [ -f "$DIR/.env" ]; then
    set -a
    source "$DIR/.env"
    set +a
fi

if [ -z "$GEMINI_API_KEY" ]; then
    echo "ERROR: GEMINI_API_KEY chưa set."
    echo ""
    echo "1. Lấy key miễn phí: https://aistudio.google.com/apikey"
    echo "2. Thêm vào ai-test/.env:"
    echo "   GEMINI_API_KEY=..."
    echo "   NVR_PATTERN=0,1,2,5,8   # pattern của bạn (bỏ qua nếu chưa setup)"
    exit 1
fi

echo "Chạy AI UI test..."
"$DIR/venv/bin/python3" "$DIR/test_nvr_ui.py" "$@"
