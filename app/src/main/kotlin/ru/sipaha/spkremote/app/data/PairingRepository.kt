package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences

/**
 * Persists the most-recently-successful pairing URL so the app skips the
 * QR-scan flow on subsequent launches.
 *
 * Stored in an Android-Keystore-backed [EncryptedSharedPreferences] file
 * (`spk_pairing`) — the pairing URL embeds the shared HMAC secret, so we
 * absolutely don't want it sitting in plain text on disk where another
 * app with READ_EXTERNAL_STORAGE-equivalent access could harvest it. The
 * master key is generated once via [MasterKey.KeyScheme.AES256_GCM] and
 * lives in the per-app keystore tied to the install.
 *
 * **Lifecycle:**
 *  - [save] is called by [ru.sipaha.spkremote.app.vm.MainViewModel.connect]
 *    *after* a successful TLS-pinned, HMAC-validated handshake — never on
 *    the input side of the scan. This keeps poisoned QRs from leaving
 *    state behind.
 *  - [load] runs synchronously on `MainActivity.onCreate`. The wrapped
 *    EncryptedSharedPreferences API performs disk I/O on first access, so
 *    we accept the cold-start hit (~30 ms on a modern device) rather than
 *    block the first frame on an async coroutine — the alternative is a
 *    one-frame flicker into the QR screen before redirecting.
 *  - [clear] is called from the Settings screen (Forget / Re-pair).
 *
 * The repository swallows EncryptedSharedPreferences I/O failures (key
 * rotated, file corrupted) into `null` from [load] — the user lands on
 * the QR screen and the corrupted entry is overwritten by the next
 * successful pairing.
 */
class PairingRepository(private val context: Context) {

    private val prefs: SharedPreferences? by lazy { openPrefs() }

    /**
     * Build (or open) the encrypted prefs file. Isolated so we can swallow
     * I/O failures into a `null` repository — the alternative (throwing
     * from [load]) would crash MainActivity on cold start in the rare case
     * that the keystore entry is unreachable (device migration, factory
     * reset of credential store, etc.).
     */
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
        Log.w(TAG, "EncryptedSharedPreferences unavailable; pairing won't persist", it)
    }.getOrNull()

    fun save(url: String) {
        val p = prefs ?: return
        runCatching { p.edit().putString(KEY_URL, url).apply() }
            .onFailure { Log.w(TAG, "save() failed; pairing not persisted", it) }
    }

    fun load(): String? {
        val p = prefs ?: return null
        return runCatching { p.getString(KEY_URL, null) }
            .onFailure { Log.w(TAG, "load() failed; treating as un-paired", it) }
            .getOrNull()
    }

    fun clear() {
        val p = prefs ?: return
        runCatching { p.edit().remove(KEY_URL).apply() }
            .onFailure { Log.w(TAG, "clear() failed", it) }
    }

    companion object {
        private const val TAG = "PairingRepository"
        private const val PREFS_NAME = "spk_pairing"
        private const val KEY_URL = "url"

        /**
         * Process-wide singleton. The repo is intentionally tied to
         * `applicationContext` (not the Activity's context) so that
         * configuration changes don't churn the EncryptedSharedPreferences
         * instance — opening it costs a master-key unlock round-trip with
         * the Android keystore.
         */
        @Volatile
        private var instance: PairingRepository? = null

        fun get(context: Context): PairingRepository =
            instance ?: synchronized(this) {
                instance ?: PairingRepository(context.applicationContext).also { instance = it }
            }
    }
}
