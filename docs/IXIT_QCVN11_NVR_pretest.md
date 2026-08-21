# Kiểm thử thông tin PRE-TESTED — Đối chiếu sản phẩm NVR với QCVN 11:2026/BCA

> **Mục đích:** Rà soát xem hồ sơ/tính năng của sản phẩm **AIView NVR** đã đủ thông tin để khai IXIT
> theo QCVN 11:2026/BCA hay chưa, và chỉ ra các mục **còn thiếu / cần bổ sung** trước khi nộp phòng đo kiểm.
>
> - **Ngày lập:** 2026-07-16
> - **Bản QCVN tham chiếu:** QCVN 11:2026/BCA (Thông tư 48/2026/TT-BCA, ký 12/5/2026, hiệu lực **01/7/2026**) — thay thế QCVN 135:2024/BTTTT (hết hiệu lực 01/7/2026). Viện dẫn ETSI EN 303 645 v2.1.1 + ETSI TS 103 701 v1.1.1.
> - **Form gốc đối chiếu:** `IXIT_QCVN11_P200-0803FG.docx` — đây là form của **camera** model P200-0803FG, KHÔNG phải của NVR (xem §0 về phạm vi).
> - **Bằng chứng NVR:** audit mã nguồn repo `my-nvr` @ VERSION `1.0.0.3` (tham chiếu `file:line` trong từng mục).

---

## 0. ⚠️ Cảnh báo phạm vi (đọc trước) — NVR có thuộc diện hợp quy không?

QCVN 11:2026/BCA (mục 1.1 + định nghĩa 1.5.42) quy định đối tượng là **"camera kỹ thuật số kết nối Internet, thực hiện một phần hoặc toàn bộ việc giám sát, ghi hình"**. Quy chuẩn **KHÔNG nêu đích danh đầu ghi NVR/DVR**. Theo tiền lệ QCVN 135:2024/BTTTT (cùng định nghĩa), đầu ghi NVR/DVR độc lập **không thuộc phạm vi** — chỉ camera IP độc lập phải gắn dấu hợp quy (CR).

**Hệ quả cho dự án:**
1. **Sản phẩm NVR (phần mềm ghi hình trên PC Ubuntu) nhiều khả năng KHÔNG bắt buộc hợp quy QCVN 11.** Phạm vi cuối cùng phụ thuộc **danh mục sản phẩm rủi ro trung bình/cao do Bộ Công an ban hành** (chưa công bố tại thời điểm lập tài liệu). Cụm "thực hiện một phần… việc giám sát, ghi hình" có độ mở → cần theo dõi danh mục.
2. **Các camera IP bán kèm / tích hợp vào hệ thống NVR VẪN phải có dấu CR theo QCVN 11** khi danh mục ban hành → ảnh hưởng chuỗi cung ứng.
3. Tài liệu này dùng 11 nhóm QCVN 11 làm **checklist tự đối chiếu "tuân thủ tương đương"** cho NVR — hữu ích cả khi không bắt buộc, và bắt buộc nếu sau này BCA đưa NVR vào danh mục.

> **Việc cần làm ngay (không kỹ thuật):** gửi công văn/hỏi tổ chức đo kiểm được chỉ định (hoặc A05 — Cục An ninh mạng) xác nhận **NVR phần mềm có thuộc danh mục rủi ro phải hợp quy hay không**. Đây là mục quyết định toàn bộ khối lượng còn lại.

---

## 1. Bảng tổng hợp mức độ đầy đủ (11 nhóm)

