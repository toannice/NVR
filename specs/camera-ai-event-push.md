# Kế hoạch: Server API nhận sự kiện AI **từ camera AI** → cắt clip -5/+5

**Ngày:** 2026-08-03 · **Trạng thái:** PLAN (chưa code) · **Liên quan:** [box-ai-event-push-integration.md](./box-ai-event-push-integration.md)

> Phân biệt rõ: `POST /api/pushevent` hiện có là **Box AI** (thiết bị riêng, tự tổng hợp rồi đẩy sang, có `username/password` + `camera_id` = channel_id NVR).
> Tài liệu này là đường **thứ hai**: **camera AI tự bắt sự kiện và tự đẩy** (log `BkavAIEvent` / `BkavAICore`), payload hoàn toàn khác, camera **không biết** channel_id của NVR.

---

## 1. Mục tiêu

Camera được add vào NVR → bật bài AI bất kỳ trên camera (ở đây: phát hiện xe + bắt biển số) →
camera POST JSON sang NVR → NVR cắt clip **[time-5s, time+5s]** từ recording, sinh ảnh, hiển thị ở tab "Sự kiện AI" kèm nhãn biển số.

Toàn bộ pipeline sau khi có `(camera_slug, timestamp_ms, label)` đã tồn tại và chạy thật:
`EventClipService.enqueue()` → chờ flush → `ffmpeg` copy từ Frigate VOD → mp4 + jpg → cập nhật `ai_events`.
**Việc phải làm = tầng ingest (parse + định danh camera + xác thực + chống trùng) + hiển thị biển số.**

---

## 2. Payload thật của camera (mẫu đã bắt được)

```json
{
  "type": "vehicledetection",
  "time": "2026-08-03T10:41:58+07:00",
  "uuid": "bed2294d-b28d-87a2-c3d7-044072eeaba8",
  "image_id": "45c62cde1318511e9659ddbe29993079",
  "monitor_uuid": "a1c32ea7-90c8-536b-ddbe-1d797be5911c",
  "video_id": "",
  "crop_id": ["6f00b3a3d4a7dc580ef6b2179e5a6efa", "d9e7b08a5080641ae0bab93a295ced0b"],
  "pathImage": "/data/misc/onvif/data/bkavai/images/11/1785728519150056168-173.jpg",
  "path_cr_Image": ["/data/misc/onvif/.../-171.jpg", "/data/misc/onvif/.../-172.jpg"],
  "tags": { "cam_id": 96, "license_enable": true, "monitor_guid": "44bcba3c-...", "monitor_id": 188 },
  "extras": {
    "cam_id": 96,
    "objects": [{
      "type": "car",
      "confidence": 0.99984854,
      "locations": [542, 110, 369, 173],
      "license": { "label": "30A87662", "locations": [793, 208, 68, 34] },
      "zone": { "id": 199, "name": "Full screen" }
    }]
  }
}
```

### 2.1. Khác biệt so với định dạng Box — 6 điểm phải xử lý

| # | Vấn đề | Box | Camera AI | Xử lý |
|---|--------|-----|-----------|-------|
| 1 | **Định danh camera** | `camera_id` = channel_id NVR | chỉ có `monitor_uuid` / `cam_id` / `monitor_id` — vô nghĩa với NVR | §4 (map theo IP nguồn + bảng map) |
| 2 | **Thời gian** | epoch ms UTC | ISO-8601 có offset `+07:00` | parse `OffsetDateTime` → ms |
| 3 | **Xác thực** | `username`/`password` trong body | camera FW chỉ cấu hình được **1 URL** | §5 (token trong URL + whitelist IP) |
| 4 | **Ảnh** | `snapshot_base64` | `pathImage` = **đường dẫn nội bộ trong camera**, NVR không đọc được | §6 (tự trích frame; tuỳ chọn fetch HTTP) |
| 5 | **Chống trùng** | `event_id` | `uuid` khác nhau giữa 2 tầng, nhưng `image_id` **giống nhau** | key = `image_id` (fallback `uuid`) |
| 6 | **Tần suất** | thấp | mỗi xe qua = 1..n event, có thể vài event/giây | §7 (gộp cửa sổ + rate limit riêng) |

