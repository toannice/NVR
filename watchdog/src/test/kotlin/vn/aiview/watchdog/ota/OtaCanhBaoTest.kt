package vn.aiview.watchdog.ota

import kotlinx.serialization.json.Json
import vn.aiview.watchdog.model.OtaConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtaCanhBaoTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Hậu quả nếu hỏng: gói .nvrup lên server ở một chỗ, thiết bị hỏi ở chỗ
     * khác, và cả đợt phát hành trả 404 — trong khi nhật ký publish báo thành
     * công. Hợp đồng đóng băng §A.1 đòi artifact nằm trong artifacts/.
     */
    @Test
    fun `canh bao khi package url khong tro vao artifacts`() {
        val xau = json.decodeFromString(
            OtaManifest.serializer(),
            """{"schema":1,"model":"AIVIEW-NVR-X1","version":"1","minApplier":"1.0.0",
                "package":{"url":"https://aiview.bkav.com/download/VMS/OTA/a.nvrup"}}"""
        )
        assertTrue(xau.canhBaoDuongGoi() != null, "phải cảnh báo")

        val tot = json.decodeFromString(
            OtaManifest.serializer(),
            """{"schema":1,"model":"AIVIEW-NVR-X1","version":"1","minApplier":"1.0.0",
                "package":{"url":"https://aiview.bkav.com/download/VMS/OTA/artifacts/a.nvrup"}}"""
        )
        assertEquals(null, tot.canhBaoDuongGoi())
    }

    /**
     * Hậu quả nếu hỏng: manifest không khai package (gói đi đường upload tay)
     * bị cảnh báo oan, và người vận hành học cách bỏ qua cảnh báo — đúng lúc
     * nó thật sự cần được đọc.
     */
    @Test
    fun `khong co package thi khong canh bao`() {
        val m = json.decodeFromString(
            OtaManifest.serializer(),
            """{"schema":1,"model":"AIVIEW-NVR-X1","version":"1","minApplier":"1.0.0"}"""
        )
        assertEquals(null, m.canhBaoDuongGoi())
    }

    /**
     * Hậu quả nếu hỏng: một máy cấu hình sai sẽ IM LẶNG không bao giờ thấy bản
     * cập nhật — và "không có bản mới" trông y hệt lúc chạy đúng. Lệch này là
     * chuyện THẬT trong kho: OtaConfig.DEFAULT_MANIFEST_URL trỏ VMS còn
     * .env.template khai DEVICE_MODEL=AIVIEW-NVR-X1.
     */
    @Test
    fun `phat hien lech base_url va manifest_url`() {
        val lech = OtaConfig(
            base_url = "https://aiview.bkav.com/download",
            manifest_url = "https://aiview.bkav.com/download/VMS/OTA/manifest.json"
        )
        val c = canhBaoLechUrl(lech, "AIVIEW-NVR-X1")
        assertTrue(c != null, "phải phát hiện lệch")
        // Thông báo phải nêu CẢ HAI URL — nêu một cái thì người đọc không biết
        // đang so với cái gì.
        assertTrue(c!!.contains("VMS") && c.contains("AIVIEW-NVR-X1"), c)
    }

    /**
     * Hậu quả nếu hỏng: cảnh báo nổ mỗi lần khởi động dù cấu hình đúng, và
     * người vận hành học cách bỏ qua nó.
     */
    @Test
    fun `khong lech thi im`() {
        val khop = OtaConfig(
            base_url = "https://aiview.bkav.com/download",
            manifest_url = "https://aiview.bkav.com/download/AIVIEW-NVR-X1/OTA/manifest.json"
        )
        assertEquals(null, canhBaoLechUrl(khop, "AIVIEW-NVR-X1"))
    }

    /**
     * Hậu quả nếu hỏng: máy thiếu DEVICE_MODEL nhận thêm một cảnh báo vô nghĩa
     * bên cạnh OTA_NO_MODEL đã có — nhiễu che mất cảnh báo thật.
     */
    @Test
    fun `thieu model thi khong canh bao lech`() {
        assertEquals(null, canhBaoLechUrl(OtaConfig(), ""))
    }
}
