package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class UploadProtocolTest {

    private val json: Json = JsonRpc.json

    // ---- buildUploadChunkFrame: header byte order + payload layout ----

    @Test
    fun `header encodes uploadId big-endian in bytes 0 through 7`() {
        // uploadId = 0x0102030405060708; expected MSB-first ordering.
        val frame = buildUploadChunkFrame(
            uploadId = 0x0102030405060708L,
            offset = 0L,
            payload = ByteArray(0),
        )
        assertEquals(UPLOAD_CHUNK_HEADER_BYTES, frame.size)
        // Decode by hand to mirror what `u64::from_be_bytes` does in
        // Rust — most-significant byte at index 0.
        val expected = byteArrayOf(
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
        )
        assertContentEquals(expected, frame.copyOfRange(0, 8))
    }

    @Test
    fun `header encodes offset big-endian in bytes 8 through 15`() {
        val frame = buildUploadChunkFrame(
            uploadId = 0L,
            offset = 0x1112131415161718L,
            payload = ByteArray(0),
        )
        val expected = byteArrayOf(
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
        )
        assertContentEquals(expected, frame.copyOfRange(8, 16))
    }

    @Test
    fun `negative-looking uploadId masks correctly without sign extension`() {
        // 0xFFFFFFFFFFFFFFFE — without an explicit `and 0xFF` on each
        // shifted byte the JVM sign-extends the intermediate Long and
        // the cast to Byte clamps to 0xFF for every position.
        val frame = buildUploadChunkFrame(
            uploadId = -2L,
            offset = -1L,
            payload = ByteArray(0),
        )
        val expectedId = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte(),
        )
        val expectedOffset = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        assertContentEquals(expectedId, frame.copyOfRange(0, 8))
        assertContentEquals(expectedOffset, frame.copyOfRange(8, 16))
    }

    @Test
    fun `payload bytes are appended verbatim after the 16-byte header`() {
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val frame = buildUploadChunkFrame(uploadId = 7L, offset = 1024L, payload = payload)
        assertEquals(UPLOAD_CHUNK_HEADER_BYTES + payload.size, frame.size)
        assertContentEquals(payload, frame.copyOfRange(UPLOAD_CHUNK_HEADER_BYTES, frame.size))
    }

    @Test
    fun `empty payload is the legal finalisation frame at offset = totalSize`() {
        // Mid-upload empty frames are invalid (the loop sends what it
        // read), but a stream whose length is an exact multiple of
        // UPLOAD_CHUNK_PAYLOAD_BYTES needs a zero-length tail frame to
        // mark the slot ready for upload_finish.
        val totalSize = (UPLOAD_CHUNK_PAYLOAD_BYTES.toLong()) * 3L
        val frame = buildUploadChunkFrame(
            uploadId = 42L,
            offset = totalSize,
            payload = ByteArray(0),
        )
        assertEquals(UPLOAD_CHUNK_HEADER_BYTES, frame.size)
        // Sanity: the offset round-trips through manual decode.
        val decodedOffset = (0..7).fold(0L) { acc, i ->
            (acc shl 8) or (frame[8 + i].toLong() and 0xFF)
        }
        assertEquals(totalSize, decodedOffset)
    }

    @Test
    fun `header constant matches the documented 16-byte size`() {
        // Guard against a future "let's add a third field" attempt without
        // bumping the server-side decoder constant.
        assertEquals(16, UPLOAD_CHUNK_HEADER_BYTES)
    }

    @Test
    fun `payload chunk size is sized to fit comfortably under the 1 MiB server WS cap`() {
        assertEquals(256 * 1024, UPLOAD_CHUNK_PAYLOAD_BYTES)
        assertTrue(UPLOAD_CHUNK_PAYLOAD_BYTES + UPLOAD_CHUNK_HEADER_BYTES < 1024 * 1024)
    }

    // ---- DTO round-trips ----

    @Test
    fun `UploadInitParams serialises with snake_case wire fields`() {
        val params = UploadInitParams(
            sessionId = "s-1",
            mime = "image/png",
            displayName = "cat.png",
            totalSize = 4096L,
            sha256 = "abc",
        )
        val text = json.encodeToString(UploadInitParams.serializer(), params)
        // Snake-case keys come from the @SerialName overrides; the
        // server-side handler decodes the same shape.
        assertTrue(text.contains("\"session_id\":\"s-1\""), text)
        assertTrue(text.contains("\"display_name\":\"cat.png\""), text)
        assertTrue(text.contains("\"total_size\":4096"), text)
        assertTrue(text.contains("\"sha256\":\"abc\""), text)
        val back = json.decodeFromString(UploadInitParams.serializer(), text)
        assertEquals(params, back)
    }

    @Test
    fun `UploadInitParams elides sha256 when null - back-compat with older servers`() {
        val params = UploadInitParams(
            sessionId = "s",
            mime = "text/plain",
            displayName = "x.txt",
            totalSize = 1L,
            sha256 = null,
        )
        val text = json.encodeToString(UploadInitParams.serializer(), params)
        // JsonRpc.json has explicitNulls = false — the wire shape stays
        // compact and pre-sha256 server builds never see the field.
        assertTrue(!text.contains("sha256"), "sha256 leaked into wire: $text")
    }

    @Test
    fun `UploadInitResult decodes the snake_case upload_id key`() {
        val text = """{"upload_id":12345}"""
        val result = json.decodeFromString(UploadInitResult.serializer(), text)
        assertEquals(12345L, result.uploadId)
    }

    @Test
    fun `UploadStatusParams + Result round-trip`() {
        val params = UploadStatusParams(uploadId = 7L)
        val pText = json.encodeToString(UploadStatusParams.serializer(), params)
        assertEquals("""{"upload_id":7}""", pText)

        val rText = """{"received_bytes":1024,"total_size":4096}"""
        val r = json.decodeFromString(UploadStatusResult.serializer(), rText)
        assertEquals(1024L, r.receivedBytes)
        assertEquals(4096L, r.totalSize)
    }

    @Test
    fun `UploadFinishParams + Result round-trip with optional sha256`() {
        val params = UploadFinishParams(uploadId = 9L, sha256 = "deadbeef")
        val text = json.encodeToString(UploadFinishParams.serializer(), params)
        assertTrue(text.contains("\"upload_id\":9"), text)
        assertTrue(text.contains("\"sha256\":\"deadbeef\""), text)

        val noSha = UploadFinishParams(uploadId = 10L, sha256 = null)
        val text2 = json.encodeToString(UploadFinishParams.serializer(), noSha)
        assertTrue(!text2.contains("sha256"), text2)

        val resp = json.decodeFromString(
            UploadFinishResult.serializer(),
            """{"handle":"spk-upload://42"}""",
        )
        assertEquals("spk-upload://42", resp.handle)
    }

    @Test
    fun `UploadAbortParams round-trip`() {
        val p = UploadAbortParams(uploadId = 5L)
        val text = json.encodeToString(UploadAbortParams.serializer(), p)
        assertEquals("""{"upload_id":5}""", text)
        val back = json.decodeFromString(UploadAbortParams.serializer(), text)
        assertEquals(p, back)
    }

    @Test
    fun `UploadChunkAckedPayload decodes the notification data block`() {
        // Shape the desktop server emits inside
        // `params.data` on each `upload_chunk_acked` notification.
        val text = """{"upload_id":42,"received_bytes":131072}"""
        val payload = json.decodeFromString(UploadChunkAckedPayload.serializer(), text)
        assertEquals(42L, payload.uploadId)
        assertEquals(131072L, payload.receivedBytes)
    }

    @Test
    fun `UploadStatusResult ignores unknown forward-compat fields`() {
        // JsonRpc.json is configured with ignoreUnknownKeys = true; the
        // server may grow the result with e.g. `ttl_secs` later and old
        // clients must still decode cleanly.
        val text = """{"received_bytes":1,"total_size":2,"ttl_secs":3600}"""
        val r = json.decodeFromString(UploadStatusResult.serializer(), text)
        assertEquals(1L, r.receivedBytes)
        assertEquals(2L, r.totalSize)
    }

    @Test
    fun `UploadInitParams - sha256 absent on wire decodes to null`() {
        val text = """{"session_id":"s","mime":"image/png","display_name":"a.png","total_size":1}"""
        val p = json.decodeFromString(UploadInitParams.serializer(), text)
        assertNull(p.sha256)
    }

    // ---- binary send seam ----

    @Test
    fun `RemoteTransport send(bytes) is the binary-frame seam UploadManager will call`() {
        // The FakeRemoteTransport mirrors the OkHttp transport's separate
        // text vs. binary send paths — captures into [sentBinary] not [sent].
        // This protects UploadManager from a future refactor that
        // collapses the two into a single text-frame send.
        val url = PairingUrl(
            host = "127.0.0.1",
            port = 1,
            secret = ByteArray(32),
            client = "test",
            fingerprint = ByteArray(32),
        )
        val transport = FakeRemoteTransport(url, object : RemoteTransportListener {
            override fun onBinary(bytes: ByteArray) {}
            override fun onText(text: String) {}
            override fun onClosed(code: Int, reason: String) {}
            override fun onFailure(t: Throwable) {}
        })
        val frame = buildUploadChunkFrame(uploadId = 1L, offset = 0L, payload = byteArrayOf(0x42))
        val ok = transport.send(frame)
        assertTrue(ok)
        assertEquals(1, transport.sentBinary.size)
        assertTrue(transport.sent.isEmpty(), "binary send leaked into text path: ${transport.sent}")
        val captured = transport.sentBinary.first()
        assertContentEquals(frame, captured)
    }
}
