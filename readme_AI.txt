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
## 8. Quản lý Đội ngũ & Bảo trì (Fleet Management & Maintenance)
Hệ thống được thiết kế để quản lý hàng loạt (scale-out) mà không cần can thiệp thủ công vào từng thiết bị.

### 8.1. Quy trình Cập nhật Phiên bản (OTA & Fleet Deployment)
Khi bạn có thay đổi về code và muốn đẩy lên tất cả các máy trạm:

**Bước 1: Build và Push Image (Tại máy cá nhân/Server chính)**
1. Mở terminal tại thư mục dự án.
2. Build image mới: `docker build -t <your-username>/my-nvr:latest .`
3. Đẩy lên kho lưu trữ: `docker push <your-username>/my-nvr:latest`

**Bước 2: Triển khai hàng loạt (Deployment)**
- **Cách 1: Tự động hàng ngày (Watchtower - Không chạm dữ liệu)**
  - Mặc định, `watchtower` trên các máy trạm sẽ tự động quét bản mới trên Docker Hub vào lúc 3h sáng và tự cập nhật. Nó chỉ ghi đè container phần mềm, bảo toàn 100% dữ liệu `media` và `data`.
- **Cách 2: Chủ động lập tức (Ansible - Dành cho nâng cấp hạ tầng)**
  - Sử dụng file `deploy.yml` đi kèm dự án. 
  - Khai báo IP (Tailscale) định danh các máy trạm vào file `inventory.ini`.
  - Khai báo User/Pass Docker Hub (nếu image là Private) qua Terminal Variables:
    ```bash
    export DOCKER_USER="<username>"
    export DOCKER_PASS="<password>"
    ansible-playbook -i inventory.ini deploy.yml -K
    ```
  - Cấu hình hạ tầng mới sẽ được Ansible triển khai và container sẽ tự động restarts.

### 8.2. Khởi tạo máy trạm từ con số 0 (Zero-Touch Provisioning)
Khi lắp đặt nhà hàng/chi nhánh mới:
1. Copy duy nhất file `run.sh` (Phiên bản All-in-one) qua máy Ubuntu mới.
2. Chạy lệnh: `sudo ./run.sh`
3. Kịch bản sẽ tương tác và thực hiện chuỗi các tác vụ:
   - Hỏi bạn có muốn **Factory Reset** (Xóa sạch DB & Volume cũ) không.
   - Cài đặt công cụ lõi \& Docker.
   - Tự động gộp các ổ đĩa vật lý thành `/media` qua MergerFS.
   - Cài đặt SSH Server & Mạng riêng ảo Tailscale (để kết nối vào Ansible).
   - Sửa quyền hệ thống (Fix Permissions 777) và dựng NVR.
4. Ngay khi NVR trực tuyến trên IP mới, người dùng nhấn "Thêm Camera" trên giao diện Web. API Backend (`CameraRouting.kt`) sẽ **tự động sinh toàn bộ bộ khung config.yml tiêu chuẩn** cho Frigate mà không cần ai động đến file text. Cấu trúc cấu hình tối ưu này bao gồm:
   - **Lưu trữ (Record)**: Bật ghi hình mặc định 24/7 với chế độ xoay vòng 7 ngày.
   - **Tăng tốc phần cứng**: Tự động chèn `preset-intel-qsv-h264` để giải mã luồng bằng QuickSync của chip Intel đời 8 trở lên, giúp server mát và tiết kiệm CPU.
   - **Restream & WebRTC (Độ trễ <500ms)**: Sử dụng go2rtc để lấy và phát lại luồng qua giao thức `#tcp`, tự động gán ứng cử viên WebRTC (`{FRIGATE_HOST_IP}:8555` và `stun:8555`).
   - **Hiển thị trực tiếp**: Camera vừa thêm sẽ được tự động kích hoạt `detect: enabled: true`, đảm bảo màn hình tổng quan (Overview Dashboard) có hình ảnh ngay lập tức.

## 9. Kết nối Từ xa & Xử lý trùng IP (Remote Connectivity & NAT Traversal)
Khi triển khai nhiều máy trạm ở các địa điểm khác nhau (hệ thống phân tán), dù dải IP nội bộ đều là `192.168.2.x`, hệ thống vẫn hoạt động hoàn hảo nhờ các cơ chế sau:

### 9.1. Cập nhật qua Docker Hub (Outbound)
- Các máy trạm chủ động "nhìn" ra ngoài internet để lấy bản cập nhật từ Docker Hub. 
- Việc trùng dải IP nội bộ (`192.168.2.x`) ở các công trình khác nhau không ảnh hưởng gì vì mỗi máy đều đứng sau một Router riêng và có IP Public (hoặc NAT) riêng để đi ra ngoài.

### 9.2. Quản lý tập trung (Inbound & Ansible)
Để bạn có thể dùng Ansible điều khiển các máy này từ văn phòng mà không cần NAT Port hay cài VPN phức tạp:
- **Giải pháp khuyên dùng: Tailscale (Mesh VPN)**
  - Cài Tailscale lên mỗi máy Ubuntu trạm. 
  - Mỗi máy sẽ được cấp một IP duy nhất (dải `100.x.x.x`) không bao giờ trùng nhau.
  - Bạn dùng IP Tailscale này để khai báo vào file `hosts` của Ansible. Bạn có thể SSH vào bất kỳ máy nào ở bất kỳ đâu chỉ bằng một IP cố định này.
- **Giải pháp Zero-Trust: Cloudflare Tunnel**
  - Tôi đã để sẵn "stub" (đoạn mã gợi ý) trong `docker-compose.yml`. 
  - Giúp bạn ánh xạ giao diện Web (Port 80/443) của từng máy trạm thành các subdomain như `station1.yourdomain.com`, `station2.yourdomain.com` để xem live từ xa mà không cần mở port modem.
