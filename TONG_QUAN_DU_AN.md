# TỔNG QUAN DỰ ÁN NVR — Tài liệu nghiên cứu toàn diện

> Mục đích: hiểu **toàn bộ** hệ thống — ai làm gì, bằng gì, công nghệ nào, viết bằng ngôn ngữ nào,
> dữ liệu nào truyền đi đâu, edge case, sự cố, và khả năng port phần Pi sang phần cứng khác.
> Cập nhật: 2026-06-06.

---

## 0. Một câu mô tả dự án

Đây là một **NVR (Network Video Recorder) chạy ở biên (edge)**: một máy chủ NVR đặt tại chỗ
(Ubuntu PC) thu video từ camera IP, ghi hình, chạy AI nhận diện, và **đồng bộ/điều khiển qua cloud
AIView**. Có thêm một lớp **gateway Raspberry Pi** đặt ở "nhà khách" để kéo camera ở mạng LAN xa về
NVR qua mạng riêng ảo ZeroTier, mà **không cần mở port / không cần VPN thủ công**.

Có 3 "thế giới" cần phân biệt rõ:

1. **NVR core** (máy Ubuntu): trái tim — Frigate + app Kotlin/React + watchdog + nginx.
2. **Cloud AIView** (server của bên thứ 3, không phải code mình): dashboard điều khiển từ xa.
3. **Edge gateway** (Raspberry Pi): proxy L4 thụ động để với tới camera ở site xa.

---

## 1. Sơ đồ kiến trúc tổng thể

```
                          ┌──────────────────────── CLOUD AIView (bên thứ 3) ───────────────────────┐
                          │   MQTT broker cloud   +   Signaling   +   Dashboard web                  │
                          └───────▲───────────────────────▲──────────────────────▲─────────────────┘
            MQTT (CRUD camera,    │    WebRTC LIVE (video) │   WebRTC DATA (device info)
            heartbeat) tcp 1883   │    qua go2rtc          │   qua dc-bridge (Go/Pion)
                                  │                        │
┌─────────────────────────────── NVR CORE (máy Ubuntu, Docker Compose) ───────────────────────────┐
│                                                                                                   │
│  mosquitto ──► frigate (NVR engine + go2rtc) ──► nvr-wrapper (Kotlin backend + React frontend)    │
│   (MQTT)         0.16.3, chốt cứng                3000 (host net)                                  │
│                  5000/8554/1984/8555              │  ▲                                             │
│                                                   │  │ /var/run/docker.sock (restart frigate)     │
│  nvr-watchdog (self-heal + OTA, Kotlin) ──────────┘  │                                             │
│  nvr-nginx (HTTPS 443, reverse proxy) ───────────────┘                                            │
│                                                                                                   │
│  dc-bridge (Go/Pion sidecar, spawn bởi backend) ── 127.0.0.1:8766                                 │
└───────────────────────────────────────────────────▲───────────────────────────────────────────-─┘
                                                      │ ZeroTier overlay (10.147.20.0/24)
                                                      │ NVR auto-scan subnet → tìm gateway
                          ┌───────────────────────────┴────────────────────────────┐
                          │  EDGE GATEWAY = Raspberry Pi (Go agent + nginx stream)   │
                          │  passive L4 proxy, KHÔNG giữ creds, KHÔNG gọi NVR        │
                          └───────────────────────────┬────────────────────────────┘
                                                       │ LAN site xa (192.168.x.x)
                                              [ Camera IP ONVIF/RTSP ]
```

Một thành phần điều phối overlay riêng:

```
HQ controller (máy 192.168.2.106): self-host ZeroTier controller (ztncui + ZT one)
  → cấp mạng "nvr-fleet" id efb242bde2f3e856, pool 10.147.20.0/24
  → admin "authorize" mỗi Pi/NVR mới ở giao diện ztncui (192.168.2.106:3443)
```

---

## 2. Bảng "ai chịu trách nhiệm gì — bằng công nghệ gì"

