[LOGO AIVIEW — căn giữa]

| Phiên bản | Ngày       |
|:---------:|:----------:|
| 0.0.0.1   | 01/06/2026 |

Thank you for choosing our products. If there are any questions or requests,
please do not hesitate to contact us at aiview@bkav.com.

# Changelog & Release Notes — AIView NVR

Tài liệu số: NVR-DOC03-V0.0.0.1

Đơn vị: AIView Dev Team

Ngày viết: 01/06/2026

Ngày ban hành tài liệu: 01/06/2026

Được soạn bởi

AIView Dev Team

Địa chỉ: Tầng 2 toà nhà HH1, Phường Yên Hoà, Quận Cầu Giấy, Thành phố Hà Nội.

| Người tổng hợp    | Người duyệt             |
|-------------------|-------------------------|
| AIView Dev Team   | AIView Product Manager  |

## Lịch sử sửa đổi

| Bản sửa đổi | Ngày       | Mô tả                                              |
|:-----------:|:----------:|:--------------------------------------------------:|
| 0.0.0.1     | 01/06/2026 | Khởi tạo changelog cho AIView NVR v0.0.0.1        |

## Mục lục

1. [Tổng quan các phiên bản](#1-tổng-quan-các-phiên-bản)
2. [Chi tiết theo Module](#2-chi-tiết-theo-module)
3. [Chi tiết đầy đủ theo Version](#3-chi-tiết-đầy-đủ-theo-version)

[LOGO AIVIEW — căn giữa]
The Future of AI Camera

---

## 1. Tổng quan các phiên bản

| Version | Ngày phát hành | Số thay đổi | Tóm tắt |
|---------|----------------|-------------|---------|
| 0.0.0.1 | 01/06/2026     | 20+         | Phiên bản khởi tạo — tích hợp đầy đủ stack NVR + cloud sync + security hardening |

---

## 2. Chi tiết theo Module

### Camera Management

**Version 0.0.0.1 — 01/06/2026**
- Có: Thêm camera qua RTSP URL trực tiếp
- Có: Thêm camera qua ONVIF scan
- Có: PTZ control (manual toggle, ContinuousMove mượt mà)
- Có: Unified source of truth — Web UI và cloud dashboard đọc cùng bảng `Cameras`
- Có: RTSP URL validation (SSRF prevention)
- Có: Auto-detect Intel GPU (không hardcode hwaccel_args)

### Cloud Sync (003)

**Version 0.0.0.1 — 01/06/2026**
- Có: MQTT kênh `channel_service` — camera CRUD từ cloud dashboard
- Có: WebRTC LIVE — stream video qua go2rtc
- Có: WebRTC DATA — device info qua dc-bridge (Go Pion sidecar)
- Có: `DcBridgeManager` — auto-spawn dc-bridge binary khi backend khởi động
- Có: `DataChannelManagerDesktop` — HTTP client tới dc-bridge port 8766
- Có: `POST /internal/dc/command` endpoint cho dc-bridge callback

### Security

**Version 0.0.0.1 — 01/06/2026**
- Có: bcrypt hashing (cost 12) cho pattern lock
- Có: SHA-256 legacy fallback + auto-upgrade khi login
- Có: RTSP URL validation chặn SSRF (loopback, link-local, `host.docker.internal`)
- Có: CameraConfigMutex — mutex bảo vệ config.yml read-write concurrent
- Có: Gmail App Password qua Docker secret (không trong .env)
- Có: OTP persistence trong SQLite (không in-memory)

### Authentication (Pattern Lock + OTP)

**Version 0.0.0.1 — 01/06/2026**
- Có: Pattern lock UI (draw pattern 2 lần để setup)
- Có: Gmail OTP flow (send → verify → setup/reset)
- Có: Reset flow qua OTP email
- Có: OTP stored in `otp_tokens` SQLite table

### Watchdog & Health

**Version 0.0.0.1 — 01/06/2026**
- Có: Self-healing (auto restart unhealthy containers)
- Có: OTA update cho nvr-wrapper
- Có: Health check schedule (9 AM daily)
- Có: Telegram alert on incidents

---

## 3. Chi tiết đầy đủ theo Version

### Version 0.0.0.1 — 01/06/2026

**Loại:** Initial release  
**Breaking changes:** Không (phiên bản đầu tiên)

#### ✨ Tính năng mới

- **Camera RTSP/ONVIF:** Thêm camera thủ công qua RTSP URL hoặc ONVIF scan từ Web UI
- **PTZ Control:** Manual toggle và ContinuousMove mượt mà cho camera hỗ trợ PTZ
- **Tab RTSP trực tiếp:** Thêm tab RTSP bên cạnh ONVIF trong modal thêm camera
- **Cloud Data Sync (003):** 3 kênh dữ liệu cloud — MQTT + WebRTC LIVE + WebRTC DATA
- **dc-bridge sidecar:** Go Pion binary để handle WebRTC DATA channel trên Linux headless
- **Unified Cameras table:** Web UI và cloud dashboard dùng chung bảng `Cameras` (bỏ `CloudCameras`)
- **Pattern Lock:** Bảo vệ Web UI bằng pattern lock + Gmail OTP reset
- **Gmail OTP:** Gửi OTP qua Gmail SMTP, lưu SQLite, verify trước khi setup/reset
- **Watchdog:** Self-healing + OTA tự động cho nvr-wrapper
- **HTTPS:** nginx reverse proxy với SSL certificate
- **Docker Compose stack:** 5 services — mosquitto, frigate, nvr-wrapper, nvr-watchdog, nvr-nginx

#### 🔧 Cải tiến (so với prototype trước)

- **GPU auto-detect:** Tự động detect Intel iGPU, không hardcode `hwaccel_args` global
- **go2rtc sync:** `Go2RtcClient` PUT/DELETE với 5s timeout, đợi 200 OK trước khi trả response
- **Frigate restart feedback:** API trả `frigateRestarted: true/false`, UI hiện toast warning nếu thất bại
- **Config mutex:** `CameraConfigMutex` ngăn race condition khi nhiều client thêm/xóa camera đồng thời
- **bcrypt upgrade:** Pattern hash nâng cấp từ SHA-256 lên bcrypt cost-12; legacy hash tự migrate transparent

#### 🐛 Sửa lỗi

- **Camera offline sau khi thêm:** Ghi đè `hwaccel_args: []` ở camera-level khi host không có Intel QSV
- **nginx không tự start:** Tách `docker compose up -d nginx` thành bước riêng trong deploy guide
- **OTP input validate như email:** Dùng `isOtp?: boolean` param tường minh thay vì check title string
- **dc-bridge port hardcode:** Patch `sed` đổi port 8080 → 3000 trong `main.go` trước khi build

#### ⚠️ Lưu ý khi triển khai

- **dc-bridge binary bắt buộc** nếu muốn cloud dashboard hiện Device info / Ethernet / Time
- **`otp_tokens` table** phải tồn tại trên DB — tạo thủ công nếu dùng DB cũ:
  ```sql
  CREATE TABLE IF NOT EXISTS otp_tokens (
    email VARCHAR(255) PRIMARY KEY,
    code VARCHAR(6) NOT NULL,
    expiry BIGINT NOT NULL
  );
  ```
- **Frigate version chốt 0.16.3** — không tự OTA, watchdog không nâng cấp Frigate
- **`docker compose up -d nginx`** phải chạy riêng sau khi deploy nvr-wrapper

---

*Tài liệu được tạo tự động từ codebase AIView NVR ngày 01/06/2026.*
