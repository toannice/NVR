# Live View (Web UI) — Chẩn đoán & Hướng dẫn sửa

> Tài liệu cho Claude Code làm việc trong repo `~/my-nvr`.
> Mục tiêu: Web UI cục bộ (React do `nvr-wrapper` serve) phát được video live.
> **Lưu ý phân biệt:** đây là live view **Web UI cục bộ** (MSE/fMP4 qua HTTP), KHÔNG phải
> live view **cloud dashboard** (WebRTC). Hai đường hoàn toàn khác nhau — xem mục cuối.

---

## 1. Đường đi của Live View Web UI (xác định từ source)

```
React <LiveCamera name={cam.id}>            frontend/src/components/LiveCamera.tsx
  → fetch("/api/frigate/go2rtc/stream.mp4?src=<slug>")   (MSE, fMP4 over HTTP)
  → nginx :443  → nvr-wrapper :3000
  → FrigateProxy.kt  route "/api/frigate/go2rtc/{path...}"
  → http://localhost:1984/api/stream.mp4?src=<slug>      (go2rtc nhúng trong Frigate)
  → go2rtc kéo RTSP từ camera theo config.yml > go2rtc.streams[<slug>]
```

- `cam.id` = **slug** camera (vd `ai_view_p200_0802n_192_168_2_65`), trả từ `GET /api/cameras`
  (`CameraRouting.kt`, `id = slug`).
- Slug đó PHẢI tồn tại trong `config/config.yml` mục `go2rtc.streams`. Hiện tại có đủ.
- MSE-over-HTTP **không dùng WebRTC/ICE** → vấn đề `{FRIGATE_HOST_IP}` / TURN / ice_servers
  **không liên quan** đến live view Web UI. Đừng đi sửa nhầm chỗ đó.

---

## 2. Triệu chứng (bằng chứng từ log thật `logs/nvr.2026-06-01.log`)

| Mã | Số lần | Ý nghĩa |
|----|--------|---------|
| **500 Internal Server Error** trên `/api/frigate/go2rtc/stream.mp4` | **5161** | go2rtc nhận request nhưng **không mở được nguồn RTSP** → trả 500 sau ~2–3s |
| 502 Bad Gateway (`Error proxying to go2rtc`) | 162 | proxy không kết nối được go2rtc / kết nối bị reset / pool cạn |
| request kéo dài 879s … 2.7h rồi 502 | rải rác | đây là stream **đang chạy tốt** bị cắt khi service restart — KHÔNG phải lỗi |

→ Vấn đề chính: **go2rtc trả 500 = nguồn RTSP của camera không truy cập được / sai cấu hình.**
Frontend retry mỗi 3s (xem `LiveCamera.tsx` setMseKey) nên 500 dồn lên hàng nghìn lần.

---

## 3. Nguyên nhân gốc

### 3.1 (Chính) Nguồn RTSP hỏng → go2rtc 500

Trong `config/config.yml > go2rtc.streams`:

```yaml
65_10_34_24_12:  [ rtsp://10.34.24.12:8554/camera1M#tcp ]   # subnet 10.34.x — no route to host
64_10_34_24_12:  [ rtsp://10.34.24.12:8554/camera2M#tcp ]   # offline
ai_view_ossd2mpvf33xptzai_192_168_2_64: [ rtsp://...@192.168.2.65:554/profile1/live#tcp ]  # slug .64 ↔ URL .65  (HOÁN ĐỔI)
ai_view_p200_0802n_192_168_2_65:        [ rtsp://...@192.168.2.64:554/profile1/live#tcp ]  # slug .65 ↔ URL .64  (HOÁN ĐỔI)
```

- `10.34.24.12`: khác subnet, không route được → go2rtc 500 vĩnh viễn.
- `.64 ↔ .65`: slug và IP trong URL bị hoán đổi. Nếu path/credential không khớp đúng model
  camera ở IP đó → 500. Tối thiểu cũng gây hiển thị sai nhãn.

### 3.2 (Khuếch đại — đây mới là thứ làm CHẾT TOÀN BỘ grid) Proxy không có timeout + dùng chung 1 client

`backend/.../routes/FrigateProxy.kt`:

```kotlin
val proxyClient = HttpClient(CIO) {
    expectSuccess = false
    engine { requestTimeout = 0 }   // ❌ tắt timeout cho mọi request
}
```

- `requestTimeout = 0` áp cho **tất cả** request đi qua client này (stream.mp4, latest.jpg, /api/...).
- Camera offline → frontend retry stream.mp4 mỗi 3s → mỗi request **treo** chờ go2rtc thay vì fail nhanh.
- CIO mặc định giới hạn ~100 connection/route. Các request treo tới cùng host `localhost:1984`
  **ăn hết pool** → request của camera ĐANG SỐNG cũng không xin được connection → 502.
- Kết quả: **1 camera offline có thể làm đen toàn bộ Live View** của các camera khác.

### 3.3 (Phụ) Frontend không fallback

`LiveCamera.tsx`: khi MSE lỗi chỉ `setMseKey(k+1)` retry vô hạn mỗi 3s, **không bao giờ**
tự chuyển sang `mode='snapshot'`. Người dùng thấy ô đen / spinner mãi.

---

## 4. Cách xác minh trên thiết bị (chạy đúng các lệnh này trước khi sửa)

