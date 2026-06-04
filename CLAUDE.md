# CLAUDE.md — NVR Project

## Repository
- **GitHub:** https://github.com/toannice/NVR.git
- **Main branch:** `main`
- **Active feature branch:** `003-cloud-data-sync`
- **Active feature plan:** `specs/003-cloud-data-sync/plan.md`
<!-- SPECKIT START -->
- **Plan:** `specs/003-cloud-data-sync/plan.md`
<!-- SPECKIT END -->
- **Working directory on host:** `/home/toan/my-nvr`

---

## Stack Overview

Multi-service NVR (Network Video Recorder) chạy trên Ubuntu, deploy bằng Docker Compose.

```
mosquitto (MQTT) → frigate (NVR engine) → nvr-wrapper (Kotlin/React app)
                                                    ↑
                         nvr-watchdog (OTA + health) + nginx (HTTPS)
```

### Services
| Container | Image | Port | Ghi chú |
|-----------|-------|------|---------|
| `mosquitto` | `eclipse-mosquitto:2` | 1883, 9001 | MQTT broker |
| `frigate` | `ghcr.io/blakeblackshear/frigate:0.16.3` | 5000, 8554, 1984 | NVR engine, chốt version, không OTA |
| `nvr-wrapper` | `nvr-wrapper:latest` (override bởi docker-compose.override.yml) | 3000 (host) | App chính (Kotlin backend + React frontend) |
| `nvr-watchdog` | build local `./watchdog` | — | Self-healing + OTA |
| `nvr-nginx` | `nginx:stable-alpine` | 443 (host) | HTTPS gateway |

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
| `backend/src/main/kotlin/vn/aiview/nvr/routes/CameraRouting.kt` | Local Web UI camera CRUD endpoints; delegates persistence to `CameraService` |
| `backend/src/main/kotlin/vn/aiview/nvr/services/CameraService.kt` | Unified add/update/remove path used by Web UI AND cloud handler (003) |
| `backend/src/main/kotlin/vn/aiview/nvr/services/CameraConfigMutex.kt` | Shared mutex for all config.yml + Cameras DB writes |
| `backend/src/main/kotlin/vn/aiview/nvr/cloud/Go2RtcClient.kt` | Sync PUT/DELETE against go2rtc REST API on 127.0.0.1:1984 |
| `backend/src/main/kotlin/vn/aiview/nvr/cloud/DeviceConstants.kt` | Placeholder model/firmware/storage values returned to AIView cloud |
| `backend/src/main/kotlin/vn/aiview/nvr/cloud/DcBridgeManager.kt` | Spawn `dc-bridge` Go sidecar; resolves binary from `DC_BRIDGE_PATH` env / `/app/data/dc-bridge` / `~/.aiview/dc-bridge` |
| `backend/src/main/kotlin/vn/aiview/nvr/cloud/DataChannelManagerDesktop.kt` | Linux WebRTC DATA peer — HTTP client to dc-bridge on `127.0.0.1:8766` |
| `backend/src/main/kotlin/vn/aiview/nvr/routes/CloudRouting.kt` | `POST /internal/dc/command` — dc-bridge callback into CommandDispatcher |
| `data/dc-bridge` | Go Pion WebRTC sidecar binary (linux/amd64), required for device_service DATA queries |
| `connection_to_server.md` | Full guide: how a Linux device connects to AIView cloud + dc-bridge build steps |
| `edge-agent/` | **Gateway (4-6 Plan A):** Go agent on Raspberry Pi — passive L4 proxy (nginx stream), WS-Discovery, field web page. HTTP server only; never calls NVR. |
| `backend/.../gateway/EdgeAgentClient.kt` | NVR→agent HTTP client (bearer fleet token, timeout+retry) |
| `backend/.../gateway/GatewayConfig.kt` | NVR gateway settings: scan subnet, agent port, fleet token (`/run/secrets/edge_agent_token`) |
| `backend/.../services/GatewayScanner.kt` | Auto-scans `ZT_SCAN_SUBNET` /health to discover gateways (the "auto connect"; gateway never registers) |
| `backend/.../services/GatewayService.kt` | Gateway approve + adopt camera (admin types creds; reuses `CameraService.applyAndPersistFromOnvif`) |
| `backend/.../routes/GatewayRouting.kt` | `/api/gateways*` — list/scan/approve/discover/adoptable/adopt. No register/push endpoint. |
| `frontend/src/components/GatewaysPanel.tsx` | Web UI "Gateway" tab: discover → approve → adopt |
| `gateway_setup.md` | Full gateway setup guide (Pi agent install, ZeroTier authorize, NVR flow, troubleshooting) |
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

