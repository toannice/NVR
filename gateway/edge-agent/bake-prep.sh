#!/usr/bin/env bash
# ============================================================================
# bake-prep.sh — Chuẩn bị một Pi "mẫu" thành GOLDEN IMAGE có thể nhân bản.
#
# Chạy TRÊN Pi mẫu (đã `install.sh` xong, agent chạy tốt), NGAY TRƯỚC khi tắt
# máy và chụp ảnh thẻ SD:
#     sudo ./bake-prep.sh
#
# Vì sao cần: nếu chỉ clone nguyên xi, MỌI Pi sẽ dùng chung 1 ZeroTier identity
# (cùng node address) -> đụng độ, chỉ 1 cái online được. Script này XOÁ identity
# + state riêng để mỗi Pi flash ra TỰ SINH identity mới ở lần boot đầu, nhưng GIỮ
# cấu hình fleet (network id + token) để nó tự join + provisioning ngay.
#
# Sau khi chạy xong -> `sudo poweroff` -> tháo thẻ -> dd/Pi Imager chụp image.
# ============================================================================
set -euo pipefail
if [[ $EUID -ne 0 ]]; then echo "Chạy bằng sudo/root."; exit 1; fi

ZT_HOME="/var/lib/zerotier-one"
AGENT_ENV="/etc/nvr-edge-agent/agent.env"
AGENT_STATE="/var/lib/nvr-edge-agent/state.json"
NGINX_CAMS="/etc/nginx/stream.d/cameras.conf"
# Cách A: image trung lập, KHÔNG bake network id (app enroll per-region). Network ví dụ của fleet
# HQ self-host: efb242bde2f3e856 (nvr-fleet, 10.147.20.0/24).

echo "==> Dừng dịch vụ trước khi dọn"
systemctl stop nvr-edge-agent.service 2>/dev/null || true
systemctl stop zerotier-one.service 2>/dev/null || true
systemctl stop nginx.service 2>/dev/null || true

echo "==> Kiểm tra agent.env: cần token; network id ĐỂ TRỐNG (cách A — app enroll per-region)"
if [[ ! -f "$AGENT_ENV" ]]; then
  echo "  [!] Thiếu $AGENT_ENV — chạy install.sh trước đã."; exit 1
fi
# Cách A: 1 image dùng chung mọi khu vực. KHÔNG bake network id ở đây — app điện thoại gắn khu vực
# cho từng Pi qua POST /enroll (agent tự persist vào state.json). Nên blank ZT_NETWORK_ID để image
# trung lập. (Muốn ép 1 khu vực cố định vào image thì tự đặt ZT_NETWORK_ID=<id> rồi bỏ dòng blank này.)
if grep -qE '^ZT_NETWORK_ID=' "$AGENT_ENV"; then
  sed -i "s/^ZT_NETWORK_ID=.*/ZT_NETWORK_ID=/" "$AGENT_ENV"
else
  echo "ZT_NETWORK_ID=" >> "$AGENT_ENV"
fi
# Token fleet thì BẮT BUỘC (NVR↔agent auth) — admin đặt 1 lần, dùng chung toàn fleet.
if ! grep -qE '^EDGE_AGENT_TOKEN=.+' "$AGENT_ENV"; then
  echo "  [!] CẢNH BÁO: EDGE_AGENT_TOKEN trống — image sẽ KHÔNG nói chuyện được với NVR."
  echo "      Đặt fleet token (khớp secrets/edge_agent_token trên NVR) rồi chạy lại."
  exit 1
fi
echo "  -> ZT_NETWORK_ID=(trống, app sẽ enroll), EDGE_AGENT_TOKEN: có."

echo "==> Xoá ZeroTier identity + state (mỗi Pi flash sẽ TỰ SINH identity mới ở boot đầu)"
rm -f  "$ZT_HOME"/identity.secret "$ZT_HOME"/identity.public
rm -rf "$ZT_HOME"/networks.d "$ZT_HOME"/peers.d "$ZT_HOME"/controller.d 2>/dev/null || true

echo "==> Xoá state agent (không bake sẵn camera nào) + reset nginx proxy về rỗng"
rm -f "$AGENT_STATE"
echo "# Managed by nvr-edge-agent" > "$NGINX_CAMS"

echo "==> Dọn định danh máy + log để image sạch (mỗi Pi sinh mới ở boot đầu)"
# machine-id: để trống -> systemd sinh mới lúc boot. SSH host keys: xoá -> regenerate.
truncate -s 0 /etc/machine-id 2>/dev/null || true
rm -f /var/lib/dbus/machine-id 2>/dev/null || true
rm -f /etc/ssh/ssh_host_* 2>/dev/null || true
journalctl --rotate 2>/dev/null || true
journalctl --vacuum-time=1s 2>/dev/null || true
rm -f /var/log/nginx/*.log 2>/dev/null || true
find /var/log -type f -name '*.log' -exec truncate -s 0 {} \; 2>/dev/null || true
rm -f /root/.bash_history /home/*/.bash_history 2>/dev/null || true

cat <<EOF

==================== GOLDEN IMAGE SẴN SÀNG ====================
Đã xoá: ZT identity/peers/networks, agent state, nginx cams, machine-id, ssh host keys, log.
Đã giữ: agent.env (ZT_NETWORK_ID trống + fleet token), binary, systemd units,
        nginx stream{} include + boot-race fix.

Bước cuối:
  1) sudo poweroff
  2) Tháo thẻ SD -> chụp image (Pi Imager / dd) -> đây là image nhân bản dùng cho MỌI khu vực.

Mỗi Pi flash ra: boot đầu tự sinh ZT identity mới -> CHƯA vào mạng nào (chờ enroll). FW mở app, quét
gateway, nhập Network ID khu vực (POST /enroll) -> agent join đúng mạng đó -> hiện member CHỜ trong
mục "Chờ cấp phép" trên Web UI NVR -> admin bấm "Cấp phép" (NVR tự authorize qua controller API,
Plan A — KHÔNG dùng ztncui) -> NVR scanner tự thấy. KHÔNG ai sờ Pi bằng tay.
==============================================================
EOF
