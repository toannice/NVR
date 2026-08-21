# AIView NVR — Thông số kỹ thuật phần mềm

**Bkav AI · Phiên bản V1.0.0.3 · 30/07/2026**

> **Quy ước đọc:** ✅ = có, đang chạy thực tế · ⚙️ = nền tảng hỗ trợ nhưng **chưa kích hoạt/chưa kiểm chứng** trên sản phẩm · ❌ = **không có** trong bản này.
> Các mục ❌ và ⚙️ được ghi rõ thay vì bỏ trống, để không bị bắt lỗi khi nghiệm thu hoặc chấm thầu.

---

## 1. Kiến trúc & Nền tảng (Architecture & Deployment)

| Hạng mục | Thực tế sản phẩm |
|---|---|
| Môi trường vận hành | ✅ **Containerization** — Docker + Docker Compose, đa dịch vụ. Chạy trên bare-metal hoặc máy ảo Linux (Ubuntu 22.04/24.04 LTS). Windows Server: ❌ không hỗ trợ |
| Mô hình triển khai | ✅ **Đơn điểm (Standalone)** · ✅ **Phân tán đa điểm (Distributed Multi-site)** — camera chi nhánh về NVR trung tâm qua Edge Gateway + overlay Nebula (mTLS). Cluster Master/Worker: ❌ chưa hỗ trợ |
| Tối ưu tài nguyên | ✅ Ghi hình **stream-copy, không transcode** → CPU thấp, không giảm chất lượng · ✅ **MergerFS pool** cho lưu trữ, ghi thẳng xuống đĩa · ✅ Tăng tốc phần cứng Intel Quick Sync (tự dò iGPU, không có vẫn chạy) |
| Kéo dài tuổi thọ ổ đĩa | ✅ Không transcode, không ghi tạm nhiều tầng; log xoay vòng có giới hạn (50 MB × 3/dịch vụ). Ghi đệm RAM trước khi xuống đĩa: ❌ chưa có |

## 2. Tương thích Stream & Mã hoá (Core Streaming & Codecs)

| Hạng mục | Thực tế sản phẩm |
|---|---|
| Giao thức đầu vào | ✅ **ONVIF** (dò tìm WS-Discovery, lấy profile, PTZ) · ✅ **RTSP over TCP** · ✅ **HTTP/HTTPS REST API**. **RTMP: ❌ không hỗ trợ** |
| Mức ONVIF | ✅ Tập lệnh cốt lõi **tương thích Profile S** (Device, Media, Streaming, PTZ) ở cả vai trò client và server. **Profile G/T: ❌ chưa hỗ trợ. Chưa có chứng nhận ONVIF chính thức** |
| Chuẩn nén video — **ghi hình** | ✅ **H.265 và H.264**, ghi nguyên luồng gốc (stream-copy, không nén lại). Camera bật chế độ H.265+/H.264+ vẫn ghi được vì luồng ra là H.265/H.264 hợp chuẩn — **đây là tính năng của camera, không phải của NVR**. MJPEG: ⚙️ nhận được, không tối ưu |
| Chuẩn nén video — **xem trên trình duyệt** | ✅ **H.264: mọi trình duyệt, mọi nền tảng.** ⚙️ **H.265: tuỳ khả năng giải mã của trình duyệt và hệ điều hành** — hệ thống tự chuyển sang H.264 khi cần. Cơ chế tự chuyển **hiện áp dụng cho kênh xem từ xa**; xem trực tiếp và xem lại trong mạng nội bộ đang ở chế độ truyền thẳng *(đang rà soát, xem mục Lộ trình)* |
| Giao thức phát ra trình duyệt | ✅ **MSE / fMP4** (xem trực tiếp trong LAN, độ trễ thấp, hỗ trợ H.265) · ✅ **WebRTC** (xem trực tiếp & phát lại từ xa) · ✅ **HLS** (dùng cho phát lại theo dòng thời gian). **WHEP: ❌ chưa hỗ trợ** |
| Không cần plugin | ✅ Xem trực tiếp và xem lại chạy thẳng trên Chrome/Edge/Firefox, desktop và mobile, không cài phần mềm client |
| Độ trễ | ⚙️ Độ trễ thấp trong LAN (MSE), **chưa có số đo nghiệm thu** — không tuyên bố con số cụ thể |