## Thêm Camera: Luồng dữ liệu (003 — unified)

Cả Web UI lẫn cloud dashboard giờ đi qua **một** code path: `CameraService.applyAndPersist*`.

1. User nhập thông tin (Web UI tab ONVIF/RTSP, hoặc cloud dashboard `post_channels`)
2. `CameraRouting.kt#addCamera` (Web UI) hoặc `CameraCommandHandler.kt#postOrPutChannels` (cloud) parse request
3. → `CameraService.applyAndPersistFromRtsp` (Web UI, RTSP URL đã có) hoặc `applyAndPersistFromOnvif` (cloud, ONVIF probe trước)
4. `CameraService` acquire `CameraConfigMutex.mutex`, làm tuần tự:
   - UPSERT row vào bảng `Cameras` (unified — không còn `CloudCameras`); gán `channel_id` nếu mới
   - Ghi `config/config.yml` (cameras + go2rtc.streams entry)
   - `Go2RtcClient.registerStreamSync("${channelId}_main", ...)` — chờ go2rtc PUT 200 OK (5s timeout)
5. Trigger `POST http://localhost:5000/api/restart` → trả `frigateRestarted: true/false`
6. Web UI hiện toast warning nếu `frigateRestarted === false`; cloud trả về `body.data.frigate_restarted`
7. Cloud `get_channels` đọc cùng bảng `Cameras` → cloud và Web UI luôn thấy danh sách giống nhau

Delete đi qua `CameraService.removeAndPersist(slugOrChannelId)` — idempotent, xóa DB + YAML + go2rtc stream + trigger Frigate restart.

---

## Cloud Connection — Linux Device

Chi tiết đầy đủ: **[connection_to_server.md](./connection_to_server.md)**.

Tóm tắt 3 đường data đi tới cloud:

| Kênh | Vận chuyển | Loại dữ liệu | Phụ thuộc |
|---|---|---|---|
| MQTT | tcp 1883 | Camera CRUD (`channel_service`), heartbeat | mosquitto broker reach được cloud |
| WebRTC LIVE | UDP/TCP qua ICE | Stream video camera | `go2rtc` ở `127.0.0.1:1984` |
| WebRTC DATA | UDP/TCP qua ICE | **Device info, ethernet, time** (`device_service`) | **Binary `dc-bridge`** ở `data/dc-bridge` |

### dc-bridge (BẮT BUỘC để dashboard hiện Device info)

Cloud KHÔNG hỏi `get_information / get_ethernet / get_time` qua MQTT — chỉ qua WebRTC DATA channel. JVM `dev.onvoid.webrtc` không chạy được headless Linux, nên cần sidecar Go (Pion).

**Build cho linux/amd64:**
```bash
SRC=~/Documents/1CodeBkav/B02/aiview_b02_box/dc-bridge
mkdir -p /tmp/dc-bridge-build && cp "$SRC"/{main.go,go.mod,go.sum} /tmp/dc-bridge-build/
# Patch port JVM (8080 → 3000 vì backend NVR ở 3000)
sed -i 's|127.0.0.1:8080/internal/dc/command|127.0.0.1:3000/internal/dc/command|' /tmp/dc-bridge-build/main.go
cd /tmp/dc-bridge-build
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o /home/toan/my-nvr/data/dc-bridge -ldflags='-s -w' .
chmod +x /home/toan/my-nvr/data/dc-bridge
docker compose restart nvr-wrapper
```

