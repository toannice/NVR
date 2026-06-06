# Connection to AIView Cloud — Linux Device

Hướng dẫn deploy 1 thiết bị Linux (x86_64 hoặc arm64) kết nối tới AIView cloud server và **trả về thông tin thiết bị** (Device info / Ethernet / Time / Storage) cho dashboard.

Tham chiếu protocol gốc: `~/Documents/1CodeBkav/B02/aiview_b02_box/.claude/context/claude_cloud_connection.md`.

---

## Kiến trúc kết nối

```
        HTTP handshake (lấy creds)
NVR  ───────────────────────────────────►  Cloud HTTPS
        /p2p/api/sample/client/connect

        MQTT (commands, heartbeat, signaling)
NVR  ◄──────────────────────────────────►  Cloud MQTT broker
        sub: /send/<peerId>
        pub: /recv/<peerId>  (mainPubTopic)

        WebRTC LIVE (video stream)
NVR ── go2rtc ◄─────WebRTC────────────►   Cloud / Dashboard
        port 1984 / 8554

        WebRTC DATA (device info, ethernet, time)  ◄── đây là phần dễ thiếu
NVR ── dc-bridge ◄──WebRTC DataChannel──►  Cloud / Dashboard
        port 8766  →  http://127.0.0.1:3000/internal/dc/command
```

Trên Linux desktop/headless, **JVM `dev.onvoid.webrtc` không chạy được** (yêu cầu PulseAudio + X11). Vì vậy DATA channel được offload cho sidecar `dc-bridge` viết bằng Go + Pion WebRTC.

---

## Step 1 — Connect + trả về Device info (chi tiết)

Đây là phần dashboard sẽ hiện: **firmware, model, MAC, IP, time, ethernet info**.

### 1.1. HTTP handshake (lấy MQTT credentials)

`SignalingHttpClient.fetchMqttConnectionInfo()` gọi:

```http
POST https://{domain}:{port}/p2p/api/sample/client/connect
Authorization: Basic base64(username:password)
Content-Type: application/json

{
  "context": "signal.connect",
  "type": "new",
  "data": { "reference_id": "<serial>" },
  "auth": { "type": "inherit" },
  "version": "1.0.2.3",
  "data_encode_type": "none"
}
```

Response chứa: `peer_id`, `serverUri` (MQTT URL), `clientId`, `username/password`, `subTopic`, `pubTopic`.

NVR lưu:
- `myPeerId` = `peer_id` → `source_id` trong mọi message MQTT outgoing
- `mainPubTopic` = `functions[send].path` → **tất cả response management luôn publish vào đây**

Endpoint config trong DB (bảng `CloudConfig`): `domain`, `port`, `username`, `password` (encrypted). UI nhập ở tab P2P Settings.

### 1.2. MQTT connect + subscribe

```
mqttClient.connect(serverUri, clientId, username, password)
mqttClient.subscribe(subTopic)            // = /send/<myPeerId>
```

Khi nhận message trên `subTopic`, `SignalingManager.handleMqttMessage()` route theo `context`:

| context | type | Xử lý |
|---|---|---|
| `signal.connect.channel` | `new.response` | Tạo session mới, subscribe sessionSubTopic, publish `peer.event.join_channel` + `peer.event.ready` |
| `signal.manager` | `send-action` | `CommandDispatcher.dispatch()` — route theo `header.component` |
| `signal.webrtc` | `offer` (extend.type=LIVE) | `handleLiveOffer()` → go2rtc SDP exchange |
| `signal.webrtc` | `offer` (extend.type=DATA) | `handleDataOffer()` → forward cho `dc-bridge` (xem 1.4) |
| `signal.webrtc` | `icecandidate` | LIVE drop, DATA → forward cho `dc-bridge` |

### 1.3. Heartbeat (mỗi 5s)

`ChannelStateNotifier` publish vào `mainPubTopic`:

```json
{
  "context": "signal.message",
  "type": "event.data",
  "source_id": "<myPeerId>",
  "target_id": "server",
  "data": {
    "data": {
      "envelope": {
        "header": { "component": "notify_service" },
        "body": {
          "func": "notify_channels_state",
          "status": true,
          "data": { "status": true, "data": { "channels": [
            { "channelID": "1", "status": "connected" }
          ]}}
        }
      }
    }
  }
}
```

