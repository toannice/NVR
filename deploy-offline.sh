#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# deploy-offline.sh — triển khai NVR stack trên máy mới.
#
# Hỗ trợ 2 chế độ:
#   [offline] Không cần internet — Docker + images cài từ bundle, OTA tắt.
#             Update bằng cách chạy lại với bundle mới.
#   [ota]     Có internet + GHCR token — Docker cài từ bundle, nvr-wrapper
#             và watchdog kéo image từ GHCR. Máy tự cập nhật khi có bản mới.
#
# Chạy từ thư mục đã giải nén bundle:
#   tar -xzf nvr-bundle-YYYYMMDD.tar.gz
#   cd nvr-bundle-YYYYMMDD
#   sudo ./deploy-offline.sh
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_DIR="${NVR_INSTALL_DIR:-/opt/nvr}"

c()         { printf '\033[1;36m%s\033[0m\n' "$*"; }
ok()        { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn()      { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()       { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }
step()      { printf '\n\033[1;35m━━━ [%s] %s ━━━\033[0m\n' "$1" "$2"; }
ask()       { printf '\033[1;33m  → %s: \033[0m' "$1"; read -r REPLY; }
ask_secret(){ printf '\033[1;33m  → %s: \033[0m' "$1"; read -rs REPLY; echo ""; }
ask_yn()    { printf '\033[1;33m  → %s [y/N]: \033[0m' "$1"; read -r REPLY; [[ "${REPLY,,}" == "y" ]]; }

[[ $EUID -eq 0 ]] || die "Chạy với sudo: sudo ./deploy-offline.sh"
[[ -f "$HERE/images.tar" ]] || die "Không tìm thấy images.tar — hãy chạy từ thư mục bundle đã giải nén"
[[ -f "$HERE/bundle-info.env" ]] && source "$HERE/bundle-info.env"

WRAPPER_IMAGE="${WRAPPER_IMAGE:-nvr-wrapper:latest}"
FRIGATE_VER="${FRIGATE_VER:-0.16.3}"
DEPLOY_MODE="offline"   # sẽ đổi thành "ota" nếu user chọn

# ── 1. Docker ────────────────────────────────────────────────────────────────
step "1/7" "Docker engine"
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
    ok "docker $(docker --version | awk '{print $3}' | tr -d ,) + compose đã có"
else
    DOCKER_INSTALL_DIR="$HERE/docker-install"
    [[ -f "$DOCKER_INSTALL_DIR/docker.tgz" ]] || die "Không tìm thấy docker-install/docker.tgz trong bundle"
    [[ -f "$DOCKER_INSTALL_DIR/version.env" ]] && source "$DOCKER_INSTALL_DIR/version.env"

    if ! command -v docker >/dev/null 2>&1; then
        echo "  Cài Docker ${DOCKER_STATIC_VER:-?} từ bundle (không cần internet)..."
        tar -xzf "$DOCKER_INSTALL_DIR/docker.tgz" -C /usr/local/bin --strip-components=1
        ok "docker CLI + dockerd extracted"

        cat > /etc/systemd/system/docker.service <<'SVCEOF'
[Unit]
Description=Docker Application Container Engine (offline install)
After=network-online.target firewalld.service containerd.service
Wants=network-online.target

[Service]
Type=notify
ExecStart=/usr/local/bin/dockerd
ExecReload=/bin/kill -s HUP $MAINPID
TimeoutSec=0
RestartSec=2
Restart=always
StartLimitBurst=3
StartLimitInterval=60s
LimitNOFILE=infinity
LimitNPROC=infinity
LimitCORE=infinity
TasksMax=infinity
Delegate=yes
KillMode=process
OOMScoreAdjust=-500

[Install]
WantedBy=multi-user.target
SVCEOF
        systemctl daemon-reload
        systemctl enable --now docker
        for i in $(seq 1 15); do docker info >/dev/null 2>&1 && break; sleep 1; done
        ok "dockerd started"
    else
        ok "docker đã có — chỉ cài compose plugin"
    fi

    if [[ -f "$DOCKER_INSTALL_DIR/docker-compose" ]]; then
        mkdir -p /usr/local/lib/docker/cli-plugins
        cp "$DOCKER_INSTALL_DIR/docker-compose" /usr/local/lib/docker/cli-plugins/docker-compose
        chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
        ok "docker compose ${COMPOSE_VER:-?} plugin installed"
    fi

    docker --version && docker compose version || die "Docker vẫn chưa hoạt động sau khi cài"
fi

# ── 2. Load images ───────────────────────────────────────────────────────────
step "2/7" "Load Docker images từ images.tar"
echo "  ($(du -sh "$HERE/images.tar" | cut -f1) — có thể mất 2–5 phút...)"
docker load -i "$HERE/images.tar"
ok "Tất cả images đã load"
echo "  Images đã load:"
docker images --format "    {{.Repository}}:{{.Tag}}  ({{.Size}})" \
    | grep -E "nvr|frigate|mosquitto|nginx|watchdog" || true

# ── 3. Cài đặt thư mục ───────────────────────────────────────────────────────
step "3/7" "Chuẩn bị thư mục ${INSTALL_DIR}"
mkdir -p "$INSTALL_DIR"/{config,nginx,mosquitto/config,data,secrets,media/recordings,logs}
chmod 755 "$INSTALL_DIR"

cp "$HERE/docker-compose.yml"         "$INSTALL_DIR/"
cp "$HERE/docker-compose.offline.yml" "$INSTALL_DIR/"
cp "$HERE/.env.template"              "$INSTALL_DIR/"
[[ -f "$HERE/nginx/nginx.conf" ]]     && cp "$HERE/nginx/nginx.conf"     "$INSTALL_DIR/nginx/"
[[ -f "$HERE/config/config.yml" ]]    && cp "$HERE/config/config.yml"    "$INSTALL_DIR/config/"
[[ -f "$HERE/config/watchdog.yml" ]]  && cp "$HERE/config/watchdog.yml"  "$INSTALL_DIR/config/"
[[ -f "$HERE/mosquitto/config/mosquitto.conf" ]] \
    && cp "$HERE/mosquitto/config/mosquitto.conf" "$INSTALL_DIR/mosquitto/config/"
if [[ -f "$HERE/data/dc-bridge" ]]; then
    cp "$HERE/data/dc-bridge" "$INSTALL_DIR/data/"
    chmod +x "$INSTALL_DIR/data/dc-bridge"
    ok "dc-bridge copied"
fi
ok "Project files copied to ${INSTALL_DIR}"

# ── 4. .env ──────────────────────────────────────────────────────────────────
step "4/7" ".env configuration"
ENV_FILE="$INSTALL_DIR/.env"

if [[ -f "$ENV_FILE" ]]; then
    warn ".env đã tồn tại — giữ nguyên (xóa $ENV_FILE nếu muốn reset)"
else
    cp "$INSTALL_DIR/.env.template" "$ENV_FILE"

    HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
    [[ -n "$HOST_IP" ]] && sed -i "s|^FRIGATE_HOST_IP=.*|FRIGATE_HOST_IP=${HOST_IP}|" "$ENV_FILE"
    sed -i "s|^WRAPPER_TAG=.*|WRAPPER_TAG=latest|" "$ENV_FILE"
    sed -i "s|^FRIGATE_VERSION=.*|FRIGATE_VERSION=${FRIGATE_VER}|" "$ENV_FILE"
    ok ".env created (FRIGATE_HOST_IP=${HOST_IP:-?})"

    echo ""
    ask "GMAIL_USER (địa chỉ Gmail gửi OTP, Enter để bỏ qua)"
    [[ -n "$REPLY" ]] && sed -i "s|^GMAIL_USER=.*|GMAIL_USER=${REPLY}|" "$ENV_FILE"

    ask "HOSTNAME máy này (vd: nvr-office-1, Enter = nvr-edge-node)"
    HOSTNAME_VAL="${REPLY:-nvr-edge-node}"
    grep -q "^HOSTNAME=" "$ENV_FILE" \
        && sed -i "s|^HOSTNAME=.*|HOSTNAME=${HOSTNAME_VAL}|" "$ENV_FILE" \
        || echo "HOSTNAME=${HOSTNAME_VAL}" >> "$ENV_FILE"

    ok ".env configured"
fi

# ── 5. Chế độ cập nhật (OTA) ─────────────────────────────────────────────────
step "5/7" "Chế độ cập nhật"

# Đọc chế độ đã chọn trước (re-deploy)
if grep -q "^DEPLOY_MODE=ota" "$ENV_FILE" 2>/dev/null; then
    DEPLOY_MODE="ota"
    warn "Phát hiện cấu hình OTA từ lần deploy trước — giữ nguyên"
else
    echo ""
    c "  Máy này có kết nối internet ổn định (để nhận cập nhật tự động)?"
    echo "  • [Y] OTA — watchdog tự pull image mới từ GHCR khi dev đẩy bản mới"
    echo "  • [N] Offline — update thủ công bằng cách deploy bundle mới"
    echo ""
    if ask_yn "Bật OTA tự động"; then
        DEPLOY_MODE="ota"
    fi
fi

if [[ "$DEPLOY_MODE" == "ota" ]]; then
    echo ""
    c "  Cấu hình OTA — cần GitHub token để pull image từ ghcr.io"

    # Đọc credentials đã lưu (nếu re-deploy)
    SAVED_GH_USER="$(grep '^GITHUB_USERNAME=' "$ENV_FILE" 2>/dev/null | cut -d= -f2 | tr -d '"' || true)"
    SAVED_GH_TOKEN="$(grep '^GITHUB_TOKEN=' "$ENV_FILE" 2>/dev/null | cut -d= -f2 | tr -d '"' || true)"

    if [[ -n "$SAVED_GH_USER" && -n "$SAVED_GH_TOKEN" ]]; then
        warn "Dùng GitHub credentials đã lưu (user: ${SAVED_GH_USER})"
        GH_USER="$SAVED_GH_USER"
        GH_TOKEN="$SAVED_GH_TOKEN"
    else
        ask "GitHub Username (vd: toannice)"
        GH_USER="$REPLY"
        ask_secret "GitHub Token (cần quyền read:packages)"
        GH_TOKEN="$REPLY"
    fi

    # Đăng nhập GHCR
    echo ""
    echo "  Đăng nhập ghcr.io..."
    echo "$GH_TOKEN" | docker login ghcr.io -u "$GH_USER" --password-stdin \
        && ok "ghcr.io login thành công" \
        || die "Login thất bại — kiểm tra lại username và token"

    # Lưu vào .env để watchdog có thể pull
    grep -q "^GITHUB_USERNAME=" "$ENV_FILE" \
        && sed -i "s|^GITHUB_USERNAME=.*|GITHUB_USERNAME=${GH_USER}|" "$ENV_FILE" \
        || echo "GITHUB_USERNAME=${GH_USER}" >> "$ENV_FILE"
    grep -q "^GITHUB_TOKEN=" "$ENV_FILE" \
        && sed -i "s|^GITHUB_TOKEN=.*|GITHUB_TOKEN=${GH_TOKEN}|" "$ENV_FILE" \
        || echo "GITHUB_TOKEN=${GH_TOKEN}" >> "$ENV_FILE"
    grep -q "^DEPLOY_MODE=" "$ENV_FILE" \
        && sed -i "s|^DEPLOY_MODE=.*|DEPLOY_MODE=ota|" "$ENV_FILE" \
        || echo "DEPLOY_MODE=ota" >> "$ENV_FILE"

    # Đảm bảo docker config.json có credentials để watchdog (chạy root) pull được
    mkdir -p /root/.docker
    docker login ghcr.io -u "$GH_USER" --password-stdin <<< "$GH_TOKEN" >/dev/null 2>&1 || true

    ok "OTA mode configured — watchdog sẽ tự pull image mới từ ghcr.io/toannice/nvr:latest"
else
    grep -q "^DEPLOY_MODE=" "$ENV_FILE" \
        && sed -i "s|^DEPLOY_MODE=.*|DEPLOY_MODE=offline|" "$ENV_FILE" \
        || echo "DEPLOY_MODE=offline" >> "$ENV_FILE"
    ok "Offline mode — update bằng cách chạy lại deploy-offline.sh với bundle mới"
fi

# ── 6. TLS cert + Secrets ─────────────────────────────────────────────────────
step "6/7" "TLS certificate & Secrets"
CERT="$INSTALL_DIR/nginx/server.crt"
KEY="$INSTALL_DIR/nginx/server.key"

if [[ -s "$CERT" && -s "$KEY" ]]; then
    ok "TLS cert đã có — giữ nguyên"
else
    HOST_IP="$(grep '^FRIGATE_HOST_IP=' "$ENV_FILE" | cut -d= -f2 | tr -d '"')"
    openssl req -x509 -nodes -newkey rsa:2048 \
        -keyout "$KEY" -out "$CERT" -days 3650 \
        -subj "/CN=${HOST_IP:-nvr-device}" \
        -addext "subjectAltName=IP:${HOST_IP:-127.0.0.1}" \
        2>/dev/null
    chmod 600 "$KEY"
    ok "Self-signed cert tạo xong (10 năm, CN=${HOST_IP:-nvr-device})"
fi

SECRETS_DIR="$INSTALL_DIR/secrets"
echo ""
c "  Secrets (Enter để bỏ qua, điền sau trong $SECRETS_DIR/)"

for s_file in gmail_app_password edge_agent_token nebula_sign_token; do
    if [[ -s "$SECRETS_DIR/$s_file" ]]; then
        ok "$s_file đã có"; continue
    fi
    [[ -f "$HERE/secrets/$s_file" && -s "$HERE/secrets/$s_file" ]] \
        && cp "$HERE/secrets/$s_file" "$SECRETS_DIR/$s_file" && ok "$s_file copied from bundle" && continue

    case "$s_file" in
        gmail_app_password) ask_secret "Gmail App Password (myaccount.google.com/apppasswords)" ;;
        edge_agent_token)
            ask_secret "Edge Agent Token (fleet token Pi gateway, Enter = tự tạo ngẫu nhiên)"
            [[ -z "$REPLY" ]] && REPLY="$(openssl rand -hex 32)"
            ;;
        nebula_sign_token)  ask_secret "Nebula Sign Token (Enter nếu không dùng Nebula overlay)" ;;
    esac
    echo -n "$REPLY" > "$SECRETS_DIR/$s_file"
    [[ -n "$REPLY" ]] && ok "$s_file saved" || warn "$s_file để trống"
