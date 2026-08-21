# Runbook sự cố cập nhật

Viết cho người đang ngồi trước máy có vấn đề. Mỗi mục: **triệu chứng → nguyên nhân → làm gì**.

---

## 0. Ba lệnh xem trước tiên

```bash
cd /opt/nvr        # máy dev: cd ~/toanDoc/Documents/my-nvr

cat data/ota/status.json | jq .              # đang ở pha nào, lỗi gì
cat data/ota/state.json  | jq .              # version đang cài, sàn, lịch sử
journalctl -u nvr-ota-apply.service -n 100   # host script nói gì
```

`status.json` là **thứ quan trọng nhất**: lúc apply, container watchdog bị recreate nên
`docker logs nvr-watchdog` **mất hết** dòng trước đó. Nhật ký từng bước được lưu trong
`status.json` chính vì vậy.

---

## 1. 🚨 `ROLLBACK_FAILED` — nghiêm trọng nhất

**Triệu chứng:** Telegram báo `NGHIÊM TRỌNG: cập nhật thất bại VÀ không lùi được`. Máy có thể
đang **không ghi hình**.

### Kiểm nhanh máy còn sống mức nào

```bash
docker compose ps                            # container nào lên, container nào không
curl -s localhost:3000/api/health            # wrapper còn không
curl -s localhost:5000/api/version           # frigate còn không
```

### Nguyên nhân hay gặp nhất: image cũ đã bị xoá

```bash
docker images | grep nvr-ota-rollback
```

Trống nghĩa là image ghim để lùi **đã bị xoá** — thường do `docker system prune` chạy giữa hai
lần cập nhật (`SelfHealingEngine` có dọn đĩa khi > 88%). Không còn gì để lùi về.

**Xử lý:** build lại tại chỗ từ source:

```bash
git -C /opt/nvr log --oneline -5             # tìm commit của bản đang cần
docker compose build nvr-wrapper
docker compose up -d --force-recreate --no-deps nvr-wrapper nginx
```

Không có source trên máy → dùng bundle offline (`deploy-offline.sh`) hoặc upload lại gói của
bản cũ qua UI (nếu UI còn lên).

### "Đã lùi thành công" nhưng máy vẫn chạy bản mới

**Triệu chứng:** `status.json` báo `ROLLED_BACK`, nhưng `/api/version` vẫn là version của bản
vừa lỗi.

**Nguyên nhân:** tag image (`nvr-wrapper:latest`) đã bị đổi **ngoài đường OTA** trước lúc apply
— điển hình là ai đó chạy `docker compose build` trên máy, vì build gán thẳng vào tag đó. Lúc
snapshot, "image hiện tại" đã là image mới rồi, nên tag ghim để lùi trỏ vào chính nó và
`docker tag` lúc rollback thành lệnh không làm gì.

**Đã được xử lý từ 21-08-2026:** `state.json` giờ ghi `committedImages` (tag → image id thật
tại lần commit), và snapshot ghim theo ID đó chứ không theo tag hiện tại. Khi phát hiện lệch,
log ghi `SNAPSHOT_TAG_DRIFT` và `status.json` có dòng *"đã bị đổi ngoài OTA — ghim đường lùi
theo bản đã commit"*.

Nếu vẫn gặp trên máy chạy bản watchdog cũ hơn:

```bash
cat data/ota/state.json | jq .committedImages     # trống = bản watchdog cũ
docker images | grep nvr-ota-rollback              # ID có trùng nvr-wrapper:latest?
```

Trùng nghĩa là không còn image cũ trên máy — dùng mục 1 (build lại từ source, hoặc load lại gói
của bản cũ).

### Lùi bằng tay khi image ghim còn

```bash
cat data/ota/rollback/plan.json | jq .

# Với mỗi cặp trong plan:
docker tag nvr-ota-rollback/nvr-wrapper:1.0.0.3-prev nvr-wrapper:latest

# Trả file cấu hình cũ
cp -r data/ota/rollback/files/* /opt/nvr/

docker compose up -d --force-recreate --no-deps nvr-wrapper nvr-watchdog
docker compose up -d nginx        # ĐỪNG QUÊN — nginx không tự start
```

> **nginx không tự start** khi recreate `nvr-wrapper`. Quên là site unreachable dù backend khoẻ.

