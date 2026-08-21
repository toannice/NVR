# Định dạng gói cập nhật `.nvrup`

Đặc tả kỹ thuật. Quy trình phát hành: [runbook-phat-hanh.md](runbook-phat-hanh.md).
Dựng máy air-gap và quản khoá: [ky-va-may-air-gap.md](ky-va-may-air-gap.md).

---

## 1. Cấu trúc gói

`.nvrup` **là một tar.gz**. Đuôi chỉ là nhãn cho người đọc và cho UI biết đây là gói cập nhật
NVR — không phải định dạng mới. `tar -tzf x.nvrup` chạy bình thường.

```
manifest.json              ← chữ ký Ed25519 phủ ĐÚNG file này (byte thô)
manifest.json.sig          ← {"keyId":"…","alg":"ed25519","sig":"<base64 64 byte>"}
images/
  nvr-wrapper.tar.zst      ← docker save, nén zstd -19
  nvr-watchdog.tar.zst
files/                     ← overlay lên PROJECT_DIR, đường dẫn bị whitelist
  docker-compose.yml
  nginx/nginx.conf
  config/watchdog.yml
  VERSION
  scripts/nvr-ip-guard.sh
  data/dc-bridge
hooks/
  pre-apply.sh             ← tuỳ chọn, chạy TRƯỚC khi đổi container
  post-apply.sh            ← tuỳ chọn, chạy SAU khi health-check đạt
```

**Chữ ký chỉ phủ `manifest.json`.** Manifest lại chứa sha256 của từng file payload, nên chữ ký
phủ toàn bộ gói một cách **bắc cầu** — cùng mô hình với file `Release` của apt. Lợi ích quyết
định: chỉ ~2KB phải đi qua máy air-gap, image 200MB–3GB không bao giờ chạm máy giữ khoá ký.

### Vì sao nén zstd

Đo thật trên `nvr-wrapper` (dev, 2026-08-20):

| | Kích thước | Thời gian nén |
|---|---|---|
| `docker save` thô | 434 MB | 16s |
| gzip -6 | 173 MB | 19s |
| **zstd -19 -T0** | **134 MB** | 58s |

zstd nhỏ hơn gzip **23%** — khoảng 39MB mỗi bản trên đường truyền field. Thời gian nén là chi
phí một lần trên máy dev, không đáng kể.

`docker load` **tự nhận zstd** (cả gzip, xz, bzip2): daemon trên host giải nén, không phải CLI
trong container. Nên **không cần thêm binary `zstd` vào image watchdog** — đã kiểm thật.

---

## 2. manifest.json

```jsonc
{
  "schema": 1,                              // tăng khi đổi cấu trúc không tương thích
  "model": "AIVIEW-NVR-X1",                 // phải khớp DEVICE_MODEL của máy
  "version": "1.1.0.0",
  "channel": "stable",
  "releaseDate": "2026-08-25T00:00:00Z",
  "expiresAt":   "2027-02-21T00:00:00Z",    // gate 3 — chống freeze attack
  "description": "…",
  "notes": "…",

  "minFrom":    "1.0.0.0",                  // gate 1 — bậc thang nâng cấp
  "minApplier": "1.0.0",                    // gate 4 — watchdog tối thiểu
  "requiresFreeBytes": 1826586624,
  "risk": "normal",                         // "high" cho gói có Frigate
  "autoApply": false,                       // gói risk=high phải khai true mới tự áp

  "package": { "url": "https://aiview.bkav.com/download/AIVIEW-NVR-X1/OTA/nvr-…-1.1.0.0.nvrup" },

  "images": [
    { "name": "nvr-wrapper", "source": "tarball",
      "path": "images/nvr-wrapper.tar.zst",
      "sha256": "9cfe98ff…", "tag": "nvr-wrapper:latest" },

    { "name": "frigate", "source": "registry",
      "ref": "ghcr.io/blakeblackshear/frigate@sha256:…",   // PHẢI ghim digest
      "tag": "ghcr.io/blakeblackshear/frigate:0.17.0",
      "fallback": { "source": "tarball",
                    "url": "https://aiview.bkav.com/…/frigate-0.17.0.tar.zst",
                    "sha256": "…" } }
  ],

  "files": [
    { "path": "docker-compose.yml", "sha256": "d631f18f…", "mode": "644" }
  ],

  "hooks": { "preApply": "hooks/pre-apply.sh", "postApply": "hooks/post-apply.sh" },

  "healthChecks": [
    { "url": "http://127.0.0.1:3000/api/health",
      "expectStatus": 200, "expectJson": {}, "timeoutSec": 180 },
    { "url": "http://127.0.0.1:3000/api/version",
      "expectStatus": 200, "expectJson": { "version": "1.1.0.0" }, "timeoutSec": 180 }
  ]
}
```

