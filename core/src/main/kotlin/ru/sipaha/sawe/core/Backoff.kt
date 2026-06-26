package ru.sipaha.sawe.core

/**
 * Reconnect backoff schedule.
 *
 * Default schedule: `1s → 2s → 4s → 8s → 16s → 30s` (cap). After the first
 * successful reconnect the schedule resets to `1s`.
 *
 * Why exponential capped at 30 s:
 *   - Short for the common case (LTE handoff, brief Wi-Fi roam) — the user
 *     barely notices.
 *   - Capped at 30 s so a longer outage doesn't drift into 5-minute retries
 *     where re-pair would be faster.
 *   - No jitter today: the server can absorb a thundering herd of one
 *     device, and per-device randomness adds testing complexity.
 *
 * Tests can substitute [fixed] (every attempt returns the same delay) or
 * pass a custom lambda.
 */
fun interface BackoffStrategy {
    /**
     * Delay before the *next* attempt, in milliseconds.
     *
     * @param attempt 1-indexed: `attempt=1` means "we're about to make the
     *   first retry"; `attempt=2` after that, etc.
     */
    fun nextDelayMs(attempt: Int): Long

    companion object {
        /** The default `1 → 2 → 4 → 8 → 16 → 30` cap schedule (seconds). */
        val Default: BackoffStrategy = BackoffStrategy { attempt ->
            val seconds = when {
                attempt <= 0 -> 1L
                attempt > 30 -> 30L // belt-and-braces against overflow
                else -> minOf(30L, 1L shl (attempt - 1))
            }
            seconds * 1_000L
        }

        /** Fixed-delay backoff — useful in tests. */
        fun fixed(delayMs: Long): BackoffStrategy = BackoffStrategy { delayMs }
    }
}
