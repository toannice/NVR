package vn.aiview.watchdog.ota

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Xác minh chữ ký Ed25519 + sha256 cho gói OTA.
 *
 * Ed25519 verify dùng `java.security.Signature` có sẵn trong JDK 21 (JEP 339, từ JDK 15) —
 * KHÔNG thêm dependency crypto nào. Phía ký dùng openssl trên máy air-gap; hai bên gặp nhau
 * ở đúng chuẩn: khoá công khai dạng X.509 SubjectPublicKeyInfo, chữ ký 64 byte thô.
 *
 * ─── Thứ tự bắt buộc: VERIFY TRƯỚC, PARSE SAU ───────────────────────────────────────────
 * Chữ ký được kiểm trên BYTE THÔ của manifest.json, không cần parse gì. Nhờ vậy bộ parse JSON
 * không bao giờ phải nhìn dữ liệu chưa được xác thực — bỏ hẳn parser ra khỏi bề mặt tấn công.
 *
 * API ở đây ép đúng thứ tự đó: [OtaManifest] chỉ lấy được qua [VerifiedManifest], mà
 * [VerifiedManifest] chỉ [OtaVerifier] tạo được (constructor private, cùng file). Không có
 * đường nào để lỡ dùng manifest chưa verify — đường sai không tồn tại chứ không phải chỉ
 * "đừng làm thế".
 */
object OtaVerifier {
    private val log = LoggerFactory.getLogger("OtaVerifier")

    /** Chữ ký Ed25519 luôn đúng 64 byte. Khác là loại thẳng, không cần gọi crypto. */
    private const val ED25519_SIG_LEN = 64

    /** Chặn manifest phình to bất thường trước khi cấp bộ nhớ cho nó. Bình thường ~1–4KB. */
    const val MAX_MANIFEST_BYTES = 256 * 1024

    // ── Kết quả ──────────────────────────────────────────────────────────────────────────

    sealed interface Result {
        data class Ok(val verified: VerifiedManifest) : Result
        /** [reason] viết cho người vận hành đọc, đã kèm giá trị thật để lần ra nguyên nhân. */
        data class Rejected(val code: String, val reason: String) : Result
    }

    /**
     * Manifest ĐÃ xác minh chữ ký. Constructor private ⇒ chỉ [OtaVerifier] tạo được.
     *
     * [rawBytes] được giữ lại vì bước sau còn cần: manifest trong gói phải BYTE-IDENTICAL với
     * manifest remote đã verify, nếu không kẻ tấn công ghép được manifest-của-bản-này với
     * gói-của-bản-khác (cả hai đều có chữ ký thật của bạn).
     */
    class VerifiedManifest private constructor(
        val manifest: OtaManifest,
        val keyId: String,
        val rawBytes: ByteArray,
    ) {
        val rawSha256: String by lazy { sha256Hex(rawBytes) }

        /** So byte-identical với một manifest khác (dùng cho bước đối chiếu remote ↔ trong gói). */
        fun sameBytesAs(other: ByteArray): Boolean = MessageDigest.isEqual(rawBytes, other)

        internal companion object {
            fun create(m: OtaManifest, keyId: String, raw: ByteArray) = VerifiedManifest(m, keyId, raw)
        }
    }

    // ── Keyring ──────────────────────────────────────────────────────────────────────────

    /**
     * Một khoá công khai được tin cậy.
     *
     * [keyId] TÍNH RA từ 8 hex đầu của sha256(DER) — không phải chuỗi tự đặt trong file, nên
     * không ai khai sai được keyId để trỏ sang khoá khác.
     */
    data class TrustedKey(val keyId: String, val label: String, val key: PublicKey)

