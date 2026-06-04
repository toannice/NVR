# 4/6/2026 — TODO Jobs: Passive Gateway + Multi-NVR Auto Provisioning (Plan A)

> ## ✅ DONE 2026-06-04 — đã hiện thực + deploy + verify end-to-end trên Pi thật
> Phase 1-5 xong. Adopt camera `.64` (slug `cam_6`) qua gateway `gateway-nha-A` (ZT 10.34.24.12):
> NVR auto-scan → approve → agent cấp cổng onvif 8080/rtsp 8081 → probe ONVIF qua proxy →
> rewrite RTSP → **live view lên hình OK**; auto-PTZ ra `zoom` đúng cho .64.
> Vận hành: [`gateway_setup.md`](./gateway_setup.md). Known limitations xem cuối § Notes/risks.
>
> Kế hoạch thực thi ngày 04/06/2026. Tiếp nối tài liệu "Công nghệ cho PoC 3/6" (Google Doc)
> và mục gateway trong `CLAUDE.md`.
>
> **Bản này thay thế bản plan cũ.** Hướng cũ cho gateway *self-register / push* lên NVR đã bị bỏ.

## Quyết định đã chốt với user (KHÔNG đổi)

1. **Gateway PASSIVE** — chỉ là HTTP **server** trên ZeroTier; **không bao giờ** là client tới NVR, không self-register, không push, không heartbeat ngược. Tự động hoá của gateway **dừng đúng ở 3 việc**: (a) join ZeroTier lúc boot, (b) khi được lệnh: cấp cổng proxy ONVIF + RTSP cho camera, (c) giữ mọi thứ sống khi Pi online (systemd). Lý do: sẽ có **nhiều NVR**, gateway không được dính vào 1 NVR nào.
2. **Plan A — Pi thuần L4, KHÔNG go2rtc restream trên Pi.** Mỗi camera = **2 cổng nginx-stream** trên IP ZeroTier của Pi: `onvifPort`→cam:80, `rtspPort`→cam:554. Cả hai chỉ forward TCP. RAM Pi thấp nhất, đúng tinh thần tài liệu (go2rtc từng làm Pi quá tải 191% CPU). "Restream" vẫn do **go2rtc/Frigate của NVR** làm như hiện tại.
3. **Pi KHÔNG giữ creds camera.** Field worker tạo proxy **không cần** nhập user/pass. **NVR admin gõ creds lúc approve.**
4. **NVR auto-scan subnet ZeroTier** (vd `10.34.24.0/24`) hit `/health` để tự phát hiện gateway — đây là phần "auto connect" mà gateway không phải push.
5. **Authorize ZeroTier member: thủ công ở ZeroTier Central** (out-of-band). Không lưu ZT API token ở đâu, không couple NVR nào. (Có thể tự động hoá sau, không thuộc phạm vi hôm nay.)
6. **Field page**: agent Pi phục vụ 1 trang web tĩnh cục bộ; worker scan + tạo proxy **cục bộ trên Pi** — **không push** sang NVR. NVR tự discover camera đã proxy.

## Đòn bẩy thiết kế (giữ blast radius nhỏ)

Vẫn lưu `cameras.ip = gateway.zerotierIp` và `cameras.onvif_port = assignedOnvifPort`. → **toàn bộ code ONVIF/PTZ/live-view hiện tại chạy y nguyên**, lớp gateway chỉ *tự tính* 2 giá trị đó. Việc NVR mới duy nhất: **rewrite host RTSP** từ IP LAN camera (`192.168.2.65:554`, do ONVIF `GetStreamUri` trả) → `gateway.zerotierIp:rtspPort`.

---

## Architecture (HTTP đi 1 chiều: NVR → Pi)

