package ru.sipaha.spkremote.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure content-merge / dedup algorithms used by the chat detail
 * collaborator. Extracted out of the Android `:app` module so they can be
 * unit-tested on the JVM without an Android context.
 *
 * Each function is total over its inputs (never throws), returns new
 * immutable lists, and never touches state-flows. The state-flow-touching
 * wrappers live in `SessionDetailStore` and just call these.
 */

/**
 * Outcome of [applyAppendedPlaceholder]:
 *
 *   - [Replaced] — the placeholder fit inside the current entries (either
 *     appended at the end, or in-place replaced an existing slot). The
 *     caller publishes [entries] onto the session flow.
 *   - [OutOfRange] — the notification's [MessageAppendedPayload.entryIndex]
 *     is beyond `entries.size`. The caller must fall back to a full
 *     refetch of the session because the local transcript is missing the
 *     intermediate entries.
 */
sealed interface AppendedPlaceholderOutcome {
    data class Replaced(val entries: List<EntrySummary>) : AppendedPlaceholderOutcome
    data object OutOfRange : AppendedPlaceholderOutcome
}

/**
 * Apply a `agent_session_message_appended` placeholder onto the current
 * entry list.
 *
 *   - When `payload.entryIndex == current.size` — append at the end.
 *   - When `payload.entryIndex < current.size` — replace the slot
 *     in-place (idempotent: replaying the same payload yields the same
 *     list).
 *   - Otherwise (`payload.entryIndex > current.size`) — the local view is
 *     missing intermediate entries; caller must refetch.
 *
 * Pure — never mutates [current].
 */
fun applyAppendedPlaceholder(
    current: List<EntrySummary>,
    payload: MessageAppendedPayload,
): AppendedPlaceholderOutcome {
    val placeholder = EntrySummary(role = payload.role, preview = payload.preview)
    return when {
        payload.entryIndex == current.size ->
            AppendedPlaceholderOutcome.Replaced(current + placeholder)
        payload.entryIndex in 0 until current.size ->
            AppendedPlaceholderOutcome.Replaced(
                current.toMutableList().also { it[payload.entryIndex] = placeholder },
            )
        else -> AppendedPlaceholderOutcome.OutOfRange
    }
}

/**
 * Pop optimistic bubbles whose corresponding server-side "user" entry
 * has now landed. Matching is by `stripRoleHeading(preview)` content,
 * walking optimistic entries in FIFO order and removing one server
 * preview from the candidate set per match. Original arrival order of
 * unmatched optimistic entries is preserved. The companion id list is
 * rewritten in lock-step so the cancel-by-id path in the producer keeps
 * working.
 *
 * @param optimistic    Current optimistic bubble list (mutated copy returned).
 * @param optimisticIds Stable per-bubble id list paired with [optimistic] by index.
 * @param serverUserEntries The newly-received transcript slice (any role —
 *   the function filters for `role == "user"` internally).
 *
 * @return `(remainingOptimistic, remainingIds)` — both lists are
 *   freshly-allocated, ready to overwrite the state holders. Both have
 *   the same length and are paired by index, just like the inputs.
 */
fun reconcileOptimisticContent(
    optimistic: List<EntrySummary>,
    optimisticIds: List<Long>,
    serverUserEntries: List<EntrySummary>,
): Pair<List<EntrySummary>, List<Long>> {
    if (optimistic.isEmpty()) return emptyList<EntrySummary>() to emptyList()
    val serverPreviews = serverUserEntries
        .filter { it.role == "user" }
        .map { stripRoleHeading(it.preview) }
        .toMutableList()
    val keptEntries = mutableListOf<EntrySummary>()
    val keptIds = mutableListOf<Long>()
    for ((idx, opt) in optimistic.withIndex()) {
        val id = optimisticIds.getOrNull(idx) ?: -1L
        val key = stripRoleHeading(opt.preview)
        val hit = serverPreviews.indexOf(key)
        if (hit >= 0) {
            serverPreviews.removeAt(hit)
        } else {
            keptEntries.add(opt)
            keptIds.add(id)
        }
    }
    return keptEntries to keptIds
}

/**
 * Pure parser for the bounce-to-input recovery path. Returns
 * `(sessionId, content)` when [message] is a non-blank user-shaped
 * queued message that survived a process restart and we can route its
 * text back into the compose field, or `null` when the message should
 * be silently dropped.
 *
 * Two methods are recognised:
 *  - `remote.solution_agent.send_message` — the legacy text-only
 *    path. `content` is the raw text payload.
 *  - `remote.solution_agent.send_message_blocks` — the multi-block
 *    path (post-R-6c-attach). We recover the FIRST text block's body
 *    as the bounce content so the user gets their typed message back.
 *    Image / file blocks can't be reconstructed without the original
 *    URIs (which we never persisted), so they are dropped from the
 *    bounce — V1 behaviour, acceptable given the queue TTL is 24 h
 *    and the user is likely to re-attach if they cared.
 *
 * Extracted from `SessionDetailStore.handleExpiredMessage` so the JVM
 * test target can exercise it directly without instantiating a
 * `DraftRepository` (which depends on Android `SharedPreferences`).
 */
fun parseExpiredSendMessage(message: QueuedMessage): Pair<String, String>? {
    val params = (message.params as? JsonObject) ?: return null
    val sessionId = params["session_id"]?.jsonPrimitive?.content ?: return null
    val content: String? = when (message.method) {
        "remote.solution_agent.send_message" ->
            params["content"]?.jsonPrimitive?.content
        "remote.solution_agent.send_message_blocks" ->
            extractFirstTextBlockBody(params["blocks"] as? JsonArray)
        else -> return null
    }
    if (content.isNullOrBlank()) return null
    return sessionId to content
}

/**
 * Pluck the body of the first `{"type": "text", "text": "..."}` entry
 * from a `blocks` array. Returns null when [blocks] is null, empty, or
 * contains only non-text variants. The discriminator is read from the
 * `"type"` field (matches the ACP `#[serde(tag = "type")]` envelope —
 * see [ContentBlockDto]).
 */
private fun extractFirstTextBlockBody(blocks: JsonArray?): String? {
    if (blocks == null) return null
    for (element in blocks) {
        val obj = element as? JsonObject ?: continue
        val type = obj["type"]?.jsonPrimitive?.content ?: continue
        if (type == "text") {
            val text = obj["text"]?.jsonPrimitive?.content
            if (!text.isNullOrBlank()) return text
        }
    }
    return null
}
