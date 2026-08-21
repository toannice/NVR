# Cloud Playback Debug — 2026-06-30

## 🔑 ROOT CAUSE (chốt 2026-06-30, từ B01 reference `psservice-b01`)

**Lệnh đổi bar / tua KHÔNG đi qua MQTT, cũng KHÔNG qua dc-bridge DATA peer. Chúng đi qua DataChannel nằm TRÊN CHÍNH peer WebRTC của luồng PLAYBACK** (peer đang tải video).

Bằng chứng từ `~/Documents/1CodeBkav/CodeBox01/code/psservice-b01/`:
- `cloud_connection.go:716` + `router.go:982` `DisPatCherPeer(..., isLive, uuid)` — khi `!isLive` (PLAYBACK) thì `OnDataChannel` của peer media route vào `controlRecord()`.
- 3 lệnh control đi trên data channel này (`srouteControlRecord`, router.go:930):
  - `post_select_record` (token recording + seek) = **click bar khác**
  - `post_select_record_timestamp` (profile token + timestamp) = **tua tới mốc / next-10s**
  - `post_control_record` (action play/pause/seek/scale) = play/pause/x4
- MQTT (`DisPatCherMQTT`) chỉ xử lý forward-service; dc-bridge (`DisPatCherPeerData`/ONLY_DATA) chỉ `device_service`.

**Vì sao NVR không thấy lệnh:** `SignalingManager.handlePlaybackOffer` (`SignalingManager.kt:418`) đẩy thẳng SDP offer cho **go2rtc** (`POST 127.0.0.1:1984/api/webrtc?src=...`). go2rtc sở hữu trọn peer connection + **nuốt application data channel** của cloud → lệnh `post_select_record`/`post_control_record` không bao giờ tới backend Kotlin → không có `DC_CMD_IN`. Cloud không mở được data channel nên **re-offer bar 1** (đúng triệu chứng quan sát).

→ Cả 3 hypothesis cũ (MQTT / dc-bridge / format response) đều SAI.

### ✅ ĐÃ TRIỂN KHAI (2026-06-30, chưa test cloud thật)

dc-bridge giờ **tự sở hữu peer playback** thay vì go2rtc:
- **Go (`dc-bridge/main.go`):** thêm `/playback/offer`, `/playback/source`, `/playback/stop`. Peer có **H264 track** (server chỉ nhận H264 → `-c:v copy`, KHÔNG transcode) nạp bằng `ffmpeg -re -i {VOD} -c:v copy -bsf:v dump_extra -f rtp` → UDP → Pion `WriteRTP`. Data channel của peer → `sendToJVM` (`/internal/dc/command`). Đổi bar/seek = `/playback/source` swap ffmpeg trên **cùng peer** (RTP seq rewrite monotonic + timestamp rebase để không bị coi là mất gói).
- **Kotlin:** `PlaybackPeerManager.kt` (client tới dc-bridge); `SignalingManager.handlePlaybackOffer` → dc-bridge thay go2rtc; `PlaybackCommandHandler.selectRecord/controlRecord` → `swapSource(vodUrl)` thay `registerHlsStream`; `CommandDispatcher.routeCommand` route `post_select_record`/`post_select_record_timestamp`/`post_control_record` theo **func** (component cloud gắn là `ps_service`, không phải `playback_service`).
- **Build:** Go + Kotlin compile sạch; binary `data/dc-bridge` (amd64, port→3000) đã build; `docker compose build nvr-wrapper` + recreate xong; dc-bridge `DC_BRIDGE_STARTED`, `/health` ok, ffmpeg 8.0.1 có sẵn.
- **CÒN LẠI:** test cloud thật — click bar 2 / next-10s. Theo dõi: `docker logs nvr-wrapper -f | grep -E "PLAYBACK_|DC_CMD_IN|SELECT_RECORD|CONTROL_|SWAP_SOURCE|playback"`. ⚠️ Recording H265 sẽ đen hình (copy vào track H264) → chỉnh camera xuống H264 hoặc xem WebUI.

