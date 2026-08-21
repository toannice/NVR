# AIView NVR — Thông số kỹ thuật (Datasheet)

**Model:** AIView NVR · **Hãng:** Bkav AI · **Phiên bản phần mềm:** V1.0.0.3 (2026-07-30)
**Kiểu sản phẩm:** NVR phần mềm (software-defined NVR) chạy trên máy chủ x86, đóng gói Docker.

> **Lưu ý đọc bảng:** sản phẩm này là **phần mềm NVR + máy chủ x86 tiêu chuẩn**, không phải đầu ghi
> nhúng dạng hộp (appliance) có cổng HDMI/BNC/PoE/RS-485 trên mặt máy. Các mục thuộc nhóm phần cứng
> đầu ghi truyền thống được ghi rõ **"Không áp dụng"** thay vì bỏ trống, để không gây hiểu nhầm khi
> so bảng với Hikvision/Dahua/Hanwha. Các con số có chữ **(ước lượng)** là tính toán lý thuyết, chưa
> đo nghiệm thu; các con số còn lại lấy trực tiếp từ cấu hình/mã nguồn/phần cứng đang chạy.

---

## 1. Hệ thống & Hiệu năng lõi (System & Performance)

| Hạng mục | Thông số |
|---|---|
| Kiến trúc | NVR phần mềm, multi-container Docker: `mosquitto` (MQTT) → `frigate` 0.16.3 (engine ghi hình/phát hiện) → `nvr-wrapper` (ứng dụng Kotlin/Ktor + React) + `nvr-watchdog` (tự phục hồi & OTA) + `nginx` (HTTPS gateway) |
| Hệ điều hành | Ubuntu 24.04 LTS (kernel 6.8), Docker Engine 28.x / Compose v2 |
| Số kênh hỗ trợ | **Không giới hạn cứng trong phần mềm** — số kênh thực tế do CPU/băng thông/ổ đĩa quyết định. Trên cấu hình tham chiếu mục 8: **16–24 kênh** camera LAN 2 MP (main ghi hình + sub phát hiện), hoặc **6–8 kênh** 4K *(ước lượng)* |
| Chuẩn nén video — ghi hình | **H.265 và H.264**, ghi nguyên luồng gốc (stream-copy, không nén lại). Camera bật H.265+/H.264+ vẫn ghi được — đó là cơ chế điều tiết bitrate **của camera**, luồng ra vẫn hợp chuẩn H.265/H.264. MJPEG: chỉ nhận, không tối ưu |
| Chuẩn nén video — xem trên trình duyệt | **H.264: mọi trình duyệt.** **H.265: tuỳ khả năng giải mã của trình duyệt/hệ điều hành** — có cơ chế tự lùi sang luồng phụ hoặc chuyển mã sang H.264, **hiện đã áp dụng cho kênh xem từ xa** (`SignalingManager.negotiateLiveSdp`); xem trực tiếp và xem lại trong LAN đang truyền thẳng, chưa có fallback |
| Chuẩn nén âm thanh | Theo luồng RTSP của camera (AAC / G.711) — truyền tải nguyên trạng, **không có đàm thoại 2 chiều** |
| Độ phân giải ghi hình tối đa | Theo camera nguồn, đã chạy thực tế tới **4K (8 MP)**; không giới hạn phần mềm |
| Cơ chế ghi hình | Ghi luồng gốc (stream copy), không nén lại → không suy giảm chất lượng, tải CPU thấp |
| Băng thông vào (Incoming) | Giới hạn bởi NIC + đĩa. Cấu hình tham chiếu: **~90 Mbps** liên tục ghi ổn định *(ước lượng, NIC hiện đang đàm phán ở 100 Mbps — xem mục 8)* |
| Băng thông ra (Outgoing) | Xem trực tiếp qua LAN/HTTPS + xem từ xa qua WebRTC (cloud). Không giới hạn phiên trong phần mềm |
| Tăng tốc phần cứng | Intel Quick Sync (`preset-intel-qsv-h264`) khi máy có iGPU Intel; tự dò `/dev/dri/renderD128` lúc cài, tự tắt nếu không có |

---

## 2. Hiển thị & Xem lại (Video Output & Playback)

