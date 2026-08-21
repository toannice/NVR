#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# airgap-setup.sh — sinh cặp khoá ký OTA. CHẠY MỘT LẦN, TRÊN MÁY AIR-GAP.
#
# Máy air-gap = máy riêng, đã rút mạng VĨNH VIỄN trước khi chạy script này.
# Yêu cầu duy nhất: boot được Linux + có openssl 3.x. Laptop cũ 2GB RAM / 30GB ổ là dư.
# (Laptop tốt hơn máy cây: có pin nên không cần UPS, và cất được vào tủ khoá cùng phong bì.)
#
# Máy đã từng nối mạng thì sao? Điểm mấu chốt: keypair sinh SAU khi máy offline vĩnh viễn,
# nên malware có sẵn từ trước chưa từng thấy khoá và sau đó không còn đường ra mạng để gửi
# đi. Quy trình tương xứng:
#     1. Rút dây mạng / rút card wifi TRƯỚC
#     2. Format + cài lại OS từ USB ISO, TRONG LÚC đã offline
#     3. Không nối mạng lại, không bao giờ
#     4. RỒI MỚI chạy script này
#
# Sinh HAI khoá:
#     ota-primary  → ký mọi bản phát hành
#     ota-backup   → niêm phong, chỉ dùng khi mất primary
# Cả hai được máy field tin cậy TỪ NGÀY CÀI. Đây là điểm quan trọng: nếu chỉ tin 1 khoá và
# nó mất/lộ thì phải ra TỪNG máy field để nạp khoá mới — với fleet thương mại là bất khả thi.
#
# Chạy:  bash airgap-setup.sh [thư-mục-đích]
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

OUT="${1:-$HOME/nvr-ota-keys}"

c()    { printf '\033[1;36m%s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m  ✓ %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m  ! %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m  ✗ %s\033[0m\n' "$*" >&2; exit 1; }

command -v openssl >/dev/null || die "không có openssl"
openssl genpkey -algorithm ed25519 -out /dev/null 2>/dev/null \
    || die "openssl không hỗ trợ ed25519 — cần openssl 3.x (bản này: $(openssl version))"

# ── Cảnh báo nếu máy đang có mạng ────────────────────────────────────────────
# Không chặn cứng (có thể đang test quy trình trên máy dev), nhưng phải nói to.
if ip route get 1.1.1.1 >/dev/null 2>&1; then
    warn "MÁY NÀY ĐANG CÓ ĐƯỜNG RA MẠNG."
    warn "Khoá ký sinh trên máy nối mạng thì mất toàn bộ ý nghĩa của air-gap."
    printf '\033[1;33m  → Vẫn tiếp tục? Gõ đúng chữ "toi-hieu-rui-ro": \033[0m'
    read -r ans
    [[ "$ans" == "toi-hieu-rui-ro" ]] || die "dừng lại — hãy rút mạng rồi chạy lại"
fi

[[ -e "$OUT" ]] && die "$OUT đã tồn tại — không ghi đè lên khoá đang có. Đổi thư mục hoặc xoá tay."
mkdir -p "$OUT"
chmod 700 "$OUT"
cd "$OUT"

c "[1/3] Sinh cặp khoá Ed25519"
for name in ota-primary ota-backup; do
    openssl genpkey -algorithm ed25519 -out "$name.key"
    chmod 400 "$name.key"
    openssl pkey -in "$name.key" -pubout -out "$name.pub"
    chmod 444 "$name.pub"
    # keyId = 8 hex đầu của sha256(DER SubjectPublicKeyInfo). TÍNH RA được, không tự đặt,
    # nên không ai khai sai keyId để trỏ sang khoá khác.
    kid="$(openssl pkey -in "$name.key" -pubout -outform DER | sha256sum | cut -c1-8)"
    echo "$kid" > "$name.keyid"
    ok "$name  keyId=$kid"
done