done
touch "$SECRETS_DIR/zt_controller_token" 2>/dev/null || true

# ── 7. Docker Compose up ─────────────────────────────────────────────────────
step "7/7" "Khởi động NVR stack (chế độ: ${DEPLOY_MODE})"
cd "$INSTALL_DIR"

if [[ "$DEPLOY_MODE" == "ota" ]]; then
    # OTA: dùng compose gốc — nvr-wrapper pull từ GHCR, watchdog OTA bật
    COMPOSE_CMD="docker compose -f docker-compose.yml"
    echo "  nvr-wrapper image: ghcr.io/toannice/nvr:latest (từ GHCR)"
    echo "  OTA: watchdog tự pull bản mới khi dev push lên GHCR"
else
    # Offline: dùng override — nvr-wrapper dùng image đã load từ tar, OTA tắt
    COMPOSE_CMD="docker compose -f docker-compose.yml -f docker-compose.offline.yml"
    export WRAPPER_IMAGE="${WRAPPER_IMAGE}"
    echo "  nvr-wrapper image: ${WRAPPER_IMAGE} (từ bundle)"
    echo "  OTA: tắt"
fi

echo ""
echo "  Khởi động services (mosquitto → frigate → nvr-wrapper → watchdog)..."
$COMPOSE_CMD up -d