## 3. Tích hợp AI Edge & Phân tích hình ảnh (Edge AI Integration)

| Hạng mục | Thực tế sản phẩm |
|---|---|
| Phân tích chạy trên NVR | ✅ Phát hiện chuyển động · ✅ **Phân loại đối tượng người / phương tiện** · ✅ **Vùng phát hiện (Zone detection)** vẽ theo từng camera |
| Phần cứng tăng tốc AI | ⚙️ Nền tảng (Frigate 0.16.3) hỗ trợ **Intel OpenVINO, Google Coral TPU, NVIDIA TensorRT**, nhưng **sản phẩm hiện chạy suy luận trên CPU**, chưa cấu hình sẵn accelerator. Bổ sung được theo yêu cầu dự án |
| Nhận diện khuôn mặt / biển số | ❌ **Không chạy trên NVR.** Do thiết bị chuyên dụng **AIView Box** đảm nhiệm; NVR nhận kết quả, tự **cắt clip −5s/+5s**, trích ảnh và hiển thị ở mục "Sự kiện AI" |
| Line crossing | ❌ Chưa có (hiện chỉ có phát hiện theo vùng) |
| Pipeline tối ưu | ✅ Tách luồng: luồng phụ độ phân giải thấp dành cho phân tích, luồng chính chỉ để ghi hình → giảm mạnh tải AI. **Frame-batching / Dynamic FPS scaling: ❌ chưa có** |

## 4. Quản lý Lưu trữ & Dữ liệu (Storage & Retention Management)

| Hạng mục | Thực tế sản phẩm |
|---|---|
| Cơ chế lưu giữ | ✅ **Theo sự kiện** (chỉ giữ đoạn có chuyển động) hoặc **liên tục 24/7** — cấu hình **riêng cho từng camera**. Mặc định giữ 7 ngày, điều chỉnh được |
| Lịch ghi hình | ✅ Bật/tắt ghi theo khung giờ và theo camera |
| Chống đầy đĩa | ✅ Tự xoá bản ghi cũ nhất theo chính sách lưu, không dừng ghi khi hết chỗ |
| Tích hợp lưu trữ | ✅ **Local Storage + MergerFS pool** (thêm ổ là cộng dồn dung lượng, không cài lại). **NAS/SAN (NFS/SMB): ⚙️** mount ở lớp hệ điều hành thì dùng được, **chưa có giao diện quản lý trong ứng dụng**. **Cloud Storage S3: ❌ chưa hỗ trợ** |
| ANR (đồng bộ lại thẻ nhớ camera khi mất mạng) | ❌ Không hỗ trợ |
| Đệm RAM trước khi ghi đĩa | ❌ Không hỗ trợ |
| RAID | ❌ Không có RAID trong ứng dụng. Cần dự phòng đĩa: dựng RAID/ZFS ở lớp hệ điều hành trước khi cài |

## 5. An ninh mạng & Tuân thủ (Security & Compliance)

| Hạng mục | Thực tế sản phẩm |
|---|---|
| Mã hoá đường truyền | ✅ **HTTPS bắt buộc, TLS 1.2/1.3**; HTTP tự chuyển hướng 301. Kênh đa điểm mã hoá **mTLS (Nebula)** |
| Xác thực người dùng | ✅ Mật khẩu hình, băm **bcrypt cost 12**; phiên bằng cookie **ký HMAC** (không giả mạo được) |
| Chống dò mật khẩu | ✅ Giới hạn theo IP luỹ tiến: 60s → 5 phút → 15 phút → 1 giờ; đường khôi phục có bucket riêng |
| Khôi phục truy cập | ✅ **Hoàn toàn offline** — mã khôi phục ~98 bit dùng một lần + 3 câu hỏi bảo mật. Không cần Internet, không cần email |
| Bảo vệ dữ liệu tại chỗ | ✅ Mật khẩu camera mã hoá khi lưu. **Mã hoá toàn bộ dữ liệu ghi hình (AES-256): ❌ chưa có** — cần thì mã hoá ổ đĩa ở lớp hệ điều hành |
| Giảm bề mặt tấn công | ✅ Ứng dụng bind loopback, chỉ vào qua nginx+TLS; API engine nội bộ không mở ra LAN · ✅ Chặn SSRF trên URL RTSP · ✅ MQTT nội bộ bắt buộc xác thực + ACL theo client |
| **RBAC (phân quyền nhiều người dùng)** | ❌ **Chưa có** — bản này dùng một mật khẩu quản trị chung, không phân vai trò |
| **2FA (xác thực 2 bước)** | ❌ Chưa có |
| **Audit Trail (nhật ký thao tác người dùng)** | ❌ Chưa có. Hiện chỉ có log kỹ thuật của hệ thống |
| **NDAA Compliant** | ❌ **Không tuyên bố.** Phần mềm không phụ thuộc chip bị cấm, nhưng tuân thủ NDAA phụ thuộc **phần cứng máy chủ và camera do khách chọn** — chỉ khẳng định được khi chốt cấu hình và có văn bản của nhà cung cấp phần cứng |
| FIPS 140-2 / TPM 2.0 / Secure Element / firmware ký số | ❌ Không có |

