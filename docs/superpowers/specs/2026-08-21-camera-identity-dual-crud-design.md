# Tách định danh camera — CRUD từ hai nguồn không nói sai

**Ngày:** 21-08-2026
**Trạng thái:** đã duyệt thiết kế, chờ thi hành

---

## 1. Vấn đề

Camera CRUD được từ hai nơi: Web UI LAN và dashboard cloud. Cả hai đi qua
`CameraService`, nhưng chúng tranh nhau cùng một không gian số kênh, và một trong hai
bên không có cách nào biết bên kia đã làm gì.

### Sự cố quan sát được (21-08-2026)

Dashboard cloud hiện một camera tên `192.168.2.4` đang online, trong khi NVR không có
camera nào ở IP đó. Dấu vết từ log:

```
[03-08 13:12] put_channels  channelID:"1"  host:"192.168.2.4"    ← cloud tự cấp số 1
[19-08 06:18] (Web UI)      channel_id  1   host: 192.168.1.25   ← UI lấy số nhỏ nhất trống = 1
```

`assignNextChannelIdLocked()` chỉ hỏi *"số nào chưa có trong bảng `cameras`"*. Camera cloud
ở số 1 bị xoá thì số 1 thành "trống" dưới mắt NVR, trong khi bên cloud bản ghi vẫn còn
nguyên. Web UI cấp lại số 1 cho một camera khác, rồi heartbeat 5 giây một lần báo
`ch1=online` — cloud thắp sáng đúng bản ghi cũ của nó.

### Bốn sự thật đã kiểm chứng bằng thực nghiệm

1. **Cloud là bên cấp số.** `put_channels` mang sẵn `channelID`; NVR nhận vô điều kiện.
2. **Metadata bên cloud chỉ sinh từ `put_channels`** — tức từ chính cái cloud gửi xuống.
   `get_channels` cloud có gọi nhưng không dùng để cập nhật danh sách của nó.
3. **Cloud không tự dọn.** Xoá camera ở NVR thì kênh đó chuyển sang *offline* trên
   dashboard, bản ghi vẫn còn tới khi có người bấm xoá ở đó.
4. **Cloud không gửi tên camera.** Payload `put_channels` chỉ có `channelID`, `host`,
   `port`, `https`, `username`, `password` + 3 trường null. Kể cả khi rename trên
   dashboard (thí nghiệm 21-08 16:45) cũng không có trường tên.

Server là **hộp đen** — không sửa được. Mọi giải pháp phải nằm trong NVR.

### Hai lớp lỗi khác cùng gốc

**Lẫn playback local.** `CameraService.kt:84` sinh `slug = "cam_$channelId"`. `slug` là tên
camera trong `config.yml` của Frigate, tức tên thư mục ghi hình và khoá của `ai_events`
(6050 dòng, `camera_slug='cam_1'`, `snapshot_path=/app/media/events/cam_1/...`). Số kênh
tái sử dụng thì `cam_1` tái sử dụng: trong cửa sổ retention 7 ngày, playback hiện hình
camera cũ dán nhãn camera mới.

**Ba bên cùng cache số kênh, không bên nào NVR xoá được:**

| Bên | Cache ở đâu |
|---|---|
| Cloud | bản ghi kênh trên dashboard |
| AiView Box | `already_added_camera_ids` gửi trong `/getprofilecamera` |
| Camera Bkav | cấu hình push trên thân camera |

---

## 2. Bất biến

> **Một số kênh đã phát ra ngoài thì không bao giờ được gán cho một camera vật lý khác.**

Và hệ quả trực tiếp:

> **Cloud chỉ được thấy, và chỉ được điều khiển, những camera do chính nó tạo.**

---

## 3. Mô hình định danh

Ba khái niệm, mỗi cái một việc:

| | Là gì | Ai dùng | Tái sử dụng |
|---|---|---|---|
| `uid` | UUIDv4 sinh lúc tạo, bất biến | nội bộ: `slug = cam_<uid8>` | không bao giờ |
| `channel_id` | Số 1–16, **mọi** camera đều có | Box, camera Bkav, ONVIF server, alias go2rtc, API events | có, theo luật ở §4 |
| `owner` | `cloud` \| `local` | quyết định camera có xuất hiện trước mặt cloud | — |

