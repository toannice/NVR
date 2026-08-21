# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## 0. Quy tắc bắt buộc khi làm việc trên repo này

### 0.1 — Log đầy đủ để gỡ lỗi (theo đúng chuẩn log đã có)

Mọi code mới **phải** ghi log đủ để chẩn đoán từ xa (máy khách ở hiện trường, không SSH được ngay).
Backend dùng `AppLogger` ([utils/AppLogger.kt](backend/src/main/kotlin/vn/aiview/nvr/utils/AppLogger.kt)) —
**không** tự `LoggerFactory.getLogger` trừ khi có lý do rõ ràng (chỉ 6 file làm vậy, đều là ngoại lệ có chủ đích).

```kotlin
AppLogger.info(AppLogger.Module.CAMERA, "ADD_CAMERA", "GO2RTC_REGISTER_OK", "stream=${chId}_main ttl=${ms}ms")
AppLogger.warn(AppLogger.Module.CLOUD, "DC_BRIDGE", "SPAWN_RETRY", "attempt=$n err=${e.message}")
AppLogger.error(AppLogger.Module.GATEWAY, "ADOPT", "ONVIF_PROBE_FAIL", "gw=$ztIp port=$p", e)
```

Định dạng in ra: `[MODULE] [componentId] [EVENT_TYPE] | details`

| Thành phần | Quy tắc |
|---|---|
| `Module` | Chọn từ enum có sẵn: `CAMERA`, `CLOUD`, `GATEWAY`, `HEALTH`, `RECORDING`, `STORAGE`, `EVENT`, `SYSTEM`, `SECURITY`, `PLAYBACK`, `API`. Module quyết định log chảy vào file nào |
| `componentId` | Tên thao tác/thành phần, VIẾT HOA, ổn định để `grep`: `ADD_CAMERA`, `AUTH`, `DC_BRIDGE`, `PUSH_EVENT` |
| `eventType` | **Kết cục**, không phải câu văn: `..._OK` / `..._FAIL` / `..._RETRY` / `ACCEPTED` / `DUPLICATE` / `ROLLBACK`. Tên phải đọc là hiểu ngay chuyện gì xảy ra |
| `details` | Giá trị thật để lần ra nguyên nhân: id, host, port, mã lỗi, thời lượng. **Không** log pattern, mật khẩu, token, mã khôi phục |

Mỗi đường thất bại phải có **một** dòng log nói rõ *hỏng ở đâu* và *vì sao* — không nuốt exception
(`catch { }` trống là lỗi review). Với thao tác dài (restart Frigate, adopt gateway, spawn dc-bridge):
log cả lúc **bắt đầu** và lúc **kết thúc** kèm thời lượng.

Logback tách file theo module ([logback.xml](backend/src/main/resources/logback.xml)) — mọi log đều
đồng thời ra STDOUT và `logs/errors.log` nếu là ERROR:

| Logger | File | Module đổ vào |
|---|---|---|
| `CameraLog` | `logs/camera.log` | CAMERA |
| `CloudLog` | `logs/cloud.log` | CLOUD |
| `GatewayLog` | `logs/gateway.log` | GATEWAY |
| `HealthLog` | `logs/health.log` | HEALTH |
| `IntegrationAudit` | `logs/integration.log` | audit tích hợp Box |
| root | `logs/nvr.log` | SYSTEM, API, SECURITY, RECORDING, STORAGE, EVENT, PLAYBACK |

Rolling 50MB × 30 ngày, gzip vào `logs/archive/`.

### 0.2 — Changelog offline sau MỖI thay đổi

Mỗi khi có thay đổi, ghi thêm một mục vào **`/home/toan/toanDoc/Documents/1CodeBkav/ChangeLog/NVR.md`**
(file đã tồn tại — **append** vào cuối, không ghi đè). Đúng khuôn dưới đây, tiếng Việt, dấu `===============` khép mục:

```
Ngày viết : dd-mm-yyyy

Công việc : (sửa chữa | nâng cấp | thêm mới)

ngày bắt đầu : dd-mm-yyyy, ngày kết thúc : dd-mm-yyyy

hiện trạng trước khi xử lý:
(chỉ có khi là sửa chữa/nâng cấp — mô tả triệu chứng người dùng thấy VÀ nguyên nhân gốc)

Định hướng cách làm :
(các bước, đánh số)

Kết quả :
(đã làm được gì, file nào sửa, test bao nhiêu cái xanh)

Vấn đề tồn đọng:
(cái gì chưa xong, chưa test, hoặc là sự cố môi trường không phải lỗi code)

===============
```

Viết cho **người vận hành đọc**, không phải cho dev: mô tả triệu chứng nhìn thấy được trước, rồi mới
tới nguyên nhân kỹ thuật.

---

## 1. Lệnh thường dùng

### Vòng lặp dev (nhanh nhất)
```bash
./run.sh --rebuild
```
Build lại `nvr-wrapper` từ source → recreate → tự chữa kẹt file-tĩnh → đảm bảo nginx chạy. Dùng sau mỗi lần sửa code.

Thủ công (phải chạy từ project root):
```bash
docker compose build nvr-wrapper
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx
```
> ⚠️ **nginx KHÔNG tự start** khi recreate `nvr-wrapper` → quên `up -d nginx` là site unreachable.
> Chỉ dùng `--no-cache` khi cache bẩn thật (đổi base image / sửa Dockerfile); Dockerfile đã tách tầng deps nên đổi code tự bust đúng tầng.

### Build từng phần (không qua Docker)
```bash
cd frontend && npm run build      # tsc -b && vite build
cd frontend && npm run lint       # eslint
cd backend  && ./gradlew installDist
cd watchdog && ./gradlew installDist
```

### Test

**E2E trong repo** (Playwright, `tests/e2e/`):
```bash
cd tests/e2e && npm test                              # tất cả
cd tests/e2e && npx playwright test tests/security.spec.ts   # một file
cd tests/e2e && npm run test-and-report                # chạy + sinh báo cáo
```

**Khôi phục mật khẩu offline** (`tests/test_recovery.sh`) — **không tự tạo container**, phải dựng DB trắng trước:
```bash
mkdir -p /tmp/t/data /tmp/t/config && cp config/config.yml.template /tmp/t/config/config.yml
docker run -d --name nvr-test --network host -e PORT=3999 -e BIND_HOST=127.0.0.1 \
  -v /tmp/t/data:/app/data -v /tmp/t/config:/app/config nvr-wrapper:latest
bash tests/test_recovery.sh ; docker rm -f nvr-test ; rm -rf /tmp/t
```

Bộ E2E cũ hơn nằm **ngoài repo** ở `/home/toan/e2e-generator/` — chi tiết + trạng thái từng spec: [claude_test.md](claude_test.md).
Không có unit test Kotlin (`backend/src/test` không tồn tại).

### Triển khai
```bash
sudo ./install.sh          # máy mới CÓ source: .env + TLS + secrets + compose build/up (5 bước)
sudo ./install.sh --check  # chỉ kiểm tra, không đổi gì
./deploy-offline.sh        # máy mới KHÔNG source: load images.tar từ bundle (6 bước)
./dc-bridge/build.sh       # build lại sidecar Go → data/dc-bridge
curl -X POST http://localhost:5000/api/restart   # restart Frigate (áp config mới)
```

---

## 2. Kiến trúc

```
mosquitto (MQTT) → frigate (NVR engine) → nvr-wrapper (Kotlin/React)
                                                ↑
                    nvr-watchdog (self-heal) + nginx (HTTPS gateway)
```