| Hạng mục | Thông số |
|---|---|
| Giao diện vận hành | **Web UI (React)** qua HTTPS — không cần cài phần mềm client. Trình duyệt Chrome/Edge/Firefox; hỗ trợ desktop và mobile |
| Cổng xuất hình cục bộ (HDMI/VGA/BNC) | **Không áp dụng** — máy chủ chạy headless, xem hình qua trình duyệt trên bất kỳ máy nào trong mạng |
| Chế độ chia màn hình | **1 / 4 / 9 / 16 / 25** ô, cộng các bố cục không đối xứng **1+3, 1+4, 1+5, 1+6, 2+3, 2+4, 2+5** (ô lớn + dải ô nhỏ) |
| Chọn kênh hiển thị | Bật/tắt từng camera trên lưới, phóng to 1 kênh, toàn màn hình |
| Kỹ thuật truyền hình trực tiếp | MSE/fMP4 qua go2rtc cho LAN (độ trễ thấp, hỗ trợ H.265); WebRTC cho xem từ xa qua cloud |
| Phát lại | Timeline 24 giờ, kéo/thu phóng dòng thời gian, chọn ngày, chọn camera |
| Tốc độ phát lại | **0.5x / 1x / 2x / 4x**, tạm dừng / tiếp tục |
| Phóng to số khi phát lại | Digital zoom tới **8x**, kéo-thả di chuyển khung nhìn |
| Xuất / tải video | Cắt đoạn theo thời gian → xuất **MP4**, quản lý & tải trong mục "Tải xuống", xoá file đã xuất |
| Phát lại từ xa (cloud) | Có — qua kênh WebRTC riêng (dc-bridge), hỗ trợ tua/tạm dừng/đổi tỉ lệ từ dashboard cloud |

---

## 3. Lưu trữ & Quản lý đĩa (Storage)

| Hạng mục | Thông số |
|---|---|
| Kiểu lưu trữ | Ghi trực tiếp lên hệ thống file của máy chủ (`media/`), gộp nhiều ổ bằng **mergerfs pool** — thêm ổ là tăng dung lượng, không phải format lại |
| Số ổ cứng | Theo số cổng SATA của bo mạch máy chủ (cấu hình tham chiếu: 2 ổ, pool 1.2 TB) |
| Dung lượng tối đa mỗi ổ | Không giới hạn phần mềm (giới hạn của ext4/XFS) |
| RAID | **Không có RAID phần cứng/phần mềm trong bản này** — pool dạng JBOD gộp không gian. Muốn dự phòng đĩa: dựng RAID/ZFS ở lớp hệ điều hành trước khi cài |
| Thay nóng (hot-swap) | Theo phần cứng máy chủ; **phần mềm không quản lý hot-swap** |
| Chính sách lưu | Mặc định **giữ 7 ngày**, chế độ `motion` (chỉ giữ đoạn có chuyển động) hoặc `all` (giữ liên tục) — **đặt riêng từng camera** |
| Lịch ghi hình | Bộ lập lịch theo khung giờ/camera (`RecordingScheduler`) — bật/tắt ghi theo lịch |
| Chống đầy đĩa | Tự động xoá bản ghi cũ nhất theo chính sách lưu; watchdog giám sát dung lượng |
| Lưu trữ mở rộng eSATA / SAS / N+1 / ANR | **Không áp dụng** |

---

## 4. Kết nối mạng & Tích hợp (Networking & Integration)

| Hạng mục | Thông số |
|---|---|
| Cổng mạng | Theo máy chủ (cấu hình tham chiếu: 1× RJ45 Gigabit). Gộp cổng/failover: cấu hình ở lớp OS (bonding), không quản lý trong app |
| Cổng PoE | **Không áp dụng** — camera cấp nguồn qua switch PoE bên ngoài |
| Cổng dịch vụ | **443/TCP** HTTPS (Web UI + API) · **9000/TCP** HTTP (chỉ phục vụ `/onvif/`, còn lại 301 → HTTPS) · **9001/TCP** MQTT (LAN, có xác thực) · **8554/TCP** RTSP restream · **8555/UDP** WebRTC · **5000, 1984** chỉ loopback (không mở ra LAN) |
| Giao thức | IPv4, TCP/IP, HTTP/HTTPS, **RTSP (TCP)**, **WebRTC (ICE/STUN/TURN)**, **MQTT (có auth + ACL theo client)**, WS-Discovery, DHCP, DNS, NTP |
| ONVIF — vai trò client | Dò tìm camera (WS-Discovery), lấy profile/stream URI, thêm camera qua ONVIF, **điều khiển PTZ** (ContinuousMove/Stop, Pan-Tilt-Zoom, tự dò khả năng PTZ) |
| ONVIF — vai trò server | Phát hiện & tích hợp NVR: `GetDeviceInformation`, `GetCapabilities`, `GetScopes`, `GetServices`, `GetSystemDateAndTime`, Media/Stream URI (kiểu `NetworkVideoTransmitter`) — dùng cho AIView Box lấy luồng camera từ NVR |
| Mức tuân thủ ONVIF | **Tập hợp lệnh cốt lõi tương thích Profile S** (khám phá, media, streaming, PTZ). **Chưa có chứng nhận ONVIF chính thức** |
| API mở | REST API HTTPS đầy đủ (camera CRUD, PTZ, phát lại, xuất video, sự kiện, hệ thống) + hợp đồng tích hợp AIView Box v1.1 (`/getintegrationinfo`, `/getprofilecamera`, `/getcamerastatus`) |
| Camera ở xa (multi-site) | **Edge Gateway** (Raspberry Pi) + overlay **Nebula (mTLS, lighthouse tự vận hành)** — proxy L4 cho camera ở chi nhánh về NVR trung tâm; quy trình enroll → cấp phép → adopt trên Web UI, gateway **không lưu mật khẩu camera** |
| Kết nối cloud | Kênh MQTT (quản lý kênh, heartbeat) + WebRTC LIVE (xem trực tiếp) + WebRTC DATA (thông tin thiết bị, phát lại) tới hệ thống AIView cloud |

