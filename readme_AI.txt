# PROJECT AI KNOWLEDGE BASE: my-nvr

Tài liệu này được tạo ra để cung cấp bối cảnh toàn diện về dự án `my-nvr` cho AI, giúp AI đọc hiểu nhanh chóng kiến trúc, luồng hoạt động và các yêu cầu nghiệp vụ chuyên sâu.

## 1. Tổng quan dự án (Project Overview)
- **Tên dự án:** my-nvr
- **Vai trò:** Bản chất là một hệ thống **NVR Wrapper** mức doanh nghiệp, hoạt động như một lớp trừu tượng phía trên lõi camera, với thành phần trung tâm là Frigate v0.16.3 và AI Box ngoại vi. 
- **Mục tiêu:** Trở thành "Tổng chỉ huy" (Orchestrator) hệ thống giám sát. Nó không đảm nhiệm trực tiếp render video nặng nề mà ủy thác cho Frigate, bản thân Wrapper đảm nhiệm proxy API, tổ chức UI, và quản lý lưu trữ với MergerFS trên môi trường Ubuntu. 

## 2. Stack Công Nghệ (Tech Stack)
- **Hệ điều hành:** Ubuntu (chuẩn công nghiệp cho hệ thống).
- **Backend:** Kotlin + framework Ktor (xử lý bất đồng bộ tốt, khởi động nhanh, chiếm ít RAM cho thiết bị Edge).
- **Frontend:** React + TypeScript + Vite + TailwindCSS (ưu tiên tốc độ render UI, kiến trúc "Ephemeral UI").
- **Message Broker:** Eclipse Mosquitto (MQTT broker 1883/9001) phục vụ giao tiếp AI và IoT.
- **Lõi xử lý Camera:** Frigate v0.16.3 + go2rtc phục vụ xử lý luồng (WebRTC, RTSP).
- **Reverse Proxy / Bảo mật:** Nginx (Gateway HTTPS).
- **Quản lý thiết bị lưu trữ:** MergerFS (gộp ổ đĩa vật lý dưới nền tảng FUSE).

## 3. Workflow & Kiến trúc hệ thống
- **Luồng Video (Video Pipeline):** 
  Camera IP -> Frigate (qua RTSP) -> NVR Wrapper (qua proxy API) -> Frontend Vite (ưu tiên phát bằng WebRTC qua go2rtc với độ trễ <500ms thay vì MSE). 
- **Luồng Sự Kiện AI (AI Events):**
  Frigate (hoặc AI Box truyền qua ONVIF Profile M / MQTT) -> Broker Mosquitto -> Backend Ktor -> Frontend. 
- **Workflow Lưu trữ & Xoay vòng (Storage & Retention):**
  Sử dụng chuẩn ghi phân đoạn (segments) của Frigate, phát lại thông qua `/api/recordings/summary`. Cơ chế xoay vòng (Retention) tự động xóa các đoạn video cũ dựa trên cấu hình số ngày lưu trữ hoặc khi dung lượng ổ đĩa chạm ngưỡng giới hạn. Quản lý ổ đĩa bằng MergerFS để tối ưu hóa việc ghi tuần tự và gộp nhiều ổ đĩa vật lý. 
  *Thông số định mức:* Dung lượng tiêu thụ trung bình ước tính **1.28 GB / giờ / camera**. 

## 4. Những chức năng đã có (Existing Features / Setup)
- Khởi tạo kiến trúc hệ thống Docker Compose gồm `mosquitto`, `frigate`, `nvr-wrapper`, `nginx`, `watchtower`.
- Codebase framework: Đã thiết lập cấu trúc backend bằng Kotlin/Gradle và Frontend bằng Vite/React.
- Cấu hình thư mục phân tán: `config`, `data`, `media` (kết nối trực tiếp vào Frigate).
- Hỗ trợ giải mã tăng tốc phần cứng thông qua Intel VAAPI (`/dev/dri/renderD128`).
- Giao tiếp thời gian thực: Mosquitto MQTT đã lắng nghe ở cổng 1883.
- **Bộ lập lịch ghi hình (Recording Scheduler):** Backend Kotlin tích hợp logic kiểm tra lịch trình (Scheduler) mỗi phút, điều khiển bật/tắt ghi hình của Frigate qua MQTT. Hỗ trợ thiết lập lịch linh hoạt theo ngày và giờ (bao gồm cả lịch chạy xuyên đêm).

