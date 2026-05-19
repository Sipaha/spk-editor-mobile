package ru.sipaha.spkremote.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Binary-frame upload protocol shared between mobile and the
 * `remote_control` listener on the desktop. See server-side decoder in
 * `crates/remote_control/src/listener.rs` (mobile contract section).
 *
 * Wire layout for one chunk frame (sent on the SAME WebSocket the
 * JSON-RPC text frames travel over — OkHttp / okio multiplexes by frame
 * type, the server reads `webSocket.send(ByteString)` as a BINARY frame
 * and routes header-prefixed payloads through the upload registry):
 *
 *   bytes [0..8)   u64 upload_id  (big-endian)
 *   bytes [8..16)  u64 offset     (big-endian)
 *   bytes [16..)   raw payload    (length = totalFrameSize - 16)
 *
 * Big-endian for both fields matches `u64::from_be_bytes` on the Rust
 * decoder. Server WS cap is 1 MiB so a 256 KiB payload + 16-byte header
 * fits comfortably; raise [UPLOAD_CHUNK_PAYLOAD_BYTES] only after
 * verifying the server-side cap on the deployed build.
 *
 * The final chunk of a complete upload may have `payload.size == 0` when
 * the byte stream ended exactly on a chunk boundary — the empty-payload
 * frame still carries the (uploadId, offset = totalSize) tuple so the
 * server can mark the slot ready for `upload_finish`. Mid-upload empty
 * chunks are invalid (the loop only sends what it actually read).
 */

const val UPLOAD_CHUNK_HEADER_BYTES: Int = 16

/** 256 KiB raw payload per chunk. Server WS cap is 1 MiB; header is 16 B. */
const val UPLOAD_CHUNK_PAYLOAD_BYTES: Int = 256 * 1024

/**
 * Encode `(uploadId, offset, payload)` into a single 16+N byte buffer
 * matching the server-side decoder in
 * `crates/remote_control/src/listener.rs`.
 *
 * Both u64 fields are written big-endian (most-significant byte first)
 * so the Rust side decodes them with `u64::from_be_bytes`. The function
 * is allocation-once + copy — no intermediate `ByteBuffer` or `Stream`
 * — because this runs in the hot per-chunk loop and we'd rather not
 * trigger a GC pulse mid-upload.
 */
fun buildUploadChunkFrame(uploadId: Long, offset: Long, payload: ByteArray): ByteArray {
    val out = ByteArray(UPLOAD_CHUNK_HEADER_BYTES + payload.size)
    // u64 big-endian — write the most-significant byte at index 0. We
    // mask each shifted byte explicitly (`and 0xFF`) before the .toByte()
    // cast: without the mask the JVM's sign-extension on the intermediate
    // Long would carry the sign bit into the high bits of the cast.
    for (i in 0..7) {
        out[i] = ((uploadId ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
    for (i in 0..7) {
        out[UPLOAD_CHUNK_HEADER_BYTES / 2 + i] =
            ((offset ushr ((7 - i) * 8)) and 0xFF).toByte()
    }
    payload.copyInto(out, destinationOffset = UPLOAD_CHUNK_HEADER_BYTES)
    return out
}

// ---- JSON-RPC params/result DTOs for the upload_* allow-listed tools ----

@Serializable
data class UploadInitParams(
    @SerialName("session_id") val sessionId: String,
    val mime: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("total_size") val totalSize: Long,
    val sha256: String? = null,
)

@Serializable
data class UploadInitResult(
    @SerialName("upload_id") val uploadId: Long,
)

@Serializable
data class UploadStatusParams(
    @SerialName("upload_id") val uploadId: Long,
)

@Serializable
data class UploadStatusResult(
    @SerialName("received_bytes") val receivedBytes: Long,
    @SerialName("total_size") val totalSize: Long,
)

@Serializable
data class UploadFinishParams(
    @SerialName("upload_id") val uploadId: Long,
    val sha256: String? = null,
)

@Serializable
data class UploadFinishResult(
    val handle: String,
)

@Serializable
data class UploadAbortParams(
    @SerialName("upload_id") val uploadId: Long,
)

/**
 * Decoded `data` payload of an `upload_chunk_acked` notification. The
 * server fires one of these after each successfully-written chunk so the
 * client can advance its offset and persist the new lastConfirmedOffset
 * to disk for crash-resume.
 *
 * The notification is delivered through the existing JSON-RPC
 * notification path (allow_list pattern `upload_*`). Forwarded by the
 * mobile's notification dispatcher to the per-upload ack channel — see
 * `UploadManager.onChunkAcked`.
 */
@Serializable
data class UploadChunkAckedPayload(
    @SerialName("upload_id") val uploadId: Long,
    @SerialName("received_bytes") val receivedBytes: Long,
)
