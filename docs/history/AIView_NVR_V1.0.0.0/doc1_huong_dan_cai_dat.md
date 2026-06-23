# Hướng dẫn Cài đặt AIView NVR — V1
**Phiên bản:** 1.0.0.0 | **Ngày:** 17/06/2026 | **Người soạn:** Bkav AIView Team | **Duyệt:** HaDM

---

# Yêu cầu

- Máy đích: Ubuntu 20.04 / 22.04 / 24.04, quyền sudo
- Không bắt buộc có internet — bundle đã bao gồm mọi thứ cần thiết
- Ổ cứng trống: tối thiểu 15 GB (Docker images ~10 GB + recordings)

# Các bước cài đặt

## Bước 1 — Tải file bundle

Tải file `V1.0.0.0-YYYYMMDD.tar.gz` từ thư mục **Release Version V1.0.0.0** trên Google Drive (xem link trong `release_info.md`).

## Bước 2 — Chuyển file sang máy đích

**Qua mạng nội bộ:**
```bash
scp V1.0.0.0-*.tar.gz user@<IP_MÁY_ĐÍCH>:~/
```
**Không có mạng (air-gapped):** Dùng USB.

## Bước 3 — Giải nén
```bash
tar -xzf V1.0.0.0-*.tar.gz
cd nvr-bundle-*
```

## Bước 4 — Chạy script triển khai
```bash
sudo ./deploy-offline.sh
```

Script tự động: cài Docker offline → load images → tạo `/opt/nvr/` → sinh `.env` → chọn chế độ OTA/offline → tạo TLS cert → điền secrets → khởi động stack.

## Bước 5 — Kiểm tra

Truy cập `https://<IP_MÁY>` (bỏ qua cảnh báo cert tự ký). Đăng nhập → giao diện NVR hiển thị bình thường.

```bash
/opt/nvr/nvr.sh ps   # tất cả container phải Up
```

# Cập nhật về sau

- **Offline:** bundle mới → giải nén → `sudo ./deploy-offline.sh`
- **OTA:** Watchdog tự kéo image mới từ GHCR, không cần thao tác
