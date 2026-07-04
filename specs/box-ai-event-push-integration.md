# Tài liệu tích hợp: Box AI đẩy sự kiện sang NVR (Event Push API)

**Phiên bản:** 1.0 · **Ngày:** 2026-07-01 · **Đối tượng:** Đội phát triển Box AI
**Bổ sung cho:** NVR_AIView_Integration_Protocol v1.2 (phần đẩy sự kiện AI — endpoint mới `/api/pushevent`)

> Mục tiêu: Box AI phát hiện sự kiện (nhận diện khuôn mặt, phát hiện người, xâm nhập…) rồi **gọi HTTP sang NVR** kèm thời điểm + loại AI. NVR tự **cắt 1 clip ngắn -5s/+5s** quanh thời điểm đó từ recording sẵn có, tự sinh ảnh đại diện, lưu trữ và hiển thị ở mục "Sự kiện AI" trên WebUI.
>
> **Toàn bộ phần NVR đã hoàn thiện.** Tài liệu này mô tả chính xác những gì Box AI phải gửi để chạy đúng ngay lần đầu.

---

## 1. Kiến trúc & luồng dữ liệu

```
┌────────────┐   (1) phát hiện sự kiện AI            ┌──────────────────────────────┐
│  Box AI    │ ───────────────────────────────────► │            NVR               │
│            │   (2) POST /api/pushevent            │                              │
│            │        {camera_id, timestamp,        │  (3) lưu event (source=box,  │
│            │         ai_type, objects, snapshot}  │      status=processing) → 202│
│            │ ◄─────────────────────────────────── │                              │
│            │   202 Accepted {event_id, status}    │  (4) NỀN: chờ recording flush│
└────────────┘                                      │      → ffmpeg cắt VOD -5/+5  │
                                                     │      → mp4 + ảnh jpg          │
                                                     │  (5) hiển thị WebUI "Sự kiện"│
                                                     └──────────────────────────────┘
```

**Mô hình "fire-and-forget":** Box gửi 1 request, nhận `202 Accepted` ngay lập tức. Box **không cần** chờ NVR cắt xong clip và **không cần** poll trạng thái — NVR tự xử lý phần còn lại. Đây là mô hình một chiều: Box → NVR.

---

## 2. Điều kiện tiên quyết (BẮT BUỘC đọc)

Để một sự kiện được cắt clip thành công, **tất cả** các điều sau phải đúng:

| # | Điều kiện | Vì sao | Nếu sai thì |
|---|-----------|--------|-------------|
| 1 | Tích hợp đã được **bật** trên NVR (tab "Tích hợp") | Endpoint tự xác thực bằng cặp credential tích hợp | HTTP 403 |
| 2 | Box dùng đúng **username/password** do admin NVR cấp | Xác thực | HTTP 401 |
| 3 | `camera_id` **tồn tại** trên NVR (= `channel_id`) | NVR ánh xạ camera → thư mục recording | HTTP 404 |
| 4 | Camera đó **đang được NVR ghi hình** (record.enabled=true) | Clip cắt từ recording của NVR, không phải từ Box | Clip `failed` (nhưng vẫn tạo event) |
| 5 | Tại `timestamp` có **recording thật trên đĩa** | NVR ghi theo chế độ `motion` (giữ 7 ngày); khoảng không có chuyển động có thể không có file | Clip `failed` |
| 6 | `timestamp` **không cũ hơn hạn lưu** (mặc định 7 ngày) | Recording đã bị dọn | Clip `failed` |

> **Lấy `camera_id` ở đâu?** Gọi `POST /api/getprofilecamera` (đã có trong v1.2). Trường `camera_id` trong response chính là giá trị dùng cho `/api/pushevent`. Đây **cùng một định danh** Box đã dùng cho luồng stream.

---

## 3. Đặc tả endpoint

### 3.1. Địa chỉ & phương thức

