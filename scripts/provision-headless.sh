#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# provision-headless.sh — cấu hình máy NVR qua SSH, không cần web UI.
#
# Dùng khi máy chạy Ubuntu headless: không có màn hình, không có trình duyệt, và
# cũng không có máy nào khác trong LAN để mở https://<ip>. Script gọi đúng những
# REST endpoint mà web UI vẫn gọi — không có đường ghi dữ liệu thứ hai (P1).
#
#   sudo ./provision-headless.sh --cameras cams.txt \
#        --cloud-domain vms.example.vn --cloud-user box01 --serial VMSAV000123
#
#   ./provision-headless.sh --cameras cams.txt --dry-run   # chỉ kiểm file, không gọi API
#   ./provision-headless.sh --pattern 12369                # chỉ đặt mã vẽ + lưu recovery code
#
# Chạy lại được nhiều lần (idempotent): máy đã thiết lập thì đăng nhập thay vì
# setup lại, camera đã có thì bỏ qua, cloud thì ghi đè cấu hình mới.
#
# Vì sao recovery code phải ghi ra file: /api/lock/setup trả mã khôi phục ĐÚNG MỘT
# LẦN, không có endpoint nào đọc lại. Trên máy headless mã đó trôi qua màn hình SSH
# là mất đường khôi phục offline duy nhất — nên script ghi xuống secrets/ (chmod 600)
# trước khi in ra, và từ chối chạy setup nếu file đó đã tồn tại.
#
# Phụ thuộc: curl, jq (jq đã là phụ thuộc sẵn của scripts/ota-apply.sh).
# ──────────────────────────────────────────────────────────────────────────────
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# ── Mặc định ─────────────────────────────────────────────────────────────────
# Gọi thẳng cổng 3000 trên loopback: nvr-wrapper nghe BIND_HOST=127.0.0.1:3000 nên
# từ chính máy đó không cần qua nginx, tránh luôn cảnh báo cert tự ký.
BASE="http://127.0.0.1:3000"
INSECURE=0
TIMEOUT=30
RESTART_TIMEOUT=180        # thêm camera = ghi config.yml + restart Frigate, chờ lâu hơn
DRY_RUN=0
PATTERN=""
RECOVERY_OUT="$ROOT/secrets/recovery-code.txt"
CAMERAS_FILE=""
CLOUD_DOMAIN=""; CLOUD_PORT=""; CLOUD_USER=""; CLOUD_PASS=""; CLOUD_SERIAL=""
CLOUD_ENABLED=true
CLOUD_PASS_SET=0
# Cấu hình cloud đọc sẵn từ file này. Để ở secrets/ vì secrets/ đã gitignore: mật khẩu
# cloud là secret thật, nhúng vào script theo git là đẩy nó lên GitHub. Serial cũng
# phải nằm đây chứ không hardcode — serial là định danh RIÊNG của từng máy, hai máy
# cùng serial thì cloud không phân biệt được máy nào.
CLOUD_CONF="$ROOT/secrets/cloud.conf"
# Frigate để kiểm lại sau khi thêm camera. Xem chú thích ở bước [4/4] về lý do bắt buộc kiểm.
FRIGATE_URL="http://127.0.0.1:5000"
FRIGATE_WAIT=120
CHECK_FRIGATE=1

# KHÔNG đặt sẵn địa chỉ máy chủ cloud ở đây. File này theo git, nên một địa chỉ hạ tầng
# nội bộ ghi vào đây là đẩy nó lên GitHub — tiện cho máy dev một lần, mà lộ vĩnh viễn.
# Địa chỉ đi qua --cloud-domain hoặc secrets/cloud.conf (không theo git).
# Nhớ kèm http:// nếu cloud chạy HTTP thuần: thiếu scheme thì backend tự thêm https://
# (SignalingHttpClient.kt kiểm startsWith("http")) và gọi sai giao thức.
DEFAULT_CLOUD_DOMAIN=""
DEFAULT_CLOUD_PORT=30009

