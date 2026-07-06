# CLAUDE.md — NVR Project

## Repository
- **GitHub:** https://github.com/toannice/NVR.git
- **Main branch:** `main` — phát triển trực tiếp trên main (nhánh `003-cloud-data-sync` đã DONE, đóng 2026-07-06)
- **Plan đã xong:** `specs/003-cloud-data-sync/plan.md` (cloud sync — DONE)
- **Principles & gates:** [`.specify/memory/constitution.md`](./.specify/memory/constitution.md) · **Doc map:** [`docs/INDEX.md`](./docs/INDEX.md)
- **Working directory on host:** `/home/toan/my-nvr`

---

## Stack Overview

Multi-service NVR (Network Video Recorder) chạy trên Ubuntu, deploy bằng Docker Compose.

```
mosquitto (MQTT) → frigate (NVR engine) → nvr-wrapper (Kotlin/React app)
                                                    ↑
                         nvr-watchdog (OTA + health) + nginx (HTTPS)
```

| Container | Image | Port | Ghi chú |
|-----------|-------|------|---------|
| `mosquitto` | `eclipse-mosquitto:2` | 1883, 9001 | MQTT broker |
| `frigate` | `ghcr.io/blakeblackshear/frigate:0.16.3` | 5000, 8554, 1984 | NVR engine, chốt version, không OTA |
| `nvr-wrapper` | prod: `ghcr.io/toannice/nvr:${WRAPPER_TAG:-latest}` · dev: `nvr-wrapper:latest` (override) | 3000 (host) | App chính (Kotlin backend + React frontend) |
| `nvr-watchdog` | build local `./watchdog` | — | Self-healing + OTA (luôn build local, KHÔNG OTA chính nó) |
| `nvr-nginx` | `nginx:stable-alpine` | 443 (host) | HTTPS gateway |

---

## Build & Deploy

### Triển khai máy mới (production) — `install.sh`
```bash
git clone https://github.com/toannice/NVR.git my-nvr && cd my-nvr
sudo ./install.sh           # .env từ template + TLS cert + secrets + docker login GHCR + compose pull/up
#   sudo ./install.sh --check   # chỉ kiểm tra, không đổi gì
```
`install.sh` **kéo image từ GHCR** (không build). Nó tự sinh `.env` (từ `.env.template`),
self-signed cert (`nginx/server.{crt,key}`), và `secrets/*` nếu thiếu. Máy prod KHÔNG có
`docker-compose.override.yml` → compose dùng `ghcr.io/toannice/nvr:${WRAPPER_TAG:-latest}`.

### OTA channel — `WRAPPER_TAG` (1 nguồn sự thật)
| `WRAPPER_TAG` | Kênh | Build từ |
|---|---|---|
| `latest` | **stable** (mặc định prod) | git `main` |
| `staging` | beta / máy test | git `staging` |
| `vX.Y.Z` | pin cứng 1 bản | git tag `v*.*.*` |
Watchdog OTA + rollback đều đọc đúng tag này (hết hardcode `staging`). Muốn beta: đổi 1 dòng `.env`.

### CRITICAL: docker-compose.override.yml (chỉ máy DEV)
`docker-compose.override.yml` **git-ignored** — chỉ tồn tại trên máy dev, auto-merge để override
`nvr-wrapper` thành `image: nvr-wrapper:latest` + `build: .` (build từ source). Máy prod KHÔNG có file
này nên compose kéo GHCR image. **Trên dev LUÔN dùng `docker compose build`** — KHÔNG `docker build -t ...`.

### Build image nvr-wrapper (app chính)
```bash
# NHANH NHẤT sau mỗi lần sửa code (build + recreate + self-heal + nginx, một lệnh):
./run.sh --rebuild
```
```bash
# Hoặc thủ công — phải chạy từ /home/toan/my-nvr (project root).
# Vòng lặp thường (nhanh): KHÔNG dùng --no-cache. Dockerfile tách copy deps trước,
# source sau → đổi code tự bust đúng tầng; npm install + gradle deps tái dùng (BuildKit cache mount).
docker compose build nvr-wrapper
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx   # nginx KHÔNG tự start khi nvr-wrapper recreate
```
> Chỉ dùng `--no-cache` khi cache bẩn thật: đổi base image, sửa Dockerfile, hoặc build ra code cũ bất thường.