## 6. Khả năng mở rộng & Tích hợp (Integration & API)

| Hạng mục | Thực tế sản phẩm |
|---|---|
| API mở | ✅ **RESTful API** đầy đủ qua HTTPS (camera, PTZ, phát lại, xuất video, sự kiện, hệ thống) · ✅ **WebSocket API** (`/ws/mqtt`, luồng sự kiện) · ✅ Hợp đồng tích hợp **AIView Box v1.1** |
| Nhận sự kiện từ bên thứ ba | ✅ Endpoint nhận sự kiện AI đẩy vào (`POST /api/pushevent`) → tự cắt clip và lập hồ sơ sự kiện |
| Webhook đẩy ra ngoài | ⚙️ **Chưa có webhook cấu hình được qua giao diện.** Hiện có kênh cảnh báo **Telegram** (tuỳ chọn) và bus **MQTT** để hệ thống khác đăng ký nhận sự kiện. Tích hợp báo cháy / kiểm soát ra vào / Zalo Bot: làm được qua MQTT + REST, **cần phát triển thêm theo dự án** |
| Quản lý tập trung | ✅ Giao diện **Web-based (React + Vite)**, quản lý toàn bộ camera và chi nhánh trên một màn hình |
| Kết nối an toàn không cần mở NAT | ✅ **Overlay Nebula** (mesh mã hoá, lighthouse tự vận hành, không phụ thuộc máy chủ bên thứ ba) · ⚙️ Tailscale/WireGuard dùng được ở lớp hệ điều hành nhưng **không phải thành phần sản phẩm** |
| Vận hành từ xa | ✅ Xem trực tiếp và phát lại từ xa qua kênh WebRTC của hệ thống AIView Cloud |

---

## Vận hành & bảo trì

| Hạng mục | Thực tế sản phẩm |
|---|---|
| Tự phục hồi | ✅ Dịch vụ giám sát nội bộ theo dõi sức khoẻ từng thành phần, tự khởi động lại thành phần lỗi — **chạy được cả khi máy không có Internet** |
| Cập nhật phần mềm | ✅ **Mặc định trên máy giao khách: cập nhật thủ công tại chỗ** bằng bộ cài mới. Máy khách **không lưu thông tin đăng nhập kho phần mềm** và không tự kết nối ra ngoài để tải bản mới — đây là lựa chọn có chủ đích để giảm rủi ro |
| Cập nhật từ xa (OTA) | ⚙️ **Tuỳ chọn, mặc định TẮT.** Có sẵn cơ chế OTA theo lịch, 3 kênh phát hành và **tự quay về bản cũ khi bản mới lỗi**; chỉ bật khi khách yêu cầu và máy có Internet |
| Cảnh báo sự cố | ⚙️ Qua Telegram (container lỗi, đĩa đầy) — tuỳ chọn, cần Internet và cấu hình khi triển khai |
| Triển khai | ✅ **Bộ cài ngoại tuyến ~1,2 GB** chạy trọn vẹn tại chỗ, **không cần Internet ở hiện trường** · ✅ Bộ cài trực tuyến cho môi trường có mạng |
| Chống đổi IP | ✅ Dịch vụ nền tự phát hiện máy đổi địa chỉ IP và cấu hình lại luồng, tránh mất hình |

---

## Lộ trình phát triển (đã xác minh 30/07/2026)