| Thành phần | Nơi chạy | Ngôn ngữ / công nghệ | Trách nhiệm chính | Truyền dữ liệu gì |
|---|---|---|---|---|
| **mosquitto** | container (bridge net) | Eclipse Mosquitto 2 (C) | MQTT broker nội bộ; cầu nối camera CRUD + heartbeat tới cloud | MQTT tcp 1883 / ws 9001 |
| **frigate** | container (bridge net) | Frigate 0.16.3 (Python + go2rtc Go) | NVR engine: ghi hình, detect AI, restream RTSP, WebRTC qua go2rtc | RTSP 8554, go2rtc API 1984, WebRTC UDP 8555 |
| **nvr-wrapper** | container (host net) | **Backend: Kotlin + Ktor**, **Frontend: React + TypeScript (Vite)** | App chính: Web UI, camera CRUD, lock/OTP, ONVIF/PTZ, cloud bridge, gateway control | HTTP 3000, MQTT, WebRTC |
| **nvr-watchdog** | container (host net) | Kotlin | Self-healing (restart container chết), OTA update có rollback, alert Telegram, đo disk | Docker socket, Telegram API |
| **nvr-nginx** | container (host net) | nginx stable-alpine | HTTPS gateway 443 → reverse proxy về nvr-wrapper:3000 | TLS 443 |
| **dc-bridge** | tiến trình con (spawn bởi backend) | **Go + Pion WebRTC** | Sidecar WebRTC DATA channel (vì JVM `dev.onvoid.webrtc` không chạy headless Linux) | WebRTC DATA, callback HTTP 127.0.0.1:3000 |
| **edge-agent** | Raspberry Pi | **Go (thuần, ~925 dòng)** | Agent thụ động: WS-Discovery camera, cấp port proxy nginx, báo /health | HTTP 9797 qua ZeroTier |
| **nginx (trên Pi)** | Raspberry Pi | nginx `stream{}` module | Proxy L4 TCP: ONVIF :80 và RTSP :554 của camera → port trên ZeroTier IP | TCP forward |
| **ZeroTier** | NVR + Pi + HQ | ZeroTier One (C++) | Overlay VPN P2P, NAT traversal, không cần mở port | Lớp 2/3 ảo |
| **ztncui** | HQ controller | Node.js (self-host) | Giao diện quản trị ZeroTier controller (authorize member) | Web 3443 |

---

## 3. Chi tiết từng thành phần

### 3.1 mosquitto (MQTT broker)
- **Làm gì:** broker MQTT nội bộ. Cloud AIView kết nối tới đây (qua cầu nối) để gửi lệnh
  `channel_service` (thêm/sửa/xóa camera) và nhận heartbeat.
- **Công nghệ:** image `eclipse-mosquitto:2`.
- **Đặc biệt:** chạy bridge network `nvr-backend`, không host net. Mở 1883 (MQTT) + 9001 (WebSocket).

### 3.2 frigate (NVR engine) — "chốt cứng 0.16.3, KHÔNG OTA"
- **Làm gì:** đọc RTSP từ camera, ghi hình (`./media`), chạy object detection (AI), tạo restream
  RTSP nội bộ ở `rtsp://127.0.0.1:8554/<slug>`, và nhúng **go2rtc** để phát WebRTC.
- **Công nghệ:** Frigate (Python) + go2rtc (Go) bên trong.
- **Đặc biệt quan trọng:**
  - `privileged: true`, dùng `/dev/dri/renderD128` cho hwaccel Intel QSV.
  - **go2rtc là điểm mấu chốt của live view**: NVR app PUT/DELETE stream qua REST API
    `127.0.0.1:1984`.
  - Version **chốt cứng** trong compose, watchdog KHÔNG OTA Frigate (tránh vỡ config).
  - `shm_size: 1024mb` + tmpfs 1GB cho cache (giảm hao mòn SSD).

### 3.3 nvr-wrapper — APP CHÍNH (file quan trọng nhất)
Đây là phần do mình viết nhiều nhất. Multi-stage Docker:
- Stage 1: `node:22-alpine` build React → `dist/`
- Stage 2: `eclipse-temurin:21-jdk-alpine` build Kotlin → `installDist`
- Stage 3: `eclipse-temurin:21-jre-alpine` runtime

