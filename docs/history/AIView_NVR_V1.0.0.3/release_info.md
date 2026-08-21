# AIView NVR — Release V1.0.0.3

**Ngày phát hành:** 15/07/2026

## Tài liệu

- **Hướng dẫn cài đặt:** [Hướng dẫn cài đặt V1](https://drive.google.com/file/d/1ij-k9fNc5LJBwGdnQb3Ps20UO3FMEpcm/view) *(quy trình không đổi so với V1.0.0.0)*
- **Cài đặt môi trường biên dịch (dành cho dev):** [Cài đặt môi trường biên dịch](https://drive.google.com/file/d/1FSXOxZSR659PbSTKGKE5zE8GY104EfuY/view)
- **Changelog đầy đủ:** `doc2_changelog.docx` (cùng thư mục này)

## Package

File bundle: `V1.0.0.3-20260715.tar.gz`

Cài trên máy mới (không cần internet):
```bash
tar -xzf V1.0.0.3-20260715.tar.gz
cd nvr-bundle-20260715
sudo ./deploy-offline.sh
```

## Thay đổi chính từ V1.0.0.0 → V1.0.0.3

- Box AI đẩy sự kiện sang NVR (`POST /api/pushevent`) — NVR tự cắt clip -5/+5s, tab "Sự kiện AI" mới trên Web UI.
- Cloud playback: transcode phần cứng VAAPI, tua x2/x4/x8, play/pause thật.
- Bảo mật: MQTT nội bộ bắt buộc auth + ACL; agent Pi bỏ quyền root (G4); nút thu hồi gateway (G8); đóng cổng nội bộ (G9).
- Vận hành: OTA chỉ áp lúc 3h sáng; alert Telegram chi tiết theo tên máy; giới hạn log Docker chống đầy đĩa.
- Kèm trong bundle (chưa OTA): alert gia hạn cert gateway thất bại; Pi tự đồng bộ giờ NTP trước khi lên overlay; chống mòn SD.
