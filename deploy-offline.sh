#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# deploy-offline.sh — triển khai NVR stack trên máy mới.
#
# Chỉ một chế độ: OFFLINE. Không cần internet — Docker + images cài từ bundle,
# không đăng nhập registry, không lưu credential nào của nhà phát triển lên máy khách.
# Cập nhật = chạy lại script này với bundle mới.
#
# (Bỏ chế độ "ota" từ 2026-08-03: nó hỏi GitHub token rồi ghi plaintext vào .env
#  máy khách + /root/.docker/config.json. Bản mới đưa xuống bằng bundle.)
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
DEPLOY_MODE="offline"   # chế độ duy nhất

# ── 1. Docker ────────────────────────────────────────────────────────────────
step "1/6" "Docker engine"
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
step "2/6" "Load Docker images từ images.tar"
echo "  ($(du -sh "$HERE/images.tar" | cut -f1) — có thể mất 2–5 phút...)"
docker load -i "$HERE/images.tar"
ok "Tất cả images đã load"
echo "  Images đã load:"
docker images --format "    {{.Repository}}:{{.Tag}}  ({{.Size}})" \
    | grep -E "nvr|frigate|mosquitto|nginx|watchdog" || true

# ── 3. Cài đặt thư mục ───────────────────────────────────────────────────────
step "3/6" "Chuẩn bị thư mục ${INSTALL_DIR}"
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
[[ -f "$HERE/mosquitto/config/acl" ]] \
    && cp "$HERE/mosquitto/config/acl" "$INSTALL_DIR/mosquitto/config/"
if [[ -f "$HERE/data/dc-bridge" ]]; then
    cp "$HERE/data/dc-bridge" "$INSTALL_DIR/data/"
    chmod +x "$INSTALL_DIR/data/dc-bridge"
    ok "dc-bridge copied"
fi
ok "Project files copied to ${INSTALL_DIR}"

# ── 4. .env ──────────────────────────────────────────────────────────────────
step "4/6" ".env configuration"
ENV_FILE="$INSTALL_DIR/.env"

if [[ -f "$ENV_FILE" ]]; then
    warn ".env đã tồn tại — giữ nguyên (xóa $ENV_FILE nếu muốn reset)"
else
    cp "$INSTALL_DIR/.env.template" "$ENV_FILE"

    HOST_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
    [[ -n "$HOST_IP" ]] && sed -i "s|^FRIGATE_HOST_IP=.*|FRIGATE_HOST_IP=${HOST_IP}|" "$ENV_FILE"
    sed -i "s|^FRIGATE_VERSION=.*|FRIGATE_VERSION=${FRIGATE_VER}|" "$ENV_FILE"
    ok ".env created (FRIGATE_HOST_IP=${HOST_IP:-?})"

    echo ""
    # Không còn hỏi Gmail: khôi phục mật khẩu chạy offline (mã khôi phục + câu hỏi bảo mật),
    # thiết bị không giữ thông tin đăng nhập mail nào.
    ask "HOSTNAME máy này (vd: nvr-office-1, Enter = nvr-edge-node)"
    HOSTNAME_VAL="${REPLY:-nvr-edge-node}"
    grep -q "^HOSTNAME=" "$ENV_FILE" \
        && sed -i "s|^HOSTNAME=.*|HOSTNAME=${HOSTNAME_VAL}|" "$ENV_FILE" \
        || echo "HOSTNAME=${HOSTNAME_VAL}" >> "$ENV_FILE"

    ok ".env configured"
fi

