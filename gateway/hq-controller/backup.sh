#!/usr/bin/env bash
# ============================================================================
# backup.sh — Backup self-host ZeroTier controller (chạy bởi systemd timer)
#
# Backup những thứ MẤT = MẤT NETWORK ID / không khôi phục được controller:
#   - identity.secret / identity.public  (network ID sinh từ đây — TỐI QUAN TRỌNG)
#   - authtoken.secret                    (token controller API)
#   - controller.d/                       (networks + members đã authorize)
#   - /opt/ztncui/src/etc/                (user ztncui + TLS + DB ztncui)
#
# Giữ 14 bản gần nhất ở /var/backups/zt-controller/.
# Khuyến nghị THÊM: rsync/scp các bản này sang máy KHÁC (HQ box cháy là mất hết).
# ============================================================================
set -euo pipefail

ZT_HOME="/var/lib/zerotier-one"
ZTNCUI_ETC="/opt/ztncui/src/etc"
DEST="/var/backups/zt-controller"
KEEP=14
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$DEST/zt-controller-$STAMP.tar.gz"

mkdir -p "$DEST"

# Gom các path tồn tại (controller.d/ztncui etc có thể chưa có lúc mới cài)
paths=()
for p in \
  "$ZT_HOME/identity.secret" \
  "$ZT_HOME/identity.public" \
  "$ZT_HOME/authtoken.secret" \
  "$ZT_HOME/controller.d" \
  "$ZTNCUI_ETC" ; do
  [[ -e "$p" ]] && paths+=("$p")
done

if [[ ${#paths[@]} -eq 0 ]]; then
  echo "[!] Không tìm thấy gì để backup — controller đã cài chưa?" >&2
  exit 1
fi

tar -czf "$OUT" "${paths[@]}" 2>/dev/null
chmod 600 "$OUT"
echo "[+] Backup: $OUT ($(du -h "$OUT" | cut -f1))"

# Dọn bản cũ, giữ $KEEP bản mới nhất
mapfile -t old < <(ls -1t "$DEST"/zt-controller-*.tar.gz 2>/dev/null | tail -n +$((KEEP+1)))
for f in "${old[@]:-}"; do [[ -n "$f" ]] && rm -f "$f" && echo "[-] Xoá bản cũ: $f"; done

# ---------------------------------------------------------------------------
# Đẩy off-box sang máy KHÁC (vd NAS) — opt-in, dùng SSH key (KHÔNG nhúng mật khẩu).
# Cấu hình ở /etc/zt-controller-backup.env (chmod 600, KHÔNG commit):
#   NAS_DEST="user@192.168.2.88:/volume1/backup/zt-controller/"
#   NAS_SSH_KEY="/root/.ssh/id_nas"          # tùy chọn; mặc định dùng key sẵn của root
#   NAS_SSH_PORT="22"                        # tùy chọn
# HQ box cháy = mất network ID, nên bước này rất nên bật.
# ---------------------------------------------------------------------------
ENVF="/etc/zt-controller-backup.env"
if [[ -f "$ENVF" ]]; then
  # shellcheck disable=SC1090
  source "$ENVF"
  if [[ -n "${NAS_DEST:-}" ]]; then
    SSH_OPTS="-o BatchMode=yes -o StrictHostKeyChecking=accept-new -o ConnectTimeout=10"
    [[ -n "${NAS_SSH_PORT:-}" ]] && SSH_OPTS="$SSH_OPTS -p ${NAS_SSH_PORT}"
    [[ -n "${NAS_SSH_KEY:-}"  ]] && SSH_OPTS="$SSH_OPTS -i ${NAS_SSH_KEY}"
    echo "[*] Đẩy backup sang NAS: $NAS_DEST"
    if rsync -a --timeout=30 -e "ssh $SSH_OPTS" "$OUT" "$NAS_DEST" 2>/dev/null; then
      echo "[+] NAS OK"
    else
      # KHÔNG để NAS down làm hỏng backup local (backup local đã thành công ở trên).
      echo "[!] CẢNH BÁO: đẩy NAS thất bại (NAS down / SSH key chưa setup?). Backup local vẫn còn." >&2
    fi
  fi
fi