    /**
     * Nạp mọi `*.pub` trong [dir] (mặc định /opt/nvr/keys/ota-trust.d).
     *
     * Vì sao nhiều khoá: keyring có `ota-primary` + `ota-backup` được tin cậy TỪ NGÀY CÀI.
     * Nếu chỉ tin 1 khoá và nó mất/lộ thì phải ra TỪNG máy field để nạp khoá mới — với fleet
     * thương mại là bất khả thi. Có backup sẵn thì rotate chỉ là đổi khoá ký ở máy air-gap.
     */
    fun loadKeyring(dir: File): List<TrustedKey> {
        if (!dir.isDirectory) {
            log.error("KEYRING_MISSING thư mục khoá tin cậy không tồn tại: ${dir.absolutePath}")
            return emptyList()
        }
        val keys = dir.listFiles { f -> f.isFile && f.name.endsWith(".pub") }
            ?.sortedBy { it.name }
            ?.mapNotNull { f ->
                runCatching { parsePublicKeyPem(f.readText(), f.nameWithoutExtension) }
                    .onFailure { log.error("KEYRING_BAD_KEY bỏ qua ${f.name}: ${it.message}") }
                    .getOrNull()
            } ?: emptyList()

        if (keys.isEmpty()) log.error("KEYRING_EMPTY không nạp được khoá nào từ ${dir.absolutePath} — mọi gói OTA sẽ bị từ chối")
        else log.info("KEYRING_LOADED ${keys.size} khoá: ${keys.joinToString { "${it.label}(${it.keyId})" }}")
        return keys
    }

    /** Parse PEM `-----BEGIN PUBLIC KEY-----` (X.509 SubjectPublicKeyInfo) thành Ed25519 key. */
    fun parsePublicKeyPem(pem: String, label: String): TrustedKey {
        val b64 = pem.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("-----") }
            .joinToString("")
        require(b64.isNotEmpty()) { "PEM rỗng hoặc thiếu thân base64" }

        val der = try {
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("thân PEM không phải base64 hợp lệ: ${e.message}")
        }

