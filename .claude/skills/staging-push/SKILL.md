---
name: staging-push
description: >
  Build nvr-wrapper từ source, push lên GHCR với tag `staging`, để các máy sản phẩm ngoài field
  tự OTA lúc đêm (watchdog chạy lúc 3h sáng). Kích hoạt khi người dùng nói: "đẩy lên staging",
  "push staging", "update cho máy field", "deploy field", "release staging", "/staging-push",
  hoặc bất kỳ yêu cầu nào muốn đưa code mới ra máy sản phẩm qua kênh staging.
---

# Staging Push — Build & Push GHCR Staging

Skill này build image `nvr-wrapper`, tag thành `ghcr.io/toannice/nvr:staging`, push lên GHCR.
Các máy field có `WRAPPER_TAG=staging` sẽ tự kéo về và cập nhật lúc 3h sáng (watchdog OTA).

## Quy trình

### Bước 1 — Kiểm tra trước khi push

Chạy song song:

```bash
# Commit hiện tại (cái gì sẽ được push)
git log --oneline -5

# Có uncommitted changes không? (cảnh báo nếu có)
git status --short

# GHCR login còn hạn không?
docker info 2>/dev/null | grep -i "registry" || true
```

Hiện cho người dùng:
- 5 commit gần nhất
- Cảnh báo nếu có uncommitted changes: "Có thay đổi chưa commit — image sẽ build từ file đang có
  trên disk (bao gồm cả thay đổi chưa commit). Tiếp tục không?"
- Nếu không có uncommitted changes → tiến hành ngay, không hỏi.

### Bước 2 — Build image

```bash
cd /home/toan/my-nvr
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1
docker compose build nvr-wrapper
```

- Nếu fail → hiện 30 dòng log cuối, dừng, báo lỗi.
- Nếu OK → tiếp bước 3.

### Bước 3 — Tag và Push lên GHCR

```bash
# Lấy image ID vừa build (override file dùng image: nvr-wrapper:latest trên dev)
# Tag thành staging trên GHCR
docker tag nvr-wrapper:latest ghcr.io/toannice/nvr:staging

# Push (cần GITHUB_TOKEN đã docker login ghcr.io)
docker push ghcr.io/toannice/nvr:staging
```

**Nếu push fail với `unauthorized` / `denied`:**

```bash
# Lấy token từ .env và login lại
source /home/toan/my-nvr/.env
echo "$GITHUB_TOKEN" | docker login ghcr.io -u toannice --password-stdin
# Rồi push lại
docker push ghcr.io/toannice/nvr:staging
```

Nếu vẫn fail → hướng dẫn người dùng: "Chạy `! echo <token> | docker login ghcr.io -u toannice --password-stdin` rồi thử lại `/staging-push`."

### Bước 4 — Xác nhận push thành công

```bash
# Kiểm tra digest của tag vừa push
docker inspect ghcr.io/toannice/nvr:staging --format='{{.RepoDigests}}'
```

Lấy thông tin commit để báo cáo:

```bash
git log --oneline -1
git log -1 --format="%H %s" 
```

### Bước 5 — Báo kết quả

Hiện tóm tắt:
```
✅ Đã push staging thành công!

Image  : ghcr.io/toannice/nvr:staging
Commit : <hash> <message>
Thời gian push: <timestamp>

Các máy field có WRAPPER_TAG=staging sẽ tự OTA lúc 3h sáng.
Muốn force OTA ngay: liên hệ máy field chạy lệnh watchdog trigger (xem watchdog.yml).
```

---

## Lưu ý quan trọng

- **Tag `staging`** dành cho máy beta/field test. Tag `latest` là stable prod — KHÔNG push `latest`
  trong skill này trừ khi người dùng yêu cầu rõ ràng.
- **`WRAPPER_TAG` là 1 nguồn sự thật:** máy field muốn dùng staging → set `WRAPPER_TAG=staging`
  trong `.env` của máy đó. Watchdog OTA + rollback đều đọc đúng tag này.
- Image build từ **file đang có trên disk** (kể cả uncommitted changes) — cảnh báo nếu có dirty
  working tree.
- Push `latest` (stable) chỉ khi merge vào `main` — đó là workflow CI chính thức, KHÔNG làm thủ
  công trong skill này.
- GHCR token nằm trong `.env` (`GITHUB_TOKEN`). Token hết hạn → đăng nhập lại như hướng dẫn trên.

## Kênh OTA

| Tag | Kênh | Máy nào dùng |
|-----|------|--------------|
| `staging` | Beta / Field test | Máy có `WRAPPER_TAG=staging` trong `.env` |
| `latest` | Stable prod | Mặc định mọi máy prod (không set WRAPPER_TAG) |
| `vX.Y.Z` | Pin cứng | Máy cần giữ đúng một phiên bản |

Watchdog chạy OTA lúc **3h sáng** (`WATCHTOWER_SCHEDULE="0 0 3 * * *"`). Sau khi push staging,
máy field sẽ tự cập nhật tối hôm đó.