```
Field worker (trang web tĩnh trên Pi; sau này mobile/PWA dùng lại cùng API)
        │ scan → chọn camera + tên  (KHÔNG nhập creds)
        ▼
Raspberry Pi 3  (10.34.24.x ZeroTier, 256MB)   ── nvr-edge-agent (Go, systemd, ~3MB) ──
  HTTP SERVER ONLY (bind ZT IP + 127.0.0.1; Bearer token fleet dùng chung)
  ├─ GET  /health      → {status, ramMb, uptime, nginxOk, gatewayName, fieldSubnet, cameras:[…]}
  ├─ POST /discovery    → WS-Discovery multicast LAN hiện trường → [{ip,name,hardware,xaddrs}]
  ├─ POST /camera {localIp,name} → cấp onvifPort+rtspPort, ghi 2 block nginx stream, reload, lưu state → {onvifPort,rtspPort}
  ├─ GET  /cameras      → list camera đã proxy (state) — NVR đọc cái này để adopt
  ├─ DELETE /camera?localIp=… → xoá 2 block + reload
  └─ GET  /  (trang field tĩnh, gọi /discovery + /camera của chính agent)
  boot: zerotier-cli join <netid> → DỪNG. Không gọi NVR.
  nginx stream{} (1 file /etc/nginx/stream.d/cameras.conf): mỗi cam 2 server{} (onvif/rtsp). socat bị thay bằng nginx.

        ▲  (NVR là client; nhiều NVR cùng gọi, idempotent)
        │  HTTP qua ZeroTier (mọi action do NVR khởi xướng)
NVR(s) (Ubuntu, Kotlin/Ktor)
  ├─ bảng Gateways (status discovered/approved/online/offline) + cameras.gateway_id/local_ip/assigned_rtsp_port
  ├─ GatewayScanner: quét subnet ZT → /health → upsert gateway "discovered"
  ├─ EdgeAgentClient (HttpClient, timeout ngắn + retry 1 lần, bearer fleet token)
  ├─ Admin approve gateway → adopt camera: GỖ CREDS → probe ONVIF qua proxy → GetStreamUri
  │     → rewrite host RTSP → gateway:rtspPort → CameraService (tái dùng) → Frigate/go2rtc NVR
  └─ auto-PTZ probe (move nhỏ + stop qua onvifPort)
```

---

## Phase 1 — Data model (NVR backend)

**`backend/.../models/Schema.kt`**
- Bảng **`Gateways`** mới:
  - `id` (PK varchar, uuid ngắn), `name`, `zerotierIp` (varchar nullable), `agentPort` (int default `9797`),
    `status` (varchar: `discovered`/`approved`/`online`/`offline`), `fieldSubnet` (varchar nullable, vd `192.168.2.0/24`),
    `lastSeen` (datetime nullable), `createdAt`/`updatedAt`.
  - **KHÔNG** có `agentTokenEncrypted` (token là fleet-secret dùng chung, xem Phase 5) và **KHÔNG** có ZT memberId/API field (authorize thủ công).
- Cột nullable mới cho **`Cameras`** (chỉ metadata reprovision — live path vẫn đọc `ip`/`onvifPort`):
  - `gatewayId` (varchar nullable, index), `localIp` (varchar nullable, IP camera trong LAN field), `assignedRtspPort` (int nullable).
  - (`assignedOnvifPort` = `onvifPort` đã có sẵn.)

**`backend/.../plugins/Databases.kt`**
- Thêm `Gateways` vào `createMissingTablesAndColumns(...)`. Exposed tự `ALTER TABLE ADD COLUMN` cho cột nullable + tạo bảng mới — không cần migration tay.

---

## Phase 2 — Edge agent (Go, trên Pi) — HTTP SERVER ONLY

Thư mục mới **`edge-agent/`** (Go module, no framework; build `linux/arm64` cho Pi 3 64-bit + `linux/arm` cho Debian 32-bit). Học ergonomics build/launch từ `data/dc-bridge` (1 binary static, `-ldflags=-s -w`).

