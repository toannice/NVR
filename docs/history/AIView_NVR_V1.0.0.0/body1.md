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

Script tự động thực hiện:

- Cài Docker không cần internet (từ binary trong bundle)
- Load toàn bộ Docker images vào Docker local
- Tạo cấu trúc thư mục `/opt/nvr/`
- Sinh file `.env` (tự phát hiện IP máy, điền `FRIGATE_HOST_IP`)
- Hỏi chọn chế độ cập nhật: **Offline** hoặc **OTA** (kéo tự động từ GHCR)
- Tạo self-signed TLS cert cho HTTPS
- Hỏi secrets: `gmail_app_password`, `edge_agent_token`, `nebula_sign_token` (Enter để bỏ qua, điền sau trong `/opt/nvr/secrets/`)
- Khởi động toàn bộ stack

## Bước 5 — Kiểm tra

Truy cập `https://<IP_MÁY>` trên trình duyệt (bỏ qua cảnh báo cert tự ký).

Đăng nhập vào giao diện NVR → xác nhận hiển thị bình thường.

```bash
/opt/nvr/nvr.sh ps
```

Tất cả container phải ở trạng thái **Up**.

# Cập nhật về sau

**Chế độ Offline:** Tải bundle phiên bản mới → giải nén → `sudo ./deploy-offline.sh`. Script tự giữ lại `.env` và secrets cũ, chỉ load image mới và restart.

**Chế độ OTA:** Watchdog tự kiểm tra và kéo image mới từ GHCR khi có bản cập nhật, không cần thao tác thủ công.