| Nhóm | Tên | Trạng thái NVR | Ghi chú nhanh |
|---|---|---|---|
| 2.1 | Khởi tạo mật khẩu duy nhất | 🟠 **Một phần** | Pattern-lock bắt buộc đặt lần đầu (tốt) NHƯNG có tài khoản API mặc định `admin/Bkav@2020` bật sẵn → **vi phạm 5.1** |
| 2.2 | Quản lý lỗ hổng bảo mật | 🔴 **Thiếu** | Chưa có chính sách tiết lộ lỗ hổng công khai (URL). **Bắt buộc.** |
| 2.3 | Quản lý cập nhật | 🟠 **Một phần** | Có OTA + rollback + version UI; **THIẾU: ký số firmware/image, thời hạn hỗ trợ công bố, thông báo cập nhật trong UI** |
| 2.4 | Lưu trữ tham số nhạy cảm | 🟠 **Một phần** | AES-256-GCM cho mật khẩu camera, NHƯNG **khóa AES lưu plaintext cạnh DB**; mật khẩu RTSP cleartext trong `config.yml` |
| 2.5 | Kênh giao tiếp an toàn | 🟢 **Gần đủ** | HTTPS/TLS1.2+1.3, MQTT có auth+ACL; cần khai cipher suite + xử lý FTP không mã hóa |
| 2.6 | Phòng chống tấn công qua giao diện | 🟠 **Một phần** | **Nhiều endpoint nhạy cảm KHÔNG auth** (system/storage/network/export, `/internal/dc/command`); CORS `anyHost()`; cần liệt kê cổng/dịch vụ |
| 2.7 | Bảo vệ dữ liệu người dùng | 🟢 **Gần đủ** | Dữ liệu qua HTTPS/TLS; cần mô tả đầy đủ cảm biến + xử lý kênh FTP |
| 2.8 | Tự khôi phục sau sự cố | 🟢 **Đủ** | Watchdog self-heal + `restart:` policy + healthcheck + auto-reconnect MQTT/RTSP |
| 2.9 | Xóa dữ liệu trên thiết bị | 🟠 **Một phần** | Có xóa camera/export + retain 7 ngày; **THIẾU: factory-reset xóa toàn bộ dữ liệu người dùng + khóa** |
| 2.10 | Xác thực dữ liệu đầu vào | 🟠 **Một phần** | Có `validateRtspUrl` (SSRF một phần); **THIẾU chống path-traversal ở export/screenshot**, validate form/API tổng quát |
| 2.11 | Bảo vệ dữ liệu (đặc thù VN) | 🔴 **Thiếu nhiều** | **THIẾU: consent thu thập/thu hồi, khai telemetry, cấu hình lưu trữ tại VN, minh bạch dữ liệu cá nhân** |

**Chú giải:** 🟢 đủ để khai — 🟠 có cơ chế nhưng thiếu thông tin/còn lỗ hổng — 🔴 chưa có, phải bổ sung.

**Kết luận nhanh:** Hồ sơ **CHƯA đủ** để nộp. 2 nhóm 🔴 (2.2, 2.11) và 6 nhóm 🟠 cần bổ sung. Xem §3 danh sách hành động.

---

## 2. Đối chiếu chi tiết từng nhóm (Yêu cầu QCVN → Bằng chứng NVR → Còn thiếu)

### 2.1 — Khởi tạo mật khẩu duy nhất (IXIT 1-AuthMech, 2-UserInfo)

| Y/c | Bằng chứng NVR hiện có | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.1.1 Mật khẩu duy nhất / do người dùng đặt | Pattern-lock **bắt buộc thiết lập lần đầu** (`LockRouting.kt:51-54`), không có pattern xuất xưởng; setup cần email + OTP | 🟢 | Khai đây là cơ chế chính; nêu rõ single-account (`session id = "admin"`) |
| 2.1.2 Mật khẩu cài sẵn phải sinh chống dò tự động | **VI PHẠM:** tài khoản API tích hợp mặc định `admin` / `Bkav@2020` **bật sẵn** khi khởi tạo (`Databases.kt:56-70`), không ép đổi | 🔴 | **Bỏ default creds hoặc ép đổi lần đầu**; sinh mật khẩu ngẫu nhiên per-device. Đây là điểm lab sẽ bắt lỗi ngay. |
| 2.1.3 Xác thực dùng mật mã an toàn | Pattern hash **bcrypt cost 12** (`Security.kt:93`); Box creds bcrypt; session cookie **HMAC-SHA256 signed** | 🟢 | Khai thuật toán băm (bcrypt), TLS tối thiểu + cipher suite (§2.5). **Lưu ý:** cookie thiếu cờ `secure` (`Security.kt:52`) → nên bật |
| 2.1.4 Cho phép đổi giá trị xác thực | Có reset pattern qua email OTP; đổi Box creds qua admin API | 🟢 | Viết HDSD bước đổi (lab thực hành theo HDSD) |
| 2.1.5 Chống brute-force qua mạng | Pattern verify: **5 lần sai → khóa 60s** (`LockRouting.kt:129-131`, HTTP 429); Box API rate-limit 30 req/60s | 🟠 | **Lỗ hổng:** OTP **không giới hạn số lần thử** (không gian 10^6); khóa pattern **global + in-memory** (reset khi restart), không per-IP. nginx **không có `limit_req`**. Lab sẽ chủ động thử vét cạn MỌI giao diện (WebUI, ONVIF, RTSP). → thêm throttle OTP + rate-limit nginx |