### Trường `package` — vì sao KHÔNG có `sha256` và `size`

Nhìn thì tưởng thiếu, nhưng đưa vào là **bất khả thi và dư thừa**:

1. **Bất khả thi — vòng tròn.** `.nvrup` *chứa* `manifest.json`. Hash của `.nvrup` phụ thuộc
   vào manifest, mà manifest lại phải chứa hash đó. Không có điểm dừng.

2. **Dư thừa — toàn vẹn đã phủ kín theo đường khác, và phủ TỐT HƠN:**
   - `manifest.json` được ký Ed25519 ⇒ sửa manifest là sai chữ ký
   - manifest chứa sha256 **từng file payload** ⇒ sửa payload là sai hash file đó
   - sau khi giải nén, manifest trong gói phải **byte-identical** với manifest đã verify từ
     server ⇒ không ghép được manifest-của-bản-này với gói-của-bản-khác

   Kết quả: file bị sửa một byte ở đâu cũng bị bắt, và bắt **chính xác file nào** thay vì chỉ
   báo "gói hỏng". Hash cả tarball còn kém hơn thế.

`size` cũng bỏ: chỉ dùng để vẽ thanh tiến trình, mà `Content-Length` lúc tải là đủ và luôn
đúng. Một trường luôn rỗng chỉ gây nhầm.

### `healthChecks[].expectJson` — vì sao `/api/health` một mình là không đủ

`/api/health` trả **200 kể cả khi container CŨ vẫn đang chạy**: `docker compose up` có thể thất
bại lặng lẽ, hoặc container mới crash rồi Docker restart về bản cũ. Health pass ⇒ máy ghi "cập
nhật thành công" trong khi **chưa đổi gì**.

Nên bắt buộc có một check đối chiếu `/api/version` báo **đúng version mong đợi**. Repo này đã có
tiền lệ đúng lớp lỗi đó: comment trong `docker-compose.yml` ghi lại chuyện `APP_VERSION` bị
override làm badge UI luôn hiện `dev` bất kể thực tế deploy bản nào.

### `images[].source` — hai đường lấy hàng

| `source` | Dùng cho | Toàn vẹn |
|---|---|---|
| `tarball` | Image của mình (`nvr-wrapper`, `nvr-watchdog`) — không nằm trên registry công khai nào | `sha256` trong manifest |
| `registry` | Image công khai bên thứ ba (Frigate) | **digest** — Docker tự từ chối layer sai hash |

`source: registry` **bắt buộc ghim digest** (`@sha256:…`). Tag thuần bị từ chối
(`IMAGE_REF_NOT_PINNED`): tag có thể bị trỏ lại sang image khác bất cứ lúc nào mà tên không
đổi, dùng nó là bỏ luôn phần toàn vẹn mà đường registry vốn cho miễn phí.

Pull luôn **anonymous**: `DOCKER_CONFIG` trỏ thư mục tạm rỗng nên kể cả khi máy từng
`docker login`, lệnh vẫn không gửi credential nào. Image Frigate là công khai — đã kiểm: token
anonymous được cấp tự động, không cần tài khoản. Và pull là **tăng dần theo layer** nên nâng
Frigate chỉ tải phần thay đổi thay vì cả 1.2GB tarball.