**TUYỆT ĐỐI KHÔNG nhét device info (time, ethernet, storage…) vào heartbeat.** Server sẽ mark device offline khi các field đó ngừng update.

### 1.4. Device info — đi qua WebRTC DATA channel (cần `dc-bridge`)

Cloud KHÔNG hỏi `get_information / get_ethernet / get_time` qua MQTT. Nó mở 1 WebRTC DATA session riêng và gửi command qua DataChannel.

Pipeline đầy đủ:

```
Cloud server → signal.webrtc/offer (extend.type=DATA, offerId=rtc_api_gate)
   ↓
SignalingManager.handleDataOffer()
   ↓
DataChannelManagerDesktop.handleOffer()
   POST http://127.0.0.1:8766/dc/offer  { sdp, ice_servers }
   ↓
dc-bridge (Go Pion WebRTC sidecar)
   ↳ thiết lập PeerConnection, gather ICE, trả answer SDP
   ↳ khi cloud gửi command qua DataChannel:
       POST http://127.0.0.1:3000/internal/dc/command  (raw JSON)
   ↓
CloudRouting.kt#`/internal/dc/command`
   → CommandDispatcher.dispatchDataChannel(rawJson) { reply -> ... }
   → routeCommand(component, func, data)
       "device" / "device_service" → DeviceCommandHandler.handle()
   → reply string → POST response → dc-bridge → DataChannel.SendText → Cloud
```

Handler map (`DeviceCommandHandler`):

| component | func | Trả về |
|---|---|---|
| `device` | `get_information` | firmware, model, manufacturer, product_number, serial, web_version, number_of_channel |
| `device` | `get_time` | timezone, current time ISO 8601 |
| `device` | `get_ethernet` | IP (real, LAN), MAC (real, exclude tailscale/tun/tap) |
| `device` | `get_wifi` | mock (NVR không có wifi) |
| `device` | `get_user_management` | mock — "admin" |
| `device` | `post_reboot` | stub log, no real reboot |
| `device_service` | `get_storages` | real disk info |

---

## Cài đặt `dc-bridge` trên Linux x86_64

Reference cung cấp binary **arm64 only**. Trên Ubuntu x86_64 phải build từ source.

### Build

```bash
# Source ở reference project (B02)
SRC=~/Documents/1CodeBkav/B02/aiview_b02_box/dc-bridge

# Patch jvmCommandURL nếu backend NVR không chạy port 8080
# Trong project này backend ở 3000:
mkdir -p /tmp/dc-bridge-build
cp "$SRC"/{main.go,go.mod,go.sum} /tmp/dc-bridge-build/
sed -i 's|http://127.0.0.1:8080/internal/dc/command|http://127.0.0.1:3000/internal/dc/command|' \
    /tmp/dc-bridge-build/main.go

# Build static binary cho linux/amd64
cd /tmp/dc-bridge-build
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 \
    go build -o /home/toan/my-nvr/data/dc-bridge -ldflags='-s -w' .
chmod +x /home/toan/my-nvr/data/dc-bridge
```

Output ≈ 10MB ELF statically linked.

### Đặt binary

`DcBridgeManager.resolveBinaryPath()` tìm theo thứ tự:

1. Env `DC_BRIDGE_PATH` (nếu set)
2. `/app/data/dc-bridge` ← Docker mount từ host `./data/dc-bridge` ⭐ khuyến nghị
3. `~/.aiview/dc-bridge`
4. `/usr/local/bin/dc-bridge`

Vì `nvr-wrapper` mount `./data → /app/data`, chỉ cần để binary ở `/home/toan/my-nvr/data/dc-bridge`. `DcBridgeManager.start()` sẽ `ProcessBuilder(path).inheritIO().start()` khi backend khởi động.

### Verify

```bash
docker compose restart nvr-wrapper
docker compose logs --tail 100 nvr-wrapper | grep -iE "DC_BRIDGE|dc-bridge"
```