# ── 4b. MQTT internal-bus auth ───────────────────────────────────────────────
# mosquitto chạy allow_anonymous=false (từ 1.0.0.3): cần 2 mật khẩu trong .env và file hash
# mosquitto/config/passwd khớp với chúng. Tự sinh nếu thiếu — image mosquitto đã load ở bước 2
# nên mosquitto_passwd chạy được hoàn toàn offline. Chạy cả khi .env cũ (upgrade từ bản trước
# chưa có MQTT auth: biến thiếu sẽ được thêm + passwd được sinh mới).
# KHÔNG dùng `tr -dc ... </dev/urandom | head -c 32`: head thoát sau 32 ký tự → tr nhận SIGPIPE
# (exit 141) → `set -o pipefail` + `set -e` giết script IM LẶNG ngay giữa bước 4b. Bug này chỉ lộ
# trên máy cài mới (máy nâng cấp đã có sẵn mật khẩu trong .env nên bỏ qua nhánh này). Thay bằng
# input hữu hạn (256 byte ≈ 155 ký tự alnum, thừa cho 32) → không còn pipe bị đóng sớm.
rand_pw() {
    local p
    p="$(head -c 256 /dev/urandom | LC_ALL=C tr -dc 'A-Za-z0-9' || true)"
    [[ ${#p} -ge 32 ]] || die "rand_pw: không sinh đủ 32 ký tự (/dev/urandom lỗi?)"
    printf '%s' "${p:0:32}"
}
grep -qE '^MQTT_FRIGATE_PASSWORD=' "$ENV_FILE" || printf '\nMQTT_FRIGATE_PASSWORD=\n' >> "$ENV_FILE"
grep -qE '^MQTT_BACKEND_PASSWORD=' "$ENV_FILE" || printf 'MQTT_BACKEND_PASSWORD=\n' >> "$ENV_FILE"
MQTT_FP="$(grep -E '^MQTT_FRIGATE_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2- | sed 's/#.*//' | tr -d '"\r\t ')"
MQTT_BP="$(grep -E '^MQTT_BACKEND_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2- | sed 's/#.*//' | tr -d '"\r\t ')"
MQTT_REGEN=0
if [[ -z "$MQTT_FP" ]]; then
    MQTT_FP="$(rand_pw)"; sed -i "s|^MQTT_FRIGATE_PASSWORD=.*|MQTT_FRIGATE_PASSWORD=${MQTT_FP}|" "$ENV_FILE"
    MQTT_REGEN=1; ok "MQTT_FRIGATE_PASSWORD generated"
fi
if [[ -z "$MQTT_BP" ]]; then
    MQTT_BP="$(rand_pw)"; sed -i "s|^MQTT_BACKEND_PASSWORD=.*|MQTT_BACKEND_PASSWORD=${MQTT_BP}|" "$ENV_FILE"
    MQTT_REGEN=1; ok "MQTT_BACKEND_PASSWORD generated"
fi
if [[ ! -s "$INSTALL_DIR/mosquitto/config/passwd" || $MQTT_REGEN -eq 1 ]]; then
    docker run --rm -v "$INSTALL_DIR/mosquitto/config:/mc" eclipse-mosquitto:2 sh -c \
        "mosquitto_passwd -c -b /mc/passwd frigate '$MQTT_FP' && mosquitto_passwd -b /mc/passwd nvr-backend '$MQTT_BP' && chown mosquitto:mosquitto /mc/passwd && chmod 600 /mc/passwd" \
        && ok "mosquitto passwd file generated (frigate + nvr-backend)" \
        || die "mosquitto passwd generation failed"
else
    ok "mosquitto passwd present"
fi

# ── 4c. Dọn credential của nhà phát triển (máy nâng cấp từ bản cũ) ───────────
# Bản trước có chế độ "ota" ghi GITHUB_USERNAME/GITHUB_TOKEN plaintext vào .env và
# /root/.docker/config.json để watchdog pull GHCR. Không dùng registry nữa → xoá đi,
# nếu để lại thì ai lấy được máy là lấy được token của tài khoản dev.
if grep -qE '^(GITHUB_USERNAME|GITHUB_TOKEN)=.*[^=[:space:]]' "$ENV_FILE" 2>/dev/null; then
    sed -i -E '/^(GITHUB_USERNAME|GITHUB_TOKEN)=/d' "$ENV_FILE"
    warn "Đã xoá GITHUB_USERNAME/GITHUB_TOKEN khỏi .env (token cũ cần REVOKE trên GitHub)"
fi
sed -i -E '/^DEPLOY_MODE=/d' "$ENV_FILE" 2>/dev/null || true
echo "DEPLOY_MODE=offline" >> "$ENV_FILE"
if [[ -f /root/.docker/config.json ]] && grep -q 'ghcr.io' /root/.docker/config.json 2>/dev/null; then
    docker logout ghcr.io >/dev/null 2>&1 || true
    warn "Đã docker logout ghcr.io (bỏ credential registry khỏi /root/.docker/config.json)"
fi
# .env chứa mật khẩu MQTT của máy này — 644 (mặc định) cho mọi user local đọc được.
chmod 600 "$ENV_FILE"
ok "Chế độ: offline — cập nhật bằng bundle mới, không kéo image từ registry (.env chmod 600)"

# ── 5. TLS cert + Secrets ─────────────────────────────────────────────────────
step "5/6" "TLS certificate & Secrets"
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

for s_file in edge_agent_token nebula_sign_token; do
    if [[ -s "$SECRETS_DIR/$s_file" ]]; then
        ok "$s_file đã có"; continue
    fi
    [[ -f "$HERE/secrets/$s_file" && -s "$HERE/secrets/$s_file" ]] \
        && cp "$HERE/secrets/$s_file" "$SECRETS_DIR/$s_file" && ok "$s_file copied from bundle" && continue

    case "$s_file" in
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

# ── 6b. GPU (Intel VAAPI) ────────────────────────────────────────────────────
# docker-compose.yml khai `devices: /dev/dri/renderD128` cho frigate + nvr-wrapper. Máy KHÔNG
# có iGPU Intel (chỉ NVIDIA, hoặc VM, hoặc GPU passthrough tắt) sẽ chết ngay lúc `up`:
#   "error gathering device information while adding custom device /dev/dri/renderD128"
# Compose không có cú pháp device tuỳ chọn → sinh override gỡ hẳn khai báo đó. Frigate/dc-bridge
# tự fallback sang decode/encode phần mềm (libx264) — chậm hơn nhưng chạy đúng.
GPU_OVERRIDE=""
if [[ -e /dev/dri/renderD128 ]]; then
    ok "Intel VAAPI: /dev/dri/renderD128 có mặt — bật hardware acceleration"
else
    warn "Không thấy /dev/dri/renderD128 — chạy chế độ SOFTWARE (CPU cao hơn khi nhiều camera)"
    cat > "$INSTALL_DIR/docker-compose.nogpu.yml" <<'NOGPUEOF'
# Tự sinh bởi deploy-offline.sh — máy này không có /dev/dri/renderD128 (Intel iGPU).
# Gỡ khai báo device để container start được; decode/encode chạy bằng CPU.
# Xoá file này (và bỏ -f tương ứng) nếu sau này lắp GPU Intel.
services:
  frigate:
    devices: !reset []
  nvr-wrapper:
    devices: !reset []
NOGPUEOF
    GPU_OVERRIDE=" -f docker-compose.nogpu.yml"
    ok "docker-compose.nogpu.yml đã tạo"
fi

# ── 7. Docker Compose up ─────────────────────────────────────────────────────
step "6/6" "Khởi động NVR stack (chế độ: ${DEPLOY_MODE})"
cd "$INSTALL_DIR"

# Luôn dùng override offline: nvr-wrapper + watchdog chạy image đã load từ images.tar,
# không build (bundle không có source), không pull (không có registry).
COMPOSE_CMD="docker compose -f docker-compose.yml -f docker-compose.offline.yml${GPU_OVERRIDE}"
export WRAPPER_IMAGE="${WRAPPER_IMAGE}"
echo "  nvr-wrapper image: ${WRAPPER_IMAGE} (từ bundle)"
echo "  OTA qua registry: không có (cập nhật bằng bundle mới)"

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

# ── IP guard ─────────────────────────────────────────────────────────────────
# FRIGATE_HOST_IP quyết định WebRTC candidates + cổng restream 8554. Máy đổi IP
# (DHCP cấp lại, đổi mạng) mà .env còn IP cũ = docker không bind được cổng, frigate
# không lên → mất cả live view lẫn ghi hình dù container khác vẫn healthy.
#
# Kiểm tra MỘT LẦN lúc boot là đủ: đây là máy ghi hình đặt cố định, không phải máy
# xách tay đổi mạng liên tục. IP chỉ đổi khi có người cắm lại dây/đổi router/DHCP cấp
# lại — mà mọi trường hợp đó đều kèm khởi động lại hoặc có người ở đó xử lý. (Trước
# đây chỗ này là timer quét mỗi 2 phút; bỏ đi 2026-08-15, quét liên tục cả đời máy để
# bắt một sự kiện vài năm mới xảy ra là không đáng.)
#
# Chạy SAU docker.service: container đã lên sẵn bằng restart:unless-stopped với cổng
# publish cũ đã bake vào container, nên guard phải chạy bản đầy đủ (sửa .env RỒI
# recreate frigate), không phải --env-only.
if [[ -f "$HERE/scripts/nvr-ip-guard.sh" ]]; then
    cp "$HERE/scripts/nvr-ip-guard.sh" "$INSTALL_DIR/nvr-ip-guard.sh"
    chmod +x "$INSTALL_DIR/nvr-ip-guard.sh"

    cat > /etc/systemd/system/nvr-ip-guard.service <<UNITEOF
[Unit]
Description=NVR — đồng bộ FRIGATE_HOST_IP với IP LAN thật
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
Environment=NVR_INSTALL_DIR=${INSTALL_DIR}
ExecStart=${INSTALL_DIR}/nvr-ip-guard.sh

[Install]
WantedBy=multi-user.target
UNITEOF

    # Gỡ timer của bản cũ nếu máy này từng cài (nay chỉ chạy lúc boot)
    if [[ -f /etc/systemd/system/nvr-ip-guard.timer ]]; then
        systemctl disable --now nvr-ip-guard.timer >/dev/null 2>&1 || true
        rm -f /etc/systemd/system/nvr-ip-guard.timer
        ok "đã gỡ nvr-ip-guard.timer cũ (không quét định kỳ nữa)"
    fi

    systemctl daemon-reload
    systemctl enable nvr-ip-guard.service >/dev/null 2>&1 \
        && ok "nvr-ip-guard đã bật (kiểm tra IP mỗi lần khởi động máy)" \
        || warn "không bật được nvr-ip-guard.service — chạy tay khi đổi IP: $INSTALL_DIR/nvr-ip-guard.sh"
else
    warn "không tìm thấy scripts/nvr-ip-guard.sh trong bundle — bỏ qua IP guard"
fi

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

c "  Cập nhật:"
echo "    1. Chạy ./package.sh trên máy dev → file bundle mới"
echo "    2. Copy bundle sang máy này → tar -xzf → sudo ./deploy-offline.sh"
echo "    (script tự nhận ra .env cũ, chỉ update image và restart)"

if [[ -n "$(find "$SECRETS_DIR" -maxdepth 1 -name '*.secret' -empty 2>/dev/null)" ]]; then
    echo ""
    warn "Còn secret để trống trong $SECRETS_DIR — điền khi cần rồi:"
    echo "    $INSTALL_DIR/nvr.sh restart nvr-wrapper"
fi
