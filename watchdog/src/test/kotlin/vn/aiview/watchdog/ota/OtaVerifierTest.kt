package vn.aiview.watchdog.ota

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Test cho [OtaVerifier].
 *
 * Giá trị lớn nhất nằm ở nhóm "interop với openssl": fixture được ký bằng **openssl** (đúng
 * công cụ máy air-gap sẽ dùng) và verify bằng **JDK** (đúng thứ máy field sẽ dùng). Nếu chỉ
 * ký bằng KeyPairGenerator của JDK rồi verify bằng JDK thì test luôn xanh mà không chứng minh
 * được gì về openssl — và đó chính là chỗ cả cơ chế sẽ vỡ ở production.
 *
 * Sinh lại fixture: bash src/test/resources/ota/regenerate-fixtures.sh
 */
class OtaVerifierTest {

    private fun res(name: String): ByteArray =
        javaClass.getResourceAsStream("/ota/$name")?.readBytes()
            ?: error("thiếu test resource /ota/$name — chạy src/test/resources/ota/regenerate-fixtures.sh")

    private fun resText(name: String) = res(name).decodeToString()

    private val trustedKeyring by lazy {
        listOf(OtaVerifier.parsePublicKeyPem(resText("fixture-primary.pub"), "fixture-primary"))
    }

    // ── Interop openssl → JDK: đường đi đúng ─────────────────────────────────────────────

    @Test
    fun `chu ky openssl duoc JDK chap nhan`() {
        val r = OtaVerifier.verify(res("manifest.json"), res("manifest.json.sig"), trustedKeyring)
        val ok = assertIs<OtaVerifier.Result.Ok>(r, "chữ ký openssl phải được JDK verify thành công")
        assertEquals("1.1.0.0", ok.verified.manifest.version)
        assertEquals("AIVIEW-NVR-TEST", ok.verified.manifest.model)
        assertEquals(1, ok.verified.manifest.images.size)
        assertEquals("nvr-wrapper:latest", ok.verified.manifest.images[0].tag)
        // expectJson là thứ chặn "health 200 nhưng container cũ vẫn chạy"
        assertEquals("1.1.0.0", ok.verified.manifest.healthChecks[1].expectJson["version"])
    }

    @Test
    fun `keyId tinh ra tu public key khop voi keyId openssl ghi trong file sig`() {
        // keyId phải TÍNH RA được từ khoá, không phải đọc từ file .sig — nếu không thì kẻ tấn
        // công khai keyId gì cũng được. Test này chốt rằng công thức hai bên giống nhau.
        val fromKey = trustedKeyring.single().keyId
        val fromSig = OtaManifest.json
            .decodeFromString(OtaVerifier.SigFile.serializer(), resText("manifest.json.sig")).keyId
        assertEquals(fromSig, fromKey, "công thức keyId của regenerate-fixtures.sh và OtaVerifier phải khớp")
    }

    // ── Các đường bị từ chối ─────────────────────────────────────────────────────────────

    @Test
    fun `sua mot byte trong manifest lam chu ky sai`() {
        val tampered = res("manifest.json").copyOf()
        // Đổi 'A' của AIVIEW thành 'B' — thay đổi nhỏ nhất có thể mà vẫn là JSON hợp lệ
        val idx = tampered.indexOfSub("AIVIEW-NVR-TEST".toByteArray())
        assertTrue(idx >= 0, "fixture phải chứa chuỗi model để test sửa byte")
        tampered[idx] = 'B'.code.toByte()

        val r = OtaVerifier.verify(tampered, res("manifest.json.sig"), trustedKeyring)
        val rej = assertIs<OtaVerifier.Result.Rejected>(r)
        assertEquals("SIG_INVALID", rej.code)
    }

    @Test
    fun `ky bang khoa la bi tu choi`() {
        val r = OtaVerifier.verify(res("manifest.json"), res("manifest-untrusted.json.sig"), trustedKeyring)
        val rej = assertIs<OtaVerifier.Result.Rejected>(r)
        assertEquals("SIG_INVALID", rej.code)
        // Lý do phải kèm giá trị thật để lần ra nguyên nhân từ xa
        assertTrue(rej.reason.contains("keyId"), "lý do phải nói keyId nào đã thử: ${rej.reason}")
    }

