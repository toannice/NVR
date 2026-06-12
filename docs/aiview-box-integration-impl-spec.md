# Implementation Spec — Tích hợp AiView Box ↔ NVR AIView

> **Mục đích:** tài liệu kỹ thuật chi tiết để AI (hoặc dev) code đầy đủ tính năng tích hợp ở **cả 2 phía**:
> NVR AIView (Kotlin/Ktor — repo này) và AiView Box (repo khác).
> **Nguồn chân lý hợp đồng API:** `NVR_AIView_Integration_Protocol` v1.1. File này là phần *triển khai*.
>
> **Nguyên tắc nền:**
> - Tính năng này **thuần bổ sung (additive)**. KHÔNG sửa schema camera hiện có, KHÔNG đụng
>   `CameraService`/`GatewayService`/code Nebula.
> - Box **chỉ** nói chuyện với NVR (HTTP API + RTSP restream cổng 8554). Camera là LAN hay sau gateway/Nebula
>   là **trong suốt** với Box.
> - **Băng thông:** go2rtc = 1 producer → fan-out nhiều consumer. Box pull thêm chỉ tăng **egress LAN của NVR**,
>   KHÔNG tải thêm link gateway/Nebula.

---

## 0. Tổng quan luồng

```
WebUI Box ──HTTP──> Box BE ──HTTP──> NVR BE (3 endpoint mới)
                       │                    └── đọc bảng `cameras` + trạng thái go2rtc
                       └── go2rtc Box ──RTSP pull──> NVR go2rtc :8554/{channelId}_main
```

3 endpoint NVR mới (đều **public**, tự xác thực bằng credential tích hợp riêng):
| Endpoint | Method | Vai trò |
|---|---|---|
| `/api/getintegrationinfo` | GET | version + giới hạn + cờ rtsp_auth |
| `/api/getprofilecamera` | POST | discovery: list camera chưa-add + restream URL |
| `/api/getcamerastatus` | POST | health-check: exists + online (no-auth) |

2 endpoint phía Box (Box tự implement, WebUI Box gọi vào):
| Endpoint | Method | Vai trò |
|---|---|---|
| `/api/nvr-aiview/connect` | POST | nhận creds NVR → gọi getprofilecamera → trả list |
| `/api/nvr-aiview/add-cameras` | POST | đăng ký restream vào go2rtc Box + lưu DB |

---

# PHẦN A — NVR AIView (repo này, Kotlin/Ktor)

## A0. Map vào codebase hiện có

| Việc | File/đối tượng có sẵn để dựa vào |
|---|---|
| Định nghĩa bảng + auto-migrate | `backend/.../models/Schema.kt` + `backend/.../plugins/Databases.kt` (`createMissingTablesAndColumns`) |
| Đăng ký route | `backend/.../plugins/Routing.kt` (thêm vào khối **public**, cạnh `cloudRouting()`) |
| Mẫu file route | `backend/.../routes/CloudRouting.kt`, `GatewayRouting.kt` |
| Hash/verify mật khẩu (bcrypt) | `backend/.../plugins/Security.kt` → `hashPattern()`, `verifyPattern()` |
| Đọc camera | bảng `Cameras` (Exposed) — cột `slug`, `displayName`, `channelId`, `subRtspUrl` |
| LAN IP cho restream URL | `System.getenv("FRIGATE_HOST_IP")` rồi fallback `detectLanIp()` (xem `CameraService.kt:332`) |
| go2rtc client | `backend/.../cloud/Go2RtcClient.kt` (BASE = `http://127.0.0.1:1984`) |

**Tên stream restream (đã có sẵn trong hệ):** `${channelId}_main` và `${channelId}_sub`,
phục vụ RTSP tại `rtsp://{lanIp}:8554/${channelId}_main`. (Xem `CameraService.kt:253,326`.)

---

## A1. DB — bảng `integration_settings`

Thêm vào `Schema.kt`:

```kotlin
object IntegrationSettings : Table("integration_settings") {
    val id = integer("id").default(1)                 // single-row, luôn = 1
    val enabled = bool("enabled").default(false)       // bật/tắt tích hợp Box
    val username = varchar("username", 255).default("")
    val passwordHash = text("password_hash").default("") // bcrypt (verifyPattern dùng được)
    val updatedAt = datetime("updated_at").defaultExpression(CurrentDateTime)
    override val primaryKey = PrimaryKey(id)
}
```

- Auto-tạo qua `createMissingTablesAndColumns` trong `Databases.kt` (thêm `IntegrationSettings` vào danh sách).
- Seed row id=1 với `enabled=false` nếu chưa có.
- **Độc lập hoàn toàn** với pattern-lock (bảng `LockConfig`). NVR khoá UI không ảnh hưởng API tích hợp.

---

## A2. Route mới — `routes/IntegrationRouting.kt`

Đăng ký trong `plugins/Routing.kt`, khối **public** (KHÔNG nằm trong `authenticate("auth-session")`),
vì endpoint tự xác thực bằng credential tích hợp:

```kotlin
// plugins/Routing.kt — thêm cạnh cloudRouting()
cloudRouting()
integrationRouting()   // <— mới
```

```kotlin
// routes/IntegrationRouting.kt
fun Route.integrationRouting() {
    route("/api") {
        get("/getintegrationinfo")  { /* A7.1 */ }
        post("/getprofilecamera")   { /* A7.2 — requireBoxAuth */ }
        post("/getcamerastatus")    { /* A7.3 — no auth, rate-limited */ }
    }
}
```

---

## A3. Auth helper (credential tích hợp riêng)

```kotlin
sealed class BoxAuthResult { object Ok; object Disabled; object Invalid }

fun verifyBoxAuth(username: String, password: String): BoxAuthResult {
    val s = readIntegrationSettings()           // SELECT row id=1
    if (!s.enabled) return BoxAuthResult.Disabled
    val (matched, _) = verifyPattern(password, s.passwordHash)   // bcrypt, từ Security.kt
    return if (username == s.username && matched) BoxAuthResult.Ok else BoxAuthResult.Invalid
}
```

Ánh xạ response:
- `Disabled` → **403** `{ "error":"FORBIDDEN", "message":"Tích hợp chưa được bật trên NVR." }`
- `Invalid`  → **401** `{ "error":"INVALID_CREDENTIALS", "message":"Sai tên đăng nhập hoặc mật khẩu." }`
- Body thiếu/sai JSON → **400** `BAD_REQUEST`.

---

## A4. Rate-limit helper (chống flood)

In-memory sliding window theo client IP (`call.request.origin.remoteHost`):

```kotlin
object RateLimiter {
    private const val MAX = 30            // request
    private const val WINDOW_MS = 60_000  // mỗi phút
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()
    /** @return null nếu OK, hoặc số giây Retry-After nếu vượt ngưỡng. */
    fun check(ip: String): Long? { /* dọn timestamp cũ, đếm, trả Retry-After nếu >= MAX */ }
}
```

- Áp cho `getprofilecamera` + `getcamerastatus`.
- Vượt ngưỡng → **429** `{ "error":"TOO_MANY_REQUESTS", "message":"Quá nhiều yêu cầu, thử lại sau." }`
  + header `Retry-After: <giây>`.

---

## A5. Online status service — `services/StreamOnlineTracker.kt`

`online` = stream restream `${channelId}_main` của go2rtc **đang có producer nhận frame**, có **debounce**.

```kotlin
object StreamOnlineTracker {
    // channelId -> thời điểm cuối cùng thấy producer "live"
    private val lastSeen = ConcurrentHashMap<String, Long>()

    fun start(scope: CoroutineScope) = scope.launch {
        while (isActive) {
            val live: Set<String> = Go2RtcClient.liveStreamNames()   // A5.1
            val now = System.currentTimeMillis()
            live.forEach { name ->
                if (name.endsWith("_main")) lastSeen[name.removeSuffix("_main")] = now
            }
            delay(2_000)
        }
    }

    /** Debounce: true ngay khi vừa thấy; false chỉ khi mất > 10s. */
    fun isOnline(channelId: String): Boolean {
        val t = lastSeen[channelId] ?: return false
        return System.currentTimeMillis() - t <= 10_000
    }
}
```