### 2.2 — Quản lý lỗ hổng bảo mật (IXIT 2-UserInfo)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.2.1 Công bố chính sách tiếp nhận & xử lý lỗ hổng | **KHÔNG có** `SECURITY.md`, không URL công bố | 🔴 | **BẮT BUỘC:** công bố công khai (URL truy cập tự do, không cần login) gồm: (1) đầu mối liên hệ (vd `security@bkav.com`); (2) cam kết thời gian xác nhận đã nhận; (3) cam kết cập nhật trạng thái đến khi vá xong. Lab sẽ truy cập URL này kiểm tra (test 3.2.1). |

### 2.3 — Quản lý cập nhật (IXIT 6-SoftComp, 7-UpdMech, 8-UpdProc, 9-ReplSup)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.3.1 Thông báo có bản cập nhật + cài an toàn | OTA qua watchdog lúc 3h sáng (`UpdateManager.kt`); thông báo **chỉ qua Telegram**, không có thông báo trong UI | 🟠 | Khai cơ chế thông báo; cân nhắc thêm thông báo cập nhật trong WebUI |
| 2.3.2 Người dùng tự cập nhật | OTA tự động theo `WRAPPER_TAG`; có `/api/version` | 🟢 | Liệt kê thành phần phần mềm (IXIT 6-SoftComp): nvr-wrapper, frigate (pin 0.16.3, không OTA), watchdog, nginx, mosquitto |
| 2.3.3 Mật mã an toàn cho cập nhật | `docker pull` từ GHCR qua **HTTPS/TLS** | 🟠 | Khai kênh TLS; nhưng xem 2.3.5 |
| 2.3.4 Chính sách/quy trình phát hành bản vá | Có kênh staging/stable (`WRAPPER_TAG`); chưa văn bản hóa quy trình | 🟠 | **Viết quy trình phát hành** (IXIT 8-UpdProc): thực thể, trách nhiệm, khung thời gian mục tiêu |
| 2.3.5 Kiểm tra xác thực + toàn vẹn từng bản cập nhật | **THIẾU:** OTA kéo image theo tag `latest` **không verify chữ ký/digest** (`UpdateManager.kt`); không chống hạ cấp | 🔴 | **QUAN TRỌNG:** thêm ký số/verify digest image (vd cosign / pin digest) + chống rollback. Lab sẽ thử tấn công downgrade (test 3.3.1.3.2) |
| 2.3.6 Công bố thời hạn hỗ trợ | **KHÔNG có** tuyên bố thời hạn hỗ trợ | 🔴 | **BẮT BUỘC công bố** thời hạn hỗ trợ cập nhật an toàn cho sản phẩm (khoảng thời gian hoặc ngày kết thúc) |
| 2.3.7 Tra cứu model qua nhãn/giao diện | `/api/version` trả version; release naming `AIView_NVR_V1.0.0.x` | 🟢 | **Lưu ý mismatch:** `VERSION`=1.0.0.3 nhưng fallback code = "1.2.0", `DeviceConstants` firmware "1.0.3.2" → **chốt 1 phiên bản nhất quán** |