| Container | Cổng | Ghi chú |
|---|---|---|
| `mosquitto` | `9001:1883` | Chỉ MQTT. **Không có WebSocket** — `mosquitto.conf` chưa từng khai listener websockets, cổng WS cũ đã gỡ khỏi compose |
| `frigate` | `127.0.0.1:5000`, `127.0.0.1:1984`, `8554` (loopback + `${FRIGATE_HOST_IP}`), `8555/udp` | Chốt cứng `0.16.3`, không OTA. UI gốc **không** expose ra LAN. 8554 chỉ mở cho LAN của Box — không mở qua overlay Nebula (G9) |
| `nvr-wrapper` | `127.0.0.1:3000` | App chính. `BIND_HOST` mặc định loopback → **chỉ vào được qua `https://<ip>`** |
| `nvr-nginx` | `443` (HTTPS), `9000` (HTTP) | 9000 chỉ phục vụ `/onvif/`, còn lại 301 → HTTPS. Đổi cổng phải sửa kèm `ONVIF_HTTP_PORT` |
| `nvr-watchdog` | — | Self-heal + cảnh báo Telegram. OTA **tắt cứng** (`DISABLE_OTA=true`) |

`nvr-wrapper`, `nginx`, `nvr-watchdog` chạy `network_mode: host` (cần cho ONVIF multicast/WS-Discovery).
`mosquitto` + `frigate` ở bridge net `nvr-backend`.

**Dockerfile 3 stage:** `node:22-alpine` (build React) → `eclipse-temurin:21-jdk-alpine` (Gradle `installDist`) → `eclipse-temurin:21-jre-alpine` (runtime).
Backend: Ktor 3.0.1 + Netty, Exposed + SQLite, Paho MQTT, SnakeYAML. Frontend: React 19 + Vite 7 + Tailwind 4.

### 🚨 KHÔNG có registry **RIÊNG** trên máy khách
`nvr-wrapper` luôn **build tại chỗ** (`install.sh`), **load từ `images.tar`** (bundle), hoặc **load từ gói
`.nvrup` đã ký** (OTA) — không bao giờ pull image riêng từ GHCR, không PAT, không `docker login`.
Biến điều khiển là `WRAPPER_IMAGE`; `WRAPPER_TAG` / `GITHUB_USERNAME` / `GITHUB_TOKEN` đã bị xoá.

**Pull image CÔNG KHAI theo digest thì được phép** và là đường nâng Frigate: `docker pull` image công khai
không cần credential nào (token anonymous cấp tự động), và pull là tăng dần theo layer nên nâng 0.16.3→0.17
chỉ tải phần thay đổi thay vì 1.2GB tarball. `DockerCli.pullAnonymous()` đặt `DOCKER_CONFIG` vào thư mục tạm
rỗng nên kể cả máy từng `docker login` thì lệnh vẫn đi anonymous — không thể lỡ gửi token. Chỉ áp dụng cho
image bên thứ ba đã ghim `@sha256:`; tag thuần bị từ chối (`IMAGE_REF_NOT_PINNED`).

### Đưa bản mới ra máy field — ĐÃ CÓ, hai đường dùng chung một định dạng
| Đường | Cách | Khi nào |
|---|---|---|
| Upload tay | UI LAN: `https://<ip>` → Thiết lập → **Cập nhật** → kéo file `.nvrup` | Tại hiện trường, không cần internet |
| OTA tự động | 2h sáng máy tự hỏi `<OTA_BASE_URL>/<DEVICE_MODEL>/OTA/manifest.json` | Fleet, có rollback + Telegram |

Gói `.nvrup` (là tar.gz) ký **Ed25519**, khoá sinh trên **máy air-gap**. Verify bằng `java.security` của
JDK 21 — không thêm dependency crypto. Bốn gate chính sách chặn "đúng file, sai thời điểm": `minFrom`
(bậc thang migration), `floorVersion` (chống hạ cấp), `expiresAt` (chống freeze), `minApplier`.

