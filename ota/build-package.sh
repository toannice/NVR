#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# build-package.sh — dựng thư mục gói OTA (chưa ký) từ cây source hiện tại.
#
# Chạy trên máy DEV, sau khi đã build image. Output là một THƯ MỤC, chưa phải file .nvrup:
# phải đi ký manifest.json trước rồi finalize-package.sh mới nén lại. Làm vậy để không phải
# sửa file tar tại chỗ (vừa khó vừa dễ sai) sau khi có chữ ký.
#
#   releaseVersion/ota/<version>/
#       manifest.json          ← copy file NÀY sang USB đưa vào máy air-gap
#       images/*.tar.zst
#       files/...
#
# Nén bằng zstd -19: đo thật trên nvr-wrapper 434MB → gzip 173MB, zstd-19 134MB (nhỏ hơn
# 23%, tức ~39MB mỗi bản trên đường truyền field). `docker load` tự nhận zstd (daemon trên
# host giải nén, không phải CLI trong container) nên không cần thêm binary vào image nào.
#
# Chạy:
#   ./ota/build-package.sh 1.1.0.0
#   ./ota/build-package.sh 1.1.0.0 --min-from 1.0.0.0 --expires-days 120
#   ./ota/build-package.sh 1.1.0.0 --with-watchdog
#   ./ota/build-package.sh 2.0.0.0 --frigate 0.17.0 --risk high --auto-apply
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$HERE"

c()    { printf '\033[1;36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }
step() { printf '\n\033[1;35m━━━ [%s] %s ━━━\033[0m\n' "$1" "$2"; }

# ── Tham số ──────────────────────────────────────────────────────────────────
VERSION=""
MIN_FROM=""
MIN_APPLIER="1.0.0"
EXPIRES_DAYS=180
RISK="normal"
AUTO_APPLY="false"
WITH_WATCHDOG=0
FRIGATE_VER=""
NOTES=""
DESCRIPTION=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --min-from)     MIN_FROM="$2"; shift 2 ;;
        --min-applier)  MIN_APPLIER="$2"; shift 2 ;;
        --expires-days) EXPIRES_DAYS="$2"; shift 2 ;;
        --risk)         RISK="$2"; shift 2 ;;
        --auto-apply)   AUTO_APPLY="true"; shift ;;
        --with-watchdog) WITH_WATCHDOG=1; shift ;;
        --frigate)      FRIGATE_VER="$2"; shift 2 ;;
        --notes)        NOTES="$2"; shift 2 ;;
        --description)  DESCRIPTION="$2"; shift 2 ;;
        -h|--help)      sed -n '2,24p' "$0"; exit 0 ;;
        -*)             die "tham số lạ: $1" ;;
        *)              VERSION="$1"; shift ;;
    esac
done

[[ -n "$VERSION" ]] || VERSION="$(cat VERSION 2>/dev/null | tr -d '[:space:]')"
[[ -n "$VERSION" ]] || die "thiếu version. Dùng: $0 <version>  (hoặc điền file VERSION)"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]] \
    || die "version '$VERSION' phải là các số cách nhau bằng dấu chấm (vd 1.1.0.0)"
[[ "$RISK" == "normal" || "$RISK" == "high" ]] || die "--risk chỉ nhận normal|high"

command -v zstd   >/dev/null || die "thiếu zstd (apt install zstd)"
command -v docker >/dev/null || die "thiếu docker"
command -v jq     >/dev/null || die "thiếu jq (apt install jq)"

# Model: nguồn duy nhất là DEVICE_MODEL trong .env. Manifest URL scope theo model, và máy
# field từ chối gói khai model khác — sai chỗ này là gói bị từ chối ở tận hiện trường.
DEVICE_MODEL="$(grep -E '^DEVICE_MODEL=' .env 2>/dev/null | cut -d= -f2- | tr -d '"'"'"'[:space:]' || true)"
[[ -n "$DEVICE_MODEL" ]] || die "thiếu DEVICE_MODEL trong .env — không đoán được model, gói sẽ bị máy field từ chối"