> ⚠️ Trong log, cùng một lần phát hiện xuất hiện **2 lần** với 2 `uuid` khác (`bed2294d…` ở `BkavAIEvent`, `c6e73540…` ở `BkavAICore`) nhưng **cùng `image_id`**. Nếu chống trùng theo `uuid` → **cắt 2 clip cho 1 sự kiện**. Bắt buộc key theo `image_id`.

---

## 3. Endpoint mới

| | |
|---|---|
| **Method** | `POST` |
| **URL chính** | `https://<NVR_IP>/api/aievent?token=<PUSH_TOKEN>` |
| **Alias** | `POST /event?token=…` — dùng nếu FW camera **hardcode path `/event`** (log cho thấy tầng EventServer dùng path này). Cùng handler. |
| **Body** | JSON nguyên bản của camera, **không sửa gì** |
| **Trả về** | `202 {"event_id":"camai-<image_id>","status":"processing"|"duplicate"}` |
| **Nguyên tắc** | fire-and-forget, giống Box: nhận là trả 202, cắt clip ở nền |

Mã lỗi giữ đúng bảng của Box (`400 BAD_REQUEST`, `401 INVALID_CREDENTIALS`, `403 FORBIDDEN`, `404 CAMERA_NOT_FOUND`, `429 TOO_MANY_REQUESTS`, `500 INTERNAL_ERROR`) để 2 đường đồng nhất tài liệu.

---

## 4. Định danh camera (điểm khó nhất) — 3 tầng, lần lượt

Camera **không** gửi channel_id. Thứ tự resolve trong `CameraAiEventService.resolveSlug()`:

1. **IP nguồn của request** → `cameras.ip` hoặc `cameras.local_ip` → `slug`.
   Đúng cho mọi camera LAN trực tiếp, **không cần cấu hình gì thêm**. Đây là đường chính.
2. **Bảng map `ai_event_sources`** (`monitor_uuid` → `camera_slug`).
   Cần cho camera **qua gateway/NAT** (IP nguồn = IP Pi, không phải camera) hoặc nhiều camera sau một IP.
3. **Không khớp** → vẫn ghi lại vào `ai_event_sources` với `camera_slug = NULL`, `last_seen`, `last_payload`,
   trả **404 `CAMERA_NOT_FOUND`**. Admin vào tab "Sự kiện AI" → mục "Nguồn AI chờ gán" → chọn camera → gán 1 lần.
   (Tự học nguồn thay vì bắt admin gõ uuid tay.)

Bảng mới:
```
ai_event_sources(
  monitor_uuid  varchar PK,
  camera_slug   varchar NULL,     -- NULL = chờ admin gán
  cam_id        int NULL,         -- tags.cam_id, chỉ để hiển thị
  last_ip       varchar,
  last_seen     long,
  last_payload  text NULL         -- JSON gần nhất, để admin nhìn mà gán đúng
)
```

---

## 5. Xác thực

Camera FW gần như chắc chắn chỉ cấu hình được **một URL** (không thêm được header, không nhét được credential vào body). Vì vậy:

- **`token` trong query string** — sinh 1 lần cho mỗi NVR, lưu `app_settings.ai_push_token`, hiện ở tab "Tích hợp" kèm nút "Tạo lại". So sánh constant-time.
- **Cộng thêm** whitelist ngầm: IP nguồn phải là IP của một camera đã add (hoặc của gateway đã cấp phép). Sai IP → 403, kể cả token đúng.
- Tái dùng `IntegrationService` gate: nếu tích hợp đang tắt → 403 (đồng nhất với Box).
- Nếu FW camera **hỗ trợ** Basic Auth → nhận thêm `Authorization: Basic` bằng credential tích hợp (nice-to-have).

