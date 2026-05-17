package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists per-session compose-bar drafts across process death (R-6d).
 *
 * **Two channels, one prefs file:**
 *   - `draft:<sessionId>` — the live typing buffer. Saved on every keystroke
 *     (debounced 500 ms by the caller), cleared after a successful send.
 *   - `bounced:<sessionId>` — the read-once recovery slot for TTL-expired
 *     or terminally-failed messages. Written by
 *     [ru.sipaha.spkremote.app.vm.MainViewModel.handleExpiredMessage] when
 *     [ru.sipaha.spkremote.core.RemoteClient.onMessageExpired] fires. Read
 *     once via [bouncedFor] when the user next opens the session, which
 *     also clears the slot — so the bounce surfaces exactly once per
 *     expiry event.
 *
 * **Storage:** plain [SharedPreferences] (not encrypted). Drafts aren't
 * secrets in the threat model — the server has the same text the moment
 * Send is tapped, and the encryption overhead would just bloat startup
 * time. The encrypted-prefs flow is reserved for the pairing URL (which
 * embeds the HMAC secret) and the queued-messages blob (which carries
 * authoritative session ids).
 *
 * **Lifecycle:** singleton tied to `applicationContext`. Same rationale
 * as [PairingRepository] — opening a SharedPreferences file is cheap,
 * but the JVM-level instance also serves as a process-wide synchronization
 * point if multiple ViewModels ever share a draft (today, only [MainViewModel]).
 */
class DraftRepository(private val context: Context) {

    private val prefs: SharedPreferences? by lazy { openPrefs() }

    private fun openPrefs(): SharedPreferences? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.onFailure { Log.w(TAG, "openPrefs() failed; drafts won't persist", it) }
        .getOrNull()

    /** Save the in-progress draft for [sessionId]. Empty string clears it. */
    fun save(sessionId: String, text: String) {
        val p = prefs ?: return
        val key = draftKey(sessionId)
        runCatching {
            if (text.isEmpty()) {
                p.edit().remove(key).apply()
            } else {
                p.edit().putString(key, text).apply()
            }
        }.onFailure { Log.w(TAG, "save() failed", it) }
    }

    /** Return the typing-buffer draft for [sessionId], or `""` if absent. */
    fun load(sessionId: String): String {
        val p = prefs ?: return ""
        return runCatching { p.getString(draftKey(sessionId), null) ?: "" }
            .onFailure { Log.w(TAG, "load() failed", it) }
            .getOrDefault("")
    }

    /** Drop any saved draft for [sessionId]. Called after a successful send. */
    fun clear(sessionId: String) {
        val p = prefs ?: return
        runCatching { p.edit().remove(draftKey(sessionId)).apply() }
            .onFailure { Log.w(TAG, "clear() failed", it) }
    }

    /**
     * Read-and-clear the bounced-message slot for [sessionId]. Returns
     * `null` when no bounce is pending; the slot is wiped before this
     * function returns so a subsequent call sees a clean state.
     *
     * Caller (the session-detail screen) is responsible for surfacing the
     * snackbar / pre-filling the compose field — see SessionDetailScreen.
     */
    fun bouncedFor(sessionId: String): String? {
        val p = prefs ?: return null
        val key = bouncedKey(sessionId)
        return runCatching {
            val text = p.getString(key, null) ?: return@runCatching null
            // apply() vs commit(): we don't strictly need a barrier here —
            // a duplicate snackbar on a race is benign (worst case: same
            // text appears in the field twice in a row).
            p.edit().remove(key).apply()
            text
        }.onFailure { Log.w(TAG, "bouncedFor() failed", it) }
            .getOrNull()
    }

    /**
     * Stash [text] as the pending bounce for [sessionId]. Called by the
     * ViewModel when a queued send expires or fails terminally — the
     * next time the user opens the session, the OutlinedTextField shows
     * this text + a "couldn't send earlier" snackbar.
     *
     * If a bounce is already pending we **append** the new text on a
     * fresh paragraph (separated by `\n\n`) rather than overwriting —
     * losing the user's previous-typed-but-failed message because a
     * second send also failed would compound the original frustration.
     */
    fun setBounced(sessionId: String, text: String) {
        val p = prefs ?: return
        val key = bouncedKey(sessionId)
        runCatching {
            val existing = p.getString(key, null)
            val merged = if (existing.isNullOrBlank()) text else "$existing\n\n$text"
            p.edit().putString(key, merged).apply()
        }.onFailure { Log.w(TAG, "setBounced() failed", it) }
    }

    /** Forcibly drop a pending bounce — used by `forgetPairing`. */
    fun clearBounced(sessionId: String) {
        val p = prefs ?: return
        runCatching { p.edit().remove(bouncedKey(sessionId)).apply() }
            .onFailure { Log.w(TAG, "clearBounced() failed", it) }
    }

    /** Wipe every draft + bounce. Called from `forgetPairing` on Settings. */
    fun clearAll() {
        val p = prefs ?: return
        runCatching { p.edit().clear().apply() }
            .onFailure { Log.w(TAG, "clearAll() failed", it) }
    }

    private fun draftKey(sessionId: String) = "draft:$sessionId"
    private fun bouncedKey(sessionId: String) = "bounced:$sessionId"

    companion object {
        private const val TAG = "DraftRepository"
        private const val PREFS_NAME = "spk_drafts"

        @Volatile
        private var instance: DraftRepository? = null

        fun get(context: Context): DraftRepository =
            instance ?: synchronized(this) {
                instance ?: DraftRepository(context.applicationContext).also { instance = it }
            }
    }
}