## 5. Những chức năng đang làm (Current Focus - WIP)
- **Trải nghiệm Live View & Playback:** 
  - Xem một camera / Lưới nhiều camera tự động switch giữa Sub Stream (tiết kiệm băng thông) và Main Stream (để xem chi tiết).
  - Khả năng **Zoom kỹ thuật số (CSS Transform)** mượt mà, kết hợp với lăn chuột (Wheel panzoom) không bị giật lag.
  - Xem lại (Playback) độ trễ truy xuất thấp bằng việc bắt đầu segment chỉ trong 5-10s.
- **Giao tiếp thiết bị & AI Box:**
  - Tích hợp phát hiện mạng chuyên sâu ONVIF Profile S/T để nhận dạng Hardware (Mac Address, Firmware, Profiles).
  - Tích hợp chuẩn giao tiếp Object/Attribute Metadata từ AI Box ngoại vi bằng JSON qua MQTT.
- **Quản lý Storage nâng cao:** Tính toán dung lượng từng camera qua lệnh `du -sh` trong thư mục MergerFS.

## 6. Mục "Nâng cấp" tương lai (Future Upgrades)
- **[Nâng cấp 1] Tóm tắt AI tự nhiên (GenAI Summary):** Thay vì cảnh báo thô (vd "Phát hiện người"), đưa ra thông báo văn cảnh (vd "Phát hiện một nhóm người đứng lại kho hàng hơn 5 phút").
- **[Nâng cấp 2] Tự động hóa & Tích hợp IoT:** Liên kết các cảm biến khói/cửa để lập kịch bản (Automation rules) - Ví dụ: Mở cửa + Nhận diện người = Báo động cấp cao.
- **[Nâng cấp 3] Auto-tuning FFmpeg:** NVR tự động chọn cấu hình luồng tối ưu với sức cứng.
- **[Nâng cấp 4] Giao tiếp đàm thoại hai chiều (Two-way Talk):** Khai thác các codec PCMU/PCMA trên WebRTC/go2rtc.
- **[Nâng cấp 5] Quản lý đội ngũ (Fleet Management):** Cập nhật OTA bằng Watchtower (đã setup cấu trúc) cùng các công cụ quản lý thiết bị hàng loạt từ xa không gián đoạn dịch vụ.

## 7. Chức năng bảo mật (Security Features)
- **Nginx Security Gateway:** Tất cả các luồng truy cập frontend -> backend đều nên đi qua NGINX cấu hình HTTPS để bảo đảm bí mật dữ liệu. NVR Wrapper container không expose port trực tiếp (chạy dạng `host` network hoặc kết nối nội bộ).
- **Zero-trust Networking:** Đã chuẩn bị stub (có đánh dấu bình luận) trong compose để sử dụng `Cloudflare Tunnel` thay cho việc NAT port truyền thống.
- **Phân tách Role cập nhật:** Watchtower chỉ quét những container có scope/label `nvr-auto-update`, giữ Frigate độc lập ở phiên bản v0.16.3 được chốt cứng nhằm tránh gãy đổ do update tự động lỗi.
## 8. CI/CD & OTA (Over-The-Air) Maintenance
Hệ thống hiện tại đã được nâng cấp lên luồng CI/CD tự động 100% qua GitHub Actions và GitHub Container Registry (GHCR).

### 8.1. Luồng phát triển 2 Làn (Deployment Pipeline)
Bạn thao tác hoàn toàn từ **Máy Code (Dev)**, các máy trạm sẽ tự động nhận diện bản build phù hợp:

1. **Làn THỬ NGHIỆM (Staging/Beta):**
   - **Thao tác:** `git push origin staging` (Từ máy dev).
   - **GitHub Action:** Tự động Build Image và gắn tag `:staging`.
   - **Máy TEST:** Thiết lập nhận tag `:staging`. Watchtower sẽ tự động kéo bản mới (mỗi 5 phút hoặc qua lệnh thủ công).
   
2. **Làn SẢN XUẤT (Production/Stable):**
   - **Thao tác:** Merge `staging` vào `main` và `git push origin main`.
   - **GitHub Action:** Tự động Build Image và gắn tag `:latest`.
   - **10 Máy Trạm:** Chỉ nhận tag `:latest`. Tự động cập nhật âm thầm vào **03:00 sáng** hàng ngày để đảm bảo ổn định.