Verify ở log: `DC_BRIDGE_STARTED | dc-bridge started from /app/data/dc-bridge`.

`DcBridgeManager` spawn binary tự động khi backend khởi động. Reference source + binary arm64 ở `~/Documents/1CodeBkav/B02/aiview_b02_box/dc-bridge/`.

---

## PTZ / ONVIF (kể cả camera qua socat proxy)

Web UI điều khiển PTZ qua `/api/cameras/{slug}/ptz/{capabilities,move,stop}` (`OnvifRouting.kt` + `OnvifSoapClient`). **Một code path cho cả camera LAN trực tiếp lẫn camera sau proxy** — vì `OnvifSoapClient` rewrite authority của ONVIF Media/PTZ XAddr về `ip:onvifPort`.

`getCameraOnvifConfig` resolve creds: **ưu tiên `user:pass` nhúng trong URL RTSP của `config.yml`** (camera LAN), **fallback `Cameras.username` + giải mã `passwordEncrypted`** (camera proxy/cloud có URL RTSP không kèm creds).

**Cấu hình camera qua proxy (Web UI → Sửa Camera):**
- **Cổng ONVIF** = cổng socat proxy (vd `8080`, `8081`...). Mỗi camera field thường có 1 cổng proxy riêng trên gateway.
- **ONVIF Username / Password** (để trống = giữ creds cũ).
- **Kiểu PTZ**: `Tự động` (theo ONVIF `GetNodes`) | `Toàn phần` (ép Pan/Tilt/Zoom) | `Chỉ Zoom`. Lưu ở cột `cameras.ptz_mode`.
- Bật toggle PTZ.

**Vì sao có override "Kiểu PTZ":** một số camera PTZ rẻ **chỉ quảng cáo `ContinuousZoomVelocitySpace` trong `GetNodes`** (không có PanTilt space) dù phần cứng *có thể* pan/tilt qua API riêng → auto-detect ẩn d-pad. Nếu camera thật sự nhận ONVIF PanTilt thì chọn **Toàn phần** để ép hiện d-pad. `/ptz/capabilities` ưu tiên override → trả ngay, không cần probe camera (chạy cả khi proxy/link tạm down). ⚠️ Có camera phần cứng PTZ thật nhưng **không lái pan/tilt bằng ONVIF `ContinuousMove`/GenericSpace** (vd `OSSD2MPVF33XPTZAI`, xem dưới) → ép `full` thì d-pad hiện nhưng lệnh PanTilt vẫn fault "speed out of bounds". Cần tìm đúng phương thức (AbsoluteMove/RelativeMove/space riêng/API riêng).

`PtzControls` (frontend) fetch `/ptz/capabilities` khi mở panel, ẩn d-pad khi `hasPanTilt=false`, ẩn zoom khi `hasZoom=false`.

> Camera zoom-only thật (Bkav P200-0802N): để `Tự động` hoặc `Chỉ Zoom`. Gửi PanTilt cho nó → fault "speed out of bounds".

Chi tiết: **[connection_to_server.md](./connection_to_server.md)** § "ONVIF / PTZ camera field qua socat proxy".

### Gateway Camera Provisioning (Plan A) — ✅ DONE 2026-06-04

> Kế hoạch đầy đủ + verify steps: **[4-6-to-do-jobs.md](./4-6-to-do-jobs.md)**. Hướng dẫn vận hành: **[gateway_setup.md](./gateway_setup.md)**.

**Đã hoàn thành & verify end-to-end trên Pi thật** (gateway-nha-A @ ZT `10.34.24.12`, RAM agent ~4MB): NVR auto-scan subnet ZeroTier → phát hiện gateway (passive, **không push**) → admin approve → adopt camera (`192.168.2.64` → slug `cam_6`): agent cấp cổng nginx-stream onvif 8080 / rtsp 8081, NVR probe ONVIF qua proxy, rewrite RTSP host → `10.34.24.12:8081`, ghi Frigate/go2rtc, **live view lên hình OK**. Multi-NVR ready; Pi không giữ creds (admin gõ lúc approve); fleet token dùng chung (`secrets/edge_agent_token`).