**Auth**: mọi request cần `Authorization: Bearer <FLEET_TOKEN>` (token dùng chung cả fleet, nạp từ `agent.env`). Bind **chỉ** vào IP ZeroTier + `127.0.0.1` (không mở ra LAN field công cộng — đúng quy tắc `bind=` trong tài liệu).

Endpoints (tất cả là server, agent **không bao giờ** gọi NVR):
- `GET /health` → `{status, ramMb, uptime, nginxOk, gatewayName, fieldSubnet, cameras:[{localIp,name,onvifPort,rtspPort}]}`. Dùng cho NVR auto-scan + poll.
- `POST /discovery` `{subnet?}` → **WS-Discovery multicast** trên LAN field, trả `[{ip, name, hardware, xaddrs}]`. Agent **chỉ** discovery (nhẹ); **không** làm SOAP nặng.
- `POST /camera` `{localIp, name}` → cấp **2 cổng trống** từ pool (≥8080, tránh 1984/8554/8555): thêm 2 block `server{}` (onvif→:80, rtsp→:554) vào `stream.d/cameras.conf`, `nginx -s reload`, lưu state file, trả `{onvifPort, rtspPort}`. **Idempotent theo `localIp`**. **Không nhận/không lưu creds.**
- `GET /cameras` → đọc state, list camera đã proxy. (NVR adopt từ đây.)
- `DELETE /camera?localIp=…` → xoá 2 block + reload + state.
- `GET /` → **trang web tĩnh** field: Scan (gọi `/discovery`) → chọn camera + nhập **tên** → Submit (gọi `/camera`). Không có ô creds. Cùng API này về sau làm nền cho mobile/PWA.

Boot & resilience:
- `zerotier-cli join <NETWORK_ID>` (network id nướng trong image/`agent.env`), chờ có IP → **DỪNG** (không liên hệ NVR).
- **State file** (vd `/var/lib/nvr-edge-agent/state.json`) ghi mọi proxy đã cấp; lúc start agent **dựng lại** `cameras.conf` từ state rồi reload → proxy sống lại sau reboot.
- Ship **`nvr-edge-agent.service`** systemd (`Restart=always`) + **`edge-agent/install.sh`** cho non-IT: cài nginx (bật module `stream`, `include /etc/nginx/stream.d/*.conf`), drop binary + unit + `agent.env` (ZeroTier network id, fleet token), enable. Document **image SD nướng sẵn** = đường zero-touch.
- **Thay socat bằng nginx stream** (tài liệu §IV.1): 1 file config, ~5–8MB, gộp mọi camera, reload động không gián đoạn RTSP đang chạy.

---

## Phase 3 — NVR backend (Kotlin)

**`backend/.../gateway/EdgeAgentClient.kt`** (mới) — Ktor `HttpClient` tới agent qua ZeroTier; style `Go2RtcClient` (timeout ngắn vì link ZT chập chờn + retry 1 lần; bearer fleet token). Methods: `health`, `discover`, `createProxy`, `listCameras`, `deleteProxy`.

**`backend/.../services/GatewayScanner.kt`** (mới) — auto-discovery:
- Quét subnet ZeroTier (cấu hình ở `.env`, vd `10.34.24.0/24`) gọi `GET /health` (timeout ngắn, song song có giới hạn).
- Mỗi agent trả OK → upsert `Gateways` status `discovered` (hoặc cập nhật `online`/`lastSeen` nếu đã approved).
- Chạy định kỳ (tái dùng cadence heartbeat của `ChannelStateNotifier`, ~5–10s) để cập nhật `online/offline`.

