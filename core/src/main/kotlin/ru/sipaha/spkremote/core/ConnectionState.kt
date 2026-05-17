package ru.sipaha.spkremote.core

/**
 * Lifecycle states for one [RemoteClient]'s WebSocket connection.
 *
 * The state machine — driven entirely from inside [RemoteClient]'s lifecycle
 * coroutine — is:
 *
 * ```
 *  Disconnected ──(connect)──▶ Connecting
 *  Connecting   ──ok─────────▶ Connected
 *  Connecting   ──terminal──▶ FailedTerminal
 *  Connecting   ──transient─▶ Reconnecting(attempt=1, nextRetryMs)
 *  Connected    ──ws close──▶ Reconnecting(attempt=1, nextRetryMs)
 *  Reconnecting ──backoff──▶ Connecting (attempt++)
 *  Reconnecting ──terminal─▶ FailedTerminal
 *  *            ──close()──▶ Disconnected
 * ```
 *
 * `Reconnecting.attempt` is 1-indexed (`attempt=1` = "we're about to make the
 * first retry"). It resets to 1 every time a reconnect succeeds, so a single
 * 30-second drop a year into the session shows up as `attempt=1` rather than
 * `attempt=12_000`.
 *
 * `FailedTerminal` is non-retryable: certificate pin mismatch, HMAC reject,
 * or wrong protocol version. The UI surfaces this as "Re-pair required" —
 * the user has to scan the QR again.
 */
sealed interface ConnectionState {
    /** Initial state, or after a programmatic [RemoteClient.close]. */
    data object Disconnected : ConnectionState

    /** Handshake in flight (first attempt). */
    data object Connecting : ConnectionState

    /** WS open + HMAC succeeded. */
    data object Connected : ConnectionState

    /**
     * Backoff phase between two reconnect attempts.
     *
     * @param attempt 1-indexed; resets to 1 on every successful reconnect.
     * @param nextRetryMs delay until the next [Connecting] transition.
     */
    data class Reconnecting(val attempt: Int, val nextRetryMs: Long) : ConnectionState

    /**
     * Non-retryable failure. The caller must re-pair (fresh QR scan) to
     * recover. [reason] is shown in the UI banner.
     */
    data class FailedTerminal(val reason: String) : ConnectionState
}