    @Test
    fun `keyring rong tu choi moi goi`() {
        val r = OtaVerifier.verify(res("manifest.json"), res("manifest.json.sig"), emptyList())
        assertEquals("NO_TRUSTED_KEY", assertIs<OtaVerifier.Result.Rejected>(r).code)
    }

    @Test
    fun `thuat toan khac ed25519 bi tu choi`() {
        val sig = resText("manifest.json.sig").replace("\"ed25519\"", "\"rsa-sha256\"")
        val r = OtaVerifier.verify(res("manifest.json"), sig.toByteArray(), trustedKeyring)
        assertEquals("SIG_ALG", assertIs<OtaVerifier.Result.Rejected>(r).code)
    }

    @Test
    fun `chu ky sai do dai bi loai truoc khi goi crypto`() {
        val short = Base64.getEncoder().encodeToString(ByteArray(63))
        val sig = """{"keyId":"deadbeef","alg":"ed25519","sig":"$short"}"""
        val r = OtaVerifier.verify(res("manifest.json"), sig.toByteArray(), trustedKeyring)
        val rej = assertIs<OtaVerifier.Result.Rejected>(r)
        assertEquals("SIG_MALFORMED", rej.code)
        assertTrue(rej.reason.contains("63"), "lý do phải nêu độ dài thật: ${rej.reason}")
    }

    @Test
    fun `file sig khong phai json bi tu choi`() {
        val r = OtaVerifier.verify(res("manifest.json"), "khong phai json".toByteArray(), trustedKeyring)
        assertEquals("SIG_MALFORMED", assertIs<OtaVerifier.Result.Rejected>(r).code)
    }

    @Test
    fun `manifest qua lon bi loai truoc khi cap bo nho`() {
        val huge = ByteArray(OtaVerifier.MAX_MANIFEST_BYTES + 1) { '{'.code.toByte() }
        val r = OtaVerifier.verify(huge, res("manifest.json.sig"), trustedKeyring)
        assertEquals("MANIFEST_SIZE", assertIs<OtaVerifier.Result.Rejected>(r).code)
    }

    @Test
    fun `manifest rong bi tu choi`() {
        val r = OtaVerifier.verify(ByteArray(0), res("manifest.json.sig"), trustedKeyring)
        assertEquals("MANIFEST_SIZE", assertIs<OtaVerifier.Result.Rejected>(r).code)
    }

    /**
     * Ca khó: chữ ký ĐÚNG nhưng schema không đọc được.
     *
     * Ký bằng khoá sinh tại chỗ (JDK) vì cần ký một manifest tự dựng — fixture openssl là
     * tĩnh. Ở đây mục tiêu không phải interop mà là logic gate schema.
     */
    @Test
    fun `schema khong ho tro bi tu choi du chu ky dung`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val manifest = """{"schema":999,"model":"X","version":"1.0.0.0"}""".toByteArray()
        val raw = Signature.getInstance("Ed25519").run {
            initSign(kp.private); update(manifest); sign()
        }
        val der = kp.public.encoded
        val keyId = OtaVerifier.keyIdOf(der)
        val sigFile = """{"keyId":"$keyId","alg":"ed25519","sig":"${Base64.getEncoder().encodeToString(raw)}"}"""
        val keyring = listOf(OtaVerifier.TrustedKey(keyId, "local", kp.public))

