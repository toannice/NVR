# Tổng quan các phiên bản

| Version | Thời điểm | Mô tả ngắn |
|---------|-----------|-----------|
| 1.0.0.0 | 17/06/2026 | Phiên bản phát hành đầu tiên (baseline thương mại) |
| 1.0.0.1 | ~23–29/06/2026 | Hoàn thiện bảo mật gateway Nebula đợt 2 (G4/G5-A/G8/G9) |
| 1.0.0.2 | 01–03/07/2026 | Cloud playback tăng tốc x2/x4/x8, OTA theo khung giờ, chuẩn hóa VERSION |
| 1.0.0.3 | 03/07/2026 → nay | Box AI đẩy sự kiện + tab "Sự kiện AI", MQTT auth, playback play/pause thật, vận hành log |

> Ghi chú: 1.0.0.1 và 1.0.0.2 là các bản OTA trung gian (không phát hành bundle offline riêng); mốc thời gian lấy theo lịch sử git. Bundle offline chính thức: V1.0.0.0 (17/06) và V1.0.0.3 (15/07).

# Tính năng cơ bản của sản phẩm (baseline V1.0.0.0)

Nhắc lại khung tính năng nền — các version sau chỉ thay đổi/bổ sung trên nền này:

- **Camera & Live view:** thêm/sửa/xóa camera qua Web UI (RTSP trực tiếp hoặc ONVIF discovery); ghi hình liên tục bằng Frigate 0.16.3 (chốt version); live view WebRTC lên cloud AIView; PTZ auto-detect + override thủ công.
- **Gateway (Raspberry Pi):** camera ở LAN xa kết nối về NVR qua overlay Nebula tự host (lighthouse + signing service ở HQ); NVR auto-scan → cấp phép → adopt camera; Pi chỉ proxy L4, không giữ mật khẩu camera; cert tự gia hạn (R1) / thu hồi (V3).
- **Cloud AIView:** đồng bộ camera CRUD qua MQTT; device info / playback qua WebRTC DATA (sidecar dc-bridge); một bảng `Cameras` là nguồn sự thật chung cho Web UI lẫn cloud.
- **Tích hợp AiView Box:** REST API tự xác thực (getintegrationinfo / getprofilecamera / getcamerastatus), rate-limit 30 req/phút/IP, audit log đầy đủ.
- **Bảo mật & xác thực:** pattern lock + OTP Gmail; bcrypt cost 12; chặn SSRF khi khai báo RTSP URL.
- **Triển khai & vận hành:** cài offline bằng bundle (`deploy-offline.sh`, không cần internet) hoặc OTA qua GHCR (`WRAPPER_TAG`: latest/staging/pin version); watchdog tự phục hồi container + cảnh báo Telegram.

# Chi tiết thay đổi giữa các phiên bản

## V1.0.0.1 — ~23–29/06/2026

**Loại:** Bản vá bảo mật + ổn định (OTA)

### Bảo mật gateway Nebula (đợt hardening 2)

- **G4 — Agent trên Pi bỏ quyền root:** chạy bằng user riêng `nvragent` không đăng nhập được, sandbox systemd (filesystem read-only trừ 3 thư mục cần ghi), chỉ được sudo đúng 2 lệnh (reload nginx, restart nebula).
- **G5-A — Thu hẹp lộ thông tin:** endpoint `/identity` (không cần token, dùng cho app enroll) không còn trả thông tin trinh sát overlay (overlay IP, lighthouse, số camera).
- **G8 — Thu hồi gateway từ NVR:** nút "Thu hồi" trên tab Gateway → HQ revoke cert (chặn gia hạn + blocklist) → tự gỡ camera liên quan; hoạt động best-effort kể cả khi HQ down.
- **G9 giai đoạn 1 — Đóng cổng:** RTSP restream và các port nội bộ chỉ bind localhost + LAN IP, thiết bị trong mạng ảo không xem được dữ liệu NVR.
- Chuẩn hóa vòng đời cert (renew/revoke) sau đợt G1/G2/G3/G6 của bản 1.0.0.0.

### Sửa lỗi & khác

- Edge-agent: kiểm tra nginx bằng `systemctl` thay vì `nginx -t` (lỗi quyền đọc pid sau khi bỏ root — G4).
- Gán số hiệu sản phẩm thiết bị `NVR-01`.
- Dọn repo: bỏ track file runtime/secret (config.yml, nvr.db, jwt_secret...).

## V1.0.0.2 — 01–03/07/2026

**Loại:** Tính năng playback cloud + vận hành OTA (OTA)

