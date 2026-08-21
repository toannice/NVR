---
name: dev-deploy
description: >
  Build lại nvr-wrapper từ source code hiện tại và deploy lên máy dev (local). Dùng sau mỗi
  lần sửa code để xem kết quả ngay. Kích hoạt khi người dùng nói: "chạy lại NVR", "build lại",
  "deploy dev", "update code và chạy", "rebuild", "/dev-deploy", hoặc bất kỳ yêu cầu nào muốn
  áp dụng code mới lên môi trường local đang chạy.
---

# Dev Deploy — Build & Chạy NVR Local

Skill này build lại `nvr-wrapper` từ source rồi deploy lên stack đang chạy trên máy dev.
Tương đương `./run.sh --rebuild` nhưng với output rõ ràng hơn và hỏi xác nhận trước khi chạy.

## Quy trình

### Bước 1 — Kiểm tra nhanh trạng thái hiện tại

Chạy song song:

```bash
# Xem stack đang chạy container nào
docker compose ps --format "table {{.Name}}\t{{.Status}}"

# Xem có thay đổi code nào chưa commit không (để người dùng biết đang build gì)
git diff --stat HEAD | tail -5
git log --oneline -3
```

Hiện kết quả ngắn gọn cho người dùng: container nào đang UP/DOWN, commit hiện tại là gì.

### Bước 2 — Xác nhận và Build

Thông báo cho người dùng: "Sẽ build lại `nvr-wrapper` từ source (dùng BuildKit cache — chỉ
recompile phần đã đổi) rồi recreate container. Thời gian ~1–3 phút tuỳ lượng thay đổi."

Sau đó chạy build (KHÔNG hỏi thêm, bắt đầu ngay):

```bash
cd /home/toan/toanDoc/Documents/my-nvr
export DOCKER_BUILDKIT=1
export COMPOSE_DOCKER_CLI_BUILD=1
docker compose build nvr-wrapper
```

- Nếu build fail → hiện 30 dòng log cuối, dừng, báo lỗi cụ thể.
- Nếu build OK → tiếp bước 3.

### Bước 3 — Deploy và kiểm tra sức khoẻ

```bash
cd /home/toan/toanDoc/Documents/my-nvr
# Recreate nvr-wrapper với image mới build
docker compose up -d --force-recreate --no-deps nvr-wrapper
```

Sau đó poll health (tối đa 60s, check mỗi 3s):

```bash
for i in $(seq 1 20); do
    API=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/api/health 2>/dev/null)
    ROOT=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/ 2>/dev/null)
    echo "  [${i}] API=${API} UI=${ROOT}"
    [ "$API" = "200" ] && [ "$ROOT" = "200" ] && break
    sleep 3
done
```

**Xử lý kết quả health check:**

| API | UI | Hành động |
|-----|-----|-----------|
| 200 | 200 | ✅ OK — tiếp bước 4 |
| 200 | !200 | File tĩnh treo → `docker compose restart nvr-wrapper` rồi poll lại 30s |
| !200 | any | Backend không lên → hiện `docker compose logs nvr-wrapper --tail=40`, dừng |

Sau cùng đảm bảo nginx chạy (luôn chạy lệnh này dù wrapper OK hay không):

```bash
docker compose up -d nginx
```

### Bước 4 — Báo kết quả

Lấy IP máy: `hostname -I | awk '{print $1}'`

Hiện tóm tắt:
- Trạng thái: OK / DEGRADED / FAILED
- URL truy cập: `https://<IP>`
- Commit đang chạy: `git log --oneline -1`
- Nếu có lỗi: gợi ý lệnh xem log `docker compose logs nvr-wrapper --tail=50`

---

## Lưu ý quan trọng

- **KHÔNG dùng `--no-cache`** trừ khi người dùng yêu cầu rõ. Cache BuildKit là chủ ý — làm
  nhanh vòng lặp dev.
- **Chỉ build nvr-wrapper**, KHÔNG pull lại frigate/mosquitto/nginx (tốn thời gian vô ích).
- Nếu người dùng muốn rebuild sạch không cache: chạy `docker compose build --no-cache nvr-wrapper`.
- Working directory LUÔN là `/home/toan/toanDoc/Documents/my-nvr` (không cd lung tung).
