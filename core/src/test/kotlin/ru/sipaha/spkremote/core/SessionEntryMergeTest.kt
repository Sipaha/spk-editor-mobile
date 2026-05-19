package ru.sipaha.spkremote.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JVM-only tests for the pure merge / dedup helpers extracted from
 * `SessionDetailStore`. Covers the three index-boundary branches of
 * [applyAppendedPlaceholder] and the FIFO / partial-match / duplicate-
 * content behaviour of [reconcileOptimisticContent], plus the wire-
 * parser [parseExpiredSendMessage].
 */
class SessionEntryMergeTest {

    private fun entry(role: String, preview: String, index: Int = -1): EntrySummary =
        EntrySummary(role = role, preview = preview, index = index)

    private fun payload(
        sessionId: String = "s1",
        entryIndex: Int,
        role: String = "assistant",
        preview: String = "preview",
    ): MessageAppendedPayload =
        MessageAppendedPayload(
            sessionId = sessionId,
            entryIndex = entryIndex,
            role = role,
            preview = preview,
        )

    // -------------------------------------------------------------------------
    // applyAppendedPlaceholder
    // -------------------------------------------------------------------------

    @Test
    fun `append onto empty list at index 0 lands as a single-entry Replaced`() {
        val out = applyAppendedPlaceholder(
            current = emptyList(),
            payload = payload(entryIndex = 0, role = "user", preview = "hi"),
        )
        assertTrue(out is AppendedPlaceholderOutcome.Replaced)
        val entries = (out as AppendedPlaceholderOutcome.Replaced).entries
        assertEquals(1, entries.size)
        assertEquals("user", entries[0].role)
        assertEquals("hi", entries[0].preview)
    }