### Khác
```bash
docker compose up -d --build nvr-watchdog        # build watchdog
docker compose up -d && docker compose up -d nginx   # khởi động toàn bộ stack (nginx start riêng)
curl -X POST http://localhost:5000/api/restart   # restart Frigate (áp config mới)
```

### Dockerfile (Multi-stage)
```
Stage 1: node:22-alpine                → build React frontend (npm run build → dist/)
Stage 2: eclipse-temurin:21-jdk-alpine → build Kotlin backend (./gradlew installDist)
Stage 3: eclipse-temurin:21-jre-alpine → runtime image
```

---

## Codebase

| Path | Mô tả |
|------|-------|
| `frontend/src/App.tsx` | React shell + orchestration (~626 dòng; UI đã tách ra `components/`) |
| `frontend/src/components/*.tsx` | UI tách từ App.tsx: `LiveView`, `PlaybackView`, `SetupView`, `Header`, `PtzControls`, `CameraModals`, `IntegrationPanel`... |
| `frontend/src/components/PatternLockScreen.tsx` | Pattern lock, setup, Gmail OTP flow |
| `frontend/src/components/GatewaysPanel.tsx` | Web UI "Gateway" tab: discover → approve → adopt |
| `backend/.../routes/CameraRouting.kt` | Local Web UI camera CRUD; delegates persistence to `CameraService` |
| `backend/.../services/CameraService.kt` | Unified add/update/remove path (Web UI AND cloud handler) |
| `backend/.../services/CameraConfigMutex.kt` | Shared mutex cho mọi config.yml + Cameras DB writes |
| `backend/.../cloud/Go2RtcClient.kt` | Sync PUT/DELETE go2rtc REST API on 127.0.0.1:1984 |
| `backend/.../cloud/DcBridgeManager.kt` | Spawn `dc-bridge` Go sidecar (`DC_BRIDGE_PATH` / `/app/data/dc-bridge`) |
| `backend/.../cloud/DataChannelManagerDesktop.kt` | Linux WebRTC DATA peer — HTTP client to dc-bridge on `127.0.0.1:8766` |
| `backend/.../routes/CloudRouting.kt` | `POST /internal/dc/command` — dc-bridge callback into CommandDispatcher |
| `data/dc-bridge` | Go Pion WebRTC sidecar binary (linux/amd64), bắt buộc cho device_service DATA |
| `backend/.../gateway/EdgeAgentClient.kt` | NVR→agent HTTP client (bearer fleet token, timeout+retry) |
| `backend/.../gateway/GatewayConfig.kt` | NVR gateway settings: scan subnet, agent port, fleet token |
| `backend/.../services/GatewayScanner.kt` | Auto-scan `ZT_SCAN_SUBNET` /health để phát hiện gateway |
| `backend/.../services/GatewayService.kt` | Gateway approve + adopt camera (reuses `CameraService.applyAndPersistFromOnvif`) |
| `backend/.../routes/GatewayRouting.kt` | `/api/gateways*` — list/scan/approve/discover/adoptable/adopt |
| `backend/.../routes/LockRouting.kt` | Pattern lock API, OTP verify endpoint |
| `backend/.../routes/OnvifRouting.kt` | Scan ONVIF, thêm camera qua ONVIF, PTZ |
| `backend/.../routes/IntegrationRouting.kt` | AiView Box integration v1.1: public contract + admin (enable/creds) |
| `backend/.../services/IntegrationService.kt` | Box integration settings (`IntegrationSettings` table, bcrypt creds, độc lập pattern lock) |
| `backend/.../services/StreamOnlineTracker.kt` | Suy ra camera `online` từ go2rtc active producers (`Go2RtcClient.liveStreamNames`) |
| `backend/.../services/EmailService.kt` | Gmail SMTP, OTP send/peek/verify với SQLite |
| `backend/.../models/Schema.kt` | DB schema: LockConfig, OtpTokens, AIEvents, Cameras... |
| `backend/.../plugins/Databases.kt` | SQLite init, createMissingTablesAndColumns |
| `backend/.../plugins/Security.kt` | hashString (SHA-256), hashPattern (bcrypt), verifyPattern |
| `gateway/` | All gateway-side artifacts (Pi agent, enroll app, Nebula control plane, docs). Xem `gateway/README.md`. ZeroTier-era docs ở `gateway/legacy/` |
| `gateway/edge-agent/` | Go agent on Pi — passive L4 proxy (nginx stream), WS-Discovery, field web page |
| `config/config.yml` | Frigate config (cameras, go2rtc, ffmpeg global) |
| `config/watchdog.yml` | Watchdog config (thresholds, OTA, schedule) |
| `watchdog/src/main/kotlin/...` | Watchdog: DockerApi, SelfHealingEngine, UpdateManager |
| `docker-compose.yml` / `docker-compose.override.yml` | Stack def / override nvr-wrapper image |
| `.env` | Secrets (KHÔNG commit) |
| `secrets/gmail_app_password` | Docker secret cho Gmail (git-ignored) |
| `nginx/nginx.conf` | HTTPS reverse proxy config |
| `connection_to_server.md` | Full guide: Linux device kết nối AIView cloud + dc-bridge build |