OTA_BASE_URL="$(grep -E '^OTA_BASE_URL=' .env 2>/dev/null | cut -d= -f2- | tr -d '"'"'"'[:space:]' || true)"
OTA_BASE_URL="${OTA_BASE_URL:-https://aiview.bkav.com/download}"

OUT="releaseVersion/ota/$VERSION"
PKG_NAME="nvr-${DEVICE_MODEL}-${VERSION}.nvrup"

c "════════════════════════════════════════════════════════════"
c " Dựng gói OTA  $DEVICE_MODEL  $VERSION"
c "════════════════════════════════════════════════════════════"
echo "  minFrom      : ${MIN_FROM:-(không giới hạn)}"
echo "  minApplier   : $MIN_APPLIER"
echo "  hạn manifest : $EXPIRES_DAYS ngày"
echo "  risk         : $RISK   autoApply: $AUTO_APPLY"
echo "  ra           : $OUT/"

# ── 1. Kiểm image đã build ───────────────────────────────────────────────────
step "1/5" "Kiểm image"

docker image inspect nvr-wrapper:latest >/dev/null 2>&1 \
    || die "chưa có nvr-wrapper:latest. Build trước: docker compose build nvr-wrapper"

# Đối chiếu VERSION nướng trong image với version đang đóng gói. Sai chỗ này thì health-check
# expectJson trên máy field sẽ thất bại và gói tự rollback — mất một đêm của cả fleet vì một
# lần quên bump VERSION.
BAKED="$(docker run --rm --entrypoint sh nvr-wrapper:latest -c 'cat /app/VERSION 2>/dev/null || cat VERSION 2>/dev/null' 2>/dev/null | tr -d '[:space:]' || true)"
if [[ -z "$BAKED" ]]; then
    warn "không đọc được VERSION trong image — bỏ qua đối chiếu"
elif [[ "$BAKED" != "$VERSION" ]]; then
    die "image nvr-wrapper:latest nướng VERSION='$BAKED' nhưng đang đóng gói '$VERSION'.
      Sửa file VERSION rồi build lại:  echo $VERSION > VERSION && docker compose build nvr-wrapper
      (không sửa thì máy field sẽ health-check thất bại vì /api/version báo sai và tự rollback)"
else
    ok "nvr-wrapper:latest (VERSION nướng trong image = $VERSION)"
fi

if [[ $WITH_WATCHDOG -eq 1 ]]; then
    docker image inspect nvr-watchdog:latest >/dev/null 2>&1 \
        || die "chưa có nvr-watchdog:latest. Build trước: docker compose build nvr-watchdog"
    ok "nvr-watchdog:latest"
fi

# ── 2. Thư mục gói ───────────────────────────────────────────────────────────
step "2/5" "Chuẩn bị $OUT"
rm -rf "$OUT"
mkdir -p "$OUT"/{images,files,hooks}
ok "đã tạo"

# ── 3. Export + nén image ────────────────────────────────────────────────────
step "3/5" "Export image (zstd -19, có thể mất vài phút)"

IMAGES_JSON="[]"

save_image() { # save_image <docker-image> <tên-file> <tên-logic>
    local img="$1" fname="$2" logical="$3"
    local dest="$OUT/images/$fname"
    echo "  $img → images/$fname"
    docker save "$img" | zstd -19 -T0 -q -o "$dest"
    local sha size
    sha="$(sha256sum "$dest" | cut -d' ' -f1)"
    size="$(stat -c%s "$dest")"
    ok "$fname  $((size / 1048576))MB  sha256 ${sha:0:16}…"
    IMAGES_JSON="$(jq --arg n "$logical" --arg p "images/$fname" --arg s "$sha" --arg t "$img" \
        '. + [{name:$n, source:"tarball", path:$p, sha256:$s, tag:$t}]' <<<"$IMAGES_JSON")"
}

save_image "nvr-wrapper:latest" "nvr-wrapper.tar.zst" "nvr-wrapper"
[[ $WITH_WATCHDOG -eq 1 ]] && save_image "nvr-watchdog:latest" "nvr-watchdog.tar.zst" "nvr-watchdog"

