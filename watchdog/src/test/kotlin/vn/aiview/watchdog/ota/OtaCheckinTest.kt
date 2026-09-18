package vn.aiview.watchdog.ota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtaCheckinTest {

    /**
     * Hậu quả nếu hỏng: trang /noi-bo/ota lọc "có lỗi" bằng
     * last_result != ok && != no_update. Ánh xạ sai làm máy lỗi lọt qua bộ lọc
     * đó — máy đang hỏng mà KHÔNG xuất hiện trong danh sách cần xem.
     */
    @Test
    fun `anh xa ket cuc sang tu vung hop dong`() {
        val c = OtaCheckin.anhXaKetCuc
        assertEquals("ok", c("COMMITTED"))
        assertEquals("no_update", c(""))
        assertEquals("rolled_back", c("ROLLED_BACK"))
        assertEquals("rolled_back", c("ROLLBACK_FAILED"))
        assertEquals("install_failed", c("REJECTED"))
        assertEquals("sig_invalid", c("SIG_INVALID"))
        assertEquals("fetch_failed", c("FETCH_FAILED"))
        assertEquals("no_space", c("NO_SPACE"))
    }

    /**
     * Hậu quả nếu hỏng: một outcome MỚI do đời sau thêm sẽ được ánh xạ thành
     * "ok", và một thất bại biến thành thành công trên bảng theo dõi. Đây là
     * loại lỗi không ai phát hiện: bảng xanh, máy hỏng.
     */
    @Test
    fun `ket cuc la khong bao gio thanh ok`() {
        for (la in listOf("MOT_KET_CUC_MOI", "PARTIAL", "unknown", "Committed", "ok")) {
            assertEquals("install_failed", OtaCheckin.anhXaKetCuc(la),
                "kết cục lạ \"$la\" bị ánh xạ sai")
        }
    }

    /**
     * Hậu quả nếu hỏng: lastError dài làm máy chủ trả 413 và mất LUÔN cả dòng
     * báo cáo — mất cả những trường khác vốn vẫn dùng được.
     */
    @Test
    fun `lastError bi cat ve dung gioi han hop dong`() {
        assertEquals(OtaCheckin.MAX_LOI, OtaCheckin.catLoi("x".repeat(500)).length)
        assertEquals(256, OtaCheckin.MAX_LOI)
        // Chuỗi ngắn phải giữ NGUYÊN — cắt cả chuỗi ngắn là mất chi tiết chẩn đoán.
        assertEquals("loi ngan", OtaCheckin.catLoi("loi ngan"))
        assertEquals("", OtaCheckin.catLoi(""))
    }

    /**
     * Hậu quả nếu hỏng: check-in hỏng làm vòng OTA dừng, và một máy chủ theo
     * dõi chết kéo theo CẢ ĐỘI ngừng nhận bản vá. Đây là bất biến quan trọng
     * nhất của lớp này: kênh QUAN SÁT không được thành kênh ĐIỀU KHIỂN.
     */
    @Test
    fun `bao khong nem ngoai le khi may chu khong ton tai`() {
        val d = kotlin.io.path.createTempDirectory("ckin").toFile()
        val c = OtaCheckin(
            baseUrl = "http://127.0.0.1:1",   // chắc chắn không ai nghe
            model = "AIVIEW-NVR-X1",
            enrollKey = "khong-quan-trong",
            tokenFile = java.io.File(d, "token"),
            deviceId = "NVR-thu",
        )
        val ok = c.bao(OtaState(installedVersion = "1.0.0.6"), "1.0.0.6", 1000, 60)
        assertTrue(!ok, "phải trả false, không ném")
    }

    /**
     * Hậu quả nếu hỏng: thiếu enroll key làm lớp này ném lỗi lúc khởi động thay
     * vì chỉ ghi nhật ký — máy mất đường cập nhật vì thiếu một thứ chỉ dùng cho
     * việc quan sát.
     */
    @Test
    fun `thieu enroll key chi tra false chu khong nem`() {
        val d = kotlin.io.path.createTempDirectory("ckin2").toFile()
        val c = OtaCheckin("http://127.0.0.1:1", "AIVIEW-NVR-X1", "",
            java.io.File(d, "token"), "NVR-thu")
        assertTrue(!c.bao(OtaState(), "1.0.0.6", 1, 1))
    }
}
