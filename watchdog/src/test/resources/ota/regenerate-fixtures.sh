#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────────
# regenerate-fixtures.sh — sinh lại fixture cho OtaVerifierTest.
#
# Vì sao có script này thay vì commit luôn private key: verify chỉ cần PUBLIC key (đó là
# toàn bộ điểm của mã hoá bất đối xứng), nên private key không có việc gì trong git. Để nó
# vào repo là tự nuôi một thói quen mà đến lúc quan trọng sẽ trả giá — và `verify-bundle.sh`
# có pattern "Private key" sẽ chặn bundle nếu file như vậy lọt vào.
#
# Fixture này kiểm ĐIỂM KHỚP quan trọng nhất của cả cơ chế OTA: chữ ký do **openssl** tạo ra
# trên máy air-gap phải được **JDK** trên máy field đọc được. Hai thư viện crypto khác nhau,
# hai máy khác nhau. Sinh chữ ký bằng KeyPairGenerator của JDK rồi verify bằng JDK thì không
# chứng minh được gì về openssl cả.
#
# Chạy:  bash src/test/resources/ota/regenerate-fixtures.sh
# ──────────────────────────────────────────────────────────────────────────────
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

command -v openssl >/dev/null || { echo "cần openssl 3.x" >&2; exit 1; }
openssl genpkey -algorithm ed25519 -out /dev/null 2>/dev/null \
    || { echo "openssl không hỗ trợ ed25519 — cần 3.x" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Khoá "phát hành" (mô phỏng ota-primary trên máy air-gap) + một khoá KHÔNG được tin cậy
openssl genpkey -algorithm ed25519 -out "$TMP/primary.key"
openssl pkey -in "$TMP/primary.key" -pubout -out fixture-primary.pub
openssl genpkey -algorithm ed25519 -out "$TMP/untrusted.key"
openssl pkey -in "$TMP/untrusted.key" -pubout -out fixture-untrusted.pub

# keyId = 8 hex đầu của sha256(DER SubjectPublicKeyInfo) — TÍNH RA, không tự đặt
KEYID="$(openssl pkey -in "$TMP/primary.key" -pubout -outform DER | sha256sum | cut -c1-8)"

sign() { # sign <key> <file-vào> <file-sig-ra>
    openssl pkeyutl -sign -rawin -inkey "$1" -in "$2" -out "$TMP/raw.sig"
    printf '{"keyId":"%s","alg":"ed25519","sig":"%s"}\n' "$KEYID" "$(base64 -w0 "$TMP/raw.sig")" > "$3"
}

# -rawin là bắt buộc với Ed25519: nó ký toàn bộ message, không pre-hash như RSA/ECDSA.
# Thiếu cờ này openssl sẽ báo lỗi thay vì tạo chữ ký sai — nhưng ghi lại đây vì đó chính là
# cái phải khớp với Signature.getInstance("Ed25519").update(bytes) ở phía JDK.
sign "$TMP/primary.key"   manifest.json manifest.json.sig
sign "$TMP/untrusted.key" manifest.json manifest-untrusted.json.sig

echo "✓ fixture-primary.pub / fixture-untrusted.pub  (keyId primary = $KEYID)"
echo "✓ manifest.json.sig  (ký bằng khoá tin cậy)"
echo "✓ manifest-untrusted.json.sig  (ký bằng khoá LẠ — phải bị từ chối)"

# Đối chứng bằng chính openssl trước khi giao cho test JDK
base64 -d <<< "$(python3 -c "import json;print(json.load(open('manifest.json.sig'))['sig'])")" > "$TMP/v.sig"
openssl pkeyutl -verify -rawin -pubin -inkey fixture-primary.pub -in manifest.json -sigfile "$TMP/v.sig"