**Backend (Kotlin + Ktor)** — các package:
- `routes/` — HTTP endpoints:
  - `CameraRouting.kt` — CRUD camera Web UI; có `validateRtspUrl()` chống SSRF; có `configMutex`.
  - `OnvifRouting.kt` + `OnvifSoapClient.kt` — scan ONVIF, PTZ, SOAP client (rewrite XAddr authority).
  - `LockRouting.kt` — pattern lock + OTP verify.
  - `CloudRouting.kt` — `/internal/dc/command` (callback từ dc-bridge).
  - `GatewayRouting.kt` — `/api/gateways*` (list/scan/approve/discover/adopt).
  - `FrigateProxy.kt` — proxy stream từ Frigate (⚠️ chỗ từng gây bug CIO busy-spin).
  - `ScheduleRouting.kt`, `ConfigRouting.kt`, `SystemRouting.kt`, `NetworkConfig.kt`, `DevRouting.kt`, `StaticFiles.kt`.
- `services/` — logic nghiệp vụ:
  - `CameraService.kt` — **code path THỐNG NHẤT** để add/update/remove camera (cả Web UI lẫn cloud).
  - `CameraConfigMutex.kt` — mutex dùng chung cho mọi ghi `config.yml` + bảng `Cameras`.
  - `CloudService.kt` — apply ICE servers vào go2rtc, detect LAN IP (fix `{FRIGATE_HOST_IP}`).
  - `MqttService.kt` — kết nối MQTT, route lệnh cloud.
  - `GatewayScanner.kt` — quét subnet ZeroTier tìm gateway (auto-connect).
  - `GatewayService.kt` — approve + adopt camera từ gateway.
  - `EmailService.kt` — Gmail SMTP gửi OTP.
  - `OnvifCameraClient.kt`, `RecordingScheduler.kt`.
- `cloud/` — kết nối cloud AIView:
  - `MqttService` + `SignalingManager.kt` (xử lý LIVE offer), `SignalingHttpClient.kt`.
  - `CommandDispatcher.kt` + `handlers/` (CameraCommandHandler, DeviceCommandHandler, PlaybackCommandHandler).
  - `Go2RtcClient.kt` — PUT/DELETE stream go2rtc.
  - `DcBridgeManager.kt` — spawn binary dc-bridge.
  - `DataChannelManagerDesktop.kt` — HTTP client tới dc-bridge.
  - `DeviceConstants.kt` — giá trị placeholder model/firmware trả cho cloud.
- `gateway/` — `EdgeAgentClient.kt` (HTTP client tới Pi), `GatewayConfig.kt` (subnet/port/token).
- `models/Schema.kt` — schema SQLite (xem mục 5).
- `plugins/` — Databases (SQLite init), Security (bcrypt/SHA-256), Serialization, Sockets, HTTP, Routing.

**Frontend (React + TypeScript)** — `frontend/src/`:
- `App.tsx` (~3000 dòng — toàn bộ UI chính).
- `components/PatternLockScreen.tsx` (pattern lock + Gmail OTP), `PatternLock.tsx`.
- `components/GatewaysPanel.tsx` (tab "Gateway": discover → approve → adopt).
- `components/LiveCamera.tsx`, `AIConfigView.tsx`, `BoundingBoxCanvas.tsx`.

### 3.4 nvr-watchdog
- **Làm gì:** giám sát sức khỏe, tự khởi động lại container chết, OTA update (có rollback),
  cảnh báo Telegram, đo disk + dọn recording.
- **Công nghệ:** Kotlin. Modules: `DockerApi`, `SelfHealingEngine`, `UpdateManager`,
  `SystemHealthMonitor`, `NetworkMonitor`, `AlertManager`, `EventLogger`, `ReportScheduler`.
- **Đặc biệt:** `restart: always` (phải sống sót mọi crash); mount docker.sock + project dir để
  chạy được `docker compose`. Thay thế Watchtower (⚠️ vẫn còn 1 container `watchtower` mồ côi — cân nhắc xóa).

### 3.5 nvr-nginx
- HTTPS 443 → reverse proxy về `nvr-wrapper:3000`. Dùng host net. **KHÔNG tự start khi nvr-wrapper
  recreate** → luôn phải `docker compose up -d nginx` sau khi build lại app.

### 3.6 dc-bridge (sidecar Go/Pion) — BẮT BUỘC để cloud hiện Device info
- **Vì sao tồn tại:** cloud hỏi `get_information/get_ethernet/get_time` **chỉ qua WebRTC DATA channel**
  (không qua MQTT). Thư viện JVM `dev.onvoid.webrtc` không chạy được headless Linux → cần sidecar Go (Pion).
- **Build:** từ source ở `~/Documents/1CodeBkav/B02/aiview_b02_box/dc-bridge`, patch port 8080→3000,
  build linux/amd64 → drop vào `data/dc-bridge`. `DcBridgeManager` tự spawn khi backend khởi động.

