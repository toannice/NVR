# AIView NVR — Thông số kỹ thuật tóm tắt

**Bkav AI · Phiên bản V1.0.0.3 · 30/07/2026**
Đầu ghi hình mạng dạng phần mềm (Software-defined NVR) chạy trên máy chủ x86.

**Đặc điểm chính:** ghi hình luồng gốc H.265/H.264 không nén lại · vận hành hoàn toàn qua Web UI HTTPS, không cần cài phần mềm client · khôi phục mật khẩu offline không cần Internet · kết nối camera chi nhánh xa qua overlay mã hoá Nebula · tự giám sát và tự phục hồi khi có sự cố.

## Hệ thống & hiệu năng
| | |
|---|---|
| Nền tảng | Ubuntu 24.04 LTS + Docker, kiến trúc đa dịch vụ |
| Số kênh | Không giới hạn cứng — theo CPU / băng thông / đĩa |
| Chuẩn nén | **Ghi hình:** H.265 và H.264, nguyên luồng gốc không nén lại. **Xem trên trình duyệt:** H.264 mọi nền tảng; H.265 tuỳ trình duyệt, hệ thống tự chuyển sang H.264 khi cần |
| Độ phân giải | Theo camera nguồn, đã chạy thực tế tới 4K (8 MP) |
| Tăng tốc phần cứng | Intel Quick Sync — tự dò iGPU, không có vẫn chạy |

## Hiển thị & xem lại
| | |
|---|---|
| Giao diện | Web UI HTTPS (Chrome/Edge/Firefox, desktop & mobile) |
| Chia màn hình | 1 / 4 / 9 / 16 / 25 ô + 7 bố cục không đối xứng (1+3 … 2+5) |
| Xem trực tiếp | MSE/fMP4 độ trễ thấp (LAN) · WebRTC (từ xa qua cloud) |
| Phát lại | Timeline 24 giờ, tốc độ 0.5x / 1x / 2x / 4x, digital zoom tới 8x |
| Xuất video | Cắt đoạn → MP4, quản lý và tải trong mục "Tải xuống" |
| PTZ | ONVIF ContinuousMove qua Web UI (pan/tilt/zoom, tự dò khả năng) |

## Lưu trữ
| | |
|---|---|
| Cơ chế | mergerfs pool — thêm ổ là tăng dung lượng, không format lại |
| Chính sách lưu | Mặc định 7 ngày; chế độ *motion* hoặc *all*, đặt riêng từng camera |
| Lịch ghi hình | Theo khung giờ và theo camera |
| Chống đầy đĩa | Tự xoá bản ghi cũ nhất theo chính sách; watchdog giám sát dung lượng |

## Mạng & tích hợp
| | |
|---|---|
| Cổng dịch vụ | 443 HTTPS · 9000 HTTP (chỉ ONVIF) · 9001 MQTT · 8554 RTSP · 8555/UDP WebRTC |
| Giao thức | TCP/IP, HTTPS, RTSP/TCP, WebRTC, MQTT (auth + ACL), WS-Discovery, DHCP, DNS, NTP |
| ONVIF client | Dò tìm camera, thêm camera, lấy profile, điều khiển PTZ |
| ONVIF server | Device / Media / Streaming — tập lệnh cốt lõi tương thích Profile S *(chưa có chứng nhận chính thức)* |
| API mở | REST API HTTPS đầy đủ + hợp đồng tích hợp AIView Box v1.1 |
| Đa điểm | Edge Gateway (Raspberry Pi) qua overlay **Nebula mTLS**, lighthouse tự vận hành; gateway không lưu mật khẩu camera |
| Cloud | MQTT quản lý + WebRTC xem trực tiếp và phát lại từ xa |

## Bảo mật
| | |
|---|---|
| Truy cập | Bắt buộc TLS 1.2 / 1.3, HTTP tự chuyển hướng 301 |
| Xác thực | Mật khẩu hình, băm bcrypt cost 12; phiên bằng cookie ký HMAC |
| Khôi phục mật khẩu | Hoàn toàn offline: mã khôi phục ~98 bit (dùng một lần) + 3 câu hỏi bảo mật |
| Chống dò | Giới hạn theo IP luỹ tiến 60s → 5 phút → 15 phút → 1 giờ, bucket độc lập |
| Dữ liệu | Mật khẩu camera mã hoá khi lưu; không có secret trong bundle giao khách |
| Bề mặt tấn công | Ứng dụng bind loopback, chỉ vào qua nginx + TLS; API engine không mở ra LAN |
| Chống SSRF | Kiểm tra URL RTSP: chặn loopback, link-local, scheme lạ |

## Trí tuệ nhân tạo
- Phát hiện chuyển động + phân loại **người / phương tiện**, vẽ vùng phát hiện (zones) theo từng camera.
- Nhận sự kiện AI từ **AIView Box** → tự cắt clip −5s/+5s, trích ảnh khung hình, hiển thị ở tab "Sự kiện AI".
- Nhận diện khuôn mặt / biển số do thiết bị AI chuyên dụng đảm nhiệm; NVR lưu trữ và hiển thị kết quả.

## Vận hành & độ tin cậy
| | |
|---|---|
| Tự phục hồi | Watchdog giám sát, tự khởi động lại thành phần lỗi — chạy được cả khi không có Internet |
| Cập nhật phần mềm | Mặc định **cập nhật thủ công tại chỗ** bằng bộ cài mới. Cập nhật từ xa (OTA, tự rollback) là **tuỳ chọn**, không bật trên máy giao khách |
| Cảnh báo | Telegram: container chết, đĩa đầy (tuỳ chọn, cần Internet) |
| Triển khai | Trực tuyến `install.sh` · Ngoại tuyến bundle ~1.2 GB (không cần Internet tại hiện trường) |

## Cấu hình tham chiếu & sức chứa
| | |
|---|---|
| Máy chủ tham chiếu | Intel Core i5-9400 (6 nhân) · 24 GB RAM · SSD 240 GB + pool ghi hình 1.2 TB · UHD 630 · 1× RJ45 |
| Yêu cầu tối thiểu | 4 nhân x86-64 · 8 GB RAM · SSD 120 GB + HDD 1 TB · 1 Gbps |

| Cấu hình camera (H.265) | Bitrate/cam | NIC 100 Mbps | NIC 1 Gbps |
|---|---|---|---|
| FullHD main + 720p sub | 4 – 5.5 Mbps | ~12 – 16 kênh | ~24 kênh |
| 4K main + FullHD sub | 12 – 16 Mbps | ~4 – 5 kênh | 6 – 8 kênh |

> Số kênh là **ước lượng theo băng thông**, chưa nghiệm thu bằng đo tải thực tế.

## Không có trong bản này
*(nêu minh bạch để đối chiếu hồ sơ thầu)*

Cổng xuất hình cục bộ HDMI/VGA/BNC · cổng PoE tích hợp · Alarm I/O, Audio I/O, đàm thoại 2 chiều, RS-485/RS-232 · RAID và hot-swap có quản lý, eSATA/SAS, N+1 hot spare, ANR · nguồn kép dự phòng (RPS) · TPM 2.0 / Secure Element / FIPS 140-2 / firmware ký số · chứng nhận NDAA / TAA / chứng nhận ONVIF chính thức.

---
*Chi tiết đầy đủ: [DATASHEET_AIView_NVR.md](./DATASHEET_AIView_NVR.md)*