### 2.4 — Lưu trữ tham số an toàn nhạy cảm (IXIT 10-SecParam)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.4.1 Lưu trữ an toàn tham số nhạy cảm | Mật khẩu camera/cloud: **AES-256-GCM** (`CryptoUtils.kt:30-47`); pattern bcrypt; Docker secrets cho gmail/token | 🟠 | **Lỗ hổng:** khóa AES lưu **plaintext** tại `data/.device_key` (`CryptoUtils.kt:11`); mật khẩu RTSP **cleartext** trong `config/config.yml`; TURN creds cleartext. → mô tả trung thực + cân nhắc bảo vệ khóa (secure storage/passphrase) |
| 2.4.2 Bảo vệ định danh cứng hóa dùng cho bảo mật | NVR chạy trên PC (không SoC bảo mật); N/A phần lớn | 🟢/N-A | Khai "không dùng định danh cứng hóa cho mục đích bảo mật" nếu đúng |
| 2.4.3 Bảo vệ tham số cứng hóa trong mã nguồn | **VI PHẠM:** `install.sh:57-59` chứa **GitHub PAT + Gmail app password thật** committed; salt cố định `NVR_SECRET_2026_AIVIEW` (`Security.kt:85`) | 🔴 | **Gỡ secret khỏi git ngay** (rotate token đã lộ); không hard-code CSP |
| 2.4.4 Khóa kiểm tra cập nhật + bảo vệ dịch vụ liên kết duy nhất per-device | Session secret random per-install (`Security.kt:28-44`); MQTT pass random per-install (`install.sh`) | 🟠 | Khai cơ chế sinh (RNG); gắn với 2.3.5 (chưa có khóa verify update) |

### 2.5 — Quản lý kênh giao tiếp an toàn (IXIT 11-ComMech, 14-SecMgmt)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.5.1 Mật mã an toàn thiết lập kênh | nginx **TLS 1.2 + 1.3**, `ssl_ciphers HIGH:!aNULL:!MD5` (`nginx.conf:46-47`); HSTS bật | 🟢 | Khai chính xác cipher suite dùng (IXIT 11-ComMech) |
| 2.5.2 Xác thực trước khi áp thay đổi liên quan an toàn | Mọi thay đổi cấu hình qua route trong `authenticate("auth-session")` | 🟢 | Ngoại lệ ARP/DHCP/DNS/ICMP/NTP không áp dụng |
| 2.5.3 Bảo mật tham số quan trọng khi truyền | HTTPS/TLS cho quản trị + dữ liệu | 🟠 | **Kênh FTP sự kiện không mã hóa** — nếu truyền dữ liệu cá nhân qua FTP thì vi phạm; khuyến nghị chỉ dùng HTTPS/Email-TLS hoặc thêm FTPS/SFTP |
| 2.5.4 Quy trình quản lý CSP | Chưa văn bản hóa | 🟠 | Viết quy trình vòng đời khóa (IXIT 14-SecMgmt): tạo/cấp/lưu/cập nhật/hủy |

### 2.6 — Phòng chống tấn công qua giao diện (IXIT 12/15-Intf, 13-SoftServ)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.6.1 Vô hiệu hóa giao diện không dùng | Frigate 5000 / go2rtc 1984 bind **localhost only**; MQTT auth+ACL bật | 🟠 | **Liệt kê đầy đủ cổng/dịch vụ + lý do** (IXIT 15): 443, 80, 1883, 9001, 8554, 8555/udp, 3000(host). Xác nhận Wi-Fi/dịch vụ không dùng đã tắt |
| 2.6.2 Hạn chế lộ thông tin trước khi xác thực | **VI PHẠM:** `/api/network-config` (lộ MAC/IP/gateway/DNS), `/api/storage-config`, `/api/version`, `/internal/dc/command` (dispatch lệnh) **KHÔNG auth** trên host-mode LAN; CORS `anyHost()` (`HTTP.kt:15`); nginx **không `server_tokens off`** | 🔴 | **Đặt các endpoint nhạy cảm sau auth**; giới hạn CORS; bật `server_tokens off`; giảm verbosity thông báo lỗi (`e.message` lộ ra client) |
| 2.6.3 Vô hiệu hóa debug vật lý bằng phần mềm | NVR trên PC — không debug UART/JTAG sản phẩm | 🟢/N-A | Khai "không có giao diện debug vật lý" nếu đúng |

