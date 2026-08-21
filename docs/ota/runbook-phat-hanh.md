# Runbook: phát hành một bản cập nhật

Checklist chạy từ trên xuống. Định dạng gói: [dinh-dang-goi.md](dinh-dang-goi.md).
Khoá và máy air-gap: [ky-va-may-air-gap.md](ky-va-may-air-gap.md).

---

## 0. Chuẩn bị (một lần cho mỗi máy dev)

```bash
sudo apt install -y zstd jq            # build-package.sh cần
ls ota/keys/*.pub                      # phải có ota-primary.pub + ota-backup.pub
grep DEVICE_MODEL .env                 # phải có, không được rỗng
```

---

## 1. Bump version và build image

```bash
echo 1.1.0.0 > VERSION
docker compose build nvr-wrapper
# Nếu có sửa watchdog:
docker compose build nvr-watchdog
```

> `build-package.sh` **đối chiếu** `VERSION` nướng trong image với version đang đóng gói và dừng
> nếu lệch. Quên bump `VERSION` mà vẫn đóng gói thì `/api/version` trên máy field báo sai, health
> check `expectJson` thất bại, và cả fleet **tự rollback** — mất một đêm vì một lệnh `echo`.

---

## 2. Dựng gói (chưa ký)

```bash
./ota/build-package.sh 1.1.0.0 \
    --min-from 1.0.0.0 \
    --expires-days 180 \
    --description "Sửa live view H.265, thêm tab Cập nhật"
```

Thêm cờ khi cần:

| Cờ | Khi nào |
|---|---|
| `--with-watchdog` | Có sửa code watchdog (kể cả sửa chính logic OTA) |
| `--frigate 0.17.0 --risk high` | Nâng Frigate. **Đọc mục 6 trước** |
| `--auto-apply` | Cho phép gói `risk=high` tự áp lúc 2h sáng |
| `--min-applier 1.2.0` | Gói dùng tính năng chỉ watchdog mới có |

### Chọn `--min-from` thế nào

Trả lời một câu: *"bản này có xoá/đổi migration nào mà máy cũ cần đi qua không?"*

- Không có gì đặc biệt → để trống hoặc đặt bằng bản cũ nhất còn ngoài field
- **Có xoá code migration** → đặt `minFrom` = bản đầu tiên mà migration đó đã chạy xong

Ví dụ: nếu 1.1 xoá `migrateCloudCamerasIntoCameras()`, thì `--min-from` phải là bản đầu tiên
có hàm đó chạy đủ, để máy cũ hơn bị chặn lại thay vì mất sạch camera cloud **im lặng**
(health vẫn 200 nên rollback không kích hoạt).

---

## 3. Ký (máy air-gap)

```bash
# Máy dev — CHỈ copy manifest.json, ~2KB
cp releaseVersion/ota/1.1.0.0/manifest.json /media/usb/
```

```bash
# MÁY AIR-GAP
bash sign-manifest.sh /media/usb/manifest.json
```

Đọc lại trên màn hình rồi **gõ lại đúng version** để xác nhận. Ba điểm dễ sai:

- [ ] `version` đúng bản vừa build (không phải bản cũ còn sót trên USB)?
- [ ] `minFrom` chặn đúng những máy cần đi qua bản trung gian?
- [ ] `risk` / `autoApply` đúng ý?

```bash
# Máy dev
cp /media/usb/manifest.json.sig releaseVersion/ota/1.1.0.0/
```

---

## 4. Đóng gói và kiểm

```bash
./ota/finalize-package.sh releaseVersion/ota/1.1.0.0
./ota/verify-package.sh  releaseVersion/ota/nvr-AIVIEW-NVR-X1-1.1.0.0.nvrup
./verify-bundle.sh       releaseVersion/ota/nvr-AIVIEW-NVR-X1-1.1.0.0.nvrup
```

| Script | Kiểm gì |
|---|---|
| `finalize-package.sh` | Chữ ký + sha256 + whitelist trên **thư mục gói**, rồi nén |
| `verify-package.sh` | Y như trên nhưng trên **file đã nén** — bắt cả lỗi ở bước tar |
| `verify-bundle.sh` | Quét secret (PAT, mật khẩu camera, IP nội bộ, private key) |

Cả ba phải xanh. `verify-package.sh` phải in `══ GÓI HỢP LỆ ══`.

Nếu thấy `! ký bằng KHOÁ DEV` thì gói này **chỉ để test**, không giao khách.

