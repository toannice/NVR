package vn.aiview.watchdog.ota

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaVersionTest {

    private fun v(s: String) = OtaVersion.parseOrNull(s) ?: error("'$s' phải parse được")

    /**
     * Ca sinh ra cả class này.
     *
     * So chuỗi thì "1.10.0.0" < "1.9.0.0" (vì ký tự '1' < '9' ở vị trí thứ 3), tức máy đang ở
     * 1.9 sẽ coi 1.10 là bản CŨ và từ chối nâng cấp, im lặng, mãi mãi. Đây đúng là họ hàng của
     * bug thật trong repo: Frigate 0.16.3 so chuỗi toạ độ zone nên tưởng "1.000" > "1.0".
     */
    @Test
    fun `1_10 moi hon 1_9 chu khong phai nguoc lai`() {
        assertTrue(v("1.10.0.0") > v("1.9.0.0"))
        assertTrue(v("1.9.0.0") < v("1.10.0.0"))
        // Chốt luôn rằng so chuỗi đúng là SAI, để nếu ai đổi sang String.compareTo thì test đỏ
        assertTrue("1.10.0.0" < "1.9.0.0", "tiền đề của test: so chuỗi cho kết quả ngược")
    }

    @Test
    fun `so tung doan tu trai sang phai`() {
        assertTrue(v("2.0.0.0") > v("1.99.99.99"))
        assertTrue(v("1.2.3.4") > v("1.2.3.3"))
        assertTrue(v("1.0.0.10") > v("1.0.0.9"))
    }

    @Test
    fun `doan thieu tinh la 0`() {
        assertEquals(v("1.2"), v("1.2.0.0"))
        assertEquals(v("1"), v("1.0.0.0"))
        assertTrue(v("1.2.0.1") > v("1.2"))
        // watchdog dùng 3 đoạn, sản phẩm dùng 4 — phải so được với nhau
        assertTrue(v("1.0.0") < v("1.0.1.0"))
    }

    @Test
    fun `equals va hashCode nhat quan voi doan thieu`() {
        val a = v("1.2")
        val b = v("1.2.0.0")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode(), "equals bằng nhau thì hashCode phải bằng nhau")
        // Dùng được làm key trong Set/Map
        assertEquals(1, setOf(a, b).size)
    }

    @Test
    fun `raw giu nguyen chuoi goc de log dung cai nguoi ta thay`() {
        assertEquals("1.2", v("1.2").raw)
        assertEquals("1.2", v("1.2").toString())
    }

    @Test
    fun `so 0 dau doan van parse duoc`() {
        assertEquals(v("1.2.3"), v("01.02.03"))
    }

    @Test
    fun `chuoi khong hop le tra ve null chu khong coi la 0`() {
        // Quan trọng: trả 0 âm thầm nghĩa là version rác sẽ được coi là "rất cũ" và một số
        // gate sẽ hành xử sai thay vì báo lỗi.
        listOf(null, "", "   ", "abc", "1.0.0-beta", "1.0.0+build", "v1.0.0",
               "1..0", "1.-2", "1.0x", "1,0", "1.0.0.0.0.0.0.0.0")
            .forEach { assertNull(OtaVersion.parseOrNull(it), "'$it' phải trả null") }
    }

    @Test
    fun `max cua danh sach dung cho viec nang san`() {
        // Chính là phép tính OtaStateStore.commit dùng: sàn mới = max(sàn cũ, version mới)
        val all = listOf(v("1.2.0.0"), v("1.10.0.0"), v("1.9.9.9"))
        assertEquals("1.10.0.0", all.max().raw)
    }
}