> Vì sao không dùng `username/password` như Box: token trong URL là thứ duy nhất chắc chắn cấu hình được trên camera. Rủi ro (token lọt vào access log nginx) chấp nhận được vì đã cộng whitelist IP + phạm vi LAN, và token chỉ cho phép **ghi event**, không đọc được gì.

---

## 6. Ảnh sự kiện

`pathImage` là đường dẫn **trong filesystem camera** (`/data/misc/onvif/data/bkavai/images/...`) → NVR không mount được.

- **Mặc định:** bỏ qua `pathImage`, để NVR **tự trích frame** ở giây thứ 5 của clip — code đã có (`EventClipService` nhánh `SNAP_EXTRACT`), chất lượng bằng luồng ghi chính.
- **Tuỳ chọn (cần FW xác nhận):** nếu camera có HTTP serve file ảnh đó (ví dụ `http://<cam_ip>/bkavai/images/...`), thêm `snapshotUrl` fetch (timeout 3s, ≤5MB) để lấy **ảnh đã vẽ bbox/biển số** — đẹp hơn cho nghiệp vụ đọc biển số. Ảnh crop `path_cr_Image` (crop biển số) đặc biệt giá trị.
- **Tốt nhất:** đề nghị FW thêm tuỳ chọn gửi kèm `image_base64` — khi đó dùng nguyên nhánh Box đã có, không phụ thuộc mạng ngược chiều.

→ **Câu hỏi cần chốt với đội FW camera** (§10).

---

## 7. Chống bão sự kiện (bắt buộc — khác Box)

Bài đếm xe/biển số bắn nhiều hơn nhận diện mặt rất nhiều. Nếu bê nguyên hành vi Box: mỗi event = 1 lần chờ 30s + 1 tiến trình ffmpeg + 1 file mp4 → **CPU và đĩa nổ**.

- **Gộp cửa sổ (dedup window):** trong `COALESCE_SEC = 10s`, cùng `(camera_slug, type)` và (nếu có) cùng `license.label` → chỉ **1** event/clip; các event sau chỉ thêm vào danh sách đối tượng của event đó, không cắt clip mới.
- **Rate limit riêng:** `MAX = 300 req/phút/IP` cho endpoint này (30/phút của Box là quá chặt), nhưng **số clip** bị chặn bởi gộp cửa sổ ở trên, không phải bởi rate limit.
- **Giới hạn hàng đợi cắt clip:** tối đa N job đồng thời (đề xuất 2) + hàng đợi có trần; vượt trần → ghi event nhưng `clip_status="skipped"`.
- **Dọn rác (HIỆN CHƯA CÓ — nợ kỹ thuật thật):** `media/events/` hiện **không có job xoá**, sẽ phình vô hạn.
  Thêm `EventRetentionJob`: giữ `AI_EVENT_RETENTION_DAYS` (mặc định 7, khớp retention recording) + trần dung lượng `AI_EVENT_MAX_GB` (mặc định 10), quét mỗi giờ, xoá file + row.

---

## 8. Ánh xạ dữ liệu → `ai_events`

| Trường payload | Vào đâu |
|---|---|
| `image_id` (fallback `uuid`) | `id` = `"camai-" + image_id` |
| resolve từ IP/`monitor_uuid` | `camera_slug` |
| `type` + `extras.objects[0]` | `label`: `"vehicledetection: 30A87662"` (có biển) hoặc `"vehicledetection: car"` |
| `objects[0].confidence` | `score` |
| `time` (ISO+offset) | `start_time = t-5`, `end_time = t+5` (giây) |
| — | `source = "camera_ai"` (giá trị **mới**, khác `"box"`/`"frigate"`) |
| `objects[*].license.label` | **cột mới** `plate varchar(32) NULL` — để lọc/tìm theo biển số |
| toàn bộ JSON gốc | **cột mới** `meta text NULL` — giữ nguyên bản: zone, locations, crop_id, cam_id |

