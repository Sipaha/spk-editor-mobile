package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Snapshot of one in-flight chunked upload written to disk so a process
 * kill or network drop mid-upload can be resumed on next launch.
 *
 * Mirrors the per-upload state held inside `UploadManager` — the
 * coroutine that drives the chunk loop persists this on every ack so
 * the worst-case lost work after a force-kill is one chunk
 * ([UPLOAD_CHUNK_PAYLOAD_BYTES]). On resume, the manager calls
 * `upload_status` against the server (which is authoritative); the
 * persisted [lastConfirmedOffset] is only used as a hint for the UI's
 * percent label before the status round-trip returns.
 */
@Serializable
data class PersistedUpload(
    @SerialName("local_key") val localKey: String,
    @SerialName("upload_id") val uploadId: Long,
    @SerialName("uri") val uriString: String,
    @SerialName("session_id") val sessionId: String,
    val mime: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("total_size") val totalSize: Long,
    @SerialName("last_confirmed_offset") val lastConfirmedOffset: Long,
)

/**
 * Encrypted-on-disk per-server registry of in-flight chunked uploads.
 *
 * **Storage:** one [EncryptedSharedPreferences] file (`spk_inflight_uploads`)
 * with per-(server, localKey) keys
 * `inflight-upload:<serverId>:<localKey>`, each holding a JSON-serialised
 * [PersistedUpload]. Mirrors [EncryptedQueueStore] /
 * [SessionHistoryRepository]'s "single prefs file, prefixed keys"
 * pattern — the closest existing analog after a quick repo audit.
 *
 * **Why encrypted:** uploads carry user attachments (photos, files,
 * source). Same threat model as [EncryptedQueueStore] — adversary with
 * file-system access on an unlocked device shouldn't recover what the
 * user attached.
 *
 * **Failure path:** if [EncryptedSharedPreferences] is unavailable
 * (keystore migration, factory-reset credential store) every method
 * becomes a no-op and [list] returns an empty list. The caller falls
 * through to "no in-flight uploads to resume" — degraded but
 * non-crashing.
 *
 * **Per-server scoping:** every key is prefixed with the active server
 * id (read via [activeServerProvider]) so two paired servers' in-flight
 * uploads can't accidentally cross-replay onto the wrong wire.
 */
class InFlightUploadsRepository(
    private val context: Context,
) {

    @Volatile
    var activeServerProvider: () -> String? = { null }
        internal set

    private val prefs: SharedPreferences? by lazy { openPrefs() }

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
        Log.w(TAG, "EncryptedSharedPreferences unavailable; in-flight uploads not persisted", it)
    }.getOrNull()

    /**
     * Persist (or replace) a snapshot for [persisted.localKey] under the
     * currently-active server. Re-saving an existing localKey overwrites
     * the prior blob — the typical write pattern is "save once on init,
     * re-save on each chunk ack with the new offset".
     */
    fun saveOrUpdate(persisted: PersistedUpload) {
        val p = prefs ?: return
        val key = scopedKey(persisted.localKey) ?: return
        runCatching {
            val raw = JSON.encodeToString(PersistedUpload.serializer(), persisted)
            p.edit().putString(key, raw).apply()
        }.onFailure { Log.w(TAG, "saveOrUpdate() failed for ${persisted.localKey}", it) }
    }

    /**
     * List every persisted upload for the currently-active server. Used
     * by `UploadManager.resumeAllFromDisk` at startup to revive the
     * coroutines for uploads that were mid-stream when the process died.
     */
    fun list(): List<PersistedUpload> {
        val p = prefs ?: return emptyList()
        val serverId = activeServerProvider() ?: return emptyList()
        val prefix = "$KEY_PREFIX:$serverId:"
        return runCatching {
            p.all.entries
                .filter { it.key.startsWith(prefix) }
                .mapNotNull { (_, raw) ->
                    val s = raw as? String ?: return@mapNotNull null
                    runCatching {
                        JSON.decodeFromString(PersistedUpload.serializer(), s)
                    }.getOrNull()
                }
        }.onFailure { Log.w(TAG, "list() failed", it) }
            .getOrDefault(emptyList())
    }

    /** Drop the persisted entry for [localKey] under the currently-active server. */
    fun remove(localKey: String) {
        val p = prefs ?: return
        val key = scopedKey(localKey) ?: return
        runCatching { p.edit().remove(key).apply() }
            .onFailure { Log.w(TAG, "remove() failed for $localKey", it) }
    }

    /**
     * Wipe every persisted entry for [serverId]. Called from
     * `MainViewModel.removeServer` / `forgetAllServers` so pairing
     * deletion drops dangling upload state alongside drafts /
     * lastSeen / queue blobs.
     */
    fun removeForServer(serverId: String) {
        val p = prefs ?: return
        val prefix = "$KEY_PREFIX:$serverId:"
        runCatching {
            val editor = p.edit()
            for (key in p.all.keys) {
                if (key.startsWith(prefix)) editor.remove(key)
            }
            editor.apply()
        }.onFailure { Log.w(TAG, "removeForServer() failed", it) }
    }

    private fun scopedKey(localKey: String): String? {
        val serverId = activeServerProvider() ?: return null
        return "$KEY_PREFIX:$serverId:$localKey"
    }

    companion object {
        private const val TAG = "InFlightUploadsRepo"
        private const val PREFS_NAME = "spk_inflight_uploads"
        private const val KEY_PREFIX = "inflight-upload"

        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        @Volatile
        private var instance: InFlightUploadsRepository? = null

        fun get(
            context: Context,
            activeServerProvider: () -> String?,
        ): InFlightUploadsRepository = synchronized(this) {
            val store = instance
                ?: InFlightUploadsRepository(context.applicationContext).also { instance = it }
            store.activeServerProvider = activeServerProvider
            store
        }
    }
}