---

## Gmail OTP Flow

**Setup:** Draw pattern → confirm (2 lần khớp) → email entry → `POST /api/lock/send-otp` → enter OTP → `POST /api/lock/verify-otp` (peek, not consume) → if valid → `POST /api/lock/setup` (verifyOtp consumes OTP on final submit).

**Reset:** `POST /api/lock/reset-request` (email) → OTP → `POST /api/lock/verify-otp` (peek) → draw new pattern twice → `POST /api/lock/reset-complete`.

OTP stored in SQLite `otp_tokens`. On fresh DB phải tạo thủ công:
```sql
CREATE TABLE IF NOT EXISTS otp_tokens (email VARCHAR(255) PRIMARY KEY, code VARCHAR(6) NOT NULL, expiry BIGINT NOT NULL);
```
Gmail creds: `GMAIL_USER` env (`.env`), `GMAIL_APP_PASSWORD` via Docker secret `/run/secrets/gmail_app_password`.

## Password Hashing
Pattern hash dùng **bcrypt** (cost 12, ~300ms). Legacy SHA-256 auto-upgrade khi login thành công lần đầu (transparent, không re-setup).
- `hashPattern(pattern)` → bcrypt · `verifyPattern(input, stored)` → `(matched, needsRehash)` · `hashString(input)` → SHA-256+salt (email/gmail compare).

## RTSP URL Validation (SSRF Prevention)
`validateRtspUrl()` (`CameraRouting.kt`) chặn: non-`rtsp(s)://` schemes, loopback (`127.x`, `localhost`), link-local (`169.254.x` — cloud metadata), `host.docker.internal`. Private LAN (`192.168.x`, `10.x`) **được phép** (camera ở đó).

## Config File Mutex
`configMutex` (`kotlinx.coroutines.sync.Mutex` trong `CameraRouting.kt`) wrap mọi `config.yml` read-modify-write → tránh race khi nhiều client add/delete camera.

## Frigate Config Notes
- **Global hwaccel:** `preset-intel-qsv-h264` — chỉ chạy nếu host có Intel GPU.
- **RTSP camera thủ công:** phải override `hwaccel_args: []` ở cấp camera để tránh crash trên máy không Intel GPU (vd NVIDIA).
- **go2rtc streams:** mỗi camera RTSP thêm `#tcp` để tránh UDP drop/lag.
- **Frigate version:** chốt cứng `0.16.3`, watchdog không OTA Frigate.

---

## Thêm Camera: Luồng dữ liệu (003 — unified)

Cả Web UI lẫn cloud dashboard đi qua **một** code path: `CameraService.applyAndPersist*`.

