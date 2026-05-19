package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.sipaha.spkremote.app.data.DraftRepository
import ru.sipaha.spkremote.core.AppendedPlaceholderOutcome
import ru.sipaha.spkremote.core.EntrySummary
import ru.sipaha.spkremote.core.GetSessionResult
import ru.sipaha.spkremote.core.MessageAppendedPayload
import ru.sipaha.spkremote.core.QueueTtlException
import ru.sipaha.spkremote.core.QueuedMessage
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.applyAppendedPlaceholder
import ru.sipaha.spkremote.core.parseExpiredSendMessage
import ru.sipaha.spkremote.core.reconcileOptimisticContent
import java.util.concurrent.atomic.AtomicLong

/** Page size for [SessionDetailStore.openSession] / [SessionDetailStore.loadOlder]. */
private const val SESSION_PAGE_SIZE = 50

/**
 * Currently-open session detail — transcript, optimistic bubbles, draft
 * seeds, send / cancel / resume / pagination. The "detail seam" half of
 * the previous god-object `SessionStore`.
 *
 * ### Invariants
 *
 *  1. **`openSessionId` is `@Volatile` + every `_session` write is
 *     guarded by a stale-write barrier** — any coroutine that resolves a
 *     network result MUST re-check `openSessionId == sessionId` immediately
 *     before writing `_session.value`, otherwise a late delivery for a
 *     just-closed session can resurrect the previous transcript on top
 *     of the new session's `Loading`.
 *  2. **`detailSubscribed` is owned by [SessionListStore]** — the single
 *     notification collector lives there; this store consumes routed
 *     events via the [DetailNotificationRouter] hook implemented below.
 *  3. **Read-modify-write mutations on `_session.value` are serialised
 *     under [sessionMutex]** — multiple coroutines (notification observer,
 *     fetchAndReplaceEntry, loadOlder, resumeSession) compete for the
 *     same flow; without the mutex a stale snapshot can be re-published
 *     on top of a newer one.
 *  4. **Optimistic bubbles carry a stable client id** — see
 *     [optimisticIdGen]. The pure `reconcileOptimisticContent` matches
 *     by content (FIFO order of arrival) so sending duplicate text
 *     twice in a row reconciles when both echoes land.
 *  5. **The shared [lastSeen] index is the single source of truth** —
 *     don't store a sibling map; both this store and [SessionListStore]
 *     read/write through [LastSeenIndex].
 */
