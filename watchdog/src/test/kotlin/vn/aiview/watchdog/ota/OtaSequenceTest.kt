package vn.aiview.watchdog.ota

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OtaSequenceTest {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Hậu quả nếu hỏng: trang /noi-bo/ota không có số nào để đếm "bao nhiêu máy
     * đã lên bản hiện hành", nên cả dòng NVR hiện ra mà cột đó trống — người
     * vận hành không phân biệt được "chưa cài" với "không báo cáo".
     */
    @Test
    fun `manifest doc duoc sequence va releaseId`() {
        val m = json.decodeFromString(
            OtaManifest.serializer(),
            """{"schema":1,"model":"AIVIEW-NVR-X1","version":"1.0.0.7",
                "sequence":14,"releaseId":"2026-09-18-a","minApplier":"1.0.0"}"""
        )
        assertEquals(14L, m.sequence)
        assertEquals("2026-09-18-a", m.releaseId)
    }

    /**
     * Hậu quả nếu hỏng: manifest CŨ (chưa có hai trường này) làm watchdog sập
     * khi giải mã — cả đội NVR mất đường cập nhật vì một trường mới.
     */
    @Test
    fun `manifest cu khong co hai truong van doc duoc`() {
        val m = json.decodeFromString(
            OtaManifest.serializer(),
            """{"schema":1,"model":"AIVIEW-NVR-X1","version":"1.0.0.6","minApplier":"1.0.0"}"""
        )
        assertEquals(0L, m.sequence)
        assertEquals("", m.releaseId)
    }

    /**
     * Hậu quả nếu hỏng: sàn chống hạ cấp bị thay nghĩa. floorVersion và
     * appliedSequence đo HAI chuyện khác nhau và cả hai đều cần — sàn chặn gói
     * cũ do chính ta ký, sequence cho biết máy đã lên bản nào.
     */
    @Test
    fun `sequence khong thay the floorVersion`() {
        val s = OtaState(floorVersion = "1.0.0.6", appliedSequence = 14, lastSeenSequence = 15)
        assertEquals("1.0.0.6", s.floorVersion)
        assertEquals(14L, s.appliedSequence)
        assertEquals(15L, s.lastSeenSequence)
    }

    /**
     * Hậu quả nếu hỏng: gộp "đã thấy" và "đã cài" thành một số làm mất bộ lọc
     * "đã thấy bản mới nhưng CHƯA cài" — đúng danh sách người vận hành cần khi
     * một đợt phát hành không lan hết.
     */
    @Test
    fun `da thay va da cai la hai so tach roi`() {
        val s = OtaState(lastSeenSequence = 15, appliedSequence = 14)
        assertTrue(s.lastSeenSequence > s.appliedSequence,
            "phải biểu diễn được trạng thái 'đã thấy nhưng chưa cài'")
    }

    /**
     * Hậu quả nếu hỏng: state.json cũ trên máy field (chưa có hai trường) làm
     * watchdog sập lúc khởi động — máy mất cả đường cập nhật lẫn đường quay lui.
     */
    @Test
    fun `state cu khong co hai truong van giai ma duoc`() {
        val s = json.decodeFromString(
            OtaState.serializer(),
            """{"installedVersion":"1.0.0.6","floorVersion":"1.0.0.6"}"""
        )
        assertEquals(0L, s.lastSeenSequence)
        assertEquals(0L, s.appliedSequence)
        assertEquals("1.0.0.6", s.installedVersion)
    }
}