Code: `edge-agent/` (Go), `backend/.../gateway/*`, `services/{GatewayScanner,GatewayService}.kt`, `routes/GatewayRouting.kt`, `CameraService.rewriteRtspHost`+`autoDetectPtz`, `frontend/.../GatewaysPanel.tsx` (tab "Gateway").

**Auto-PTZ (đã hiện thực):** sau adopt, gửi 1 `ContinuousMove` PanTilt nhỏ (x=0.05) + `Stop`, xem SOAP fault → set `cameras.ptz_mode` = `full` (no fault) hoặc `zoom` (fault). KHÔNG đè lựa chọn thủ công. (`OnvifSoapClient.probePanTiltSupported`, `CameraService.autoDetectPtz`.)

**TODO ưu tiên — App mobile "Thêm Gateway vào NVR" (zero hand-touch trên Pi):**
> Mục tiêu user (04/06): **KHÔNG bao giờ phải cấu hình Pi bằng tay**. App mobile chỉ làm **đúng 1 việc**: onboard/đăng ký một gateway vào NVR. Pi nướng image sẵn (agent + ZT network id + fleet token có sẵn) → cắm điện + mạng → app mobile lo phần "thêm vào NVR" (và nếu cần: authorize ZeroTier member, đặt tên gateway). Không quản lý camera trong app này — chỉ thêm gateway. Tái dùng API agent (`/health`, `/discovery`, `/camera`, `/cameras`) + endpoint NVR (`/api/gateways*`). Cần nghĩ: cách app authorize Pi vào ZeroTier (ZT Central API qua NVR?) để bỏ luôn bước thủ công duy nhất còn lại.

**Còn lại / known limitations:**
- ⚠️ **Camera under-report (verified):** `OSSD2MPVF33XPTZAI` (`.64`) là PTZ thật nhưng **không nhận pan/tilt qua ONVIF `ContinuousMove`** (`GetNodes` chỉ có Zoom; PanTilt → fault "speed out of bounds"; Zoom → OK). → auto-PTZ phân loại nó `zoom` (false-negative đã biết). Cần tìm phương thức pan thật (`AbsoluteMove`/`RelativeMove`/space riêng/API vendor) — **chưa làm**.
- **Chưa làm (nice-to-have):** auto-dò `onvifPort` (thử nhiều cổng), tái dùng creds giữa camera cùng gateway, classifier PTZ thử thêm move-type.
- Link ZeroTier chập chờn → mọi call agent đã có timeout ngắn + retry 1 lần.

---

## Known Issues & Fixes

