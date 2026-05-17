package ru.sipaha.spkremote.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server-shaped DTOs for the `remote.solutions.*` and
 * `remote.solution_agent.*` JSON-RPC namespaces.
 *
 * These mirror the spk-editor MCP tool catalog's response shapes verbatim,
 * including snake_case field names and the "state is a Rust Debug string"
 * quirk on [SessionSummary]. See [parseDisplayState] for the classifier the
 * UI uses to map that raw debug payload onto a small enum.
 */

@Serializable
data class SolutionSummary(
    val id: String,
    val name: String,
    val root: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("last_opened_at") val lastOpenedAt: String? = null,
    @SerialName("window_open") val windowOpen: Boolean,
    @SerialName("main_window_id") val mainWindowId: String? = null,
)

@Serializable
data class ListSolutionsResult(val solutions: List<SolutionSummary>)

@Serializable
data class SessionSummary(
    val id: String,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    /**
     * Raw `format!("{:?}", state)` dump from the server side. Stable prefixes:
     * `Idle`, `Running`, `AwaitingInput`, `Errored`. Use [parseDisplayState]
     * to classify — do NOT try to parse the parenthesised payload, it is
     * a Rust Debug rendering and shape varies (e.g. embedded `Instant`).
     */
    val state: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_activity_at") val lastActivityAt: Long,
)

@Serializable
data class ListSessionsResult(val sessions: List<SessionSummary>)

/**
 * One transcript entry returned by `remote.solution_agent.get_session`.
 *
 * The wire-side `EntrySummary` always carries a truncated `preview` (~200
 * chars). When the caller asks for `include_full_content=true` the server
 * also populates [markdown] with the entry's full markdown body. When
 * `include_images=true` and the entry is an assistant message containing
 * inline images, [images] is populated with base64-encoded blobs indexed by
 * their position in the markdown (referenced as `spk-image://N` URLs).
 *
 * For `role=tool_call` the server populates [toolCall] with name/status/args/
 * result; for `role=plan` it populates [plan] with the list of plan items.
 * These per-role payloads are absent on entries of other roles, so older
 * clients can ignore them safely.
 *
 * Backwards compatible with R-5d's flat `{role, preview}` shape — every new
 * field is nullable and defaulted, so the legacy minimal payload still
 * deserialises cleanly.
 */
@Serializable
data class EntrySummary(
    /** Role tag: `user`, `assistant`, `tool_call`, or `plan`. Use [parseEntryRole]. */
    val role: String,
    /** Truncated markdown rendering of the entry (≤200 chars, ellipsised). */
    val preview: String,
    /**
     * Full markdown body — only present when the request set
     * `include_full_content=true` AND the entry has more than the preview.
     */
    val markdown: String? = null,
    /**
     * Inline images referenced from [markdown] via `spk-image://N` URLs.
     * Present only when the request set `include_images=true` and the
     * entry actually carries image blocks. Index of each [EntryImage]
     * matches the `N` in the URL.
     */
    val images: List<EntryImage>? = null,
    /** Tool-call detail — present only on entries with `role=tool_call`. */
    @SerialName("tool_call") val toolCall: ToolCallSummary? = null,
    /** Plan detail — present only on entries with `role=plan`. */
    val plan: PlanSummary? = null,
)

/**
 * Base64-encoded inline image carried alongside an [EntrySummary].
 *
 * The server emits unpadded standard-alphabet base64. [index] matches the
 * `N` used to reference this image from markdown as `spk-image://N`.
 */
@Serializable
data class EntryImage(
    val index: Int,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("data_base64") val dataBase64: String,
)

/**
 * Server-side `EntrySummary.tool_call` payload for `role=tool_call` rows.
 *
 * [status] is a human-readable phrase: one of `"pending"`,
 * `"waiting for confirmation"`, `"running"`, `"done"`, `"failed"`,
 * `"rejected"`, `"canceled"`. Note the spaces (not underscores) and that
 * we DO NOT classify these into an enum — UI surfaces just render the
 * raw string with role-coloured pills.
 */
@Serializable
data class ToolCallSummary(
    val name: String,
    val status: String,
    @SerialName("args_preview") val argsPreview: String,
    @SerialName("result_preview") val resultPreview: String = "",
)

/**
 * Server-side `EntrySummary.plan` payload for `role=plan` rows.
 *
 * Each entry in [items] is a single plan step the agent committed to.
 */
@Serializable
data class PlanSummary(val items: List<String>)

/**
 * Result envelope for `remote.solution_agent.get_session_entry`.
 *
 * The server wraps the entry in a `{ entry: ... }` object rather than
 * returning it bare so future fields (cursor, neighbour ids, …) can be
 * added alongside without breaking clients that decode this type.
 */
@Serializable
data class GetSessionEntryResult(val entry: EntrySummary)

/**
 * Decoded payload of an `agent_session_message_appended` notification.
 *
 * Carries the entry index (post-R-5e); diff-streaming clients fetch the
 * full entry via `remote.solution_agent.get_session_entry { index }`
 * rather than re-polling the entire transcript on every append.
 */
@Serializable
data class MessageAppendedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("entry_index") val entryIndex: Int,
    val role: String,
    val preview: String,
)

@Serializable
data class GetSessionResult(
    val id: String,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("agent_id") val agentId: String,
    val title: String,
    /** Raw Rust `Debug` string — see [SessionSummary.state]. Classify via [parseDisplayState]. */
    val state: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("last_activity_at") val lastActivityAt: Long,
    val entries: List<EntrySummary>,
)

enum class DisplayState { Idle, Running, AwaitingInput, Errored, Unknown }

enum class EntryRole { User, Assistant, ToolCall, Plan, Unknown }

/**
 * Classify a server-side entry `role` string. Unknown roles fall through
 * to [EntryRole.Unknown] rather than throwing — older clients should
 * survive a future role expansion without crashing.
 */
fun parseEntryRole(raw: String): EntryRole = when (raw) {
    "user" -> EntryRole.User
    "assistant" -> EntryRole.Assistant
    "tool_call" -> EntryRole.ToolCall
    "plan" -> EntryRole.Plan
    else -> EntryRole.Unknown
}

/**
 * Classify a raw debug-formatted state string (see [SessionSummary.state]).
 *
 * The server emits Rust `Debug` format. Variants without payload come out as
 * the bare variant name (`"Idle"`); variants with payload come out as
 * `"VariantName { ... }"` or `"VariantName(...)"`. Prefix-match is the only
 * stable thing across server versions because the payload shape can change
 * (the `Running` variant has already been seen to embed an `Instant`).
 */
fun parseDisplayState(raw: String): DisplayState = when {
    raw.startsWith("Idle") -> DisplayState.Idle
    raw.startsWith("Running") -> DisplayState.Running
    raw.startsWith("AwaitingInput") -> DisplayState.AwaitingInput
    raw.startsWith("Errored") -> DisplayState.Errored
    else -> DisplayState.Unknown
}
