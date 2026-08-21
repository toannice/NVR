package vn.aiview.watchdog.ota

/**
 * So sánh phiên bản dạng số có dấu chấm: "1.0.0.3", "1.10.0.0", "1.0.0" (watchdog).
 *
 * Vì sao phải có class riêng thay vì so chuỗi: `"1.10.0.0" < "1.9.0.0"` theo thứ tự chuỗi
 * (vì '1' < '9' ở ký tự thứ 3) — tức là máy đang ở 1.9 sẽ coi 1.10 là bản CŨ và từ chối
 * nâng cấp, im lặng, mãi mãi. Đây đúng là họ hàng của bug đã có thật trong repo này: Frigate
 * 0.16.3 so chuỗi toạ độ zone nên tưởng "1.000" > "1.0" (xem sanitizeZoneCoordinates trong
 * CLAUDE.md mục 4). Cùng một cái bẫy, chỗ khác.
 *
 * Số đoạn không cố định: VERSION của sản phẩm có 4 đoạn ("1.0.0.3") còn watchdog có 3
 * ("1.0.0"). Đoạn thiếu tính là 0, nên "1.2" == "1.2.0.0".
 */
class OtaVersion private constructor(private val parts: List<Int>, val raw: String) : Comparable<OtaVersion> {

    override fun compareTo(other: OtaVersion): Int {
        val n = maxOf(parts.size, other.parts.size)
        for (i in 0 until n) {
            val a = parts.getOrElse(i) { 0 }
            val b = other.parts.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is OtaVersion && compareTo(other) == 0

    // Phải khớp với equals: "1.2" và "1.2.0.0" bằng nhau nên hash phải giống nhau.
    // Chuẩn hoá bằng cách bỏ hết số 0 ở cuối trước khi hash.
    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()

    override fun toString(): String = raw

    companion object {
        /** Trả về null nếu chuỗi không phải version hợp lệ — người gọi phải xử lý, không im lặng coi là 0. */
        fun parseOrNull(s: String?): OtaVersion? {
            val t = s?.trim().orEmpty()
            if (t.isEmpty()) return null
            val segs = t.split('.')
            if (segs.isEmpty() || segs.size > 8) return null
            val nums = segs.map { seg ->
                // Chỉ chấp nhận chữ số thuần. Loại "1.0.0-beta", "1.0.0 ", " 1", "01" thì vẫn nhận
                // (toInt bỏ số 0 đầu) nhưng "1.-2" hay "1.0x" thì không.
                if (seg.isEmpty() || !seg.all { it.isDigit() }) return null
                seg.toIntOrNull() ?: return null
            }
            return OtaVersion(nums, t)
        }
    }
}
