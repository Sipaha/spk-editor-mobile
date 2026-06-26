package ru.sipaha.sawe.core

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

/**
 * Owns the outbound queue half of [RemoteClient]'s state machine — the
 * in-memory FIFO of typed-but-unsent JSON-RPC calls, the disk-backed
 * [QueueStore] mirror, TTL bookkeeping, and the connected-edge flush.
 *
 * Extracted from [RemoteClient] in the M1 refactor: the host class is
 * now a thin facade over [QueueController] for the queue-shaped API
 * (`queueCall`, rehydrate-on-connect, close-time queue drain), while the
 * handshake + transport lifecycle stays in [RemoteClient].
 *
 * **Concurrency model:** every mutation to [queued] goes through
 * [stateLock]. [stateLock] is shared with [RemoteClient] so the
 * close-time edge (null transport, clear queue, mark closing) is one
 * atomic block from the caller's perspective.
 *
 * **Dispatch — M1 "accumulate-then-restore":**
 * Per-item flush is launched concurrently as `Deferred<DispatchResult>`
 * children of a single coordinator coroutine. On success the child
 * removes its own store entry; on transient failure it records its
 * original index. After all children settle (or the transport drops and
 * they cancel/fail in any order) the coordinator collects the
 * failed-at-index list, sorts ascending, and re-prepends in ONE
 * synchronized block. This preserves FIFO across concurrent
 * mid-dispatch failures — the open M1 gap that the previous per-item
 * `addFirst` could not guarantee.
 */
