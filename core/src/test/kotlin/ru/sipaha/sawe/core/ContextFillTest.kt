package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * The chat-header context meter must render for sleeping / restored sessions.
 * The wire sends `max_tokens = null` whenever the agent never advertised a
 * window (the common case for cold sessions — the desktop never restores
 * `cached_max_tokens` from disk). Clients apply their own default rather
 * than blanking the meter, mirroring the desktop `resolve_max_tokens`.
 */
class ContextFillTest {

    @Test
    fun `sleeping session with cached total but null max falls back to default window`() {
        val state = ContextFill.compute(totalTokens = 120_000L, maxTokens = null)
        assertNotNull(state)
        assertEquals(ContextFill.DEFAULT_CONTEXT_WINDOW_TOKENS, state.max)
        assertEquals(120_000L, state.used)
        assertEquals(12, state.percent) // 120k / 1.0M
    }

    @Test
    fun `real advertised max is used when present`() {
        val state = ContextFill.compute(totalTokens = 100_000L, maxTokens = 200_000L)
        assertNotNull(state)
        assertEquals(200_000L, state.max)
        assertEquals(50, state.percent)
    }

    @Test
    fun `zero max sentinel is treated as unknown and falls back to default window`() {
        val state = ContextFill.compute(totalTokens = 50_000L, maxTokens = 0L)
        assertNotNull(state)
        assertEquals(ContextFill.DEFAULT_CONTEXT_WINDOW_TOKENS, state.max)
        assertEquals(5, state.percent)
    }

    @Test
    fun `no usage and no max hides the meter`() {
        assertNull(ContextFill.compute(totalTokens = null, maxTokens = null))
        assertNull(ContextFill.compute(totalTokens = 0L, maxTokens = 0L))
        assertNull(ContextFill.compute(totalTokens = null, maxTokens = 0L))
    }

    @Test
    fun `fraction clamps to one when usage exceeds the window`() {
        val state = ContextFill.compute(totalTokens = 2_000_000L, maxTokens = 1_000_000L)
        assertNotNull(state)
        assertEquals(1.0f, state.fraction)
        assertEquals(100, state.percent)
    }
}