**`backend/.../services/GatewayService.kt`** (mới):
- `approve(id)` → set `approved` (chỉ đánh dấu tin cậy — **không** gọi ZT API; authorize member làm thủ công ở ZT Central).
- `listAdoptableCameras(id)` → `EdgeAgentClient.listCameras` của gateway (camera đã proxy nhưng NVR chưa adopt).
- `adoptCamera(gatewayId, localIp, user, pass, name)` — **admin gõ creds ở đây**:
  1. Lấy `onvifPort`,`rtspPort` từ state agent (qua `/cameras`).
  2. `CameraService.applyAndPersistFromOnvif(channelId, host=gateway.zerotierIp, port=onvifPort, user, pass, displayName=name, rtspProxyHostPort="${zerotierIp}:${rtspPort}")` — **tái dùng**; probe ONVIF qua proxy, ghi config Frigate/go2rtc NVR.
  3. Lưu `gatewayId`, `localIp`, `assignedRtspPort` vào dòng `cameras`.

**`backend/.../services/CameraService.kt`** — 2 bổ sung:
- **Rewrite host RTSP cho camera gateway.** ONVIF `GetStreamUri` trả RTSP chứa IP LAN camera (`192.168.2.65:554`) — go2rtc NVR không tới được. Thêm param tuỳ chọn `rtspProxyHostPort` vào `applyAndPersistFromOnvif`: khi có, rewrite authority RTSP thành `gateway.zerotierIp:rtspPort` (giữ path, nhúng creds admin gõ) trước `writeYaml`/mutex. **LAN path (không gateway) không đổi.** Giữ `#tcp` (RTSP interleaved chui được qua 1 cổng forward; UDP thì không).
- **Auto PTZ detection** (hiện thực TODO `CLAUDE.md`): sau adopt, gửi 1 `ContinuousMove` pan nhỏ (x≈0.05) + `Stop` ngay qua `onvifPort`; 200/no-fault ⇒ `ptzMode="full"`, fault "speed out of bounds"/space ⇒ `"zoom"`. Chạy 1 lần, timeout ngắn + retry, **không bao giờ đè `ptzMode` thủ công**. Tái dùng `OnvifSoapClient.continuousMove`/`stopMove`.

**`backend/.../routes/GatewayRouting.kt`** (mới), đăng ký trong `Routing.kt`:
- `GET /api/gateways` (UI list + status live từ scanner).
- `POST /api/gateways/scan` (kích hoạt quét ngay) — hoặc để scanner tự chạy nền.
- `POST /api/gateways/{id}/approve`.
- `POST /api/gateways/{id}/discover` → proxy `EdgeAgentClient.discover` (admin xem camera field thấy gì).
- `GET  /api/gateways/{id}/adoptable` → `listAdoptableCameras`.
- `POST /api/gateways/{id}/cameras` `{localIp,user,pass,name}` → `adoptCamera` (creds ở đây).
- **KHÔNG** có `/register` hay `/pending-cameras` (gateway không push).

**Dọn hardcode:** bỏ/parameter hoá `OnvifCameraClient.DEFAULT_ENDPOINT` (`10.34.24.12:8080`) để path proxy `/api/onvif/ptz` + `/test-connection` nhận endpoint gateway tường minh; path DB-backed `/api/cameras/{id}/ptz/*` đã resolve động, không cần sửa.

---

## Phase 4 — Frontend (`frontend/src/App.tsx` + `GatewaysPanel.tsx` mới)

- Tab/section **"Gateways"** mới:
  - List gateway tự phát hiện qua scan + badge status (discovered/online/offline, lastSeen); nút **Approve** cho `discovered`.
  - Mỗi gateway approved: **Adoptable cameras** (từ agent `/cameras`) → mỗi camera nhập **user/pass + tên** → **Thêm** (gọi `/api/gateways/{id}/cameras`). Có nút **Thêm tất cả** (lặp adopt từng camera, dùng creds đã nhập).
  - (Tuỳ chọn) **Discover** trực tiếp từ NVR khi field chưa tạo proxy.
- Tái dùng list camera + `PtzControls` — camera gateway hiện y hệt vì là dòng `cameras` bình thường.

---

## Phase 5 — Onboarding / config / docs