FAILS=0; WARNS=0
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; WARNS=$((WARNS+1)); }
fail() { printf '\033[1;31m  ✗ %s\033[0m\n' "$*"; FAILS=$((FAILS+1)); }
hdr()  { printf '\n\033[1;36m%s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 2; }
need() { command -v "$1" >/dev/null 2>&1 || die "thiếu lệnh '$1' — cài bằng: apt-get install -y $1"; }

usage() {
    awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "${BASH_SOURCE[0]}"
    cat <<'USAGE'

Tuỳ chọn:
  --url URL              gốc API (mặc định http://127.0.0.1:3000)
  --insecure             bỏ kiểm cert TLS (khi --url là https://<ip> cert tự ký)
  --pattern P            mã vẽ (>=4 ký tự). Bỏ trống thì script hỏi, giấu khi gõ
  --recovery-out FILE    nơi ghi mã khôi phục (mặc định secrets/recovery-code.txt)
  --cameras FILE         danh sách camera, phân tách bằng dấu |
  --frigate-url URL      Frigate để kiểm lại sau mỗi lần thêm camera
                         (mặc định http://127.0.0.1:5000)
  --no-frigate-check     không chờ/không kiểm Frigate sau khi thêm camera
  --cloud-conf FILE      file cấu hình cloud (mặc định secrets/cloud.conf, có thì tự nạp)
  --no-cloud-conf        bỏ qua file đó, chỉ dùng tham số dòng lệnh
  --cloud-domain HOST    tên miền/IP cloud, kèm http:// nếu cloud chạy HTTP thuần
                         (không có mặc định — bắt buộc khi làm bước cloud)
  --cloud-port N         mặc định 30009
  --cloud-user U
  --cloud-pass P         bỏ trống thì hỏi, giấu khi gõ. Gõ Enter = giữ mật khẩu cũ
  --serial VMSAVxxxxxx   6 chữ số sau VMSAV. Thiếu --cloud-user hoặc --serial (và cũng
                         không có trong file) thì bước cloud bị bỏ qua hẳn
  --cloud-disabled       lưu cấu hình nhưng để tắt
  --dry-run              chỉ kiểm tra tham số + file camera, không gọi API
  -h, --help

Định dạng secrets/cloud.conf (KEY=VALUE, không phải shell script):
  CLOUD_DOMAIN=http://<ip-hoac-ten-mien-cloud>
  CLOUD_PORT=30009
  CLOUD_USER=...
  CLOUD_PASSWORD=...
  CLOUD_SERIAL=VMSAV<6-chu-so>
Serial là định danh RIÊNG của từng máy — mỗi máy một file, đừng chép nguyên sang máy khác.

Định dạng file camera (dấu | ngăn cách, # là chú thích):
  # tên | rtsp chính | rtsp phụ | cổng onvif | ghi hình | ptz
  Cong chinh | rtsp://admin:pw@192.168.1.xx0:554/s1 | rtsp://admin:pw@192.168.1.xx0:554/s2 | 80 | true | false
  Kho hang   | rtsp://admin:pw@192.168.1.xx1:554/s1

Bốn cột cuối đều có thể bỏ trống (mặc định: rtsp phụ rỗng, onvif 80, ghi hình true, ptz false).
Dùng | vì mật khẩu và query string trong URL RTSP hay chứa dấu phẩy.
USAGE
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --url)            BASE="${2:?}"; shift 2 ;;
        --insecure)       INSECURE=1; shift ;;
        --pattern)        PATTERN="${2:?}"; shift 2 ;;
        --recovery-out)   RECOVERY_OUT="${2:?}"; shift 2 ;;
        --cameras)        CAMERAS_FILE="${2:?}"; shift 2 ;;
        --frigate-url)    FRIGATE_URL="${2:?}"; shift 2 ;;
        --no-frigate-check) CHECK_FRIGATE=0; shift ;;
        --cloud-conf)     CLOUD_CONF="${2:?}"; shift 2 ;;
        --no-cloud-conf)  CLOUD_CONF=""; shift ;;
        --cloud-domain)   CLOUD_DOMAIN="${2:?}"; shift 2 ;;
        --cloud-port)     CLOUD_PORT="${2:?}"; shift 2 ;;
        --cloud-user)     CLOUD_USER="${2:?}"; shift 2 ;;
        --cloud-pass)     CLOUD_PASS="${2:?}"; CLOUD_PASS_SET=1; shift 2 ;;
        --serial)         CLOUD_SERIAL="${2:?}"; shift 2 ;;
        --cloud-disabled) CLOUD_ENABLED=false; shift ;;
        --dry-run)        DRY_RUN=1; shift ;;
        -h|--help)        usage; exit 0 ;;
        *) die "tham số lạ: $1  (xem --help)" ;;
    esac
done

