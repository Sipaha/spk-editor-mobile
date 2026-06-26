package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundShellTest {

    // -------------------------------------------------------------------------
    // DTO deserialization
    // -------------------------------------------------------------------------

    @Test
    fun `BackgroundShellDto decodes full server payload`() {
        val text = """
            {
              "id": "bash-1",
              "command": "npm run dev",
              "state": "running",
              "mtime_ms": 1700000000000,
              "output_tail": "Listening on :3000\n"
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(BackgroundShellDto.serializer(), text)
        assertEquals("bash-1", parsed.id)
        assertEquals("npm run dev", parsed.command)
        assertEquals("running", parsed.state)
        assertEquals(1700000000000L, parsed.mtimeMs)
        assertEquals("Listening on :3000\n", parsed.outputTail)
    }

    @Test
    fun `BackgroundShellDto tolerates missing mtime_ms and output_tail`() {
        val text = """{"id":"bash-2","command":"cargo build","state":"exited:0"}"""
        val parsed = JsonRpc.json.decodeFromString(BackgroundShellDto.serializer(), text)
        assertEquals("bash-2", parsed.id)
        assertEquals("cargo build", parsed.command)
        assertEquals("exited:0", parsed.state)
        assertNull(parsed.mtimeMs)
        assertNull(parsed.outputTail)
    }

    @Test
    fun `BackgroundShellDto decodes an unknown state value (neutral pill)`() {
        // A future server might emit a state the client doesn't recognise.
        // It must still decode — the UI maps unknown -> a neutral pill.
        val text = """{"id":"bash-3","command":"sleep 5","state":"suspended"}"""
        val parsed = JsonRpc.json.decodeFromString(BackgroundShellDto.serializer(), text)
        assertEquals("bash-3", parsed.id)
        assertEquals("suspended", parsed.state)
    }

    @Test
    fun `GetSessionBackgroundShellsResult decodes full result`() {
        val text = """
            {
              "background_shells": [
                {"id":"bash-1","command":"npm run dev","state":"running","mtime_ms":100},
                {"id":"bash-2","command":"cargo test","state":"exited:1","output_tail":"FAILED\n"},
                {"id":"bash-3","command":"tail -f log","state":"killed"}
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(
            GetSessionBackgroundShellsResult.serializer(),
            text,
        )
        assertEquals(3, parsed.backgroundShells.size)
        assertEquals("running", parsed.backgroundShells[0].state)
        assertEquals("FAILED\n", parsed.backgroundShells[1].outputTail)
        assertEquals("killed", parsed.backgroundShells[2].state)
        assertNull(parsed.backgroundShells[2].outputTail)
    }

    @Test
    fun `GetSessionBackgroundShellsResult without background_shells decodes (default empty)`() {
        val text = "{}"
        val parsed = JsonRpc.json.decodeFromString(
            GetSessionBackgroundShellsResult.serializer(),
            text,
        )
        assertTrue(parsed.backgroundShells.isEmpty())
    }

    @Test
    fun `SessionBackgroundShellsChangedPayload decodes lite list (no output_tail)`() {
        val text = """
            {
              "session_id": "ses-1",
              "background_shells": [
                {"id":"bash-1","command":"npm run dev","state":"running"},
                {"id":"bash-2","command":"cargo test","state":"exited:0"}
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(
            SessionBackgroundShellsChangedPayload.serializer(),
            text,
        )
        assertEquals("ses-1", parsed.sessionId)
        assertEquals(2, parsed.backgroundShells.size)
        assertNull(parsed.backgroundShells[0].outputTail)
        assertEquals("exited:0", parsed.backgroundShells[1].state)
    }

    @Test
    fun `SessionBackgroundShellsChangedPayload decodes empty list (all shells gone)`() {
        val text = """{"session_id":"ses-1","background_shells":[]}"""
        val parsed = JsonRpc.json.decodeFromString(
            SessionBackgroundShellsChangedPayload.serializer(),
            text,
        )
        assertEquals("ses-1", parsed.sessionId)
        assertTrue(parsed.backgroundShells.isEmpty())
    }
}