---

## 5. Bảo mật (Security)

| Hạng mục | Thông số |
|---|---|
| Truy cập Web | Bắt buộc **HTTPS/TLS 1.2 – 1.3**; HTTP tự chuyển hướng 301. Chứng chỉ tự ký sinh lúc cài (thay bằng chứng chỉ CA nếu khách yêu cầu) |
| Xác thực người dùng | **Mật khẩu hình (pattern lock)**, băm **bcrypt cost 12**; phiên đăng nhập bằng **cookie ký HMAC** (không giả mạo được) |
| Khôi phục mật khẩu | **Hoàn toàn offline, không cần Internet/email**: mã khôi phục 20 ký tự Base32 (~98 bit, bcrypt, dùng một lần) + 3 câu hỏi bảo mật (bcrypt từng đáp án, chuẩn hoá không dấu). Đường cuối: reset tại máy (cần có mặt vật lý) |
| Chống dò mật khẩu | Giới hạn theo IP, tăng dần: pattern 5 lần sai → 60s → 5 phút → 15 phút → 1 giờ; mã khôi phục 5 lần → 30 phút; câu hỏi 5 lần → 1 giờ. **Bucket độc lập** — tấn công đường khôi phục không khoá được chủ máy |
| Bảo vệ dữ liệu nhạy cảm | Mật khẩu camera **mã hoá khi lưu** trong CSDL; không có secret trong bundle giao khách (`verify-bundle.sh` quét trước khi gửi) |
| Bảo mật MQTT nội bộ | Bắt buộc xác thực + **ACL riêng theo từng client**, không cho anonymous |
| Chống SSRF | Kiểm tra URL RTSP: chặn scheme lạ, loopback (`127.x`), link-local `169.254.x` (metadata cloud), `host.docker.internal` |
| Giảm bề mặt tấn công | Ứng dụng bind **127.0.0.1** (chỉ vào qua nginx+TLS); UI gốc Frigate và go2rtc API **không mở ra LAN** |
| Cập nhật phần mềm | **Bản thương mại giao khách: cập nhật từ xa TẮT mặc định** — máy khách không lưu thông tin đăng nhập kho phần mềm. Nâng cấp bằng bộ cài mới, kỹ thuật viên thực hiện tại chỗ. Cập nhật từ xa (OTA có kiểm soát kênh, tự rollback) là **tuỳ chọn**, chỉ bật khi khách yêu cầu và máy có Internet |
| Nhật ký & cảnh báo | Log xoay vòng mọi container (50 MB × 3); cảnh báo sự cố qua Telegram (watchdog) |
| TPM 2.0 / Secure Element / FIPS 140-2 / Signed Firmware | **Không có** trong bản này |
| NDAA / TAA Compliant | **Không tuyên bố** — phụ thuộc phần cứng máy chủ và camera do khách chọn |

---

## 6. Trí tuệ nhân tạo & Phân tích video (AI & Analytics)

