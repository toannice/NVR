# HQ Controller Setup — Self-host ZeroTier + ztncui

> Phase 0 của [specs/004-gateway-onboarding/plan.md](../specs/004-gateway-onboarding/plan.md).
> Mục tiêu: **tự host control plane ZeroTier** trên một **box Ubuntu riêng ở HQ** (always-on) — $0, **không giới hạn node**, full ability. Data plane vẫn là ZeroTier One (đã chốt). NAT traversal dùng **planet roots công cộng** (không cần moon). Scripts ở `hq-controller/`.

---

## 0. Vì sao tự host (nhắc lại)

| | ZeroTier Central (cloud) | **Self-host controller (cái này)** |
|---|---|---|
| Giới hạn node | ~25 (free) | **Không giới hạn** |
| Chi phí | free tới cap, rồi trả tiền | **$0** (chạy trên box sẵn có) |
| Trang approve | my.zerotier.com | **ztncui của bạn** |
| NAT traversal | planet roots ZT | **vẫn planet roots ZT** (controller chỉ lo auth) |
| Sở hữu | ZT cloud | **toàn quyền** |

Controller chỉ cấp config + authorize member. **Không relay video** — video đi P2P trực tiếp NVR ↔ gateway qua ZeroTier.

---

## 1. Cài đặt (một lệnh)

Trên **box Ubuntu HQ** (không phải NVR):

```bash
git clone <repo> my-nvr && cd my-nvr/hq-controller   # hoặc copy thư mục hq-controller sang box
sudo bash install-controller.sh
```

Script làm:
1. Cài `zerotier-one` → enable systemd (controller mode = chạy bình thường, có sẵn controller API ở `127.0.0.1:9993/controller`).
2. Cài Node.js 18 + build deps.
3. Clone + build **ztncui**, tạo user `ztncui`, ghi `.env` (nhúng token controller), cài **systemd unit auto-start**.
4. Cài **backup script + systemd timer** (chạy 03:00 mỗi ngày).

> **systemd auto-start** (yêu cầu owner): cả `zerotier-one` và `ztncui` đều `enable` → **tự lên khi box vừa có điện**. Kiểm tra: `sudo reboot` rồi `systemctl status zerotier-one ztncui`.

---

## 2. Tạo network (làm tay trên ztncui, 1 lần)

1. Mở `https://<IP-box-HQ>:3443` (cert tự ký → Accept cảnh báo).
2. Đăng nhập **admin / password** → **đổi mật khẩu ngay**.
3. **Networks → Add network** → mở network → **Easy setup**:
   - Đặt **IPv4 Auto-Assign pool**, ví dụ `10.147.20.0/24`.
   - **Ghi lại NETWORK ID** (16 hex, bắt đầu bằng node address của controller).
4. Pool này chính là **`ZT_SCAN_SUBNET`** cho NVR.

---

## 3. Đưa NVR vào network mới

Trên máy NVR:

```bash
# join network của controller HQ
sudo zerotier-cli join <NETWORK_ID>
zerotier-cli listnetworks        # xem address node NVR
```

Lên ztncui → network → **Members** → **authorize** member NVR → nó nhận IP trong pool (vd `10.147.20.x`).

Sửa `.env` của NVR (`/home/toan/my-nvr/.env`) rồi recreate `nvr-wrapper`:
```
ZT_NETWORK_ID=<NETWORK_ID mới>
ZT_SCAN_SUBNET=10.147.20.0/24      # khớp pool bước 2
```
```bash
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx
docker logs nvr-wrapper | grep GATEWAY   # SCAN_START subnet=10.147.20.0/24
```

> ⚠️ **Đổi network ID TRƯỚC khi nhân bản gateway.** Network ID self-host khác `3b19...` của Central; gateway nướng network ID cũ sẽ không vào được mạng mới.

---

## 4. Gateway dùng network mới

Trong image gateway / `agent.env` trên Pi: đặt `ZT_NETWORK_ID=<NETWORK_ID mới>`. Boot lên → tự `zerotier join` → hiện member chờ trong ztncui → admin authorize. (Chi tiết onboarding: Phase 1-4 của plan.)

---

## 5. Backup & khôi phục (BẮT BUỘC)

**Mất `identity.secret` của controller = mất luôn NETWORK ID = cả fleet phải join mạng mới.** Nên backup là sống còn.

- Tự động: timer chạy 03:00 mỗi ngày → `/var/backups/zt-controller/*.tar.gz` (giữ 14 bản).
- Chạy thử / chạy tay:
  ```bash
  sudo bash hq-controller/backup.sh
  systemctl list-timers zt-controller-backup.timer
  ```
- **Khuyến nghị mạnh:** copy các bản `.tar.gz` sang **máy KHÁC** (HQ box hỏng là mất hết):
  ```bash
  rsync -a /var/backups/zt-controller/ user@other-host:/backup/zt-controller/
  ```

**Khôi phục** (box mới): cài lại bằng `install-controller.sh`, **dừng zerotier-one + ztncui**, giải nén bản backup đè lên `/var/lib/zerotier-one/` và `/opt/ztncui/src/etc/`, `chown` đúng, start lại. Network ID + members trở lại nguyên vẹn.

---

## 6. Kiểm tra nhanh

```bash
systemctl status zerotier-one ztncui zt-controller-backup.timer
zerotier-cli info                      # 200 info <address> <ver> ONLINE
ss -tlnp | grep 3443                   # ztncui đang nghe
curl -k https://localhost:3443 -o /dev/null -s -w '%{http_code}\n'   # 200/302
```

---

## 7. Bảo mật (Tier 0)

- **ztncui `:3443` CHỈ cho reachable trong LAN/qua ZeroTier — ĐỪNG lộ ra internet.** Admin quản trị từ HQ LAN hoặc qua ZeroTier là đủ.
- `.env` của ztncui chứa token controller → `chmod 600`, **không commit** (đã gitignore).
- Đổi mật khẩu admin ztncui ngay lần đầu.
- Chức năng controller **không cần mở cổng inbound** (ZeroTier planet lo việc tìm peer). Chỉ ztncui mới cần cổng, và chỉ trong nội bộ.

---

## Files

| File | Vai trò |
|---|---|
| `hq-controller/install-controller.sh` | Cài tất cả (idempotent) |
| `hq-controller/ztncui.service` | systemd unit ztncui (auto-start) |
| `hq-controller/backup.sh` | Backup identity + controller.d + ztncui |
| `hq-controller/zt-controller-backup.{service,timer}` | systemd timer backup hằng ngày |