### 8.2. Watchtower & Cập nhật tức thì
- **Cập nhật thủ công (Update Now):** Tại bất kỳ máy nào, chạy `./update-now.sh` để kích hoạt Watchtower kéo code mới ngay lập tức qua HTTP API (Port 8080 nội bộ), không cần đợi đến 3h sáng.
- **Bảo mật Login:** Watchtower được chia sẻ file `/root/.docker/config.json` để tự động dùng quyền `docker login` của máy host, giúp nó pull được Private Image từ GHCR mà không lộ mật khẩu.
- **Fix lỗi Docker API:** Đã ép `DOCKER_API_VERSION=1.44` để tương thích hoàn toàn với các phiên bản Docker mới nhất (v25+).

### 8.3. Khởi tạo máy trạm v4.0 (run.sh)
Script `run.sh` đã được tự động hóa tối đa:
- **Tự động cấu hình:** Hỏi Username, GitHub PAT (Token), Telegram Bot/Chat ID và lưu vào `.env` (có dấu ngoặc kép chuẩn xác).
- **Tự động Login:** Tự thực hiện `docker login ghcr.io` ngay khi cài đặt.
- **Tự động sinh Token:** Tạo ngẫu nhiên `WATCHTOWER_TOKEN` bảo mật để bảo vệ cổng API cập nhật.

## 9. Chế độ Thử nghiệm & Test (Test-Mode)
- **Bypass Setup Gmail:** Để thuận tiện cho việc test nhanh trên 10 máy, bước xác thực OTP qua Gmail đã được cấu hình "Bypass" (Bỏ qua).
- **Cách dùng:** Khi cài máy mới, bạn chỉ cần vẽ hình mở khóa 2 lần. Hệ thống Frontend sẽ tự động gửi dữ liệu giả (`test@test.com`/`000000`) để hoàn tất cài đặt và cho phép vào giao diện chính ngay lập tức.

## 10. Kết nối Từ xa & Quản lý Fleet
- **Tailscale (Mesh VPN):** Dùng IP `100.x.x.x` duy nhất cho mỗi máy để bạn SSH quản lý hàng loạt qua Ansible.
- **Cloudflare Tunnel:** Để Remote Web View qua HTTPS mà không cần mở Port modem (NAT).
- **Telegram Alert:** Mọi sự kiện Cập nhật thành công/Thất bại hoặc Lỗi ổ cứng đều được báo về Telegram của bạn theo thời gian thực.

## 11. Logic Thêm Mới Camera & Tối Ưu Cấu Hình (go2rtc)
Khi người dùng thêm camera mới (thủ công qua RTSP hoặc tự động qua ONVIF), hệ thống Backend Kotlin sẽ áp dụng chuẩn cấu hình trực tiếp vào file `config/config.yml` mặc định như sau:
- **Tập trung luồng (go2rtc):** Đường dẫn gốc RTSP của camera (ví dụ: `rtsp://...#tcp`) được lưu duy nhất tại thẻ `go2rtc -> streams`. Hệ thống NVR chỉ tạo kết nối một lần duy nhất với camera gốc, đặc biệt hữu ích cho các mạng có kết nối truyền dẫn kém, giúp chia sẻ băng thông.
- **Tái sử dụng nội bộ (Local Restream):** Ở khối cấu hình của camera trong Frigate (`cameras -> <slug> -> ffmpeg -> inputs`), hệ thống thay thế đường dẫn trực tiếp bằng địa chỉ proxy mạng nội bộ: `path: rtsp://127.0.0.1:8554/<slug>`. Đồng thời sử dụng `input_args: preset-rtsp-restream` nhằm thông báo cho FFmpeg bỏ qua giai đoạn sửa lỗi luồng phân mảnh, giảm thiểu buffer để đẩy tốc độ Load (Live View) xuống gần 0ms.
- **Cấu hình Lưu trữ (Recording):** Camera mới tự động được bật (`enabled: true`) và cấu hình sẵn chế độ lưu trữ 24/7 trong 7 ngày (`retain: -> days: 7, mode: all`), đảm bảo không thất thoát đoạn ghi hình nào ngay khi thiết bị vừa được thêm.
