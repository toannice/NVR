# Máy air-gap, khoá ký, và quy trình rotate

Khoá ký gói cập nhật là **thứ nhạy cảm nhất trong toàn bộ dự án**. Ai giữ nó thì đẩy được code
tuỳ ý vào mọi máy khách, chạy bằng quyền root, và máy sẽ tin vì chữ ký hợp lệ.

Định dạng gói: [dinh-dang-goi.md](dinh-dang-goi.md). Quy trình phát hành:
[runbook-phat-hanh.md](runbook-phat-hanh.md).

---

## 1. Phần cứng cần

| | Yêu cầu thật | Ghi chú |
|---|---|---|
| Máy air-gap | Boot được Linux, có `openssl` 3.x | Laptop cũ 2GB RAM / 30GB ổ là **dư**. Không cần card mạng — càng không có càng tốt |
| USB | 1 cái chạy được, 2 cái đúng bài | Dung lượng **không quan trọng**: khoá chỉ ~120 byte/file. Tiêu chí duy nhất là **mới và lành** |

Laptop **tốt hơn** máy cây: có pin nên không cần UPS lúc mất điện, và cất được vào tủ khoá cùng
phong bì khoá.

### Máy đã từng nối mạng thì sao?

Điểm mấu chốt: **keypair được sinh SAU khi máy offline vĩnh viễn**. Malware có sẵn từ trước (nếu
có) chưa từng thấy khoá, và sau đó không còn đường ra mạng để gửi đi. Đường rò còn lại duy nhất
là chính cái USB bạn cắm vào — một kịch bản exotic.

Quy trình tương xứng với quy mô:

1. Rút dây mạng / rút card wifi **trước**
2. Format + cài lại OS từ USB ISO, **trong lúc đã offline**
3. Không nối mạng lại, không bao giờ
4. **Rồi mới** chạy `airgap-setup.sh`

Bỏ bước 2 thì rủi ro còn lại là "malware resident + biết ghi lén vào USB" — thấp nhưng không
bằng 0.

`airgap-setup.sh` tự phát hiện máy đang có đường ra mạng và bắt gõ xác nhận trước khi tiếp tục.

---

## 2. Sinh khoá — chạy một lần

```bash
# Trên MÁY AIR-GAP
bash airgap-setup.sh              # mặc định ra ~/nvr-ota-keys
```

Sinh **hai** khoá:

| Khoá | Dùng khi nào |
|---|---|
| `ota-primary` | Ký mọi bản phát hành |
| `ota-backup` | Niêm phong, chỉ dùng khi mất/lộ primary |

**Cả hai được máy field tin cậy TỪ NGÀY CÀI.** Đây là điểm quan trọng nhất của thiết kế khoá:
nếu chỉ tin một khoá và nó mất, bạn phải ra **từng máy field** để nạp khoá mới — với fleet
thương mại là bất khả thi. Có backup sẵn thì rotate chỉ là "đổi khoá ký trên máy air-gap", máy
field **không cần chạm**.

Script tự kiểm: ký thử rồi verify lại bằng public key dẫn xuất từ chính khoá đó. Không khớp thì
dừng — thà biết ngay còn hơn phát hiện lúc máy field từ chối gói.

### Đưa public key vào repo

```bash
# USB: air-gap → máy dev. CHỈ file .pub, private key ở lại máy air-gap.
cp ota-primary.pub ota-backup.pub /media/usb/

# Trên máy dev
cp /media/usb/ota-primary.pub /media/usb/ota-backup.pub ota/keys/
git add ota/keys/*.pub && git commit -m "OTA: khoá công khai phát hành"
```

Public key đưa đi đâu cũng được — đó là toàn bộ điểm của mã hoá bất đối xứng.

---

## 3. Sao lưu private key

Kích thước thật: private key Ed25519 dạng PEM PKCS#8 là **đúng một dòng 64 ký tự base64**.

```
-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEI<-- 43 ký tự base64 nữa, tổng 64 -->xxxxxxxxxxx
-----END PRIVATE KEY-----
```

> Ở trên là **hình dạng** chứ không phải khoá thật — cố tình không in một khoá đọc được, kể cả
> khoá vứt đi. Khoá private trong tài liệu là thứ secret scanner sẽ bắt, và quan trọng hơn là
> nó dạy sai thói quen cho người đọc tài liệu này.