---

## 4. EDGE GATEWAY (Raspberry Pi) — phần quan trọng nhất cho câu hỏi "port sang vi điều khiển khác"

### 4.1 Triết lý thiết kế (Plan A — passive)
- **Agent là HTTP SERVER, không bao giờ gọi NVR.** NVR tự quét subnet ZeroTier để tìm gateway
  (auto-scan `/health`). → decoupled, **multi-NVR ready** (nhiều NVR cùng dùng 1 gateway).
- **Pi KHÔNG giữ credentials camera.** Mỗi camera chỉ cấp 2 port nginx `stream{}` (ONVIF + RTSP).
  Mọi việc nặng (SOAP ONVIF, go2rtc restream) làm trên NVR. Admin gõ creds lúc adopt.
- **Cực nhẹ:** RAM agent ~4MB (đo thật). Mục tiêu budget 256MB.
- State lưu xuống đĩa (`/var/lib/nvr-edge-agent/state.json`) → reboot vẫn dựng lại proxy, không cần NVR.

### 4.2 Agent làm chính xác những gì (Go, ~925 dòng)
- **`main.go`** — HTTP server, các endpoint:
  - `GET /health` — báo trạng thái (gatewayId, ztAddress, ztOnline, ramMb, uptime, nginxOk, danh sách camera). *Cần fleet token nếu gọi từ xa.*
  - `GET /identity` — **no-auth (LAN)**: cho app mobile nhận diện gateway trên LAN site mà không cần token.
  - `POST /label` — **no-auth (LAN)**: đặt tên/installer/location (cosmetic, Tier 0).
  - `POST /discovery` — WS-Discovery ONVIF trên LAN, trả danh sách camera tìm thấy.
  - `POST /camera` — cấp (idempotent) cặp port ONVIF+RTSP cho 1 camera; `DELETE /camera?localIp=` để xóa.
  - `GET /cameras` — liệt kê camera đang proxy.
  - `GET /` — trang web field nhúng (embed).
- **`config.go`** — đọc toàn bộ từ env (`agent.env`). Bind `0.0.0.0:9797` (cover loopback + ZT + LAN).
- **`zerotier.go`** — gọi `zerotier-cli` (join/info/listnetworks), cache 3s.
- **`nginx.go`** — sinh file `stream.d/cameras.conf`: mỗi camera = 2 block `server{ listen <ztIP>:<port>; proxy_pass <localIp>:80/554; }`. **Chỉ bind ZeroTier IP**, không bind LAN. `nginx -t` trước khi reload (config sai không hạ proxy đang chạy).
- **`discovery.go`** — gửi WS-Discovery Probe multicast `239.255.255.250:3702`, parse ProbeMatch.
- **`state.go`** — lưu/load state JSON, cấp port từ pool (mặc định từ 8080), tránh port reserved (1984/8554/8555).

### 4.3 Bảo mật agent
- **Fleet token chung** (`secrets/edge_agent_token` trên NVR khớp `EDGE_AGENT_TOKEN` trên Pi).
- Caller không phải loopback và không phải `/identity`,`/label` → **bắt buộc Bearer token**.
- `/identity` + `/label` để hở (LAN) **có chủ đích** cho app mobile onboard — chỉ đọc/cosmetic, mọi
  thao tác đụng camera/proxy đều sau token.

### 4.4 ➡️ CÓ THỂ CHUYỂN PI3 SANG VI ĐIỀU KHIỂN KHÁC KHÔNG? (câu hỏi trọng tâm)

**Trả lời ngắn: Đổi sang một Pi/SBC Linux khác (Pi4, Pi Zero 2W, Orange Pi, x86 mini PC) thì RẤT DỄ.
Đổi sang "vi điều khiển" thực thụ (MCU: ESP32, STM32, Arduino...) thì KHÔNG khả thi.**

