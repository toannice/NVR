# Yêu cầu cập nhật: NVR kéo camera được bằng **cả RTSP (554) và RTSPS (322)**

**Ngày:** 2026-08-25 · **Trạng thái:** ĐÃ THI HÀNH VÀ KIỂM CHỨNG TRÊN MÁY THẬT (nhánh `feat/rtsp-rtsps-dual-transport`)
**Liên quan:** [camera-ai-event-push.md](./camera-ai-event-push.md)

---

## 1. Vì sao cần

Firmware camera Bkav từ 1.6.9.x đưa RTSP vào sau một lớp TLS: `rtsps_proxy` nghe **322**, chuyển tiếp
vào `127.0.0.1:554`. Khi camera được cấu hình để **554 chỉ nghe loopback** (`<serverip>127.0.0.1</serverip>`
trong `rtsp.cfg` — cách chuẩn để giấu RTSP thô khỏi mạng), NVR mất luồng hoàn toàn:

```
[2026-08-25 13:40:55] frigate  WRN [rtsp] error="streams: dial tcp 192.168.1.209:554: connect: connection refused" stream=cam_05b284ad_sub
[2026-08-25 13:40:55] watchdog.cam_05b284ad  ERROR : No new recording segments were created ... restarting the ffmpeg record process
```

Hệ quả dây chuyền: không có recording → tính năng cắt clip sự kiện AI (`EventClipService`) hỏng theo,
mọi sự kiện camera đẩy về đều thành `clip_status='failed'` kèm `EVENT_CLIP CLIP_EMPTY ... no recording?`.

**Không giải quyết bằng cách bắt camera mở lại 554 ra mạng.** NVR phải kéo được cả hai đường: RTSPS cho
thiết bị của mình, RTSP thường cho thiết bị hãng khác — và không bind hay chặn cổng nào.

## 2. Đã kiểm chứng trước khi thiết kế

| Câu hỏi | Kết quả đo thật (2026-08-25) |
|---|---|
| go2rtc 1.9.9 có hiểu `rtsps://` không? | **Có.** Kéo được frame JPEG 175 KB từ `rtsps://admin:***@192.168.1.41:322/profile1/live` |
| Chứng chỉ tự ký sai SAN có bị chặn không? | **Không.** Cert của camera là `CN=192.168.2.65`, SAN `IP:192.168.2.65, DNS:aiview.local` — không khớp IP camera, go2rtc vẫn kết nối (bỏ qua xác thực) |
| Có phải sửa Frigate không? | **Không.** Frigate luôn đọc `rtsp://127.0.0.1:8554/<slug>` (restream nội bộ của go2rtc). Chỉ URL *nguồn* trong `go2rtc.streams` đổi |

Kết luận: đây là thay đổi **chỉ ở tầng NVR backend**, không đụng Frigate, không đụng firmware.

## 3. Nguyên tắc thiết kế

1. **Không đổi hành vi camera đang chạy tốt.** Camera nào đang kéo được bằng 554 thì vẫn 554.
   Không tự ý "nâng cấp" lên TLS — đó là đổi chính sách sau lưng người vận hành.
2. **Tự chữa khi đường hiện tại chết.** Nếu cổng trong URL không kết nối được mà cổng kia mở, chuyển
   sang cổng kia. Đây là thứ giải quyết đúng sự cố ở §1.
3. **Có đường ép tay.** Người vận hành phải ép được một camera luôn dùng TLS (hoặc luôn dùng RTSP thô)
   mà không phụ thuộc kết quả dò.
4. **Thiết bị hãng khác không bị ảnh hưởng.** Cam không có 322 → không có gì thay đổi với nó.

## 4. Thay đổi

### 4.1. `services/RtspTransport.kt` (mới)

Tiện ích thuần, không trạng thái:

| Hàm | Việc |
|---|---|
| `parse(url)` | tách `scheme / creds / host / port / path`, chấp nhận cả `rtsp://` và `rtsps://` |
| `switchTo(url, secure)` | đổi scheme **và** cổng mặc định kèm theo (554 ↔ 322), giữ nguyên creds và path |
| `injectCredentials(url, user, pass)` | thay cho `url.replace("rtsp://", "rtsp://u:p@")` — cách cũ im lặng không làm gì với URL `rtsps://` |
| `probe(host, port, timeoutMs)` | TCP connect, mặc định 700 ms |
| `resolve(url, mode)` | áp dụng bảng dưới |

`resolve()`:

| `mode` | Hành vi |
|---|---|
| `rtsp` | ép `rtsp://host:554` |
| `rtsps` | ép `rtsps://host:322` |
| `auto` *(mặc định)* | thử cổng trong URL; **kết nối được → giữ nguyên**; không được → thử cổng đối ứng, mở thì chuyển sang; cả hai chết → giữ nguyên URL gốc (để log lỗi của Frigate vẫn chỉ đúng chỗ) |

Bỏ qua hoàn toàn URL loopback (`127.0.0.1:8554` — restream nội bộ), không dò.

### 4.2. `models/Schema.kt`

Thêm `cameras.stream_transport VARCHAR(10) DEFAULT 'auto'`.
`createMissingTablesAndColumns` tự migrate, camera cũ nhận `auto` — tức không đổi hành vi.

