package vn.aiview.watchdog.ota

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtaDeviceIdTest {
    private fun tmp(): File = createTempDirectory("devid").toFile()

    /**
     * Hậu quả nếu hỏng: .env để trống HOSTNAME thì mọi máy dùng chung tên
     * "nvr-edge-node" (mặc định ghi thẳng trong .env.template), nên CẢ ĐỘI gộp
     * thành MỘT dòng trên trang — mất hẳn khả năng quan sát từng máy, mà bảng
     * vẫn trông bình thường.
     */
    @Test
    fun `hostname mac dinh khong duoc dung lam dinh danh`() {
        val d = tmp()
        val mid = File(d, "machine-id").apply { writeText("abc123def456\n") }
        val id = OtaDeviceId.resolve(File(d, "device-id"), OtaDeviceId.HOSTNAME_MAC_DINH, mid)
        assertTrue(id.startsWith("NVR-"), "id = $id")
        assertTrue(id != OtaDeviceId.HOSTNAME_MAC_DINH)
    }

    /**
     * Hậu quả nếu hỏng: định danh đổi mỗi lần khởi động, và mỗi lần reboot máy
     * xuất hiện thành một thiết bị MỚI — bảng phình vô hạn bằng máy không tồn tại.
     */
    @Test
    fun `dinh danh on dinh qua cac lan goi`() {
        val d = tmp()
        val f = File(d, "device-id")
        val mid = File(d, "machine-id").apply { writeText("abc123def456\n") }
        val a = OtaDeviceId.resolve(f, "", mid)
        val b = OtaDeviceId.resolve(f, "", mid)
        assertEquals(a, b)
        assertTrue(f.isFile, "phải ghi xuống đĩa để sống qua reboot")
    }

    /**
     * Hậu quả nếu hỏng: hai máy khác nhau ra cùng định danh và gộp thành một
     * dòng — đúng lỗi mà việc bỏ "nvr-edge-node" sinh ra để tránh.
     */
    @Test
    fun `hai machine-id khac nhau ra hai dinh danh khac nhau`() {
        val d1 = tmp(); val d2 = tmp()
        val a = OtaDeviceId.resolve(File(d1, "device-id"), "",
            File(d1, "machine-id").apply { writeText("may-mot\n") })
        val b = OtaDeviceId.resolve(File(d2, "device-id"), "",
            File(d2, "machine-id").apply { writeText("may-hai\n") })
        assertTrue(a != b, "a=$a b=$b")
    }

    /**
     * Hậu quả nếu hỏng: HOSTNAME người vận hành đặt tay bị bỏ qua, và họ mất
     * cách tự đặt tên máy cho dễ tra.
     */
    @Test
    fun `hostname that duoc dung`() {
        val d = tmp()
        val mid = File(d, "machine-id").apply { writeText("abc\n") }
        assertEquals("nvr-khach-A-01",
            OtaDeviceId.resolve(File(d, "device-id"), "nvr-khach-A-01", mid))
    }

    /**
     * Hậu quả nếu hỏng: file device-id đã có bị ghi đè, máy đổi định danh, và
     * lịch sử check-in của nó đứt làm hai — không tra được quá khứ của máy đó.
     */
    @Test
    fun `file da co thi giu nguyen ke ca khi hostname doi`() {
        val d = tmp()
        val f = File(d, "device-id").apply { writeText("NVR-cu-roi\n") }
        val mid = File(d, "machine-id").apply { writeText("abc\n") }
        assertEquals("NVR-cu-roi", OtaDeviceId.resolve(f, "hostname-moi", mid))
    }

    /**
     * Hậu quả nếu hỏng: thiếu /etc/machine-id (container, máy lạ) làm gói OTA
     * sập thay vì rơi về một định danh nào đó — máy mất cả kênh quan sát.
     */
    @Test
    fun `thieu machine-id van sinh duoc dinh danh va ghi xuong dia`() {
        val d = tmp()
        val f = File(d, "device-id")
        val id = OtaDeviceId.resolve(f, "", File(d, "khong-co"))
        assertTrue(id.isNotBlank())
        assertTrue(f.isFile, "phải ghi xuống để lần sau ổn định")
        assertEquals(id, OtaDeviceId.resolve(f, "", File(d, "khong-co")))
    }

    /**
     * Hậu quả nếu hỏng: file rỗng (đĩa đầy lúc ghi, hoặc bị truncate) bị coi là
     * một định danh hợp lệ, và máy check-in với serial rỗng.
     */
    @Test
    fun `file rong khong duoc coi la dinh danh`() {
        val d = tmp()
        val f = File(d, "device-id").apply { writeText("   \n") }
        val mid = File(d, "machine-id").apply { writeText("abc\n") }
        val id = OtaDeviceId.resolve(f, "", mid)
        assertTrue(id.isNotBlank())
        assertTrue(id.startsWith("NVR-"), "id = $id")
    }
}
