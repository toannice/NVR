# Nghiệm thu tích hợp AiView Box ↔ NVR — trước khi báo dev Box

> **Mục đích:** xác nhận NVR đã implement ĐÚNG hợp đồng `NVR_AIView_Integration_Protocol v1.1`
> (cùng mạng LAN) **trước khi** bàn giao cho đội dev Box. Một lệnh, tự chấm PASS/FAIL theo từng điều khoản.

## Đã làm phía NVR (repo này)

| Lớp | File | Vai trò |
|---|---|---|
| DB | `models/Schema.kt` → `IntegrationSettings` | 1 hàng (id=1): `enabled`, `username`, `password_hash` (bcrypt). Độc lập pattern-lock. |
| DB seed/migrate | `plugins/Databases.kt` | auto-tạo bảng + seed hàng id=1 (`enabled=false`). |
| Service | `services/IntegrationService.kt` | đọc/ghi settings, `verifyBoxAuth`, `RateLimiter` (30 req/phút/IP), dựng restream URL. |
| Online tracker | `services/StreamOnlineTracker.kt` + `cloud/Go2RtcClient.liveStreamNames()` | `online` = go2rtc `{id}_main` có producer; debounce: mất >10s mới `false`, có lại thì `true` ngay (§5). |
| Route công khai | `routes/IntegrationRouting.kt` → `integrationRouting()` | `GET /api/getintegrationinfo`, `POST /api/getprofilecamera`, `POST /api/getcamerastatus`. |
| Route quản trị | `routes/IntegrationRouting.kt` → `integrationAdminRouting()` | `GET/PUT /api/integration/settings` (trong `authenticate("auth-session")`). |
| UI | `frontend/src/components/IntegrationPanel.tsx` + tab **“Tích hợp”** trong `App.tsx` | bật/tắt + đặt username/password cho Box. |

**3 endpoint công khai = đúng hợp đồng v1.1 §3.** Box xác thực bằng cặp user/pass riêng (§8 hướng A),
không liên quan mật khẩu mở khoá màn hình. Mặc định `rtsp_auth=false` (restream trần trong LAN tin cậy).

## Chạy test hợp đồng

Yêu cầu: `bash`, `curl`, `jq`. NVR stack đang chạy (`./run.sh --rebuild` hoặc `docker compose up -d`).

```bash
# Mặc định: localhost:3000, tự tạo user/pass = box/secret, dùng cookie admin nội bộ
docs/aiview-box-contract-test.sh

# Hoặc trỏ tới NVR qua LAN / nginx 443, đặt creds tuỳ ý:
BASE=http://192.168.2.2:3000 BOX_USER=box BOX_PASS=secret docs/aiview-box-contract-test.sh
```

Script tự: bật tích hợp → kiểm tra 3 endpoint → sai pass (401) → body sai (400) → tắt (403) →
rate-limit (429 + `Retry-After`) → bật lại. Mỗi assertion in `PASS/FAIL` kèm số điều khoản. Exit ≠ 0 nếu có FAIL.

### Các điều khoản được kiểm (map sang protocol v1.1)

- **§3.1** `getintegrationinfo` → 200, `protocol_version=="1.1"`, `nvr_version:string`, `max_cameras:int`, `rtsp_auth:false`.
- **§3.2** `getprofilecamera` (đúng creds) → mảng; mỗi item `{camera_id:str, name:str, main_stream_url, sub_stream_url|null, online:bool}`;
  `main_stream_url` khớp `rtsp://<ip>:8554/<id>_main`; lọc `already_added_camera_ids`.
- **§3.3** `getcamerastatus` (không cần auth) → mảng `{camera_id, exists:bool, online:bool}`; id không tồn tại → `exists=false`.
- **§4.1** sai pass → `401 INVALID_CREDENTIALS`; body sai → `400 BAD_REQUEST`; tích hợp tắt → `403 FORBIDDEN`.
- **§4.4** quá 30 req/phút/IP → `429 TOO_MANY_REQUESTS` + header `Retry-After`.

> **Lưu ý online/cameras:** muốn `online=true` và có camera để Box thấy, cần ≥1 camera đã add (có `channel_id`)
> đang stream. Không có camera → phần kiểm shape item được bỏ qua (script vẫn chấm các điều khoản còn lại).
> Test rate-limit chạy **cuối** vì nó làm đầy cửa sổ đếm theo IP trong 1 phút.

## Checklist E2E thật với Box (sau khi script xanh)

- [ ] Trên UI NVR tab **Tích hợp**: bật + đặt user/pass → lưu OK.
- [ ] Box `connect` (IP NVR, port, user/pass) → thấy danh sách camera chưa-add + URL restream + `online`.
- [ ] Box `add-cameras` → camera lên Box, badge “NVR AIView”, **xem live qua restream**, AI chạy.
- [ ] Tắt camera nguồn → ≤ ~12s badge Box chuyển Offline (debounce), không nhấp nháy khi rớt 1–2s.
- [ ] Xoá camera trên NVR → polling Box thấy `exists=false` → “Đã gỡ khỏi NVR”.
- [ ] Sai pass → 401; tắt tích hợp → 403; spam → 429 + `Retry-After`.
- [ ] Camera sau gateway/Nebula add & xem được y hệt camera LAN (trong suốt với Box).