1. User nhập (Web UI tab ONVIF/RTSP, hoặc cloud `post_channels`)
2. `CameraRouting#addCamera` (Web UI) / `CameraCommandHandler#postOrPutChannels` (cloud) parse request
3. → `CameraService.applyAndPersistFromRtsp` (RTSP có sẵn) / `applyAndPersistFromOnvif` (cloud, ONVIF probe trước)
4. `CameraService` acquire `CameraConfigMutex.mutex`, tuần tự:
   - UPSERT row vào bảng `Cameras` (unified — không còn `CloudCameras`); gán `channel_id` nếu mới
   - Ghi `config/config.yml` (cameras + go2rtc.streams)
   - `Go2RtcClient.registerStreamSync("${channelId}_main", ...)` — chờ go2rtc PUT 200 (5s timeout)
5. Trigger `POST localhost:5000/api/restart` → trả `frigateRestarted: true/false`
6. Web UI toast warning nếu `frigateRestarted === false`; cloud trả `body.data.frigate_restarted`
7. Cloud `get_channels` đọc cùng bảng `Cameras` → cloud và Web UI luôn thấy giống nhau

Delete qua `CameraService.removeAndPersist(slugOrChannelId)` — idempotent (DB + YAML + go2rtc stream + Frigate restart).

---

## Cloud Connection — Linux Device

Chi tiết đầy đủ: **[connection_to_server.md](./connection_to_server.md)**. 3 đường data tới cloud:

| Kênh | Vận chuyển | Loại dữ liệu | Phụ thuộc |
|---|---|---|---|
| MQTT | tcp 1883 | Camera CRUD (`channel_service`), heartbeat | mosquitto reach cloud |
| WebRTC LIVE | UDP/TCP qua ICE | Stream video | `go2rtc` @ `127.0.0.1:1984` |
| WebRTC DATA | UDP/TCP qua ICE | **Device info, ethernet, time** (`device_service`) | **Binary `dc-bridge`** @ `data/dc-bridge` |

### dc-bridge (BẮT BUỘC để dashboard hiện Device info)
Cloud chỉ hỏi `get_information/get_ethernet/get_time` qua WebRTC DATA, KHÔNG qua MQTT. JVM `dev.onvoid.webrtc` không chạy headless Linux → cần sidecar Go (Pion).
```bash
SRC=~/Documents/1CodeBkav/B02/aiview_b02_box/dc-bridge
mkdir -p /tmp/dc-bridge-build && cp "$SRC"/{main.go,go.mod,go.sum} /tmp/dc-bridge-build/
# Patch JVM port 8080 → 3000 (backend NVR ở 3000)
sed -i 's|127.0.0.1:8080/internal/dc/command|127.0.0.1:3000/internal/dc/command|' /tmp/dc-bridge-build/main.go
cd /tmp/dc-bridge-build
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o /home/toan/my-nvr/data/dc-bridge -ldflags='-s -w' .
chmod +x /home/toan/my-nvr/data/dc-bridge && docker compose restart nvr-wrapper
```
Verify log: `DC_BRIDGE_STARTED | dc-bridge started from /app/data/dc-bridge`. `DcBridgeManager` spawn tự động khi backend khởi động. Reference source + arm64 binary: `~/Documents/1CodeBkav/B02/aiview_b02_box/dc-bridge/`.

---

## PTZ / ONVIF (kể cả camera qua socat/gateway proxy)

Web UI điều khiển PTZ qua `/api/cameras/{slug}/ptz/{capabilities,move,stop}` (`OnvifRouting.kt` + `OnvifSoapClient`). **Một code path cho camera LAN trực tiếp lẫn camera sau proxy** — `OnvifSoapClient` rewrite authority của ONVIF Media/PTZ XAddr về `ip:onvifPort`.

`getCameraOnvifConfig` resolve creds: **ưu tiên `user:pass` nhúng trong RTSP URL của `config.yml`** (camera LAN), fallback `Cameras.username` + giải mã `passwordEncrypted` (camera proxy/cloud).

**Cấu hình camera qua proxy (Web UI → Sửa Camera):** Cổng ONVIF = cổng socat/nginx proxy (vd `8080`). ONVIF User/Pass (trống = giữ creds cũ). **Kiểu PTZ**: `Tự động` (theo ONVIF `GetNodes`) | `Toàn phần` (ép Pan/Tilt/Zoom) | `Chỉ Zoom`. Lưu ở `cameras.ptz_mode`.