**H.265 trên đường xem nội bộ.** Kết quả đo trực tiếp trên hệ thống bằng luồng HEVC mô phỏng:

| Phép đo | Kết quả |
|---|---|
| Ghi hình luồng H.265 | ✅ Bình thường — ghi nguyên luồng gốc, không đụng codec |
| Xem trực tiếp nội bộ, trình duyệt không đỡ HEVC | ❌ Hệ thống **vẫn trả về HEVC** (`hevc/hev1`), không tự đổi mã → **đen hình** trên Chrome/Linux |
| Chuyển mã sang H.264 khi được yêu cầu | ✅ Chạy tốt (`h264/avc1`) — **cơ chế đã có sẵn**, hiện dùng cho kênh xem từ xa |
| Xem từ xa qua cloud | ✅ Đã tự lùi luồng phụ / chuyển mã |

→ **Việc cần làm:** áp dụng đúng cơ chế chuyển mã đã có cho đường xem trực tiếp và xem lại nội bộ.
Ước lượng: live view **1–2 ngày**, playback + xuất video **2–4 ngày**. Chi phí xử lý thấp nhờ Intel Quick Sync,
nhưng mỗi kênh phải chuyển mã sẽ làm giảm số camera tối đa.

**Các hạng mục đề xuất ưu tiên tiếp theo:** RBAC (phân quyền nhiều người dùng) · Audit Trail · webhook cấu hình qua giao diện.

---

## NVR SOFTWARE — KEY HIGHLIGHTS

- **Deployment:** Docker / bare-metal Linux (Ubuntu LTS). Hỗ trợ Standalone và **Distributed Multi-site** qua Edge Gateway + overlay Nebula mã hoá.
- **Core Performance:** Ghi hình **H.265 và H.264 nguyên luồng gốc, không nén lại**; xem trực tiếp trên trình duyệt bằng MSE/fMP4, xem từ xa bằng WebRTC, tự chuyển sang H.264 khi trình duyệt không giải mã được H.265 — không cần cài plugin hay phần mềm client.
- **AI:** Phân loại **người / phương tiện** và **vùng phát hiện** chạy trực tiếp trên NVR (CPU); tích hợp **AIView Box** cho nhận diện khuôn mặt và biển số, tự cắt clip sự kiện. Sẵn sàng bổ sung tăng tốc phần cứng (OpenVINO/Coral/TensorRT) theo yêu cầu dự án.
- **Protocols:** **ONVIF (tập lệnh Profile S, hai chiều client + server)**, RTSP, REST API, WebSocket API, MQTT.
- **Security:** TLS 1.2/1.3, bcrypt + phiên ký HMAC, chống dò mật khẩu theo IP, **khôi phục truy cập hoàn toàn offline**, mã hoá mật khẩu camera khi lưu, MQTT nội bộ có xác thực và ACL.
- **Storage:** Lưu theo sự kiện hoặc 24/7 đặt riêng từng camera, **MergerFS mở rộng bằng cách lắp thêm ổ**, tự dọn dữ liệu cũ khi đầy.
- **Reliability:** Tự giám sát và tự phục hồi khi có sự cố, chạy độc lập không cần Internet; bộ cài ngoại tuyến triển khai trọn vẹn tại hiện trường.

### Những mục KHÔNG có — nêu minh bạch để đối chiếu hồ sơ

RTMP · WHEP · ONVIF Profile G/T và chứng nhận ONVIF chính thức · Cluster Master/Worker · nhận diện khuôn mặt/biển số chạy trên NVR · line crossing · frame-batching / dynamic FPS · ANR · đệm RAM · RAID trong ứng dụng · NAS/S3 quản lý qua giao diện · mã hoá dữ liệu lưu trữ AES-256 · **RBAC · 2FA · Audit Trail** · NDAA / TAA / FIPS 140-2 / TPM 2.0 · webhook cấu hình qua giao diện · cổng PoE tích hợp · cổng xuất hình HDMI/VGA/BNC · Alarm I/O · đàm thoại 2 chiều.

---
*Chi tiết đầy đủ: [DATASHEET_AIView_NVR.md](./DATASHEET_AIView_NVR.md) · Bản tóm tắt 1 trang: [DATASHEET_AIView_NVR_tomtat.md](./DATASHEET_AIView_NVR_tomtat.md)*
