# OTA — phát hành và ký gói cập nhật NVR

Thư mục này chứa công cụ **phía nhà phát hành**. Phần chạy trên máy khách nằm ở
[watchdog/.../ota/](../watchdog/src/main/kotlin/vn/aiview/watchdog/ota/) và `scripts/ota-apply.sh`.

Hai đường đưa bản mới ra máy field, **dùng chung một định dạng gói và một bộ verify**:

| | Đường | Khi nào |
|---|---|---|
| A | Kỹ thuật viên upload `.nvrup` qua UI LAN (`https://<ip>` → Thiết lập → Cập nhật) | Tại hiện trường, không cần internet |
| B | Máy tự tải từ `https://aiview.bkav.com/download/<MODEL>/OTA/manifest.json` lúc 2h sáng | Fleet, tự động, có rollback |

---

## Chu trình phát hành

```
┌─ MÁY DEV ──────────────────────────────────────────────────────────┐
│  1.  echo 1.1.0.0 > VERSION                                        │
│      docker compose build nvr-wrapper                              │
│  2.  ./ota/build-package.sh 1.1.0.0 --min-from 1.0.0.0             │
│         → releaseVersion/ota/1.1.0.0/  (manifest.json + payload)    │
│  3.  cp releaseVersion/ota/1.1.0.0/manifest.json /media/usb/        │
└────────────────────────────────┬───────────────────────────────────┘
                          USB — chỉ ~2KB
┌─ MÁY AIR-GAP ──────────────────▼───────────────────────────────────┐
│  4.  bash sign-manifest.sh /media/usb/manifest.json                │
│         → in ra model/version/minFrom cho MẮT NGƯỜI đọc lại        │
│         → gõ lại version để xác nhận                               │
│         → manifest.json.sig                                        │
└────────────────────────────────┬───────────────────────────────────┘
                          USB — chỉ ~140 byte
┌─ MÁY DEV ──────────────────────▼───────────────────────────────────┐
│  5.  ./ota/finalize-package.sh releaseVersion/ota/1.1.0.0          │
│         → nvr-<MODEL>-1.1.0.0.nvrup  +  deploy-1.1.0.0/            │
│  6.  ./ota/verify-package.sh releaseVersion/ota/nvr-*.nvrup        │
│  7.  ./verify-bundle.sh releaseVersion/ota/nvr-*.nvrup   (quét secret)│
│  8.  upload 3 file trong deploy-1.1.0.0/ lên domain                │
└────────────────────────────────────────────────────────────────────┘
```

**Điểm cốt lõi: chỉ `manifest.json` (~2KB) đi qua máy air-gap.** Image 200MB–3GB không bao giờ
chạm máy giữ khoá ký. Làm được vậy vì chữ ký chỉ phủ `manifest.json`, mà manifest lại chứa
sha256 của từng file payload — chữ ký phủ toàn bộ gói một cách **bắc cầu**, cùng mô hình với
file `Release` của apt.

---

## File trong thư mục này

| File | Chạy ở đâu | Việc |
|---|---|---|
| `airgap-setup.sh` | **Máy air-gap**, một lần | Sinh cặp khoá `ota-primary` + `ota-backup`, tự kiểm, sinh bản in giấy |
| `sign-manifest.sh` | **Máy air-gap**, mỗi lần phát hành | Ký `manifest.json`, có bước cho người đọc lại |
| `build-package.sh` | Máy dev | `docker save` + zstd, sinh `manifest.json` (chưa ký) |
| `finalize-package.sh` | Máy dev | Verify chữ ký + sha256 + whitelist, rồi nén thành `.nvrup` |
| `verify-package.sh` | Máy dev / bất kỳ | Kiểm một file `.nvrup` **đúng cách máy field sẽ kiểm** |
| `lib/overlay-whitelist.sh` | (thư viện) | Whitelist đường dẫn overlay, **trích thẳng từ `OtaLayout.kt`** |
| `keys/*.pub` | commit vào repo | Khoá công khai máy field dùng để đối chiếu |
| `.dev-keys/` | gitignored | Khoá DEV để chạy thử chu trình. **Không bao giờ dùng cho bản thật** |

---

## Định dạng `.nvrup`

`.nvrup` **là một tar.gz** — đuôi chỉ là nhãn. `tar -tzf x.nvrup` chạy bình thường.

```
manifest.json              ← chữ ký phủ ĐÚNG file này (byte thô)
manifest.json.sig          ← {"keyId","alg":"ed25519","sig":"<base64>"}
images/nvr-wrapper.tar.zst
images/nvr-watchdog.tar.zst
files/docker-compose.yml   ← overlay lên PROJECT_DIR, đường dẫn bị whitelist
files/nginx/nginx.conf
hooks/pre-apply.sh         ← tuỳ chọn
```

Nén bằng **zstd -19**. Đo thật trên `nvr-wrapper`: raw 434MB → gzip 173MB → **zstd-19 134MB**
(nhỏ hơn gzip 23%, tức ~39MB mỗi bản trên đường truyền field). `docker load` tự nhận zstd —
daemon trên host giải nén, không phải CLI trong container — nên **không cần thêm binary nào
vào image**.

