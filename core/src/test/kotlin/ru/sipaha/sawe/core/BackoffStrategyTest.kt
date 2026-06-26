package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BackoffStrategyTest {
    @Test
    fun `default schedule matches 1-2-4-8-16-30 cap`() {
        val s = BackoffStrategy.Default
        assertEquals(1_000L, s.nextDelayMs(1))
        assertEquals(2_000L, s.nextDelayMs(2))
        assertEquals(4_000L, s.nextDelayMs(3))
        assertEquals(8_000L, s.nextDelayMs(4))
        assertEquals(16_000L, s.nextDelayMs(5))
        assertEquals(30_000L, s.nextDelayMs(6))
        // Cap holds for arbitrarily large attempts.
        assertEquals(30_000L, s.nextDelayMs(7))
        assertEquals(30_000L, s.nextDelayMs(50))
        assertEquals(30_000L, s.nextDelayMs(1_000))
    }

    @Test
    fun `default handles edge cases without overflow`() {
        val s = BackoffStrategy.Default
        // Defensive: attempt=0 or negative should not crash and should
        // yield a sensible minimum delay (we treat them as "first retry").
        assertEquals(1_000L, s.nextDelayMs(0))
        assertEquals(1_000L, s.nextDelayMs(-5))
    }

    @Test
    fun `fixed strategy returns the same delay regardless of attempt`() {
        val s = BackoffStrategy.fixed(123L)
        assertEquals(123L, s.nextDelayMs(1))
        assertEquals(123L, s.nextDelayMs(10))
        assertEquals(123L, s.nextDelayMs(10_000))
    }
}