---

## 2. Site không vào được sau cập nhật, nhưng backend khoẻ

**Triệu chứng:** `https://<ip>` không mở, mà `curl localhost:3000/api/health` trả `ok`.

**Nguyên nhân:** nginx không chạy.

```bash
docker compose up -d nginx
docker compose logs --tail=50 nginx
```

Nếu nginx crash: gói vừa rồi có thể mang `nginx/nginx.conf` sai cú pháp.

```bash
docker compose exec nginx nginx -t                  # nếu container còn sống
cp data/ota/rollback/files/nginx/nginx.conf nginx/  # trả bản cũ
docker compose up -d --force-recreate nginx
```

---

## 3. Live view đen toàn bộ sau cập nhật

**Nguyên nhân số một:** `FRIGATE_HOST_IP` trong `.env` lệch IP thật. Compose publish cổng
restream theo biến đó; lệch thì Docker báo `cannot assign requested address`, Frigate không lên
→ mất **cả live view lẫn ghi hình** — trong khi container khác vẫn `healthy` nên nhìn bảng trạng
thái không thấy sai ở đâu.

```bash
./scripts/nvr-ip-guard.sh --env-only     # dò IP thật, sửa .env
docker compose up -d frigate nvr-wrapper nginx
```

`ota-apply.sh` gọi sẵn bước này, nhưng nếu nó chết trước đó thì phải chạy tay.

---

## 4. Máy từ chối gói — từng mã lý do

Xem `errorCode` trong `status.json`. **Từ chối nghĩa là máy CHƯA bị chạm gì** — vẫn đang chạy
bản cũ, không cần cứu.

| Mã | Nghĩa | Làm gì |
|---|---|---|
| `SIG_INVALID` | Chữ ký không khớp khoá nào trên máy | Gói bị sửa, hoặc ký bằng khoá khác. Kiểm `ls /opt/nvr/keys/ota-trust.d/` |
| `NO_TRUSTED_KEY` | Keyring rỗng | `sudo ./scripts/install-ota.sh` |
| `MODEL_MISMATCH` | Gói của model khác | Tải đúng gói. Kiểm `grep DEVICE_MODEL .env` |
| `BELOW_FLOOR` | Gói cũ hơn sàn chống hạ cấp | **Cố ý** — xem mục 5 nếu thật sự cần hạ cấp |
| `MINFROM_NOT_MET` | Máy quá cũ để nhảy bậc | Cài bản trung gian trước (lý do ghi rõ bản nào) |
| `MANIFEST_EXPIRED` | Manifest hết hạn | Máy tắt lâu, hoặc mạng bị chặn, hoặc bản phát hành quá cũ |
| `DISK_INSUFFICIENT` | Không đủ trống | Xoá gói cũ trong `data/ota/incoming/`, dọn ghi hình |
| `PRECONDITION_UNHEALTHY` | Máy đang không khoẻ | Sửa lỗi hiện tại trước. Apply lúc này thì không phân biệt được "bản mới lỗi" với "máy vốn đã lỗi" |
| `OVERLAY_PATH_DENIED` | Gói muốn ghi file không được phép | **Gói này không đáng tin — đừng cài** |
| `HIGH_RISK_NEEDS_OPT_IN` | Gói `risk=high` không khai `autoApply` | Áp tay qua UI, hoặc phát hành lại với `--auto-apply` |
| `MANIFEST_MISMATCH` | Gói và manifest khác bản phát hành | Server đang có file lệch nhau — deploy lại đủ 3 file |

---

## 5. Hạ `floorVersion` (cần có mặt tại máy)

**Chỉ làm khi thật sự cần cài bản cũ hơn** — ví dụ bản mới có lỗi nặng và bạn muốn về bản trước.

Hiểu rõ trước khi làm: sàn tồn tại để chặn **rollback attack** — một gói cũ do chính bạn ký, chữ
ký hợp lệ, nhưng là bản **trước khi vá lỗ hổng**. Hạ sàn là tự mở lại cửa đó.

```bash
cp data/ota/state.json data/ota/state.json.bak.$(date +%s)

# Sửa floorVersion xuống mức cần
docker exec nvr-wrapper sh -c \
  'cd /app/data/ota && jq ".floorVersion = \"1.0.0.0\"" state.json > s.tmp && mv s.tmp state.json'

docker compose restart nvr-watchdog
cat data/ota/state.json | jq .floorVersion
```