```bash
# 1) Stack có chạy không
docker ps --format '{{.Names}}\t{{.Status}}' | grep -E 'frigate|nvr-wrapper|mosquitto|nginx'

# 2) go2rtc thấy stream nào, producer có lỗi không (state, recv bytes)
curl -s http://localhost:1984/api/streams | python3 -m json.tool

# 3) Test TỪNG src — xem cái nào 500. Đổi <slug> theo go2rtc.streams trong config.yml
for s in ipcamera_192_168_2_3 ai_view_p200_0802n_192_168_2_65 \
         ai_view_ossd2mpvf33xptzai_192_168_2_64 65_10_34_24_12 64_10_34_24_12; do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 \
         "http://localhost:1984/api/stream.mp4?src=$s")
  echo "$s -> HTTP $code"
done
# 200 = OK; 500 = nguồn RTSP hỏng; 404 = src không có trong go2rtc config

# 4) Producer error chi tiết từ go2rtc (log Frigate)
docker logs frigate 2>&1 | grep -iE "go2rtc|rtsp|error" | tail -50

# 5) Kiểm tra camera có thật sự reachable từ host không
ffprobe -rtsp_transport tcp -i "rtsp://admin:Bkav@2020@192.168.2.64:554/profile1/live" 2>&1 | head
```

Ghi lại `slug -> HTTP code`. Mọi sửa chữa bám theo kết quả bước (3).

---

## 5. Việc cần làm (theo thứ tự)

### Bước A — Dọn nguồn hỏng (sửa được ngay, hết phần lớn 5161 lỗi 500)
1. Xoá hoặc tắt 2 camera `10.34.24.12` (`65_10_34_24_12`, `64_10_34_24_12`) nếu thật sự offline:
   xoá qua Web UI (`POST /api/delete-camera`) hoặc bỏ khỏi `config/config.yml` (cả `cameras:` lẫn
   `go2rtc.streams:`) rồi `curl -X POST http://localhost:5000/api/restart`.
2. Sửa hoán đổi `.64 ↔ .65`: dùng bước 4.3/4.5 để xác định IP nào trả 200 với path/credential nào,
   rồi chỉnh `go2rtc.streams` cho slug khớp đúng IP camera. Đừng đoán — verify bằng `ffprobe`.

### Bước B — Cô lập lỗi ở proxy (để 1 camera hỏng KHÔNG kéo sập các camera khác)
Sửa `backend/src/main/kotlin/vn/aiview/nvr/routes/FrigateProxy.kt`:

- **Tách 2 client**: một client cho stream dài (stream.mp4) và một cho request thường (status,
  snapshot, /api/...). Hoặc tối thiểu đặt timeout hợp lý + nâng giới hạn connection:

```kotlin
import io.ktor.client.plugins.*

// Client cho stream dài: KHÔNG giới hạn tổng thời gian, NHƯNG có connect/socket-idle timeout
val streamClient = HttpClient(CIO) {
    expectSuccess = false
    engine {
        requestTimeout = 0
        endpoint {
            connectTimeout = 5_000          // fail nhanh nếu go2rtc/nguồn không phản hồi
            socketTimeout  = 15_000         // cắt khi nguồn “treo” không gửi byte
            maxConnectionsPerRoute = 256    // tránh cạn pool khi nhiều camera
            maxConnectionsCount    = 1000
        }
    }
}
```

- **Quan trọng:** khi go2rtc trả **không-2xx** (vd 500), **đừng** mở `respondBytesWriter` để stream
  body lỗi; hãy trả luôn status đó về browser để frontend fallback nhanh:

```kotlin
}.execute { response ->
    if (!response.status.isSuccess()) {
        call.respond(response.status)   // 500/404 → trả thẳng, không giữ connection
        return@execute
    }
    // ... copy header + body như cũ
}
```

### Bước C — Log src để debug được (access log đang mất `?src=`)
Trong route `/api/frigate/go2rtc/{path...}`, log `path` + query khi lỗi:

```kotlin
call.application.environment.log.warn("go2rtc proxy fail src=${call.request.queryParameters["src"]} status=${response.status}")
```

### Bước D — Frontend fallback (UX)
`frontend/src/components/LiveCamera.tsx`: sau N lần MSE lỗi liên tiếp (vd 3) hoặc khi nhận
HTTP 500, `setMode('snapshot')` thay vì retry vô hạn. Snapshot dùng `/api/frigate/api/<name>/latest.jpg`
(đã có sẵn trong component). Cho nút “Thử lại” quay về `mse`.

### Bước E — Build & verify
```bash
docker compose build --no-cache nvr-wrapper
docker compose up -d --force-recreate --no-deps nvr-wrapper
docker compose up -d nginx
# Lặp lại bước 4.3: tất cả camera ONLINE phải -> HTTP 200
```

---

## 6. Cloud live view (WebRTC) — KHÁC, đừng lẫn

Live view trên **cloud dashboard** đi qua WebRTC (`SignalingManager.handleLiveOffer` →
`POST 127.0.0.1:1984/api/webrtc`). Đường đó mới phụ thuộc `{FRIGATE_HOST_IP}`, TURN, `ice_servers`
(xem `CLAUDE.md` mục “Cloud Live View — Root Cause & Fix (003)”). Log mới nhất vẫn thấy
`GO2RTC_CANDIDATES_RAW = '{FRIGATE_HOST_IP}:8555'` → placeholder CHƯA được thay bằng IP LAN thật,
nên cloud live view vẫn có thể fail. Nhưng đó là việc tách biệt với Web UI cục bộ ở tài liệu này.

---

## 7. Tóm tắt 1 dòng

Web UI không live được vì **go2rtc trả 500 cho `stream.mp4` do nguồn RTSP hỏng/offline**
(`10.34.24.12` + hoán đổi `.64↔.65`), và **proxy `FrigateProxy.kt` tắt timeout + dùng chung pool**
khiến camera hỏng làm cạn connection → kéo sập cả camera đang sống. Sửa nguồn + cô lập proxy +
fallback frontend.