`channel_id` **không** nullable. Camera local vẫn cần số vì `BoxEventClient.kt:188` tra
`cam_id → channel_id → slug`, `BkavEventRouting.kt:225` khớp event theo `cam_id`,
`IntegrationRouting.kt:175` giao URL restream, và `EventsRouting.kt:33` trả `camera_id`
ra API. Bỏ số của camera local là mất tích hợp Box và event từ camera Bkav.

### Schema

Bảng `cameras`, thêm:

```
uid    TEXT NOT NULL UNIQUE        -- UUIDv4
owner  TEXT NOT NULL DEFAULT 'local'   -- 'cloud' | 'local'
```

`channel_id` thêm ràng buộc UNIQUE (hiện chưa có).

Bảng mới:

```
channel_claims(
  channel_id  TEXT PRIMARY KEY,
  issued_to   TEXT NOT NULL,   -- 'cloud' | 'box' | 'bkav'
  state       TEXT NOT NULL,   -- 'active' | 'orphan' | 'released'
  claimed_at  TEXT NOT NULL,
  last_seen   TEXT NOT NULL
)
```

`channel_claims` là ký ức duy nhất NVR có về chuyện "số này đã ra khỏi máy". Không có nó
thì mọi luật cấp số đều mù, vì §1.3 đã chứng minh trạng thái bên ngoài không quan sát được.

### Điểm cốt tử

Khi `slug` không còn phụ thuộc `channel_id`, **đổi số kênh trở thành cập nhật metadata
thuần**: không đổi khoá camera trong `config.yml`, không dời thư mục ghi hình, không sửa
6050 dòng `ai_events`. Chỉ đăng ký lại hai alias go2rtc. Đó là điều làm luật "dời camera
local" ở §4.3 trở nên an toàn thay vì là một migration rủi ro mỗi lần xung đột.

---

## 4. Luật cấp số

### 4.1 Hai đầu, trần động

Cloud cấp từ 1 lên (hành vi quan sát được). Web UI cấp **từ 16 xuống**.

Không chia cứng 8/8 — khách chỉ dùng local sẽ bị chặn ở 8 camera trên NVR 16 kênh. Thay
vào đó `maxChannelNum` mà NVR khai cho cloud (`CameraCommandHandler.kt:118`, hiện hardcode
`DeviceConstants.NUMBER_OF_CHANNEL = 16`) đổi thành:

```
16 − (số camera local + số claim local chưa released)
```

Cloud thấy trần tụt dần khi thêm camera ở UI nên không có lý do bò vào dải trên. Tổng hai
bên không vượt 16, không bên nào bị giới hạn cứng. Đây là đòn duy nhất ta có với một
server hộp đen: `maxChannelNum` là **thông tin do NVR khai**.

### 4.2 Claim chỉ ghi khi số thật sự ra khỏi máy

Không tombstone lúc cấp. Tombstone lúc **giao cho một bên ngoài**:

| Ghi claim khi | `issued_to` | Chỗ móc |
|---|---|---|
| cloud gửi `put_channels`/`post_channels` số N | `cloud` | `CameraCommandHandler.postOrPutChannels` |
| NVR trả số N trong `/getprofilecamera` | `box` | `IntegrationRouting.kt:171` |
| event đẩy về với `cam_id = N` | `bkav` | `BkavEventRouting.kt:225` |

Camera thêm ở UI, không Box nối, cloud không biết → **không sinh claim** → xoá đi dùng lại
được ngay. Vòng lặp thêm/xoá lúc dev không hao số nào.

Nếu tombstone ngay lúc cấp thì 16 số cạn trong một buổi test, và một luật đúng-về-lý-thuyết
sẽ là thứ đầu tiên bị người ta tắt đi.

Cấp số bỏ qua `active` và `orphan`. `orphan → released` chỉ đổi bằng tay ở màn dọn (§6.2).
Hết số thì **báo lỗi rõ ràng** trỏ vào màn dọn, không âm thầm cấp lại một `orphan`.

### 4.3 Lưới an toàn: cloud giành số camera local đang giữ

Không loại trừ được, vì không biết cloud có tôn trọng `maxChannelNum`.

| Camera local ở số N | Xử lý |
|---|---|
| Chưa có claim ngoài | **Dời** sang số local trống khác, cloud nhận N |
| Có claim `box` hoặc `bkav` | **Từ chối** `put_channels`, `E0007` nói rõ số N đang được Box dùng |