- **Cloud playback tăng tốc:** transcode phần cứng VAAPI (Intel GPU), encode an toàn cho WebRTC, hỗ trợ tua nhanh **x2 / x4 / x8** khi xem lại từ dashboard cloud.
- **OTA theo khung giờ:** watchdog chỉ áp dụng bản cập nhật vào giờ cấu hình (mặc định **3h sáng**) — tránh restart giữa giờ làm việc.
- **Một nguồn sự thật cho version:** file `VERSION` ở gốc repo được nướng vào image (trước đây badge version luôn hiện "dev"); API `/api/version` trả đúng số bản.
- Cập nhật tổng hợp code ngày 01/07 (đồng bộ 003, chỉnh AI zone mặc định).

## V1.0.0.3 — 03/07/2026 → 15/07/2026

**Loại:** Tính năng tích hợp Box + bảo mật MQTT + hoàn thiện playback (bản hiện hành, có bundle offline)

### Box AI đẩy sự kiện sang NVR (tính năng lớn nhất)

- Endpoint mới **`POST /api/pushevent`**: Box phát hiện sự kiện AI (khuôn mặt, người, xâm nhập...) → gửi camera_id + timestamp + loại AI → NVR trả 202 ngay (fire-and-forget).
- NVR tự **cắt clip -5s/+5s** quanh thời điểm sự kiện từ recording sẵn có (ffmpeg stream-copy, không re-encode), tự trích ảnh đại diện nếu Box không gửi snapshot.
- Web UI thêm tab **"Sự kiện AI"**: danh sách sự kiện (lọc nguồn Box/Frigate), xem clip + ảnh, badge "BOX AI".
- Idempotent theo `event_id` (Box retry an toàn, không cắt clip trùng); tài liệu tích hợp đầy đủ cho đội Box tại `specs/box-ai-event-push-integration.md`.

### Bảo mật

- **MQTT nội bộ bắt buộc xác thực:** mosquitto yêu cầu user/password + **ACL riêng từng client** (frigate / nvr-wrapper / watchdog chỉ được đúng topic của mình) — chặn thiết bị lạ trong LAN nghe bus nội bộ.

### Cloud playback hoàn thiện

- **Play/pause thật** khi xem lại từ cloud: dc-bridge thêm `/playback/pause` giữ nguyên peer (trước đây pause là no-op).
- **Source dc-bridge (Go/Pion) đưa vào repo** (`dc-bridge/`) — hết phụ thuộc bản build ngoài repo, build lại bằng `./dc-bridge/build.sh`.

### Vận hành & watchdog

- Watchdog: **nội dung cảnh báo Telegram chi tiết hơn** + đặt tên máy theo `HOSTNAME` — phân biệt được máy nào báo khi vận hành nhiều NVR.
- **Giới hạn log Docker** mọi container (json-file, 50MB × 3 file) — chặn sự cố log phình đầy đĩa hệ thống sau vài tháng chạy.
- CI: bỏ trigger nhánh `git-staging` cũ; kênh beta chỉ còn qua lệnh `/staging-push` chủ động.

### Đã có trong bundle này nhưng chưa phát hành OTA (hoàn thành 15/07, chờ commit)

- **Alert Telegram khi gia hạn cert gateway thất bại ≥ 2 lần liên tiếp** (agent đếm số lần fail, NVR giám sát và cảnh báo — phát hiện sớm loại sự cố "token ký cert sai" trước khi cert hết hạn hàng loạt).
- Pi gateway: cài **chrony + đợi đồng bộ giờ** trước khi start Nebula (Pi không có RTC — hết lỗi "offline bí ẩn" sau mất điện lâu); **chống mòn thẻ SD** (journal vào RAM, /var/log tmpfs).
- Quy trình lắp đặt: chốt chính sách **camera luôn đặt IP tĩnh** (chống lỗi camera đổi IP DHCP sau cúp điện → proxy trỏ IP cũ).

# Nâng cấp từ bản cũ

- **Máy đang chạy OTA (kênh `latest`):** tự nhận 1.0.0.3 vào 3h sáng sau khi image được push. Riêng thay đổi hạ tầng (MQTT auth) cần chạy lại `install.sh` trên máy (OTA chỉ thay image app, không thay config mosquitto).
- **Máy mới / máy offline:** dùng bundle `V1.0.0.3-20260715.tar.gz` → giải nén → `sudo ./deploy-offline.sh` (quy trình không đổi so với V1.0.0.0 — xem "Hướng dẫn cài đặt V1").
