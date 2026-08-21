package vn.aiview.watchdog.ota

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Test cho [OtaGates] — lớp chính sách "gói này có NÊN được cài lên máy này, lúc này".
 *
 * Mỗi ca phải bị từ chối với ĐÚNG mã lý do, không chỉ "fail". Vì khi một máy ngoài field từ
 * chối cập nhật lúc 2h sáng, thứ duy nhất bạn có là một dòng Telegram — nó phải nói được vì
 * sao, chứ không phải "update failed".
 */
class OtaGatesTest {

    private val MODEL = "AIVIEW-NVR-TEST"

    private fun manifest(
        version: String = "1.2.0.0",
        model: String = MODEL,
        minFrom: String = "",
        minApplier: String = "",
        expiresAt: String = "2099-01-01T00:00:00Z",
        risk: String = "normal",
        autoApply: Boolean = true,
        requiresFreeBytes: Long = 0,
        images: List<OtaImage> = listOf(
            OtaImage(name = "nvr-wrapper", source = "tarball", path = "images/w.tar.zst",
                sha256 = "aa", tag = "nvr-wrapper:latest")),
        files: List<OtaFile> = emptyList(),
    ) = OtaManifest(
        schema = 1, model = model, version = version, minFrom = minFrom, minApplier = minApplier,
        expiresAt = expiresAt, risk = risk, autoApply = autoApply,
        requiresFreeBytes = requiresFreeBytes, images = images, files = files,
    )

    private fun ctx(
        trigger: OtaGates.Trigger = OtaGates.Trigger.SCHEDULED,
        applierVersion: String = "1.0.0",
        freeBytes: Long = 100L * 1024 * 1024 * 1024,
        packageBytes: Long = 0,
        model: String = MODEL,
        now: Instant = Instant.parse("2026-08-20T02:00:00Z"),
    ) = OtaGates.Context(model, applierVersion, trigger, freeBytes, packageBytes, now)

    private fun state(installed: String = "1.1.0.0", floor: String = installed) =
        OtaState(installedVersion = installed, floorVersion = floor)

    private fun reject(r: OtaGates.Result) = assertIs<OtaGates.Result.Reject>(r)

    // ── Đường đi đúng ────────────────────────────────────────────────────────────────────