internal class SessionDetailStore(
    private val scope: CoroutineScope,
    private val context: ConnectionContext,
    private val draftRepository: DraftRepository,
    private val lastSeen: LastSeenIndex,
    private val sessionList: SessionListStore,
) : DetailNotificationRouter {

    private val _session = MutableStateFlow<UiData<GetSessionResult>>(UiData.Loading)
    val session: StateFlow<UiData<GetSessionResult>> = _session.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    private val _optimisticEntries = MutableStateFlow<List<EntrySummary>>(emptyList())
    val optimisticEntries: StateFlow<List<EntrySummary>> = _optimisticEntries.asStateFlow()

    /**
     * Stable per-optimistic-bubble id paired with each entry in
     * [_optimisticEntries] by list index. Source-of-truth for FIFO
     * reconciliation; we expose only [_optimisticEntries] to the UI.
     * Both lists are mutated together under [sessionMutex].
     */
    private val optimisticIds: MutableList<Long> = mutableListOf()
    private val optimisticIdGen = AtomicLong(0L)

    private val _cancelInFlight = MutableStateFlow(false)
    val cancelInFlight: StateFlow<Boolean> = _cancelInFlight.asStateFlow()

    @Volatile
    var openSessionId: String? = null
        private set

    /**
     * Serialises read-modify-write mutations on [_session]. See class
     * KDoc invariant 3.
     */
    private val sessionMutex = Mutex()

    init {
        // Wire the routing hook so the list-side notification collector
        // can forward detail-shaped events through this store.
        sessionList.detailNotificationRouter = this
    }

    /** Tear-down hook called from coordinator on server switch / disconnect. */
    fun reset() {
        _isLoadingOlder.value = false
        // Detail-state mutations MUST run under `sessionMutex` — otherwise
        // an in-flight `withLock` block that already cleared its stale-write
        // barrier check (e.g. fetchSession onSuccess) could publish AFTER
        // this clear and resurrect the previous server's session state.
        // See class KDoc invariant 3 + closeSession() (mirrors this pattern).
        scope.launch {
            sessionMutex.withLock {
                openSessionId = null
                lastSeen.clear()
                _session.value = UiData.Loading
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
            }
        }
    }

    /**
     * Lightweight pre-switch reset that drops just the open-session
     * markers — called BEFORE `tearDownConnection` so the connection
     * observer doesn't try to resume against the old `openSessionId`
     * mid-teardown.
     */
    fun beforeServerSwitch() {
        // Don't cancel the list-owned consolidated observer here —
        // [reset] (driven by onTearDown) does that. Only drop the
        // open-session marker so resumeSession in onReconnected sees null.
        openSessionId = null
    }

    /** Bounce-to-input recovery routed here from ConnectionManager. */
    fun handleExpiredMessage(message: QueuedMessage) {
        val parsed = parseExpiredSendMessage(message) ?: return
        val (sessionId, content) = parsed
        draftRepository.setBounced(sessionId, content)
    }

    fun openSession(sessionId: String) {
        val active = context.activeClient()
        if (active == null) {
            _session.value = UiData.Error(context.notConnectedMessage())
            return
        }
        // Sequence the openSessionId update + Loading reset together to
        // block a late delivery for the previous session from landing
        // between them (audit Fix M). The mutex covers both reads inside
        // the observer (when it checks `openSessionId == sessionId`) and
        // writes here.
        scope.launch {
            sessionMutex.withLock {
                openSessionId = sessionId
                _session.value = UiData.Loading
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
                lastSeen.primeFromDisk(sessionId)
            }
            fetchInitialPage(active, sessionId)
        }
        sessionList.loadChildren(sessionId)
        sessionList.ensureNotificationsObserver()
    }

    fun closeSession() {
        scope.launch {
            sessionMutex.withLock {
                openSessionId = null
                _session.value = UiData.Loading
                _isLoadingOlder.value = false
                _optimisticEntries.value = emptyList()
                optimisticIds.clear()
            }
        }
    }

    // -------------------------------------------------------------------------
    // DetailNotificationRouter — invoked by the list store's collector.
    // -------------------------------------------------------------------------

    override fun onChildSessionCreated(parentSessionId: String) {
        val openSid = openSessionId ?: return
        if (parentSessionId == openSid) {
            sessionList.loadChildren(parentSessionId)
        }
    }

    override fun onMessageAppended(payload: MessageAppendedPayload) {
        val openSid = openSessionId ?: return
        if (payload.sessionId != openSid) return
        lastSeen.recordIfNewer(payload.sessionId, payload.entryIndex)
        applyAppendedPlaceholderToFlow(payload)
        fetchAndReplaceEntry(openSid, payload.entryIndex)
    }

    override fun onMessageAppendedFallback() {
        val openSid = openSessionId ?: return
        refreshSession(openSid)
    }

    override fun onSessionStateOrTitleChanged(notifSessionId: String?) {
        val openSid = openSessionId ?: return
        if (notifSessionId != null && notifSessionId != openSid) return
        refreshSession(openSid)
    }

    // -------------------------------------------------------------------------
    // Network ops
    // -------------------------------------------------------------------------

    private fun applyAppendedPlaceholderToFlow(payload: MessageAppendedPayload) {
        scope.launch {
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != payload.sessionId) return@withLock
                val current = _session.value as? UiData.Loaded ?: return@withLock
                when (val outcome = applyAppendedPlaceholder(current.value.entries, payload)) {
                    is AppendedPlaceholderOutcome.Replaced -> {
                        _session.value = UiData.Loaded(current.value.copy(entries = outcome.entries))
                    }
                    AppendedPlaceholderOutcome.OutOfRange -> {
                        // Out-of-range index — fall back to a full refetch.
                        val active = context.activeClient() ?: return@withLock
                        scope.launch {
                            runCatching { fetchFullSession(active, payload.sessionId) }
                        }
                    }
                }
            }
        }
    }

    private fun fetchAndReplaceEntry(sessionId: String, index: Int) {
        val active = context.activeClient() ?: return
        scope.launch {
            val result = runCatching {
                active.getSessionEntry(sessionId, index, includeImages = true)
            }.getOrNull() ?: return@launch
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != sessionId) return@withLock
                val current = _session.value as? UiData.Loaded ?: return@withLock
                val entries = current.value.entries
                val newEntries = when {
                    index < entries.size ->
                        entries.toMutableList().also { it[index] = result.entry }
                    index == entries.size -> entries + result.entry
                    else -> {
                        scope.launch {
                            runCatching { fetchFullSession(active, sessionId) }
                        }
                        return@withLock
                    }
                }
                _session.value = UiData.Loaded(current.value.copy(entries = newEntries))
                reconcileOptimisticLocked(newEntries)
            }
        }
    }

    private suspend fun fetchFullSession(active: RemoteClient, sessionId: String) {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
        }
        val result = runCatching { active.call("remote.solution_agent.get_session", params) }
            .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            .getOrNull() ?: return
        sessionMutex.withLock {
            // stale-write barrier (see class kdoc invariant 1)
            if (openSessionId != sessionId) return@withLock
            _session.value = UiData.Loaded(result)
            reconcileOptimisticLocked(result.entries)
        }
    }

    private suspend fun fetchInitialPage(active: RemoteClient, sessionId: String) {
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("count", SESSION_PAGE_SIZE)
        }
        val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
            .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
        sessionMutex.withLock {
            // stale-write barrier (see class kdoc invariant 1)
            if (openSessionId != sessionId) return@withLock
            outcome
                .onSuccess { result ->
                    _session.value = UiData.Loaded(result)
                    _isLoadingOlder.value = false
                    reconcileOptimisticLocked(result.entries)
                    result.entries.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                        lastSeen.recordIfNewer(sessionId, newest.index)
                    }
                }
                .onFailure {
                    if (_session.value !is UiData.Loaded) {
                        _session.value = UiData.Error(it.message ?: "unknown error")
                    }
                }
        }
    }

    fun loadOlder(sessionId: String) {
        if (openSessionId != sessionId) return
        if (_isLoadingOlder.value) return
        val active = context.activeClient() ?: return
        val current = _session.value as? UiData.Loaded ?: return
        val oldest = current.value.entries.firstOrNull() ?: return
        val oldestIndex = oldest.index
        if (oldestIndex <= 0) return
        _isLoadingOlder.value = true
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("before_index", oldestIndex)
            put("count", SESSION_PAGE_SIZE)
        }
        scope.launch {
            val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            sessionMutex.withLock {
                if (openSessionId != sessionId) {
                    _isLoadingOlder.value = false
                    return@withLock
                }
                outcome
                    .onSuccess { result ->
                        val latest = _session.value as? UiData.Loaded
                        if (latest == null) {
                            _isLoadingOlder.value = false
                            return@onSuccess
                        }
                        val existingIndices = latest.value.entries.mapNotNull {
                            it.index.takeIf { i -> i >= 0 }
                        }.toHashSet()
                        val older = result.entries.filterNot {
                            it.index >= 0 && existingIndices.contains(it.index)
                        }
                        val merged = older + latest.value.entries
                        val newTotal = maxOf(latest.value.totalCount, result.totalCount)
                        _session.value = UiData.Loaded(
                            latest.value.copy(entries = merged, totalCount = newTotal),
                        )
                        _isLoadingOlder.value = false
                    }
                    .onFailure {
                        _isLoadingOlder.value = false
                        context.emitError("Couldn't load older messages: ${it.message ?: "?"}")
                    }
            }
        }
    }

    fun resumeSession(sessionId: String) {
        val active = context.activeClient() ?: return
        if (openSessionId != sessionId) return
        val current = _session.value
        val lastSeenIdx = lastSeen.getCached(sessionId) ?: lastSeen.readFromDisk(sessionId)
        if (lastSeenIdx == null || current !is UiData.Loaded) {
            scope.launch { fetchInitialPage(active, sessionId) }
            return
        }
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
            put("after_index", lastSeenIdx)
        }
        scope.launch {
            val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            outcome.onSuccess { result ->
                // Fast-path: dedup first, then decide whether the
                // post-dedup merged size still falls short of the server's
                // totalCount (audit Fix R). The previous order compared
                // pre-dedup, which could spuriously trigger a full
                // refetch when the resume returned overlapping entries.
                sessionMutex.withLock {
                    if (openSessionId != sessionId) return@withLock
                    val latest = _session.value as? UiData.Loaded ?: return@withLock
                    val existingIndices = latest.value.entries.mapNotNull {
                        it.index.takeIf { i -> i >= 0 }
                    }.toHashSet()
                    val fresh = result.entries.filterNot {
                        it.index >= 0 && existingIndices.contains(it.index)
                    }
                    val merged = if (fresh.isEmpty()) latest.value.entries else latest.value.entries + fresh
                    val mergedTotal = maxOf(latest.value.totalCount, result.totalCount)
                    if (result.totalCount >= 0 && merged.size < result.totalCount) {
                        // Truly fell short — kick a full refetch.
                        scope.launch { fetchInitialPage(active, sessionId) }
                        return@withLock
                    }
                    if (fresh.isEmpty()) {
                        if (result.totalCount >= 0 && result.totalCount != latest.value.totalCount) {
                            _session.value = UiData.Loaded(
                                latest.value.copy(totalCount = result.totalCount),
                            )
                        }
                        return@withLock
                    }
                    _session.value = UiData.Loaded(
                        latest.value.copy(entries = merged, totalCount = mergedTotal),
                    )
                    reconcileOptimisticLocked(merged)
                    fresh.lastOrNull()?.takeIf { it.index >= 0 }?.let { newest ->
                        lastSeen.recordIfNewer(sessionId, newest.index)
                    }
                }
            }
            // Failed resume is recoverable — silent.
        }
    }

    private fun refreshSession(sessionId: String) {
        val active = context.activeClient() ?: return
        val params = buildJsonObject {
            put("session_id", sessionId)
            put("include_full_content", true)
            put("include_images", true)
        }
        scope.launch {
            val outcome = runCatching { active.call("remote.solution_agent.get_session", params) }
                .mapCatching { resp -> resp.decodeResultOrThrow(GetSessionResult.serializer()) }
            sessionMutex.withLock {
                // stale-write barrier (see class kdoc invariant 1)
                if (openSessionId != sessionId) return@withLock
                outcome
                    .onSuccess { result ->
                        _session.value = UiData.Loaded(result)
                        reconcileOptimisticLocked(result.entries)
                    }
                    .onFailure {
                        if (_session.value !is UiData.Loaded) {
                            _session.value = UiData.Error(it.message ?: "unknown error")
                        }
                    }
            }
        }
    }

    /**
     * Pop optimistic bubbles whose corresponding server-side "user"
     * entry has now landed. Delegates to the pure
     * `reconcileOptimisticContent` (in `:core`); MUST be called while
     * holding [sessionMutex] — mutates both [_optimisticEntries] and
     * [optimisticIds] in lock-step.
     *
     * Audit Phase 3 caveat: this is content-match, NOT strict FIFO
     * stable-id matching. Sending the same text twice in a row where
     * the first send fails before the server echoes can briefly
     * appear to dedup the wrong bubble; the optimisticId rewrite
     * keeps the cancel path correct nonetheless.
     */
    private fun reconcileOptimisticLocked(serverEntries: List<EntrySummary>) {
        if (_optimisticEntries.value.isEmpty()) return
        val (keptEntries, keptIds) = reconcileOptimisticContent(
            optimistic = _optimisticEntries.value,
            optimisticIds = optimisticIds.toList(),
            serverUserEntries = serverEntries,
        )
        _optimisticEntries.value = keptEntries
        optimisticIds.clear()
        optimisticIds.addAll(keptIds)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val active = context.activeClient() ?: return
        val sessionId = openSessionId ?: return
        val optimistic = EntrySummary(role = "user", preview = text)
        val localId = optimisticIdGen.incrementAndGet()
        scope.launch {
            sessionMutex.withLock {
                _optimisticEntries.value = _optimisticEntries.value + optimistic
                optimisticIds.add(localId)
            }
            val params = buildJsonObject {
                put("session_id", sessionId)
                put("content", text)
            }
            runCatching { active.queueCall("remote.solution_agent.send_message", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    // Match the envelope-error precedent set by
                    // [decodeResultOrThrow]: tool-level `isError: true`
                    // must surface to the user the same way.
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onFailure {
                    sessionMutex.withLock {
                        // Match by stable id (audit Fix Q) — referential
                        // equality breaks when reconcileOptimistic has
                        // rebuilt the list. The two lists stay in sync
                        // because we mutate them together under the
                        // mutex.
                        val idx = optimisticIds.indexOf(localId)
                        if (idx >= 0) {
                            optimisticIds.removeAt(idx)
                            val list = _optimisticEntries.value.toMutableList()
                            if (idx < list.size) {
                                list.removeAt(idx)
                                _optimisticEntries.value = list
                            }
                        }
                    }
                    val msg = when (it) {
                        is QueueTtlException ->
                            "send timed out — the editor was offline for too long"
                        is RemoteClient.ClosedException ->
                            "send cancelled — connection closed"
                        else -> it.message ?: "send failed"
                    }
                    context.emitError(msg)
                }
        }
    }

    fun cancelTurn() {
        val active = context.activeClient() ?: return
        val sessionId = openSessionId ?: return
        if (_cancelInFlight.value) return
        _cancelInFlight.value = true
        val params = buildJsonObject { put("session_id", sessionId) }
        scope.launch {
            runCatching { active.call("remote.solution_agent.cancel_turn", params) }
                .mapCatching { resp ->
                    val err = resp.error
                    if (err != null) error(err.message)
                    val toolErr = resp.toolError()
                    if (toolErr != null) error(toolErr)
                }
                .onFailure { context.emitError("cancel failed: ${it.message ?: "?"}") }
            _cancelInFlight.value = false
        }
    }

    // ---- Draft seed methods (R-6c-multi) ----

    suspend fun loadDraftSeed(sessionId: String): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val bounced = draftRepository.bouncedFor(sessionId)
        if (bounced != null) bounced to true
        else draftRepository.load(sessionId) to false
    }

    suspend fun saveDraft(sessionId: String, text: String) = withContext(Dispatchers.IO) {
        draftRepository.save(sessionId, text)
    }

    fun clearDraft(sessionId: String) {
        draftRepository.clear(sessionId)
    }
}