| Hạng mục | Thông số |
|---|---|
| Phát hiện chuyển động | Có — theo camera, dùng để lọc đoạn ghi (`retain mode: motion`) |
| Phát hiện & phân loại đối tượng | Có — engine Frigate, phân loại **người / phương tiện / vật thể** theo nhãn mô hình; chạy trên CPU (mặc định), có thể gắn accelerator |
| Vùng phát hiện (Zones) | Vẽ vùng đa giác trên khung hình theo camera; chỉ báo động trong vùng quan tâm |
| Bật/tắt phát hiện | Theo từng camera và công tắc tổng toàn hệ thống |
| Sự kiện AI từ thiết bị ngoài | **Nhận metadata sự kiện từ AIView Box** (`POST /api/pushevent`) → NVR tự **cắt clip −5s/+5s** quanh thời điểm sự kiện, trích ảnh khung hình, hiển thị ở tab **"Sự kiện AI"** |
| Nhận diện khuôn mặt / biển số (ANPR) | **Không chạy trên NVR** — do thiết bị AI chuyên dụng (AIView Box) hoặc camera AI đảm nhiệm, NVR lưu trữ và hiển thị kết quả |
| Xem lại theo sự kiện | Nhảy tới thời điểm sự kiện trên timeline, xem clip đã cắt, tải về |

---

## 7. Cổng giao tiếp & Ngoại vi (External Interfaces)

| Hạng mục | Thông số |
|---|---|
| USB | Theo máy chủ (USB 2.0/3.0) — dùng sao lưu thủ công tại máy; **không cần chuột/màn hình để vận hành** (vận hành qua Web UI) |
| Cổng báo động Alarm In/Out | **Không áp dụng** (không có I/O rời). Sự kiện đi qua MQTT / REST API để tích hợp hệ thống khác |
| Audio I/O (RCA), đàm thoại 2 chiều | **Không áp dụng** |
| RS-485 (bàn PTZ) / RS-232 (console) | **Không áp dụng** — PTZ điều khiển qua **ONVIF trên mạng** từ Web UI (d-pad pan/tilt, zoom, chọn kiểu PTZ: Tự động / Toàn phần / Chỉ Zoom) |

---

## 8. Cơ điện, môi trường & cấu hình tham chiếu (General / Hardware)

Phần mềm chạy trên máy chủ x86-64 tiêu chuẩn. Dưới đây là **cấu hình tham chiếu đang vận hành thực tế**:

| Hạng mục | Cấu hình tham chiếu (đang chạy) | Yêu cầu tối thiểu |
|---|---|---|
| CPU | Intel Core i5-9400, 6 nhân / 6 luồng, 2.9–4.1 GHz | 4 nhân x86-64, có AVX |
| Đồ hoạ / giải mã | Intel UHD Graphics 630 (Quick Sync, `/dev/dri/renderD128`) | Tuỳ chọn — không có iGPU vẫn chạy (tự tắt hwaccel) |
| RAM | 24 GB | 8 GB |
| Ổ hệ thống | SSD 240 GB (Kingston SA400) | SSD 120 GB |
| Ổ ghi hình | HDD 1 TB (WD) + pool mergerfs tổng **1.2 TB** | 1 TB HDD chuyên dụng |
| Mạng | 1× RJ45 (đang đàm phán **100 Mbps** — khuyến nghị nâng lên Gigabit để tăng số kênh) | 1× RJ45 1 Gbps |
| Hệ điều hành | Ubuntu 24.04.4 LTS, kernel 6.8 | Ubuntu 22.04/24.04 LTS |
| Nguồn điện / nguồn kép | Theo máy chủ; **nguồn kép dự phòng (RPS): không áp dụng** ở cấu hình tham chiếu | — |
| Công suất tiêu thụ | Theo máy chủ (~65 W TDP CPU, chưa gồm HDD) | — |
| Điều kiện hoạt động | Theo máy chủ, khuyến nghị **10–35 °C, độ ẩm 20–80 % không ngưng tụ**, phòng máy có điều hoà | — |
| Kích thước / kiểu dáng | Theo máy chủ (desktop hoặc rack 1U–4U tuỳ đơn hàng) | — |
| Chứng nhận (CE, FCC, UL, RoHS…) | Theo phần cứng máy chủ do nhà cung cấp cấp; **phần mềm chưa có chứng nhận riêng** | — |

**Ước lượng sức chứa theo băng thông** *(chưa nghiệm thu bằng đo tải)* — mỗi camera kéo 2 luồng (main ghi hình + sub phát hiện):

| Cấu hình camera | Bitrate/camera | Số kênh với NIC 100 Mbps | Số kênh với NIC 1 Gbps |
|---|---|---|---|
| 4K main + FullHD sub (H.265) | ~12–16 Mbps | ~4–5 kênh | giới hạn bởi CPU/đĩa trước |
| FullHD main + 720p sub (H.265) | ~4–5.5 Mbps | ~12–16 kênh | ~24 kênh (CPU-bound) |