Phải thấy:
```
[dc-bridge] Starting on :8766 (JVM command URL: http://127.0.0.1:3000/internal/dc/command)
[SYSTEM] [CLOUD] [DC_BRIDGE_STARTED] | dc-bridge started from /app/data/dc-bridge
```

Sau đó vào dashboard cloud refresh — log sẽ thấy:
```
[STEP3_DATA_OFFER] | offerId=rtc_api_gate channelId= → forwarding to dc-bridge
[dc-bridge] DataChannel received N bytes → forwarding to JVM
[DC_CMD_RECV] | component=device func=get_information
```

---

## Checklist deploy thiết bị Linux mới

1. ✅ Set `CloudConfig` trong DB (UI tab P2P): `domain`, `port`, `username`, `password`, `enable=true`
2. ✅ Serial number đã được nạp vào `SignalingManager.deviceSerial` (qua UI hoặc env)
3. ✅ Backend NVR (Ktor) chạy ở `127.0.0.1:3000` và có route `POST /internal/dc/command` (xem `CloudRouting.kt`)
4. ✅ go2rtc chạy ở `127.0.0.1:1984` (đã có sẵn trong Frigate container hoặc binary riêng)
5. ✅ MQTT outbound port 1883 (hoặc 8883 TLS) tới cloud broker — không bị firewall chặn
6. ✅ UDP/TCP ports cho WebRTC ICE — STUN tới `stun.l.google.com:19302`, hoặc TURN server lấy từ `/p2p/api/sample/server/resource/ices`
7. ✅ Binary `dc-bridge` đặt đúng path + executable (xem ở trên)
8. ✅ Restart `nvr-wrapper`, verify log có `DC_BRIDGE_STARTED` và `MQTT_CONNECTED`

---

## Troubleshooting

| Triệu chứng | Nguyên nhân | Fix |
|---|---|---|
| Dashboard hiện status online nhưng không có device info / ethernet | DATA channel fail (thiếu dc-bridge) | Build + drop binary vào `data/dc-bridge` |
| `DC_BRIDGE_MISSING` trong log | Binary không có ở path | Xem section build ở trên |
| `DC_OFFER_FAIL` Connection refused | dc-bridge chưa start hoặc crash | `docker compose logs nvr-wrapper \| grep dc-bridge` |
| dc-bridge start nhưng cloud không nhận response | `jvmCommandURL` trỏ sai port (8080 thay vì 3000) | Patch `main.go` rồi build lại |
| `get_channels` OK nhưng `get_information` không bao giờ vào | Server đang gửi qua DATA channel mà DATA fail | Fix dc-bridge |
| `maxChannel` luôn = 0 trên dashboard | Response gửi sai topic (session topic thay vì mainPubTopic) | `CommandDispatcher.sendResponse()` phải dùng `mainPubTopic` |
| Heartbeat ngừng → device offline | Heartbeat coroutine bị crash hoặc bị nhét device info vào | Xem `ChannelStateNotifier` — KHÔNG nhét time/ethernet vào |

---

## Error codes (`CommandDispatcher`)

| Code | Nghĩa |
|---|---|
| `I200` | Success (đặt trong `error_code` cho tất cả response OK) |
| `E0001` | Offer type not found |
| `E0007` | ONVIF probe failed hoặc internal HTTP error |
| `E0008` | Function not supported |
| `E0009` | Component not supported |

---

## ONVIF / PTZ camera field qua socat proxy

Một số camera nằm ở mạng trạm, chỉ tới được qua **socat unicast proxy** trên Edge Gateway (vd `10.34.24.12:8080` → camera `192.168.2.65:80`). Proxy là **path-transparent** (forward raw TCP, giữ nguyên HTTP path), nên ONVIF chạy bình thường qua nó.

### Điều khiển PTZ từ Web UI (không cần client riêng)

Đường PTZ production đã proxy-capable, **không cần** truyền creds hay endpoint trong request:

| Endpoint (cookie `auth-session`) | Tác dụng |
|---|---|
| `GET /api/cameras/{slug}/ptz/capabilities` | Trả `{hasPtz, hasPanTilt, hasZoom}` (đọc từ ONVIF `GetNodes`) |
| `POST /api/cameras/{slug}/ptz/move` | ContinuousMove `{pan, tilt, zoom}` |
| `POST /api/cameras/{slug}/ptz/stop` | Stop |