**Phân vai bắt buộc** — không đổi được, vì một container không thể `docker compose up -d` cho chính nó:
| Ai | Làm gì |
|---|---|
| `nvr-wrapper` | CHỈ nhận file upload + đọc `status.json` ra UI. Không verify, không apply |
| `nvr-watchdog` | Tải, verify chữ ký + sha256, 4 gate, snapshot đường lùi, `docker load`, ghi `data/ota/trigger` |
| `scripts/ota-apply.sh` (host, systemd oneshot) | Đổi container, health-check, rollback — thứ **duy nhất sống sót** khi cả wrapper lẫn watchdog bị recreate |

Container không gọi được `systemctl`; bàn giao bằng **systemd path unit** theo dõi `data/ota/trigger`.
Cài: `sudo ./scripts/install-ota.sh`. Chi tiết: [docs/ota/](docs/ota/).

⚠️ `ota-apply.sh` **luôn phải `docker compose up -d ... nginx`** — nginx không tự start khi recreate
`nvr-wrapper`, và áp OTA lúc 2h sáng rồi để site chết tới sáng là kịch bản tệ nhất của cả cơ chế.

---

## 3. Các luồng phải đọc nhiều file mới hiểu

### 3.1 Thêm/xoá camera — MỘT code path duy nhất
Web UI **và** cloud dashboard đều đi qua `CameraService`, không có đường thứ hai:

```
Web UI  → CameraRouting#addCamera ─┐
                                   ├→ CameraService.applyAndPersistFromRtsp / FromOnvif
Cloud   → CameraCommandHandler ────┘        │
                                            ├ 1. lock CameraConfigMutex.mutex
                                            ├ 2. UPSERT bảng `Cameras` (gán channel_id nếu mới)
                                            ├ 3. FrigateConfigGuard.snapshot(config.yml)
                                            ├ 4. ghi config.yml (cameras + go2rtc.streams)
                                            ├ 5. Go2RtcClient.registerStreamSync (chờ PUT 200, 5s)
                                            └ 6. restart Frigate + chờ healthy, KHÔNG lên → rollback
```
Xoá: `CameraService.removeAndPersist(slugOrChannelId)` — idempotent (DB + YAML + go2rtc + restart).

**`FrigateConfigGuard`** ([services/FrigateConfigGuard.kt](backend/src/main/kotlin/vn/aiview/nvr/services/FrigateConfigGuard.kt))
là lớp an toàn bắt buộc: config.yml sai một dòng → Frigate crash-loop → **mất cả live view lẫn ghi hình**.
Guard chụp `config.yml.last-good`, restart, poll health 90s; không khoẻ thì trả bản cũ + restart lại rồi mới báo lỗi.
Đã cứu 2 sự cố thật (zone `1.000`, `roles: []` khi tắt AI). **Mọi đường ghi config.yml mới phải đi qua guard này.**

### 3.2 Cloud — 3 kênh độc lập
| Kênh | Vận chuyển | Dữ liệu | Phụ thuộc |
|---|---|---|---|
| MQTT | tcp | Camera CRUD (`channel_service`), heartbeat | mosquitto reach cloud |
| WebRTC LIVE | ICE | Video trực tiếp | `go2rtc` @ `127.0.0.1:1984` |
| WebRTC DATA | ICE | Device info / ethernet / time (`device_service`) + playback | **binary `data/dc-bridge`** |

`dc-bridge` là **sidecar Go (Pion) bắt buộc** — JVM `dev.onvoid.webrtc` không chạy headless Linux.
Source trong repo tại `dc-bridge/` (port 3000 bake sẵn), `DcBridgeManager` tự spawn lúc backend khởi động.
Thiếu nó = dashboard cloud online nhưng **trống Device info và không playback được**.
Verify: log `DC_BRIDGE_STARTED`. Chi tiết: [connection_to_server.md](connection_to_server.md).

### 3.3 Gateway biên (Pi) — passive, đã chuyển sang Nebula
NVR **quét** subnet overlay để phát hiện gateway (`GatewayScanner`); Pi **không bao giờ** gọi ngược về NVR.
Pi không giữ credential camera — admin gõ lúc approve. Adopt dùng lại `CameraService.applyAndPersistFromOnvif`,
rewrite RTSP host về IP overlay, rồi tự dò PTZ (`autoDetectPtz` gửi thử `ContinuousMove` nhỏ, xem SOAP fault).

