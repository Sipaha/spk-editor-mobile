package ru.sipaha.sawe.app.vm

import ru.sipaha.sawe.app.data.LastSeenRepository

/**
 * Tiny shared store of `sessionId -> last-seen-entry-index` shared
 * between [SessionListStore] and [SessionDetailStore] so notifications
 * and detail-page paginations agree on the same watermark.
 *
 * Wraps the on-disk [LastSeenRepository] with an in-memory cache; the
 * cache is keyed by sessionId and follows the original
 * `SessionStore.lastSeenEntryIndex` semantics:
 *
 *   - [getCached] does NOT consult disk — it returns null when the
 *     in-memory cache has never been seeded for the session.
 *   - [primeFromDisk] hydrates the cache from disk on first observation
 *     (called from `openSession` / `resumeSession`).
 *   - [recordIfNewer] writes both the cache and disk only when the
 *     incoming index is strictly greater than the cached one. This
 *     mirrors the original "max-monotone" behaviour.
 *   - [clear] wipes the in-memory cache; called from `reset()` on
 *     server-switch.
 *
 * Thread-safety: all mutations are sequenced through the same
 * dispatcher (`viewModelScope`), matching the original `SessionStore`
 * invariant that `lastSeenEntryIndex` is only touched from coroutines
 * launched on that scope.
 */
internal class LastSeenIndex(private val lastSeenRepository: LastSeenRepository) {

    private val cache = mutableMapOf<String, Int>()

    fun getCached(sessionId: String): Int? = cache[sessionId]

    /**
     * Hydrate the in-memory entry for [sessionId] from disk if it is
     * absent. No-op if already cached.
     */
    fun primeFromDisk(sessionId: String) {
        if (cache[sessionId] == null) {
            lastSeenRepository.get(sessionId)?.let { cache[sessionId] = it }
        }
    }

    /**
     * Read the on-disk value WITHOUT seeding the cache. Used by
     * [SessionDetailStore.resumeSession] which wants to know whether
     * we've ever persisted anything for this session, separately from
     * deciding whether to hydrate.
     */
    fun readFromDisk(sessionId: String): Int? = lastSeenRepository.get(sessionId)

    /**
     * Record [index] as the latest seen entry index for [sessionId] —
     * both the in-memory cache and the on-disk repository are updated,
     * but only when [index] is strictly greater than the cached value
     * (defaulting to `-1` when absent).
     */
    fun recordIfNewer(sessionId: String, index: Int) {
        val prev = cache[sessionId] ?: -1
        if (index > prev) {
            cache[sessionId] = index
            lastSeenRepository.set(sessionId, index)
        }
    }

    fun clear() {
        cache.clear()
    }
}
