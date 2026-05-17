package ru.sipaha.spkremote.app.data

import android.content.Context
import android.util.Log
import androidx.security.crypto.MasterKey

/**
 * Process-wide singleton wrapping the Android Keystore-backed [MasterKey]
 * used by every disk-backed repository in `:app/data` (R-6d).
 *
 * **Why one MasterKey:** constructing a [MasterKey] is not free — it
 * round-trips through the Android keystore subsystem to generate or
 * unwrap the AES256_GCM key on first use, and re-creating a fresh
 * instance per repository (PairingRepository, EncryptedQueueStore,
 * etc.) measurably slows down cold-start. The R-6b
 * [ru.sipaha.spkremote.app.data.PairingRepository] originally inlined
 * one of its own; R-6d hoists it here so every repository points at
 * the same JVM object.
 *
 * **Null on failure:** if the keystore is unreachable (factory-reset
 * credential store, device migration), [get] returns `null` and each
 * repository falls back to a no-op disk layer — the app still runs,
 * just without persistence. This mirrors PairingRepository's behavior
 * on R-6b and keeps cold-start from crashing on edge-case devices.
 */
internal object AppMasterKey {
    private const val TAG = "AppMasterKey"

    @Volatile
    private var cached: MasterKey? = null

    fun get(context: Context): MasterKey? {
        val existing = cached
        if (existing != null) return existing
        return synchronized(this) {
            cached ?: runCatching {
                MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
            }.onFailure {
                Log.w(TAG, "MasterKey unavailable; encrypted prefs disabled", it)
            }.getOrNull().also { cached = it }
        }
    }
}