Từ chối ở trường hợp hai là có chủ ý: dời đi sẽ khiến Box coi nó là camera mới và giữ số cũ
thành ma bên Box — đổi một cái nói sai ở cloud thành một cái nói sai ở Box. Thà cloud báo
lỗi ngay lúc người ta đang bấm thêm camera.

Mọi lần dời **và** mọi lần từ chối: `AppLogger.warn(CAMERA, "CHANNEL_ALLOC", …)` + Telegram.

### 4.4 Một cổng duy nhất

`ChannelAllocator` mới, cạnh `CameraService`. `assignNextChannelIdLocked()` bị xoá. Không ai
ngoài `ChannelAllocator` được quyết định một `channel_id` — hôm nay `CameraRouting.kt:166`
đã lặng lẽ tự sinh `slug` theo kiểu riêng, và đó chính là nguồn 15 stream rác
`cam_c_ng_192_168_2_73` trong `config.yml`.

---

## 5. Bề mặt hướng cloud và khoá

### 5.1 Chặn ở đường phân giải, không chỉ lọc danh sách

Lọc `getCameraStatusList()` (`SignalingManager.kt:168`) và `getChannels()`
(`CameraCommandHandler.kt:49`) còn `owner='cloud'` là cần, nhưng một mình nó là mỹ phẩm:
cloud không cần danh sách để hỏi. Nó có bản ghi kênh 1 và gửi thẳng offer LIVE cho kênh 1;
đường live phân giải theo `${channelId}_main` (`SignalingManager.kt:579`) nên sẽ trả video
thật của camera local, dán nhãn cũ.

Một hàm phân giải duy nhất:

```kotlin
fun resolveCloudChannel(channelId: String): CameraRow?   // null nếu không phải owner='cloud'
```

**Mọi** cửa vào từ cloud phải đi qua: `get_channels`, `put/post/del_channels`,
`get_profiles_channel`, `get_videosources`, `get_snapshot`, `get_ai_event_image`, offer LIVE
trong `SignalingManager`, `PlaybackCommandHandler`. Không khớp → `E0007` + log
`CLOUD_CHANNEL_DENIED`. Whitelist chứ không blacklist: cửa nào quên móc thì hỏng chức năng,
không rò dữ liệu.

Alias go2rtc `${n}_main` vẫn tạo cho cả camera local vì Box lấy stream bằng tên đó. Bù lại
`IntegrationService.streamUrl` đổi sang `slug`: `rtsp://<lan>:8554/<slug>` — alias số trở
lại cloud-only, và URL giao cho Box không đứt khi camera bị dời số. Giá bằng 0 lúc này vì
`box_ip` đang trống.

### 5.2 Khoá cưỡng chế ở `CameraService`

Ẩn nút không phải khoá; chặn từng route thì đường thứ ba xuất hiện là hở (`GatewayService`
đang gọi thẳng `removeAndPersist`). Chặn ở cổ chai:

```kotlin
enum class Actor { LOCAL_UI, CLOUD, GATEWAY, SYSTEM }
```

`LOCAL_UI` không sửa/xoá được camera `owner='cloud'` → `403 CLOUD_MANAGED`.

### 5.3 Khoá cái gì

Quy tắc tự bảo trì: **khoá đúng những trường `put_channels` ghi, thả mọi thứ còn lại.**

| Khoá | Thả |
|---|---|
| xoá camera | lịch trình ghi hình |
| `ip`, `onvif_port`, `username`, `password` | `ptz_mode` |
| `rtsp_url`, `sub_rtsp_url` | `ai_intent`, zone AI |
| | `display_name` |