# Frigate đi đường registry theo DIGEST, không đóng vào gói.
# Vì sao: image công khai trên ghcr.io ⇒ pull anonymous, KHÔNG cần token nào (đã kiểm: token
# anonymous lấy tự động, HTTP 200). Và pull là TĂNG DẦN THEO LAYER — nâng 0.16.3→0.17 chỉ tải
# layer thay đổi, có thể 300-400MB thay vì 1.2GB nếu đóng cả tarball. Digest tự bảo đảm toàn
# vẹn: Docker từ chối layer sai hash.
if [[ -n "$FRIGATE_VER" ]]; then
    FRIGATE_TAG="ghcr.io/blakeblackshear/frigate:${FRIGATE_VER}"
    echo "  Đang lấy digest của $FRIGATE_TAG…"
    FRIGATE_DIGEST="$(docker buildx imagetools inspect "$FRIGATE_TAG" --format '{{.Manifest.Digest}}' 2>/dev/null || true)"
    if [[ -z "$FRIGATE_DIGEST" ]]; then
        docker pull -q "$FRIGATE_TAG" >/dev/null \
            || die "không pull được $FRIGATE_TAG để lấy digest"
        FRIGATE_DIGEST="$(docker image inspect "$FRIGATE_TAG" \
            --format '{{index .RepoDigests 0}}' | sed 's/.*@//')"
    fi
    [[ "$FRIGATE_DIGEST" == sha256:* ]] || die "digest Frigate không hợp lệ: '$FRIGATE_DIGEST'"
    ok "frigate ghim digest ${FRIGATE_DIGEST:0:23}…"
    IMAGES_JSON="$(jq --arg r "ghcr.io/blakeblackshear/frigate@${FRIGATE_DIGEST}" --arg t "$FRIGATE_TAG" \
        '. + [{name:"frigate", source:"registry", ref:$r, tag:$t}]' <<<"$IMAGES_JSON")"
    [[ "$RISK" == "high" ]] || warn "gói có Frigate nên đặt --risk high (config.yml sai một dòng = crash-loop)"
fi

# ── 4. File overlay ──────────────────────────────────────────────────────────
step "4/5" "File overlay"

FILES_JSON="[]"

add_file() { # add_file <đường-dẫn-trong-repo> [mode]
    local rel="$1" mode="${2:-644}"
    [[ -f "$rel" ]] || { warn "bỏ qua $rel (không có)"; return 0; }
    mkdir -p "$OUT/files/$(dirname "$rel")"
    cp "$rel" "$OUT/files/$rel"
    local sha; sha="$(sha256sum "$rel" | cut -d' ' -f1)"
    FILES_JSON="$(jq --arg p "$rel" --arg s "$sha" --arg m "$mode" \
        '. + [{path:$p, sha256:$s, mode:$m}]' <<<"$FILES_JSON")"
    ok "$rel"
}

# CHỈ những đường dẫn nằm trong whitelist của OtaLayout.ALLOWED_OVERLAY_PREFIXES.
# Cố tình KHÔNG có: .env (mật khẩu MQTT/Telegram của máy), secrets/, media/, logs/, data/*.
add_file "docker-compose.yml"
add_file "nginx/nginx.conf"
add_file "config/watchdog.yml"
add_file "VERSION"
add_file "scripts/ota-apply.sh" 755
add_file "scripts/nvr-ip-guard.sh" 755
[[ -f data/dc-bridge ]] && add_file "data/dc-bridge" 755

# ── 5. manifest.json ─────────────────────────────────────────────────────────
step "5/5" "Sinh manifest.json (CHƯA ký)"

EXPIRES_AT="$(date -u -d "+${EXPIRES_DAYS} days" +%Y-%m-%dT%H:%M:%SZ)"
RELEASE_DATE="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
PACKAGE_URL="${OTA_BASE_URL}/${DEVICE_MODEL}/OTA/${PKG_NAME}"

# requiresFreeBytes = 3x tổng payload: cần chỗ cho gói + image đã giải nén + bản cũ giữ lại
# để rollback. Thiếu chỗ giữa lúc docker load là kịch bản tệ nhất — chết đúng khoảnh khắc
# image cũ đã bị ghi đè.
PAYLOAD_BYTES="$(du -sb "$OUT" | cut -f1)"
REQUIRES_FREE=$(( PAYLOAD_BYTES * 3 + 1073741824 ))

