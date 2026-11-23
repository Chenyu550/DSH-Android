package io.github.jackwener.dsh.core.designsystem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #63 / #65: table columns sized by content; nothing is cut, only a table that cannot fit scrolls. */
class TableColumnsTest {
    private fun widths(min: IntArray, max: IntArray, avail: Int) = TableColumns.widths(min, max, avail, floor = 96, cap = 320, readable = 200)

    @Test fun `a table that fits is stretched to the width in proportion`() {
        val w = widths(intArrayOf(30, 40), intArrayOf(60, 90), avail = 300)
        assertEquals(300, w.sum())
        assertArrayEquals(intArrayOf(120, 180), w)
    }

    @Test fun `a short label keeps its width and the long column wraps into the rest`() {
        // 项目 | 每天 09:30 检查 GitHub … (a long sentence, CJK: every character can break)
        val w = widths(intArrayOf(40, 14), intArrayOf(60, 1400), avail = 330)
        assertArrayEquals(intArrayOf(60, 270), w)
    }

    @Test fun `columns that must wrap share the room by what they lack`() {
        val w = widths(intArrayOf(14, 14, 14), intArrayOf(900, 900, 120), avail = 330)
        assertEquals(330, w.sum())
        assertEquals(w[0], w[1])
        // The short column never drops below its floor.
        assertTrue(w.toList().toString(), w[2] >= 96)
    }

    @Test fun `too many columns scroll with readable widths`() {
        val w = widths(IntArray(6) { 14 }, IntArray(6) { 800 }, avail = 330)
        assertArrayEquals(IntArray(6) { 200 }, w)
        // A long unbreakable word (a URL) is capped and breaks inside the cell.
        assertEquals(320, widths(intArrayOf(2000), intArrayOf(2000), avail = 300)[0])
    }

    @Test fun `unknown width uses what the content wants`() {
        assertArrayEquals(intArrayOf(60, 320), widths(intArrayOf(40, 14), intArrayOf(60, 1400), avail = 0))
    }
}