# Cắt khoảng trắng hai đầu. Không dùng xargs: nó diễn giải dấu nháy và backslash,
# mà mật khẩu trong URL RTSP hoàn toàn có thể chứa chúng.
trim() { local s="$1"; s="${s#"${s%%[![:space:]]*}"}"; s="${s%"${s##*[![:space:]]}"}"; printf '%s' "$s"; }

BASE="${BASE%/}"
need curl
need jq

# ── Nạp secrets/cloud.conf ───────────────────────────────────────────────────
# Đọc KEY=VALUE bằng tay, KHÔNG `source`: file nằm trong secrets/ nhưng `source` nghĩa là
# bất cứ gì trong đó cũng chạy được bằng quyền đang chạy script. Tham số dòng lệnh đã
# truyền thì thắng giá trị trong file.
conf_get() { # conf_get <file> <KEY>
    local v
    v="$(grep -m1 -E "^[[:space:]]*$2[[:space:]]*=" "$1" 2>/dev/null | cut -d= -f2-)" || return 0
    v="$(trim "$v")"
    v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
    printf '%s' "$v"
}

if [[ -n "$CLOUD_CONF" && -r "$CLOUD_CONF" ]]; then
    [[ -z "$CLOUD_DOMAIN" ]] && CLOUD_DOMAIN="$(conf_get "$CLOUD_CONF" CLOUD_DOMAIN)"
    [[ -z "$CLOUD_PORT"   ]] && CLOUD_PORT="$(conf_get "$CLOUD_CONF" CLOUD_PORT)"
    [[ -z "$CLOUD_USER"   ]] && CLOUD_USER="$(conf_get "$CLOUD_CONF" CLOUD_USER)"
    [[ -z "$CLOUD_SERIAL" ]] && CLOUD_SERIAL="$(conf_get "$CLOUD_CONF" CLOUD_SERIAL)"
    if [[ $CLOUD_PASS_SET -eq 0 ]]; then
        CLOUD_PASS="$(conf_get "$CLOUD_CONF" CLOUD_PASSWORD)"
        [[ -n "$CLOUD_PASS" ]] && CLOUD_PASS_SET=1
    fi
    CONF_USED="$CLOUD_CONF"
else
    CONF_USED=""
fi
CLOUD_DOMAIN="${CLOUD_DOMAIN:-$DEFAULT_CLOUD_DOMAIN}"
CLOUD_PORT="${CLOUD_PORT:-$DEFAULT_CLOUD_PORT}"

JAR=""
cleanup() { [[ -n "$JAR" && -f "$JAR" ]] && rm -f "$JAR"; }
trap cleanup EXIT INT TERM

# ── Gọi API ──────────────────────────────────────────────────────────────────
# Đặt HTTP_CODE + RESP. Trả 0 khi 2xx. Không dùng --fail vì cần đọc body lỗi:
# backend trả lý do thật trong {"error":...} và đó là thứ người vận hành cần thấy.
HTTP_CODE=""; RESP=""
api() {
    local method="$1" path="$2" body="${3:-}" tmo="${4:-$TIMEOUT}" out
    local -a args=(-sS -m "$tmo" -w $'\n%{http_code}' -X "$method" -b "$JAR" -c "$JAR")
    [[ $INSECURE -eq 1 ]] && args+=(-k)
    [[ -n "$body" ]] && args+=(-H 'Content-Type: application/json' --data-binary "$body")
    # curl vẫn in %{http_code} (=000) khi không nối được, nên tách giống hệt nhau cho cả
    # hai đường — khác đi thì mã 000 bị dính vào cuối thông báo lỗi.
    out="$(curl "${args[@]}" "$BASE$path" 2>&1)"
    HTTP_CODE="${out##*$'\n'}"
    RESP="${out%$'\n'*}"
    [[ "$HTTP_CODE" =~ ^[0-9]{3}$ ]] || { RESP="$out"; HTTP_CODE="000"; }
    [[ "$HTTP_CODE" =~ ^2[0-9][0-9]$ ]]
}

# Lý do lỗi: {"error":...} hoặc {"message":...} của backend, không có thì in mã HTTP.
why() {
    local m; m="$(jq -r '.error? // .message? // empty' <<<"$RESP" 2>/dev/null)"
    [[ -n "$m" ]] && printf '%s' "$m" || printf 'HTTP %s %s' "$HTTP_CODE" "$(head -c 160 <<<"$RESP" | tr -d '\n')"
}

ask_secret() { printf '\033[1;33m  → %s: \033[0m' "$1" >&2; read -rs REPLY; echo "" >&2; }

# ── Kiểm tra URL RTSP ────────────────────────────────────────────────────────
# Giống validateRtspUrl() trong CameraRouting.kt — kiểm tại chỗ để --dry-run bắt
# được lỗi khi chưa đụng tới máy, backend vẫn kiểm lại lần nữa.
validate_rtsp() {
    local url="$1" lower host
    lower="$(tr 'A-Z' 'a-z' <<<"$url")"
    [[ -z "$url" ]] && { echo "URL rỗng"; return 1; }
    case "$lower" in rtsp://*|rtsps://*) ;; *) echo "phải bắt đầu bằng rtsp:// hoặc rtsps://"; return 1 ;; esac
    host="${url#*//}"; host="${host%%/*}"; host="${host##*@}"; host="${host%%:*}"
    host="$(tr 'A-Z' 'a-z' <<<"$host")"
    case "$host" in
        localhost|host.docker.internal) echo "host bị cấm: $host"; return 1 ;;
        127.*)       echo "loopback bị cấm: $host"; return 1 ;;
        169.254.*)   echo "địa chỉ link-local bị cấm: $host"; return 1 ;;
    esac
    return 0
}