### 2.7 — Bảo vệ dữ liệu người sử dụng (IXIT 16/21-PersData, 22-ExtSens)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.7.1 Mã hóa dữ liệu cá nhân trao đổi với dịch vụ liên kết | Cloud sync qua MQTT + WebRTC (TLS/DTLS/SRTP); heartbeat trạng thái camera | 🟢 | Xác nhận WebRTC media mã hóa; xử lý kênh FTP (2.5.3) |
| 2.7.2 Mô tả đầy đủ cảm biến bên ngoài | NVR không có cảm biến (dùng cảm biến của camera nguồn) | 🟠 | Mô tả trong HDSD: NVR nhận luồng từ camera; AI phân tích (khuôn mặt, biển số, người...) — nêu rõ dữ liệu suy diễn |

### 2.8 — Tự khôi phục sau sự cố (IXIT 18/23-ResMech)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.8.1 Cơ chế khôi phục khi mất mạng/điện | Watchdog `SelfHealingEngine` restart container (cap 3/h); `NetworkMonitor` | 🟢 | Khai chi tiết (IXIT 23-ResMech) |
| 2.8.2 Khôi phục hoạt động sau sự cố | `restart: unless-stopped/always` + healthcheck + `depends_on: service_healthy` | 🟢 | — |
| 2.8.3 Kết nối lại mạng theo trình tự, ổn định | MQTT `isAutomaticReconnect` + `maxReconnectDelay=300s`; go2rtc/Frigate RTSP reconnect | 🟢 | Nêu backoff tránh quá tải khi khôi phục đồng loạt |

### 2.9 — Xóa dữ liệu trên thiết bị (IXIT 20/25-DelFunc)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.9.1 Chức năng xóa dữ liệu người dùng | Có xóa camera (DB+YAML+go2rtc), xóa export/screenshot, retain 7 ngày (`config.yml`); `reset_lock.sh` chỉ xóa lock_config | 🟠 | **THIẾU factory-reset thật:** chưa có chức năng xóa **toàn bộ** dữ liệu người dùng (recordings + events + camera creds + khóa). Cần bổ sung + mô tả rõ dữ liệu nào bị xóa/giữ (IXIT 25) |

### 2.10 — Xác thực dữ liệu đầu vào (IXIT 24/27/28/29)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.10.1 Validate input từ UI/API/dịch vụ | `validateRtspUrl` chặn scheme lạ, loopback, link-local (`CameraRouting.kt:690-702`) | 🟠 | **Lỗ hổng:** SSRF chỉ chặn một phần (không chặn `[::1]`, `0.0.0.0`, IP dạng số/octal, DNS→internal); **path-traversal** ở `/api/exports/{filename}`, `save-screenshot` không sanitize `..` (`SystemRouting.kt:240-302`). → thêm sanitize + mô tả validate form/API (IXIT 29) |

### 2.11 — Bảo vệ dữ liệu trên thiết bị camera (đặc thù VN) (IXIT 16-Pers, 19/24-TelData)

| Y/c | Bằng chứng NVR | Trạng thái | Cần bổ sung |
|---|---|---|---|
| 2.11.1 Minh bạch thu thập/xử lý/lưu trữ dữ liệu cá nhân | AI events lưu label/bbox/snapshot/clip (`AIEvents` schema); recordings 7 ngày | 🔴 | **Viết bản mô tả đầy đủ** mục đích + cách thu thập/xử lý/lưu trữ (kể cả cloud/bên thứ ba) |
| 2.11.2 Chức năng thu nhận sự đồng ý | **KHÔNG có** | 🔴 | **YÊU CẦU MỚI:** bổ sung chức năng consent thu thập/xử lý dữ liệu cá nhân |
| 2.11.3 Chức năng thu hồi sự đồng ý | **KHÔNG có** | 🔴 | **YÊU CẦU MỚI:** bổ sung chức năng thu hồi đồng ý |
| 2.11.4 Mô tả telemetry | Watchdog gửi **Telegram alert** (metadata vận hành: health/OTA/disk + hostname) tới `TELEGRAM_CHAT_ID` | 🟠 | **Khai rõ:** telemetry Telegram (mục đích, dữ liệu, nơi nhận). Nếu không muốn tính là telemetry NSX thì làm rõ đây là alert vận hành cho chủ thiết bị, tùy chọn tắt |
| 2.11.5 Cấu hình lưu trữ dữ liệu tại Việt Nam | Lưu tại chỗ (đĩa host / mergerfs); cloud dùng TURN `103.149.87.79` (cần xác nhận đặt tại VN) | 🔴 | **Khai rõ:** đây là chức năng cho phép trỏ đích lưu trữ về máy chủ tại VN; nếu có cloud mặc định ra nước ngoài phải nêu + cho tắt/chuyển VN. Lab minh chứng "chỉ kết nối máy chủ tại VN" (test 3.11.5) |