> File trong `data/ota/` do container ghi nên thuộc **root** — sửa qua `docker exec` hoặc `sudo`.

Sau khi cài xong bản cũ, sàn sẽ tự nâng lại lên bản đó khi `COMMITTED`.

---

## 6. Cập nhật treo giữa đường

**Triệu chứng:** `status.json` ở `SWAPPING` (hoặc `HEALTH_CHECK`) rất lâu, UI hiện "đang xử lý"
mãi không đổi.

```bash
systemctl status nvr-ota-apply.path       # phải active (waiting)
systemctl status nvr-ota-apply.service    # đang chạy? hay đã chết?
ls -la data/ota/trigger                   # còn file này = chưa ai tiêu thụ
journalctl -u nvr-ota-apply.service -n 200
```

| Tình huống | Xử lý |
|---|---|
| `nvr-ota-apply.path` không active | `sudo ./scripts/install-ota.sh` — chưa cài systemd unit. **Watchdog vẫn verify + load được nhưng không ai đổi container** |
| `trigger` còn mà service không chạy | `sudo systemctl start nvr-ota-apply.service` |
| Service chết giữa đường | Đọc journal tìm lý do, rồi lùi tay theo mục 1 |
| `apply.lock` bị giữ | `flock` tự nhả khi tiến trình chết. Còn giữ = còn tiến trình: `ps aux \| grep ota-apply` |

### Đưa về trạng thái sạch

```bash
docker exec nvr-wrapper sh -c 'rm -rf /app/data/ota/staged /app/data/ota/trigger /app/data/ota/apply-plan.json'
docker compose restart nvr-watchdog
```

Watchdog sẽ ghi lại `status.json` về `IDLE` khi khởi động.

---

## 7. Máy không tự tìm bản mới

```bash
# Cấu hình có bật?
grep -A3 '^ota:' config/watchdog.yml
grep -E 'DEVICE_MODEL|OTA_' .env

# Watchdog nói gì lúc khởi động
docker compose logs nvr-watchdog | grep -iE 'OTA_|KEYRING|MANIFEST'

# Thử tay, không chờ 2h sáng
curl -s -X POST localhost:3000/api/ota/check-now    # cần cookie session
```

| Log | Nghĩa |
|---|---|
| `OTA_DISABLED` | `ota.enabled: false` — chỉ nhận upload tay |
| `OTA_NO_MODEL` | Thiếu `DEVICE_MODEL` → **mọi gói bị từ chối** |
| `OTA_NO_KEYS` / `KEYRING_EMPTY` | Chưa chạy `install-ota.sh` |
| `MANIFEST_UNAVAILABLE` | Không tới được server (mạng, 404, thiếu file `.sig`) |
| `Không có bản mới (manifest không đổi)` | Bình thường — ETag/Last-Modified khớp |

**Nhớ jitter:** máy chỉ gọi trong khoảng `hour:00` đến `hour:jitter_minutes`, ở một phút cố định
tính theo hostname. Xem lúc 02:05 mà máy chưa gọi là **bình thường** nếu phút của nó là 02:30.

---

## 8. Ổ đầy vì gói cũ

```bash
du -sh data/ota/*
```

| Thư mục | Xoá được? |
|---|---|
| `incoming/` | **Có** — gói đã upload. Xoá qua UI hoặc `docker exec nvr-wrapper rm /app/data/ota/incoming/xxx.nvrup` |
| `staged/` | Có, nếu không đang cập nhật |
| `rollback/` | **KHÔNG** — đây là đường lùi của lần cập nhật gần nhất |
| Image `nvr-ota-rollback/*` | **KHÔNG** — xoá là mất đường lùi (xem mục 1) |

---

## 9. Đường cuối: dựng lại từ bundle

Khi máy không còn cứu được bằng các cách trên:

```bash
tar -xzf nvr-bundle-YYYYMMDD.tar.gz
cd nvr-bundle-YYYYMMDD
sudo ./deploy-offline.sh
```

Giữ nguyên `data/` và `media/` để không mất camera lẫn ghi hình. Sau đó chạy lại
`sudo ./scripts/install-ota.sh`.