- **`.env`**: `ZT_NETWORK_ID`, `ZT_SCAN_SUBNET` (vd `10.34.24.0/24`), `EDGE_AGENT_PORT=9797`.
- **`secrets/edge_agent_token`** (Docker secret, git-ignored, như `secrets/gmail_app_password`) — **fleet token dùng chung**: cùng giá trị nạp vào mọi NVR (secret) và mọi Pi (`agent.env`). Vì agent chỉ bind interface ZeroTier (mesh đã authorize) → 1 token chung là defense-in-depth chấp nhận được; tài liệu rõ tradeoff.
- **Authorize ZeroTier member: thủ công** — admin vào ZeroTier Central tick `authorized` cho node Pi mới. (Tự động qua ZT API là việc tương lai, ngoài phạm vi.)
- **`gateway_setup.md`** mới (cạnh `connection_to_server.md`): image sẵn, cài agent (`install.sh`), join ZeroTier, authorize ở ZT Central, luồng scan→approve→adopt ở NVR, port pool, troubleshooting. Cập nhật bảng codebase + Known-Issues trong `CLAUDE.md`, đánh dấu hoàn tất TODO 2026-06-04.
- **Sau (phase cuối): mobile app / PWA** — dùng lại cùng agent API + NVR endpoints, không rework backend.

---

## Critical files

| File | Thay đổi |
|------|----------|
| `backend/.../models/Schema.kt` | Bảng `Gateways` (no token/ZT-API field); cột nullable `gatewayId`/`localIp`/`assignedRtspPort` cho `Cameras` |
| `backend/.../plugins/Databases.kt` | Đăng ký `Gateways` trong `createMissingTablesAndColumns` |
| `backend/.../gateway/EdgeAgentClient.kt` | **Mới** — HTTP client tới agent Pi (bearer fleet token, timeout+retry) |
| `backend/.../services/GatewayScanner.kt` | **Mới** — auto-scan subnet ZT, upsert/health gateway |
| `backend/.../services/GatewayService.kt` | **Mới** — approve / listAdoptable / adoptCamera (creds ở approve) |
| `backend/.../services/CameraService.kt` | `rtspProxyHostPort` rewrite host RTSP; auto-PTZ probe (tái dùng `applyAndPersistFromOnvif`) |
| `backend/.../routes/GatewayRouting.kt` | **Mới** — endpoints gateway (no register/no push); đăng ký `Routing.kt` |
| `backend/.../routes/OnvifRouting.kt` (`OnvifCameraClient`) | Bỏ hardcode `10.34.24.12:8080` |
| `edge-agent/` (Go) | **Mới** — HTTP server, nginx-stream mgmt, state file, trang field, systemd unit, install.sh |
| `frontend/src/App.tsx` (+ `GatewaysPanel.tsx`) | Tab Gateways: scan list, approve, adoptable + creds, Thêm tất cả |
| `.env` / `secrets/edge_agent_token` / `gateway_setup.md` / `CLAUDE.md` | Config + docs |

---

## Verification (end to end)

