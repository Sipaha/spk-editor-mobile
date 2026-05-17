package ru.sipaha.spkremote.core

import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * OkHttp-backed [RemoteTransportFactory].
 *
 * Each call to [connect] creates a fresh [WebSocket]. The underlying
 * [OkHttpClient] is shared across attempts so connection-pool / DNS / TLS
 * caches survive a reconnect. The fingerprint-pinning TrustManager is
 * keyed off [PairingUrl.fingerprint], so the pin survives a reconnect too;
 * that's the *desired* behaviour — if a future server reissues its leaf,
 * we want the pin to fail and report [ConnectionState.FailedTerminal].
 *
 * `pingInterval` is set to **30 s** per the R-6a spec — that's enough to
 * keep NAT translations alive on residential LTE / Wi-Fi while staying
 * well clear of common 60 s idle-timeout proxies.
 */
internal class OkHttpRemoteTransportFactory(
    private val builder: (PairingUrl) -> OkHttpClient.Builder = ::defaultBuilder,
) : RemoteTransportFactory {

    private val clients = HashMap<PairingUrl, OkHttpClient>()

    override fun connect(
        url: PairingUrl,
        listener: RemoteTransportListener,
    ): RemoteTransport {
        val client = clients.getOrPut(url) { builder(url).build() }
        val request = Request.Builder()
            .url("https://${url.host}:${url.port}/remote")
            .header("X-Spk-Remote-Client", url.client)
            .build()
        val socket = client.newWebSocket(request, Adapter(listener))
        return OkHttpRemoteTransport(socket)
    }

    private class Adapter(private val listener: RemoteTransportListener) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener.onOpen()
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            listener.onBinary(bytes.toByteArray())
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            listener.onText(text)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            listener.onFailure(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            listener.onClosed(code, reason)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Acknowledge the close to flush the queue, then OkHttp delivers
            // onClosed on the same thread.
            webSocket.close(code, reason)
        }
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun defaultBuilder(url: PairingUrl): OkHttpClient.Builder {
            val trustManager = FingerprintPinningTrustManager(url.fingerprint)
            val sslContext = SSLContext.getInstance("TLSv1.3").apply {
                init(null, arrayOf(trustManager), secureRandom)
            }
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true } // fingerprint pin obsoletes hostname check
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
        }
    }
}

private class OkHttpRemoteTransport(private val socket: WebSocket) : RemoteTransport {
    override fun send(text: String): Boolean = socket.send(text)
    override fun send(bytes: ByteArray): Boolean = socket.send(bytes.toByteString())
    override fun close(code: Int, reason: String) {
        socket.close(code, reason)
    }
}