Phân biệt thuật ngữ rất quan trọng:
- **"Microcontroller" (MCU)** = ESP32/STM32/Arduino: không chạy Linux, RAM vài trăm KB, không có
  process/filesystem/nginx/ZeroTier. → **Không port được.** Lý do cứng:
  1. Agent là binary Go cần **Linux userspace** (gọi `exec` `zerotier-cli`, `nginx`).
  2. Cần **nginx `stream{}`** để proxy L4 TCP (ONVIF+RTSP). MCU không có.
  3. Cần **ZeroTier One** (client C++ nặng) — không chạy trên MCU.
  4. Cần filesystem ghi state JSON, đọc `/proc`.
  → Muốn dùng MCU phải viết lại hoàn toàn bằng kiến trúc khác (tự cài TCP forwarder + tự implement
     overlay) — coi như dự án mới.

- **"Single-board computer" (SBC) chạy Linux** = Pi4/Pi5/Pi Zero 2W/Orange Pi/Radxa/x86 thin client:
  **Port gần như không tốn công**, vì:
  1. Agent là **Go thuần, cross-compile dễ**: `GOOS=linux GOARCH=arm64|arm|amd64 go build`.
     `build.sh` đã có sẵn. Không có CGO (`CGO_ENABLED=0`) → binary tĩnh, chạy mọi distro.
  2. Phụ thuộc runtime chỉ là: **nginx (có module stream)** + **zerotier-one** + systemd. Cả ba có
     gói cho mọi distro Debian/Ubuntu/Alpine/Arch.
  3. `install.sh` đã tự cài và fix nginx boot-race (`ip_nonlocal_bind=1` + drop-in `After=zerotier-one`).
  4. Không phụ thuộc phần cứng Pi cụ thể (không GPIO, không camera CSI — chỉ mạng).

  **Việc cần làm khi đổi SBC:**
  - Build lại binary đúng `GOARCH` (arm64 cho Pi4/5, arm cho Pi Zero/Pi3 32-bit, amd64 cho x86).
  - Chạy `install.sh`, set `agent.env` (token + ZT network id).
  - Join ZeroTier → admin authorize trên ztncui.
  - Xong. Không đụng code.

**Tóm lại bảng quyết định:**

| Phần cứng đích | Port được? | Công sức |
|---|---|---|
| Pi3 (hiện tại) | — | đang chạy |
| Pi4 / Pi5 / Pi Zero 2W | ✅ Rất dễ | đổi GOARCH + chạy install.sh |
| Orange Pi / Radxa / SBC Linux khác | ✅ Dễ | như trên, miễn có nginx+ZT |
| x86 mini PC / thin client | ✅ Dễ | GOARCH=amd64 |
| Router OpenWrt | ⚠️ Có thể | cần nginx-stream + ZT cho OpenWrt (có nhưng RAM/flash hạn chế) |
| ESP32 / STM32 / Arduino (MCU thật) | ❌ Không | phải viết lại kiến trúc hoàn toàn |

> Ghi chú PoC: hiện chỉ 2 Pi (1 prod + 1 spare). **Quyết định 2026-06-06: KHÔNG bake golden image
> cho PoC.** Bake để dành đến khi có phần cứng chính thức/phù hợp rồi bake cho đúng phần cứng đó.

---

## 5. Data model (SQLite — bảng chính trong `Schema.kt`)

| Bảng | Vai trò | Cột đáng chú ý |
|---|---|---|
| `cameras` | **Nguồn sự thật DUY NHẤT** cho camera (Web UI + cloud dùng chung) | slug, display_name, ip, onvif_port, ptz_enabled, **ptz_mode** (auto/full/zoom), channel_id, username, **password_encrypted**, rtsp_url, **gateway_id** + **local_ip** + **assigned_rtsp_port** (camera qua gateway) |
| `gateways` | Gateway đã phát hiện/approve | id, name, zerotier_ip, agent_port, **status** (discovered/approved/online/offline), zt_address, installer, location |
| `schedules` | Lịch ghi hình | camera_slug, days(JSON), start/end time |
| `lock_config` | Pattern lock (bcrypt) | hash, gmail_hash |
| `otp_tokens` | OTP Gmail (persist, không in-memory) | email, code, expiry |
| `cloud_config` | Cấu hình kết nối cloud | domain, port, username, password_encrypted, serial, enabled |
| `cloud_cameras` | ⚠️ **Bảng cũ/legacy** — đã thống nhất về `cameras` (003), không còn là nguồn drift | (giữ lại nhưng không dùng làm source of truth) |
| `ai_events` | Sự kiện AI detect | camera_slug, label, snapshot_path, start/end time |