# nginx phải start riêng (không depends_on nvr-wrapper)
$COMPOSE_CMD up -d nginx

# Lưu compose command để dùng lại sau (restart, update, logs...)
cat > "$INSTALL_DIR/nvr.sh" <<WRAPEOF
#!/usr/bin/env bash
# Helper: chạy đúng compose command cho máy này
# Dùng: ./nvr.sh ps | logs | restart nvr-wrapper | ...
cd "${INSTALL_DIR}"
exec ${COMPOSE_CMD} "\$@"
WRAPEOF
chmod +x "$INSTALL_DIR/nvr.sh"

echo ""
ok "Stack đã khởi động!"
echo ""
HOST_IP="$(grep '^FRIGATE_HOST_IP=' "$ENV_FILE" | cut -d= -f2 | tr -d '"')"
c "  Truy cập Web UI:"
echo "    https://${HOST_IP:-<IP_MÁY>}  (bỏ qua cảnh báo certificate)"
echo ""
c "  Quản lý stack (shortcut):"
echo "    $INSTALL_DIR/nvr.sh ps"
echo "    $INSTALL_DIR/nvr.sh logs -f nvr-wrapper"
echo "    $INSTALL_DIR/nvr.sh restart nvr-wrapper"
echo ""

if [[ "$DEPLOY_MODE" == "ota" ]]; then
    c "  Cập nhật tự động:"
    echo "    Dev push image mới lên GHCR → watchdog phát hiện → tự pull & restart nvr-wrapper"
    echo "    Kiểm tra log OTA:  $INSTALL_DIR/nvr.sh logs -f nvr-watchdog"
else
    c "  Cập nhật (offline):"
    echo "    1. Chạy ./package.sh trên máy dev → file bundle mới"
    echo "    2. Copy bundle sang máy này → tar -xzf → sudo ./deploy-offline.sh"
    echo "    (script tự nhận ra .env cũ, chỉ update image và restart)"
fi

if [[ -n "$(find "$SECRETS_DIR" -maxdepth 1 -name '*.secret' -empty 2>/dev/null)" ]] || \
   [[ ! -s "$SECRETS_DIR/gmail_app_password" ]]; then
    echo ""
    warn "Secrets còn thiếu — điền sau:"
    echo "    nano $SECRETS_DIR/gmail_app_password"
    echo "    $INSTALL_DIR/nvr.sh restart nvr-wrapper"
fi
