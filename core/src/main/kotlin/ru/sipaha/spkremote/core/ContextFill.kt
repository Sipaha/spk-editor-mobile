package ru.sipaha.spkremote.core

/** Resolved context-meter readout — usage against a (possibly defaulted) window. */
data class ContextFillState(val used: Long, val max: Long, val fraction: Float) {
    val percent: Int get() = (fraction * 100f).toInt()
}

object ContextFill {
    /**
     * Fallback window when the server reports no max (`max_tokens == null`
     * or the `0` "not filled in yet" sentinel). Mirrors the desktop
     * `status_row::DEFAULT_CONTEXT_WINDOW` so a sleeping / restored session
     * — whose `cached_max_tokens` is never restored from disk — still
     * renders a meter instead of a blank gap.
     */
    const val DEFAULT_CONTEXT_WINDOW_TOKENS: Long = 1_000_000L

    /**
     * Resolve the meter readout from the wire's nullable token counts.
     * Returns `null` only when there is nothing meaningful to show — no
     * usage reported AND no real window — so a brand-new session that has
     * never had a turn doesn't render a misleading "0%".
     */
    fun compute(totalTokens: Long?, maxTokens: Long?): ContextFillState? {
        val realMax = maxTokens?.takeIf { it > 0L }
        val used = (totalTokens ?: 0L).coerceAtLeast(0L)
        if (used == 0L && realMax == null) return null
        val max = realMax ?: DEFAULT_CONTEXT_WINDOW_TOKENS
        val fraction = (used.toFloat() / max.toFloat()).coerceIn(0f, 1f)
        return ContextFillState(used = used, max = max, fraction = fraction)
    }
}
