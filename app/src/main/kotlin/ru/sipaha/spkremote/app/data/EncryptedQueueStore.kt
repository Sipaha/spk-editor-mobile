package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import ru.sipaha.spkremote.core.QueueStore
import ru.sipaha.spkremote.core.QueuedMessage

/**
 * Disk-backed [QueueStore] using Android-Keystore-encrypted shared
 * preferences (R-6d).
 *
 * **Storage layout:** one entry — `queued_messages_v1` — holding a JSON
 * array of [QueuedMessage]. We rewrite the full blob on every mutation
 * (`add` / `remove` / `clear`) because the typical queue depth is
 * 0-3 entries and a real production app rarely sees more than ~10:
 * partial updates would add complexity for negligible I/O savings.
 *
 * **Why encrypted:** queued sends contain user content (potentially
 * sensitive — code snippets, work-in-progress text, debugging info)
 * and the session id which authorises follow-ups to the same chat. Same
 * threat model as the pairing URL itself — adversary with file-system
 * access shouldn't be able to read drafts off a stolen unlocked device.
 *
 * **Concurrency:** [SharedPreferences.Editor.commit] is called for every
 * mutation so a process kill *after* `add` returns is guaranteed to
 * preserve the entry. The slower commit (vs apply) is acceptable here —
 * the queue mutation rate is bounded by user tap rate (< 1/sec) and we
 * want guaranteed-on-disk semantics for the "tap Send → force-kill"
 * crash safety story.
 *
 * **Failure path:** if EncryptedSharedPreferences is unavailable
 * (keystore migration, factory reset of credential store), every
 * method falls back to a no-op except [loadAll] which returns the
 * cached in-memory list — equivalent to [ru.sipaha.spkremote.core.InMemoryQueueStore].
 * The app keeps working, just without restart durability.
 */
class EncryptedQueueStore(private val context: Context) : QueueStore {

    private val prefs: SharedPreferences? by lazy { openPrefs() }
    // Defensive in-memory cache so a transient prefs-open failure doesn't
    // strand the user. Mutations write through both — disk is canonical.
    private val cache: MutableList<QueuedMessage> = mutableListOf<QueuedMessage>().apply {
        addAll(readBlob())
    }

    private fun openPrefs(): SharedPreferences? = runCatching {
        val masterKey = AppMasterKey.get(context) ?: return@runCatching null
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        Log.w(TAG, "EncryptedSharedPreferences unavailable; queue persistence disabled", it)
    }.getOrNull()

    @Synchronized
    override fun loadAll(): List<QueuedMessage> =
        cache.sortedBy { it.enqueuedAtMs }

    @Synchronized
    override fun add(message: QueuedMessage) {
        // id is the key — re-adding the same id replaces in place.
        cache.removeAll { it.id == message.id }
        cache.add(message)
        writeBlob()
    }

    @Synchronized
    override fun remove(id: String) {
        val removed = cache.removeAll { it.id == id }
        if (removed) writeBlob()
    }

    @Synchronized
    override fun clear() {
        cache.clear()
        writeBlob()
    }

    private fun readBlob(): List<QueuedMessage> {
        val p = prefs ?: return emptyList()
        val raw = runCatching { p.getString(KEY_BLOB, null) }.getOrNull() ?: return emptyList()
        return runCatching {
            JSON.decodeFromString(ListSerializer(QueuedMessage.serializer()), raw)
        }.onFailure {
            // Corrupt blob (schema migration, partial write) — wipe and
            // start fresh. We deliberately do NOT propagate the error
            // because the user-visible recovery (one lost queued message
            // after a version upgrade) is better than crashing on cold
            // start.
            Log.w(TAG, "queue blob corrupt; resetting", it)
            runCatching { p.edit().remove(KEY_BLOB).commit() }
        }.getOrDefault(emptyList())
    }

    private fun writeBlob() {
        val p = prefs ?: return
        val payload = runCatching {
            JSON.encodeToString(ListSerializer(QueuedMessage.serializer()), cache.toList())
        }.getOrNull() ?: return
        // commit() (synchronous) — see kdoc on the class. The R8-shrunk
        // release build inlines this into the call site, so the cost is
        // the EncryptedSharedPreferences write only.
        runCatching { p.edit().putString(KEY_BLOB, payload).commit() }
            .onFailure { Log.w(TAG, "writeBlob() failed; queue mutation not persisted", it) }
    }

    companion object {
        private const val TAG = "EncryptedQueueStore"
        private const val PREFS_NAME = "spk_queue"
        private const val KEY_BLOB = "queued_messages_v1"

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        @Volatile
        private var instance: EncryptedQueueStore? = null

        fun get(context: Context): EncryptedQueueStore =
            instance ?: synchronized(this) {
                instance ?: EncryptedQueueStore(context.applicationContext).also { instance = it }
            }
    }
}