1. **Build & deploy NVR**: `docker compose build --no-cache nvr-wrapper && docker compose up -d --force-recreate --no-deps nvr-wrapper && docker compose up -d nginx`. Kiểm tra schema: `sqlite3 data/nvr.db '.schema gateways'` và `.schema cameras` (có `gateway_id`/`local_ip`/`assigned_rtsp_port`).
2. **Agent**: build `linux/arm64`, `install.sh` trên Pi, `systemctl status nvr-edge-agent`. Từ NVR host (qua ZT): `curl -H "Authorization: Bearer <token>" http://10.34.24.12:9797/health` trả OK. `netstat -tulnp | grep nginx` sau khi tạo proxy thấy 2 cổng/cam.
3. **Onboard**: cấp nguồn Pi → tự join ZeroTier → **authorize thủ công ở ZT Central** → NVR auto-scan thấy gateway **discovered** → **Approve**.
4. **Field tạo proxy** (Pi): mở `http://<pi-zt-ip>/` → Scan → chọn `192.168.2.65` + tên → Submit. Xác nhận agent thêm **2 block** nginx stream (onvif+rtsp) + reload; **không** hỏi creds; state file ghi lại.
5. **NVR adopt**: tab Gateways → gateway → **Adoptable** thấy camera → **gõ user/pass + tên** → Thêm. Xác nhận dòng `cameras` có `ip=<zt_ip>`, `onvif_port=<onvifPort>`, `gateway_id`, `local_ip`, `assigned_rtsp_port`; `config/config.yml` có go2rtc stream với host RTSP đã **rewrite** (`10.34.24.12:<rtspPort>`, KHÔNG phải `192.168.2.65`).
6. **Connect/Live/PTZ camera khác mạng**: ONVIF GetDeviceInformation OK qua proxy; live view chạy ở UI local + cloud (`{channelId}_main`); `/api/cameras/{slug}/ptz/move` điều khiển được.
7. **Auto-PTZ**: không set `ptz_mode` tay; d-pad hiện iff probe thành công; override tay vẫn thắng.
8. **Multi-NVR**: NVR thứ 2 (cùng ZT, cùng fleet token) auto-scan thấy **cùng** gateway, adopt **cùng** camera độc lập (idempotent ở agent), không đụng NVR thứ 1. Gateway **không** biết/không liên hệ NVR nào.
9. **Resilience**: reboot Pi → systemd restart agent → dựng lại `cameras.conf` từ state → proxy quay lại; NVR scan thấy `online` lại. Link ZT chập chờn: timeout+retry giữ UI NVR mượt.
10. Chạy bộ E2E theo `claude_test.md`.

---

## Notes / risks

- **Phân vai rõ:** Pi = L4 thuần (2 cổng TCP/cam, không creds, không go2rtc session) → RAM thấp nhất, đúng bài học tài liệu (go2rtc từng 191% CPU). Mọi SOAP/probe/PTZ/rewrite/Frigate = NVR.
- **RTSP phải TCP** (`#tcp`): interleaved chui qua 1 cổng forward; UDP RTP thì không (cổng RTP rời không được proxy). Dự án đã mặc định `#tcp`.
- **Băng thông video** Pi→ZeroTier→NVR: P2P trực tiếp thì mượt; relay qua root ZT thì chậm hơn — vấn đề mạng, không phải chức năng.
- **ZeroTier free** không custom route → vì vậy proxy ở L4; authorize member thủ công ở ZT Central (tự động hoá qua ZT API để sau).
- **Bảo mật agent**: bind chỉ interface ZeroTier + `127.0.0.1`; bearer fleet token (Docker secret/`agent.env`); Pi **không** lưu mật khẩu camera (creds chỉ ở NVR DB, đã mã hoá `passwordEncrypted`).
- **Link ZeroTier chập chờn** (note `CLAUDE.md`): mọi call agent timeout ngắn + retry; auto-PTZ probe 1 lần, không đè lựa chọn tay.
- **⚠️ Auto-PTZ classifier chưa đủ với camera under-report nặng (verified 2026-06-04):** `OSSD2MPVF33XPTZAI` (.64/8081) là PTZ thật nhưng `GetNodes` không khai PanTilt space VÀ `ContinuousMove` PanTilt/GenericSpace bị fault "speed out of bounds" → probe ContinuousMove (Phase 3) sẽ **false-negative** (báo zoom-only nhầm). Phải nâng classifier: thử thêm `AbsoluteMove`/`RelativeMove` và/hoặc velocity space lấy từ `GetConfigurations`/`GetConfigurationOptions`, hoặc cho phép override thủ công **có ghi phương thức pan**. Cần 1 việc khảo sát riêng: dò đúng cách .64 nhận lệnh pan (chưa làm).
</content>
</invoke>