> Điểm thiết kế 003: trước đây cloud dùng `CloudCameras`, Web UI dùng `Cameras` → lệch nhau. Giờ
> **cả hai đi qua `CameraService.applyAndPersist*` → chung bảng `cameras`** → cloud và Web UI luôn thấy giống nhau.

---

## 6. Các luồng dữ liệu (data flows) — nắm chắc để trả lời mọi câu hỏi

### 6.1 Ba kênh dữ liệu NVR ↔ Cloud AIView

| Kênh | Vận chuyển | Dữ liệu | Phụ thuộc |
|---|---|---|---|
| **MQTT** | tcp 1883 | Camera CRUD (`channel_service`), heartbeat | mosquitto reach cloud |
| **WebRTC LIVE** | UDP/TCP qua ICE | Video stream camera | go2rtc @127.0.0.1:1984 |
| **WebRTC DATA** | UDP/TCP qua ICE | Device info, ethernet, time (`device_service`) | binary dc-bridge |

### 6.2 Thêm camera (thống nhất 003)
1. User nhập (Web UI ONVIF/RTSP **hoặc** cloud `post_channels`).
2. `CameraRouting#addCamera` (Web UI) / `CameraCommandHandler#postOrPutChannels` (cloud) parse.
3. → `CameraService.applyAndPersistFromRtsp` (đã có URL) / `applyAndPersistFromOnvif` (probe ONVIF trước).
4. `CameraService` lock `CameraConfigMutex.mutex`, làm tuần tự:
   - UPSERT bảng `cameras` (gán channel_id nếu mới).
   - Ghi `config/config.yml` (cameras + go2rtc.streams).
   - `Go2RtcClient.registerStreamSync("<chId>_main", ...)` — chờ go2rtc PUT 200 (timeout 5s).
5. Trigger `POST http://localhost:5000/api/restart` → trả `frigateRestarted: true/false`.
6. Web UI toast warning nếu false; cloud trả `frigate_restarted`.

Delete: `CameraService.removeAndPersist(slugOrChannelId)` — idempotent (DB + YAML + go2rtc + restart Frigate).

### 6.3 Live view cloud (WebRTC LIVE)
1. Cloud browser click Live → MQTT LIVE offer → `SignalingManager.handleLiveOffer()`.
2. Resolve `profileToken` → `<chId>_main` (`resolveStreamId()`).
3. POST SDP offer → `http://127.0.0.1:1984/api/webrtc?src=<chId>_main`.
4. go2rtc trả SDP answer (201) → device publish answer về cloud qua MQTT.
5. ICE negotiate → video chảy.
- **Fix quan trọng:** (a) inject TURN/ICE servers vào go2rtc (`CloudService.applyIceServersToGo2Rtc`);
  (b) thay literal `{FRIGATE_HOST_IP}` bằng LAN IP thật (`detectLanIp()`) vì Frigate 0.16.3 không substitute.

### 6.4 Camera qua gateway (Plan A) — luồng adopt
1. NVR `GatewayScanner` quét `ZT_SCAN_SUBNET` mỗi 30s → gọi `/health` mỗi host → phát hiện gateway → `status=discovered`.
2. Admin **Phê duyệt** (tab Gateway) → `status=approved`.
3. Admin **Discover** → agent WS-Discovery trả camera trên LAN site.
4. Admin **Adopt** + gõ ONVIF creds → NVR:
   - gọi agent `POST /camera` → agent cấp port ONVIF (vd 8080) + RTSP (vd 8081) trên ZeroTier IP.
   - NVR probe ONVIF **qua proxy** (`<ztIP>:8080`), rewrite RTSP host → `<ztIP>:8081`.
   - ghi Frigate/go2rtc như camera thường → live view lên hình.
   - auto-detect PTZ: gửi 1 ContinuousMove nhỏ + Stop → xem SOAP fault → set `ptz_mode` = full/zoom.

### 6.5 PTZ / ONVIF (kể cả qua proxy)
- 1 code path cho cả camera LAN trực tiếp lẫn camera sau proxy (`OnvifSoapClient` rewrite authority XAddr về `ip:onvifPort`).
- Creds: ưu tiên `user:pass` nhúng trong URL RTSP của config.yml; fallback `cameras.username` + giải mã `passwordEncrypted`.
- "Kiểu PTZ" override (`ptz_mode`): Tự động / Toàn phần / Chỉ Zoom — vì nhiều camera rẻ under-report GetNodes.

