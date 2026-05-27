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
| `nvr-wrapper` | `nvr-wrapper:latest` (override bởi docker-compose.override.yml) | 3000 (host) | App chính (Kotlin backend + React frontend) |
| `nvr-watchdog` | build local `./watchdog` | — | Self-healing + OTA |
| `nvr-nginx` | `nginx:stable-alpine` | 443 (host) | HTTPS gateway |
| `cloudflared` | `cloudflare/cloudflared:latest` | — | Remote tunnel |

---

## Build & Deploy

### CRITICAL: docker-compose.override.yml
`docker-compose.override.yml` auto-merges và override `nvr-wrapper` thành `image: nvr-wrapper:latest` + `build: .`.
**LUÔN dùng `docker compose build`** — KHÔNG dùng `docker build -t ghcr.io/toannice/nvr:staging .` vì sẽ build sai tag.

### Build image nvr-wrapper (app chính)
```bash
# Phải chạy từ /home/toan/my-nvr (project root)
docker compose build --no-cache nvr-wrapper
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx   # nginx không tự start khi nvr-wrapper recreate
```

### Build watchdog
```bash
docker compose up -d --build nvr-watchdog
```

### Khởi động toàn bộ stack
```bash
docker compose up -d
docker compose up -d nginx   # nginx KHÔNG phụ thuộc watchdog, start riêng
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
| `frontend/src/components/PatternLockScreen.tsx` | Pattern lock, setup, Gmail OTP flow (~442 dòng) |
| `backend/src/main/kotlin/vn/aiview/nvr/routes/CameraRouting.kt` | API thêm/xóa/sửa camera, sinh config Frigate; RTSP validation, config mutex |
| `backend/src/main/kotlin/vn/aiview/nvr/routes/LockRouting.kt` | Pattern lock API, OTP verify endpoint |
| `backend/src/main/kotlin/vn/aiview/nvr/routes/OnvifRouting.kt` | Scan ONVIF, thêm camera qua ONVIF |
| `backend/src/main/kotlin/vn/aiview/nvr/services/EmailService.kt` | Gmail SMTP, OTP send/peek/verify với SQLite |
| `backend/src/main/kotlin/vn/aiview/nvr/models/Schema.kt` | DB schema: LockConfig, OtpTokens, AIEvents, ... |
| `backend/src/main/kotlin/vn/aiview/nvr/plugins/Databases.kt` | SQLite init, createMissingTablesAndColumns |
| `backend/src/main/kotlin/vn/aiview/nvr/plugins/Security.kt` | hashString (SHA-256), hashPattern (bcrypt), verifyPattern (bcrypt + legacy fallback) |
| `config/config.yml` | Frigate config (cameras, go2rtc, ffmpeg global) |
| `config/watchdog.yml` | Watchdog config (thresholds, OTA, schedule) |
| `watchdog/src/main/kotlin/...` | Watchdog: DockerApi, SelfHealingEngine, UpdateManager |
| `docker-compose.yml` | Định nghĩa toàn bộ stack |
| `docker-compose.override.yml` | QUAN TRỌNG: Override nvr-wrapper image → nvr-wrapper:latest |
| `.env` | Secrets (KHÔNG commit) |
| `secrets/gmail_app_password` | Docker secret file cho Gmail (KHÔNG commit, git-ignored) |
| `nginx/nginx.conf` | HTTPS reverse proxy config |

---

## Gmail OTP Flow (Đã hoàn thiện)

Setup flow:
1. Draw pattern → confirm pattern (2 lần khớp) → email entry screen
2. Enter Gmail → POST `/api/lock/send-otp` → Email sent → OTP screen
3. Enter 6-digit OTP → POST `/api/lock/verify-otp` (peek, not consume) → if valid → POST `/api/lock/setup`
4. `verifyOtp()` consumes OTP on final submit

Reset flow:
1. POST `/api/lock/reset-request` with email → OTP sent
2. Enter OTP → POST `/api/lock/verify-otp` (peek) → if valid → draw new pattern screen
3. Draw pattern twice → POST `/api/lock/reset-complete`

OTP stored in SQLite `otp_tokens` table (not in-memory). On fresh DB, must create manually:
```sql
CREATE TABLE IF NOT EXISTS otp_tokens (email VARCHAR(255) PRIMARY KEY, code VARCHAR(6) NOT NULL, expiry BIGINT NOT NULL);
```

Gmail credentials:
- `GMAIL_USER` env var (not sensitive, in `.env`)
- `GMAIL_APP_PASSWORD` via Docker secret at `/run/secrets/gmail_app_password`

---

## Password Hashing (Security)

Pattern hash uses **bcrypt** (cost 12, ~300ms). Legacy SHA-256 hashes auto-upgrade on first successful login (transparent migration — no re-setup required).

- `hashPattern(pattern)` → bcrypt hash (for new setup/reset)
- `verifyPattern(input, stored)` → `(Boolean, Boolean)` = (matched, needsRehash)
- `hashString(input)` → SHA-256+salt (still used for email/gmail hash comparison)

---

## RTSP URL Validation (SSRF Prevention)

`validateRtspUrl()` in `CameraRouting.kt` blocks:
- Non-`rtsp://` / `rtsps://` schemes (e.g., `http://`, `file://`)
- Loopback: `127.x.x.x`, `localhost`
- Link-local: `169.254.x.x` (cloud metadata endpoints)
- `host.docker.internal`