**Hướng fix:** thiết bị phải TỰ sở hữu peer playback (Pion) — không giao go2rtc — để nhận data channel và route `post_select_record`/`post_control_record` vào `PlaybackCommandHandler` (handler đã viết sẵn, chỉ chưa bao giờ được gọi). Video lấy từ go2rtc RTSP / Frigate VOD qua ffmpeg, bơm vào Pion track; đổi bar = swap nguồn ffmpeg trên CÙNG peer (giống B01 `controlRecord` close+reopen RTSP client).

---

## Trạng thái tổng quan
Bar 1 **đã chạy đúng** (video 1x, không giật, đúng thời lượng).  
Bar 2 và next-10s **chưa hoạt động** — ~~chưa rõ cơ chế cloud gửi lệnh~~ → **đã rõ: lệnh trên data channel của peer playback, go2rtc nuốt mất (xem ROOT CAUSE trên)**.

---

## Recordings trên disk (hôm nay)

```
Tổng: 1945 segment, 3 nhóm (gap > 120s)

Bar 1: 08:21:52 → 08:23:30 VN  (11 segs,  ~2 phút)
Bar 2: 08:46:12 → 11:28:12 VN  (973 segs, ~162 phút)
Bar 3: 12:00:29 → 14:40:30 VN  (961 segs, ~160 phút)
```

Host path: `/home/toan/toanDoc/Documents/my-nvr/media/recordings/`  
Container path: `/app/media/recordings/`  
Frigate path: `/media/frigate/recordings/`

Frigate lưu theo **UTC** (không phải VN time). File `01/cam_1/21.52.mp4` = 01:21:52 UTC = 08:21:52 VN.  
Code parse `ZoneOffset.UTC` là **đúng**.

---

## Luồng hoạt động (đã verify)

### Khởi tạo playback (OK)
```
Cloud DC → post_search_calendar → trả danh sách ngày có recording
Cloud DC → post_search_findrecordings → trả 3 bar (recordingFiles[])
Cloud MQTT PLAYBACK offer → token=1:1782782512:1782782625 (bar 1)
SignalingManager → selectRecord(token) → registerHlsStream → go2rtc HLS_REGISTER ok=true
ffmpeg -re -i http://127.0.0.1:5000/vod/cam_1/start/.../end/.../index.m3u8 → MPEG-TS → HTTP → go2rtc → WebRTC
```

### Bar 1 chạy đúng
- Token `1:1782782512:1782782625` = 113 giây
- HLS_REGISTER lúc 14:29:57 → PIPE_DONE lúc 14:31:47 = **1 phút 50 giây** ✓
- Video liền mạch, không nhảy thời gian, không giật PIPE_CLOSED

---

## Các fix đã làm hôm nay

### 1. ffmpeg trong container
**Vấn đề:** `PlaybackStreamer.start()` fail vì ffmpeg chưa có trong image.  
**Fix:** Thêm `ffmpeg` vào `Dockerfile` dòng 49 (`apk add ... ffmpeg`).

### 2. Frigate VOD 503 (giới hạn ~1080 segment)
**Vấn đề:** Frigate VOD trả 503 khi query > ~1080 recordings trong 1 range.  
**Fix:** `PlaybackService.findRecordings()` cap tại 1000 segment per group (`.take(1000)`).

### 3. Bar logic: gap-based grouping (thay merged)
**Vấn đề:** Gộp tất cả vào 1 bar → video nhảy thời gian, sai.  
**Fix:** Group theo gap > 120 giây → N bar riêng biệt, đúng với các đoạn bật/tắt camera.

### 4. SSL cert bị hỏng
**Vấn đề:** `nginx/server.crt` thành directory → HTTPS không vào được.  
**Fix:** Xóa + regenerate bằng openssl + `docker rm -f nvr-nginx` + `docker compose up -d nginx`.

### 5. -re flag (quan trọng nhất)
**Vấn đề không có -re:** ffmpeg dump 113s data trong ~1 giây → go2rtc phát siêu nhanh → dừng sau 1 giây.  
**Vấn đề có -re (cũ):** Trước đây PIPE_CLOSED sau ~40s nhưng thực ra đó là recordings test ngắn ~40s (không phải bug của -re).  
**Quyết định:** Giữ `-re` — video phát đúng 1x tốc độ.

### 6. x4/scale: no-op
**Fix:** `post_control_record action=scale` → trả success (không re-encode). Video tiếp tục 1x.

