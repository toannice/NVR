# CLAUDE.md — NVR Project

## Repository
- **GitHub:** https://github.com/toannice/NVR.git
- **Main branch:** `main`
- **Active feature branch:** `001-nvr-aibox-connect`
- **Working directory on host:** `/home/toan/my-nvr`

---

## Stack Overview

Multi-service NVR (Network Video Recorder) chạy trên Ubuntu, deploy bằng Docker Compose.

```
mosquitto (MQTT) → frigate (NVR engine) → nvr-wrapper (Kotlin/React app)
                                                    ↑
                         nvr-watchdog (OTA + health) + nginx (HTTPS) + cloudflared (tunnel)
```

### Services
| Container | Image | Port | Ghi chú |
|-----------|-------|------|---------|
| `mosquitto` | `eclipse-mosquitto:2` | 1883, 9001 | MQTT broker |
| `frigate` | `ghcr.io/blakeblackshear/frigate:0.16.3` | 5000, 8554, 1984 | NVR engine, chốt version, không OTA |
| `nvr-wrapper` | `ghcr.io/toannice/nvr:staging` | 3000 (host) | App chính (Kotlin backend + React frontend) |
| `nvr-watchdog` | build local `./watchdog` | — | Self-healing + OTA |
| `nvr-nginx` | `nginx:stable-alpine` | 443 (host) | HTTPS gateway |
| `cloudflared` | `cloudflare/cloudflared:latest` | — | Remote tunnel |

---

## Build & Deploy

### Build image nvr-wrapper (app chính)
```bash
# Phải chạy từ /home/toan/my-nvr (project root)
docker build --no-cache -t ghcr.io/toannice/nvr:staging .
docker compose up -d --force-recreate --no-deps nvr-wrapper
```
> **Quan trọng:** Luôn dùng `--no-cache` khi source code thay đổi (Docker cache rất aggressive).
> Dùng `--force-recreate --no-deps` để restart đúng container mà không kéo theo dependencies.

### Build watchdog
```bash
docker compose up -d --build nvr-watchdog
```

### Khởi động toàn bộ stack
```bash
docker compose up -d
# nginx KHÔNG phụ thuộc watchdog, phải start riêng nếu cần:
docker compose up -d nginx
```

### Restart Frigate (áp dụng config mới)
```bash
curl -X POST http://localhost:5000/api/restart
```

---

## Dockerfile (Multi-stage)

```
Stage 1: node:22-alpine       → build React frontend (npm run build → dist/)
Stage 2: eclipse-temurin:21-jdk-alpine → build Kotlin backend (./gradlew installDist)
Stage 3: eclipse-temurin:21-jre-alpine → runtime image
```

---

## Codebase

| Path | Mô tả |
|------|-------|
| `frontend/src/App.tsx` | Toàn bộ React UI (file lớn, ~3000+ dòng) |
| `backend/src/main/kotlin/vn/aiview/nvr/routes/CameraRouting.kt` | API thêm/xóa/sửa camera, sinh config Frigate |
| `backend/src/main/kotlin/vn/aiview/nvr/routes/OnvifRouting.kt` | Scan ONVIF, thêm camera qua ONVIF |
| `config/config.yml` | Frigate config (cameras, go2rtc, ffmpeg global) |
| `config/watchdog.yml` | Watchdog config (thresholds, OTA, schedule) |
| `watchdog/src/main/kotlin/...` | Watchdog: DockerApi, SelfHealingEngine, UpdateManager |
| `docker-compose.yml` | Định nghĩa toàn bộ stack |
| `.env` | Secrets (KHÔNG commit) |
| `nginx/nginx.conf` | HTTPS reverse proxy config |

---

## Frigate Config Notes

- **Global hwaccel:** `preset-intel-qsv-h264` — chỉ hoạt động nếu host có Intel GPU.
- **RTSP camera thêm thủ công:** phải ghi đè `hwaccel_args: []` ở cấp camera để tránh crash trên máy không có Intel GPU (ví dụ: máy NVIDIA).
- **go2rtc streams:** mỗi camera RTSP được thêm vào `go2rtc.streams` với `#tcp` để tránh UDP drop/lag.
- **Frigate version:** chốt cứng `0.16.3`, watchdog không OTA Frigate.

---

## Thêm Camera: Luồng dữ liệu

1. User nhập thông tin trên UI (tab ONVIF hoặc tab RTSP trực tiếp)
2. Frontend gọi API `/api/cameras` (POST)
3. `CameraRouting.kt` sinh entry YAML và thêm vào `config/config.yml`
4. Backend gọi `POST http://localhost:5000/api/restart` để Frigate reload config
5. Frigate kết nối RTSP qua go2rtc → restream nội bộ trên `rtsp://127.0.0.1:8554/<slug>`

---

## Known Issues & Fixes

| Vấn đề | Nguyên nhân | Fix |
|--------|-------------|-----|
| Camera offline ngay sau khi thêm RTSP | Global `hwaccel_args: preset-intel-qsv-h264` không tương thích GPU NVIDIA | Thêm `hwaccel_args: []` vào camera-level config |
| Site unreachable sau deploy | nginx không phụ thuộc watchdog, không tự start | `docker compose up -d nginx` |
| Container dùng image cũ | Docker cache, container không recreate | `--force-recreate --no-deps` |
| Build không lấy code mới | Docker layer cache | `docker build --no-cache` |
| GHCR pull fail | GitHub token hết hạn | `echo <token> \| docker login ghcr.io -u toannice --password-stdin` |

---

## Environment Variables (.env)

Không commit `.env`. Các biến quan trọng:
- `FRIGATE_HOST_IP` — IP của host Ubuntu (dùng cho WebRTC candidates)
- `TZ=Asia/Ho_Chi_Minh`
- `WRAPPER_TAG` — tag image nvr-wrapper (mặc định: `staging`)
- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` — Watchdog alert
- `GITHUB_TOKEN` — Pull image từ GHCR (cần refresh khi hết hạn)
- `CLOUDFLARE_TUNNEL_TOKEN` — Remote access tunnel

---

## Network

- `nvr-wrapper`, `nginx`, `nvr-watchdog`, `cloudflared` dùng `network_mode: host` (cần cho ONVIF multicast và RTSP)
- `mosquitto`, `frigate` dùng bridge network `nvr-backend`
- Frigate có thể reach Tailscale/VPN IPs (100.64.x.x) từ trong container
