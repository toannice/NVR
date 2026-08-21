#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# package.sh — đóng gói toàn bộ stack NVR thành 1 file bundle để triển khai offline.
#
# Chạy trên máy DEV (nơi đã build xong image). Output: nvr-bundle-YYYYMMDD.tar.gz
#
# Bao gồm:
#   - Docker static binary (dockerd + docker CLI + compose plugin) — cài Docker không cần internet
#   - Tất cả Docker images (frigate, nvr-wrapper, mosquitto, nginx, nvr-watchdog)
#   - Config files (docker-compose.yml, nginx, mosquitto, frigate config, watchdog.yml)
#   - Script triển khai (deploy-offline.sh)
#
# Usage:
#   ./package.sh              # bundle ngày hôm nay
#   ./package.sh --no-frigate # bỏ frigate (2.94GB) nếu máy đích đã có sẵn
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

INCLUDE_FRIGATE=1
[[ "${1:-}" == "--no-frigate" ]] && INCLUDE_FRIGATE=0

DATE="$(date +%Y%m%d)"
BUNDLE_NAME="nvr-bundle-${DATE}"
BUNDLE_DIR="/tmp/${BUNDLE_NAME}"
OUTPUT="${HERE}/${BUNDLE_NAME}.tar.gz"

# Phiên bản Docker static binary để bundle (linux/amd64)
# Xem: https://download.docker.com/linux/static/stable/x86_64/
DOCKER_STATIC_VER="28.1.1"
DOCKER_STATIC_URL="https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_STATIC_VER}.tgz"

# Docker Compose plugin standalone binary
COMPOSE_VER="v2.35.1"
COMPOSE_URL="https://github.com/docker/compose/releases/download/${COMPOSE_VER}/docker-compose-linux-x86_64"