---

## 3. Bốn gate — vì sao chữ ký không đủ

> Chữ ký chứng minh: *"file này đúng là do tôi phát hành, không ai sửa một byte."*
> Chữ ký **không** chứng minh: *"file này nên được cài lên máy này, lúc này."*

`sha256` chống **hỏng** file. Chữ ký chống **sửa** file. Gate dưới đây chống **đúng file, sai
thời điểm** — một lớp lỗi hoàn toàn riêng.

### Gate 1 — `minFrom`: bậc thang nâng cấp

Migration dữ liệu không phải lúc nào cũng nhảy bậc được.

**Ví dụ thật trong repo này:** `migrateCloudCamerasIntoCameras()` là migration **một chiều**, và
CLAUDE.md ghi rõ bảng `cloud_cameras` "giữ để rollback, không đọc/ghi vào nó trong code mới".

Giả sử bản 1.4 xoá hàm migration đó (hợp lý — cả fleet đã chạy qua). Một máy field còn ở 1.0
nhảy thẳng lên 1.4: bảng `cloud_cameras` vẫn đầy dữ liệu mà **không còn dòng code nào đọc nó**.
Camera cloud biến mất sạch. Chữ ký gói 1.4 hoàn toàn hợp lệ, sha256 khớp, và `/api/health` vẫn
trả 200 nên health-check **pass** — rollback KHÔNG kích hoạt. Khách mất camera, không ai biết
vì sao.

`minFrom: "1.2.0.0"` biến chuyện đó thành: máy 1.0 **từ chối** gói 1.4, báo Telegram *"cần đi
qua 1.2 trước"*. Máy cũ nhất trong fleet luôn là máy dễ chết nhất — gate này tồn tại cho nó.

### Gate 2 — `floorVersion`: sàn chống hạ cấp

Đây là gate mà chữ ký **không thay được**, và là lý do chính người ta cần "phiên bản tối thiểu".

Giả sử 1.0.5 có lỗ hổng auth bypass, bạn vá ở 1.0.6. File gói 1.0.5 **do chính bạn ký** vẫn còn
tồn tại: trên CDN cache, trên mirror chưa sync, trong thư mục backup, trong máy kẻ tấn công đã
tải từ tháng trước. File đó có:

- chữ ký hợp lệ ✓ (bạn ký thật)
- sha256 khớp ✓
- model khớp ✓
- **và lỗ hổng auth bypass**

Kẻ tấn công chỉ cần làm máy tải lại đúng file cũ đó — không sửa gì, **không cần khoá của bạn** —
rồi khai thác lại lỗ hổng đã "vá". Lớp tấn công này có tên: **rollback / downgrade attack**. Đó
là lý do TUF, Android Verified Boot và UEFI Secure Boot đều có anti-rollback counter.

`floorVersion` = version cao nhất **từng chạy thành công** trên máy, lưu ở `data/ota/state.json`.
Gói có `version < floorVersion` bị từ chối kể cả khi mọi thứ khác đúng.

**Quy tắc then chốt: sàn CHỈ tăng khi vào `COMMITTED`.** Rollback KHÔNG tăng sàn — nếu tăng, một
bản lỗi vừa rollback sẽ chặn luôn chính bản cũ đang chạy, và máy tự khoá mình ra khỏi mọi đường
cập nhật.

Hạ sàn: xem [runbook-su-co.md](runbook-su-co.md) — cần có mặt tại máy.

### Gate 3 — `expiresAt`: chống freeze attack

Họ hàng của gate 2, theo hướng ngược. Kẻ chặn mạng **không cần sửa gì**: chỉ cần **giữ nguyên**
manifest cũ mãi mãi. Máy hỏi mỗi đêm, thấy version y như đang chạy, kết luận "không có bản mới",
và **không bao giờ nhận bản vá bảo mật**. Im lặng hoàn toàn — không log lỗi, không alert, vì
theo góc nhìn của máy thì mọi thứ bình thường.

