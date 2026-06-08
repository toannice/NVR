#!/usr/bin/env bash
# ============================================================================
# install-controller.sh — Dựng ZeroTier self-host controller + ztncui trên box HQ
# Phase 0 của specs/004-gateway-onboarding/plan.md
#
# Chạy TRÊN box Ubuntu HQ (KHÔNG phải NVR), với sudo:
#   sudo bash install-controller.sh
#
# Sau khi xong:
#   - zerotier-one chạy ở controller mode (systemd, auto-start) — đây là controller.
#   - ztncui (web UI) chạy systemd auto-start, HTTPS cổng 3443.
#   - Tạo network + IP pool trong ztncui, ghi lại NETWORK ID mới.
#   - KHÔNG cần mở cổng inbound cho chức năng controller (ZeroTier planet lo).
#     Chỉ cần cho admin truy cập ztncui :3443 trong LAN/qua ZeroTier.
# ============================================================================
set -euo pipefail

ZTNCUI_DIR="/opt/ztncui"
ZTNCUI_SRC="$ZTNCUI_DIR/src"
ZT_HOME="/var/lib/zerotier-one"
NODE_MAJOR="18"   # ztncui ổn định nhất trên Node 18 LTS

log() { echo -e "\n\033[1;32m[+] $*\033[0m"; }
err() { echo -e "\n\033[1;31m[!] $*\033[0m" >&2; }

if [[ $EUID -ne 0 ]]; then err "Phải chạy bằng sudo/root."; exit 1; fi

# ---------------------------------------------------------------------------
# 1. ZeroTier One (controller mode = chạy zerotier-one bình thường, nó tự có
#    controller API ở 127.0.0.1:9993/controller)
# ---------------------------------------------------------------------------
if ! command -v zerotier-cli >/dev/null 2>&1; then
  log "Cài ZeroTier One..."
  curl -s https://install.zerotier.com | bash
else
  log "ZeroTier One đã có: $(zerotier-cli -v 2>/dev/null || true)"
fi
systemctl enable --now zerotier-one
sleep 2
ZT_ADDRESS="$(zerotier-cli info | awk '{print $3}')"
log "Controller node address: $ZT_ADDRESS  (network ID sẽ có dạng ${ZT_ADDRESS}______)"

if [[ ! -f "$ZT_HOME/authtoken.secret" ]]; then
  err "Không thấy $ZT_HOME/authtoken.secret — zerotier-one chưa khởi tạo xong?"; exit 1
fi
ZT_TOKEN="$(cat "$ZT_HOME/authtoken.secret")"

# ---------------------------------------------------------------------------
# 2. Node.js 18 + build deps (ztncui có native module cần biên dịch)
# ---------------------------------------------------------------------------
if ! command -v node >/dev/null 2>&1 || [[ "$(node -v | sed 's/v\([0-9]*\).*/\1/')" -lt 16 ]]; then
  log "Cài Node.js ${NODE_MAJOR} LTS (NodeSource)..."
  curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash -
  apt-get install -y nodejs
fi
log "Node: $(node -v) / npm: $(npm -v)"
apt-get install -y git build-essential python3

# ---------------------------------------------------------------------------
# 3. ztncui (web UI cho controller)
# ---------------------------------------------------------------------------
if [[ ! -d "$ZTNCUI_SRC" ]]; then
  log "Clone ztncui..."
  git clone https://github.com/key-networks/ztncui "$ZTNCUI_DIR"
else
  log "ztncui đã có, git pull..."
  git -C "$ZTNCUI_DIR" pull --ff-only || true
fi

log "npm install (production)..."
( cd "$ZTNCUI_SRC" && npm install --omit=dev || npm install --production )

# User riêng chạy ztncui (đỡ chạy root)
if ! id ztncui >/dev/null 2>&1; then
  useradd --system --home "$ZTNCUI_DIR" --shell /usr/sbin/nologin ztncui
fi

# .env cho ztncui — token controller đọc từ authtoken.secret
log "Ghi $ZTNCUI_SRC/.env ..."
cat > "$ZTNCUI_SRC/.env" <<EOF
# ztncui config — KHÔNG commit file này (chứa token controller)
ZT_ADDR=127.0.0.1:9993
ZT_TOKEN=${ZT_TOKEN}
NODE_ENV=production
HTTPS_PORT=3443
# Cho phép truy cập ztncui từ LAN/ZeroTier (không chỉ loopback):
HTTP_ALL_INTERFACES=yes
EOF
chmod 600 "$ZTNCUI_SRC/.env"

