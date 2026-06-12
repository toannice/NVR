[LOGO AIVIEW — căn giữa]

| Phiên bản | Ngày       |
|:---------:|:----------:|
| 0.0.0.1   | 01/06/2026 |

Thank you for choosing our products. If there are any questions or requests,
please do not hesitate to contact us at aiview@bkav.com.

# Hướng dẫn Cài đặt & Triển khai — AIView NVR

Tài liệu số: NVR-DOC02-V0.0.0.1

Đơn vị: AIView Dev Team

Ngày viết: 01/06/2026

Ngày ban hành tài liệu: 01/06/2026

Được soạn bởi

AIView Dev Team

Địa chỉ: Tầng 2 toà nhà HH1, Phường Yên Hoà, Quận Cầu Giấy, Thành phố Hà Nội.

| Người tổng hợp    | Người duyệt             |
|-------------------|-------------------------|
| AIView Dev Team   | AIView Product Manager  |

## Lịch sử sửa đổi

| Bản sửa đổi | Ngày       | Mô tả                                                           |
|:-----------:|:----------:|:---------------------------------------------------------------:|
| 0.0.0.1     | 01/06/2026 | Khởi tạo hướng dẫn cài đặt cho AIView NVR v0.0.0.1            |

## Mục lục

1. [Yêu cầu trước khi bắt đầu](#1-yêu-cầu-trước-khi-bắt-đầu)
2. [Cài đặt môi trường cơ bản](#2-cài-đặt-môi-trường-cơ-bản)
3. [Lấy source code](#3-lấy-source-code)
4. [Cấu hình môi trường](#4-cấu-hình-môi-trường)
5. [Build image](#5-build-image)
6. [Khởi động stack](#6-khởi-động-stack)
7. [Cấu hình camera](#7-cấu-hình-camera)
8. [Cài đặt dc-bridge (tính năng cloud)](#8-cài-đặt-dc-bridge-tính-năng-cloud)
9. [Triển khai HTTPS (production)](#9-triển-khai-https-production)
10. [Xử lý sự cố thường gặp](#10-xử-lý-sự-cố-thường-gặp)

[LOGO AIVIEW — căn giữa]
The Future of AI Camera

---

## 1. Yêu cầu trước khi bắt đầu

Kiểm tra trước khi bắt đầu:

- [ ] Máy chủ chạy Ubuntu 22.04 LTS (x86_64)
- [ ] Có kết nối Internet
- [ ] Có quyền `sudo`
- [ ] RAM tối thiểu 4 GB, ổ cứng trống 20 GB+
- [ ] Docker Engine 24.0+ đã được cài đặt

---

## 2. Cài đặt môi trường cơ bản

### Bước 1: Cập nhật hệ thống

```bash
sudo apt-get update && sudo apt-get upgrade -y
```

Kiểm tra: không có lỗi trong output.

### Bước 2: Cài đặt Docker Engine

```bash
# Thêm Docker GPG key
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg

# Thêm Docker repo
echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] \
  https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

# Cài đặt
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-compose-plugin
```

Kiểm tra:
```bash
docker --version
docker compose version
```

Kết quả mong đợi:
```
Docker version 24.x.x, build ...
Docker Compose version v2.x.x
```

### Bước 3: Thêm user vào group docker (tránh dùng sudo mỗi lần)

```bash
sudo usermod -aG docker $USER
newgrp docker
```

---

## 3. Lấy source code

### Bước 1: Clone repository

```bash
git clone https://github.com/toannice/NVR.git my-nvr
cd my-nvr
```

### Bước 2: Kiểm tra cấu trúc thư mục

```bash
ls -la
```

Kết quả mong đợi: thấy `docker-compose.yml`, `docker-compose.override.yml`, `Dockerfile`, `backend/`, `frontend/`, `config/`, `data/`, `nginx/`.

---

## 4. Cấu hình môi trường

### Bước 1: Tạo file `.env`

```bash
cp .env.example .env 2>/dev/null || touch .env
nano .env
```

Điền các giá trị sau:

```env
# IP của máy Ubuntu host (dùng cho WebRTC ICE candidates)
FRIGATE_HOST_IP=192.168.1.100

# Timezone
TZ=Asia/Ho_Chi_Minh

# Tag image (để mặc định)
WRAPPER_TAG=latest

# Watchdog: Telegram alert
TELEGRAM_BOT_TOKEN=YOUR_BOT_TOKEN_HERE
TELEGRAM_CHAT_ID=YOUR_CHAT_ID_HERE

# GitHub Container Registry token (pull image từ GHCR)
GITHUB_TOKEN=YOUR_GITHUB_TOKEN_HERE

# Gmail gửi OTP (chỉ địa chỉ email, không phải password)
GMAIL_USER=your-gmail@gmail.com
```

> **Lưu ý:** `FRIGATE_HOST_IP` phải là IP thực của máy host (không phải `127.0.0.1`). Kiểm tra bằng `hostname -I`.

### Bước 2: Tạo Gmail App Password secret

```bash
mkdir -p secrets
echo "your-gmail-app-password" > secrets/gmail_app_password
chmod 600 secrets/gmail_app_password
```

> Gmail App Password: vào Google Account → Security → 2-Step Verification → App passwords → tạo mới.

### Bước 3: Tạo SSL certificate (cho HTTPS)

```bash
mkdir -p nginx/certs
# Tự ký (cho dev/test):
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout nginx/certs/server.key \
  -out nginx/certs/server.crt \
  -subj "/C=VN/ST=HN/L=Hanoi/O=AIView/CN=localhost"
```

---

## 5. Build image

```bash
# PHẢI chạy từ project root /home/toan/my-nvr (hoặc thư mục đã clone)
docker compose build --no-cache nvr-wrapper
```

Thời gian ước tính: 5-15 phút (tùy tốc độ máy và mạng).

Kết quả mong đợi:
```
=> => naming to docker.io/library/nvr-wrapper:latest
```

---

## 6. Khởi động stack

### Bước 1: Khởi động toàn bộ

```bash
docker compose up -d
```

### Bước 2: Khởi động nginx (phải chạy riêng)

```bash
docker compose up -d nginx
```

> nginx **không** phụ thuộc nvr-wrapper trong compose, nên cần start riêng.

### Bước 3: Kiểm tra tất cả container đang chạy

```bash
docker compose ps
```

Kết quả mong đợi — tất cả STATUS là `Up` hoặc `running`:

```
NAME             STATUS
mosquitto        Up
frigate          Up
nvr-wrapper      Up
nvr-watchdog     Up
nvr-nginx        Up
```

### Bước 4: Kiểm tra logs

```bash
docker compose logs nvr-wrapper --tail=30
```

Kết quả mong đợi: thấy `Application started` và không có `ERROR`.

### Bước 5: Truy cập Web UI

Mở trình duyệt: `https://<FRIGATE_HOST_IP>`

> Lần đầu sẽ thấy cảnh báo SSL (nếu dùng self-signed cert) — chọn "Advanced → Proceed".

---

## 7. Cấu hình camera

### Thêm camera RTSP thủ công

1. Mở Web UI → Tab **Camera**
2. Nhấn **Thêm Camera** → chọn tab **RTSP trực tiếp**
3. Điền URL RTSP (ví dụ: `rtsp://admin:pass@192.168.1.50:554/stream`)
4. Nhấn **Lưu**

> Nếu camera offline sau khi thêm, kiểm tra `hwaccel_args` — xem phần Xử lý sự cố.

### Thêm camera qua ONVIF

1. Mở Web UI → Tab **Camera**
2. Nhấn **Scan ONVIF**
3. Chọn camera được phát hiện → điền credentials → **Thêm**

---

## 8. Cài đặt dc-bridge (tính năng cloud)

dc-bridge cần thiết để dashboard cloud AIView hiện thông tin Device / Ethernet / Time.

Binary pre-built có sẵn tại `data/dc-bridge`. Nếu muốn build lại:

```bash
# Yêu cầu: Go 1.21+ đã được cài đặt
SRC=~/Documents/1CodeBkav/B02/aiview_b02_box/dc-bridge
mkdir -p /tmp/dc-bridge-build && cp "$SRC"/{main.go,go.mod,go.sum} /tmp/dc-bridge-build/

# Patch port (JVM 8080 → NVR backend 3000)
sed -i 's|127.0.0.1:8080/internal/dc/command|127.0.0.1:3000/internal/dc/command|' \
  /tmp/dc-bridge-build/main.go

cd /tmp/dc-bridge-build
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build \
  -o /path/to/my-nvr/data/dc-bridge \
  -ldflags='-s -w' .

chmod +x /path/to/my-nvr/data/dc-bridge
```

Sau khi có binary, restart nvr-wrapper:

```bash
docker compose restart nvr-wrapper
```

Verify:

```bash
docker compose logs nvr-wrapper | grep dc-bridge
```

Kết quả mong đợi: `DC_BRIDGE_STARTED | dc-bridge started from /app/data/dc-bridge`

---

## 9. Triển khai HTTPS (production)

Thay self-signed cert bằng cert thực (ví dụ Let's Encrypt):

```bash
# Cài certbot
sudo apt-get install certbot -y

# Lấy cert (cần domain trỏ về máy)
sudo certbot certonly --standalone -d yourdomain.com

# Copy cert vào project
sudo cp /etc/letsencrypt/live/yourdomain.com/fullchain.pem nginx/certs/server.crt
sudo cp /etc/letsencrypt/live/yourdomain.com/privkey.pem nginx/certs/server.key
sudo chown $USER:$USER nginx/certs/*

# Restart nginx
docker compose restart nvr-nginx
```

---

## 10. Xử lý sự cố thường gặp

| Lỗi | Nguyên nhân | Cách xử lý |
|-----|-------------|------------|
| Camera offline ngay sau khi thêm | Intel QSV không tương thích GPU | Thêm `hwaccel_args: []` vào camera trong `config/config.yml` rồi restart Frigate: `curl -X POST http://localhost:5000/api/restart` |
| `https://<IP>` không truy cập được | nginx chưa start | `docker compose up -d nginx` |
| Build image bị dùng cache cũ | Docker build cache | `docker compose build --no-cache nvr-wrapper` |
| GHCR pull fail | GitHub token hết hạn | `echo <token> \| docker login ghcr.io -u toannice --password-stdin` |
| Container nvr-wrapper restart liên tục | Lỗi config hoặc missing file | `docker compose logs nvr-wrapper` để xem lỗi cụ thể |
| Dashboard cloud không có Device info | dc-bridge chưa chạy | Kiểm tra log `DC_BRIDGE_STARTED`; nếu thiếu binary — xem Bước 8 |
| OTP email không gửi được | Gmail App Password sai | Kiểm tra `secrets/gmail_app_password`, tạo lại App Password trên Google Account |
| `command not found: docker-compose` | Dùng sai version | Dùng `docker compose` (v2, có space, không có dấu `-`) |