`expiresAt` biến một sự cố im lặng thành một tin nhắn Telegram.

`build-package.sh --expires-days` mặc định 180. Đặt quá ngắn thì gói hợp lệ bị từ chối khi khách
để máy tắt vài tháng; quá dài thì gate mất tác dụng.

### Gate 4 — `minApplier`: watchdog đủ mới

Phần đi apply nằm ở watchdog. Gói dùng hook hoặc trường mà watchdog cũ không biết sẽ apply
**thiếu bước** rồi báo thành công — thất bại im lặng, tệ hơn thất bại ồn ào.

### Các gate khác trong `OtaGates.evaluate()`

| Mã | Chặn |
|---|---|
| `MODEL_MISMATCH` | Gói của dòng sản phẩm khác |
| `OLDER_THAN_INSTALLED` | Gói cũ hơn bản đang chạy |
| `ALREADY_CURRENT` | Đang chạy đúng bản đó — **kết cục bình thường của hầu hết các đêm**, không báo Telegram |
| `EMPTY_PAYLOAD` | Gói không khai image nào và cũng không file nào |
| `IMAGE_REF_NOT_PINNED` | `source: registry` mà không ghim digest |
| `HIGH_RISK_NEEDS_OPT_IN` | `risk: high` mà không khai `autoApply: true`, khi chạy tự động |
| `DISK_INSUFFICIENT` | Trống < `max(requiresFreeBytes, 3 × kích thước gói)` |
| `PRECONDITION_UNHEALTHY` | Máy đang không khoẻ — apply lúc này thì không phân biệt được "bản mới lỗi" với "máy vốn đã lỗi", và sẽ rollback oan |

Hệ số **3× kích thước gói**: cần chỗ cho gói + image đã giải nén + bản cũ giữ lại để lùi. Thiếu
chỗ giữa lúc `docker load` là kịch bản tệ nhất — chết đúng khoảnh khắc image cũ đã bị ghi đè.

---

## 4. Whitelist đường dẫn overlay

Gói ghi file vào `PROJECT_DIR` với quyền root. `manifest.files[].path` bị **whitelist** — không
phải blacklist, vì blacklist thì luôn thiếu một biến thể nào đó, còn whitelist thì cái gì không
nghĩ tới sẽ bị chặn thay vì cho qua.

Nguồn duy nhất: `ALLOWED_OVERLAY_PREFIXES` trong
[OtaLayout.kt](../../watchdog/src/main/kotlin/vn/aiview/watchdog/ota/OtaLayout.kt).

| Được phép | Ghi chú |
|---|---|
| `docker-compose.yml`, `docker-compose.offline.yml` | |
| `VERSION` | |
| `nginx/`, `config/`, `mosquitto/config/`, `scripts/` | cả thư mục |
| `data/dc-bridge` | **đúng một file**, không phải cả `data/` |

**Cố tình KHÔNG có:**

| Bị chặn | Vì sao |
|---|---|
| `.env` | mật khẩu MQTT + token Telegram của máy khách |
| `secrets/` | token gateway |
| `media/`, `logs/` | dữ liệu khách |
| `data/nvr.db` | camera + hash mật khẩu |
| `data/session_secret` | ghi được là giả mạo được session |
| `data/ota/state.json` | ghi được là **xoá được sàn chống hạ cấp** |

Whitelist là **lớp phòng thủ thứ hai cho tình huống khoá ký bị lộ** — lúc đó chữ ký không còn
bảo vệ được gì. Đã chứng minh bằng test tấn công: gói ký hợp lệ khai `path: ".env"` bị chặn ở cả
`ota/verify-package.sh` và `OtaLayout.isAllowedOverlayPath()`.

Phía bash (`ota/lib/overlay-whitelist.sh`) **trích danh sách thẳng từ file Kotlin** thay vì giữ
bản copy. Hai bản danh sách riêng sẽ lệch nhau theo hướng tệ nhất: bash nói "hợp lệ", máy field
từ chối ở tận hiện trường.

