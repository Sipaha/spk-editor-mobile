package ru.sipaha.spkremote.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Persists the active navigation route so cold start can land the user
 * back where they were (R-6d).
 *
 * **Why disk:** R-6b's auto-resume lands the user on `solutions` after
 * a successful pairing replay, which is correct when they last had the
 * solutions list open — but jarring when they were mid-chat on a deep
 * route like `solutions/{id}/sessions/{sid}`. Persisting the most-recent
 * resolved route closes that loop.
 *
 * **Route format:** the saved route string is the *resolved* navigation
 * route with arguments substituted (e.g. `solutions/abc/sessions/xyz`),
 * not the template (`solutions/{solutionId}/sessions/{sessionId}`). The
 * caller in `AppNavGraph` builds the resolved form from the
 * `NavBackStackEntry`'s arguments before saving — see [saveRoute] kdoc.
 *
 * **Validation on resume:** the cold-start path in `MainActivity` reads
 * the saved route via [loadRoute], hands it to the nav controller, and
 * — if the resulting destination 404s on lookup (e.g. the user deleted
 * the session from the desktop in the meantime) — falls back to
 * `solutions` and clears the saved route. We do NOT pre-validate
 * (session_exists round-trip before navigating) because the validation
 * happens naturally inside the screen anyway: SessionDetailScreen calls
 * `openSession` which exercises `get_session`, and a 404 there shows
 * the error state and the user pops back to a working route.
 *
 * **Storage:** plain [SharedPreferences]. The route string is purely
 * cosmetic (no auth secret), and encrypting it would slow cold-start
 * unnecessarily.
 */
class NavStateRepository(private val context: Context) {

    private val prefs: SharedPreferences? by lazy { openPrefs() }

    private fun openPrefs(): SharedPreferences? = runCatching {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.onFailure { Log.w(TAG, "openPrefs() failed; nav state won't persist", it) }
        .getOrNull()

    /**
     * Persist [route] as the last-known nav destination. Routes shallower
     * than `solutions` (i.e. `pairing` / `connecting`) are deliberately
     * NOT saved — those screens already auto-resolve on cold start from
     * the pairing-replay path, and persisting them would cause harmless
     * but ugly thrash where the nav controller "navigates" to itself.
     */
    fun saveRoute(route: String) {
        if (route.isBlank()) return
        if (route == "pairing" || route == "connecting") return
        val p = prefs ?: return
        runCatching { p.edit().putString(KEY_ROUTE, route).apply() }
            .onFailure { Log.w(TAG, "saveRoute() failed", it) }
    }

    /** The most-recently-saved route, or `null` if none. */
    fun loadRoute(): String? {
        val p = prefs ?: return null
        return runCatching { p.getString(KEY_ROUTE, null) }
            .onFailure { Log.w(TAG, "loadRoute() failed", it) }
            .getOrNull()
    }

    /** Drop the saved route. Called when the resume target 404s. */
    fun clear() {
        val p = prefs ?: return
        runCatching { p.edit().remove(KEY_ROUTE).apply() }
            .onFailure { Log.w(TAG, "clear() failed", it) }
    }

    companion object {
        private const val TAG = "NavStateRepository"
        private const val PREFS_NAME = "spk_nav_state"
        private const val KEY_ROUTE = "route"

        @Volatile
        private var instance: NavStateRepository? = null

        fun get(context: Context): NavStateRepository =
            instance ?: synchronized(this) {
                instance ?: NavStateRepository(context.applicationContext).also { instance = it }
            }
    }
}