---

## 3. Danh sách hành động trước khi nộp (ưu tiên)

### 🔴 P0 — Chặn nộp hồ sơ / lab bắt lỗi ngay
1. **Xác nhận phạm vi** với tổ chức đo kiểm/A05: NVR phần mềm có thuộc danh mục hợp quy không (§0).
2. **Bỏ / ép đổi tài khoản mặc định `admin/Bkav@2020`** đang bật sẵn (`Databases.kt:56-70`) — vi phạm 5.1 trực tiếp.
3. **Gỡ secret thật khỏi git** (`install.sh:57-59`: GitHub PAT + Gmail password) và **rotate** token đã lộ.
4. **Công bố chính sách tiếp nhận lỗ hổng** (URL công khai) — nhóm 2.2.
5. **Công bố thời hạn hỗ trợ cập nhật** — 2.3.6.
6. **Bổ sung consent + thu hồi consent + cấu hình lưu trữ tại VN** — 2.11.2/2.11.3/2.11.5 (yêu cầu đặc thù VN, không né được).

### 🟠 P1 — Cần cho hồ sơ đầy đủ / tránh fail test triển khai
7. **Thêm ký số/verify digest cho OTA** + chống downgrade — 2.3.5 (lab thử tấn công update).
8. **Đưa các endpoint nhạy cảm vào auth** (`network-config`, `storage-config`, export, `/internal/dc/command`); giới hạn CORS; `server_tokens off` — 2.6.2.
9. **Throttle OTP + rate-limit nginx**; đổi khóa pattern sang persistent/per-IP — 2.1.5.
10. **Chống path-traversal** ở export/screenshot; siết `validateRtspUrl` — 2.10.1.
11. **Factory-reset xóa toàn bộ dữ liệu người dùng + khóa** — 2.9.1.
12. **Bật cờ cookie `secure`** (`Security.kt:52`); cân nhắc bảo vệ khóa AES `data/.device_key` — 2.1.3/2.4.1.
13. **Chốt 1 phiên bản nhất quán** (VERSION vs code fallback vs DeviceConstants) — 2.3.7.
14. **Xử lý kênh FTP không mã hóa** (thêm FTPS/SFTP hoặc chặn dữ liệu cá nhân qua FTP) — 2.5.3/2.7.1.

### 📄 P2 — Tài liệu phải soạn để khai IXIT (không phải sửa code)
15. Bộ **IXIT** đầy đủ (mapping form → yêu cầu ở §2 và bảng dưới), **ICS**, **HDSD sử dụng cho người dùng cuối** (hiện chỉ có HDSD cài đặt), tài liệu mô tả cảm biến + dữ liệu cá nhân + telemetry, khai cipher suite/thuật toán mã hóa.

---

## 4. Danh mục form IXIT phải nộp (TS 103 701 — Phụ lục A)