### 4.3. `services/CameraService.kt`

- `writeYaml()`: URL chính và URL phụ đi qua `RtspTransport.resolve()` **trước khi** ghi vào
  `go2rtc.streams`. Đây là điểm duy nhất cần chặn, vì mọi lần thêm/sửa camera đều ghi lại file này.
  DB vẫn giữ URL gốc do ONVIF khai — không ghi đè, để còn dò lại được về sau.
- `rewriteRtspHost()`: bỏ hardcode `"rtsp://"`, giữ nguyên scheme của URL vào.
- `syncYamlCamerasToDb()`: điều kiện bỏ qua loopback nhận cả `rtsps://127.0.0.1`.

### 4.4. `routes/OnvifSoapClient.kt`

Hai chỗ nhét user/pass dùng `RtspTransport.injectCredentials()`.

### 4.5. `routes/OnvifRouting.kt`

`removePrefix("rtsp://")` → bóc scheme không phân biệt.

### 4.6. API

`PATCH /api/cameras/{slug}/transport` với `{"mode":"auto"|"rtsp"|"rtsps"}`. Trả 400 nếu mode lạ,
404 nếu không có camera. Sau khi đổi thì ghi lại `config.yml` và nạp lại Frigate như các thao tác
sửa camera khác.

## 5. Log

Theo chuẩn `AppLogger` của repo, module `CAMERA`, componentId `RTSP_TRANSPORT`:

| eventType | Khi nào |
|---|---|
| `KEEP` | cổng trong URL còn sống, không đổi gì |
| `SWITCHED` | đã chuyển cổng, kèm `from=`/`to=` |
| `BOTH_DOWN` | cả hai cổng chết — đây là dòng cần tìm khi camera mất hình |
| `FORCED` | mode ép tay, không dò |

Không log mật khẩu: URL luôn đi qua `redact()` trước khi vào log.

## 6. Giới hạn đã biết

- **Không xác thực chứng chỉ.** go2rtc không kiểm tra cert của camera, và cert hiện tại sai SAN nên
  có kiểm cũng trượt. TLS ở đây chống nghe lén chứ **không** chống giả mạo. Muốn chống giả mạo thì
  phải cấp cert đúng IP/DNS cho từng máy — việc riêng, không thuộc thay đổi này.
- **`auto` dò mỗi lần ghi `config.yml`,** không dò định kỳ. Camera đổi cổng khi đang chạy sẽ chỉ được
  nhận ra ở lần sửa camera kế tiếp hoặc khi NVR khởi động lại. Nếu cần bám sát hơn thì móc thêm vào
  `HealthService` — chưa làm vì chưa có nhu cầu thật.
- Chi phí dò: 2 lần TCP connect × 700 ms tối đa cho mỗi camera, chỉ khi ghi `config.yml`.

## 7. Kết quả kiểm chứng trên máy thật (2026-08-25)

| Nhánh | Camera | Bằng chứng |
|---|---|---|
| `SWITCHED` | `.209` sau khi nạp firmware rtsps (554 đóng, 322 mở) | `RTSP_TRANSPORT SWITCHED from=554 to=322` → `YAML_UPDATED` → `STARTUP_HEALED frigate_restarted=true` → `camera_fps=5.1`, recording chạy lại |
| `KEEP` | `.41` khi 554 còn sống | không đụng vào, `config.yml` không đổi một byte |
| `BOTH_DOWN` | `.8` (offline thật) | giữ nguyên URL gốc, không ghi `config.yml`, không restart Frigate |
| `FORCED` | `.41` sau khi 554 hỏng xác thực | `FORCED mode=rtsps` → `camera_fps=5.0`, kéo camera từ chết về sống |

Chuỗi đầy đủ camera→NVR cũng đã chạy thật: camera POST sự kiện → `202` → `EVENT_CLIP READY` →
clip **10,56 giây** (3.890.412 byte) + ảnh trích từ đúng thời điểm sự kiện.

### Giới hạn đã lộ ra khi kiểm chứng — quan trọng

`probe()` chỉ **TCP connect**, nên nó KHÔNG phát hiện được "cổng mở nhưng dịch vụ hỏng". Ca thật gặp
ngay hôm nay: camera `.41` mở 554, trả lời `RTSP/1.0 401 Unauthorized` (`Server: aiview rtsp server
V5.1`) — tầng RTSP sống nhưng **từ chối xác thực** của go2rtc, trong khi **cùng credential đó lại
chạy qua 322**. Lý do: `rtsps_proxy` chuyển tiếp từ `127.0.0.1`, và RTSP server bỏ qua `need_auth`
cho loopback. `auto` không thể tự chữa ca này vì lỗi nằm ở xác thực, không phải vận chuyển — đường ép
tay `mode=rtsps` mới là lời giải, và đã dùng đúng như vậy để cứu `.41`.

Nâng `probe()` lên tầng RTSP (gửi `OPTIONS`) **cũng không giải quyết được** ca này, vì `.41` trả lời
`401` chứ không im lặng — tức là "RTSP sống". Muốn tự chữa cả lỗi xác thực thì phải thử `DESCRIBE`
kèm credential thật rồi so kết quả hai cổng, đắt hơn nhiều và chưa làm.