| Vấn đề | Nguyên nhân | Fix |
|--------|-------------|-----|
| Camera offline ngay sau khi thêm RTSP | Global `hwaccel_args: preset-intel-qsv-h264` không tương thích GPU NVIDIA | Thêm `hwaccel_args: []` vào camera-level config |
| Site unreachable sau deploy nvr-wrapper | nginx không phụ thuộc nvr-wrapper, không tự start | `docker compose up -d nginx` |
| Container dùng image cũ | `docker-compose.override.yml` override image + Docker cache | `docker compose build --no-cache nvr-wrapper` |
| Adopt camera qua tab Gateway nhưng KHÔNG thấy trên NVR | List camera của App chỉ fetch lúc tải trang; adopt ở tab Gateway không tự kích hoạt fetch lại | Đã fix: `GatewaysPanel` gọi `onCamerasChanged` (=`fetchCameras`) sau adopt. Camera vẫn được lưu đúng (DB+config.yml); F5 cũng thấy ngay |
| Gateway không hiện trong tab Gateway dù Pi chạy | `ZT_SCAN_SUBNET` chưa set / sai; Pi chưa authorize ở ZeroTier Central; fleet token Pi ≠ `secrets/edge_agent_token` | Xem `docker logs nvr-wrapper \| grep GATEWAY`; chi tiết ở `gateway_setup.md` |
| Build không lấy code mới | Docker layer cache | `docker compose build --no-cache nvr-wrapper` |
| GHCR pull fail | GitHub token hết hạn | `echo <token> \| docker login ghcr.io -u toannice --password-stdin` |
| OTP input bị browser validate như email | `renderInput()` dùng `title.includes('Mã')` — fragile | Dùng `isOtp?: boolean` param tường minh |
| `otp_tokens` table không tự tạo trên DB cũ | Exposed WAL behavior | Tạo thủ công bằng sqlite3 |
| Dashboard cloud online nhưng KHÔNG có Device info / Ethernet / Time | DATA channel fail vì thiếu binary `dc-bridge` | Build dc-bridge linux/amd64 với JVM port=3000, drop vào `data/dc-bridge` — xem `connection_to_server.md` |
| `DC_OFFER_FAIL Connection refused` trong log | dc-bridge chưa start hoặc port 8766 bị chiếm | `docker compose logs nvr-wrapper \| grep dc-bridge`; kiểm tra `DC_BRIDGE_STARTED` |
| dc-bridge start nhưng cloud không nhận response | `jvmCommandURL` trong `main.go` hardcode port 8080, backend NVR ở 3000 | Patch `sed` rồi build lại |
| Cloud dashboard chỉ hiện 2/N cameras dù local UI có nhiều hơn | Cloud server chỉ đếm cameras được thêm qua `post_channels` (cloud dashboard). Cameras thêm qua local Web UI không được cloud server biết đến | Thêm camera từ cloud dashboard; không phải bug trong device code |
| Cloud live view không hoạt động — stream.mp4 500 liên tục | Cameras offline (10.34.24.12 — no route to host) gây go2rtc retry liên tục | Xóa hoặc tắt cameras offline; đây không ảnh hưởng đến cameras online |
| Cloud live view không hoạt động — SDP có `{FRIGATE_HOST_IP}` literal | Frigate 0.16.3 KHÔNG substitute `{FRIGATE_HOST_IP}` trong go2rtc config khi truyền cho go2rtc process. ICE candidate ghi `{FRIGATE_HOST_IP}` thay vì IP thực → cloud browser không parse được | `CloudService.applyIceServersToGo2Rtc()` detect LAN IP qua `detectLanIp()` và replace placeholder; `CameraService.writeYaml()` dùng IP thực từ đầu |
| `GO2RTC REGISTER ok=false` khi MQTT reconnect | Streams đã tồn tại trong go2rtc (load từ config.yml khi Frigate start) nên go2rtc reject PUT → bình thường, streams vẫn hoạt động | Không cần fix — benign |
| Live view ĐEN toàn bộ + site thỉnh thoảng 502 (degrade dần dưới tải live) | `FrigateProxy.kt` `proxyClient` (CIO) đặt `socketTimeout = 0`. Ktor 3.0.1 `io.ktor.network.util.Timeout` CHỈ coi `Long.MAX_VALUE` là vô hạn; `0` → coroutine timeout **busy-spin** (runnable). Mỗi luồng live tạo 1 job spin → bão hòa 64 thread `DefaultDispatcher` → mọi response ghi body (file tĩnh + proxy stream) treo. Triệu chứng đánh lừa: `/api/*` (JSON nhỏ) vẫn 200, `GET /` + stream.mp4 trả empty/000 | Đổi `socketTimeout = Long.MAX_VALUE` (KHÔNG dùng 0). Chẩn đoán: `docker exec nvr-wrapper kill -3 1` → 64 thread kẹt ở `io.ktor.network.util.Timeout$initTimeoutJob`; CPU ~300% |

---

## Cloud Live View — Root Cause & Fix (003)

### Luồng LIVE WebRTC (cloud → device → go2rtc → cloud browser)

1. Cloud browser click Live → MQTT LIVE offer → `SignalingManager.handleLiveOffer()`
2. Resolve `profileToken` → `{chId}_main` stream name (via `resolveStreamId()`)
3. POST SDP offer đến `http://127.0.0.1:1984/api/webrtc?src={chId}_main`
4. go2rtc trả về SDP answer (HTTP 201)
5. Device publish answer về cloud browser qua MQTT
6. Cloud browser + go2rtc negotiate ICE → video flow

