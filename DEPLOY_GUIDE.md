# HƯỚNG DẪN TRIỂN KHAI HỆ THỐNG NVR OTA (SERVER & EDGE)

Tài liệu này tổng hợp toàn bộ các bước bạn cần thực hiện để vận hành hệ thống Cập nhật tự động (OTA) cho các máy trạm NVR.

---

## 1. TẠI MÁY CHỦ (DEVELOPER MACHINE)
Bạn đã hoàn tất việc setup mã nguồn. Để phát hành bản cập nhật, hãy làm theo chu kỳ này:

1. **Sửa code/tính năng** trong project.
2. **Commit và Push** lên GitHub nhánh `main`.
3. **Gắn thẻ (Tag) phiên bản mới:**
   ```bash
   git tag v1.0.1  # Hoặc v1.0.2, v1.1.0...
   git push origin v1.0.1
   ```
4. **Theo dõi Build:** Vào `https://github.com/toannice/NVR/actions`. Đợi Pipeline hiện màu xanh ✅.

---

## 2. TẠI MÁY TRẠM (EDGE NODE) - QUA TAILSCALE
Đây là phần quan trọng để máy trạm tự động "thay máu" mà không cần bạn can thiệp thủ công.

### Bước A: Tạo GitHub Token (Chỉ làm 1 lần)
Máy trạm cần một chiếc "chìa khóa" để tải Docker Image riêng tư của bạn.
1. Vào GitHub -> Settings -> Developer Settings -> **Personal access tokens (classic)**.
2. Chọn **Generate new token**. Đặt tên là "NVR_EDGE".
3. Tích chọn quyền: `read:packages`.
4. **Lưu lại mã Token này** (Ví dụ: `ghp_abcd1234...`).

### Bước B: Đăng nhập Docker trên Máy Trạm
SSH vào máy trạm qua Tailscale và gõ:
```bash
docker login ghcr.io -u toannice
# Paste mã Token vừa lấy được vào phần Password
```

### Bước C: Thiết lập Crontab (Lịch bảo trì tự động)
Để máy trạm tự động kiểm tra bản cập nhật vào 3 giờ sáng mỗi ngày:
1. Gõ lệnh: `crontab -e`
2. Thêm dòng này vào cuối cùng và lưu lại:
   ```bash
   0 3 * * * /bin/bash /home/toan/toanDoc/Documents/my-nvr/deploy/edge_ota_update.sh v1.0.1 >> /home/toan/toanDoc/Documents/my-nvr/logs/ota.log 2>&1
   ```
   *Lưu ý: Bạn có thể thay `v1.0.1` thành phiên bản bạn muốn máy trạm giữ ổn định.*

---

## 3. CƠ CHẾ SELF-HEALING (TỰ PHỤC HỒI)
Script `/deploy/edge_ota_update.sh` đã được thiết kế cực kỳ an toàn:
- **Nếu cập nhật thành công:** Nó sẽ bắn tin nhắn Telegram cho bạn (ID: `6655437760`).
- **Nếu code mới bị lỗi (Crash/Không lên hình):** Script sẽ phát hiện ra ngay lập tức, **tự động Rollback** về bản cũ đang chạy ổn định và gửi Log lỗi về Telegram để bạn sửa trên máy chủ.
- **Dọn rác:** Sau mỗi lần cập nhật thành công, nó tự xoá các Docker Image cũ để tránh đầy ổ cứng máy trạm.

---

## 4. XỬ LÝ SỰ CỐ
- **Xem Log cập nhật:** `cat /home/toan/toanDoc/Documents/my-nvr/logs/ota.log`
- **Chạy thử ngay (Không đợi 3h sáng):**
  ```bash
  /bin/bash /home/toan/toanDoc/Documents/my-nvr/deploy/edge_ota_update.sh v1.0.1
  ```

Chúc bạn vận hành hệ thống NVR mượt mà trên mọi công trình!
