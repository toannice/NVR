# Thông tin dự án

- **Tên project:** AIView NVR
- **Phiên bản:** 1.0.0.0
- **Mô tả:** Hệ thống NVR (Network Video Recorder) chạy trên Ubuntu, tích hợp AI phát hiện sự kiện camera, quản lý qua Web UI và đồng bộ với cloud AIView.

# Yêu cầu Phần cứng

| Thành phần | Tối thiểu | Khuyến nghị |
|------------|-----------|-------------|
| CPU | x86_64, 4 core | 8 core trở lên |
| RAM | 8 GB | 16 GB |
| Ổ cứng | 100 GB (SSD) | 500 GB+ (SSD, cho recordings) |
| GPU | Không bắt buộc | Intel QSV (hardware accelerate H.264) |
| Network | 100 Mbps | 1 Gbps (nhiều camera 4K) |

# Yêu cầu Hệ điều hành

- **OS:** Ubuntu 20.04 LTS / 22.04 LTS / 24.04 LTS (x86_64)
- **Quyền:** sudo / root khi build và deploy
- **Docker:** 24.0+ (tự cài bởi `deploy-offline.sh` nếu chưa có)

# Stack Kỹ thuật

## Backend

| Thành phần | Phiên bản | Ghi chú |
|-----------|-----------|---------|
| Kotlin | 2.0 | JVM target 21 |
| JDK | Eclipse Temurin 21 | Build + runtime |
| Ktor | 3.0.1 | HTTP server + WebSocket |
| Exposed | 0.41+ | SQLite ORM |
| Gradle | 8.x | Build tool |

## Frontend

| Thành phần | Phiên bản | Ghi chú |
|-----------|-----------|---------|
| Node.js | 22 LTS | Build React |
| React | 18 | UI framework |
| TypeScript | 5.x | |
| Vite | 5.x | Bundler |

## Infrastructure (Docker images)

| Container | Image | Ghi chú |
|-----------|-------|---------|
| `frigate` | `ghcr.io/blakeblackshear/frigate:0.16.3` | NVR engine — chốt version |
| `mosquitto` | `eclipse-mosquitto:2` | MQTT broker |
| `nginx` | `nginx:stable-alpine` | HTTPS reverse proxy |
| `nvr-wrapper` | Build local / GHCR | App chính |
| `nvr-watchdog` | Build local | Self-healing + OTA |

## Thư viện chính (Backend)

- `ktor-server-netty`, `ktor-server-websockets`
- `ktor-client-cio`, `ktor-client-websockets`
- `exposed-core`, `exposed-jdbc`, `sqlite-jdbc`
- `kotlinx-serialization-json`
- `eclipse-paho-client-mqttv3`
- `bcrypt` (password hashing)
- `snakeyaml` (Frigate config)

# Môi trường Build (Dành cho Dev)

## Yêu cầu trên máy dev

```bash
# JDK 21
java -version   # phải hiện openjdk 21.x

# Node.js 22
node --version  # phải hiện v22.x

# Docker + Compose plugin
docker --version
docker compose version

# Python 3 + python-docx (tạo tài liệu)
python3 -m pip install python-docx
```

## Build image (từ source)

```bash
git clone https://github.com/toannice/NVR.git my-nvr
cd my-nvr
cp .env.template .env     # chỉnh sửa các biến cần thiết
docker compose build nvr-wrapper
docker compose up -d && docker compose up -d nginx
```

## Chu trình phát triển thông thường

```bash
# Sau khi sửa code
./run.sh --rebuild        # build + recreate + nginx, một lệnh
```

# Biến môi trường quan trọng

| Biến | Mô tả | Giá trị mẫu |
|------|-------|------------|
| `FRIGATE_HOST_IP` | IP host Ubuntu (WebRTC candidates) | `192.168.1.100` |
| `TZ` | Timezone | `Asia/Ho_Chi_Minh` |
| `WRAPPER_TAG` | OTA channel: latest / staging / vX.Y.Z | `latest` |
| `GITHUB_TOKEN` | Pull image từ GHCR | `ghp_xxxx` |
| `GMAIL_USER` | Gmail SMTP (OTP) | `yourmail@gmail.com` |
| `TELEGRAM_BOT_TOKEN` | Alert watchdog | `bot_token` |

Secrets lưu trong `secrets/` (Docker secrets, không commit):

- `gmail_app_password` — Gmail App Password
- `edge_agent_token` — Fleet token cho Pi gateway
- `nebula_sign_token` — Token ký cert Nebula

# Lưu ý

- Intel QSV (`preset-intel-qsv-h264`) chỉ hoạt động khi host có Intel GPU. Camera trên máy NVIDIA phải override `hwaccel_args: []` ở cấp camera.
- `docker-compose.override.yml` chỉ tồn tại trên máy dev (git-ignored) — máy prod không có file này, compose kéo GHCR image.
- Chỉ dùng `--no-cache` khi cache thực sự bẩn (đổi base image, sửa Dockerfile).