| Lớp | Cách làm | Bắt buộc? |
|---|---|---|
| **Khoá `ota-backup`** | Đã tin cậy trên mọi máy field từ ngày cài | **Lưới an toàn CHÍNH** |
| 2 USB mã hoá | `openssl enc -aes-256-cbc -pbkdf2 -salt -in ota-primary.key -out /media/usb/ota-primary.key.enc`, để ở **hai địa điểm vật lý khác nhau** | Nên có |
| Giấy in | `BAN-IN-GIAY.txt` do `airgap-setup.sh` sinh — 1 dòng base64 + 1 dòng checksum, phong bì niêm phong | **Tuỳ chọn** |

**Dùng USB mới.** USB flash để trong tủ vài năm có thể mất bit âm thầm (charge leakage ở cell
NAND), và một cái đang có biểu hiện lạ (phải mount tay, nhận chậm) không phải nơi giữ khoá gate
cả kênh cập nhật sản phẩm.

**Về lớp giấy:** bạn **in**, không viết tay; và chỉ gõ lại tay trong tình huống thảm hoạ (cả 2
USB chết) — 64 ký tự ≈ 30 giây, có dòng checksum để gõ sai là biết ngay. Lý do có nó: USB và SSD
đều chết âm thầm, còn giấy trong tủ khoá không cần cổng USB còn tốt cũng không cần filesystem
còn đọc được.

Nhưng vì `ota-backup` đã xử lý đúng cái rủi ro "mất khoá" mà **không phải ra field**, lớp giấy
là defense-in-depth — **bỏ được** nếu thấy rườm rà.

Sau khi in xong: `shred -u BAN-IN-GIAY.txt` (đừng để bản in nằm trên đĩa).

---

## 4. Nghi thức ký mỗi lần phát hành

```
[MÁY DEV]      ota/build-package.sh 1.1.0.0
                 → releaseVersion/ota/1.1.0.0/{manifest.json, images/, files/}
                 → cp manifest.json /media/usb/          (~2KB, DUY NHẤT file này)

        ──────── USB ────────►

[MÁY AIR-GAP]  bash sign-manifest.sh /media/usb/manifest.json
                 → IN RA model / version / minFrom / expiresAt / risk
                 → hỏi: gõ lại đúng version để xác nhận
                 → openssl pkeyutl -sign -rawin
                 → tự verify lại ngay
                 → /media/usb/manifest.json.sig          (~140 byte)

        ◄──────── USB ────────

[MÁY DEV]      cp /media/usb/manifest.json.sig releaseVersion/ota/1.1.0.0/
               ./ota/finalize-package.sh releaseVersion/ota/1.1.0.0
               ./ota/verify-package.sh releaseVersion/ota/nvr-*.nvrup
```

**Chỉ manifest.json và .sig đi qua air-gap.** Image 200MB–3GB không bao giờ chạm máy giữ khoá.

### Bước "đọc lại bằng mắt" — vì sao không bỏ được

Máy air-gap **không có cách nào tự biết** file nó đang ký có đúng là bản bạn định phát hành
không: nó không thấy cây source, không thấy image, không nối mạng để đối chiếu. Chỉ mắt bạn biết.

`sign-manifest.sh` in ra và bắt **gõ lại đúng version** để xác nhận (không phải bấm Enter — gõ
Enter thành phản xạ, gõ lại version thì phải đọc). Ba điểm dễ sai nhất:

- `version` đúng bản vừa build, không phải bản cũ còn sót trên USB?
- `minFrom` chặn đúng những máy field cũ cần đi qua bản trung gian?
- `risk=high` (có Frigate) thì `autoApply` đã đúng ý?

Bỏ qua bằng `--yes` **chỉ khi đang test với khoá dev**.

---

## 5. Khoá dev — và ba lớp chặn nó ra máy khách

Chu trình chạy đủ trên một máy khi chưa có hardware, vì air-gap là tính chất **vật lý/quy
trình**, không phải tính chất của code:

```bash
openssl genpkey -algorithm ed25519 -out ota/.dev-keys/ota-dev.key
openssl pkey -in ota/.dev-keys/ota-dev.key -pubout -out ota/keys/ota-dev.pub
```