# healthChecks: /api/health MỘT MÌNH LÀ KHÔNG ĐỦ — nó trả 200 kể cả khi container cũ vẫn
# đang chạy (compose up thất bại lặng lẽ, hoặc container mới crash rồi restart về bản cũ).
# Phải đối chiếu /api/version báo ĐÚNG version mới mới tính là thành công.
HEALTH_JSON="$(jq -n --arg v "$VERSION" '[
    {url:"http://127.0.0.1:3000/api/health",  expectStatus:200, timeoutSec:180},
    {url:"http://127.0.0.1:3000/api/version", expectStatus:200, expectJson:{version:$v}, timeoutSec:180}
]')"
if [[ -n "$FRIGATE_VER" ]]; then
    HEALTH_JSON="$(jq '. + [{url:"http://127.0.0.1:5000/api/version", expectStatus:200, timeoutSec:300}]' \
        <<<"$HEALTH_JSON")"
fi

HOOKS_JSON='{}'
[[ -f "$OUT/hooks/pre-apply.sh"  ]] && HOOKS_JSON="$(jq '.preApply="hooks/pre-apply.sh"'   <<<"$HOOKS_JSON")"
[[ -f "$OUT/hooks/post-apply.sh" ]] && HOOKS_JSON="$(jq '.postApply="hooks/post-apply.sh"' <<<"$HOOKS_JSON")"
rmdir "$OUT/hooks" 2>/dev/null || true

jq -n \
  --argjson schema 1 \
  --arg model "$DEVICE_MODEL" \
  --arg version "$VERSION" \
  --arg releaseDate "$RELEASE_DATE" \
  --arg expiresAt "$EXPIRES_AT" \
  --arg description "${DESCRIPTION:-Ban cap nhat NVR $VERSION}" \
  --arg notes "$NOTES" \
  --arg minFrom "$MIN_FROM" \
  --arg minApplier "$MIN_APPLIER" \
  --argjson requiresFreeBytes "$REQUIRES_FREE" \
  --arg risk "$RISK" \
  --argjson autoApply "$AUTO_APPLY" \
  --arg packageUrl "$PACKAGE_URL" \
  --argjson images "$IMAGES_JSON" \
  --argjson files "$FILES_JSON" \
  --argjson hooks "$HOOKS_JSON" \
  --argjson healthChecks "$HEALTH_JSON" \
  '{schema:$schema, model:$model, version:$version, channel:"stable",
    releaseDate:$releaseDate, expiresAt:$expiresAt,
    description:$description, notes:$notes,
    minFrom:$minFrom, minApplier:$minApplier,
    requiresFreeBytes:$requiresFreeBytes, risk:$risk, autoApply:$autoApply,
    package:{url:$packageUrl},
    images:$images, files:$files, hooks:$hooks, healthChecks:$healthChecks}' \
  > "$OUT/manifest.json"

ok "manifest.json ($(stat -c%s "$OUT/manifest.json") byte)"

TOTAL_MB=$(( $(du -sb "$OUT" | cut -f1) / 1048576 ))

cat <<EOF

$(c "══ Gói đã dựng (CHƯA KÝ) ══")

  $OUT/   —  ${TOTAL_MB}MB
$(cd "$OUT" && find . -type f | sed 's|^\./|    |' | sort)

$(c "══ Bước tiếp: ký manifest ══")

  1. Copy DUY NHẤT file manifest.json sang USB:
       cp $OUT/manifest.json /media/usb/

  2. Trên MÁY AIR-GAP:
       bash sign-manifest.sh /media/usb/manifest.json
     (đọc lại model/version/minFrom trên màn hình rồi mới xác nhận)

  3. Mang manifest.json.sig về, đặt cạnh manifest.json, rồi:
       ./ota/finalize-package.sh $OUT

  $(warn "Image ${TOTAL_MB}MB KHÔNG đi qua máy air-gap — chỉ manifest.json ($(stat -c%s "$OUT/manifest.json") byte).")
EOF