**Vì sao có override "Kiểu PTZ":** một số camera PTZ rẻ chỉ quảng cáo `ContinuousZoomVelocitySpace` trong `GetNodes` (không có PanTilt) dù phần cứng pan/tilt qua API riêng → auto-detect ẩn d-pad. Chọn **Toàn phần** để ép hiện d-pad. `/ptz/capabilities` ưu tiên override → trả ngay không cần probe. ⚠️ Có camera PTZ thật nhưng KHÔNG lái pan/tilt bằng ONVIF `ContinuousMove` (vd `OSSD2MPVF33XPTZAI`) → ép `full` thì lệnh PanTilt vẫn fault "speed out of bounds". `PtzControls` (frontend) ẩn d-pad khi `hasPanTilt=false`, ẩn zoom khi `hasZoom=false`.

> Camera zoom-only thật (Bkav P200-0802N): để `Tự động` hoặc `Chỉ Zoom`. Gửi PanTilt → fault.

### Gateway Camera Provisioning (Plan A) — ✅ DONE 2026-06-04
> Kế hoạch + verify (LEGACY, ZeroTier-era): **[gateway/legacy/4-6-to-do-jobs.md](./gateway/legacy/4-6-to-do-jobs.md)**. Vận hành: **[gateway/gateway_setup.md](./gateway/gateway_setup.md)**. Overlay hiện hành: **[gateway/nebula-migration-va-trien-khai.md](./gateway/nebula-migration-va-trien-khai.md)**.

Verify end-to-end trên Pi thật: NVR auto-scan subnet ZeroTier → phát hiện gateway (passive, **không push**) → admin approve → adopt camera: agent cấp cổng nginx-stream onvif/rtsp, NVR probe ONVIF qua proxy, rewrite RTSP host → ztIP, ghi Frigate/go2rtc, **live view OK**. Multi-NVR; Pi không giữ creds (admin gõ lúc approve); fleet token dùng chung (`secrets/edge_agent_token`).

Code: `gateway/edge-agent/` (Go), `backend/.../gateway/*`, `services/{GatewayScanner,GatewayService}.kt`, `routes/GatewayRouting.kt`, `CameraService.rewriteRtspHost`+`autoDetectPtz`, `GatewaysPanel.tsx`.

**Auto-PTZ:** sau adopt gửi 1 `ContinuousMove` PanTilt nhỏ (x=0.05) + `Stop`, xem SOAP fault → set `ptz_mode` = `full` (no fault) / `zoom` (fault). KHÔNG đè lựa chọn thủ công. (`OnvifSoapClient.probePanTiltSupported`, `CameraService.autoDetectPtz`.)

**Multi-region enroll (cách A) — ✅ DONE (code, chưa test Pi thật):** 1 image Pi dùng chung (không bake network id). App điện thoại (`gateway/android-app/`) quét gateway qua `/identity` → nhập **Network ID 16-hex** → `POST /enroll` → agent `zerotier join` + persist `state.json` (reboot tự join). Sau đó admin "Cấp phép" trên NVR. Van an toàn: `/enroll` no-auth nhưng từ chối đổi network nếu gateway đã có camera. **TODO test:** cài mới Pi → enroll → cấp phép → adopt.

**Known limitations:**
- ⚠️ `OSSD2MPVF33XPTZAI` là PTZ thật nhưng không nhận pan/tilt qua ONVIF `ContinuousMove` (`GetNodes` chỉ Zoom) → auto-PTZ phân loại `zoom` (false-negative đã biết). Cần tìm phương thức pan thật (`AbsoluteMove`/space riêng/API vendor) — chưa làm.
- **G8 (nice-to-have):** agent chỉ forward 80 (onvif) + 554 (rtsp). Camera để Web UI ở cổng khác (vd 443) → phải thêm tay `stream{}` trên Pi (`/etc/nginx/stream.d/webui-extra.conf`, NGOÀI tầm agent). **SSH Pi:** user `pi3`, key `~/.ssh/pi_gateway`, chỉ mở SSH ở field LAN (`192.168.2.71:22`).
- Link ZeroTier chập chờn → mọi call agent có timeout ngắn + retry 1 lần.