| Form | Nội dung | Phục vụ | NVR có dữ liệu? |
|---|---|---|---|
| ICS | Tuyên bố phù hợp triển khai | toàn bộ | Cần soạn |
| IXIT 1-AuthMech | Cơ chế xác thực, cipher, chống brute-force | 2.1, 2.5.2 | 🟠 có bcrypt/pattern, thiếu chống brute-force đầy đủ |
| IXIT 2-UserInfo | HDSD, model, thời hạn hỗ trợ, chính sách lỗ hổng | 2.1.4, 2.2, 2.3.6/7, 2.7.2, 2.11 | 🔴 thiếu hỗ trợ+lỗ hổng |
| IXIT 4-Conf | Xác nhận quy trình cập nhật/quản lý | 2.3.4, 2.5.4 | 🟠 cần văn bản |
| IXIT 6-SoftComp | Danh sách thành phần phần mềm | 2.3.2 | 🟢 liệt kê container |
| IXIT 7-UpdMech | Cơ chế cập nhật + ký số | 2.3.1/2/3/5 | 🔴 thiếu ký số |
| IXIT 8-UpdProc | Thủ tục phát hành bản vá | 2.3.4 | 🟠 cần văn bản |
| IXIT 10-SecParam | Từng tham số nhạy cảm + bảo vệ | 2.4, 2.5.3 | 🟠 khóa AES lộ |
| IXIT 11-ComMech | Từng kênh + cipher + khôi phục | 2.5, 2.7.1, 2.8.3, 2.11.5 | 🟢 phần lớn |
| IXIT 13-SoftServ | Dịch vụ phần mềm, trạng thái khởi tạo | 2.5.2 | 🟠 cần liệt kê |
| IXIT 15-Intf | Mọi giao diện + debug + lộ thông tin | 2.6 | 🟠 cần liệt kê cổng |
| IXIT 21-PersData | Dữ liệu cá nhân + consent/thu hồi | 2.7.1, 2.11.1/2/3 | 🔴 thiếu consent |
| IXIT 22-ExtSens | Cảm biến bên ngoài | 2.7.2 | 🟠 mô tả AI |
| IXIT 23-ResMech | Cơ chế phục hồi | 2.8 | 🟢 |
| IXIT 24-TelData | Telemetry | 2.11.4 | 🟠 khai Telegram |
| IXIT 25-DelFunc | Chức năng xóa dữ liệu | 2.9 | 🟠 thiếu factory-reset |
| IXIT 27-UserIntf / 28-ExtAPI / 29-InpVal | Input UI/API + phương pháp validate | 2.10 | 🟠 cần mô tả |

---

## 5. Nguồn tham chiếu chính thức

- Toàn văn QCVN 11:2026/BCA (PDF 67 trang): https://cdn.thuvienphapluat.vn/phap-luat/2022-2/NDBT/140526/qcvn-11-2026-bca-pdf.pdf
- Thông tư 48/2026/TT-BCA: https://thuvienphapluat.vn/chinh-sach-phap-luat-moi/vn/ho-tro-phap-luat/chinh-sach-moi/112231/toan-van-thong-tu-48-2026-tt-bca-quy-chuan-ky-thuat-quoc-gia-ve-thiet-bi-camera-giam-sat-su-dung-giao-thuc-internet
- QCVN 135:2024/BTTTT (tiền thân): https://thuvienphapluat.vn/TCVN/Dien-dien-tu/QCVN-135-2024-BTTTT-Camera-giam-sat-giao-thuc-Internet-yeu-cau-an-toan-thong-tin-921429.aspx
- Phạm vi/hồ sơ hợp quy (ExtendMax): https://extendmax.vn/qcvn-135-2024-btttt-an-toan-thong-tin-cho-camera
- Tin Bộ Công an ban hành (tcvn.gov.vn): https://tcvn.gov.vn/bộ-cong-an-ban-hanh-qcvn-112026-bca-ve-thiet-bi-camera-giam-sat-su-dung-giao-thuc-internet/14/05/2026/
- Chuẩn viện dẫn: ETSI EN 303 645 v2.1.1, ETSI TS 103 701 v1.1.1.

> Tài liệu này do rà soát tự động mã nguồn + đọc toàn văn QCVN 11 lập ra làm **bản nháp kiểm thử nội bộ**. Trước khi nộp cần đội kỹ thuật Bkav xác nhận các mục 🟠/🔴 và điền chi tiết cơ chế nội tại.