`display_name` **thả**, khác với bản thiết kế đầu. Lý do ban đầu ("cloud ghi đè ở lần put
sau") sai: §1.4 chứng minh cloud không gửi tên. Khoá lại là đóng băng camera ở tên
`Camera 2` vĩnh viễn.

### 5.4 Xoá gateway — ngoại lệ có chủ ý

`GatewayService.removeCamerasForGateway` xoá hàng loạt. Chặn nó theo §5.2 thì không xoá
được gateway nữa. Cho phép bằng actor `GATEWAY`, kèm ba điều kiện: hộp xác nhận nói rõ
*"N camera do Cloud quản lý cũng bị xoá, bạn phải tự xoá trên dashboard"*, sinh claim
`orphan` cho từng số, log `WARN` + Telegram.

---

## 6. Đường thoát và màn dọn

### 6.1 Đường thoát khỏi khoá

| Cửa | Kích hoạt | Hành vi |
|---|---|---|
| Tắt Cloud tường minh | Thiết lập → Kết nối Cloud → tắt | Xác nhận → `owner='local'`, dời số sang dải trên, claim cũ → `orphan` |
| Mất kết nối > 7 ngày | `lastCloudSeen` quá hạn | Chỉ **hiện dải mời**, không tự làm gì; người vận hành bấm *Giải phóng* |

Ngưỡng để trong `app_settings` (`cloud_free_after_days`, mặc định `7`), không hardcode.

Không dùng `connectionLost` làm tín hiệu tự động: nó nổ y như nhau khi rớt mạng 10 giây,
broker restart, hay đổi IP WAN. Tự mở khoá theo tín hiệu đó là dựng lại đúng cái lỗ vừa bịt.
`device_service` cũng không có func nào để cloud nói "tôi đã xoá thiết bị này" — đã soi hết.

Phần *dời số sang dải trên* là bắt buộc: để nguyên thì hôm sau nối lại cloud, cloud cấp
`put_channels` đúng số đó và ta về vạch xuất phát.

### 6.2 Màn "Số kênh đã phát ra ngoài"

NVR không dọn hộ được, chỉ nói cho người vận hành biết phải dọn gì.

| Kênh | Đã giao cho | Camera hiện tại | |
|---|---|---|---|
| 1 | Cloud (03-08) | — không còn — | `[Thu hồi]` |
| 2 | Cloud (21-08) | Camera 2 · 192.168.1.8 | đang dùng |

`[Thu hồi]` mở hộp nói rõ phải làm gì **trước**: *"Xoá kênh 1 trên dashboard Cloud, rồi bấm
xác nhận. Nếu chưa xoá mà thu hồi, camera thêm sau sẽ hiện sai tên trên dashboard."*

---

## 7. Sửa kèm, trong phạm vi

### 7.1 Tên và model bị vứt đi

`applyAndPersistFromOnvif` probe được thiết bị rồi ghi vào DB **tham số của caller**:

```kotlin
val info = OnvifSoapClient.getDeviceInfoAndStream(host, port, user, pass)  // có manufacturer, model
configMutexApply(manufacturer = manufacturer, model = model)               // ← mặc định "Unknown"
```

Đó là vì sao cả hai camera trong DB đều `Unknown/Unknown` và camera cloud thành `Camera 2`.

Sửa: dùng `info.manufacturer`/`info.model` khi caller không đưa giá trị tốt hơn. Tên mặc
định `"<manufacturer> <model>"`; trùng tên thì thêm ` (<ip>)`; probe không ra gì mới rơi về
`"Camera $channelId"`.

Vì cloud không gửi tên, tên trên dashboard và tên trong Web UI **sẽ khác nhau** — Web UI
phải nói thẳng điều đó trên dòng camera cloud thay vì để người vận hành tưởng là lỗi.

### 7.2 `put_channels` phải idempotent theo nội dung

Đổi tên camera trên dashboard làm Frigate restart (đo được 21-08 16:45:14 →
`ONLINE_TRACKER_RESTART_HOLD`, Frigate không phản hồi tới 16:45:27). Vì `put_channels` đi
thẳng vào đường apply bất kể payload có đổi gì thật.

Sửa: `host`/`port`/`username`/`password` không đổi so với DB → bỏ qua toàn bộ đường apply,
chỉ cập nhật `last_seen` của claim, trả `put.device.success`, log `PUT_CHANNELS_NOOP`.

### 7.3 Hợp nhất hai bộ sinh slug

`CameraService.kt:84` dùng `cam_$channelId`, `CameraRouting.kt:166` dùng `<tên>_<ip>`. Gộp
về `ChannelAllocator`/`CameraService`. Dọn 15 stream rác `cam_c_ng_192_168_2_73` còn trong
`config.yml`.

---

## 8. Migration

Chạy một lần lúc backend khởi động, sau `backfillChannelIds()`:

1. Camera chưa có `uid` → sinh UUIDv4
2. `owner`: có claim `cloud` cho `channel_id` đó → `cloud`, không thì `local`. **Cloud là
   mặc định an toàn hơn**: đoán sai thành `cloud` chỉ gây khoá oan (mở được bằng §6.1), đoán
   sai thành `local` là mở lại đúng cái lỗ đang bịt
3. `channel_claims`: coi mọi `channel_id` đang tồn tại là claim `active`. **Không** suy diễn
   quá khứ từ log (log rotate). Số 1 vì thế không tự thành `orphan`
4. `slug`: **không đổi** cho camera đang có

Điểm 4 là đánh đổi có chủ ý. Đổi slug camera cũ nghĩa là `UPDATE ai_events` 6050 dòng, đổi
tên `media/events/cam_2/`, sửa `snapshot_path`/`clip_path` tuyệt đối, đổi khoá camera trong
`config.yml` — migration rủi ro thật để đổi lấy sự nhất quán hình thức. Camera cũ **không**
có nguy cơ lẫn playback vì `cam_2` chưa từng bị cấp lại. Hệ quả: hai kiểu slug cùng tồn tại,
phải ghi rõ trong `CLAUDE.md`.

Trạng thái máy dev lúc viết: 1 camera (`channel_id=2`, `192.168.1.8`, cloud), `channel 1`
trống, dashboard cloud còn bản ghi ma cho kênh 1.

---

## 9. Kiểm chứng

Unit test (`backend/src/test`, chưa tồn tại — task đầu dựng harness):

| Trường hợp | Kỳ vọng |
|---|---|
| Cấp số local lần đầu | `16` |
| Cấp số local khi 16 đang dùng | `15` |
| Cấp số local khi 15 là `orphan` | `14`, không phải `15` |
| Cấp số local khi 15 là `released` | `15` |
| Hết số | ném lỗi có mã trỏ màn dọn, không cấp lại `orphan` |
| Xoá camera local chưa từng ra ngoài | số về dùng lại được ngay |
| Xoá camera local đã trả cho Box | số thành `orphan` |
| `resolveCloudChannel` với camera local | `null` |
| `put_channels` số đang bị camera local giữ, chưa claim ngoài | dời camera local, cloud nhận số |
| `put_channels` số đang bị camera local giữ, có claim `box` | từ chối `E0007`, camera local không đổi |
| `put_channels` nội dung y cũ | no-op, không restart Frigate |
| `LOCAL_UI` xoá camera `owner='cloud'` | `403 CLOUD_MANAGED` |
| `LOCAL_UI` sửa `ptz_mode` camera cloud | thành công |
| `LOCAL_UI` sửa `ip` camera cloud | `403` |
| `maxChannelNum` với 3 camera local | `13` |
| Migration camera cũ | `uid` sinh ra, `slug` không đổi |

Kiểm bằng tay trên máy dev, có cloud thật:

1. Thêm camera ở UI → nhận số 16, **không** xuất hiện trên dashboard
2. Thêm camera từ dashboard → nhận số 1, hiện đúng model thiết bị (không phải `Camera 1`)
3. Camera cloud ở Web UI: có ổ khoá, nút Sửa/Xoá disabled, xem live được
4. `curl -X DELETE` camera cloud với session hợp lệ → `403 CLOUD_MANAGED`
5. Đổi tên camera trên dashboard → **Frigate không restart** (`PUT_CHANNELS_NOOP`)
6. Tắt Kết nối Cloud → camera cloud thành local, dời sang số 15, mở khoá được
7. Màn dọn liệt kê kênh 1 là `orphan`; thu hồi rồi thêm camera mới thì nó nhận lại số 1

E2E (`tests/e2e/tests/camera-identity.spec.ts`): camera cloud có `disabled` trên nút Xoá;
API `DELETE` trả 403.

---

## 10. Cái này KHÔNG giải quyết

- **Ma bên Box.** Box cũng nhớ `camera_id` và NVR không xoá hộ được. Số đã trả cho Box mà
  camera bị dời/xoá thì Box giữ ma, y như cloud. Màn dọn hiện cả claim `box` để người vận
  hành biết, nhưng việc dọn vẫn phải làm ở Box.
- **Tên khác nhau giữa dashboard và Web UI.** Không có đường truyền tên. Chỉ nói rõ cho
  người dùng.
- **Camera Bkav cấu hình `cam_id` bằng tay.** NVR chỉ biết sau khi có event đầu tiên tới;
  trước đó số đó có thể đã bị cấp cho camera khác. Không phòng được, chỉ phát hiện sau.
