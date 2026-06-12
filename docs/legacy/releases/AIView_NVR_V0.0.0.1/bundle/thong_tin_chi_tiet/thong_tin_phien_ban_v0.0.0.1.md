THÔNG TIN PHIÊN BẢN 0.0.0.1
==============================
Ngày phát hành: 01/06/2026
Team phụ trách: AIView Dev Team

TÀI LIỆU LIÊN QUAN
-------------------
📄 Cài đặt môi trường biên dịch: [xem Drive sau khi upload]
📘 Hướng dẫn cài đặt, triển khai: [xem Drive sau khi upload]

NỘI DUNG THAY ĐỔI
-----------------
Phiên bản khởi tạo (0.0.0.1) — Initial release.

Tính năng chính:
- Camera RTSP/ONVIF management với unified source of truth
- Cloud sync: MQTT + WebRTC LIVE + WebRTC DATA (dc-bridge)
- Pattern lock + Gmail OTP authentication
- Security: bcrypt, RTSP SSRF validation, config mutex, Docker secrets
- Watchdog: self-healing + OTA auto-update
- Stack: mosquitto, Frigate 0.16.3, nvr-wrapper (Kotlin/React), nginx HTTPS