Private LAN ranges (`192.168.x.x`, `10.x.x.x`) are **allowed** — cameras legitimately live there.

---

## Config File Mutex

A `kotlinx.coroutines.sync.Mutex` (`configMutex`) in `CameraRouting.kt` wraps all `config.yml` read-modify-write blocks. Prevents race conditions when multiple clients add/delete cameras or update AI config concurrently.

---

## Frigate Config Notes

- **Global hwaccel:** `preset-intel-qsv-h264` — chỉ hoạt động nếu host có Intel GPU.
- **RTSP camera thêm thủ công:** phải ghi đè `hwaccel_args: []` ở cấp camera để tránh crash trên máy không có Intel GPU (ví dụ: máy NVIDIA).
- **go2rtc streams:** mỗi camera RTSP được thêm vào `go2rtc.streams` với `#tcp` để tránh UDP drop/lag.
- **Frigate version:** chốt cứng `0.16.3`, watchdog không OTA Frigate.

---

## Thêm Camera: Luồng dữ liệu

1. User nhập thông tin trên UI (tab ONVIF hoặc tab RTSP trực tiếp)
2. Frontend gọi API `/api/add-camera` (POST)
3. `CameraRouting.kt` validate RTSP URL → acquire mutex → đọc/sửa/ghi `config/config.yml` → release mutex
4. Backend gọi `POST http://localhost:5000/api/restart` → trả về `frigateRestarted: true/false`
5. Frontend hiện warning toast nếu `frigateRestarted === false`
6. Frigate kết nối RTSP qua go2rtc → restream nội bộ trên `rtsp://127.0.0.1:8554/<slug>`

---

## Known Issues & Fixes

| Vấn đề | Nguyên nhân | Fix |
|--------|-------------|-----|
| Camera offline ngay sau khi thêm RTSP | Global `hwaccel_args: preset-intel-qsv-h264` không tương thích GPU NVIDIA | Thêm `hwaccel_args: []` vào camera-level config |
| Site unreachable sau deploy nvr-wrapper | nginx không phụ thuộc nvr-wrapper, không tự start | `docker compose up -d nginx` |
| Container dùng image cũ | `docker-compose.override.yml` override image + Docker cache | `docker compose build --no-cache nvr-wrapper` |
| Build không lấy code mới | Docker layer cache | `docker compose build --no-cache nvr-wrapper` |
| GHCR pull fail | GitHub token hết hạn | `echo <token> \| docker login ghcr.io -u toannice --password-stdin` |
| OTP input bị browser validate như email | `renderInput()` dùng `title.includes('Mã')` — fragile | Dùng `isOtp?: boolean` param tường minh |
| `otp_tokens` table không tự tạo trên DB cũ | Exposed WAL behavior | Tạo thủ công bằng sqlite3 |

---

## Security Status

| # | Item | Status |
|---|------|--------|
| 1 | Remove test@test.com bypass | ✅ Done |
| 2 | RTSP URL validation (SSRF) | ✅ Done |
| 3 | File lock on config read-write | ✅ Done |
| 4 | bcrypt/Argon2 password hashing | ✅ Done |
| 5 | Frigate restart feedback to UI | ✅ Done |
| 6 | Gmail OTP persistence (SQLite) | ✅ Done |
| 7 | Docker secrets for GMAIL_APP_PASSWORD | ✅ Done |

---

## Environment Variables (.env)

NEVER commit `.env`. Các biến quan trọng:
- `FRIGATE_HOST_IP` — IP của host Ubuntu (dùng cho WebRTC candidates)
- `TZ=Asia/Ho_Chi_Minh`
- `WRAPPER_TAG` — tag image nvr-wrapper (thực tế override bởi docker-compose.override.yml → `nvr-wrapper:latest`)
- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` — Watchdog alert
- `GITHUB_TOKEN` — Pull image từ GHCR (cần refresh khi hết hạn)
- `CLOUDFLARE_TUNNEL_TOKEN` — Remote access tunnel
- `GMAIL_USER` — Gmail address (not sensitive)
- `GMAIL_APP_PASSWORD` — **Moved to `secrets/gmail_app_password` Docker secret** (NOT in .env)

---

## Network

- `nvr-wrapper`, `nginx`, `nvr-watchdog`, `cloudflared` dùng `network_mode: host` (cần cho ONVIF multicast và RTSP)
- `mosquitto`, `frigate` dùng bridge network `nvr-backend`
- Frigate có thể reach Tailscale/VPN IPs (100.64.x.x) từ trong container
