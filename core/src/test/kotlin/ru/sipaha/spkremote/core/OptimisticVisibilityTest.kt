package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OptimisticVisibilityTest {
    private fun opt(csid: Long?) =
        EntrySummary(role = EntryRoleDto.User, preview = "p", clientSendId = csid)

    @Test
    fun `optimistic with echoed csid is filtered out`() {
        val result = visibleOptimistic(listOf(opt(1L)), setOf(1L))
        assertEquals(emptyList(), result)
    }

    @Test
    fun `optimistic with non-echoed csid is kept`() {
        val e = opt(2L)
        assertEquals(listOf(e), visibleOptimistic(listOf(e), setOf(1L)))
    }

    @Test
    fun `optimistic with null csid is kept`() {
        val e = opt(null)
        assertEquals(listOf(e), visibleOptimistic(listOf(e), setOf(1L, 2L)))
    }

    @Test
    fun `empty optimistic yields empty`() {
        assertEquals(emptyList(), visibleOptimistic(emptyList(), setOf(1L)))
    }

    @Test
    fun `partial echo drops only echoed entries`() {
        val kept1 = opt(10L)
        val echoed = opt(20L)
        val kept2 = opt(null)
        val result = visibleOptimistic(listOf(kept1, echoed, kept2), setOf(20L))
        assertEquals(listOf(kept1, kept2), result)
    }
}