Overlay production đã chốt là **Nebula (MIT)** — lighthouse + signing service tự host. Code ZeroTier
(`ZtControllerClient`) vẫn biên dịch nhưng **dormant**, chỉ kích hoạt khi Nebula chưa cấu hình
(`NEBULA_CA_URL` trống). Không phát triển thêm trên nhánh ZeroTier.
Runbook: [gateway/nebula-migration-va-trien-khai.md](gateway/nebula-migration-va-trien-khai.md).

### 3.4 Khôi phục mật khẩu — hoàn toàn offline (không email)
Pattern hash bằng **bcrypt cost 12**, legacy SHA-256 tự nâng cấp khi login thành công.
Hai đường khôi phục chạy được khi mất mạng: **mã khôi phục** (4×5 Base32, bcrypt, dùng một lần) hoặc
**3 câu hỏi bảo mật** (bcrypt từng đáp án, chuẩn hoá bỏ dấu tiếng Việt). Cả hai trả `resetToken` 5 phút, dùng một lần.
Chống dò per-IP luỹ tiến, **bucket độc lập** — phá recovery không khoá được chủ máy.
Code: `RecoveryService.kt` (+ `Throttle`), `LockRouting.kt`, `PatternLockScreen.tsx`.
Đường cuối khi mất cả hai: `reset_lock.sh` tại máy (cần có mặt vật lý).

---

## 4. Ràng buộc dễ sai (đọc trước khi sửa)

- **`FRIGATE_HOST_IP` phải có giá trị trong `.env`.** Để trống thì mapping `${FRIGATE_HOST_IP}:8554:8554`
  suy biến thành `0.0.0.0` → hở RTSP ra overlay. Máy đổi IP LAN mà không cập nhật = **live view đen toàn bộ**;
  `scripts/nvr-ip-guard.sh` dò IP thật và sửa `.env` **một lần mỗi lần khởi động** (dev: `ExecStartPre` của
  `nvr-stack.service` với `--env-only`; máy khách: `nvr-ip-guard.service` lúc boot, bản đầy đủ vì container
  đã lên sẵn bằng `restart:unless-stopped`). Không quét định kỳ. Biến này **không** pass vào container `nvr-wrapper`.
- **Cần IP LAN thật thì gọi `NetUtils.detectLanIp()`** — một nguồn duy nhất, đã loại docker và **mọi overlay/VPN**
  (nebula/zt/tailscale/wg/tun), ưu tiên `192.168` hơn `10.x`. Frigate 0.16.3 không substitute `{FRIGATE_HOST_IP}`
  cho go2rtc subprocess nên chỗ nào ghi config.yml cũng phải tự thay bằng IP thật.
- **Toạ độ zone phải kẹp `[0, 0.999]`** (`sanitizeZoneCoordinates`): Frigate 0.16.3 so sánh **chuỗi**, `"1.000" > "1.0"`
  nên tưởng là pixel tuyệt đối → `int("0.000")` ValueError → crash-loop.
- **RTSP URL mới phải qua `validateRtspUrl()`** — chặn scheme lạ, loopback, link-local `169.254` (cloud metadata),
  `host.docker.internal`. LAN riêng (`192.168.x`, `10.x`) thì cho phép vì camera nằm ở đó.
- **Camera H.265 + cloud live view:** WebRTC không có profile HEVC → `SignalingManager.negotiateLiveSdp()`
  tự lùi `_sub` rồi tới stream transcode. Web UI local không dính vì dùng MSE/mp4.