| | |
|---|---|
| **Method** | `POST` |
| **URL (khuyến nghị)** | `https://<NVR_IP>/api/pushevent` (cổng 443, qua nginx) |
| **URL (thay thế, LAN tin cậy)** | `http://<NVR_IP>:3000/api/pushevent` (trực tiếp backend) |
| **Content-Type** | `application/json` |
| **Giới hạn body** | **50 MB** (nginx). Đủ cho ảnh snapshot base64. |
| **Xác thực** | Trong JSON body (`username`/`password`) — KHÔNG dùng header/cookie |

> ⚠️ **HTTPS self-signed:** NVR dùng chứng chỉ tự ký. Client HTTP của Box phải **bỏ qua verify chứng chỉ** (hoặc trust CA của NVR), nếu không sẽ lỗi TLS. Nếu Box và NVR cùng LAN tin cậy, có thể dùng `http://<NVR_IP>:3000` cho đơn giản.

### 3.2. Request body — bảng trường đầy đủ

| Trường | Kiểu | Bắt buộc | Mặc định | Ràng buộc / Ý nghĩa |
|--------|------|:---:|---|---------------------|
| `username` | string | ✅ | — | Tên đăng nhập tích hợp do admin NVR cấp |
| `password` | string | ✅ | — | Mật khẩu tích hợp |
| `camera_id` | string | ✅ | — | = `channel_id` NVR (lấy từ `/api/getprofilecamera`). KHÔNG được rỗng |
| `timestamp` | long | ✅ | — | Thời điểm sự kiện. **Epoch mili-giây (UTC)**. Là **tâm** của clip; NVR cắt `[timestamp-5s, timestamp+5s]`. Xem §5 |
| `ai_type` | string | ✅ | — | Loại sự kiện AI, ví dụ `facerecognition`, `person`, `intrusion`, `linecrossing`… (chuỗi tự do; NVR chỉ hiển thị) |
| `objects` | array | ⭕ | `[]` | Danh sách đối tượng nhận diện. Xem §3.3 |
| `event_id` | string | ⭕ | NVR tự sinh | ID sự kiện phía Box. Dùng để **chống trùng** (idempotency). Nếu gửi lại cùng `event_id` → NVR bỏ qua. Nếu rỗng, NVR tự sinh theo thời gian |
| `snapshot_base64` | string | ⭕ | `null` | Ảnh JPEG base64 do Box gửi kèm. Xem §6 |

### 3.3. Cấu trúc phần tử trong `objects`

| Trường | Kiểu | Bắt buộc | Ý nghĩa |
|--------|------|:---:|---------|
| `name` | string | ⭕ | Tên đối tượng, ví dụ tên người "Nguyễn Văn A", hoặc "person" |
| `score` | number | ⭕ | Độ tin cậy 0.0–1.0 |

- NVR ghép **nhãn hiển thị** = `"{ai_type}: {objects[0].name}"` nếu có `name`, ngược lại chỉ `ai_type`.
- NVR lưu `score` = `objects[0].score` (đối tượng đầu tiên).
- `objects` có thể rỗng — sự kiện vẫn hợp lệ, nhãn = `ai_type`.

### 3.4. Ví dụ request đầy đủ

```json
{
  "username": "admin",
  "password": "Bkav@2020",
  "camera_id": "1",
  "timestamp": 1782871872000,
  "ai_type": "facerecognition",
  "event_id": "box-evt-20260701-000123",
  "objects": [
    { "name": "Nguyễn Văn A", "score": 0.93 }
  ],
  "snapshot_base64": "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQ..."
}
```

### 3.5. Response thành công

**HTTP 202 Accepted:**
```json
{ "event_id": "boxpush-box-evt-20260701-000123", "status": "processing" }
```
- `event_id`: định danh sự kiện phía NVR (= `"boxpush-" + event_id` bạn gửi, hoặc tự sinh nếu bạn để rỗng).
- `status`: `"processing"` (đã nhận, đang cắt clip) hoặc `"duplicate"` (đã tồn tại `event_id` này, NVR bỏ qua — không cắt lại).

---

## 4. Bảng mã lỗi đầy đủ