        val key = try {
            KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(der))
        } catch (e: Exception) {
            // Bắt cả trường hợp đưa khoá RSA/EC vào đây: KeyFactory Ed25519 sẽ từ chối.
            throw IllegalArgumentException("không phải khoá công khai Ed25519 (${e.javaClass.simpleName}: ${e.message})")
        }
        return TrustedKey(keyId = keyIdOf(der), label = label, key = key)
    }

    /** keyId = 8 hex đầu của sha256(DER SubjectPublicKeyInfo). Tính ra được, không khai được. */
    fun keyIdOf(der: ByteArray): String = sha256Hex(der).take(8)

    // ── File chữ ký ──────────────────────────────────────────────────────────────────────

    /** Nội dung `manifest.json.sig`. */
    @Serializable
    data class SigFile(
        val keyId: String = "",
        val alg: String = "",
        val sig: String = "",
    )

    // ── Đường verify chính ───────────────────────────────────────────────────────────────

    /**
     * Kiểm chữ ký trên [manifestBytes] rồi mới parse.
     *
     * Trả [Result.Rejected] với [Result.Rejected.code] ổn định để grep log, chứ không ném
     * exception — thất bại xác minh là kết cục BÌNH THƯỜNG của luồng này (gói hỏng, gói của
     * model khác, mirror trả file cũ), không phải sự cố lập trình.
     */
    fun verify(manifestBytes: ByteArray, sigFileBytes: ByteArray, keyring: List<TrustedKey>): Result {
        if (keyring.isEmpty()) {
            return Result.Rejected("NO_TRUSTED_KEY", "keyring rỗng — không có khoá nào để đối chiếu")
        }
        if (manifestBytes.isEmpty() || manifestBytes.size > MAX_MANIFEST_BYTES) {
            return Result.Rejected("MANIFEST_SIZE",
                "manifest.json kích thước bất thường: ${manifestBytes.size} byte (cho phép 1..$MAX_MANIFEST_BYTES)")
        }

        // 1. Đọc file .sig. Đây là dữ liệu chưa tin được, nhưng nó chỉ chứa 3 chuỗi ngắn và
        //    không quyết định gì ngoài việc "thử khoá nào trước".
        val sigFile = try {
            OtaManifest.json.decodeFromString(SigFile.serializer(), sigFileBytes.decodeToString())
        } catch (e: Exception) {
            return Result.Rejected("SIG_MALFORMED", "manifest.json.sig không đọc được: ${e.message}")
        }
        if (sigFile.alg != "ed25519") {
            return Result.Rejected("SIG_ALG", "thuật toán chữ ký không hỗ trợ: '${sigFile.alg}' (chỉ nhận ed25519)")
        }

        val sigRaw = try {
            Base64.getDecoder().decode(sigFile.sig.trim())
        } catch (e: IllegalArgumentException) {
            return Result.Rejected("SIG_MALFORMED", "trường sig không phải base64: ${e.message}")
        }
        if (sigRaw.size != ED25519_SIG_LEN) {
            return Result.Rejected("SIG_MALFORMED", "chữ ký dài ${sigRaw.size} byte, Ed25519 phải đúng $ED25519_SIG_LEN")
        }

        // 2. Chọn khoá. keyId trong file .sig chỉ là GỢI Ý để thử đúng khoá trước cho nhanh;
        //    nếu nó không khớp khoá nào thì vẫn thử hết keyring. Kẻ tấn công khai keyId sai
        //    không đạt được gì — chữ ký vẫn phải đúng với một khoá tin cậy thật.
        val ordered = keyring.sortedByDescending { it.keyId == sigFile.keyId }

        for (tk in ordered) {
            val ok = try {
                Signature.getInstance("Ed25519").run {
                    initVerify(tk.key)
                    update(manifestBytes)
                    verify(sigRaw)
                }
            } catch (e: Exception) {
                log.warn("SIG_VERIFY_ERROR khoá ${tk.label}(${tk.keyId}): ${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (!ok) continue

            // 3. Chữ ký đúng ⇒ GIỜ mới parse.
            val manifest = try {
                OtaManifest.json.decodeFromString(OtaManifest.serializer(), manifestBytes.decodeToString())
            } catch (e: Exception) {
                // Chữ ký đúng mà nội dung không parse được = chính bạn ký một file hỏng.
                return Result.Rejected("MANIFEST_MALFORMED",
                    "chữ ký hợp lệ (khoá ${tk.label}) nhưng manifest không parse được: ${e.message}")
            }
            if (manifest.schema != OtaManifest.SUPPORTED_SCHEMA) {
                return Result.Rejected("SCHEMA_UNSUPPORTED",
                    "manifest schema=${manifest.schema}, watchdog này chỉ đọc schema=${OtaManifest.SUPPORTED_SCHEMA}")
            }
            log.info("SIG_OK manifest version=${manifest.version} model=${manifest.model} khoá=${tk.label}(${tk.keyId})")
            return Result.Ok(VerifiedManifest.create(manifest, tk.keyId, manifestBytes))
        }

        return Result.Rejected("SIG_INVALID",
            "chữ ký không khớp bất kỳ khoá tin cậy nào (đã thử ${keyring.size}: " +
                "${keyring.joinToString { it.keyId }}; file .sig khai keyId=${sigFile.keyId})")
    }

    // ── sha256 ───────────────────────────────────────────────────────────────────────────

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Băm theo luồng — file image 3GB không được nạp hết vào RAM. */
    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    fun sha256Hex(file: File): String = file.inputStream().buffered().use { sha256Hex(it) }

    /**
     * So sánh sha256 dạng hex, không phân biệt hoa thường, thời gian hằng số.
     *
     * Dùng [MessageDigest.isEqual] chứ không phải `==`: so chuỗi thoát ra ở byte khác nhau đầu
     * tiên nên thời gian chạy tiết lộ số ký tự đầu khớp. Với hash của file mình tự tải thì
     * kênh phụ này gần như không khai thác được, nhưng đây là hàm so hash — dùng đúng thói
     * quen ở đây rẻ hơn nhiều so với việc sau này copy nó sang chỗ có khai thác được thật.
     */
    fun hashEquals(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return MessageDigest.isEqual(
            a.trim().lowercase().toByteArray(),
            b.trim().lowercase().toByteArray(),
        )
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append("0123456789abcdef"[v ushr 4])
            out.append("0123456789abcdef"[v and 0x0F])
        }
        return out.toString()
    }
}