c()    { printf '\033[1;36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }
step() { printf '\033[1;35m[%s] %s\033[0m\n' "$1" "$2"; }

# ── 1. Kiểm tra prerequisites ────────────────────────────────────────────────
step "1/6" "Kiểm tra prerequisites"
command -v docker >/dev/null || die "docker không tìm thấy"
command -v tar    >/dev/null || die "tar không tìm thấy"
command -v curl   >/dev/null || die "curl không tìm thấy"

# ── 2. Download Docker static binaries ───────────────────────────────────────
step "2/6" "Download Docker static binaries (dockerd + docker CLI + compose plugin)"

DOCKER_CACHE_DIR="${HERE}/.docker-cache"
mkdir -p "$DOCKER_CACHE_DIR"

DOCKER_TGZ="$DOCKER_CACHE_DIR/docker-${DOCKER_STATIC_VER}.tgz"
COMPOSE_BIN="$DOCKER_CACHE_DIR/docker-compose-${COMPOSE_VER}"

if [[ -f "$DOCKER_TGZ" ]]; then
    ok "docker-${DOCKER_STATIC_VER}.tgz đã có trong cache"
else
    echo "  Đang tải docker-${DOCKER_STATIC_VER}.tgz (~70MB)..."
    curl -fSL --progress-bar "$DOCKER_STATIC_URL" -o "$DOCKER_TGZ" \
        || die "Download Docker binary thất bại. Kiểm tra URL: $DOCKER_STATIC_URL"
    ok "docker-${DOCKER_STATIC_VER}.tgz đã tải ($(du -sh "$DOCKER_TGZ" | cut -f1))"
fi

if [[ -f "$COMPOSE_BIN" ]]; then
    ok "docker-compose ${COMPOSE_VER} đã có trong cache"
else
    echo "  Đang tải docker-compose ${COMPOSE_VER} (~60MB)..."
    curl -fSL --progress-bar "$COMPOSE_URL" -o "$COMPOSE_BIN" \
        || die "Download Docker Compose thất bại. Kiểm tra URL: $COMPOSE_URL"
    chmod +x "$COMPOSE_BIN"
    ok "docker-compose ${COMPOSE_VER} đã tải ($(du -sh "$COMPOSE_BIN" | cut -f1))"
fi

# ── 3. Build / kiểm tra images ───────────────────────────────────────────────
step "3/6" "Kiểm tra và build images"

# nvr-watchdog: build trước nếu chưa có
if ! docker image inspect nvr-watchdog:latest >/dev/null 2>&1; then
    warn "nvr-watchdog:latest chưa có — đang build..."
    docker compose build nvr-watchdog
fi
ok "nvr-watchdog:latest OK"

# nvr-wrapper: phải là image BUILD TỪ SOURCE trên máy này. Không còn nhánh "pull từ GHCR":
# bundle là đường giao hàng duy nhất, và image mang tên registry chỉ gây nhầm lẫn trên máy khách.
WRAPPER_IMAGE="nvr-wrapper:latest"
docker image inspect "$WRAPPER_IMAGE" >/dev/null 2>&1 \
    || die "Không tìm thấy $WRAPPER_IMAGE. Build trước: docker compose build nvr-wrapper"
ok "$WRAPPER_IMAGE (local build) OK"

# Lấy FRIGATE_VERSION từ .env nếu có, fallback 0.16.3
FRIGATE_VER="0.16.3"
if [[ -f .env ]]; then
    _v="$(grep -E '^FRIGATE_VERSION=' .env 2>/dev/null | cut -d= -f2 | tr -d '"' || true)"
    [[ -n "$_v" ]] && FRIGATE_VER="$_v"
fi
FRIGATE_IMAGE="ghcr.io/blakeblackshear/frigate:${FRIGATE_VER}"

if [[ $INCLUDE_FRIGATE -eq 1 ]]; then
    if ! docker image inspect "${FRIGATE_IMAGE}" >/dev/null 2>&1; then
        warn "Chưa có ${FRIGATE_IMAGE} — đang pull (2.94GB)..."
        docker pull "${FRIGATE_IMAGE}"
    fi
    ok "${FRIGATE_IMAGE} OK"
fi

for img in "eclipse-mosquitto:2" "nginx:stable-alpine"; do
    if ! docker image inspect "$img" >/dev/null 2>&1; then
        warn "$img chưa có — đang pull..."
        docker pull "$img"
    fi
    ok "$img OK"
done

# ── 4. Chuẩn bị thư mục bundle ───────────────────────────────────────────────
step "4/6" "Chuẩn bị thư mục bundle"
rm -rf "$BUNDLE_DIR"
mkdir -p "$BUNDLE_DIR"/{config,nginx,mosquitto/config,secrets,data,docker-install,scripts}

# Sao chép config files
cp docker-compose.yml               "$BUNDLE_DIR/"
cp docker-compose.offline.yml       "$BUNDLE_DIR/" 2>/dev/null || true  # sẽ tạo bên dưới
cp .env.template                    "$BUNDLE_DIR/"
cp deploy-offline.sh                "$BUNDLE_DIR/" 2>/dev/null || true  # sẽ tạo bên dưới
# IP guard: giữ FRIGATE_HOST_IP khớp IP thật, nếu không live view đen khi máy đổi IP
cp scripts/nvr-ip-guard.sh          "$BUNDLE_DIR/scripts/" 2>/dev/null || warn "nvr-ip-guard.sh không tìm thấy"
cp nginx/nginx.conf                 "$BUNDLE_DIR/nginx/" 2>/dev/null || warn "nginx/nginx.conf không tìm thấy"
# Frigate config: LUÔN dùng template sạch, KHÔNG bao giờ config.yml của máy dev — file đó
# chứa IP + mật khẩu camera văn phòng, TURN credential và IP overlay Nebula.
cp config/config.yml.template       "$BUNDLE_DIR/config/config.yml" \
    || die "config/config.yml.template không tìm thấy — không đóng gói để tránh lộ config máy dev"
cp config/watchdog.yml              "$BUNDLE_DIR/config/" 2>/dev/null || warn "config/watchdog.yml không tìm thấy"
cp config/.vacuum                   "$BUNDLE_DIR/config/" 2>/dev/null || true
cp mosquitto/config/mosquitto.conf  "$BUNDLE_DIR/mosquitto/config/" 2>/dev/null || warn "mosquitto config không tìm thấy"
# ACL là file tĩnh (chỉ chứa username+topic, không có secret) — mosquitto.conf tham chiếu nó nên
# PHẢI có trong bundle. File passwd thì KHÔNG copy (chứa hash mật khẩu máy dev) — deploy-offline.sh
# tự sinh trên máy đích.
cp mosquitto/config/acl             "$BUNDLE_DIR/mosquitto/config/" 2>/dev/null || warn "mosquitto acl không tìm thấy (MQTT auth sẽ fail trên máy đích)"

# Tạo placeholder secrets (rỗng — người dùng điền sau)
for s in edge_agent_token zt_controller_token nebula_sign_token; do
    touch "$BUNDLE_DIR/secrets/$s"
done

# Sao chép dc-bridge binary nếu có
if [[ -f data/dc-bridge ]]; then
    cp data/dc-bridge "$BUNDLE_DIR/data/"
    ok "dc-bridge binary included"
fi

# Sao chép Docker static binaries vào bundle
cp "$DOCKER_TGZ"  "$BUNDLE_DIR/docker-install/docker.tgz"
cp "$COMPOSE_BIN" "$BUNDLE_DIR/docker-install/docker-compose"
cat > "$BUNDLE_DIR/docker-install/version.env" <<EOF
DOCKER_STATIC_VER=${DOCKER_STATIC_VER}
COMPOSE_VER=${COMPOSE_VER}
EOF
ok "Docker binaries copied"
ok "Project files copied"

# ── 5. Export Docker images ───────────────────────────────────────────────────
step "5/6" "Export Docker images → images.tar (có thể mất vài phút)"

IMAGES=("$WRAPPER_IMAGE" "eclipse-mosquitto:2" "nginx:stable-alpine" "nvr-watchdog:latest")
[[ $INCLUDE_FRIGATE -eq 1 ]] && IMAGES+=("${FRIGATE_IMAGE}")

echo "  Images sẽ export:"
for img in "${IMAGES[@]}"; do printf '    - %s\n' "$img"; done
echo ""

docker save "${IMAGES[@]}" -o "$BUNDLE_DIR/images.tar"
ok "images.tar saved ($(du -sh "$BUNDLE_DIR/images.tar" | cut -f1))"

# Ghi metadata để deploy-offline.sh biết dùng image nào
cat > "$BUNDLE_DIR/bundle-info.env" <<EOF
BUNDLE_DATE=${DATE}
WRAPPER_IMAGE=${WRAPPER_IMAGE}
FRIGATE_IMAGE=${FRIGATE_IMAGE}
FRIGATE_VER=${FRIGATE_VER}
INCLUDE_FRIGATE=${INCLUDE_FRIGATE}
EOF

# ── 6. Nén thành tar.gz ───────────────────────────────────────────────────────
step "6/6" "Nén bundle → ${OUTPUT}"
tar -czf "$OUTPUT" -C "/tmp" "$BUNDLE_NAME"
rm -rf "$BUNDLE_DIR"

SIZE="$(du -sh "$OUTPUT" | cut -f1)"
ok "Bundle created: ${BUNDLE_NAME}.tar.gz (${SIZE})"
echo ""
echo "  Gửi sang máy đích:"
echo "    scp ${BUNDLE_NAME}.tar.gz user@TARGET_IP:~/"
echo ""
echo "  Chạy trên máy đích:"
echo "    tar -xzf ${BUNDLE_NAME}.tar.gz"
echo "    cd ${BUNDLE_NAME}"
echo "    sudo ./deploy-offline.sh"
