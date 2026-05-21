package ru.sipaha.spkremote.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatTimelineTest {
    private val utc = ZoneId.of("UTC")
    private fun entry(idx: Int, ms: Long?) =
        EntrySummary(role = "user", preview = "p$idx", index = idx, createdMs = ms)

    // 2024-05-20 10:00 and 12:00 UTC (same day); 2024-05-21 09:00 UTC (next day)
    private val day1a = 1716199200000L
    private val day1b = 1716206400000L
    private val day2 = 1716282000000L

    @Test
    fun `inserts a leading separator before the first dated entry`() {
        val items = withDateSeparators(listOf(entry(0, day1a)), utc)
        assertEquals(2, items.size)
        assertTrue(items[0] is ChatItem.DateSeparator)
        assertTrue(items[1] is ChatItem.Message)
    }

    @Test
    fun `no separator between two entries on the same local day`() {
        val items = withDateSeparators(listOf(entry(0, day1a), entry(1, day1b)), utc)
        assertEquals(3, items.size) // header + msg + msg
    }

    @Test
    fun `separator inserted at a day boundary`() {
        val items = withDateSeparators(listOf(entry(0, day1b), entry(1, day2)), utc)
        assertEquals(4, items.size) // header + msg + separator + msg
        assertTrue(items[2] is ChatItem.DateSeparator)
    }

    @Test
    fun `entries without timestamps produce no separators`() {
        val items = withDateSeparators(listOf(entry(0, null), entry(1, null)), utc)
        assertEquals(2, items.size)
        assertTrue(items.all { it is ChatItem.Message })
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(0, withDateSeparators(emptyList(), utc).size)
    }

    @Test
    fun `zero createdMs treated as no timestamp — no separator`() {
        val items = withDateSeparators(listOf(entry(0, 0L)), utc)
        assertEquals(1, items.size)
        assertTrue(items[0] is ChatItem.Message)
    }

    @Test
    fun `negative createdMs treated as no timestamp — no separator`() {
        val items = withDateSeparators(listOf(entry(0, -1L)), utc)
        assertEquals(1, items.size)
        assertTrue(items[0] is ChatItem.Message)
    }
}