### A5.1 Thêm vào `Go2RtcClient.kt`
```kotlin
/** GET http://127.0.0.1:1984/api/streams — trả tên stream đang có producer hoạt động. */
suspend fun liveStreamNames(): Set<String> {
    // JSON dạng { "<name>": { "producers": [ { ... } ], "consumers": [...] }, ... }
    // "live" = producers không rỗng và có byte_recv tăng / state running.
}
```

> Khởi động tracker ở `Application.module()` (sau khi DB sẵn sàng), giống cách spawn các service nền khác.
> **Phương án thay thế** nếu go2rtc khó parse: đọc Frigate `GET :5000/api/stats` → `cameras.<slug>.camera_fps > 0`.
> Chọn go2rtc vì nó phản ánh đúng cái Box tiêu thụ (restream).

---

## A6. Dựng restream URL

```kotlin
fun lanIp(): String =
    System.getenv("FRIGATE_HOST_IP")?.takeIf { it.isNotBlank() } ?: detectLanIp()

fun streamUrl(channelId: String, sub: Boolean): String {
    val name = if (sub) "${channelId}_sub" else "${channelId}_main"
    val rtspAuth = readIntegrationSettings().let { /* nếu bật go2rtc auth, nhúng user:pass@ */ }
    return "rtsp://${lanIp()}:8554/$name"   // hoặc rtsp://user:pass@... nếu rtsp_auth
}
```

- `sub_stream_url` = `null` nếu camera **không có sub-stream** (cột `Cameras.subRtspUrl` null/blank).
- **v1.1 mặc định `rtsp_auth=false`** → trả URL trần. (Bật auth go2rtc là nâng cấp tương lai.)

---

## A7. Chi tiết 3 endpoint

### A7.1 `GET /api/getintegrationinfo`
- **No auth.** Response 200:
```json
{ "protocol_version": "1.1", "nvr_version": "<build>", "max_cameras": 64, "rtsp_auth": false }
```
- `max_cameras`: hằng số hoặc đọc config; `nvr_version`: lấy từ build info có sẵn.

### A7.2 `POST /api/getprofilecamera`
- Rate-limit (A4) → `requireBoxAuth` (A3) → nếu OK:
- Request:
```json
{ "username":"box", "password":"secret", "already_added_camera_ids":["1","2"] }
```
- Logic:
  1. Đọc bảng `Cameras` (Exposed transaction).
  2. **Chỉ lấy camera có `channelId != null`** (cần channelId để dựng tên stream). Camera chưa có channelId → bỏ qua (xem Caveat C2).
  3. Loại các camera có `channelId ∈ already_added_camera_ids`.
  4. Map mỗi camera → object response.
- Response 200 (array):
```json
[
  { "camera_id":"3", "name":"Camera Cổng Chính",
    "main_stream_url":"rtsp://192.168.2.2:8554/3_main",
    "sub_stream_url":"rtsp://192.168.2.2:8554/3_sub",
    "online":true }
]
```
- Mapping: `camera_id=channelId`, `name=displayName`, `online=StreamOnlineTracker.isOnline(channelId)`,
  `sub_stream_url = subRtspUrl?.let{ streamUrl(channelId, sub=true) }`.

### A7.3 `POST /api/getcamerastatus`
- Rate-limit (A4). **No auth** (chỉ trả trạng thái, không lộ stream URL).
- Request: `{ "camera_ids":["3","4"] }`
- Logic: với mỗi id → `exists` = có row `channelId==id`?; `online` = tracker.
- Response 200:
```json
[ { "camera_id":"3", "exists":true, "online":true },
  { "camera_id":"4", "exists":false, "online":false } ]
```

---

## A8. Settings API + Frontend (bật/tắt + đặt user/pass)