---

## 5. Đưa ra máy field

### A. Upload tay tại hiện trường (không cần internet)

```
copy nvr-AIVIEW-NVR-X1-1.1.0.0.nvrup ra USB
→ mở https://<ip-máy> → Thiết lập → Cập nhật
→ kéo file vào → bấm Áp dụng
```

Xem tiến trình ngay trên trang đó. Máy tự lùi về bản cũ nếu bản mới không lên.

### B. OTA tự động (2h sáng)

Đưa **cả 3 file** trong `releaseVersion/ota/deploy-1.1.0.0/` lên
`https://aiview.bkav.com/download/<MODEL>/OTA/`:

```
manifest.json                        ← đã ký, byte-identical với bản trong gói
manifest.json.sig
nvr-AIVIEW-NVR-X1-1.1.0.0.nvrup
```

> **Thiếu file `.sig` là máy từ chối im lặng** (`MANIFEST_UNAVAILABLE`) — nó không coi đó là tấn
> công nên không báo Telegram, và bạn sẽ tưởng fleet đã cập nhật.

> **`manifest.json` phải là file đã ký, không phải bản sửa lại.** Sửa dù một dấu cách là chữ ký
> sai, và máy báo `SIG_INVALID` kèm Telegram — trông y như đang bị tấn công.

Bật server gửi `ETag` (nginx bật sẵn) để đêm không có bản mới tốn ~0 byte. Không có ETag thì
`Last-Modified` cũng được — máy hỗ trợ cả hai.

### Rải theo đợt cho fleet đông

Không có cơ chế canary trong máy. Cách làm: đưa file lên, chờ 1–2 đêm, đọc Telegram, rồi mới
tính là đã phát hành xong. Máy nào lỗi sẽ tự rollback và báo về.

---

## 6. Riêng khi nâng Frigate

Rủi ro cao hơn hẳn: `config.yml` sai một dòng = Frigate crash-loop = **mất cả live view lẫn ghi
hình**.

- [ ] Đọc release note của Frigate xem `config.yml` có đổi schema
- [ ] Nếu đổi: viết `hooks/pre-apply.sh` để migrate config, đặt vào thư mục gói **trước khi** ký
- [ ] Dùng `--risk high`. **Không** `--auto-apply` cho lần đầu — áp tay lên một máy trước
- [ ] Test trên máy dev, xác nhận **mọi camera quay lại online**, không chỉ Frigate lên
- [ ] Gói này to (~1.2GB tarball) — nếu đi đường registry theo digest thì chỉ tải layer thay đổi

Frigate đi bằng `source: registry` + digest, pull **anonymous** (`DOCKER_CONFIG` trỏ thư mục
rỗng nên không thể lỡ gửi credential). Ghim tag thuần bị từ chối.

---

## 7. Sau khi phát hành

- [ ] Theo dõi Telegram 2 đêm
- [ ] Máy báo `MINFROM_NOT_MET` → cần phát hành bản trung gian cho nhóm máy đó
- [ ] Máy báo `MANIFEST_EXPIRED` → máy đó đã tắt lâu, hoặc mạng bị chặn
- [ ] Máy báo `ROLLBACK_FAILED` → **vào máy ngay**, xem [runbook-su-co.md](runbook-su-co.md)
- [ ] Ghi mục vào `ChangeLog/NVR.md` theo khuôn CLAUDE.md mục 0.2

---

## Bảng tra lỗi build

| Lỗi | Nguyên nhân |
|---|---|
| `image nướng VERSION='x' nhưng đang đóng gói 'y'` | Quên `echo y > VERSION` rồi build lại |
| `thiếu DEVICE_MODEL trong .env` | Thêm `DEVICE_MODEL=AIVIEW-NVR-X1` |
| `chữ ký KHÔNG khớp public key nào` | Ký bằng khoá không có `.pub` trong `ota/keys/`, hoặc manifest bị sửa **sau khi** ký |
| `gói được ký bằng KHOÁ DEV` | Đúng như vậy — ký lại bằng `ota-primary` trên máy air-gap |
| `có đường dẫn overlay ngoài whitelist` | `build-package.sh` thêm file không được phép; xem [dinh-dang-goi.md](dinh-dang-goi.md) mục 4 |
| `payload không khớp manifest` | Sửa file trong thư mục gói sau khi sinh manifest — build lại từ đầu |