> Với camera ở **chi nhánh xa qua gateway**, nút thắt là **băng thông download của NVR trung tâm** (một ống chung cho toàn hệ thống) — thêm gateway không mở rộng nút thắt này.

---

## 9. Vận hành & Độ tin cậy (Reliability & Serviceability)

| Hạng mục | Thông số |
|---|---|
| Tự phục hồi | Dịch vụ `nvr-watchdog` giám sát sức khoẻ container, tự khởi động lại thành phần lỗi — **hoạt động cả khi máy không có Internet** |
| Cập nhật phần mềm | **Mặc định: cập nhật thủ công tại chỗ bằng bộ cài mới.** Cập nhật từ xa (OTA theo lịch 03:00, 3 kênh, tự rollback) là tuỳ chọn — cần Internet và thông tin đăng nhập kho phần mềm, **không kích hoạt trên máy giao khách** |
| Cảnh báo | Telegram (watchdog) khi container chết, đĩa đầy — tuỳ chọn, cần cấu hình bot và Internet |
| Bảo vệ IP thay đổi | `nvr-ip-guard` (systemd timer 2 phút) tự phát hiện IP LAN đổi và cấu hình lại luồng — tránh mất live view khi DHCP đổi IP |
| Triển khai | Trực tuyến: `sudo ./install.sh` (kéo image từ registry). Ngoại tuyến: bundle `deploy-offline.sh` (~1.2 GB, không cần Internet tại hiện trường) |
| Sao lưu cấu hình | Cấu hình + CSDL nằm trong thư mục dự án, sao lưu bằng copy thư mục |

---

## 10. Bảng tóm tắt (Summary)

| Danh mục | Thông số tiêu biểu |
|---|---|
| **Kiểu sản phẩm** | NVR phần mềm trên máy chủ x86, Ubuntu 24.04 LTS + Docker |
| **Kênh & băng thông** | Không giới hạn cứng; 16–24 kênh 2 MP hoặc 6–8 kênh 4K trên cấu hình tham chiếu *(ước lượng)* |
| **Ghi hình & nén** | Ghi H.265/H.264 nguyên luồng gốc (không nén lại), tới 4K; xem trên trình duyệt tự chuyển sang H.264 khi cần; Intel Quick Sync khi có iGPU |
| **Hiển thị** | Web UI HTTPS, chia màn 1/4/9/16/25 + 7 bố cục không đối xứng; phát lại 0.5x–4x, zoom 8x, xuất MP4 |
| **Lưu trữ** | mergerfs pool (1.2 TB ở cấu hình tham chiếu), giữ 7 ngày, chế độ motion/all theo camera, có lịch ghi |
| **Mạng & tích hợp** | HTTPS 443, MQTT 9001, RTSP 8554, WebRTC 8555/UDP; ONVIF client (PTZ) + ONVIF server; REST API mở |
| **Bảo mật** | TLS 1.2/1.3, bcrypt cost 12, cookie ký HMAC, khôi phục offline, rate-limit luỹ tiến theo IP, MQTT auth+ACL, chống SSRF |
| **AI** | Phát hiện chuyển động + phân loại người/phương tiện, vùng phát hiện; nhận sự kiện AI từ AIView Box và tự cắt clip |
| **Đa điểm** | Edge Gateway (Raspberry Pi) qua overlay Nebula mTLS — camera chi nhánh về NVR trung tâm |
| **Vận hành** | Watchdog tự phục hồi (chạy offline), cập nhật thủ công tại chỗ (OTA là tuỳ chọn), cài đặt online/offline |

---

## Phụ lục — Các mục KHÔNG có trong bản này

Liệt kê minh bạch để dùng khi so bảng thầu:

- Cổng xuất hình cục bộ HDMI/VGA/BNC và bộ giải mã hiển thị tại chỗ
- Cổng PoE tích hợp, Alarm I/O, Audio I/O, RS-485/RS-232
- RAID phần cứng/phần mềm, hot-swap có quản lý, eSATA/SAS mở rộng, N+1 hot spare, ANR
- Nguồn kép dự phòng (RPS)
- TPM 2.0 / Secure Element / FIPS 140-2 / firmware ký số
- Chứng nhận NDAA / TAA / chứng nhận ONVIF chính thức
- Nhận diện khuôn mặt & biển số chạy trực tiếp trên NVR (do thiết bị AI ngoài đảm nhiệm)
- Đàm thoại 2 chiều

*Tài liệu lập ngày 2026-07-30 từ cấu hình và mã nguồn phiên bản V1.0.0.3 đang vận hành.*