API (đặt trong khối **protected** `authenticate("auth-session")`, vì là quản trị NVR):
| Endpoint | Method | Body | Ghi chú |
|---|---|---|---|
| `/api/integration/settings` | GET | — | trả `{ enabled, username, hasPassword }` (KHÔNG trả hash) |
| `/api/integration/settings` | PUT | `{ enabled, username, password? }` | `password` rỗng = giữ nguyên; có giá trị = `hashPattern()` rồi lưu |

Frontend: thêm 1 mục trong trang cài đặt NVR — toggle "Bật tích hợp AiView Box" + ô Username + ô Password
(placeholder "để trống = giữ nguyên") + nút Lưu. (Tham chiếu style các panel cài đặt hiện có.)

---

## A9. Nghiệm thu phía NVR (curl)

```bash
# 1) info
curl -s http://localhost:3000/api/getintegrationinfo
# 2) discovery (sau khi bật tích hợp + đặt user/pass=box/secret)
curl -s -X POST http://localhost:3000/api/getprofilecamera \
  -H "Content-Type: application/json" \
  -d '{"username":"box","password":"secret","already_added_camera_ids":[]}'
# 3) status
curl -s -X POST http://localhost:3000/api/getcamerastatus \
  -H "Content-Type: application/json" -d '{"camera_ids":["1"]}'
# 4) sai pass → 401 ; tích hợp tắt → 403 ; gọi >30 lần/phút → 429
```

> Lưu ý cổng: backend NVR chạy host **:3000** (sau nginx **:443**). Box trỏ tới cổng nào tuỳ deploy
> (protocol doc ví dụ 8088 chỉ là placeholder).

---

# PHẦN B — AiView Box (repo khác)

## B1. DB camera — thêm field cho loại `nvr_aiview`

| Field | Ý nghĩa |
|---|---|
| `source_type` | `"nvr_aiview"` |
| `nvr_base_url` | vd `http://192.168.2.2:8088` (để gọi getcamerastatus) |
| `nvr_camera_id` | = `camera_id` (channelId NVR) — **lưu đúng, không tự sinh** |
| `main_stream_url`, `sub_stream_url` | restream URL từ NVR |
| `internal_id` | id nội bộ Box |
| `nvr_status` | `online` / `offline` / `removed` (từ polling) |

## B2. Endpoint phía Box

### B2.1 `POST /api/nvr-aiview/connect`
```json
// request
{ "nvr_ip":"192.168.2.2", "nvr_port":8088, "username":"box", "password":"secret" }
```
- Logic: lấy `already_added_camera_ids` từ DB Box (các `nvr_camera_id` đã add) →
  `POST {nvr}/api/getprofilecamera` → trả nguyên list về WebUI.
- Lỗi: map lỗi NVR (`{error,message}`) hoặc lỗi kết nối (B5) → trả `{error,message}`.

### B2.2 `POST /api/nvr-aiview/add-cameras`
```json
// request
{ "nvr_base_url":"http://192.168.2.2:8088",
  "cameras":[ { "camera_id":"3", "name":"Cổng Chính",
               "main_stream_url":"rtsp://192.168.2.2:8554/3_main",
               "sub_stream_url":"rtsp://192.168.2.2:8554/3_sub" } ] }
```
- Logic mỗi camera:
  1. `PUT http://127.0.0.1:1984/api/streams?name={internal}_main&src={main_stream_url}` (go2rtc Box).
  2. (nếu có) đăng ký `_sub` tương tự.
  3. Lưu DB (B1).
- Response:
```json
{ "added":[ {"camera_id":"3","internal_id":"box_1024"} ],
  "failed":[ {"camera_id":"4","reason":"STREAM_REGISTER_FAILED"} ] }
```

## B3. go2rtc Box — auto-reconnect
Bật reconnect cho nguồn `nvr_aiview` (restream NVR có thể blip vài giây khi NVR restart Frigate).