Mọi lỗi trả JSON `{ "error": "<CODE>", "message": "<mô tả tiếng Việt>" }`.

| HTTP | error code | Khi nào xảy ra | Box nên làm gì |
|:---:|-----------|----------------|----------------|
| **202** | — | Nhận thành công (kể cả `duplicate`) | Coi như xong |
| **400** | `BAD_REQUEST` | JSON sai cú pháp; hoặc thiếu `camera_id`; hoặc `timestamp <= 0` | Sửa payload; **không** retry nguyên trạng |
| **401** | `INVALID_CREDENTIALS` | Sai `username`/`password` | Kiểm tra lại credential với admin NVR |
| **403** | `FORBIDDEN` | Tích hợp chưa bật trên NVR | Báo admin bật tab "Tích hợp"; retry sau |
| **404** | `CAMERA_NOT_FOUND` | `camera_id` không tồn tại trên NVR | Đồng bộ lại danh sách camera qua `/api/getprofilecamera` |
| **429** | `TOO_MANY_REQUESTS` | Vượt **30 request/phút/IP** | Đọc header `Retry-After` (giây), chờ rồi retry |
| **500** | `INTERNAL_ERROR` | Lỗi nội bộ NVR | Retry với backoff |

> **Lưu ý quan trọng:** `202` chỉ nghĩa là "đã nhận và tạo bản ghi sự kiện". Việc **cắt clip có thành công hay không** xảy ra ở nền và KHÔNG phản ánh trong response (xem §7 các trạng thái clip). Đây là thiết kế fire-and-forget.

---

## 5. Ngữ nghĩa `timestamp` (đọc kỹ để tránh lỗi phổ biến nhất)

- **Đơn vị chuẩn: epoch mili-giây, UTC tuyệt đối** (ví dụ `Date.now()` trong JS, `System.currentTimeMillis()` trong Java, `int(time.time()*1000)` trong Python).
- Epoch là **tuyệt đối, không phụ thuộc múi giờ** → Box và NVR ở múi giờ nào cũng khớp. **Không** cộng/trừ offset `+7`.
- **Khoan dung với giây:** nếu bạn lỡ gửi epoch **giây** (giá trị < 1.000.000.000.000), NVR tự nhân 1000. Nhưng **hãy gửi mili-giây** cho đúng chuẩn.
- Clip = `[timestamp - 5s, timestamp + 5s]` (cố định 5 giây mỗi bên = clip 10 giây).
- **Đừng gửi timestamp tương lai.** NVR phải chờ tới khi recording của mốc `timestamp+5s` được ghi xong đĩa. Nếu timestamp lệch về tương lai, NVR chờ tối đa **120 giây** rồi mới cắt (nếu vẫn chưa có recording → clip `failed`). Luôn gửi thời điểm **thực tế của sự kiện** (quá khứ gần / hiện tại).

**Độ trễ dự kiến clip sẵn sàng:** khoảng `timestamp + 5s + ~20s` (NVR chờ segment recording cuối được flush) rồi cộng thời gian ffmpeg (~1–3s). Tức thường **~25–30 giây sau thời điểm sự kiện** clip mới xuất hiện trên WebUI. Điều này là bình thường.

---

## 6. Ảnh snapshot

- `snapshot_base64` **tùy chọn**. Chấp nhận **cả hai dạng**:
  - Có tiền tố data URI: `data:image/jpeg;base64,/9j/4AAQ...`
  - Base64 thuần: `/9j/4AAQ...`
- Định dạng ảnh: **JPEG**. Kích thước khuyến nghị < vài MB (giới hạn tổng body 50 MB).
- **Nếu Box KHÔNG gửi snapshot:** NVR **tự trích 1 frame** từ recording tại đúng thời điểm sự kiện (frame ở giây thứ 5 của clip). Chất lượng bằng luồng ghi chính (thường Full HD/4K). → Box có thể bỏ qua trường này nếu không muốn tự sinh ảnh.
- **Nếu Box gửi snapshot:** NVR ưu tiên dùng ảnh của Box (ví dụ ảnh đã vẽ bbox/nhãn khuôn mặt).