        val r = OtaVerifier.verify(manifest, sigFile.toByteArray(), keyring)
        val rej = assertIs<OtaVerifier.Result.Rejected>(r)
        assertEquals("SCHEMA_UNSUPPORTED", rej.code)
        assertTrue(rej.reason.contains("999"), "lý do phải nêu schema thật: ${rej.reason}")
    }

    @Test
    fun `keyId khai sai trong file sig van verify duoc vi thu het keyring`() {
        // keyId chỉ là gợi ý để thử đúng khoá trước cho nhanh. Khai sai không đạt được gì —
        // chữ ký vẫn phải đúng với một khoá tin cậy thật.
        val sig = resText("manifest.json.sig")
            .replace(Regex("\"keyId\":\"[0-9a-f]+\""), "\"keyId\":\"00000000\"")
        val r = OtaVerifier.verify(res("manifest.json"), sig.toByteArray(), trustedKeyring)
        assertIs<OtaVerifier.Result.Ok>(r, "keyId sai không được làm gói hợp lệ bị từ chối")
    }

    // ── Keyring ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `hai khoa khac nhau cho hai keyId khac nhau`() {
        val a = OtaVerifier.parsePublicKeyPem(resText("fixture-primary.pub"), "a")
        val b = OtaVerifier.parsePublicKeyPem(resText("fixture-untrusted.pub"), "b")
        assertNotEquals(a.keyId, b.keyId)
        assertEquals(8, a.keyId.length, "keyId là 8 hex đầu của sha256(DER)")
    }

    @Test
    fun `keyring co backup thi goi ky bang primary van qua`() {
        // Đây là cấu hình thật trên máy field: tin cả ota-primary lẫn ota-backup từ ngày cài,
        // để mất một khoá không phải ra từng máy nạp khoá mới.
        val keyring = listOf(
            OtaVerifier.parsePublicKeyPem(resText("fixture-untrusted.pub"), "ota-backup"),
            OtaVerifier.parsePublicKeyPem(resText("fixture-primary.pub"), "ota-primary"),
        )
        assertIs<OtaVerifier.Result.Ok>(
            OtaVerifier.verify(res("manifest.json"), res("manifest.json.sig"), keyring))
    }

    @Test
    fun `pem rac bi tu choi chu khong crash`() {
        listOf("", "-----BEGIN PUBLIC KEY-----\n-----END PUBLIC KEY-----", "khong phai pem",
               "-----BEGIN PUBLIC KEY-----\n!!!khong-phai-base64!!!\n-----END PUBLIC KEY-----")
            .forEach { bad ->
                val e = runCatching { OtaVerifier.parsePublicKeyPem(bad, "bad") }.exceptionOrNull()
                assertTrue(e is IllegalArgumentException, "PEM rác phải ném IllegalArgumentException, được: $e")
            }
    }

    @Test
    fun `loadKeyring thu muc khong ton tai tra ve rong chu khong nem`() {
        assertTrue(OtaVerifier.loadKeyring(File("/khong/ton/tai/o/dau/ca")).isEmpty())
    }

    // ── sha256 + so sánh hash ────────────────────────────────────────────────────────────

    @Test
    fun `sha256 khop gia tri chuan`() {
        // Vector chuẩn: sha256 của chuỗi rỗng
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            OtaVerifier.sha256Hex(ByteArray(0)))
        assertEquals("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb",
            OtaVerifier.sha256Hex("a".toByteArray()))
    }

    @Test
    fun `sha256 theo luong bang sha256 trong bo nho`() {
        // File image 3GB không được nạp hết vào RAM nên có bản streaming — hai bản phải khớp.
        val data = ByteArray(300_000) { (it % 251).toByte() }
        assertEquals(OtaVerifier.sha256Hex(data), OtaVerifier.sha256Hex(data.inputStream()))
    }

    @Test
    fun `hashEquals khong phan biet hoa thuong nhung tu choi chuoi rong`() {
        val h = "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855"
        assertTrue(OtaVerifier.hashEquals(h, h.lowercase()))
        assertTrue(OtaVerifier.hashEquals(" $h ", h.lowercase()))
        assertFalse(OtaVerifier.hashEquals(h, h.lowercase().dropLast(1) + "0"))
        // Hash rỗng KHÔNG được coi là khớp nhau — nếu không thì manifest thiếu sha256 sẽ qua hết
        assertFalse(OtaVerifier.hashEquals("", ""))
        assertFalse(OtaVerifier.hashEquals(h, ""))
    }

    private fun ByteArray.indexOfSub(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