    @Test
    fun `nang cap binh thuong duoc cho qua`() {
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(manifest(), state(), ctx()))
    }

    // ── Gate 0: model ────────────────────────────────────────────────────────────────────

    @Test
    fun `goi cua model khac bi tu choi`() {
        val r = reject(OtaGates.evaluate(manifest(model = "P200-0803FG"), state(), ctx()))
        assertEquals("MODEL_MISMATCH", r.code)
        assertTrue(r.reason.contains("P200-0803FG") && r.reason.contains(MODEL))
    }

    @Test
    fun `model rong bi tu choi chu khong coi la khop`() {
        assertEquals("MODEL_MISMATCH", reject(OtaGates.evaluate(manifest(model = ""), state(), ctx())).code)
        assertEquals("MODEL_MISMATCH", reject(OtaGates.evaluate(manifest(), state(), ctx(model = ""))).code)
    }

    // ── Gate 2: SÀN CHỐNG HẠ CẤP (rollback / downgrade attack) ───────────────────────────

    @Test
    fun `goi cu hon san bi tu choi du chu ky hop le`() {
        // Đây là ca quan trọng nhất của cả file test. Gói 1.0.5 do CHÍNH BẠN ký, chữ ký hợp lệ,
        // sha256 khớp — nhưng là bản trước khi vá lỗ hổng. Chữ ký không chặn được nó, sàn thì có.
        val r = reject(OtaGates.evaluate(
            manifest(version = "1.0.5.0"),
            state(installed = "1.0.6.0", floor = "1.0.6.0"),
            ctx()))
        assertEquals("BELOW_FLOOR", r.code)
        assertTrue(r.reason.contains("1.0.5.0") && r.reason.contains("1.0.6.0"))
    }

    @Test
    fun `san cao hon ban dang chay van chan ha cap`() {
        // Tình huống thật: đã từng chạy 1.3 rồi rollback về 1.2. Sàn vẫn 1.3, nên gói 1.2.x
        // cũ hơn không được phép quay lại dù máy đang chạy 1.2.
        val r = reject(OtaGates.evaluate(
            manifest(version = "1.2.5.0"),
            OtaState(installedVersion = "1.2.0.0", floorVersion = "1.3.0.0"),
            ctx()))
        assertEquals("BELOW_FLOOR", r.code)
    }

    @Test
    fun `bang dung san thi duoc qua`() {
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(
            manifest(version = "1.3.0.0"),
            OtaState(installedVersion = "1.2.0.0", floorVersion = "1.3.0.0"),
            ctx()))
    }

    // ── Không lùi, không đứng yên ────────────────────────────────────────────────────────

    @Test
    fun `goi cu hon ban dang chay bi tu choi`() {
        val r = reject(OtaGates.evaluate(manifest(version = "1.0.0.0"),
            OtaState(installedVersion = "1.1.0.0", floorVersion = ""), ctx()))
        assertEquals("OLDER_THAN_INSTALLED", r.code)
    }

    @Test
    fun `cung version thi OTA tu dong khong lam gi`() {
        val r = reject(OtaGates.evaluate(manifest(version = "1.1.0.0"), state(installed = "1.1.0.0"), ctx()))
        assertEquals("ALREADY_CURRENT", r.code)
    }

    @Test
    fun `cung version thi admin upload tay van ap duoc de sua may loi`() {
        // Nới đúng một chỗ cho đường upload tay: máy đang lỗi, admin muốn áp lại chính bản đó.
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(
            manifest(version = "1.1.0.0"),
            state(installed = "1.1.0.0"),
            ctx(trigger = OtaGates.Trigger.MANUAL_UPLOAD)))
    }

    @Test
    fun `upload tay van khong ha cap duoc xuong duoi san`() {
        // Nới cho MANUAL_UPLOAD chỉ là "cùng version", KHÔNG phải "được hạ cấp".
        assertEquals("BELOW_FLOOR", reject(OtaGates.evaluate(
            manifest(version = "1.0.0.0"),
            state(installed = "1.1.0.0", floor = "1.1.0.0"),
            ctx(trigger = OtaGates.Trigger.MANUAL_UPLOAD))).code)
    }

    // ── Gate 1: minFrom (bậc thang nâng cấp) ────────────────────────────────────────────

    @Test
    fun `may qua cu bi chan lai cho di qua ban trung gian`() {
        val r = reject(OtaGates.evaluate(
            manifest(version = "1.4.0.0", minFrom = "1.2.0.0"),
            state(installed = "1.0.0.0", floor = "1.0.0.0"),
            ctx()))
        assertEquals("MINFROM_NOT_MET", r.code)
        // Người vận hành phải đọc ra được PHẢI LÀM GÌ, không chỉ "bị từ chối"
        assertTrue(r.reason.contains("1.2.0.0"), "lý do phải nêu bản trung gian cần đi qua: ${r.reason}")
    }

    @Test
    fun `dung bang minFrom thi qua`() {
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(
            manifest(version = "1.4.0.0", minFrom = "1.2.0.0"),
            state(installed = "1.2.0.0", floor = "1.2.0.0"), ctx()))
    }

    @Test
    fun `co minFrom ma khong biet ban dang chay thi tu choi`() {
        val r = reject(OtaGates.evaluate(manifest(minFrom = "1.0.0.0"), OtaState(), ctx()))
        assertEquals("MINFROM_UNKNOWN_CURRENT", r.code)
    }

    @Test
    fun `may trang khong co minFrom thi cai duoc`() {
        // Máy vừa cài từ bundle, state.json còn trắng. Không có minFrom thì không có gì phải chặn.
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(manifest(), OtaState(), ctx()))
    }

    // ── Gate 3: hạn manifest (freeze attack) ────────────────────────────────────────────

    @Test
    fun `manifest het han bi tu choi kem so ngay`() {
        val r = reject(OtaGates.evaluate(
            manifest(expiresAt = "2026-07-01T00:00:00Z"), state(),
            ctx(now = Instant.parse("2026-08-20T02:00:00Z"))))
        assertEquals("MANIFEST_EXPIRED", r.code)
        assertTrue(r.reason.contains("50"), "phải nêu số ngày đã hết hạn: ${r.reason}")
        assertTrue(r.reason.contains("chặn"), "phải gợi ý khả năng bị chặn mạng: ${r.reason}")
    }

    @Test
    fun `khong khai han dung thi khong chan`() {
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(manifest(expiresAt = ""), state(), ctx()))
    }

    @Test
    fun `han dung sai dinh dang bi tu choi chu khong bo qua`() {
        // Bỏ qua field không parse được nghĩa là kẻ tấn công chỉ cần làm nó méo đi để tắt gate.
        assertEquals("EXPIRY_MALFORMED",
            reject(OtaGates.evaluate(manifest(expiresAt = "hom qua"), state(), ctx())).code)
    }

    // ── Gate 4: minApplier ──────────────────────────────────────────────────────────────

    @Test
    fun `watchdog qua cu de hieu goi thi tu choi`() {
        val r = reject(OtaGates.evaluate(
            manifest(minApplier = "2.0.0"), state(), ctx(applierVersion = "1.0.0")))
        assertEquals("MINAPPLIER_NOT_MET", r.code)
    }

    @Test
    fun `watchdog du moi thi qua`() {
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(
            manifest(minApplier = "1.0.0"), state(), ctx(applierVersion = "1.2.0")))
    }

    // ── Payload phải nói rõ nó làm gì ───────────────────────────────────────────────────

    @Test
    fun `goi rong bi tu choi`() {
        assertEquals("EMPTY_PAYLOAD",
            reject(OtaGates.evaluate(manifest(images = emptyList(), files = emptyList()), state(), ctx())).code)
    }

    @Test
    fun `goi chi co file khong co image van hop le`() {
        // Bản chỉ sửa nginx.conf hoặc watchdog.yml là hợp lệ và rất nhẹ.
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(
            manifest(images = emptyList(), files = listOf(OtaFile("nginx/nginx.conf", "aa"))),
            state(), ctx()))
    }

    @Test
    fun `image registry khong ghim digest bi tu choi`() {
        // Tag thuần có thể bị trỏ lại sang image khác bất cứ lúc nào mà tên không đổi — dùng
        // tag thuần là bỏ luôn phần toàn vẹn mà đường registry vốn cho miễn phí.
        val r = reject(OtaGates.evaluate(manifest(images = listOf(
            OtaImage(name = "frigate", source = "registry",
                ref = "ghcr.io/blakeblackshear/frigate:0.17.0",
                tag = "ghcr.io/blakeblackshear/frigate:0.17.0"))), state(), ctx()))
        assertEquals("IMAGE_REF_NOT_PINNED", r.code)
    }

    @Test
    fun `image registry ghim digest thi qua`() {
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(manifest(images = listOf(
            OtaImage(name = "frigate", source = "registry",
                ref = "ghcr.io/blakeblackshear/frigate@sha256:" + "a".repeat(64),
                tag = "ghcr.io/blakeblackshear/frigate:0.17.0"))), state(), ctx()))
    }

    @Test
    fun `image tarball thieu sha256 bi tu choi`() {
        assertEquals("IMAGE_TARBALL_INCOMPLETE", reject(OtaGates.evaluate(manifest(images = listOf(
            OtaImage(name = "w", source = "tarball", path = "images/w.tar", sha256 = "",
                tag = "nvr-wrapper:latest"))), state(), ctx())).code)
    }

    @Test
    fun `image khong co tag bi tu choi`() {
        assertEquals("IMAGE_NO_TAG", reject(OtaGates.evaluate(manifest(images = listOf(
            OtaImage(name = "w", source = "tarball", path = "images/w.tar", sha256 = "aa", tag = ""))),
            state(), ctx())).code)
    }

    @Test
    fun `image source la khac bi tu choi`() {
        assertEquals("IMAGE_SOURCE_UNKNOWN", reject(OtaGates.evaluate(manifest(images = listOf(
            OtaImage(name = "w", source = "bittorrent", tag = "x:latest"))), state(), ctx())).code)
    }

    // ── Gói rủi ro cao ──────────────────────────────────────────────────────────────────

    @Test
    fun `goi frigate khong xin phep thi khong tu ap luc 2h sang`() {
        val r = reject(OtaGates.evaluate(
            manifest(risk = "high", autoApply = false), state(), ctx(trigger = OtaGates.Trigger.SCHEDULED)))
        assertEquals("HIGH_RISK_NEEDS_OPT_IN", r.code)
    }

    @Test
    fun `goi frigate xin phep tuong minh thi tu ap duoc`() {
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(
            manifest(risk = "high", autoApply = true), state(), ctx()))
    }

    @Test
    fun `goi frigate khong xin phep van ap duoc bang tay qua UI`() {
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(
            manifest(risk = "high", autoApply = false), state(),
            ctx(trigger = OtaGates.Trigger.MANUAL_UPLOAD)))
    }

    // ── Đĩa ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `khong du dia bi tu choi kem so MB`() {
        val r = reject(OtaGates.evaluate(
            manifest(requiresFreeBytes = 8L * 1024 * 1024 * 1024), state(),
            ctx(freeBytes = 1L * 1024 * 1024 * 1024)))
        assertEquals("DISK_INSUFFICIENT", r.code)
        assertTrue(r.reason.contains("8192") && r.reason.contains("1024"))
    }

    @Test
    fun `he so ba lan kich thuoc goi duoc ap dung`() {
        // Cần chỗ cho: gói + image đã giải nén + bản cũ giữ lại để rollback.
        val pkg = 500L * 1024 * 1024
        assertEquals("DISK_INSUFFICIENT", reject(OtaGates.evaluate(
            manifest(), state(), ctx(freeBytes = pkg * 2, packageBytes = pkg))).code)
        assertIs<OtaGates.Result.Pass>(OtaGates.evaluate(
            manifest(), state(), ctx(freeBytes = pkg * 4, packageBytes = pkg)))
    }

    // ── Version méo ─────────────────────────────────────────────────────────────────────

    @Test
    fun `version meo bi tu choi`() {
        listOf("", "abc", "1.0.0-beta", "1..0", "v1.0.0").forEach { v ->
            assertEquals("VERSION_MALFORMED",
                reject(OtaGates.evaluate(manifest(version = v), state(), ctx())).code,
                "version '$v' phải bị từ chối")
        }
    }
}