### Vấn đề đã phát hiện & fix

**1. Thiếu TURN credentials trong go2rtc** (đã fix trong `CloudService.kt`)
- `CloudService.applyIceServersToGo2Rtc()` chạy 1 lần khi startup (guard bởi `iceServersApplied`)
- Fetch ICE servers từ cloud → ghi vào `config/config.yml` → `go2rtc.webrtc.ice_servers`
- Restart Frigate → wait go2rtc ready → `registerAllStreams()`
- Verify: `config/config.yml` phải có `ice_servers: [- urls: [turn:...]...]`

**2. `{FRIGATE_HOST_IP}` literal trong SDP ICE candidates** (fix đang pending build)
- Frigate 0.16.3 KHÔNG replace `{FRIGATE_HOST_IP}` khi pass config cho go2rtc subprocess
- go2rtc puts literal `{FRIGATE_HOST_IP}` vào SDP answer → cloud browser parse fail
- Fix: `detectLanIp()` trong `CloudService` (filter network interfaces, skip docker/br-/veth) → replace placeholder khi write config.yml
- `FRIGATE_HOST_IP` env var KHÔNG được pass vào nvr-wrapper container (chỉ có trong Frigate env)
- Fix trong cả `CloudService.applyIceServersToGo2Rtc()` và `CameraService.writeYaml()`

**3. `registerAllStreams()` dùng direct RTSP thay vì internal restream**
- Code hiện tại: `registerStreamSync("${chId}_main", "$rtspUrl#backchannel=0")` → go2rtc tạo connection mới trực tiếp tới camera
- Config.yml đúng có: `{chId}_main: [rtsp://127.0.0.1:8554/{slug}]` (qua Frigate restreamer)
- Sau khi Frigate start, go2rtc load config.yml nên streams đúng; `registerAllStreams()` PUT bị reject (ok=false) nhưng không làm hỏng streams
- TODO: đổi `registerAllStreams()` dùng `rtsp://127.0.0.1:8554/{slug}` thay vì direct RTSP URL

### Verify live view
```bash
# SDP answer phải có relay candidate và IP thực (không phải {FRIGATE_HOST_IP})
curl -s -X POST "http://localhost:1984/api/webrtc?src=1_main" \
  -H "Content-Type: application/sdp" --data 'v=0...' | grep "candidate"

# Expected: relay candidate với TURN IP, host candidate với IP thực
# a=candidate:... typ relay raddr ...
# a=candidate:... 192.168.x.x 8555 typ host   ← không được là {FRIGATE_HOST_IP}
```

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
| 8 | Unified Cameras source of truth (drop CloudCameras drift) | ✅ Done (003) |
| 9 | Passive edge gateway (multi-NVR, no creds on Pi, fleet token) | ✅ Done (4-6 Plan A) |

---

## Environment Variables (.env)

NEVER commit `.env`. Các biến quan trọng:
- `FRIGATE_HOST_IP` — IP của host Ubuntu (dùng cho WebRTC candidates)
- `TZ=Asia/Ho_Chi_Minh`
- `WRAPPER_TAG` — tag image nvr-wrapper (thực tế override bởi docker-compose.override.yml → `nvr-wrapper:latest`)
- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` — Watchdog alert
- `GITHUB_TOKEN` — Pull image từ GHCR (cần refresh khi hết hạn)
- `GMAIL_USER` — Gmail address (not sensitive)
- `GMAIL_APP_PASSWORD` — **Moved to `secrets/gmail_app_password` Docker secret** (NOT in .env)

---

## Network

- `nvr-wrapper`, `nginx`, `nvr-watchdog` dùng `network_mode: host` (cần cho ONVIF multicast và RTSP)
- `mosquitto`, `frigate` dùng bridge network `nvr-backend`
- Frigate có thể reach Tailscale/VPN IPs (100.64.x.x) từ trong container

## Test E2E
- `for run test E2E information, read claude_test.md.