internal class QueueController(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val queueStore: QueueStore,
    private val onMessageExpired: ((QueuedMessage) -> Unit)?,
    private val stateLock: Any,
    /** Accessor for the current live transport (null while reconnecting). */
    private val transportAccessor: () -> RemoteTransport?,
    /**
     * RPC call hook — invokes the host's `call(method, params)` (no
     * default timeout). Re-uses the host's `pending` map and `transport`
     * so the queue can't see a different wire than direct callers.
     */
    private val callRpc: suspend (method: String, params: JsonElement?) -> JsonRpcResponse,
    /** Events channel — pushes [LifecycleEvent.QueueChanged] when items enqueue. */
    private val events: Channel<LifecycleEvent>,
    /** Read-only connection state — short-circuits `queueCall` while Connected. */
    private val connectionState: StateFlow<ConnectionState>,
) {
    /**
     * Outbound queue — in-memory wrappers pairing each persisted
     * [QueuedMessage] with its caller-side [CompletableDeferred]. The
     * authoritative ordering and survival across restarts comes from
     * [queueStore]; this deque is just a fast lookup for the in-flight
     * coroutines awaiting their response.
     */
    private val queued = ArrayDeque<QueuedCall>()

    /**
     * Read every previously-persisted [QueuedMessage] from [queueStore]
     * into [queued], wrapping each in a fresh [CompletableDeferred].
     *
     * **Bounce semantics for orphaned deferreds:** an entry restored
     * from disk has no caller awaiting its [CompletableDeferred] — the
     * coroutine that originally called `queueCall` died with the
     * previous process. We still complete the deferred on success /
     * failure so the bookkeeping is symmetric, but no one will observe
     * the result. The user-visible recovery path is [onMessageExpired]
     * — that's where the `:app` layer plumbs the bounced text back into
     * the draft repository for retry.
     */
    fun rehydrate() {
        val persisted = queueStore.loadAll()
        if (persisted.isEmpty()) return
        synchronized(stateLock) {
            for (msg in persisted.sortedBy { it.enqueuedAtMs }) {
                queued += QueuedCall(
                    message = msg,
                    deferred = CompletableDeferred(),
                    ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                )
            }
        }
    }

    /**
     * Snapshot the current queued items for close-time drain. Caller
     * MUST hold [stateLock] when calling (or accept a slightly stale
     * view). Clears the in-memory deque as a side effect.
     */
    fun drainOnClose(): List<QueuedCall> {
        // Caller is responsible for [stateLock] ordering — see
        // [RemoteClient.close]. We do NOT acquire it here because the
        // close path needs the same lock for transport=null, pending
        // clear, and queue drain to be one atomic block.
        val snapshot = queued.toList()
        queued.clear()
        return snapshot
    }

    /**
     * Body of [RemoteClient.queueCall]. Splits cleanly into fast path
     * (connected → call directly) and slow path (offline → persist +
     * enqueue + await TTL).
     */
    suspend fun queueCall(
        method: String,
        params: JsonElement?,
        ttlMs: Long,
    ): JsonRpcResponse {
        // Fast path: if we're connected, hand off to `call` directly. The
        // TTL only applies while the call is *queued* — once the wire
        // delivers it, the server's per-method timeout is what we trust.
        //
        // Race: between the connection-state read and `call()`, the
        // lifecycle coroutine can null out transport (mid-flush
        // transport drop) and `callInternal` throws [NotConnectedException].
        // Catch it and fall through to the queuing branch so we honour
        // the "held until next Connected" contract instead of dropping
        // the message silently.
        if (connectionState.value is ConnectionState.Connected) {
            try {
                return callRpc(method, params)
            } catch (_: NotConnectedException) {
                // Fall through to queue.
            }
        }
        val deferred = CompletableDeferred<JsonRpcResponse>()
        val message = QueuedMessage(
            id = UUID.randomUUID().toString(),
            method = method,
            params = params,
            enqueuedAtMs = nowMs(),
        )
        val item = QueuedCall(
            message = message,
            deferred = deferred,
            ttlMs = ttlMs,
        )
        val sendSynchronously = synchronized(stateLock) {
            // Re-check inside the lock so a flush in flight doesn't strand us.
            if (connectionState.value is ConnectionState.Connected) {
                true
            } else {
                // Persist BEFORE adding to the in-memory deque, both
                // under the same lock. If we add to the deque first and
                // release the lock before queueStore.add lands, a
                // QueueChanged dispatch can drain + remove the in-memory
                // entry before the disk write completes — leaving a
                // phantom on disk that gets replayed at next process
                // start. Ordering: persist → enqueue, atomically.
                runCatching { queueStore.add(message) }
                queued += item
                false
            }
        }
        if (sendSynchronously) {
            // Connected raced us; send directly. No need to persist —
            // the call is one round-trip away from a real response.
            try {
                return callRpc(method, params)
            } catch (_: NotConnectedException) {
                // Same race as the outer fast path — fall through to
                // the persistent queue branch.
                synchronized(stateLock) {
                    runCatching { queueStore.add(message) }
                    queued += item
                }
            }
        }
        events.trySend(LifecycleEvent.QueueChanged)
        return awaitWithTtl(item)
    }

    /**
     * Send every still-fresh queued item; TTL-expire the stale ones.
     * On transport loss mid-flush, items not yet dispatched are re-enqueued
     * at the head of the deque so FIFO order survives across reconnects.
     *
     * **Persistence:** expired entries are removed from [queueStore] and
     * [onMessageExpired] fires before the deferred fails so the `:app`
     * bounce-to-input path sees the payload exactly once.
     */
    fun flushQueue() {
        // Orchestrator: first walk the queue to drop anything past TTL,
        // then dispatch the survivors. Semantics are unchanged versus the
        // pre-refactor monolith — see [expireStaleEntries] and
        // [dispatchQueuedItems] for the two halves.
        val survivors = expireStaleEntries()
        if (survivors.isEmpty()) return
        dispatchQueuedItems(survivors)
    }

    /**
     * Walk [queued] under [stateLock], partitioning into still-fresh
     * survivors and TTL-expired entries. Expired entries are removed
     * from [queueStore] and bounced via [onMessageExpired] + their
     * deferred is completed with [QueueTtlException]. Returns the
     * survivors (in their original FIFO order) for [dispatchQueuedItems]
     * to send.
     */
    private fun expireStaleEntries(): List<QueuedCall> {
        val (survivors, expired) = synchronized(stateLock) {
            val now = nowMs()
            val sv = ArrayList<QueuedCall>(queued.size)
            val ex = ArrayList<QueuedCall>()
            for (item in queued) {
                if (now - item.message.enqueuedAtMs >= item.ttlMs) {
                    ex += item
                } else {
                    sv += item
                }
            }
            queued.clear()
            sv to ex
        }
        for (item in expired) {
            runCatching { queueStore.remove(item.message.id) }
            runCatching { onMessageExpired?.invoke(item.message) }
            item.deferred.completeExceptionally(QueueTtlException())
        }
        return survivors
    }

    /**
     * Concurrent FIFO drain with M1 accumulate-then-restore.
     *
     * Launches a single coordinator coroutine on [scope]. The coordinator
     * launches per-item dispatches as `Deferred<DispatchResult>` children
     * and awaits all of them via [awaitAll]. Each child:
     *
     *   - on success: removes its [queueStore] entry, completes its
     *     deferred with the response, returns [DispatchResult.Sent].
     *   - on [QueueTtlException]: removes its store entry, fires
     *     [onMessageExpired], completes the deferred with the exception,
     *     returns [DispatchResult.Expired] (no re-enqueue).
     *   - on any other failure (transient — NotConnectedException, refused
     *     frame, IO error): does NOT immediately re-enqueue. Returns
     *     [DispatchResult.Failed(originalIndex)] so the coordinator can
     *     restore FIFO order.
     *
     * After [awaitAll] resolves the coordinator sorts the failed indices
     * ascending and re-prepends those items in ONE synchronized block.
     * If new items arrived in [queued] while we were in flight, they keep
     * their relative position AFTER the restored prefix — same semantics
     * as the previous mid-flush re-enqueue.
     *
     * If transport drops mid-flush, the children's `callRpc` invocations
     * throw [NotConnectedException] (or similar transient) — the
     * coordinator collects those and re-prepends in original order, just
     * as if every child had failed sequentially. No deadlock vs the
     * lifecycle channel: the lifecycle coroutine never awaits the
     * coordinator; it's a fire-and-forget launch on [scope].
     */
    private fun dispatchQueuedItems(toSend: List<QueuedCall>) {
        if (toSend.isEmpty()) return
        // The coordinator owns the failed-index aggregation and the
        // single restore block. It runs on [scope]; the lifecycle
        // coroutine returns from flushQueue immediately and stays
        // available to consume LifecycleEvents (including a mid-flush
        // TransportClosed). This is the deadlock-free shape — Phase 3
        // M1 (sequential await on lifecycle) is forbidden, see the
        // backlog note in the host class.
        scope.launch {
            val children: List<Deferred<DispatchResult>> = toSend.mapIndexed { index, item ->
                async {
                    val active = transportAccessor()
                    if (active == null) {
                        // Wire is already gone before this child even
                        // started — fail-fast as transient. Restore at
                        // [index].
                        return@async DispatchResult.Failed(index)
                    }
                    val remainingTtl = item.ttlMs - (nowMs() - item.message.enqueuedAtMs)
                    if (remainingTtl <= 0) {
                        runCatching { queueStore.remove(item.message.id) }
                        runCatching { onMessageExpired?.invoke(item.message) }
                        item.deferred.completeExceptionally(QueueTtlException())
                        return@async DispatchResult.Expired
                    }
                    try {
                        val resp = callRpc(item.message.method, item.message.params)
                        runCatching { queueStore.remove(item.message.id) }
                        item.deferred.complete(resp)
                        DispatchResult.Sent
                    } catch (t: Throwable) {
                        if (t is QueueTtlException) {
                            runCatching { queueStore.remove(item.message.id) }
                            item.deferred.completeExceptionally(t)
                            DispatchResult.Expired
                        } else {
                            // Transient — do NOT re-enqueue here. The
                            // coordinator restores all failed items in
                            // one synchronized block, sorted by index,
                            // so concurrent reverse-order failures do
                            // not invert FIFO order.
                            DispatchResult.Failed(index)
                        }
                    }
                }
            }
            // awaitAll: every child has its own try/catch and returns
            // a DispatchResult, so this never throws even if individual
            // RPCs failed. The only escape is coordinator cancellation
            // (scope cancel = close()) — in that case let the
            // CancellationException propagate; the lock-protected
            // queue state is already consistent because each child
            // either ran to completion (returned a result) or was
            // cancelled before mutating anything.
            val results = children.awaitAll()
            val failed = results.mapNotNull { (it as? DispatchResult.Failed)?.originalIndex }
            if (failed.isEmpty()) return@launch
            // Sort ascending so the prefix we restore preserves FIFO
            // order: lower originalIndex was enqueued earlier and
            // must come out earlier on the next flush.
            val restored = failed.sorted().map { toSend[it] }
            synchronized(stateLock) {
                val combined = ArrayDeque<QueuedCall>(restored.size + queued.size)
                combined.addAll(restored)
                combined.addAll(queued)
                queued.clear()
                queued.addAll(combined)
            }
            // Wake the lifecycle coroutine — it'll re-flush the
            // restored items on the next Connected edge.
            events.trySend(LifecycleEvent.QueueChanged)
        }
    }

    /**
     * Await the per-item TTL on the caller side of [queueCall]. If TTL
     * fires while the item is still in [queued] (not yet dispatched),
     * remove it from disk and bounce via [onMessageExpired]. If the
     * item was already handed off to the wire, leave the in-flight
     * pending entry to be resolved by `dispatchJsonRpc` (or fail via
     * `failPendingOnDisconnect`) — its disk entry is cleaned up there.
     */
    suspend fun awaitWithTtl(item: QueuedCall): JsonRpcResponse {
        return try {
            withTimeout(item.ttlMs) { item.deferred.await() }
        } catch (t: TimeoutCancellationException) {
            val removed = synchronized(stateLock) { queued.remove(item) }
            if (removed) {
                runCatching { queueStore.remove(item.message.id) }
                // Fire the bounce callback directly — consistent with the
                // other two TTL-expiry paths (expireStaleEntries,
                // dispatchQueuedItems per-item) which also invoke it
                // synchronously. The callback contract is "may be called
                // from any coroutine; implementations must be thread-safe"
                // (see callback KDoc on [RemoteClient.onMessageExpired]).
                runCatching { onMessageExpired?.invoke(item.message) }
            }
            if (!item.deferred.isCompleted) {
                item.deferred.completeExceptionally(QueueTtlException())
            }
            throw QueueTtlException()
        }
    }

    /** Per-item outcome reported back to the dispatch coordinator. */
    private sealed interface DispatchResult {
        data object Sent : DispatchResult
        data object Expired : DispatchResult
        data class Failed(val originalIndex: Int) : DispatchResult
    }
}

/**
 * In-memory wrapper around a persisted [QueuedMessage] holding its
 * caller-side [CompletableDeferred] + the TTL chosen by the caller.
 * The [message] is the disk-canonical view; [deferred] is process-local
 * and re-created (orphaned) on rehydrate.
 */
internal data class QueuedCall(
    val message: QueuedMessage,
    val deferred: CompletableDeferred<JsonRpcResponse>,
    val ttlMs: Long,
)

/**
 * Lifecycle events the [RemoteClient]'s loop coroutine consumes.
 *
 * Promoted to a top-level `internal` sealed interface in the M1 refactor
 * so [QueueController] can emit [QueueChanged] from a different file.
 * Sealed-subtype rule: every variant must live in the same package +
 * module as the parent, which holds here.
 */
internal sealed interface LifecycleEvent {
    data object UserClose : LifecycleEvent
    data class TransportClosed(val failure: ConnectFailure) : LifecycleEvent
    data class TransportFailure(val failure: ConnectFailure, val terminal: Boolean) : LifecycleEvent
    data object QueueChanged : LifecycleEvent
}

/** Surface marker for TTL-expired queue items. */
class QueueTtlException : RuntimeException("queued call timed out")