---

## 7. Vòng đời sự kiện phía NVR (để Box hiểu điều gì diễn ra sau `202`)

1. NVR chèn bản ghi vào bảng `ai_events`: `source="box"`, `clip_status="processing"`.
2. NVR chờ recording flush (tối đa 120s), rồi ffmpeg đọc Frigate VOD `[ts-5, ts+5]`, stream-copy (không re-encode) ra file mp4.
3. Ảnh: dùng snapshot của Box, hoặc tự trích frame.
4. Cập nhật bản ghi: `clip_status` → `"ready"` (kèm `clip_path`) hoặc `"failed"`.

**Nơi lưu trên NVR:**
- Clip: `/app/media/events/{camera_slug}/{event_id}.mp4` (trong container) ↔ `<thư mục dự án>/media/events/{camera_slug}/{event_id}.mp4` (trên host).
- Ảnh: cùng thư mục, đuôi `.jpg`.

**URL phục vụ (chỉ admin WebUI đã đăng nhập truy cập được, KHÔNG dành cho Box):**
- `GET /api/events` — danh sách sự kiện (lọc `?source=box|frigate|all&camera=&after=&before=&limit=`)
- `GET /api/events/{event_id}/clip.mp4`
- `GET /api/events/{event_id}/snapshot.jpg`

> Box **không** cần và **không** có quyền gọi các endpoint `GET /api/events*` (chúng nằm sau session đăng nhập admin). Box chỉ dùng `/api/pushevent`.

---

## 8. Danh sách edge case & cách NVR xử lý (đảm bảo không có tình huống "không rõ vì sao")

| Tình huống | Hành vi NVR | Box cần làm |
|-----------|-------------|-------------|
| Gửi trùng `event_id` | Trả `202` với `status="duplicate"`, KHÔNG cắt lại | An toàn để retry — idempotent |
| `event_id` rỗng | NVR tự sinh `boxpush-<millis>` | Nên gửi `event_id` ổn định để retry an toàn |
| `timestamp` gửi bằng giây | NVR tự ×1000 | Nên gửi mili-giây |
| `timestamp` tương lai (lệch đồng hồ) | Chờ tối đa 120s; nếu chưa có recording → clip `failed` | Đồng bộ NTP; gửi thời điểm thực |
| `timestamp` quá cũ (> 7 ngày / ngoài retention) | Không có recording → clip `failed`, event vẫn tạo | Gửi sự kiện gần thời gian thực |
| Camera có nhưng KHÔNG bật recording | Clip `failed`, event vẫn tạo (có ảnh nếu Box gửi) | Đảm bảo camera được NVR ghi hình |
| Khoảng thời gian không có chuyển động (mode=motion) | Có thể thiếu file recording → clip `failed` | Chấp nhận; hoặc chuyển NVR sang record mode `all` (quyết định phía NVR) |
| `objects` rỗng | Nhãn = `ai_type`, `score`=null | OK |
| Ảnh base64 hỏng / không giải mã được | Bỏ qua ảnh Box, NVR tự trích frame | Kiểm tra encode base64 |
| Vượt rate limit 30 req/phút | HTTP 429 + `Retry-After` | Chờ theo `Retry-After` rồi retry |
| Body > 50 MB | nginx trả 413 (trước khi tới app) | Giảm kích thước ảnh snapshot |
| Tích hợp bị admin tắt giữa chừng | HTTP 403 | Retry sau khi admin bật lại |
| NVR đang khởi động lại | Có thể 502/500 | Retry với backoff |

---

## 9. Chính sách retry khuyến nghị cho Box

- **Nên** đặt `event_id` cố định cho mỗi sự kiện → retry bao nhiêu lần cũng an toàn (idempotent).
- Retry với **exponential backoff** cho: 429 (theo `Retry-After`), 500, 502, lỗi mạng/timeout.
- **Không** retry cho: 400 (payload sai), 401 (sai credential), 404 (camera không tồn tại) — phải sửa nguyên nhân trước.
- Timeout HTTP client khuyến nghị: kết nối 5s, đọc 10s (endpoint trả ngay, không cần dài).