### 6.6 Onboarding gateway (004 — app mobile)
- Mục tiêu: **không bao giờ cấu hình Pi bằng tay**. Pi nướng image sẵn (agent + ZT network id + token)
  → cắm điện+mạng → app mobile chỉ làm 1 việc: thêm gateway vào NVR (+ authorize ZeroTier, đặt tên).
- Tier 0: app ↔ gateway qua LAN (`/identity`,`/label` no-auth), admin approve trên ztncui.
- 004 đã làm: Phase 0 (self-host controller), Phase 1 (agent /identity+/label), Phase 2 (Android app),
  Phase 3 (NVR DELETE cascade + enrich ztAddress/installer/location).

---

## 7. Bảo mật (đã hoàn thành)

| # | Hạng mục | Trạng thái |
|---|---|---|
| 1 | Bỏ bypass test@test.com | ✅ |
| 2 | RTSP URL validation chống SSRF (chặn loopback, link-local 169.254, host.docker.internal; cho phép LAN riêng) | ✅ |
| 3 | File lock khi đọc-ghi config (`configMutex`/`CameraConfigMutex`) | ✅ |
| 4 | Hash mật khẩu bcrypt (cost 12) + tự migrate từ SHA-256 cũ | ✅ |
| 5 | Phản hồi Frigate restart lên UI | ✅ |
| 6 | OTP Gmail persist SQLite | ✅ |
| 7 | Docker secret cho GMAIL_APP_PASSWORD | ✅ |
| 8 | Thống nhất nguồn camera (bỏ drift CloudCameras) | ✅ (003) |
| 9 | Gateway thụ động (multi-NVR, không creds trên Pi, fleet token) | ✅ (4-6 Plan A) |

---

## 8. Edge cases & sự cố đã gặp (để trả lời "mọi tình huống")

| Vấn đề | Nguyên nhân gốc | Fix |
|---|---|---|
| **Live view ĐEN + site 502 dưới tải** | `FrigateProxy` CIO `socketTimeout=0` → Ktor coi 0 ≠ vô hạn → coroutine timeout **busy-spin**, bão hòa 64 thread DefaultDispatcher | Đổi `socketTimeout = Long.MAX_VALUE`. (Chẩn: `kill -3 1` thấy 64 thread kẹt ở `Timeout$initTimeoutJob`, CPU ~300%) |
| Camera offline ngay sau khi thêm RTSP | Global hwaccel QSV không hợp GPU NVIDIA | Thêm `hwaccel_args: []` cấp camera |
| Site unreachable sau deploy app | nginx không phụ thuộc nvr-wrapper, không tự start | `docker compose up -d nginx` |
| Container dùng image cũ | docker-compose.override.yml override + cache | `docker compose build --no-cache nvr-wrapper` |
| Cloud không có Device/Ethernet/Time | thiếu binary dc-bridge (DATA channel fail) | build dc-bridge port=3000, drop vào data/ |
| `DC_OFFER_FAIL Connection refused` | dc-bridge chưa start / port 8766 bận | check `DC_BRIDGE_STARTED` log |
| dc-bridge start nhưng cloud không nhận | main.go hardcode port 8080, backend ở 3000 | patch sed rồi build lại |
| Live view SDP có `{FRIGATE_HOST_IP}` literal | Frigate 0.16.3 không substitute placeholder cho go2rtc | `detectLanIp()` replace |
| Cloud live 500 liên tục | camera offline gây go2rtc retry | xóa/tắt camera offline |
| Cloud chỉ hiện 2/N camera | cloud chỉ đếm camera thêm qua dashboard | thêm từ cloud dashboard (không phải bug) |
| `GO2RTC REGISTER ok=false` khi MQTT reconnect | stream đã tồn tại (load từ config.yml) → go2rtc reject PUT | benign, không cần fix |
| Adopt qua tab Gateway không thấy camera | list camera chỉ fetch lúc tải trang | `GatewaysPanel` gọi `onCamerasChanged` sau adopt |
| Gateway không hiện dù Pi chạy | `ZT_SCAN_SUBNET` sai / Pi chưa authorize / token lệch | `docker logs nvr-wrapper \| grep GATEWAY` |
| `otp_tokens` không tự tạo trên DB cũ | WAL behavior | tạo tay bằng sqlite3 |
| nginx boot-race trên Pi (đổi IP) | nginx start trước khi có ZT IP để bind | `ip_nonlocal_bind=1` + systemd `After=zerotier-one` |
| **Camera under-report PTZ** (`OSSD2MPVF33XPTZAI` .64) | camera PTZ thật nhưng không nhận pan/tilt qua ONVIF ContinuousMove (GetNodes chỉ Zoom) | known false-negative; cần tìm AbsoluteMove/RelativeMove/API vendor — **chưa làm** |
| Camera zoom-only thật (Bkav P200) | gửi PanTilt → fault "speed out of bounds" | để ptz_mode auto/zoom |
| Tất cả container từng tắt sạch | (watchtower mồ côi nghi can) | `docker compose up -d nvr-wrapper` + nginx; cân nhắc xóa watchtower |