# ── Đọc + kiểm file camera ───────────────────────────────────────────────────
# Đổ ra mảng CAM_* song song nhau. Kiểm toàn bộ file TRƯỚC khi thêm con nào: thêm
# nửa chừng rồi chết vì dòng 7 sai cú pháp là trạng thái tệ nhất, vì mỗi lần thêm
# đã restart Frigate một lần.
declare -a CAM_NAME CAM_RTSP CAM_SUB CAM_PORT CAM_REC CAM_PTZ
parse_cameras() {
    local file="$1" lineno=0 line name rtsp sub port rec ptz err bad=0
    [[ -r "$file" ]] || die "không đọc được file camera: $file"
    while IFS= read -r line || [[ -n "$line" ]]; do
        lineno=$((lineno+1))
        line="${line%%$'\r'}"
        [[ -z "${line// /}" ]] && continue
        [[ "${line#"${line%%[![:space:]]*}"}" == \#* ]] && continue

        IFS='|' read -r name rtsp sub port rec ptz <<<"$line"
        name="$(trim "${name:-}")"; rtsp="$(trim "${rtsp:-}")"; sub="$(trim "${sub:-}")"
        port="$(trim "${port:-}")"; rec="$(trim "${rec:-}")";  ptz="$(trim "${ptz:-}")"

        [[ -z "$name" ]] && { fail "dòng $lineno: thiếu tên camera"; bad=1; continue; }
        if ! err="$(validate_rtsp "$rtsp")"; then
            fail "dòng $lineno ($name): rtsp chính — $err"; bad=1; continue
        fi
        if [[ -n "$sub" ]] && ! err="$(validate_rtsp "$sub")"; then
            fail "dòng $lineno ($name): rtsp phụ — $err"; bad=1; continue
        fi
        port="${port:-80}"
        [[ "$port" =~ ^[0-9]+$ && $port -ge 1 && $port -le 65535 ]] \
            || { fail "dòng $lineno ($name): cổng ONVIF không hợp lệ: $port"; bad=1; continue; }
        rec="$(tr 'A-Z' 'a-z' <<<"${rec:-true}")"; [[ "$rec" == "true" || "$rec" == "false" ]] \
            || { fail "dòng $lineno ($name): cột ghi hình phải là true/false, đang là: $rec"; bad=1; continue; }
        ptz="$(tr 'A-Z' 'a-z' <<<"${ptz:-false}")"; [[ "$ptz" == "true" || "$ptz" == "false" ]] \
            || { fail "dòng $lineno ($name): cột ptz phải là true/false, đang là: $ptz"; bad=1; continue; }

        CAM_NAME+=("$name"); CAM_RTSP+=("$rtsp"); CAM_SUB+=("$sub")
        CAM_PORT+=("$port"); CAM_REC+=("$rec"); CAM_PTZ+=("$ptz")
    done < "$file"
    [[ $bad -eq 1 ]] && die "file camera có lỗi — sửa xong chạy lại (chưa đụng gì tới máy)"
    [[ ${#CAM_NAME[@]} -eq 0 ]] && warn "file camera không có dòng nào dùng được"
    return 0
}

# ── Kiểm tham số cloud ───────────────────────────────────────────────────────
# domain giữ NGUYÊN những gì nhập vào, kể cả tiền tố http://. SignalingHttpClient.kt kiểm
# startsWith("http"): có scheme thì dùng y nguyên, không có thì tự thêm https://. Nên cloud
# chạy HTTP thuần BẮT BUỘC phải ghi http:// — bỏ đi là backend lặng lẽ gọi sang https.
validate_cloud() {
    [[ "$CLOUD_SERIAL" =~ ^VMSAV[0-9]{6}$ ]] \
        || die "serial sai định dạng: '${CLOUD_SERIAL:-<trống>}' — phải là VMSAV + 6 chữ số"
    [[ "$CLOUD_PORT" =~ ^[0-9]+$ && $CLOUD_PORT -ge 1 && $CLOUD_PORT -le 65535 ]] \
        || die "cổng cloud không hợp lệ: $CLOUD_PORT"
    [[ -n "$CLOUD_USER" ]] || die "thiếu --cloud-user"
    # Không còn domain mặc định trong script, nên thiếu là phải dừng ở đây: POST
    # /api/cloud/config từ chối domain trống bằng 400 "Domain must not be blank", và một
    # máy lưu cấu hình cloud rỗng thì nhìn như đã cấu hình xong mà không bao giờ kết nối.
    [[ -n "$CLOUD_DOMAIN" ]] \
        || die "thiếu --cloud-domain (hoặc CLOUD_DOMAIN trong ${CLOUD_CONF:-secrets/cloud.conf})"
}

# ── Chờ Frigate sống lại ─────────────────────────────────────────────────────
# POST /api/restart của Frigate làm container THOÁT (exit 143) rồi dựa vào
# restart: unless-stopped của compose để lên lại. Đo trên máy dev: mất ~28 giây.
wait_frigate() { # wait_frigate <giây tối đa> → 0 nếu sống lại, 1 nếu hết giờ
    local max="$1" i=0 args=(-s -o /dev/null -m 3 -f)
    [[ $INSECURE -eq 1 ]] && args+=(-k)
    while (( i < max )); do
        curl "${args[@]}" "$FRIGATE_URL/api/version" 2>/dev/null && return 0
        sleep 2; i=$((i+2))
    done
    return 1
}

# Danh sách camera Frigate ĐANG THẬT SỰ nạp — bằng chứng cuối cùng, vì DB và config.yml
# đúng vẫn chưa chắc Frigate chịu nạp (một dòng sai là crash-loop).
frigate_cameras() {
    local args=(-s -m 5)
    [[ $INSECURE -eq 1 ]] && args+=(-k)
    curl "${args[@]}" "$FRIGATE_URL/api/config" 2>/dev/null | jq -r '.cameras // {} | keys[]' 2>/dev/null
}

echo "════════════════════════════════════════════"
echo "  NVR provision headless  $([[ $DRY_RUN -eq 1 ]] && echo '(--dry-run, không gọi API)')"
echo "  API: $BASE"
echo "════════════════════════════════════════════"

# ── 0. Kiểm tham số offline (luôn chạy, kể cả không dry-run) ─────────────────
hdr "[0/4] Kiểm tham số"
[[ -n "$CAMERAS_FILE" ]] && { parse_cameras "$CAMERAS_FILE"; ok "file camera hợp lệ: ${#CAM_NAME[@]} dòng"; }
[[ -n "$CONF_USED" ]] && ok "đã nạp cấu hình cloud từ $CONF_USED"
if [[ -n "$CLOUD_USER" || -n "$CLOUD_SERIAL" ]]; then
    validate_cloud
    ok "tham số cloud hợp lệ: $CLOUD_DOMAIN:$CLOUD_PORT user=$CLOUD_USER serial=$CLOUD_SERIAL"
else
    warn "chưa có username/serial cloud → sẽ bỏ qua bước cloud (domain=${CLOUD_DOMAIN:-<trống>} port=$CLOUD_PORT)"
fi
[[ -n "$PATTERN" && ${#PATTERN} -lt 4 ]] && die "mã vẽ quá ngắn (tối thiểu 4 ký tự)"
[[ -z "$CAMERAS_FILE" && -z "$CLOUD_USER" && -z "$PATTERN" ]] && warn "không có --pattern/--cameras và không có cấu hình cloud: chỉ đặt mã vẽ (sẽ hỏi)"

if [[ $DRY_RUN -eq 1 ]]; then
    echo ""
    echo "════════════════════════════════════════════"
    c=$([[ $FAILS -eq 0 ]] && echo "Tham số hợp lệ — bỏ --dry-run để chạy thật." || echo "Có lỗi tham số.")
    echo "  $c"
    exit $(( FAILS > 0 ? 1 : 0 ))
fi

JAR="$(mktemp)"; chmod 600 "$JAR"

# ── 1. Máy có sống không ─────────────────────────────────────────────────────
hdr "[1/4] Kết nối backend"
if ! api GET /api/health; then
    fail "không gọi được $BASE/api/health — $(why)"
    echo "    Kiểm: docker ps | grep nvr-wrapper   và   ss -lntp | grep 3000"
    exit 1
fi
ok "backend trả lời ($BASE)"

api GET /api/lock/status || die "không đọc được /api/lock/status — $(why)"
IS_SETUP="$(jq -r '.isSetup' <<<"$RESP")"

# ── 2. Mã vẽ + mã khôi phục ──────────────────────────────────────────────────
hdr "[2/4] Mã vẽ & đăng nhập"
if [[ -z "$PATTERN" ]]; then
    ask_secret "Mã vẽ ($([[ "$IS_SETUP" == "true" ]] && echo 'để đăng nhập' || echo 'đặt mới, >=4 ký tự'))"
    PATTERN="$REPLY"
fi
[[ ${#PATTERN} -ge 4 ]] || die "mã vẽ quá ngắn (tối thiểu 4 ký tự)"
PAYLOAD="$(jq -nc --arg p "$PATTERN" '{pattern:$p}')"

if [[ "$IS_SETUP" == "true" ]]; then
    api POST /api/lock/verify "$PAYLOAD" || die "đăng nhập thất bại — $(why)"
    ok "máy đã thiết lập từ trước, đã đăng nhập"
    warn "mã khôi phục chỉ trả về đúng một lần lúc setup — nếu mất, sinh lại qua /api/lock/recovery/regenerate"
else
    # Ghi file TRƯỚC khi gọi setup: nếu thư mục không ghi được thì phải biết ngay,
    # chứ không phải sau khi mã đã sinh ra và không có cách đọc lại.
    mkdir -p "$(dirname "$RECOVERY_OUT")" || die "không tạo được thư mục cho $RECOVERY_OUT"
    [[ -e "$RECOVERY_OUT" ]] && die "$RECOVERY_OUT đã tồn tại — đổi tên nó đi rồi chạy lại (không ghi đè mã khôi phục cũ)"
    : > "$RECOVERY_OUT" || die "không ghi được $RECOVERY_OUT"
    chmod 600 "$RECOVERY_OUT"

    if ! api POST /api/lock/setup "$PAYLOAD"; then
        rm -f "$RECOVERY_OUT"
        die "đặt mã vẽ thất bại — $(why)"
    fi
    CODE="$(jq -r '.recoveryCode // empty' <<<"$RESP")"
    if [[ -z "$CODE" ]]; then
        rm -f "$RECOVERY_OUT"
        die "setup trả về 2xx nhưng không có recoveryCode — $(head -c 200 <<<"$RESP")"
    fi
    { echo "# Mã khôi phục NVR — sinh $(date '+%d-%m-%Y %H:%M:%S') trên $(hostname)"
      echo "# Dùng khi quên mã vẽ. Chỉ sinh được một lần; in lại là không có."
      echo "$CODE"; } > "$RECOVERY_OUT"
    chmod 600 "$RECOVERY_OUT"
    ok "đã đặt mã vẽ và đăng nhập"
    ok "mã khôi phục đã ghi: $RECOVERY_OUT (chmod 600)"
    printf '\033[1;33m  → MÃ KHÔI PHỤC: %s\033[0m\n' "$CODE"
    printf '\033[1;33m    Chép ra chỗ khác ngay — máy mất là hết đường khôi phục offline.\033[0m\n'
fi

# ── 3. Cloud ─────────────────────────────────────────────────────────────────
hdr "[3/4] Kết nối Cloud"
if [[ -z "$CLOUD_USER" || -z "$CLOUD_SERIAL" ]]; then
    ok "bỏ qua (chưa có username/serial — ${CLOUD_CONF:+điền vào $CLOUD_CONF hoặc }truyền --cloud-user/--serial)"
else
    if [[ $CLOUD_PASS_SET -eq 0 ]]; then
        ask_secret "Mật khẩu cloud cho '$CLOUD_USER' (Enter = giữ mật khẩu đã lưu)"
        CLOUD_PASS="$REPLY"
    fi
    # password rỗng = backend giữ nguyên mật khẩu cũ (CloudRouting.kt), đúng ý "chạy lại
    # để sửa domain mà không phải gõ lại mật khẩu".
    BODY="$(jq -nc \
        --arg d "$CLOUD_DOMAIN" --argjson p "$CLOUD_PORT" --arg u "$CLOUD_USER" \
        --arg w "$CLOUD_PASS" --arg s "$CLOUD_SERIAL" --argjson e "$CLOUD_ENABLED" \
        '{domain:$d,port:$p,username:$u,password:$w,serial:$s,enabled:$e}')"
    if api POST /api/cloud/config "$BODY"; then
        ok "đã lưu cấu hình cloud ($CLOUD_DOMAIN:$CLOUD_PORT serial=$CLOUD_SERIAL enabled=$CLOUD_ENABLED)"
        [[ -z "$CLOUD_PASS" ]] && warn "mật khẩu để trống → giữ nguyên mật khẩu đã lưu trước đó"
        [[ -s "$ROOT/data/dc-bridge" ]] || warn "thiếu data/dc-bridge → dashboard cloud sẽ online nhưng trống Device info và không playback được"

        # LƯU ĐƯỢC KHÔNG CÓ NGHĨA LÀ KẾT NỐI ĐƯỢC. /api/cloud/config chỉ ghi DB rồi gọi
        # reconnect ở luồng nền, nên connectionState trong phản hồi của chính lệnh POST luôn
        # là trạng thái CŨ (đo được: POST trả DISABLED trong khi đọc lại ngay sau đó đã là
        # CONNECTING). Đọc lại một lần để báo đúng, và nói rõ nó vẫn chưa phải là đã kết nối.
        if api GET /api/cloud/config; then
            ST="$(jq -r '.connectionState // "?"' <<<"$RESP")"
            case "$ST" in
                CONNECTED) ok "trạng thái kết nối: $ST" ;;
                *) warn "trạng thái kết nối: $ST — chưa phải là đã kết nối. Theo dõi tiếp bằng:"
                   echo "      tail -f logs/cloud.log   hoặc   curl -sb <cookie> $BASE/api/cloud/config | jq .connectionState" ;;
            esac
        fi
    else
        fail "lưu cấu hình cloud thất bại — $(why)"
    fi
fi

# ── 4. Camera ────────────────────────────────────────────────────────────────
# Mỗi lần thêm là một lượt ghi config.yml + restart Frigate (FrigateConfigGuard),
# nên chạy tuần tự và nới timeout. Con nào hỏng thì báo rồi đi tiếp — dừng cả loạt
# vì một camera sai mật khẩu là lãng phí công đã bỏ ra.
hdr "[4/4] Camera"
if [[ -z "$CAMERAS_FILE" ]]; then
    ok "bỏ qua (không có --cameras)"
else
    EXISTING=""
    api GET /api/cameras && EXISTING="$(jq -r '.[].name' <<<"$RESP" 2>/dev/null)"
    ADDED=0; SKIPPED=0
    for i in "${!CAM_NAME[@]}"; do
        n="${CAM_NAME[$i]}"
        if grep -qxF "$n" <<<"$EXISTING" 2>/dev/null; then
            ok "[$((i+1))/${#CAM_NAME[@]}] $n — đã có, bỏ qua"; SKIPPED=$((SKIPPED+1)); continue
        fi
        BODY="$(jq -nc \
            --arg n "$n" --arg r "${CAM_RTSP[$i]}" --arg s "${CAM_SUB[$i]}" \
            --arg p "${CAM_PORT[$i]}" --arg rec "${CAM_REC[$i]}" --arg ptz "${CAM_PTZ[$i]}" \
            '{name:$n,rtspUrl:$r,onvifPort:$p,record:$rec,ptzEnabled:$ptz}
             + (if $s == "" then {} else {subRtspUrl:$s} end)')"
        printf '  … [%d/%d] %s — đang thêm (ghi config + restart Frigate, có thể lâu)\n' \
            "$((i+1))" "${#CAM_NAME[@]}" "$n"
        T0=$SECONDS
        if api POST /api/add-camera "$BODY" "$RESTART_TIMEOUT"; then
            ok "[$((i+1))/${#CAM_NAME[@]}] $n — API nhận sau $((SECONDS-T0))s (frigateRestarted=$(jq -r '.frigateRestarted // "?"' <<<"$RESP"))"
            ADDED=$((ADDED+1))
            # /api/add-camera trả 200 NGAY SAU khi bắn POST /api/restart sang Frigate, KHÔNG
            # chờ Frigate lên lại và KHÔNG có đường lùi (applyAndPersistFromRtsp gọi
            # triggerFrigateRestart, không đi qua FrigateConfigGuard). Đo trên máy dev: API
            # trả sau 2s trong khi Frigate còn chết thêm ~28s nữa. Nên 200 ở đây KHÔNG có
            # nghĩa là hệ thống còn khoẻ — phải tự chờ và tự kiểm.
            # Và phải chờ TRƯỚC khi thêm con tiếp: bắn restart vào Frigate đang chết thì
            # lệnh đó rơi, con sau coi như không được áp.
            if [[ $CHECK_FRIGATE -eq 1 ]]; then
                printf '      chờ Frigate lên lại…'
                T1=$SECONDS
                if wait_frigate "$FRIGATE_WAIT"; then
                    printf ' lên sau %ss\n' "$((SECONDS-T1))"
                else
                    printf '\n'
                    fail "Frigate KHÔNG lên lại sau ${FRIGATE_WAIT}s — dừng, không thêm tiếp"
                    echo "      Xem ngay:  docker logs --tail 50 frigate"
                    echo "      Đường lùi: cp config/config.yml.last-good config/config.yml && docker restart frigate"
                    break
                fi
            fi
        else
            MSG="$(why)"
            case "$MSG" in
                *"đã tồn tại"*) ok "[$((i+1))/${#CAM_NAME[@]}] $n — đã có, bỏ qua"; SKIPPED=$((SKIPPED+1)) ;;
                *) fail "[$((i+1))/${#CAM_NAME[@]}] $n — $MSG" ;;
            esac
        fi
    done
    ok "camera: thêm mới $ADDED, bỏ qua $SKIPPED, lỗi $(( ${#CAM_NAME[@]} - ADDED - SKIPPED ))"

    # Bằng chứng cuối: Frigate có thật sự nạp từng con hay không.
    if [[ $CHECK_FRIGATE -eq 1 && $ADDED -gt 0 ]]; then
        LOADED="$(frigate_cameras)"
        if [[ -z "$LOADED" ]]; then
            fail "không đọc được danh sách camera của Frigate ($FRIGATE_URL/api/config) — chưa xác nhận được Frigate đã nạp"
        else
            MISSING=0
            for i in "${!CAM_NAME[@]}"; do
                # slug do backend sinh; so khớp theo IP + vài ký tự đầu của tên là đủ để
                # phát hiện con bị thiếu mà không phải dựng lại luật tạo slug của Kotlin.
                ip="$(sed -E 's|^[^/]*//||; s|/.*$||; s|.*@||; s|:.*$||' <<<"${CAM_RTSP[$i]}" | tr '.' '_')"
                grep -q "$ip" <<<"$LOADED" || { fail "Frigate chưa nạp camera '${CAM_NAME[$i]}' (không thấy slug nào chứa $ip)"; MISSING=1; }
            done
            [[ $MISSING -eq 0 ]] && ok "Frigate đã nạp $(wc -l <<<"$LOADED") camera: $(tr '\n' ' ' <<<"$LOADED")"
        fi
    fi
fi

# ── Tổng kết ─────────────────────────────────────────────────────────────────
echo ""
echo "════════════════════════════════════════════"
if [[ $FAILS -eq 0 ]]; then
    printf '\033[1;32m  Xong — %d cảnh báo, 0 lỗi\033[0m\n' "$WARNS"
else
    printf '\033[1;31m  Xong nhưng có %d lỗi (%d cảnh báo)\033[0m\n' "$FAILS" "$WARNS"
fi
echo "  Kiểm lại:  sudo $HERE/nvr-check.sh sau"
echo "  Xem camera: curl -sb <cookie> $BASE/api/cameras | jq"
exit $(( FAILS > 0 ? 1 : 0 ))