> **Golden image bake (A1):** đã làm PoC nhưng **DECISION 2026-06-06: KHÔNG bake cho PoC này** (chạy trên phần cứng có sẵn, hoãn bake đến khi có phần cứng chính thức). Chi tiết tham chiếu: **[gateway/commercialization-security-notes.md](./gateway/commercialization-security-notes.md)**.

### Băng thông & sức chứa camera qua gateway
**Luồng:** `Camera → Gateway(Pi) → [UPLOAD gateway] → internet → [DOWNLOAD NVR] → NVR`. Mỗi camera kéo 2 luồng (main=record, sub=detect) 24/7.

**Nút thắt = DOWNLOAD của NVR, là TỔNG camera toàn hệ thống** (không phải mỗi gateway — download NVR là 1 ống chung, cắm thêm gateway không mở rộng). Gateway chỉ proxy L4 (nginx `stream{}`) — không decode/buffer → RAM ~const (~4MB), không phụ thuộc resolution.

Bitrate/camera (H.265, 30fps, ước lượng): **Config A (4K main + FullHD sub) ≈ 12–16 Mbps**; **Config B (FullHD main + 720p sub) ≈ 4–5.5 Mbps**.
Hạ tầng hiện tại (đo 2026-06-06): NVR download 68 / upload 99 Mbps; chừa ~30% headroom → ~48 Mbps thực → **~3 cam Config A** hoặc **~9–10 cam Config B**. Điểm ngọt: main FullHD + sub 720p. Muốn nhiều 4K hơn → **chỉ có cách nâng gói DOWNLOAD của NVR**.

Đổi resolution làm trên **chính camera** (web cam / ONVIF `SetVideoEncoderConfiguration`), không trên gateway/NVR.
**Triệu chứng quá tải** (RTSP-over-TCP qua ZT chập chờn → kẹt→đơ→rớt, không vỡ hạt): live lag/freeze/đen; record thủng đoạn; camera flapping; log Frigate `Unable to read frames`/go2rtc reconnect loop → dây chuyền retransmit → buffer đầy → Frigate restart stream → càng tải càng sập.

---

## 🚨 Thương mại hoá — overlay ĐÃ CHỐT = Nebula (2026-06-11)
**QUYẾT ĐỊNH:** bỏ **ZeroTier** (BSL 1.1, nhúng-bán cần OEM license) → **chạy Nebula (MIT)** cho bản bán được. Việc này giải quyết luôn trục **chủ quyền** (lighthouse tự host, hết phụ thuộc planet roots ngoại). **HQ (lighthouse + signing service) và NVR đã dựng + verify chạy** (`http://192.168.2.106:9443/healthz` → ok, lighthouse `192.168.2.106:4242`; `.env` có `NEBULA_CA_URL`/`NEBULA_OVERLAY_SUBNET`, secret `nebula_sign_token`).

**Còn lại trước khi bán:** (1) **test Pi thật A→Z** trên Nebula (runbook §5 — xem dưới); (2) **An ninh sản phẩm G1–G7** (G1 pivot `/camera` whitelist field subnet, G2 per-gateway token/rotate, G3 chống hijack `/enroll`).

> Thiết kế + runbook Nebula: **[gateway/nebula-migration-va-trien-khai.md](./gateway/nebula-migration-va-trien-khai.md)** · code: **[gateway/nebula/README.md](./gateway/nebula/README.md)**. Audit bảo mật G1–G7 + bảng so sánh overlay: **[gateway/commercialization-security-notes.md](./gateway/commercialization-security-notes.md)**.

### Chạy Nebula — bring up NVR + test Pi
```bash
# 1) Bring up nvr-wrapper với NEBULA_* env (Nebula active khi NEBULA_CA_URL set; ZeroTier tự nhường)
docker compose build nvr-wrapper            # nếu image cũ hơn commit Nebula 5922f55
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx
docker logs nvr-wrapper | grep -i gateway   # chờ SCAN ... DISCOVERED_AUTOAPPROVED
```
Test Pi (runbook §5 B0–B6): cài tươi Pi (build `gateway/nebula/edge-agent` → `./build.sh`) → app nhập **mã khu vực = signing URL + token** → agent keygen + `/enroll` (gửi pubkey) → NVR tab Gateway **"Cấp phép"** → signing service ký cert → Pi lên overlay `10.10.20.0/24` → scanner phát hiện → adopt camera → live view. Đo NAT traversal để quyết có cần relay.