Đặc tả đầy đủ từng trường: [docs/ota/dinh-dang-goi.md](../docs/ota/dinh-dang-goi.md).

---

## Bốn gate, và vì sao chữ ký không đủ

> Chữ ký chứng minh: *"file này đúng là do tôi phát hành, không ai sửa một byte."*
> Chữ ký **không** chứng minh: *"file này nên được cài lên máy này, lúc này."*

| Gate | Trường | Chặn cái gì |
|---|---|---|
| 1 | `minFrom` | Máy quá cũ nhảy bậc qua một migration một chiều → mất dữ liệu **im lặng** (health vẫn 200 nên rollback không kích hoạt) |
| 2 | `floorVersion` (trên máy) | **Rollback attack**: gói cũ do chính bạn ký, chữ ký hợp lệ, nhưng là bản trước khi vá lỗ hổng |
| 3 | `expiresAt` | **Freeze attack**: kẻ chặn mạng giữ nguyên manifest cũ mãi mãi, máy tưởng "không có bản mới" và không bao giờ nhận bản vá |
| 4 | `minApplier` | Watchdog cũ không hiểu gói mới → apply **thiếu bước** rồi báo thành công |

Chi tiết từng gate: [docs/ota/dinh-dang-goi.md](../docs/ota/dinh-dang-goi.md) và chú thích trong
[OtaGates.kt](../watchdog/src/main/kotlin/vn/aiview/watchdog/ota/OtaGates.kt).

---

## Whitelist đường dẫn overlay

Gói cập nhật ghi file vào `PROJECT_DIR` với quyền root. Nên `manifest.files[].path` bị
**whitelist** (không phải blacklist — blacklist thì luôn thiếu một biến thể nào đó).

Nguồn duy nhất: `ALLOWED_OVERLAY_PREFIXES` trong
[OtaLayout.kt](../watchdog/src/main/kotlin/vn/aiview/watchdog/ota/OtaLayout.kt).
`lib/overlay-whitelist.sh` **trích thẳng từ file Kotlin đó** thay vì giữ bản copy — vì hai bản
danh sách riêng sẽ lệch nhau theo hướng tệ nhất: bash nói "hợp lệ", máy field từ chối ở tận
hiện trường.

**Cố tình KHÔNG có trong whitelist:** `.env` (mật khẩu MQTT + token Telegram của máy),
`secrets/`, `media/` + `logs/` (dữ liệu khách), `data/*` trừ đúng file `dc-bridge`
(`data/nvr.db` = camera + hash mật khẩu, `data/session_secret` = ghi được là giả mạo được
session, `data/ota/state.json` = ghi được là xoá được sàn chống hạ cấp).

Whitelist là **lớp phòng thủ thứ hai cho tình huống khoá ký bị lộ** — lúc đó chữ ký không còn
bảo vệ được gì. Đã chứng minh bằng test tấn công: gói ký hợp lệ nhưng khai `path: ".env"` bị
chặn ở cả `verify-package.sh` và `OtaLayout.isAllowedOverlayPath()`.

---

## Chốt chống khoá dev lọt ra máy khách

Máy khách tin khoá dev nghĩa là **ai chạm được máy dev cũng đẩy được bản cập nhật vào máy
khách đó**. Ba lớp chặn:

1. Private key dev ở `ota/.dev-keys/` — gitignored
2. `finalize-package.sh` **từ chối** gói ký bằng khoá tên `ota-dev*`, trừ khi `OTA_ALLOW_DEV_KEY=1`
3. `scripts/install-ota.sh` **từ chối cài** khoá `ota-dev*` vào máy, trừ khi `OTA_ALLOW_DEV_KEY=1`

---

## Chạy thử chu trình khi chưa có máy air-gap

Air-gap là tính chất **vật lý/quy trình**, không phải tính chất của code — `sign-manifest.sh`
không biết và không cần biết nó đang chạy ở đâu. Nên chu trình chạy đủ trên một máy:

```bash
./ota/build-package.sh 1.0.0.4 --min-from 1.0.0.0
echo "1.0.0.4" | bash ota/sign-manifest.sh releaseVersion/ota/1.0.0.4/manifest.json \
    --key ota/.dev-keys/ota-dev.key
OTA_ALLOW_DEV_KEY=1 ./ota/finalize-package.sh releaseVersion/ota/1.0.0.4
./ota/verify-package.sh releaseVersion/ota/nvr-AIVIEW-NVR-X1-1.0.0.4.nvrup
```

Khi có hardware: chạy `airgap-setup.sh` trên máy đó, thay `ota/keys/*.pub`, rebuild.
**Không sửa dòng code nào.**

Runbook đầy đủ: [docs/ota/runbook-phat-hanh.md](../docs/ota/runbook-phat-hanh.md).
Dựng máy air-gap + sao lưu khoá + rotate: [docs/ota/ky-va-may-air-gap.md](../docs/ota/ky-va-may-air-gap.md).