Về `locations` `[x, y, w, h]`: là **pixel theo khung của AI trên camera**, NVR không biết độ phân giải đó → **không** nhồi vào `box_x..box_h` (đang là toạ độ chuẩn hoá 0..1) để tránh vẽ lệch; giữ trong `meta`. Nếu về sau FW gửi kèm `frame_width/height` thì mới chuẩn hoá được.

---

## 9. Việc phải làm (theo thứ tự)

**P0 — chạy được end-to-end**
1. `models/Schema.kt`: thêm bảng `ai_event_sources`; thêm cột `plate`, `meta` vào `ai_events` (`createMissingTablesAndColumns` tự migrate).
2. `services/CameraAiEventService.kt` (mới): parse payload camera, parse `time` ISO→ms, `resolveSlug()` 3 tầng, build label + plate, gộp cửa sổ 10s, gọi `EventClipService.enqueue`.
3. `routes/CameraAiEventRouting.kt` (mới): `POST /api/aievent` + alias `POST /event`, token + whitelist IP + rate limit 300/phút, trả 202/4xx theo §3. Đăng ký trong `Application.kt`.
4. `EventsRouting.kt`: cho `?source=camera_ai` lọc được; trả thêm `plate` trong `EventView`.
5. `AIEventsPanel.tsx`: badge **"CAMERA AI"**, hiện biển số nổi bật, ô tìm theo biển số; mục **"Nguồn AI chờ gán"** (list `ai_event_sources` có `camera_slug=NULL` → dropdown chọn camera → `PUT /api/aievent/sources/{uuid}`).
6. Test script `tests/test_camera_ai_event.sh`: post payload mẫu ở trên (bao gồm ca 2 uuid cùng image_id → phải chỉ tạo 1 event), kiểm 202/401/403/404/429, kiểm gộp cửa sổ, kiểm clip `ready`.

**P1 — vận hành**
7. `EventRetentionJob` (§7) — nếu không có, máy khách đầy đĩa sau vài tuần.
8. Tab "Tích hợp": hiện URL push đầy đủ (`https://<ip>/api/aievent?token=…`) + nút copy + nút tạo lại token → kỹ thuật viên dán thẳng vào cấu hình camera.

**P2 — tuỳ FW camera**
9. Fetch ảnh `pathImage` / `path_cr_Image` qua HTTP nếu camera serve (ảnh có bbox + crop biển số).
10. Tài liệu `specs/camera-ai-event-push-fw.md` cho đội FW camera (giống tài liệu Box đã có).

---

## 10. Cần chốt với đội FW camera (chặn P0 mục 3 và P2)

1. Cấu hình URL push ở đâu, có nhận **full URL kèm query string** không (`?token=`), có nhận **HTTPS self-signed** không? Nếu không → phải dùng `http://<NVR_IP>:3000` (loopback-bound từ 2026-07-29 → cần mở lại hoặc thêm `location /api/aievent` trên nginx cổng 9000).
2. Có thêm được **HTTP header** (Authorization) không?
3. Path có **hardcode `/event`** không, hay theo URL người dùng nhập?
4. `pathImage` có được camera serve qua HTTP không (URL nào)? Hay thêm được `image_base64` vào payload?
5. Camera **retry** khi NVR trả 5xx/timeout không? Có thể mất event không?
6. `monitor_uuid` có **cố định theo camera** (không đổi sau reboot/factory reset) không? Đây là khoá map ở §4 tầng 2.
7. Có gửi kèm **độ phân giải khung AI** (`frame_width/height`) được không → mới vẽ được bbox đúng.

---

## 11. Điều kiện tiên quyết (không đổi so với Box)

Clip cắt từ **recording của NVR**, nên: camera phải đã add vào NVR, `record.enabled=true`, tại thời điểm sự kiện **có file recording thật** (chế độ `motion` giữ 7 ngày — khoảng không chuyển động có thể trống → clip `failed` nhưng event vẫn được ghi), và `time` không cũ hơn hạn lưu. Trễ clip sẵn sàng ≈ **25–30 giây** sau thời điểm sự kiện (chờ segment flush + ffmpeg).