# Seed user DB: ztncui chỉ ship etc/default.passwd (admin/password); thiếu etc/passwd
# → crash "ENOENT etc/passwd" ngay khi submit login. Copy 1 lần nếu chưa có.
if [[ -f "$ZTNCUI_SRC/etc/default.passwd" && ! -f "$ZTNCUI_SRC/etc/passwd" ]]; then
  log "Seed etc/passwd từ default.passwd (admin/password — đổi ngay sau khi login)..."
  cp "$ZTNCUI_SRC/etc/default.passwd" "$ZTNCUI_SRC/etc/passwd"
  chmod 600 "$ZTNCUI_SRC/etc/passwd"
fi

# TLS cert tự ký cho HTTPS của ztncui (src/bin/www đọc etc/tls/{privkey,fullchain}.pem).
# Thiếu file này → ztncui crash-loop "ENOENT etc/tls/fullchain.pem".
if [[ ! -f "$ZTNCUI_SRC/etc/tls/fullchain.pem" ]]; then
  log "Sinh TLS self-signed cho ztncui..."
  mkdir -p "$ZTNCUI_SRC/etc/tls"
  openssl req -x509 -newkey rsa:4096 -nodes \
    -keyout "$ZTNCUI_SRC/etc/tls/privkey.pem" \
    -out    "$ZTNCUI_SRC/etc/tls/fullchain.pem" \
    -days 3650 -subj "/CN=ztncui-hq"
  chmod 600 "$ZTNCUI_SRC/etc/tls/privkey.pem"
fi

chown -R ztncui:ztncui "$ZTNCUI_DIR"

# ---------------------------------------------------------------------------
# 4. systemd unit cho ztncui (auto-start khi box lên — yêu cầu owner)
# ---------------------------------------------------------------------------
log "Cài systemd unit ztncui..."
install -m 644 "$(dirname "$0")/ztncui.service" /etc/systemd/system/ztncui.service
systemctl daemon-reload
systemctl enable --now ztncui
sleep 2

# ---------------------------------------------------------------------------
# 5. Backup tự động (systemd timer) — identity + controller DB + ztncui data
# ---------------------------------------------------------------------------
log "Cài backup script + systemd timer..."
install -m 755 "$(dirname "$0")/backup.sh" /usr/local/bin/zt-controller-backup.sh
install -m 644 "$(dirname "$0")/zt-controller-backup.service" /etc/systemd/system/zt-controller-backup.service
install -m 644 "$(dirname "$0")/zt-controller-backup.timer"   /etc/systemd/system/zt-controller-backup.timer
systemctl daemon-reload
systemctl enable --now zt-controller-backup.timer

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
log "HOÀN TẤT."
cat <<EOF

==================== BƯỚC TIẾP THEO (làm tay trên trình duyệt) ====================
1. Mở:   https://<IP-box-HQ>:3443
   (cert tự ký → trình duyệt cảnh báo, cứ Accept)
2. Đăng nhập lần đầu:  admin / password  → ĐỔI MẬT KHẨU NGAY.
3. Networks → Add network → mở network vừa tạo:
     - Easy setup → đặt IPv4 Auto-Assign pool, ví dụ:  10.147.20.0/24
       (ghi nhớ pool này = ZT_SCAN_SUBNET cho NVR)
     - Lưu lại NETWORK ID (16 hex, bắt đầu bằng ${ZT_ADDRESS}).
4. Trên NVR: đổi .env
     ZT_NETWORK_ID=<network id mới>
     ZT_SCAN_SUBNET=10.147.20.0/24   # khớp pool ở bước 3
   rồi cho NVR  'zerotier join <network id>'  và approve NVR trong ztncui.
5. Re-bake gateway image với ZT_NETWORK_ID mới TRƯỚC khi deploy đại trà.

KIỂM TRA:
  systemctl status zerotier-one ztncui zt-controller-backup.timer
  zerotier-cli info
  ss -tlnp | grep 3443
  sudo bash $(dirname "$0")/backup.sh   # chạy thử backup 1 lần

BẢO MẬT (Tier 0): chỉ cho :3443 reachable trong LAN/ZeroTier, ĐỪNG lộ ra internet.
==================================================================================
EOF