---

## Known Issues & Fixes

| Vấn đề | Nguyên nhân | Fix |
|--------|-------------|-----|
| Camera offline ngay sau khi thêm RTSP | Global `hwaccel_args: preset-intel-qsv-h264` không hợp GPU NVIDIA | Thêm `hwaccel_args: []` cấp camera |
| Site unreachable sau deploy nvr-wrapper | nginx không phụ thuộc nvr-wrapper | `docker compose up -d nginx` |
| Container dùng image cũ / build không lấy code mới | `docker-compose.override.yml` override + Docker cache | `docker compose build --no-cache nvr-wrapper` |
| Adopt camera qua tab Gateway nhưng KHÔNG thấy trên NVR | List camera chỉ fetch lúc tải trang | Đã fix: `GatewaysPanel` gọi `onCamerasChanged` sau adopt (F5 cũng thấy) |
| Gateway không hiện trong tab Gateway dù Pi chạy | `ZT_SCAN_SUBNET` sai; Pi chưa authorize; fleet token Pi ≠ `secrets/edge_agent_token` | `docker logs nvr-wrapper \| grep GATEWAY`; xem `gateway/gateway_setup.md` |
| GHCR pull fail | GitHub token hết hạn | `echo <token> \| docker login ghcr.io -u toannice --password-stdin` |
| OTP input bị browser validate như email | `renderInput()` dùng `title.includes('Mã')` | Dùng `isOtp?: boolean` param tường minh |
| `otp_tokens` table không tự tạo trên DB cũ | Exposed WAL behavior | Tạo thủ công bằng sqlite3 |
| Dashboard cloud online nhưng KHÔNG có Device info/Ethernet/Time | DATA channel fail vì thiếu `dc-bridge` | Build dc-bridge linux/amd64 port=3000 → `data/dc-bridge` (xem `connection_to_server.md`) |
| `DC_OFFER_FAIL Connection refused` / cloud không nhận response | dc-bridge chưa start hoặc `main.go` hardcode port 8080 | `docker compose logs nvr-wrapper \| grep dc-bridge`; check `DC_BRIDGE_STARTED`; patch sed port 3000 |
| Cloud dashboard chỉ hiện 2/N cameras | Cloud chỉ đếm cameras thêm qua `post_channels` | Thêm từ cloud dashboard; không phải bug device code |
| Cloud live view không chạy — stream.mp4 500 liên tục | Cameras offline gây go2rtc retry liên tục | Xóa/tắt cameras offline |
| Cloud live view — SDP có `{FRIGATE_HOST_IP}` literal | Frigate 0.16.3 không substitute placeholder cho go2rtc subprocess | `CloudService.applyIceServersToGo2Rtc()` + `CameraService.writeYaml()` dùng `detectLanIp()` |
| `GO2RTC REGISTER ok=false` khi MQTT reconnect | Streams đã tồn tại trong go2rtc (load từ config.yml) → reject PUT | Benign, không cần fix |
| Live view ĐEN toàn bộ + 502 dưới tải | `FrigateProxy.kt` `proxyClient` (CIO) `socketTimeout = 0` → Ktor 3.0.1 busy-spin 64 thread `DefaultDispatcher` → mọi response ghi body treo | Đổi `socketTimeout = Long.MAX_VALUE`. Chẩn: `docker exec nvr-wrapper kill -3 1` → 64 thread kẹt `Timeout$initTimeoutJob`, CPU ~300% |
| Web UI camera qua gateway `ERR_EMPTY_RESPONSE` (RTSP vẫn chạy) | Cổng onvif proxy trỏ camera :80 (chỉ ONVIF SOAP), web UI ở 443; gateway chỉ forward 80+554 | Thêm tay `stream{}` `listen <ztIP>:<port> → <cam_ip>:443` (G8). Hoặc vào LAN field `https://<cam_ip>` |

---

