package ru.sipaha.spkremote.core

/**
 * Transport-layer abstraction over a single WebSocket session.
 *
 * [RemoteClient] used to call OkHttp directly inside its handshake listener;
 * R-6a moves the WS lifecycle into a coroutine that owns reconnects, so we
 * need a seam where tests can replace OkHttp with a fake driven by hand.
 *
 * One [RemoteTransport] instance corresponds to one open WS (or one attempt
 * that failed before opening). The owner discards it on close and asks the
 * factory for a fresh one on the next attempt.
 *
 * The contract:
 *   - [send] is fire-and-forget. The transport is responsible for backing
 *     the bytes; on a closed/closing socket it returns `false` and the
 *     caller's listener will see [RemoteTransportListener.onFailure] /
 *     [RemoteTransportListener.onClosed] shortly after.
 *   - [close] requests a graceful close. The transport must still deliver
 *     [RemoteTransportListener.onClosed] (or `onFailure`) so the lifecycle
 *     coroutine can complete its current pass.
 */
internal interface RemoteTransport {
    fun send(text: String): Boolean
    fun send(bytes: ByteArray): Boolean
    fun close(code: Int = 1000, reason: String = "client closing")
}

/**
 * Per-attempt callback surface. Methods may be invoked on any thread; the
 * implementation must marshal back to its coroutine context as needed.
 */
internal interface RemoteTransportListener {
    fun onOpen() {}
    fun onBinary(bytes: ByteArray)
    fun onText(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(t: Throwable)
}

/**
 * Factory for fresh [RemoteTransport] instances. The default implementation
 * builds OkHttp WebSocket clients; tests inject [FakeRemoteTransportFactory]
 * (in `:core` test sources) to drive the lifecycle deterministically.
 */
internal interface RemoteTransportFactory {
    fun connect(
        url: PairingUrl,
        listener: RemoteTransportListener,
    ): RemoteTransport
}
