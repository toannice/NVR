# Báo cáo So sánh Tài liệu
**Version hiện tại:** 1.0.0.3
**Version trước:** 1.0.0.0
**Ngày tạo:** 15/07/2026

## ✅ Không thay đổi

- **Quy trình cài đặt:** vẫn là bundle offline `package.sh` → `deploy-offline.sh` (hoặc OTA qua GHCR). "Hướng dẫn cài đặt V1" của bản 1.0.0.0 **dùng lại nguyên vẹn** — không tạo doc mới.
- **Môi trường biên dịch:** stack không đổi (Kotlin/Ktor + React + Docker Compose, Frigate chốt 0.16.3) — tài liệu môi trường biên dịch V1 giữ nguyên.
- **Yêu cầu phần cứng/OS:** Ubuntu + Docker, không đổi.

## ⚠️ Có sai khác / Cập nhật

| Mục | Version cũ (1.0.0.0) | Version mới (1.0.0.3) | Ghi chú |
|-----|----------------------|------------------------|---------|
| Tích hợp Box — chiều dữ liệu | NVR nhận event qua WebSocket `ws://box:port/ws/events` | Thêm chiều Box chủ động đẩy: `POST /api/pushevent` → NVR cắt clip -5/+5s + tab "Sự kiện AI" | Protocol hiển thị 1.2 |
| Cloud playback | Xem lại cơ bản | VAAPI hw transcode, tốc độ x2/x4/x8, play/pause thật | dc-bridge own playback peer |
| MQTT nội bộ | Không xác thực | Bắt buộc user/pass + ACL per-client | Máy cũ cần chạy lại `install.sh` (OTA không tự áp) |
| Agent trên Pi | Chạy root | User riêng `nvragent` + sandbox systemd + sudoers 2 lệnh (G4) | |
| Watchdog alert | Cơ bản | Chi tiết + tên máy theo HOSTNAME; OTA gate 3h sáng | |
| Log Docker | Không giới hạn | json-file 50m × 3 mọi container | |
| Version hiển thị | Badge luôn "dev" | File `VERSION` nướng vào image, API trả đúng 1.0.0.3 | |
| dc-bridge | Binary ngoài repo | Source Go trong repo `dc-bridge/` | |

## ❌ Mâu thuẫn giữa các tài liệu

- Changelog V1.0.0.0 ghi Box integration "Protocol v1.1" nhưng cùng doc nhắc WebSocket v1.2; API thực tế trả `protocol_version: 1.2` → thống nhất dùng **v1.2** từ tài liệu này.
- Bundle V1.0.0.3 build từ working tree có thêm 3 thay đổi **chưa commit** (alert renew-fail, chrony/SD-wear cho Pi, chính sách IP tĩnh trong docs) — cần commit trước khi push OTA để image `latest` khớp bundle.