---

## 9. Trạng thái dự án hiện tại (2026-06-06)

**Git:**
- Branch active: `003-cloud-data-sync`. Đã **push** tới `origin/003-cloud-data-sync`:
  - `caf36cd` 004 Phase 3 (gateway DELETE cascade + enrich)
  - `bfc8f14` 004 Phase 1 (agent /identity+/label) + Phase 2 (Android app)
  - `9646ea4` 004 self-host ZeroTier + Pi3 cutover + image prep

**Runtime (lúc kiểm tra):**
- 🔴 **NVR stack ĐANG DOWN** — không có container NVR nào chạy (chỉ `watchtower` mồ côi + `codefr_*` không liên quan). Khởi động lại: `docker compose up -d nvr-wrapper && docker compose up -d nginx`.
- 🟢 **HQ controller UP** (192.168.2.106 ping OK).
- ⚪ **Pi #1 + Pi #2 DOWN** (user xác nhận). Việc bring-up gateway #2 tạm hoãn.

**Tính năng:**
- ✅ 003 cloud data sync (camera thống nhất, live view, dc-bridge) — done.
- ✅ Gateway Plan A (passive, multi-NVR, adopt camera) — done & verify thật trên Pi.
- ✅ 004 self-host ZeroTier + onboarding app + Phase 3 — done (PoC scope 2 Pi).
- ⏳ Loose ends: xóa member test `15df2d5dc5` trên ztncui; bring-up Pi #2 (khi cần); tìm pan/tilt thật cho camera under-report.

**Quyết định chốt (2026-06-06):** PoC = 2 Pi, **không bake golden image** — chạy phần cứng đang có,
bake để dành đến khi có phần cứng chính thức/phù hợp.

---

## 10. Quy tắc vận hành phải nhớ (gotchas hay quên)

- **LUÔN `docker compose build`** (không `docker build -t ...`) vì `docker-compose.override.yml` override
  nvr-wrapper → `nvr-wrapper:latest` + `build: .`.
- **Build app:** `docker compose build --no-cache nvr-wrapper` → `up -d --force-recreate --no-deps nvr-wrapper` → `up -d nginx`.
- **Frigate version chốt cứng 0.16.3**, watchdog không OTA.
- **nginx phải start tay** sau mỗi lần recreate nvr-wrapper.
- **Network:** nvr-wrapper/nginx/watchdog dùng `network_mode: host` (cần cho ONVIF multicast); mosquitto/frigate dùng bridge `nvr-backend`.
- **Overlay khóa cứng ZeroTier** (lý do RAM + NAT) — KHÔNG đề xuất WireGuard/Tailscale/Netbird/Headscale.
- Auth API bằng curl test: cookie `NVR_SESSION=%7B%22id%22%3A%22admin%22%7D`.

---

## 11. Tài liệu tham chiếu trong repo

- `CLAUDE.md` — chỉ dẫn dự án (chi tiết build/deploy, known issues).
- `connection_to_server.md` — kết nối cloud + dc-bridge build steps + ONVIF/PTZ qua proxy.
- `gateway/` — toàn bộ artifact phía gateway (agent Pi, app enroll, controller ZeroTier, docs); xem `gateway/README.md`.
- `gateway/gateway_setup.md` — hướng dẫn dựng gateway Pi.
- `gateway/4-6-to-do-jobs.md` — kế hoạch Plan A + verify steps.
- `specs/003-cloud-data-sync/plan.md`, `specs/004-gateway-onboarding/plan.md` — plan chi tiết.
- `gateway/edge-agent/README.md` — agent Go.
- `claude_test.md` — test E2E.
```
