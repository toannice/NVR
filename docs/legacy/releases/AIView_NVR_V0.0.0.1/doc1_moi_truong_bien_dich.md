[LOGO AIVIEW — căn giữa]

| Phiên bản | Ngày       |
|:---------:|:----------:|
| 0.0.0.1   | 01/06/2026 |

Thank you for choosing our products. If there are any questions or requests,
please do not hesitate to contact us at aiview@bkav.com.

# Tài liệu Cấu hình Môi trường Biên dịch — AIView NVR

Tài liệu số: NVR-DOC01-V0.0.0.1

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

| Bản sửa đổi | Ngày       | Mô tả                                                                          |
|:-----------:|:----------:|:------------------------------------------------------------------------------:|
| 0.0.0.1     | 01/06/2026 | Khởi tạo tài liệu môi trường biên dịch cho AIView NVR v0.0.0.1               |

## Mục lục

1. [Thông tin dự án](#1-thông-tin-dự-án)
2. [Yêu cầu Phần cứng](#2-yêu-cầu-phần-cứng)
3. [Yêu cầu Hệ điều hành](#3-yêu-cầu-hệ-điều-hành)
4. [Môi trường Runtime & SDK](#4-môi-trường-runtime--sdk)
5. [Thư viện & Dependencies](#5-thư-viện--dependencies)
6. [Công cụ hỗ trợ](#6-công-cụ-hỗ-trợ)
7. [Biến môi trường](#7-biến-môi-trường)
8. [Lưu ý & Cảnh báo](#8-lưu-ý--cảnh-báo)

[LOGO AIVIEW — căn giữa]
The Future of AI Camera

---

## 1. Thông tin dự án

| Mục              | Giá trị                                                                                      |
|------------------|----------------------------------------------------------------------------------------------|
| Tên project      | AIView NVR                                                                                   |
| Version          | 0.0.0.1                                                                                      |
| Ngày tạo         | 01/06/2026                                                                                   |
| Team phụ trách   | AIView Dev Team                                                                              |
| GitHub           | https://github.com/toannice/NVR.git                                                          |
| Mô tả            | Hệ thống NVR chạy trên Ubuntu, tích hợp AI phát hiện sự kiện camera, quản lý qua Web UI và đồng bộ cloud AIView. |

---

## 2. Yêu cầu Phần cứng

| Thành phần | Tối thiểu              | Khuyến nghị            |
|------------|------------------------|------------------------|
| CPU        | x86_64, 4 cores        | x86_64, 8+ cores       |
| RAM        | 4 GB                   | 8 GB+                  |
| Ổ cứng     | 20 GB trống            | 100 GB+ (lưu video)    |
| GPU        | Không bắt buộc         | Intel iGPU (QSV H.264) hoặc NVIDIA |
| Network    | 100 Mbps               | Gigabit LAN            |

> **Lưu ý GPU:** Frigate sử dụng Intel QSV H.264 làm mặc định global. Nếu host dùng GPU NVIDIA hoặc không có Intel iGPU, cần ghi đè `hwaccel_args: []` ở cấp camera trong `config/config.yml`.

---

## 3. Yêu cầu Hệ điều hành

| OS          | Phiên bản tối thiểu | Ghi chú                         |
|-------------|---------------------|---------------------------------|
| Ubuntu      | 22.04 LTS (Jammy)   | Đã kiểm thử, khuyến nghị        |
| Ubuntu      | 20.04 LTS (Focal)   | Tương thích, chưa kiểm thử đủ   |

> Project chỉ hỗ trợ Linux. Windows/macOS không được hỗ trợ do `network_mode: host` và ONVIF multicast.

---

## 4. Môi trường Runtime & SDK

### 4.1 Node.js (Frontend build)

| Mục          | Giá trị        |
|--------------|----------------|
| Version      | Node.js 22 LTS |
| Base image   | `node:22-alpine` (Docker Stage 1) |
| Package mgr  | npm (bundled)  |

### 4.2 Java / Kotlin (Backend)

| Mục          | Giá trị                         |
|--------------|---------------------------------|
| JDK          | Eclipse Temurin 21 (JDK Alpine) |
| JRE runtime  | Eclipse Temurin 21 (JRE Alpine) |
| Kotlin       | 2.0.21                          |
| Build tool   | Gradle (Wrapper, version 8.10.2)|

### 4.3 Docker

| Mục          | Giá trị                |
|--------------|------------------------|
| Docker       | 24.0+ (Engine + CLI)   |
| Compose      | Docker Compose v2 (plugin) |

> Không dùng `docker-compose` legacy (v1). Luôn dùng `docker compose` (v2 plugin).

### 4.4 Go (dc-bridge sidecar — optional)

| Mục     | Giá trị    |
|---------|------------|
| Go      | 1.21+      |
| Target  | linux/amd64, CGO_ENABLED=0 |

Chỉ cần nếu muốn build lại binary `dc-bridge`. Binary pre-built có sẵn tại `data/dc-bridge`.

---

## 5. Thư viện & Dependencies

### 5.1 Backend (Kotlin/Ktor)

| Thư viện                  | Version   | Vai trò                              |
|---------------------------|-----------|--------------------------------------|
| Ktor (server, client)     | 3.0.1     | HTTP server + WebSocket + HTTP client|
| Kotlin Coroutines         | 1.9.0     | Async/concurrency                    |
| Exposed ORM               | 0.56.0    | SQLite ORM                           |
| SQLite JDBC               | 3.47.0.0  | SQLite driver                        |
| SnakeYAML                 | 2.3       | Parse/write `config.yml` Frigate     |
| Paho MQTT Client          | 1.2.5     | MQTT kết nối cloud AIView            |
| Jakarta Mail              | 2.0.1     | Gmail SMTP (OTP)                     |
| Logback                   | 1.4.14    | Logging                              |
| bcrypt (Spring Security)  | bundled   | Bcrypt password hashing              |

### 5.2 Frontend (React/TypeScript)

| Thư viện           | Vai trò                    |
|--------------------|----------------------------|
| React 18           | UI framework               |
| TypeScript         | Type safety                |
| Vite               | Build tool                 |
| Tailwind CSS       | Styling                    |
| shadcn/ui          | Component library          |

> Chi tiết version xem `frontend/package.json`.

### 5.3 Infrastructure (Docker images)

| Service     | Image                                           | Version    |
|-------------|--------------------------------------------------|------------|
| Frigate     | `ghcr.io/blakeblackshear/frigate`               | `0.16.3`   |
| Mosquitto   | `eclipse-mosquitto`                             | `2`        |
| nginx       | `nginx`                                         | `stable-alpine` |

> **Frigate version chốt cứng 0.16.3** — watchdog không OTA Frigate.

---

## 6. Công cụ hỗ trợ

| Tool                  | Mục đích                                  |
|-----------------------|-------------------------------------------|
| IntelliJ IDEA         | IDE chính cho Kotlin backend              |
| VS Code               | Frontend React/TypeScript                 |
| Docker Desktop (Mac)  | Kiểm thử local trên macOS (giới hạn)     |
| sqlite3 CLI           | Inspect/debug SQLite DB                   |
| mosquitto-clients     | Test MQTT (`mosquitto_pub`, `mosquitto_sub`) |
| curl / httpie         | Test REST endpoints                       |

---

## 7. Biến môi trường

File `.env` ở project root (KHÔNG commit vào git):

| Biến                | Mô tả                                          | Giá trị mẫu                    |
|---------------------|------------------------------------------------|--------------------------------|
| `FRIGATE_HOST_IP`   | IP host Ubuntu (dùng cho WebRTC ICE candidates)| `192.168.1.100`                |
| `TZ`                | Timezone                                       | `Asia/Ho_Chi_Minh`             |
| `WRAPPER_TAG`       | Tag image nvr-wrapper (bị override bởi compose override) | `latest`           |
| `TELEGRAM_BOT_TOKEN`| Watchdog alert Telegram                        | `YOUR_BOT_TOKEN_HERE`          |
| `TELEGRAM_CHAT_ID`  | Chat ID nhận alert                             | `YOUR_CHAT_ID_HERE`            |
| `GITHUB_TOKEN`      | Pull image từ GHCR                             | `YOUR_GITHUB_TOKEN_HERE`       |
| `GMAIL_USER`        | Gmail gửi OTP                                  | `your-email@gmail.com`         |

> `GMAIL_APP_PASSWORD` **KHÔNG** đặt trong `.env` — lưu tại `secrets/gmail_app_password` (Docker secret).

---

## 8. Lưu ý & Cảnh báo

| Vấn đề                                     | Nguyên nhân                              | Xử lý                                              |
|--------------------------------------------|------------------------------------------|----------------------------------------------------|
| Camera offline ngay sau khi thêm           | Intel QSV xung đột GPU NVIDIA            | Thêm `hwaccel_args: []` ở cấp camera trong config |
| Site unreachable sau deploy nvr-wrapper    | nginx không tự restart                   | `docker compose up -d nginx`                       |
| Build dùng image cũ                        | Docker layer cache                       | `docker compose build --no-cache nvr-wrapper`      |
| GHCR pull fail                             | GitHub token hết hạn                     | Refresh token, re-login ghcr.io                    |
| `otp_tokens` table không tồn tại           | DB cũ trước khi có migration             | Tạo thủ công bằng sqlite3                          |
| dc-bridge không start                      | Binary thiếu hoặc không executable       | Kiểm tra `data/dc-bridge`, `chmod +x`              |
| `docker-compose` v1 command                | Sai tool                                 | Dùng `docker compose` (v2, không có dấu `-`)       |
| Network `host` trên macOS Docker Desktop   | Docker Desktop không hỗ trợ host network | Chỉ deploy trên Linux host thực                    |