### 7. Seek: re-register go2rtc (chưa test được)
**Vấn đề cũ:** Chỉ restart ffmpeg subprocess nhưng không re-register vào go2rtc → go2rtc thấy HTTP EOF → không reconnect → video đơ.  
**Fix mới:** `controlRecord seek` gọi `Go2RtcClient.registerHlsStream(activeStream, newHlsUrl)` thay vì `PlaybackStreamer.restart()`.

### 8. Token fallback cho seek
**Fix:** Thêm `activePlaybackTokens[channelId]` — lưu token khi `selectRecord` thành công. Seek dùng token này nếu cloud không gửi token trong `post_control_record`.

---

## Vấn đề chưa giải quyết

### A. Bar 2 click không tạo DC command mới

**Quan sát:** Khi user click bar 2, **không có DC_CMD_IN mới** trong log. Cloud vẫn resend PLAYBACK offer với token bar 1.

**Hypothesis:**
1. Cloud có thể gửi lệnh bar 2 qua **MQTT** (không phải DC) → cần grep `CMD_RECV` (MQTT path)
2. Cloud chỉ nhận được 1 bar từ response của ta (format response sai?)
3. Cloud tự động restart bar 1 sau khi bar 1 kết thúc, không xử lý bar 2 click

**Điều tra tiếp:**
```bash
docker logs nvr-wrapper -f 2>&1 | grep -E "CMD_RECV|DC_CMD_IN|STEP3_PLAY|CONTROL|SELECT|HLS_REG|PIPE_"
```
→ `CMD_RECV` sẽ catch MQTT-based commands (không qua DC).

**Cần xem DC_CMD_OUT** khi findrecordings để verify cloud nhận đủ 3 bar:
```bash
docker logs nvr-wrapper 2>&1 | grep "DC_CMD_OUT" | grep "findrecord"
```

### B. Next-10s không gửi lệnh nào

**Quan sát:** Bấm next-10s, không thấy DC_CMD_IN nào. Chỉ thấy video đơ.

**Hypothesis:** Tương tự bar 2 — lệnh có thể đi qua MQTT path, không phải DC.  
Hoặc WebRTC DATA connection (dc-bridge) bị ngắt sau khi PLAYBACK WebRTC thiết lập.

**Điều tra tiếp:** Xem log dc-bridge và MQTT CMD_RECV khi bấm next-10s.

---

## Cấu trúc code hiện tại

| File | Vai trò |
|------|---------|
| `services/PlaybackService.kt` | findRecordings (gap grouping), calendar, token parse, channelToSlug |
| `services/PlaybackStreamer.kt` | ffmpeg subprocess management, MPEG-TS pipe |
| `cloud/Go2RtcClient.kt` | `registerHlsStream()` → spawn ffmpeg + PUT go2rtc |
| `cloud/handlers/PlaybackCommandHandler.kt` | handle DC commands, activePlaybackStreams/Tokens |
| `routes/CloudRouting.kt` | `GET /internal/playback-ts/{name}` → pipe MPEG-TS |
| `cloud/SignalingManager.kt` | handle MQTT PLAYBACK offer → selectRecord → SDP to go2rtc |

### Token format
```
"{channelId}:{startSec}:{endSec}"
Ví dụ: "1:1782782512:1782782625"
```

### Stream name format  
```
"playback_{channelId}_{startSec}"
Ví dụ: "playback_1_1782782512"
```

### HLS URL (Frigate VOD)
```
http://127.0.0.1:5000/vod/{slug}/start/{startSec}/end/{endSec}/index.m3u8
Giới hạn: < 1080 segments trong range (hard limit Frigate SQLite)
```

### ffmpeg command (PlaybackStreamer)
```bash
ffmpeg -re -i {hlsUrl} -c:v copy -an -f mpegts pipe:1
# -re: đọc 1x tốc độ (BẮT BUỘC — không có thì dump nhanh, siêu nhanh rồi dừng)
# -an: bỏ audio
# pipe:1: MPEG-TS ra stdout
```

### go2rtc stream source
```
ffmpeg:http://172.19.0.1:3000/internal/playback-ts/{name}#video=copy
# 172.19.0.1 = Docker bridge gateway (từ Frigate container reach nvr-wrapper)
# nvr-wrapper bind 0.0.0.0 (không phải 127.0.0.1) để Frigate reach được
```