## B4. Polling đồng bộ (phát hiện camera bị xoá + cập nhật badge)
- Vòng lặp nền **mỗi 30–60s** (và 1 lần khi user mở màn hình tích hợp):
  `POST {nvr_base_url}/api/getcamerastatus { camera_ids: [tất cả nvr_camera_id đã add] }`.
- Xử lý:
  - `exists=false` → set `nvr_status="removed"`, hiển thị "Đã gỡ khỏi NVR", cho phép xoá khỏi Box.
  - `online` → cập nhật badge Online/Offline (đã debounce phía NVR, không tự debounce thêm).
- Gặp `429` → dừng, chờ `Retry-After` rồi mới poll tiếp.

## B5. Lỗi / timeout / retry (phía Box)
- Timeout mỗi call **5–10s**, retry tối đa **1 lần** rồi báo lỗi. KHÔNG retry vòng lặp (tránh bị 429).
- Connection refused/timeout → "Không kết nối được tới NVR ({ip}:{port})".
- Parse JSON fail → "Phản hồi từ NVR không hợp lệ".
- Stream mất > 15s → "Mất tín hiệu — đang chờ NVR".

## B6. UI states
| Trạng thái | Badge / hiển thị |
|---|---|
| `online=true` | "NVR AIView" + live qua restream + chạy AI |
| `online=false` | badge "Offline", vẫn giữ camera, chờ lên |
| `exists=false` | "Đã gỡ khỏi NVR" + nút xoá |

---

# Bảng mã lỗi (tham chiếu nhanh)

| HTTP | error | Khi nào |
|---|---|---|
| 400 | BAD_REQUEST | body sai/thiếu field |
| 401 | INVALID_CREDENTIALS | sai user/pass tích hợp |
| 403 | FORBIDDEN | tích hợp đang TẮT |
| 429 | TOO_MANY_REQUESTS | vượt 30 req/phút/IP |
| 503 | SERVICE_UNAVAILABLE | Frigate/go2rtc đang restart |
| 500 | INTERNAL_ERROR | lỗi nội bộ NVR |

---

# Caveats / điểm cần lưu khi code

- **C1 — Băng thông:** mỗi camera Box pull = thêm main(+sub) vào **egress LAN NVR**. Link gateway/Nebula KHÔNG
  tăng tải (fan-out 1 producer). Vẫn phải xét trần upload LAN của NVR trước khi bật nhiều cam 4K.
- **C2 — channelId null:** chỉ camera có `channelId` mới xuất hiện qua tích hợp (cần để đặt tên stream).
  Camera thêm bằng đường cũ chưa có channelId sẽ bị bỏ. Nếu muốn phủ hết: gán channelId khi liệt kê.
- **C3 — Restream blip:** add/xoá camera trên NVR → restart Frigate → stream 8554 chớp tắt vài giây →
  go2rtc Box phải auto-reconnect; đừng báo lỗi sớm (< 15s).
- **C4 — Cổng 8554 reachable từ Box:** nếu Box khác mạng, cần định tuyến/forward riêng.
- **C5 — rtsp_auth:** v1.1 = false. Khi bật (bản thương mại), NVR nhúng credential vào stream URL và set
  `rtsp_auth=true`; Box không cần đổi gì ngoài việc dùng nguyên URL nhận được.

---

# Checklist nghiệm thu E2E

- [ ] Bật tích hợp + đặt user/pass trên NVR; `getintegrationinfo` trả đúng.
- [ ] `connect` từ Box → thấy danh sách camera chưa-add, có restream URL + `online` đúng.
- [ ] `add-cameras` → camera hiện trên Box, badge "NVR AIView", **live xem được qua restream**, AI chạy.
- [ ] Tắt camera nguồn → sau ≤ ~12s badge chuyển Offline (debounce), không nhấp nháy khi rớt 1–2s.
- [ ] Xoá camera trên NVR → polling Box thấy `exists=false` → "Đã gỡ khỏi NVR".
- [ ] Sai pass → 401; tắt tích hợp → 403; spam → 429 + Retry-After.
- [ ] Camera sau gateway/Nebula cũng add & xem được y hệt camera LAN (trong suốt).
```