`getCameraOnvifConfig` resolve `ip / onvifPort / username / password` như sau, rồi `OnvifSoapClient` gọi `GetCapabilities` và **rewrite authority của Media/PTZ XAddr về `ip:onvifPort`** — cùng một code path cho camera trực tiếp lẫn sau proxy:

- **Credentials**: ưu tiên `user:pass` nhúng trong URL RTSP của `config.yml` (camera LAN); nếu URL không có creds (camera proxy/cloud, vd `rtsp://10.34.24.12:8554/camera1M`) thì **fallback sang `username` + `password_encrypted` của record DB** (giải mã bằng `CryptoUtils`).
- **ip**: host của URL RTSP, fallback `Cameras.ip`.
- **onvifPort**: luôn lấy từ `Cameras.onvif_port`.

### Cấu hình để PTZ tới được camera sau proxy

1. Camera đã thêm (RTSP) sẽ có `ip` = host của proxy (vd `10.34.24.12`) nhưng `onvif_port` mặc định = **80**, và URL RTSP thường **không kèm creds**.
2. Mở **Web UI → sửa camera**:
   - **Cổng ONVIF** = cổng proxy (vd **8080**).
   - **ONVIF Username / Password** = tài khoản ONVIF của camera (vd `admin` / `Bkav@2020`). Để trống = giữ nguyên creds đã lưu. Password lưu mã hoá (`password_encrypted`).
3. Bật PTZ cho camera (toggle trong danh sách camera).
4. Mở panel PTZ ở ô live: UI tự gọi `/ptz/capabilities` và **chỉ hiện nút mà camera hỗ trợ**.

> Verified: với `onvif_port=8080` + creds trong record, `GET /ptz/capabilities` trả `{hasPtz:true,hasPanTilt:false,hasZoom:true}` và `POST /ptz/move {zoom:0.1}` + `/ptz/stop` đều `success:true` qua proxy.

### Nhiều camera field — mỗi camera một cổng proxy

Mỗi camera ở trạm mở **một cổng socat riêng** trên gateway (Pi): vd `8080 → camera1M (P200-0802N)`, `8081 → camera2M (OSSD2MPVF33XPTZAI)`. Trong NVR, mỗi camera là một record với `onvif_port` riêng — không hardcode. Verify từng cổng:
```bash
curl -s -m6 -H "Content-Type: application/soap+xml; charset=utf-8" \
  -d '<?xml version="1.0"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><s:Body><tds:GetSystemDateAndTime/></s:Body></s:Envelope>' \
  http://10.34.24.12:8081/onvif/device_service   # đổi 8081 cho từng cam
```

### Camera zoom-only & override "Kiểu PTZ"

`checkPtzCapability` đọc `GetNodes`: `hasPanTilt`/`hasZoom` dựa trên space mà camera quảng cáo. Vấn đề thực tế: **nhiều camera PTZ rẻ chỉ quảng cáo `ContinuousZoomVelocitySpace`** dù phần cứng có pan/tilt (đã thấy ở cả `OSSD2MPVF33XPTZAI`). Khi đó auto-detect ẩn d-pad nhầm.

→ Dùng **Sửa Camera → Kiểu PTZ = Toàn phần** (lưu `cameras.ptz_mode='full'`). `/ptz/capabilities` ưu tiên override, trả `{hasPtz,hasPanTilt,hasZoom}=true` ngay (không probe), d-pad hiện. Nếu camera chấp nhận `ContinuousMove` PanTilt (firmware lỏng) → pan/tilt chạy; nếu fault "speed out of bounds" thì camera đó không hỗ trợ pan/tilt qua ONVIF (cần API riêng).

Camera **zoom-only thật** (Bkav P200-0802N): để `Tự động` hoặc `Chỉ Zoom` → UI chỉ hiện nút Zoom.

> Test connection trước khi lưu camera (`POST /api/onvif/test-connection`) vẫn nhận creds trong body — đó là chủ đích (camera chưa có trong DB). Chỉ đường PTZ live mới đọc creds từ record.