    @Test
    fun `append at end - entryIndex equals size - lands at the end`() {
        val current = listOf(entry("user", "a"), entry("assistant", "b"))
        val out = applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 2, role = "assistant", preview = "c"),
        )
        assertTrue(out is AppendedPlaceholderOutcome.Replaced)
        val entries = (out as AppendedPlaceholderOutcome.Replaced).entries
        assertEquals(3, entries.size)
        assertEquals("c", entries[2].preview)
    }

    @Test
    fun `in-place replace - entryIndex less than size - overwrites the slot without growing`() {
        val current = listOf(entry("user", "a"), entry("assistant", "stale"))
        val out = applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 1, role = "assistant", preview = "fresh"),
        )
        assertTrue(out is AppendedPlaceholderOutcome.Replaced)
        val entries = (out as AppendedPlaceholderOutcome.Replaced).entries
        assertEquals(2, entries.size)
        assertEquals("fresh", entries[1].preview)
        // First slot untouched.
        assertEquals("a", entries[0].preview)
    }

    @Test
    fun `out-of-range entryIndex returns OutOfRange so caller refetches`() {
        val current = listOf(entry("user", "a"))
        val out = applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 5, role = "assistant", preview = "future"),
        )
        assertEquals(AppendedPlaceholderOutcome.OutOfRange, out)
    }

    @Test
    fun `idempotent — replaying the same payload twice yields identical results`() {
        val current = listOf(entry("user", "a"), entry("assistant", "old"))
        val p = payload(entryIndex = 1, role = "assistant", preview = "new")
        val first = applyAppendedPlaceholder(current, p) as AppendedPlaceholderOutcome.Replaced
        val second = applyAppendedPlaceholder(first.entries, p) as AppendedPlaceholderOutcome.Replaced
        assertEquals(first.entries, second.entries)
    }

    @Test
    fun `does not mutate the input list`() {
        val current = mutableListOf(entry("user", "a"))
        val before = current.toList()
        applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 0, role = "user", preview = "MUTATED"),
        )
        assertEquals(before, current, "input list must not be mutated")
    }

    // -------------------------------------------------------------------------
    // reconcileOptimisticContent
    // -------------------------------------------------------------------------

    @Test
    fun `empty optimistic returns empty pair`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = emptyList(),
            optimisticIds = emptyList(),
            serverUserEntries = listOf(entry("user", "hello")),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `single match drops the optimistic bubble and its id`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(entry("user", "hello")),
            optimisticIds = listOf(1L),
            serverUserEntries = listOf(entry("user", "hello", index = 0)),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `non-user server roles are ignored`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(entry("user", "hello")),
            optimisticIds = listOf(1L),
            // Server's only entry is assistant — must NOT match.
            serverUserEntries = listOf(entry("assistant", "hello")),
        )
        assertEquals(1, entries.size)
        assertEquals("hello", entries[0].preview)
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `FIFO order preserved on partial match — only the matching bubble drops`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(
                entry("user", "first"),
                entry("user", "second"),
                entry("user", "third"),
            ),
            optimisticIds = listOf(1L, 2L, 3L),
            // Only "second" has been echoed by the server.
            serverUserEntries = listOf(entry("user", "second")),
        )
        assertEquals(2, entries.size)
        assertEquals("first", entries[0].preview)
        assertEquals("third", entries[1].preview)
        assertEquals(listOf(1L, 3L), ids)
    }

    @Test
    fun `duplicate content — both optimistic bubbles reconcile when both server echoes arrive`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(
                entry("user", "ping"),
                entry("user", "ping"),
            ),
            optimisticIds = listOf(10L, 20L),
            serverUserEntries = listOf(
                entry("user", "ping", index = 0),
                entry("user", "ping", index = 1),
            ),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `duplicate content with only one server echo drops only one optimistic`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(
                entry("user", "ping"),
                entry("user", "ping"),
            ),
            optimisticIds = listOf(10L, 20L),
            serverUserEntries = listOf(entry("user", "ping", index = 0)),
        )
        assertEquals(1, entries.size)
        assertEquals("ping", entries[0].preview)
        // FIFO: the *second* bubble (id 20) survives because the first was matched.
        assertEquals(listOf(20L), ids)
    }

    @Test
    fun `stripRoleHeading is applied when matching previews`() {
        // Optimistic carries plain text; server echoes preview with the
        // role heading prefix — the matcher must normalise both sides.
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(entry("user", "hello world")),
            optimisticIds = listOf(7L),
            serverUserEntries = listOf(entry("user", "## User\nhello world", index = 0)),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
    }

    // -------------------------------------------------------------------------
    // parseExpiredSendMessage
    // -------------------------------------------------------------------------

    @Test
    fun `parseExpiredSendMessage skips non-send_message method`() {
        val msg = QueuedMessage(
            id = "1",
            method = "remote.solution_agent.cancel_turn",
            params = buildJsonObject {
                put("session_id", "s1")
                put("content", "hello")
            },
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage skips missing session_id`() {
        val msg = QueuedMessage(
            id = "2",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject { put("content", "hello") },
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage skips blank content`() {
        val msg = QueuedMessage(
            id = "3",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "s1")
                put("content", "   ")
            },
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage skips null params`() {
        val msg = QueuedMessage(
            id = "4",
            method = "remote.solution_agent.send_message",
            params = null,
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage skips non-object params`() {
        val msg = QueuedMessage(
            id = "4b",
            method = "remote.solution_agent.send_message",
            params = kotlinx.serialization.json.JsonPrimitive("not-an-object"),
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage happy path returns sessionId and content`() {
        val msg = QueuedMessage(
            id = "5",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "session-abc")
                put("content", "Hello world")
            },
            enqueuedAtMs = 0L,
        )
        val out = parseExpiredSendMessage(msg)
        assertEquals("session-abc" to "Hello world", out)
    }

    @Test
    fun `parseExpiredSendMessage forwards multi-line content verbatim`() {
        val text = "Line 1\nLine 2"
        val msg = QueuedMessage(
            id = "6",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "s2")
                put("content", text)
            },
            enqueuedAtMs = 0L,
        )
        val out = parseExpiredSendMessage(msg)
        assertEquals("s2" to text, out)
    }

    // Reference unused JsonObject import so it isn't pruned by IDE.
    @Suppress("unused")
    private val _ref: JsonObject = buildJsonObject {}
}
