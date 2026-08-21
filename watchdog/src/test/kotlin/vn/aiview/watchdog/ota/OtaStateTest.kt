package vn.aiview.watchdog.ota

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtaStateTest {

    private lateinit var dir: File
    private lateinit var store: OtaStateStore

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ota-state-test").toFile()
        store = OtaStateStore(File(dir, "state.json"))
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `chua co file thi tra trang thai trang`() {
        val s = store.load()
        assertEquals("", s.installedVersion)
        assertEquals("", s.floorVersion)
    }

    @Test
    fun `ghi roi doc lai duoc nguyen`() {
        store.save(OtaState(installedVersion = "1.2.0.0", floorVersion = "1.2.0.0", lastEtag = "\"abc\""))
        val s = store.load()
        assertEquals("1.2.0.0", s.installedVersion)
        assertEquals("\"abc\"", s.lastEtag)
    }

    // ── Sàn chống hạ cấp ─────────────────────────────────────────────────────────────────

    @Test
    fun `commit nang ca version dang cai lan san`() {
        val s = store.commit(OtaState(installedVersion = "1.1.0.0", floorVersion = "1.1.0.0"),
            "1.2.0.0", "ok")
        assertEquals("1.2.0.0", s.installedVersion)
        assertEquals("1.2.0.0", s.floorVersion)
        assertEquals("COMMITTED", s.lastResult)
    }

    @Test
    fun `commit khong bao gio ha san`() {
        // Cài lại 1.2 khi sàn đã là 1.3 (ví dụ: đã chạy 1.3 rồi rollback). Sàn phải GIỮ 1.3.
        val s = store.commit(OtaState(installedVersion = "1.2.0.0", floorVersion = "1.3.0.0"),
            "1.2.5.0", "ap lai")
        assertEquals("1.2.5.0", s.installedVersion)
        assertEquals("1.3.0.0", s.floorVersion, "sàn không được hạ khi commit bản thấp hơn")
    }

    @Test
    fun `san dung phep so version chu khong phai so chuoi`() {
        val s = store.commit(OtaState(floorVersion = "1.9.0.0"), "1.10.0.0", "ok")
        assertEquals("1.10.0.0", s.floorVersion, "1.10 mới hơn 1.9 nên sàn phải nâng lên 1.10")
    }

    /**
     * Ca then chốt: rollback KHÔNG được nâng sàn.
     *
     * Nếu nâng, một bản lỗi vừa rollback sẽ chặn luôn chính bản cũ đang chạy — máy tự khoá
     * mình ra khỏi mọi đường cập nhật, và cách duy nhất để cứu là ra tận nơi.
     */
    @Test
    fun `rollback khong nang san`() {
        val before = OtaState(installedVersion = "1.1.0.0", floorVersion = "1.1.0.0")
        val after = store.recordFailure(before, "1.2.0.0", "ROLLED_BACK", "health check that bai")
        assertEquals("1.1.0.0", after.floorVersion, "sàn phải giữ nguyên sau rollback")
        assertEquals("1.1.0.0", after.installedVersion, "version đang cài phải giữ nguyên sau rollback")
        assertEquals("ROLLED_BACK", after.lastResult)
    }

    // ── Lịch sử ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `lich su gioi han 20 muc`() {
        var s = OtaState()
        repeat(30) { i -> s = store.commit(s, "1.0.0.$i", "lan $i") }
        assertEquals(OtaState.HISTORY_LIMIT, s.history.size)
        // Giữ 20 mục GẦN NHẤT, không phải 20 mục đầu
        assertEquals("lan 29", s.history.last().detail)
    }

    @Test
    fun `lich su ghi ca huong di tu dau den dau`() {
        val s = store.commit(OtaState(installedVersion = "1.1.0.0"), "1.2.0.0", "ok")
        val h = s.history.single()
        assertEquals("1.1.0.0", h.fromVersion)
        assertEquals("1.2.0.0", h.toVersion)
        assertEquals("COMMITTED", h.outcome)
    }

    // ── Chống hỏng file ──────────────────────────────────────────────────────────────────

    @Test
    fun `file hong duoc giu lai de dieu tra chu khong xoa`() {
        val f = File(dir, "state.json")
        f.writeText("{ day khong phai json hop le")
        val s = store.load()

        // Vẫn trả trạng thái trắng để máy chạy tiếp được...
        assertEquals("", s.floorVersion)
        // ...nhưng bản lỗi phải được giữ lại, vì mất floorVersion là mở lại cửa downgrade attack
        val corrupt = dir.listFiles { f2 -> f2.name.startsWith("state.json.corrupt.") }
        assertTrue(corrupt != null && corrupt.isNotEmpty(), "phải lưu lại bản hỏng để điều tra")
    }

    @Test
    fun `field la khong biet duoc bo qua de tuong thich tien`() {
        // state.json do bản watchdog MỚI hơn ghi, có field bản cũ không biết → không được crash
        File(dir, "state.json").writeText("""{"installedVersion":"1.2.0.0","fieldMoiToanhTinh":42}""")
        assertEquals("1.2.0.0", store.load().installedVersion)
    }

    @Test
    fun `ghi khong de lai file tmp`() {
        store.save(OtaState(installedVersion = "1.0.0.0"))
        assertFalse(File(dir, "state.json.tmp").exists(), "file tạm phải được rename, không để lại rác")
    }
}
