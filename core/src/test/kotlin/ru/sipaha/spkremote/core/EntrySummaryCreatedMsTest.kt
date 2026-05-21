package ru.sipaha.spkremote.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntrySummaryCreatedMsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes created_ms when present`() {
        val e = json.decodeFromString(
            EntrySummary.serializer(),
            """{"role":"user","preview":"hi","index":0,"created_ms":1716200000000}""",
        )
        assertEquals(1716200000000L, e.createdMs)
    }

    @Test
    fun `created_ms absent decodes to null`() {
        val e = json.decodeFromString(
            EntrySummary.serializer(),
            """{"role":"user","preview":"hi","index":0}""",
        )
        assertNull(e.createdMs)
    }
}