**Máy khách tin khoá dev nghĩa là bất cứ ai chạm được máy dev đều đẩy được bản cập nhật vào máy
khách đó.** Đây là loại lỗi chỉ phát hiện được khi đã quá muộn, nên có ba lớp chặn:

| Lớp | Cơ chế |
|---|---|
| 1 | `ota/.dev-keys/` trong `.gitignore` — private key không vào git |
| 2 | `finalize-package.sh` **từ chối** gói ký bằng khoá tên `ota-dev*`, trừ khi `OTA_ALLOW_DEV_KEY=1` |
| 3 | `install-ota.sh` **từ chối cài** khoá `ota-dev*` vào máy, trừ khi `OTA_ALLOW_DEV_KEY=1` |

Trước khi giao khách: `ota/keys/` chỉ nên có `ota-primary.pub` và `ota-backup.pub`.

---

## 6. Rotate khoá

### Trường hợp A — primary bị lộ hoặc mất, backup còn tốt

Đây là trường hợp thiết kế đã lo sẵn: **máy field không cần chạm.**

1. Trên máy air-gap: `bash sign-manifest.sh <manifest> --key ~/nvr-ota-keys/ota-backup.key`
2. Từ giờ mọi bản phát hành ký bằng backup
3. Sinh một cặp khoá **mới** làm backup thay thế:
   ```bash
   openssl genpkey -algorithm ed25519 -out ota-backup2.key
   openssl pkey -in ota-backup2.key -pubout -out ota-backup2.pub
   ```
4. Đưa `ota-backup2.pub` vào `ota/keys/`, phát hành một bản bình thường (ký bằng backup). Bản đó
   mang `config/` mới… **không** — public key không đi qua overlay được (`ota/keys/` không trong
   whitelist, và cố tình như vậy).

   Nên: khoá mới chỉ có hiệu lực trên **máy cài mới** hoặc máy được chạy lại
   `install-ota.sh`. Máy đang chạy vẫn tin primary (đã lộ) + backup (đang dùng).

5. **Nếu primary bị lộ, không chỉ mất:** phải thu hồi thật — xem trường hợC.

### Trường hợp B — mất cả hai khoá (không lộ)

Không ai ký được bản mới nữa, nhưng cũng không ai tấn công được. Máy field vẫn chạy bình thường
và vẫn nhận gói **upload tay** nếu bạn còn ký được… mà bạn không còn.

Đường ra: sinh cặp khoá mới, rồi ra từng máy chạy `install-ota.sh` với keyring mới. Với fleet
đông thì đây là lý do lớp sao lưu USB + giấy tồn tại.

### Trường hợp C — primary bị lộ (kẻ khác có khoá)

Nguy hiểm nhất: kẻ đó ký được gói mà mọi máy field đều tin.

1. **Ngay lập tức**: đặt `ota.enabled: false` trong `config/watchdog.yml` trên các máy tiếp cận
   được, để chúng không tự tải gì nữa
2. Sinh cặp khoá mới trên máy air-gap **mới** (máy cũ coi như đã nhiễm)
3. Ra từng máy chạy `install-ota.sh`, và **xoá tay** file `.pub` của khoá bị lộ khỏi
   `/opt/nvr/keys/ota-trust.d/`
4. Nâng `floorVersion` trên từng máy lên trên mức mà kẻ tấn công có thể đã đẩy

Không có đường tắt: thu hồi khoá đòi tiếp cận vật lý. Đó là cái giá của việc không có kênh
revocation online — đánh đổi có chủ ý, vì một kênh revocation online lại là một bề mặt tấn công
mới và một điểm chết mới.

---

## 7. Kiểm tra định kỳ

Mỗi 6 tháng, trên máy air-gap:

```bash
# Khoá còn đọc được?
openssl pkey -in ~/nvr-ota-keys/ota-primary.key -pubout | diff - ~/nvr-ota-keys/ota-primary.pub \
    && echo "primary OK"

# USB backup còn giải mã được?
openssl enc -d -aes-256-cbc -pbkdf2 -in /media/usb/ota-primary.key.enc | \
    openssl pkey -pubout | diff - ~/nvr-ota-keys/ota-primary.pub && echo "USB OK"
```

Phát hiện USB chết lúc kiểm định kỳ thì chỉ là mua USB mới. Phát hiện lúc cần dùng thì là sự cố.