- **PTZ:** một số camera chỉ quảng cáo zoom trong `GetNodes` dù có pan/tilt → có override `cameras.ptz_mode`
  (`Tự động` / `Toàn phần` / `Chỉ Zoom`). Ngược lại cũng có camera PTZ thật **không** lái được bằng ONVIF
  `ContinuousMove` — ép `full` chỉ tạo SOAP fault. Camera zoom-only thật (Bkav P200-0802N): gửi PanTilt → fault.
- **`docker-compose.override.yml` git-ignored, chỉ có trên máy dev.** Base compose đã có sẵn
  `image: ${WRAPPER_IMAGE}` + `build: .` nên override gần như thừa. Bản offline `!reset` khoá `build`.
- **Bảng `cloud_cameras` vẫn còn** cùng migration một chiều `migrateCloudCamerasIntoCameras()` — giữ để rollback,
  **không đọc/ghi vào nó** trong code mới.
- **Release bundle** (`AIView_NVR_V*/`, `*.tar.gz`) để trong `releaseVersion/` (git-ignored), không để ở project root.
  Chạy `./verify-bundle.sh` quét secret **trước khi** gửi khách.
- **Tab trong màn Thiết lập có `id` ≠ nhãn hiển thị**, và trộn Anh/Việt — sửa nhầm là tab trắng.
  Bảng ánh xạ trong [SetupView.tsx](frontend/src/components/SetupView.tsx:100):

  | `setupTab` id | Nhãn người dùng thấy | Component |
  |---|---|---|
  | `Camera List` | Danh sách Camera | inline (gồm cả nút "Thêm mới") |
  | `Recording Schedule` | Lịch trình ghi hình | `RecordingScheduleSection` |
  | `Cloud` | **Kết nối Cloud** | `CloudSettingsSection` |
  | `Gateway` | Gateway | `GatewaysPanel` |
  | `Tích hợp` | Tích hợp | `IntegrationPanel` |
  | `Bảo mật` | Bảo mật | `SecuritySection` |
  | `System Info` | Thông tin hệ thống | `SystemInfoSection` (dùng chung cho cả id `Network`, `Storage`) |

  Tab `Add Camera` chỉ còn là placeholder báo chức năng đã dời vào "Danh sách Camera".

---

## 5. Nguyên tắc dự án

[.specify/memory/constitution.md](.specify/memory/constitution.md) là nơi quyết định khi thiết kế còn mơ hồ —
đọc trước khi thêm đường ghi dữ liệu mới. Bốn nguyên tắc chính:

| | Nguyên tắc | Áp dụng |
|---|---|---|
| P1 | Một nguồn sự thật cho camera | Code camera mới gọi `CameraService`, không tự ghi config.yml/go2rtc/DB |
| P2 | Gateway biên là passive | Không bao giờ thêm đường gateway→NVR push; agent stateless với NVR |
| P3 | Bảo mật mặc định | Dùng lại `validateRtspUrl()` / `hashPattern()`, secret ở `secrets/*` (không ở `.env`) |
| P4 | Overlay production là Nebula | ZeroTier chỉ là fallback dormant, không phải đường ship |

---

## 6. Bản đồ tài liệu

[docs/INDEX.md](docs/INDEX.md) liệt kê mọi doc theo chủ đề, đánh dấu `✅` hiện hành / `📦` legacy. Các file hay cần:

| Doc | Nội dung |
|---|---|
| [connection_to_server.md](connection_to_server.md) | Kết nối cloud đầy đủ + build dc-bridge |
| [claude_test.md](claude_test.md) | Thiết lập E2E, trạng thái từng spec |
| [gateway/README.md](gateway/README.md) | Index mọi thứ phía gateway (ZeroTier-era ở `gateway/legacy/`) |
| [gateway/commercialization-security-notes.md](gateway/commercialization-security-notes.md) | So sánh license overlay + backlog bảo mật G1–G7 (PENDING) |
| [specs/003-cloud-data-sync/plan.md](specs/003-cloud-data-sync/plan.md) | Plan unified cameras + cloud live view (DONE) |

**Repo:** https://github.com/toannice/NVR.git — phát triển trực tiếp trên `main`.