### Giải nén: tự đọc tar thay vì gọi `tar`

`OtaPackage` tự đọc tar POSIX/ustar. Gọi `tar -xzf` ra ngoài thì **tar tự quyết định** ghi file
ở đâu dựa trên tên trong archive — một archive độc hại chứa entry `../../etc/cron.d/x` hoặc một
**symlink trỏ ra ngoài** là ghi được ra ngoài thư mục đích (lớp lỗi "tar slip"; GNU tar có chặn
nhưng hành vi khác nhau giữa các bản và các cờ).

Tự đọc thì **mình** quyết định: chỉ nhận typeflag `0`/` ` (file thường) và `5` (thư mục). Symlink
(`2`), hardlink (`1`), device (`3`,`4`), fifo (`6`) đều **bỏ qua kèm log WARN**. Symlink là cái
đáng lo nhất: một archive có thể trồng symlink `config → /etc` rồi entry sau ghi `config/passwd`,
và từng entry riêng lẻ nhìn đều sạch.

Thêm trần chống bom nén: 2 000 entry, 8GB sau giải nén.

---

## 5. Máy field verify theo thứ tự nào

```
1. Giải nén (chỉ file thường, đường dẫn sạch)
2. Chữ ký Ed25519 trên BYTE THÔ của manifest.json     ← chưa parse gì
3. Parse manifest (giờ mới được phép tin)
4. Kiểm schema == 1
5. sha256 từng payload
6. Whitelist đường dẫn overlay
7. Byte-identity với manifest remote (chỉ đường OTA tự động)
8. Bốn gate chính sách
```

**Bước 2 trước bước 3 là bắt buộc.** Chữ ký kiểm trên byte thô nên bộ parse JSON không bao giờ
phải nhìn dữ liệu chưa xác thực — bỏ hẳn parser ra khỏi bề mặt tấn công.

API trong `OtaVerifier` **ép** đúng thứ tự đó: `OtaManifest` chỉ lấy được qua `VerifiedManifest`,
mà class đó có constructor `private` nên chỉ verifier tạo được. Đường dùng manifest chưa verify
*không tồn tại*, chứ không phải chỉ "đừng làm thế".

---

## 6. Chữ ký

- **Thuật toán:** Ed25519. Ký bằng `openssl pkeyutl -sign -rawin` (máy air-gap), verify bằng
  `java.security.Signature` của JDK 21 (native từ JDK 15, **không thêm dependency crypto**).
- **`-rawin` là bắt buộc:** Ed25519 ký toàn bộ message, không pre-hash như RSA/ECDSA. Đây chính
  là thứ phải khớp với `Signature.getInstance("Ed25519").update(bytes)` phía JDK.
- **Khoá công khai:** PEM X.509 SubjectPublicKeyInfo, đặt ở `/opt/nvr/keys/ota-trust.d/*.pub`
  (`root:root 0444`), mount read-only vào watchdog.
- **`keyId`** = 8 hex đầu của `sha256(DER SubjectPublicKeyInfo)` — **tính ra được**, không phải
  chuỗi tự đặt, nên không ai khai sai keyId để trỏ sang khoá khác. `keyId` trong file `.sig` chỉ
  là *gợi ý* để thử đúng khoá trước cho nhanh; khai sai vẫn thử hết keyring.

Format `manifest.json.sig`:

```json
{"keyId":"7f295ade","alg":"ed25519","sig":"<base64 của 64 byte>"}
```

Interop openssl ↔ JDK được test tự động: fixture trong
`watchdog/src/test/resources/ota/` được ký bằng **openssl thật**, verify bằng **JDK**. Ký bằng
`KeyPairGenerator` của JDK rồi verify bằng JDK thì test luôn xanh mà không chứng minh gì về
openssl — đúng chỗ cả cơ chế sẽ vỡ ở production.