---

## 10. Ví dụ mã (tham khảo)

### 10.1. curl
```bash
TS_MS=$(( $(date +%s) * 1000 ))
curl -k -X POST https://<NVR_IP>/api/pushevent \
  -H "Content-Type: application/json" \
  -d "{
    \"username\": \"admin\",
    \"password\": \"Bkav@2020\",
    \"camera_id\": \"1\",
    \"timestamp\": $TS_MS,
    \"ai_type\": \"facerecognition\",
    \"event_id\": \"box-evt-0001\",
    \"objects\": [{\"name\": \"Nguyen Van A\", \"score\": 0.93}]
  }"
# → 202  {"event_id":"boxpush-box-evt-0001","status":"processing"}
```

### 10.2. Python
```python
import time, requests

def push_event(nvr_ip, cam_id, ai_type, objects, event_id, snapshot_b64=None):
    payload = {
        "username": "admin",
        "password": "Bkav@2020",
        "camera_id": cam_id,                 # = channel_id lấy từ /api/getprofilecamera
        "timestamp": int(time.time() * 1000),# epoch MILLISECONDS, UTC
        "ai_type": ai_type,
        "event_id": event_id,                # ổn định để idempotent
        "objects": objects,                  # [{"name":..., "score":...}]
    }
    if snapshot_b64:
        payload["snapshot_base64"] = snapshot_b64
    r = requests.post(f"https://{nvr_ip}/api/pushevent",
                      json=payload, timeout=(5, 10), verify=False)  # verify=False: self-signed
    if r.status_code == 429:
        retry_after = int(r.headers.get("Retry-After", "5"))
        # ... chờ retry_after giây rồi thử lại
    r.raise_for_status()
    return r.json()   # {"event_id": "...", "status": "processing"}
```

---

## 11. Checklist tích hợp cho dev Box AI

- [ ] Lấy `camera_id` từ `POST /api/getprofilecamera` (không tự bịa; đúng bằng `channel_id`).
- [ ] Gửi `timestamp` là **epoch mili-giây UTC** của thời điểm sự kiện thực.
- [ ] Đặt `event_id` **cố định, duy nhất** cho mỗi sự kiện (để idempotent + retry an toàn).
- [ ] Điền `ai_type` và (khuyến nghị) `objects[].name`, `objects[].score`.
- [ ] Bỏ qua verify TLS (self-signed) hoặc trust CA NVR.
- [ ] Xử lý đủ các mã lỗi §4, tôn trọng `Retry-After` khi 429.
- [ ] (Tùy chọn) Gửi `snapshot_base64` JPEG nếu muốn dùng ảnh có nhãn của Box; nếu không, NVR tự trích frame.
- [ ] Kiểm thử: gửi 1 sự kiện với timestamp ~30s trước → xác nhận nhận `202`, và ~30s sau clip hiện ở WebUI tab "Sự kiện AI" (badge "BOX AI").

---

## 12. Tóm tắt trách nhiệm hai phía

| Việc | Ai làm |
|------|:---:|
| Phát hiện sự kiện AI, xác định `camera_id` + `timestamp` + `ai_type` | **Box** |
| Gọi `POST /api/pushevent`, retry, idempotency | **Box** |
| (Tùy chọn) sinh ảnh snapshot có nhãn | **Box** |
| Xác thực, chống trùng, tạo bản ghi sự kiện | NVR (xong) |
| Cắt clip -5/+5, tự trích frame nếu thiếu ảnh, lưu trữ | NVR (xong) |
| Hiển thị danh sách + phát clip trên WebUI | NVR (xong) |

---

*Mọi thắc mắc về `camera_id`, credential, hay hành vi cắt clip: liên hệ đội NVR. Không có tình huống nào trong tài liệu này để mở — nếu gặp trường hợp chưa mô tả, hãy báo để bổ sung.*