---

## ▶️ RESUME NGÀY MAI (2026-07-01) — fix đã code+deploy, cần TEST CLOUD THẬT

Trạng thái: kiến trúc dc-bridge-sở-hữu-peer-playback **đã code + build + deploy lên dev**, backend khỏe, dc-bridge STARTED. **CHƯA test cloud, CHƯA commit.**

**Việc cần làm:**
1. [ ] Mở dashboard cloud → playback → mở 1 phiên (bar 1) → **click bar 2** và **next-10s**.
2. [ ] Theo dõi log (xem lệnh debug dưới). Kỳ vọng MỚI:
   - Có `DC_CMD_RECV component=ps_service func=post_select_record` (trước đây IM HOÀN TOÀN khi click bar2).
   - → `SELECT_RECORD_OK` → dc-bridge log `playback source swapped → {vod url}`.
   - Video chuyển sang bar 2 (có thể chớp đen 1 nhịp khi swap — chấp nhận được).
3. [ ] Nếu bar 2 vẫn IM (không `DC_CMD_RECV`): data channel của peer playback chưa mở → check answer SDP dc-bridge có m=application (data channel) + ICE connect. Có thể cloud cần track video lên trước mới mở data channel → xem `playback PeerConnection state` trong dc-bridge log.
4. [ ] Nếu `DC_CMD_RECV` có nhưng `SWAP_SOURCE_FAIL`: dc-bridge `/playback/source` trả 409 = peer playback đã chết → check connection state / ffmpeg.
5. [ ] Nếu đen hình: xem recording H264 hay H265 (`ffprobe`). H265 → track H264 không decode được → chỉnh camera ghi H264.
6. [ ] Khi chạy OK → **commit** (kèm các thay đổi playback khác đang dở trên `main`).

**File đã sửa (uncommitted):**
- `dc-bridge/main.go` (ngoài repo: `~/Documents/1CodeBkav/B02/aiview_b02_box/dc-bridge/`) + binary `data/dc-bridge`
- `backend/.../cloud/PlaybackPeerManager.kt` (mới)
- `backend/.../cloud/SignalingManager.kt`, `cloud/CommandDispatcher.kt`, `cloud/handlers/PlaybackCommandHandler.kt`, `services/PlaybackService.kt`

**Rebuild dc-bridge nếu sửa lại Go:**
```bash
B=~/Documents/1CodeBkav/B02/aiview_b02_box/dc-bridge
cp "$B"/{main.go,go.mod,go.sum} /tmp/dcb && cd /tmp/dcb
sed -i 's|127.0.0.1:8080/internal/dc/command|127.0.0.1:3000/internal/dc/command|' main.go
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build -o /home/toan/toanDoc/Documents/my-nvr/data/dc-bridge -ldflags='-s -w' .
docker compose up -d --force-recreate --no-deps nvr-wrapper   # respawn dc-bridge từ binary mới
```
> ⚠️ Source gốc go.mod đã `go mod tidy` (pion/rtp thành direct) → build KHÔNG cần `-mod=mod`.

## Lệnh debug hữu ích

```bash
# Theo dõi luồng playback mới (data channel của peer playback + swap)
docker logs nvr-wrapper -f 2>&1 | grep -E "PLAYBACK_|DC_CMD_IN|DC_CMD_RECV|DC_CMD_OUT|SELECT_RECORD|CONTROL_|SWAP_SOURCE|playback"

# dc-bridge stdout (PeerConnection state, ffmpeg, source swap) nằm chung log nvr-wrapper:
docker logs nvr-wrapper -f 2>&1 | grep -i "dc-bridge"

# Frigate VOD test manual (đổi start/end theo token thật)
curl -s "http://127.0.0.1:5000/vod/cam_1/start/<startSec>/end/<endSec>/index.m3u8" | head

# Codec recording (kiểm tra H264 vs H265)
docker exec nvr-wrapper ffprobe -v error -select_streams v:0 -show_entries stream=codec_name \
  -of csv=p=0 "http://127.0.0.1:5000/vod/cam_1/start/<startSec>/end/<endSec>/index.m3u8"
```
