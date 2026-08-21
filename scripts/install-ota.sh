#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# install-ota.sh — cài hạ tầng cập nhật OTA lên máy này.
#
# Làm 3 việc:
#   1. Nạp khoá công khai tin cậy vào /opt/nvr/keys/ota-trust.d/  (root:root 0444)
#   2. Cài systemd units nvr-ota-apply.{path,service}
#   3. Tạo data/ota/ với quyền đúng
#
# Chạy:  sudo ./scripts/install-ota.sh
#        sudo ./scripts/install-ota.sh --check     # chỉ kiểm, không đổi gì
#
# Theo khuôn scripts/install-ip-guard.sh (cùng dự án, cùng cách cài systemd unit).
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYRING_DIR="/opt/nvr/keys/ota-trust.d"
UNIT_SRC="$PROJECT_DIR/deploy/systemd"

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

c()    { printf '\033[1;36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "cần sudo: sudo $0"

[[ -d "$PROJECT_DIR/ota/keys" ]] || die "không thấy $PROJECT_DIR/ota/keys"
[[ -f "$UNIT_SRC/nvr-ota-apply.path" ]] || die "không thấy $UNIT_SRC/nvr-ota-apply.path"
[[ -x "$PROJECT_DIR/scripts/ota-apply.sh" ]] || chmod +x "$PROJECT_DIR/scripts/ota-apply.sh"

# ── 1. Khoá công khai ────────────────────────────────────────────────────────
c "[1/3] Khoá công khai tin cậy → $KEYRING_DIR"

shopt -s nullglob
PUBS=("$PROJECT_DIR"/ota/keys/*.pub)
shopt -u nullglob
[[ ${#PUBS[@]} -gt 0 ]] || die "không có file .pub nào trong ota/keys/ — chưa sinh khoá?
      Trên máy air-gap: bash ota/airgap-setup.sh
      Rồi copy ota-primary.pub + ota-backup.pub vào ota/keys/"

INSTALLED=0
SKIPPED_DEV=0
for pub in "${PUBS[@]}"; do
    name="$(basename "$pub")"

    # ── CHỐT CHỐNG KHOÁ DEV ──────────────────────────────────────────────────
    # Máy khách tin khoá dev nghĩa là BẤT CỨ AI chạm được máy dev đều đẩy được bản cập nhật
    # vào máy khách đó. Đây là loại lỗi chỉ phát hiện được khi đã quá muộn, nên chặn ở đây.
    if [[ "$name" == ota-dev* ]]; then
        if [[ "${OTA_ALLOW_DEV_KEY:-0}" == "1" ]]; then
            warn "CÀI KHOÁ DEV ($name) — máy này CHỈ dùng để test, KHÔNG giao khách"
        else
            warn "bỏ qua $name (khoá DEV không được cài lên máy thật)"
            # Biến phải đặt SAU sudo: sudo mặc định env_reset, nên "VAR=1 sudo script" đặt
            # biến cho chính sudo rồi xoá đi, script không bao giờ thấy. "sudo VAR=1 script"
            # thì sudo truyền vào môi trường của lệnh.
            warn "  đang dựng máy test? chạy: sudo OTA_ALLOW_DEV_KEY=1 $0"
            SKIPPED_DEV=$((SKIPPED_DEV + 1))
            continue
        fi
    fi

    # Kiểm là khoá Ed25519 thật trước khi cài — khoá rác trong keyring làm watchdog log ERROR
    # mỗi lần khởi động mà không ai biết vì sao.
    if ! openssl pkey -pubin -in "$pub" -noout -text 2>/dev/null | grep -qi "ED25519"; then
        warn "bỏ qua $name (không phải khoá công khai Ed25519)"
        continue
    fi
    kid="$(openssl pkey -pubin -in "$pub" -pubout -outform DER 2>/dev/null | sha256sum | cut -c1-8)"

    if [[ $CHECK_ONLY -eq 1 ]]; then
        ok "$name keyId=$kid (chưa cài, --check)"
    else
        mkdir -p "$KEYRING_DIR"
        install -m 444 -o root -g root "$pub" "$KEYRING_DIR/$name"
        ok "$name keyId=$kid"
    fi
    INSTALLED=$((INSTALLED + 1))
done

if [[ $INSTALLED -eq 0 ]]; then
    if [[ $SKIPPED_DEV -gt 0 ]]; then
        die "không cài được khoá nào — chỉ tìm thấy khoá DEV.
      Máy thật : cần ota-primary.pub từ máy air-gap (xem docs/ota/ky-va-may-air-gap.md)
      Máy test : sudo OTA_ALLOW_DEV_KEY=1 $0
                 (biến đặt SAU sudo — sudo xoá môi trường nên đặt trước là mất)"
    fi
    die "không cài được khoá nào — mọi gói OTA sẽ bị từ chối"
fi

# ── 2. systemd units ─────────────────────────────────────────────────────────
c "[2/3] systemd units"

if [[ $CHECK_ONLY -eq 1 ]]; then
    for u in nvr-ota-apply.path nvr-ota-apply.service; do
        if [[ -f "/etc/systemd/system/$u" ]]; then ok "$u đã cài"; else warn "$u CHƯA cài"; fi
    done
else
    for u in nvr-ota-apply.path nvr-ota-apply.service; do
        # Unit file dùng placeholder __PROJECT_DIR__ vì đường dẫn khác nhau giữa máy dev
        # (~/toanDoc/Documents/my-nvr) và máy khách (/opt/nvr).
        sed "s|__PROJECT_DIR__|$PROJECT_DIR|g" "$UNIT_SRC/$u" > "/etc/systemd/system/$u"
        chmod 644 "/etc/systemd/system/$u"
        ok "$u"
    done
    systemctl daemon-reload
    # Chỉ enable .path — .service do .path kích hoạt, enable nó sẽ chạy ngay lúc boot.
    systemctl enable --now nvr-ota-apply.path
    ok "nvr-ota-apply.path đã enable + start"
fi

# ── 3. Thư mục làm việc ──────────────────────────────────────────────────────
c "[3/3] Thư mục data/ota"

OTA_DIR="$PROJECT_DIR/data/ota"
if [[ $CHECK_ONLY -eq 1 ]]; then
    [[ -d "$OTA_DIR" ]] && ok "$OTA_DIR đã có" || warn "$OTA_DIR chưa có"
else
    mkdir -p "$OTA_DIR"/{incoming,staged,rollback/files}
    # Chủ sở hữu theo thư mục data/ hiện có: container nvr-wrapper ghi upload vào incoming/,
    # nên phải cùng uid với phần data còn lại, không phải root.
    if [[ -d "$PROJECT_DIR/data" ]]; then
        owner="$(stat -c '%u:%g' "$PROJECT_DIR/data")"
        chown -R "$owner" "$OTA_DIR"
        ok "$OTA_DIR (owner $owner, theo data/)"
    else
        ok "$OTA_DIR"
    fi
fi

# ── Tổng kết ─────────────────────────────────────────────────────────────────
DEVICE_MODEL="$(grep -E '^DEVICE_MODEL=' "$PROJECT_DIR/.env" 2>/dev/null | cut -d= -f2- | tr -d '"'"'"' ' || true)"
echo
if [[ -z "$DEVICE_MODEL" ]]; then
    warn "THIẾU DEVICE_MODEL trong .env — MỌI gói OTA sẽ bị từ chối (gate MODEL_MISMATCH)"
    warn "  thêm vào .env:  DEVICE_MODEL=AIVIEW-NVR-X1"
else
    ok "DEVICE_MODEL=$DEVICE_MODEL"
fi

# Đọc `enabled:` trong khối `ota:`. Dùng awk theo trạng thái thay vì `grep -A<n>`: giữa `ota:`
# và `enabled:` có mấy dòng comment, nên -A2 (hay bất kỳ số cố định nào) là false negative đang
# chờ xảy ra — và nó báo "OTA TẮT" trong khi thực tế đang BẬT, tức là nói dối người vận hành
# về đúng cái họ vừa cài.
OTA_ENABLED="$(awk '
    /^ota:/            { in_ota = 1; next }
    /^[a-z_]+:/        { in_ota = 0 }
    in_ota && /^[[:space:]]+enabled:/ { gsub(/[^a-z]/, "", $2); print $2; exit }
' "$PROJECT_DIR/config/watchdog.yml" 2>/dev/null || true)"
[[ "$OTA_ENABLED" == "true" ]] \
    && ok "OTA tự động: BẬT (config/watchdog.yml → ota.enabled)" \
    || warn "OTA tự động: TẮT — chỉ nhận gói upload tay qua Web UI (config/watchdog.yml → ota.enabled)"

cat <<EOF

$(c "══ Xong ══")

  Kiểm hoạt động:
    systemctl status nvr-ota-apply.path
    ls -la $KEYRING_DIR
    docker compose logs nvr-watchdog | grep KEYRING

  Upload gói tại hiện trường:
    https://<ip-máy> → Thiết lập → Cập nhật → kéo file .nvrup vào

  Xem tiến trình khi đang cập nhật:
    cat $OTA_DIR/status.json | jq .
    journalctl -u nvr-ota-apply.service -f
EOF
