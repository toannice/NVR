#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# overlay-whitelist.sh — nguồn duy nhất của whitelist đường dẫn overlay, cho phía bash.
#
# Vì sao trích thẳng từ source Kotlin thay vì copy danh sách sang đây:
#
# Lớp thật sự bảo vệ máy field là OtaLayout.isAllowedOverlayPath() trong Kotlin. Script bash
# chỉ là lớp kiểm TRƯỚC khi phát hành. Nếu hai bên giữ hai bản danh sách riêng thì chúng sẽ
# lệch nhau — và lệch theo hướng tệ nhất: bash nói "hợp lệ", máy field từ chối ở tận hiện
# trường. Đã xảy ra thật trong lúc phát triển: verify-package.sh báo "GÓI HỢP LỆ" cho một gói
# ghi đè .env, vì nó không có bước kiểm whitelist nào cả.
#
# Trích từ source thì không thể lệch: sửa Kotlin là bash tự theo.
#
# Dùng:  source ota/lib/overlay-whitelist.sh
#        ota_whitelist_check "config/watchdog.yml" && echo ok
# ──────────────────────────────────────────────────────────────────────────────

_OTA_KOTLIN_LAYOUT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/watchdog/src/main/kotlin/vn/aiview/watchdog/ota/OtaLayout.kt"

# Đọc các chuỗi trong khối ALLOWED_OVERLAY_PREFIXES = listOf( ... )
ota_whitelist_prefixes() {
    [[ -f "$_OTA_KOTLIN_LAYOUT" ]] || {
        echo "ota_whitelist: không thấy $_OTA_KOTLIN_LAYOUT" >&2
        return 1
    }
    awk '
        /ALLOWED_OVERLAY_PREFIXES[[:space:]]*=[[:space:]]*listOf\(/ { inblock = 1; next }
        inblock && /^[[:space:]]*\)/                                { inblock = 0 }
        inblock {
            # Lấy mọi chuỗi trong dấu ngoặc kép trên dòng này (bỏ phần comment sau //)
            line = $0
            sub(/\/\/.*/, "", line)
            while (match(line, /"[^"]*"/)) {
                s = substr(line, RSTART + 1, RLENGTH - 2)
                if (s != "") print s
                line = substr(line, RSTART + RLENGTH)
            }
        }
    ' "$_OTA_KOTLIN_LAYOUT"
}

# ota_whitelist_check <đường-dẫn>  → 0 nếu được phép, 1 nếu bị chặn
# Bám sát logic isAllowedOverlayPath(): chặn tuyệt đối, backslash, NUL, "..", rồi so tiền tố.
ota_whitelist_check() {
    local p="$1" pre seg
    [[ -n "$p" ]]        || return 1
    [[ "$p" != /* ]]     || return 1   # đường dẫn tuyệt đối
    [[ "$p" != *'\'* ]]  || return 1   # tách đường dẫn kiểu Windows
    # Không kiểm NUL byte ở đây: biến bash KHÔNG THỂ chứa byte NUL, nên $'\0' là chuỗi rỗng
    # và pattern *$'\0'* trở thành ** khớp mọi thứ — phép kiểm đó vừa vô nghĩa vừa chặn sạch
    # mọi đường dẫn. Phía Kotlin (OtaLayout.isAllowedOverlayPath) vẫn kiểm NUL vì String của
    # JVM chứa được NUL thật.
    [[ "$p" != *".."* ]] || {
        # Chỉ chặn khi ".." là MỘT ĐOẠN trọn vẹn, không chặn tên kiểu "a..b.yml"
        local IFS='/'
        local -a segs=($p)
        IFS=$' \t\n'
        for seg in "${segs[@]}"; do [[ "$seg" != ".." ]] || return 1; done
    }

    while read -r pre; do
        [[ -n "$pre" ]] || continue
        if [[ "$pre" == */ ]]; then
            [[ "$p" == "$pre"* ]] && return 0
        else
            [[ "$p" == "$pre" ]] && return 0
        fi
    done < <(ota_whitelist_prefixes)
    return 1
}
