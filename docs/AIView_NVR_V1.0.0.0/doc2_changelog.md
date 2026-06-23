# Changelog & Release Notes — AIView NVR
**Phiên bản:** 1.0.0.0 | **Ngày:** 17/06/2026 | **Người soạn:** Bkav AIView Team | **Duyệt:** HaDM

---

# Tổng quan các phiên bản

| Version | Ngày phát hành | Mô tả |
|---------|---------------|-------|
| 1.0.0.0 | 17/06/2026 | Phiên bản phát hành đầu tiên |

# Chi tiết V1.0.0.0 — 17/06/2026

**Loại:** Initial Release — Phiên bản thương mại đầu tiên

## Camera & Live View

- Thêm/sửa/xóa camera qua Web UI: RTSP trực tiếp hoặc ONVIF discovery
- Live view WebRTC (cloud AIView dashboard) và HLS local
- Ghi hình liên tục qua Frigate 0.16.3 (chốt version)
- PTZ: Pan/Tilt/Zoom với auto-detect từ ONVIF GetNodes, override thủ công (Tự động / Toàn phần / Chỉ Zoom)

## Gateway — Camera từ xa qua Pi

- Pi gateway kết nối qua Nebula overlay (MIT license, lighthouse tự host)
- NVR auto-scan phát hiện gateway, admin approve và adopt camera từ LAN khác
- Cert lifecycle: tự gia hạn R1 (30 ngày trước hết hạn), revoke V3, backup CA hàng ngày
- Bảo mật: G1 whitelist field subnet, G2 cert-group auth, G3 enrollment window, G6 nginx name sanitize
- Watchdog giám sát HQ signing-service, alert Telegram khi offline hoặc cert sắp hết hạn

## Tích hợp AiView Box

- NVR kết nối Box qua WebSocket event stream (`ws://boxIp:boxPort/ws/events`)
- Nhận và lưu sự kiện AI từ Box vào bảng `ai_events`
- REST API chuẩn NVR-AIView Integration Protocol v1.1
- Rate limiting 30 req/phút/IP, bcrypt authentication
- Tab "Tích hợp" trong Web UI: bật/tắt, cấu hình kết nối Box

## Cloud Sync

- Đồng bộ camera CRUD với cloud AIView qua MQTT
- WebRTC DATA channel qua dc-bridge (Go/Pion sidecar) cho device info, ethernet, time
- Nguồn sự thật duy nhất: bảng `Cameras`
- Đăng ký go2rtc stream đồng bộ khi thêm camera

## Bảo mật & Xác thực

- Pattern lock với Gmail OTP (SMTP) cho Web UI
- bcrypt cost 12 cho pattern hash; auto-upgrade SHA-256 → bcrypt transparent
- SSRF prevention: validate RTSP URL (chặn loopback, link-local, cloud metadata)
- Frigate port 5000 bind localhost only

## Triển khai & Vận hành

- `package.sh`: đóng gói stack thành `V{version}-{date}.tar.gz`
- `deploy-offline.sh`: cài đặt offline hoặc OTA mode
- Watchdog OTA với `WRAPPER_TAG` là nguồn sự thật (latest/staging/vX.Y.Z)
- HTTPS nginx với self-signed cert tự sinh khi deploy