c "[2/3] Tự kiểm: ký thử rồi verify lại"
echo "kiem-tra-$(date -u +%Y%m%dT%H%M%SZ)" > selftest.txt
for name in ota-primary ota-backup; do
    openssl pkeyutl -sign -rawin -inkey "$name.key" -in selftest.txt -out "selftest.$name.sig"
    openssl pkeyutl -verify -rawin -pubin -inkey "$name.pub" \
        -in selftest.txt -sigfile "selftest.$name.sig" >/dev/null \
        || die "$name ký/verify không khớp — KHÔNG dùng bộ khoá này"
    ok "$name ký và verify khớp"
done
rm -f selftest.txt selftest.*.sig

c "[3/3] Bản sao lưu giấy"
{
    echo "════════════════════════════════════════════════════════════════"
    echo " KHOÁ KÝ OTA — NVR AiView"
    echo " Sinh ngày: $(date -u +%Y-%m-%d) (UTC) trên máy air-gap"
    echo "════════════════════════════════════════════════════════════════"
    echo
    for name in ota-primary ota-backup; do
        echo "── $name ── keyId $(cat "$name.keyid")"
        echo "PRIVATE (1 dòng 64 ký tự base64):"
        sed -n '2p' "$name.key"
        echo "checksum để phát hiện gõ sai (8 hex đầu sha256 của dòng trên):"
        sed -n '2p' "$name.key" | tr -d '\n' | sha256sum | cut -c1-8
        echo
    done
    echo "Khôi phục từ giấy khi cả hai USB chết:"
    echo "  printf -- '-----BEGIN PRIVATE KEY-----\\n<dòng base64>\\n-----END PRIVATE KEY-----\\n' > ota-primary.key"
    echo "  openssl pkey -in ota-primary.key -pubout   # phải khớp public key đã biết"
} > BAN-IN-GIAY.txt
chmod 400 BAN-IN-GIAY.txt
ok "BAN-IN-GIAY.txt"

cat <<EOF

$(c "══ XONG. Việc phải làm tiếp, theo thứ tự ══")

  1. SAO LƯU private key — mỗi khoá chỉ ~120 byte, nhưng mất là mất kênh cập nhật.

     a) Hai USB MỚI, mã hoá, để ở HAI ĐỊA ĐIỂM VẬT LÝ KHÁC NHAU:
          openssl enc -aes-256-cbc -pbkdf2 -salt \\
              -in ota-primary.key -out /media/usb/ota-primary.key.enc
        Dùng USB mới, đừng dùng cái đang có biểu hiện lạ: USB flash để trong tủ vài năm
        có thể mất bit âm thầm (charge leakage ở cell NAND).

     b) In BAN-IN-GIAY.txt, cho vào phong bì niêm phong, cất tủ khoá.
        TUỲ CHỌN — bỏ được nếu thấy rườm rà. Lưới an toàn chính là khoá ota-backup:
        nó đã được mọi máy field tin cậy nên mất primary KHÔNG phải thảm hoạ, chỉ cần
        chuyển sang ký bằng backup, máy field không cần chạm.

     c) Xoá bản in trên đĩa sau khi đã in:  shred -u BAN-IN-GIAY.txt

  2. ĐƯA HAI FILE .pub SANG MÁY DEV (public key — đưa đi đâu cũng được):
       ota-primary.pub  ota-backup.pub
     Chép vào repo tại  ota/keys/  rồi commit. Đó là thứ máy field dùng để đối chiếu.

  3. GIỮ .key Ở LẠI MÁY NÀY. Private key không bao giờ rời máy air-gap.

  $(warn "Từ giờ mỗi lần phát hành: chỉ manifest.json (~1KB) đi qua USB vào máy này,")
  $(warn "ký bằng ota/sign-manifest.sh, rồi mang file .sig ra. Image 200MB-3GB")
  $(warn "KHÔNG BAO GIỜ chạm máy này.")

  Thư mục khoá: $OUT
EOF