## Cloud Live View — Root Cause & Fix (003)

### Luồng LIVE WebRTC (cloud → device → go2rtc → cloud browser)
1. Cloud browser click Live → MQTT LIVE offer → `SignalingManager.handleLiveOffer()`
2. Resolve `profileToken` → `{chId}_main` stream name (`resolveStreamId()`)
3. POST SDP offer → `http://127.0.0.1:1984/api/webrtc?src={chId}_main`
4. go2rtc trả SDP answer (HTTP 201) → device publish answer về cloud browser qua MQTT → negotiate ICE → video

### Fix đã làm
1. **Thiếu TURN credentials trong go2rtc** — `CloudService.applyIceServersToGo2Rtc()` (1 lần khi startup, guard `iceServersApplied`): fetch ICE từ cloud → ghi `config.yml` `go2rtc.webrtc.ice_servers` → restart Frigate → `registerAllStreams()`.
2. **`{FRIGATE_HOST_IP}` literal trong SDP** — Frigate 0.16.3 không replace khi pass cho go2rtc subprocess. `detectLanIp()` (skip docker/br-/veth) replace placeholder khi write config.yml (cả `applyIceServersToGo2Rtc` + `CameraService.writeYaml`). `FRIGATE_HOST_IP` env KHÔNG pass vào nvr-wrapper container.
3. **`registerAllStreams()` dùng direct RTSP** thay vì internal restream — config.yml đúng có `{chId}_main: [rtsp://127.0.0.1:8554/{slug}]`. Sau Frigate start go2rtc load config.yml nên streams đúng; PUT bị reject (ok=false) nhưng không hỏng. TODO: đổi dùng `rtsp://127.0.0.1:8554/{slug}`.

### Verify
```bash
# SDP answer phải có relay candidate + IP thực (không phải {FRIGATE_HOST_IP})
curl -s -X POST "http://localhost:1984/api/webrtc?src=1_main" \
  -H "Content-Type: application/sdp" --data 'v=0...' | grep "candidate"
```

---

## Security Status

| # | Item | Status |
|---|------|--------|
| 1 | Remove test@test.com bypass | ✅ |
| 2 | RTSP URL validation (SSRF) | ✅ |
| 3 | File lock on config read-write | ✅ |
| 4 | bcrypt/Argon2 password hashing | ✅ |
| 5 | Frigate restart feedback to UI | ✅ |
| 6 | Gmail OTP persistence (SQLite) | ✅ |
| 7 | Docker secrets for GMAIL_APP_PASSWORD | ✅ |
| 8 | Unified Cameras source of truth (drop CloudCameras drift) | ✅ (003) — bảng `cloud_cameras` còn để rollback, xóa sau 1 release (xem constitution § Deferred cleanup) |
| 9 | Passive edge gateway (multi-NVR, no creds on Pi, fleet token) | ✅ (4-6 Plan A) |
| 10 | OTA 1 nguồn sự thật (`WRAPPER_TAG`, hết hardcode `staging`) | ✅ |

> Gateway security backlog (G1–G7, PENDING) → `gateway/commercialization-security-notes.md`.

---

## Environment Variables (.env)
NEVER commit `.env`. Quan trọng:
- `FRIGATE_HOST_IP` — IP host Ubuntu (WebRTC candidates)
- `TZ=Asia/Ho_Chi_Minh`
- `WRAPPER_TAG` — tag image (thực tế override → `nvr-wrapper:latest`)
- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` — Watchdog alert
- `GITHUB_TOKEN` — Pull image từ GHCR (refresh khi hết hạn)
- `GMAIL_USER` — Gmail address (not sensitive)
- `GMAIL_APP_PASSWORD` — **moved to `secrets/gmail_app_password` Docker secret** (NOT in .env)

## Network
- `nvr-wrapper`, `nginx`, `nvr-watchdog` dùng `network_mode: host` (cần cho ONVIF multicast + RTSP)
- `mosquitto`, `frigate` dùng bridge network `nvr-backend`
- Frigate reach Tailscale/VPN IPs (100.64.x.x) từ trong container

## Test E2E
- Thông tin chạy test E2E: đọc `claude_test.md`.
