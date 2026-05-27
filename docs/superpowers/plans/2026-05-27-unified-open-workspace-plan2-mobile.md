# Unified Open-Workspace — Plan 2: Mobile (Kotlin/Compose) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the mobile (Kotlin/Compose) side of the unified open-workspace feature: a new `WorkspaceStore` + `WorkspaceScreen` that consume the `workspace.*` MCP wire delivered by Plan 1, with sticky-section UI, closed-solutions picker, optimistic UI for lifecycle actions, full `seq`-protocol delta sync, and Robolectric + Roborazzi test infrastructure so the engineer (and the agent) can self-verify UI changes visually.

**Architecture:** A new `WorkspaceStore` (in `app/vm/`) replaces the old `SolutionStore`. It owns the sequenced snapshot (`StateFlow<WorkspaceUiState>`), a delta applier guarded by `snapshotMutex`, a bounded pending-delta buffer, and four resync triggers (connect, gap, foreground, pull). It also owns a separate `closedSolutions: StateFlow<UiData<List<ClosedSolutionRow>>>` for the picker. The chat screen's `SessionDetailStore` is untouched — it observes the same notifications stream but for `agent_session_*` events, not `workspace.*`.

**Tech Stack:** Kotlin/Compose (Material 3), kotlinx-coroutines, kotlinx-serialization, navigation-compose, Robolectric + compose-ui-test for JVM unit tests, Roborazzi for golden-image screenshot tests. Existing `:core` crate (transport, JSON-RPC envelope, RemoteClient) is reused — Plan 2 only adds new DTO fields and consumers.

**Spec:** `docs/superpowers/specs/2026-05-27-unified-open-workspace-design.md`
**Plan 1 (prerequisite):** `docs/superpowers/plans/2026-05-27-unified-open-workspace-plan1-desktop.md` — must be merged and the desktop running with `wire_schema_version: 2` for Plan 2 to land cleanly.

**Working directory for all commands:** `/home/spk/.spk/spk-editor/solutions/spk-solutions/spk-editor-mobile/`

---

## Phase A — Test infrastructure (Robolectric + Roborazzi)

This is the foundation that lets the engineer (and the agent) self-verify UI changes via golden screenshots. Lands first so all subsequent UI tasks can lean on it.

### Task A1: Add Robolectric + compose-ui-test deps

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add versions + libraries.**

In `gradle/libs.versions.toml`, append to `[versions]`:
```toml
robolectric = "4.16"
roborazzi = "1.49.0"
```

In `[libraries]`:
```toml
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
roborazzi-compose = { module = "io.github.takahirom.roborazzi:roborazzi-compose", version.ref = "roborazzi" }
roborazzi-junit-rule = { module = "io.github.takahirom.roborazzi:roborazzi-junit-rule", version.ref = "roborazzi" }
```

In `[plugins]`:
```toml
roborazzi = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
```

- [ ] **Step 2: Wire deps into the app module.**

In `app/build.gradle.kts`, add to `plugins { ... }`:
```kotlin
alias(libs.plugins.roborazzi)
```

In the `android { ... testOptions { ... } }` block, add (or merge):
```kotlin
testOptions {
    unitTests {
        isIncludeAndroidResources = true
    }
}
```

In `dependencies { ... }`, append:
```kotlin
testImplementation(libs.robolectric)
testImplementation(libs.compose.ui.test.junit4)
testImplementation(libs.compose.ui.test.manifest)
testImplementation(libs.roborazzi.compose)
testImplementation(libs.roborazzi.junit.rule)
```

- [ ] **Step 3: Run a sanity check.**

Run: `./gradlew :app:compileDebugUnitTestKotlin`
Expected: PASS (no test code yet — just verifying the deps resolve).

- [ ] **Step 4: Commit.**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build(app): add Robolectric + Roborazzi for JVM Compose UI tests"
```

---

### Task A2: First Robolectric + Roborazzi sanity test

**Files:**
- Create: `app/src/test/kotlin/ru/sipaha/spkremote/app/ui/RoborazziSanityTest.kt`

- [ ] **Step 1: Write the sanity test.**

```kotlin
package ru.sipaha.spkremote.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class RoborazziSanityTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rig_renders_a_text_and_captures_a_png() {
        composeRule.setContent {
            Text("Roborazzi sanity OK")
        }
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/roborazzi/RoborazziSanityTest_rig_renders_a_text_and_captures_a_png.png"
        )
    }
}
```

- [ ] **Step 2: Run the test, accept the golden.**

```bash
./gradlew :app:testDebugUnitTest --tests RoborazziSanityTest -Proborazzi.test.record=true
```

Expected: PASS. A PNG is written to `app/src/test/snapshots/roborazzi/RoborazziSanityTest_rig_renders_a_text_and_captures_a_png.png`.

- [ ] **Step 3: Read the PNG with the `Read` tool to confirm it actually rendered.**

```
Read app/src/test/snapshots/roborazzi/RoborazziSanityTest_rig_renders_a_text_and_captures_a_png.png
```

Expected: the PNG contains the text "Roborazzi sanity OK" rendered by Compose. If it's blank or corrupt, debug Robolectric SDK / GraphicsMode setup before continuing.

- [ ] **Step 4: Run again in compare mode — should match the golden.**

```bash
./gradlew :app:testDebugUnitTest --tests RoborazziSanityTest -Proborazzi.test.compare=true
```

Expected: PASS, no diff.

- [ ] **Step 5: Commit golden + test.**

```bash
git add app/src/test/kotlin/ru/sipaha/spkremote/app/ui/RoborazziSanityTest.kt \
        app/src/test/snapshots/roborazzi/RoborazziSanityTest_rig_renders_a_text_and_captures_a_png.png
git commit -m "test(app): Robolectric + Roborazzi sanity test renders Compose to PNG"
```

---

## Phase B — Wire DTO updates + schema bump

The desktop (Plan 1) ships wire schema v2 with: `SolutionSummary.window_open` renamed to `open`, `solution_agent.close_session` renamed to `solution_agent.delete_session`. Plus all the new `workspace.*` DTOs.

### Task B1: Update `SolutionSummary` DTO field rename

**Files:**
- Modify: `core/src/main/kotlin/ru/sipaha/spkremote/core/RemoteDtos.kt`

- [ ] **Step 1: Grep for all references.**

```bash
grep -rn "windowOpen\|window_open" core/src app/src --include='*.kt'
```

Expected: ~5–10 hits (DTO field + use sites in screens + tests).

- [ ] **Step 2: Rename the DTO field.**

In `core/src/main/kotlin/ru/sipaha/spkremote/core/RemoteDtos.kt`, find `SolutionSummary` (around line 81). Change:
```kotlin
@SerialName("window_open") val windowOpen: Boolean,
```
to:
```kotlin
@SerialName("open") val open: Boolean,
```

- [ ] **Step 3: Update every Kotlin use-site.**

Every `.windowOpen` access becomes `.open`. Likely hits: `SolutionsListScreen.kt` (the `solution.windowOpen ? "open" : "closed"` strings around line 207), tests.

- [ ] **Step 4: Update JSON fixture/test strings.**

```bash
grep -rn '"window_open"' core/src app/src --include='*.kt'
```

Any test JSON literals containing `"window_open"` get renamed to `"open"`.

- [ ] **Step 5: Verify build + tests.**

```bash
./gradlew :core:test :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add core/src app/src
git commit -m "refactor(core): rename SolutionSummary.windowOpen -> open to match wire v2"
```

---

### Task B2: Bump `supportedWireSchemaVersion` 1 → 2

**Files:**
- Modify: wherever `supportedWireSchemaVersion` is declared on mobile.

- [ ] **Step 1: Find the constant.**

```bash
grep -rn "supportedWireSchemaVersion\|SUPPORTED_WIRE_SCHEMA" core/src app/src --include='*.kt'
```

- [ ] **Step 2: Bump it.**

Change `1` → `2`. Add a brief KDoc comment noting that v2 added the `workspace.*` namespace + renamed `window_open` and `close_session`.

- [ ] **Step 3: Update test that asserts the constant.**

If a `WireSchemaTest` or similar exists, update its expected value.

- [ ] **Step 4: Build + test.**

```bash
./gradlew :core:test :app:testDebugUnitTest
```

- [ ] **Step 5: Commit.**

```bash
git add core/src app/src
git commit -m "feat(core): bump supportedWireSchemaVersion to 2 (workspace.* namespace)"
```

---

### Task B3: Add `workspace.*` DTOs

**Files:**
- Modify: `core/src/main/kotlin/ru/sipaha/spkremote/core/RemoteDtos.kt`

Add these data classes anywhere in the file (group them together with a section comment):

```kotlin
// =====================================================================
// workspace.* DTOs (wire schema v2)
// =====================================================================

@Serializable
data class WorkspaceSolution(
    val id: String,
    val name: String,
    val root: String,
    @SerialName("member_count") val memberCount: Int,
    @SerialName("last_opened_at") val lastOpenedAt: String? = null,
    val open: Boolean,
    @SerialName("main_window_id") val mainWindowId: String? = null,
    val sessions: List<SessionSummary> = emptyList(),
)

@Serializable
data class WorkspaceSnapshot(
    val seq: Long,
    val solutions: List<WorkspaceSolution> = emptyList(),
)

@Serializable
data class WorkspaceListSolutionsResult(
    val solutions: List<SolutionSummary> = emptyList(),
)

@Serializable
data class WorkspaceSeqAck(val seq: Long)

// Delta payloads (sequenced — every one carries `seq`).

@Serializable
data class WorkspaceSolutionOpenedPayload(
    val seq: Long,
    val solution: SolutionSummary? = null,
    val sessions: List<SessionSummary> = emptyList(),
)

@Serializable
data class WorkspaceSolutionClosedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
)

@Serializable
data class WorkspaceSolutionDeletedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
)

@Serializable
data class WorkspaceSessionOpenedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
    val session: SessionSummary,
)

@Serializable
data class WorkspaceSessionClosedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class WorkspaceSessionDeletedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
data class WorkspaceSessionStateChangedPayload(
    val seq: Long,
    @SerialName("solution_id") val solutionId: String,
    @SerialName("session_id") val sessionId: String,
    val state: SessionStateDto,
)

// Non-sequenced — no `seq` field, never triggers gap detection.
@Serializable
data class WorkspaceSessionMetricsChangedPayload(
    @SerialName("session_id") val sessionId: String,
    @SerialName("last_activity_at") val lastActivityAt: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("max_tokens") val maxTokens: Long? = null,
)
```

- [ ] **Step 1: Add the DTOs as shown above.**

- [ ] **Step 2: Write a serde round-trip test.**

In `core/src/test/kotlin/ru/sipaha/spkremote/core/`, add a `WorkspaceDtosTest.kt`:

```kotlin
package ru.sipaha.spkremote.core

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun snapshot_decodes_empty_state() {
        val raw = """{"seq":0,"solutions":[]}"""
        val snap = json.decodeFromString<WorkspaceSnapshot>(raw)
        assertEquals(0L, snap.seq)
        assertEquals(0, snap.solutions.size)
    }

    @Test
    fun snapshot_decodes_one_solution_with_one_session() {
        val raw = """
            {
              "seq": 42,
              "solutions": [{
                "id": "s1", "name": "alpha", "root": "/x",
                "member_count": 1, "open": true,
                "sessions": [{
                  "id": "se1", "solution_id": "s1", "agent_id": "claude",
                  "title": "t", "state": {"kind": "idle"},
                  "created_at": 1, "last_activity_at": 1
                }]
              }]
            }
        """.trimIndent()
        val snap = json.decodeFromString<WorkspaceSnapshot>(raw)
        assertEquals(42L, snap.seq)
        assertEquals(1, snap.solutions.size)
        assertEquals(1, snap.solutions[0].sessions.size)
    }

    @Test
    fun solution_opened_payload_carries_seq_and_optional_solution() {
        val raw = """{"seq":3,"solution":{"id":"s1","name":"a","root":"/x","member_count":0,"open":true},"sessions":[]}"""
        val p = json.decodeFromString<WorkspaceSolutionOpenedPayload>(raw)
        assertEquals(3L, p.seq)
        assertEquals("s1", p.solution?.id)
    }

    @Test
    fun session_metrics_changed_payload_omits_seq() {
        val raw = """{"session_id":"se1","total_tokens":42}"""
        val p = json.decodeFromString<WorkspaceSessionMetricsChangedPayload>(raw)
        assertEquals("se1", p.sessionId)
        assertEquals(42L, p.totalTokens)
    }
}
```

- [ ] **Step 3: Run tests, expect pass.**

```bash
./gradlew :core:test --tests WorkspaceDtosTest
```

- [ ] **Step 4: Commit.**

```bash
git add core/src
git commit -m "feat(core): workspace.* DTOs for wire v2 (snapshot + deltas + metrics)"
```

---

## Phase C — WorkspaceStore (data layer)

The core consistency machinery. Replaces `SolutionStore` outright; coexists with `SessionListStore` and `SessionDetailStore`.

### Task C1: Skeleton + state types

**Files:**
- Create: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceStore.kt`

- [ ] **Step 1: Write the skeleton with VM types + `MutableStateFlow`s.**

```kotlin
package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sipaha.spkremote.core.SessionStateDto
import ru.sipaha.spkremote.core.SolutionSummary

sealed interface WorkspaceUiState {
    object Loading : WorkspaceUiState
    data class Loaded(val snapshot: WorkspaceSnapshotVM, val stale: Boolean) : WorkspaceUiState
    data class Error(val message: String) : WorkspaceUiState
}

data class WorkspaceSnapshotVM(
    val seq: Long,
    val solutions: List<OpenSolutionVM>,
)

data class OpenSolutionVM(
    val id: String,
    val name: String,
    val memberCount: Int,
    val sessions: List<OpenSessionVM>,
)

data class OpenSessionVM(
    val id: String,
    val title: String,
    val state: SessionStateDto,
    val lastActivityAt: Long,
    val totalTokens: Long?,
    val maxTokens: Long?,
)

data class ClosedSolutionRow(
    val id: String,
    val name: String,
    val memberCount: Int,
    val lastOpenedAt: String?,
)

/**
 * WorkspaceStore — the mobile snapshot of the desktop's open workspace.
 *
 * Owns:
 * - `state` — the live snapshot with strong consistency via the `seq` protocol.
 * - `closedSolutions` — lazy picker contents (refetched on sheet-open).
 *
 * Plan-1 wire surface consumed:
 * - `workspace.snapshot` — bulk RPC for cold start + resync.
 * - `workspace.list_solutions(open: false)` — picker query.
 * - `workspace.open_solution / close_solution / open_session / close_session` —
 *   lifecycle RPCs; called from `MainViewModel` wrappers. Optimistic UI: snapshot
 *   mutated locally first, `lastAppliedSeq` advanced only when the matching
 *   delta arrives.
 * - `workspace.*` notifications — applied to the snapshot via [onNotification].
 *
 * Consistency rules:
 * - Strongly consistent fields (presence, open, state, title) — sequenced via
 *   `seq` per delta + bulk-refetch on gap / connect / foreground / pull.
 * - Eventually consistent fields (last_activity_at, total_tokens, max_tokens)
 *   — patched out-of-band via [WorkspaceSessionMetricsChangedPayload]; never
 *   triggers gap detection.
 */
class WorkspaceStore(
    // dependencies — injected from MainViewModel
    // (filled in by later tasks).
) {
    private val _state = MutableStateFlow<WorkspaceUiState>(WorkspaceUiState.Loading)
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    private val _closedSolutions = MutableStateFlow<UiData<List<ClosedSolutionRow>>>(UiData.Loading)
    val closedSolutions: StateFlow<UiData<List<ClosedSolutionRow>>> = _closedSolutions.asStateFlow()
}
```

NOTE: `UiData` is an existing sealed type in this codebase (look at `SolutionStore.kt` for the import). Use the same type for parity.

- [ ] **Step 2: Verify compile.**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 3: Commit.**

```bash
git add app/src/main/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceStore.kt
git commit -m "feat(app): WorkspaceStore skeleton + state types"
```

---

### Task C2: Bulk snapshot loader (TDD)

**Files:**
- Modify: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceStore.kt`
- Create: `app/src/test/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceStoreTest.kt`

- [ ] **Step 1: Write the failing test for cold-bulk.**

`WorkspaceStoreTest.kt`:
```kotlin
package ru.sipaha.spkremote.app.vm

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceStoreTest {

    @Test
    fun cold_bulk_populates_snapshot_with_correct_seq() = runTest {
        val fakeClient = FakeWorkspaceClient(
            snapshotResult = WorkspaceSnapshotVM(
                seq = 7,
                solutions = listOf(
                    OpenSolutionVM(
                        id = "s1", name = "alpha", memberCount = 1,
                        sessions = listOf(
                            OpenSessionVM(
                                id = "se1", title = "session 1",
                                state = SessionStateDto.Idle,
                                lastActivityAt = 1000L,
                                totalTokens = null, maxTokens = null,
                            )
                        )
                    )
                )
            )
        )
        val store = WorkspaceStore(client = fakeClient, scope = backgroundScope)

        store.refresh()
        advanceUntilIdle()

        val state = store.state.value as WorkspaceUiState.Loaded
        assertEquals(7L, state.snapshot.seq)
        assertEquals(1, state.snapshot.solutions.size)
        assertEquals("s1", state.snapshot.solutions[0].id)
        assertEquals(false, state.stale)
    }
}

/** Minimal in-memory mock of the wire client. Fill in surface as needed. */
class FakeWorkspaceClient(
    val snapshotResult: WorkspaceSnapshotVM = WorkspaceSnapshotVM(0, emptyList()),
) {
    suspend fun fetchSnapshot(): WorkspaceSnapshotVM = snapshotResult
    // ... add lifecycle calls / closed list etc. in later tasks.
}
```

- [ ] **Step 2: Run, expect failure.**

```bash
./gradlew :app:testDebugUnitTest --tests WorkspaceStoreTest
```

Expected: FAIL — `WorkspaceStore` doesn't have a `refresh()` method and constructor doesn't take a client.

- [ ] **Step 3: Implement.**

Update `WorkspaceStore.kt`:

```kotlin
class WorkspaceStore(
    private val client: FakeWorkspaceClient,   // type stays `FakeWorkspaceClient` only for tests;
                                               // production uses an interface from `:core` —
                                               // refactor to that interface in Task C3.
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val _state = MutableStateFlow<WorkspaceUiState>(WorkspaceUiState.Loading)
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    suspend fun refresh() {
        val snap = client.fetchSnapshot()
        _state.value = WorkspaceUiState.Loaded(snap, stale = false)
    }
}
```

NOTE: This is bare-minimum to get the test green. C3 introduces the real client interface and the snapshotMutex.

- [ ] **Step 4: Run test, expect pass.**

- [ ] **Step 5: Commit.**

```bash
git add app/src
git commit -m "feat(app): WorkspaceStore cold-bulk loader (refresh)"
```

---

### Task C3: Extract real wire client interface + delta application

**Files:**
- Modify: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceStore.kt`
- Modify: `app/src/test/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceStoreTest.kt`

- [ ] **Step 1: Write failing tests for delta application.**

Append to `WorkspaceStoreTest.kt`:

```kotlin
@Test
fun seq_plus_one_delta_applies_and_advances_lastAppliedSeq() = runTest {
    val fake = FakeWorkspaceClient(
        snapshotResult = WorkspaceSnapshotVM(
            seq = 5,
            solutions = listOf(OpenSolutionVM("s1", "a", 0, emptyList())),
        )
    )
    val store = WorkspaceStore(client = fake, scope = backgroundScope)
    store.refresh()
    advanceUntilIdle()

    // Solution s2 opens via delta.
    store.onSolutionOpened(seq = 6, solution = SolutionSummary(
        id = "s2", name = "beta", root = "/y", memberCount = 0,
        lastOpenedAt = null, open = true, mainWindowId = null,
    ), sessions = emptyList())
    advanceUntilIdle()

    val state = store.state.value as WorkspaceUiState.Loaded
    assertEquals(6L, state.snapshot.seq)
    assertEquals(2, state.snapshot.solutions.size)
}

@Test
fun seq_lower_or_equal_delta_is_dropped_as_duplicate() = runTest {
    val fake = FakeWorkspaceClient(
        snapshotResult = WorkspaceSnapshotVM(
            seq = 5, solutions = emptyList(),
        )
    )
    val store = WorkspaceStore(client = fake, scope = backgroundScope)
    store.refresh(); advanceUntilIdle()

    store.onSolutionClosed(seq = 5, solutionId = "s1")
    advanceUntilIdle()

    val state = store.state.value as WorkspaceUiState.Loaded
    assertEquals(5L, state.snapshot.seq, "duplicate seq must not advance")
}

@Test
fun seq_gap_triggers_resync() = runTest {
    var snapshotCalls = 0
    val fake = object : FakeWorkspaceClient() {
        override suspend fun fetchSnapshot(): WorkspaceSnapshotVM {
            snapshotCalls += 1
            return WorkspaceSnapshotVM(
                seq = if (snapshotCalls == 1) 5 else 10,
                solutions = emptyList(),
            )
        }
    }
    val store = WorkspaceStore(client = fake, scope = backgroundScope)
    store.refresh(); advanceUntilIdle()

    store.onSolutionOpened(seq = 8, solution = anyTestSolution("s1"), sessions = emptyList())
    advanceUntilIdle()

    assertEquals(2, snapshotCalls, "gap (8 > 5+1) must trigger resync")
    val state = store.state.value as WorkspaceUiState.Loaded
    assertEquals(10L, state.snapshot.seq)
}
```

You'll need a small `anyTestSolution(id: String)` helper somewhere accessible (could be a top-level fun in the test file).

- [ ] **Step 2: Run, expect failure.**

- [ ] **Step 3: Implement.**

In `WorkspaceStore.kt`, replace the body with:

```kotlin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sipaha.spkremote.core.SessionSummary
import ru.sipaha.spkremote.core.SolutionSummary

interface WorkspaceClient {
    suspend fun fetchSnapshot(): WorkspaceSnapshotVM
    // ... lifecycle + closed list added in later tasks.
}

class WorkspaceStore(
    private val client: WorkspaceClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<WorkspaceUiState>(WorkspaceUiState.Loading)
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    private val snapshotMutex = Mutex()
    private val pending = ArrayDeque<SequencedDelta>()
    private var resyncInFlight = false

    // ---- public API ----

    suspend fun refresh() = snapshotMutex.withLock {
        runResyncLocked()
    }

    fun onSolutionOpened(seq: Long, solution: SolutionSummary?, sessions: List<SessionSummary>) {
        applyOrBufferSequenced(SequencedDelta.SolutionOpened(seq, solution, sessions))
    }

    fun onSolutionClosed(seq: Long, solutionId: String) {
        applyOrBufferSequenced(SequencedDelta.SolutionClosed(seq, solutionId))
    }

    fun onSolutionDeleted(seq: Long, solutionId: String) {
        applyOrBufferSequenced(SequencedDelta.SolutionDeleted(seq, solutionId))
    }

    fun onSessionOpened(seq: Long, solutionId: String, session: SessionSummary) {
        applyOrBufferSequenced(SequencedDelta.SessionOpened(seq, solutionId, session))
    }

    fun onSessionClosed(seq: Long, solutionId: String, sessionId: String) {
        applyOrBufferSequenced(SequencedDelta.SessionClosed(seq, solutionId, sessionId))
    }

    fun onSessionDeleted(seq: Long, solutionId: String, sessionId: String) {
        applyOrBufferSequenced(SequencedDelta.SessionDeleted(seq, solutionId, sessionId))
    }

    // ---- internals ----

    private fun applyOrBufferSequenced(delta: SequencedDelta) {
        scope.launch {
            snapshotMutex.withLock {
                val cur = _state.value as? WorkspaceUiState.Loaded
                if (cur == null) {
                    pending.addLastBounded(delta)
                    return@withLock
                }
                val expected = cur.snapshot.seq + 1
                when {
                    delta.seq <= cur.snapshot.seq -> return@withLock // duplicate
                    delta.seq == expected -> {
                        val next = applyDelta(cur.snapshot, delta)
                        _state.value = WorkspaceUiState.Loaded(next, stale = false)
                    }
                    else -> {
                        pending.addLastBounded(delta)
                        if (!resyncInFlight) runResyncLocked()
                    }
                }
            }
        }
    }

    private suspend fun runResyncLocked() {
        resyncInFlight = true
        try {
            // Mark stale (keep showing old snapshot) instead of flicking to Loading
            // if we already have one. Cold start has no snapshot — show Loading.
            val cur = _state.value as? WorkspaceUiState.Loaded
            if (cur != null) {
                _state.value = WorkspaceUiState.Loaded(cur.snapshot, stale = true)
            }
            val snap = client.fetchSnapshot()
            var s = snap
            // Replay buffered deltas with seq > snap.seq.
            val drained = pending.toList()
            pending.clear()
            for (d in drained.filter { it.seq > s.seq }.sortedBy { it.seq }) {
                if (d.seq == s.seq + 1) s = applyDelta(s, d)
                // Skip out-of-order; will be picked up by next round-trip.
            }
            _state.value = WorkspaceUiState.Loaded(s, stale = false)
        } finally {
            resyncInFlight = false
        }
    }

    private fun ArrayDeque<SequencedDelta>.addLastBounded(d: SequencedDelta) {
        if (size >= 256) {
            // Overflow — drop the buffer and force a resync at next chance.
            clear()
            scope.launch { snapshotMutex.withLock { runResyncLocked() } }
        }
        addLast(d)
    }

    private sealed interface SequencedDelta {
        val seq: Long
        data class SolutionOpened(override val seq: Long, val solution: SolutionSummary?, val sessions: List<SessionSummary>) : SequencedDelta
        data class SolutionClosed(override val seq: Long, val solutionId: String) : SequencedDelta
        data class SolutionDeleted(override val seq: Long, val solutionId: String) : SequencedDelta
        data class SessionOpened(override val seq: Long, val solutionId: String, val session: SessionSummary) : SequencedDelta
        data class SessionClosed(override val seq: Long, val solutionId: String, val sessionId: String) : SequencedDelta
        data class SessionDeleted(override val seq: Long, val solutionId: String, val sessionId: String) : SequencedDelta
        data class SessionStateChanged(override val seq: Long, val solutionId: String, val sessionId: String, val state: SessionStateDto) : SequencedDelta
    }

    private fun applyDelta(snap: WorkspaceSnapshotVM, d: SequencedDelta): WorkspaceSnapshotVM {
        val newSolutions = snap.solutions.toMutableList()
        when (d) {
            is SequencedDelta.SolutionOpened -> {
                val sol = d.solution ?: return snap.copy(seq = d.seq)
                if (newSolutions.none { it.id == sol.id }) {
                    newSolutions.add(OpenSolutionVM(
                        id = sol.id, name = sol.name, memberCount = sol.memberCount,
                        sessions = d.sessions.map { it.toVM() }
                    ))
                }
            }
            is SequencedDelta.SolutionClosed, is SequencedDelta.SolutionDeleted -> {
                val id = when (d) {
                    is SequencedDelta.SolutionClosed -> d.solutionId
                    is SequencedDelta.SolutionDeleted -> d.solutionId
                    else -> error("unreachable")
                }
                newSolutions.removeAll { it.id == id }
            }
            is SequencedDelta.SessionOpened -> {
                val idx = newSolutions.indexOfFirst { it.id == d.solutionId }
                if (idx >= 0) {
                    val sol = newSolutions[idx]
                    if (sol.sessions.none { it.id == d.session.id }) {
                        newSolutions[idx] = sol.copy(sessions = sol.sessions + d.session.toVM())
                    }
                }
            }
            is SequencedDelta.SessionClosed, is SequencedDelta.SessionDeleted -> {
                val (solId, sesId) = when (d) {
                    is SequencedDelta.SessionClosed -> d.solutionId to d.sessionId
                    is SequencedDelta.SessionDeleted -> d.solutionId to d.sessionId
                    else -> error("unreachable")
                }
                val idx = newSolutions.indexOfFirst { it.id == solId }
                if (idx >= 0) {
                    val sol = newSolutions[idx]
                    newSolutions[idx] = sol.copy(sessions = sol.sessions.filterNot { it.id == sesId })
                }
            }
            is SequencedDelta.SessionStateChanged -> {
                val idx = newSolutions.indexOfFirst { it.id == d.solutionId }
                if (idx >= 0) {
                    val sol = newSolutions[idx]
                    val sIdx = sol.sessions.indexOfFirst { it.id == d.sessionId }
                    if (sIdx >= 0) {
                        val updated = sol.sessions[sIdx].copy(state = d.state)
                        newSolutions[idx] = sol.copy(sessions = sol.sessions.toMutableList().also { it[sIdx] = updated })
                    }
                }
            }
        }
        return WorkspaceSnapshotVM(seq = d.seq, solutions = newSolutions)
    }
}

private fun SessionSummary.toVM(): OpenSessionVM = OpenSessionVM(
    id = id, title = title, state = state,
    lastActivityAt = lastActivityAt,
    totalTokens = totalTokens, maxTokens = maxTokens,
)
```

- [ ] **Step 4: Run all tests, expect pass.**

```bash
./gradlew :app:testDebugUnitTest --tests WorkspaceStoreTest
```

- [ ] **Step 5: Commit.**

```bash
git add app/src
git commit -m "feat(app): WorkspaceStore delta protocol (apply / gap-resync / dup-drop, bounded buffer)"
```

---

### Task C4: Session metrics patch + closed solutions picker

**Files:**
- Modify: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceStore.kt`
- Modify: `app/src/test/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceStoreTest.kt`

- [ ] **Step 1: Test for metrics + picker.**

```kotlin
@Test
fun metrics_patch_updates_only_known_session_and_does_not_advance_seq() = runTest {
    val fake = FakeWorkspaceClient(
        snapshotResult = WorkspaceSnapshotVM(
            seq = 5,
            solutions = listOf(OpenSolutionVM("s1", "a", 0, listOf(
                OpenSessionVM("se1", "t", SessionStateDto.Idle, 0L, null, null)
            )))
        )
    )
    val store = WorkspaceStore(client = fake, scope = backgroundScope)
    store.refresh(); advanceUntilIdle()

    store.onSessionMetricsChanged(sessionId = "se1", lastActivityAt = 7777L, totalTokens = 99L, maxTokens = null)
    store.onSessionMetricsChanged(sessionId = "UNKNOWN", lastActivityAt = 0L, totalTokens = 0L, maxTokens = null)
    advanceUntilIdle()

    val state = store.state.value as WorkspaceUiState.Loaded
    assertEquals(5L, state.snapshot.seq, "metrics must not advance seq")
    val ses = state.snapshot.solutions[0].sessions[0]
    assertEquals(7777L, ses.lastActivityAt)
    assertEquals(99L, ses.totalTokens)
}

@Test
fun refresh_closed_solutions_populates_picker() = runTest {
    val fake = FakeWorkspaceClient(
        closedListResult = listOf(
            ClosedSolutionRow("c1", "frozen", 2, lastOpenedAt = null)
        ),
    )
    val store = WorkspaceStore(client = fake, scope = backgroundScope)
    store.refreshClosedSolutions()
    advanceUntilIdle()

    val list = (store.closedSolutions.value as UiData.Loaded).value
    assertEquals(1, list.size)
    assertEquals("c1", list[0].id)
}
```

- [ ] **Step 2: Implement.**

Extend `WorkspaceClient`:
```kotlin
interface WorkspaceClient {
    suspend fun fetchSnapshot(): WorkspaceSnapshotVM
    suspend fun fetchClosedSolutions(): List<ClosedSolutionRow>
    suspend fun openSolution(id: String): Long
    suspend fun closeSolution(id: String): Long
    suspend fun openSession(id: String): Long
    suspend fun closeSession(id: String): Long
}
```

Add to `WorkspaceStore`:
```kotlin
fun onSessionMetricsChanged(
    sessionId: String, lastActivityAt: Long?, totalTokens: Long?, maxTokens: Long?
) {
    scope.launch {
        snapshotMutex.withLock {
            val cur = _state.value as? WorkspaceUiState.Loaded ?: return@withLock
            val newSolutions = cur.snapshot.solutions.map { sol ->
                val sIdx = sol.sessions.indexOfFirst { it.id == sessionId }
                if (sIdx < 0) sol else sol.copy(
                    sessions = sol.sessions.toMutableList().also {
                        it[sIdx] = it[sIdx].copy(
                            lastActivityAt = lastActivityAt ?: it[sIdx].lastActivityAt,
                            totalTokens = totalTokens ?: it[sIdx].totalTokens,
                            maxTokens = maxTokens ?: it[sIdx].maxTokens,
                        )
                    }
                )
            }
            _state.value = WorkspaceUiState.Loaded(
                cur.snapshot.copy(solutions = newSolutions), stale = false,
            )
        }
    }
}

suspend fun refreshClosedSolutions() {
    _closedSolutions.value = UiData.Loading
    _closedSolutions.value = UiData.Loaded(client.fetchClosedSolutions())
}
```

- [ ] **Step 3: Run tests, expect pass.**

- [ ] **Step 4: Commit.**

```bash
git commit -m "feat(app): WorkspaceStore metrics patch + closed-solutions picker"
```

---

### Task C5: Real wire adapter for `WorkspaceClient` + nofitication dispatcher

**Files:**
- Create: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceClientImpl.kt`
- Modify: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/ConnectionManager.kt` (or wherever notifications are observed)

- [ ] **Step 1: Implement `WorkspaceClientImpl` calling `RemoteClient`.**

```kotlin
package ru.sipaha.spkremote.app.vm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.sipaha.spkremote.core.RemoteClient
import ru.sipaha.spkremote.core.WorkspaceSnapshot
import ru.sipaha.spkremote.core.WorkspaceListSolutionsResult
import ru.sipaha.spkremote.core.WorkspaceSeqAck

class WorkspaceClientImpl(
    private val getClient: () -> RemoteClient?,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : WorkspaceClient {

    override suspend fun fetchSnapshot(): WorkspaceSnapshotVM {
        val client = getClient() ?: throw IllegalStateException("Not connected")
        val raw: WorkspaceSnapshot = client.callTool(
            name = "workspace.snapshot",
            params = buildJsonObject {},
        )
        return raw.toVM()
    }

    override suspend fun fetchClosedSolutions(): List<ClosedSolutionRow> {
        val client = getClient() ?: throw IllegalStateException("Not connected")
        val raw: WorkspaceListSolutionsResult = client.callTool(
            name = "workspace.list_solutions",
            params = buildJsonObject { put("open", false) },
        )
        return raw.solutions.map {
            ClosedSolutionRow(id = it.id, name = it.name, memberCount = it.memberCount, lastOpenedAt = it.lastOpenedAt)
        }
    }

    override suspend fun openSolution(id: String): Long = lifecycleCall("workspace.open_solution", "solution_id", id)
    override suspend fun closeSolution(id: String): Long = lifecycleCall("workspace.close_solution", "solution_id", id)
    override suspend fun openSession(id: String): Long = lifecycleCall("workspace.open_session", "session_id", id)
    override suspend fun closeSession(id: String): Long = lifecycleCall("workspace.close_session", "session_id", id)

    private suspend fun lifecycleCall(toolName: String, paramKey: String, value: String): Long {
        val client = getClient() ?: throw IllegalStateException("Not connected")
        val ack: WorkspaceSeqAck = client.callTool(
            name = toolName,
            params = buildJsonObject { put(paramKey, value) },
        )
        return ack.seq
    }
}

private fun WorkspaceSnapshot.toVM(): WorkspaceSnapshotVM = WorkspaceSnapshotVM(
    seq = seq,
    solutions = solutions.map { sol ->
        OpenSolutionVM(
            id = sol.id, name = sol.name, memberCount = sol.memberCount,
            sessions = sol.sessions.map { it.toVM() }
        )
    },
)
```

NOTE: `RemoteClient.callTool<T>(name, params)` is the existing public API. If the signature differs (the actual generic parameter shape, error handling pattern), look at `SolutionStore.kt` / `SessionListStore.kt` for the exact pattern used and copy it. Do NOT invent a new tool-call API.

- [ ] **Step 2: Wire notification dispatcher.**

Find where the existing notification observer lives (likely `ConnectionManager.kt` or similar, around the `notificationsFlow.collect { ... }` block). Add a branch for `workspace.*` events:

```kotlin
"workspace.solution_opened" -> {
    val p = json.decodeFromJsonElement<WorkspaceSolutionOpenedPayload>(payload)
    workspaceStore.onSolutionOpened(p.seq, p.solution, p.sessions)
}
"workspace.solution_closed" -> {
    val p = json.decodeFromJsonElement<WorkspaceSolutionClosedPayload>(payload)
    workspaceStore.onSolutionClosed(p.seq, p.solutionId)
}
"workspace.solution_deleted" -> {
    val p = json.decodeFromJsonElement<WorkspaceSolutionDeletedPayload>(payload)
    workspaceStore.onSolutionDeleted(p.seq, p.solutionId)
}
"workspace.session_opened" -> {
    val p = json.decodeFromJsonElement<WorkspaceSessionOpenedPayload>(payload)
    workspaceStore.onSessionOpened(p.seq, p.solutionId, p.session)
}
"workspace.session_closed" -> {
    val p = json.decodeFromJsonElement<WorkspaceSessionClosedPayload>(payload)
    workspaceStore.onSessionClosed(p.seq, p.solutionId, p.sessionId)
}
"workspace.session_deleted" -> {
    val p = json.decodeFromJsonElement<WorkspaceSessionDeletedPayload>(payload)
    workspaceStore.onSessionDeleted(p.seq, p.solutionId, p.sessionId)
}
"workspace.session_state_changed" -> {
    val p = json.decodeFromJsonElement<WorkspaceSessionStateChangedPayload>(payload)
    workspaceStore.onSessionStateChanged(p.seq, p.solutionId, p.sessionId, p.state)
}
"workspace.session_metrics_changed" -> {
    val p = json.decodeFromJsonElement<WorkspaceSessionMetricsChangedPayload>(payload)
    workspaceStore.onSessionMetricsChanged(p.sessionId, p.lastActivityAt, p.totalTokens, p.maxTokens)
}
```

NOTE: `onSessionStateChanged` is the only sequenced delta method not yet on `WorkspaceStore` — add it now:
```kotlin
fun onSessionStateChanged(seq: Long, solutionId: String, sessionId: String, state: SessionStateDto) {
    applyOrBufferSequenced(SequencedDelta.SessionStateChanged(seq, solutionId, sessionId, state))
}
```

- [ ] **Step 3: Wire resync triggers.**

Where `ConnectionLifecycle.onConnected` fires (see `ConnectionManager.kt`), call `workspaceStore.refresh()`.
Where `ForegroundEventBus` reports resume, also call `workspaceStore.refresh()`.

Look at how `SolutionStore.refresh()` was called from these sites — replace the call with `workspaceStore.refresh()` for the same lifecycle hooks.

- [ ] **Step 4: Compile + tests.**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

- [ ] **Step 5: Commit.**

```bash
git commit -m "feat(app): WorkspaceClient wire adapter + notification dispatch + resync hooks"
```

---

### Task C6: Optimistic UI for lifecycle mutations

**Files:**
- Modify: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/WorkspaceStore.kt` (add pending-mutation tracking)
- Modify: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/MainViewModel.kt` (add lifecycle wrappers)

- [ ] **Step 1: Add public lifecycle methods on `WorkspaceStore` that:**

```kotlin
// In WorkspaceStore — these don't advance lastAppliedSeq. They mutate the
// snapshot for UX immediacy; the matching delta arrives later and is a no-op
// on the mutated snapshot. On ack-failure, rollback the optimistic change.

suspend fun openSolutionOptimistic(id: String) {
    val rollback = applyOptimistic { snap -> snap /* opens are best applied via delta, not optimistic */ }
    runCatching { client.openSolution(id) }
        .onFailure { rollback() }
}

suspend fun closeSolutionOptimistic(id: String) {
    val rollback = applyOptimistic { snap ->
        snap.copy(solutions = snap.solutions.filterNot { it.id == id })
    }
    runCatching { client.closeSolution(id) }
        .onFailure { rollback() }
}

suspend fun openSessionOptimistic(id: String) {
    // No-op locally — relies on server delta to surface the new session row.
    // Just fires the RPC.
    runCatching { client.openSession(id) }
}

suspend fun closeSessionOptimistic(id: String) {
    val rollback = applyOptimistic { snap ->
        snap.copy(solutions = snap.solutions.map { sol ->
            sol.copy(sessions = sol.sessions.filterNot { it.id == id })
        })
    }
    runCatching { client.closeSession(id) }
        .onFailure { rollback() }
}

/**
 * Apply a snapshot transform optimistically. Returns a rollback closure that
 * reverts to the snapshot at-the-time-of-mutation. Use only for UI immediacy
 * — lastAppliedSeq is NOT advanced; matching delta will apply idempotently.
 */
private suspend fun applyOptimistic(
    transform: (WorkspaceSnapshotVM) -> WorkspaceSnapshotVM,
): suspend () -> Unit {
    val before: WorkspaceSnapshotVM = snapshotMutex.withLock {
        val cur = _state.value as? WorkspaceUiState.Loaded ?: return {}
        val transformed = transform(cur.snapshot)
        _state.value = WorkspaceUiState.Loaded(transformed, stale = cur.stale)
        cur.snapshot
    }
    return {
        snapshotMutex.withLock {
            val cur = _state.value as? WorkspaceUiState.Loaded ?: return@withLock
            // Only rollback if the current state is still our optimistic version —
            // a concurrent delta may have moved us forward; in that case the delta
            // is the new truth, don't undo it.
            if (cur.snapshot.solutions != before.solutions) {
                _state.value = WorkspaceUiState.Loaded(before, stale = cur.stale)
            }
        }
    }
}
```

- [ ] **Step 2: Add `MainViewModel` wrappers.**

In `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/MainViewModel.kt`:

```kotlin
fun openSolution(id: String) = viewModelScope.launch {
    workspaceStore.openSolutionOptimistic(id)
}
fun closeSolution(id: String) = viewModelScope.launch {
    workspaceStore.closeSolutionOptimistic(id)
}
fun openSession(id: String) = viewModelScope.launch {
    workspaceStore.openSessionOptimistic(id)
}
fun closeSession(id: String) = viewModelScope.launch {
    workspaceStore.closeSessionOptimistic(id)
}
```

KEEP existing `deleteSolution(id)` (calls `solutions.delete`) and ADD a new `deleteSession(id)` that calls `solution_agent.delete_session`. Find the existing closeSession that calls the old (delete-semantics) RPC and update its target tool name.

- [ ] **Step 3: Test optimistic close + rollback.**

In `WorkspaceStoreTest.kt`:
```kotlin
@Test
fun close_solution_optimistic_removes_immediately_and_keeps_on_success() = runTest {
    val fake = FakeWorkspaceClient(
        snapshotResult = WorkspaceSnapshotVM(seq = 5, solutions = listOf(OpenSolutionVM("s1","a",0,emptyList()))),
        closeSolutionSeq = 6,
    )
    val store = WorkspaceStore(client = fake, scope = backgroundScope)
    store.refresh(); advanceUntilIdle()

    store.closeSolutionOptimistic("s1")
    advanceUntilIdle()

    val state = store.state.value as WorkspaceUiState.Loaded
    assertEquals(0, state.snapshot.solutions.size, "solution removed optimistically")
    assertEquals(5L, state.snapshot.seq, "seq unchanged — delta will advance it")
}

@Test
fun close_solution_optimistic_rolls_back_on_failure() = runTest {
    val fake = FakeWorkspaceClient(
        snapshotResult = WorkspaceSnapshotVM(seq = 5, solutions = listOf(OpenSolutionVM("s1","a",0,emptyList()))),
        closeSolutionShouldThrow = true,
    )
    val store = WorkspaceStore(client = fake, scope = backgroundScope)
    store.refresh(); advanceUntilIdle()

    store.closeSolutionOptimistic("s1")
    advanceUntilIdle()

    val state = store.state.value as WorkspaceUiState.Loaded
    assertEquals(1, state.snapshot.solutions.size, "rollback restored the solution")
}
```

`FakeWorkspaceClient` needs the `closeSolutionShouldThrow` flag and the `closeSolutionSeq` return value — extend the fake.

- [ ] **Step 4: Run, expect pass.**

```bash
./gradlew :app:testDebugUnitTest --tests WorkspaceStoreTest
```

- [ ] **Step 5: Commit.**

```bash
git commit -m "feat(app): optimistic UI for workspace lifecycle (close/open with rollback)"
```

---

## Phase D — `WorkspaceScreen` UI

### Task D1: Sectioned `LazyColumn` skeleton

**Files:**
- Create: `app/src/main/kotlin/ru/sipaha/spkremote/app/ui/workspace/WorkspaceScreen.kt`

- [ ] **Step 1: Write the skeleton.**

```kotlin
package ru.sipaha.spkremote.app.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.stickyHeader
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.OpenSolutionVM
import ru.sipaha.spkremote.app.vm.WorkspaceUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WorkspaceScreen(
    viewModel: MainViewModel,
    onOpenSession: (sessionId: String) -> Unit,
    onOpenProjects: (solutionId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPicker: () -> Unit,
    onCreateNewSolution: () -> Unit,
    onCreateNewSessionFor: (solutionId: String) -> Unit,
) {
    val state by viewModel.workspaceState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspace") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExpandableFab(
                onCreateNew = onCreateNewSolution,
                onOpenPicker = onOpenPicker,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                WorkspaceUiState.Loading -> LoadingState()
                is WorkspaceUiState.Error -> ErrorState(s.message)
                is WorkspaceUiState.Loaded -> {
                    if (s.snapshot.solutions.isEmpty()) {
                        EmptyState(onOpenPicker = onOpenPicker, onCreateNew = onCreateNewSolution)
                    } else {
                        if (s.stale) StaleProgressBar()
                        WorkspaceList(
                            solutions = s.snapshot.solutions,
                            onOpenSession = onOpenSession,
                            onOpenProjects = onOpenProjects,
                            onCloseSolution = { id -> viewModel.closeSolution(id) },
                            onDeleteSolution = { id -> viewModel.deleteSolution(id) },
                            onCloseSession = { id -> viewModel.closeSession(id) },
                            onDeleteSession = { id -> viewModel.deleteSession(id) },
                            onCreateNewSessionFor = onCreateNewSessionFor,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceList(
    solutions: List<OpenSolutionVM>,
    onOpenSession: (String) -> Unit,
    onOpenProjects: (String) -> Unit,
    onCloseSolution: (String) -> Unit,
    onDeleteSolution: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCreateNewSessionFor: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        solutions.forEach { sol ->
            stickyHeader(key = "header-${sol.id}") {
                SolutionHeader(
                    sol = sol,
                    onOpenProjects = { onOpenProjects(sol.id) },
                    onCloseSolution = { onCloseSolution(sol.id) },
                    onDeleteSolution = { onDeleteSolution(sol.id) },
                )
            }
            items(sol.sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    onClick = { onOpenSession(session.id) },
                    onCloseConsole = { onCloseSession(session.id) },
                    onDeleteSession = { onDeleteSession(session.id) },
                )
            }
            item(key = "new-${sol.id}") {
                NewConsoleRow(onClick = { onCreateNewSessionFor(sol.id) })
            }
            item { HorizontalDivider() }
        }
    }
}

// (SolutionHeader, SessionRow, NewConsoleRow, ExpandableFab, EmptyState, ErrorState,
//  LoadingState, StaleProgressBar — implemented in Task D2.)
```

NOTE: `viewModel.workspaceState` is the public flow exposed from `MainViewModel`. Add it now:
```kotlin
// MainViewModel.kt
val workspaceState: StateFlow<WorkspaceUiState> = workspaceStore.state
```

- [ ] **Step 2: Compile only — UI is incomplete, will fill in next task.**

Wrap the missing composables with `TODO()` stubs that have the right signatures so the file compiles.

- [ ] **Step 3: Compile.**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 4: Commit.**

```bash
git commit -m "feat(app): WorkspaceScreen skeleton with sectioned LazyColumn"
```

---

### Task D2: Fill in section header, row, fab

(Detailed Compose code for each composable. Same file, fills in the TODO stubs from D1.)

- [ ] **Step 1: Implement SolutionHeader.**

```kotlin
@Composable
private fun SolutionHeader(
    sol: OpenSolutionVM,
    onOpenProjects: () -> Unit,
    onCloseSolution: () -> Unit,
    onDeleteSolution: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sol.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${sol.memberCount} ${if (sol.memberCount == 1) "member" else "members"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Solution menu")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Projects") },
                    onClick = { menuExpanded = false; onOpenProjects() },
                )
                DropdownMenuItem(
                    text = { Text("Close solution") },
                    onClick = { menuExpanded = false; confirmClose = true },
                )
                DropdownMenuItem(
                    text = { Text("Delete solution") },
                    onClick = { menuExpanded = false; confirmDelete = true },
                )
            }
        }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close solution?") },
            text = { Text("Terminate the agents and terminals for \"${sol.name}\". Session conversations are preserved.") },
            confirmButton = {
                TextButton(onClick = { confirmClose = false; onCloseSolution() }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text("Cancel") }
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete solution?") },
            text = { Text("Delete \"${sol.name}\" and remove all its session conversations from the computer. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDeleteSolution() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
```

- [ ] **Step 2: Implement SessionRow with `⋮` overflow.**

```kotlin
@Composable
private fun SessionRow(
    session: OpenSessionVM,
    onClick: () -> Unit,
    onCloseConsole: () -> Unit,
    onDeleteSession: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(session.title.ifBlank { "(untitled session)" }, style = MaterialTheme.typography.titleSmall)
            Row {
                StatePill(state = session.state.displayState(), raw = "")
                Text(
                    text = " · ${relativeTime(session.lastActivityAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Session menu")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Close console") },
                onClick = { menuExpanded = false; onCloseConsole() },
            )
            DropdownMenuItem(
                text = { Text("Delete session") },
                onClick = { menuExpanded = false; confirmDelete = true },
            )
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete session?") },
            text = { Text("Permanently delete \"${session.title.ifBlank { "this session" }}\" and its conversation. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmDelete = false; onDeleteSession() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}
```

- [ ] **Step 3: Implement NewConsoleRow + ExpandableFab + EmptyState + StaleProgressBar.**

```kotlin
@Composable
private fun NewConsoleRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text("New console", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ExpandableFab(onCreateNew: () -> Unit, onOpenPicker: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End) {
        if (expanded) {
            ExtendedFloatingActionButton(
                onClick = { expanded = false; onCreateNew() },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("New solution") },
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ExtendedFloatingActionButton(
                onClick = { expanded = false; onOpenPicker() },
                icon = { Icon(Icons.Filled.FolderOpen, null) },
                text = { Text("Open closed solution…") },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        FloatingActionButton(onClick = { expanded = !expanded }) {
            Icon(if (expanded) Icons.Filled.Close else Icons.Filled.Add, contentDescription = "Expand actions")
        }
    }
}

@Composable
private fun EmptyState(onOpenPicker: () -> Unit, onCreateNew: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No open solutions", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Tap + to create a new one or open an existing one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun LoadingState() = Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
@Composable private fun ErrorState(msg: String) = Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) { Text(msg) }
@Composable private fun StaleProgressBar() = LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
```

NOTE: `relativeTime(epochMillis: Long): String` — reuse from `SolutionDetailScreen.kt` (existing helper). Either copy it into this file or extract to a shared util file `app/ui/util/RelativeTime.kt` and call from both.

`StatePill` — existing composable in `app/ui/solutions/StatePill.kt`. Import and reuse.

- [ ] **Step 4: Compile + sanity.**

```bash
./gradlew :app:compileDebugKotlin
```

- [ ] **Step 5: Commit.**

```bash
git commit -m "feat(app): WorkspaceScreen composables (section header, session row, FAB, empty state)"
```

---

### Task D3: Roborazzi goldens for `WorkspaceScreen`

**Files:**
- Create: `app/src/test/kotlin/ru/sipaha/spkremote/app/ui/workspace/WorkspaceScreenSnapshotTest.kt`

- [ ] **Step 1: Write a test that renders the screen with a populated snapshot and captures a golden.**

```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")
class WorkspaceScreenSnapshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun populated_two_solutions_with_sessions() {
        val snapshot = WorkspaceSnapshotVM(
            seq = 1,
            solutions = listOf(
                OpenSolutionVM("voxelcraft", "voxelcraft", 3, sessions = listOf(
                    OpenSessionVM("se1", "Refactor renderer", SessionStateDto.Running(startedAtMs = 0L), 1000L, 2400L, 200000L),
                    OpenSessionVM("se2", "Sprite editor", SessionStateDto.Idle, 3_600_000L, null, null),
                )),
                OpenSolutionVM("spk", "SPK Solutions", 5, sessions = listOf(
                    OpenSessionVM("se3", "Mobile redesign", SessionStateDto.Errored("oops"), 300_000L, null, null),
                )),
            ),
        )
        composeRule.setContent {
            // Render the body of WorkspaceScreen by inlining the LazyColumn (or
            // expose `WorkspaceListContent` as `internal` so tests can render
            // without spinning up a fake MainViewModel).
            WorkspaceListContent(snapshot.solutions, onAny = {})
        }
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/roborazzi/WorkspaceScreen_populated_two_solutions.png"
        )
    }

    @Test
    fun empty_state() {
        composeRule.setContent {
            EmptyState(onOpenPicker = {}, onCreateNew = {})
        }
        composeRule.onRoot().captureRoboImage(
            "src/test/snapshots/roborazzi/WorkspaceScreen_empty.png"
        )
    }
}
```

NOTE: To test the screen without the full ViewModel, extract a public `WorkspaceListContent` (or `internal`) composable that takes the data directly. The screen's `WorkspaceScreen(viewModel: ...)` composable then becomes a thin wrapper that collects state and forwards to `WorkspaceListContent`.

- [ ] **Step 2: Record goldens.**

```bash
./gradlew :app:testDebugUnitTest --tests WorkspaceScreenSnapshotTest -Proborazzi.test.record=true
```

- [ ] **Step 3: Read each PNG to confirm rendering.**

Use `Read` on each generated PNG to verify the screen looks right. If something's off (e.g. a section header missing, sessions blank), fix the composable before continuing.

- [ ] **Step 4: Re-run in compare mode.**

```bash
./gradlew :app:testDebugUnitTest --tests WorkspaceScreenSnapshotTest -Proborazzi.test.compare=true
```

- [ ] **Step 5: Commit.**

```bash
git add app/src/test/kotlin/ru/sipaha/spkremote/app/ui/workspace/WorkspaceScreenSnapshotTest.kt \
        app/src/test/snapshots/roborazzi/WorkspaceScreen_*.png
git commit -m "test(app): Roborazzi goldens for WorkspaceScreen populated + empty states"
```

---

## Phase E — `ClosedSolutionsPickerSheet`

### Task E1: Bottom sheet composable + interaction

**Files:**
- Create: `app/src/main/kotlin/ru/sipaha/spkremote/app/ui/workspace/ClosedSolutionsPickerSheet.kt`

- [ ] **Step 1: Implement the sheet.**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosedSolutionsPickerSheet(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val closed by viewModel.closedSolutions.collectAsState()

    LaunchedEffect(Unit) { viewModel.refreshClosedSolutions() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Open a closed solution", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            when (val c = closed) {
                is UiData.Loading -> Box(Modifier.fillMaxWidth().height(80.dp), Alignment.Center) { CircularProgressIndicator() }
                is UiData.Error -> Text("Couldn't load: ${c.message}")
                is UiData.Loaded -> if (c.value.isEmpty()) {
                    Text("No closed solutions on this server.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(c.value, key = { it.id }) { row ->
                            ClosedSolutionRowComposable(
                                row = row,
                                onOpen = { viewModel.openSolution(row.id); onDismiss() },
                                onDelete = { viewModel.deleteSolution(row.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClosedSolutionRowComposable(
    row: ClosedSolutionRow,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = row.lastOpenedAt?.let { "last opened $it" } ?: "${row.memberCount} member(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpen) { Text("Open") }
        IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Row menu")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { menuExpanded = false; onDelete() },
            )
        }
    }
}
```

NOTE: `viewModel.closedSolutions: StateFlow<UiData<List<ClosedSolutionRow>>>` — expose from `MainViewModel`:
```kotlin
val closedSolutions: StateFlow<UiData<List<ClosedSolutionRow>>> = workspaceStore.closedSolutions

fun refreshClosedSolutions() = viewModelScope.launch {
    workspaceStore.refreshClosedSolutions()
}
```

- [ ] **Step 2: Wire `onOpenPicker` from `WorkspaceScreen` to show this sheet.**

In `WorkspaceScreen.kt`, replace the `onOpenPicker: () -> Unit` plumbing with an internal state:
```kotlin
var showPicker by rememberSaveable { mutableStateOf(false) }
// ... pass `{ showPicker = true }` instead of `onOpenPicker`.
// ... at the bottom:
if (showPicker) {
    ClosedSolutionsPickerSheet(viewModel = viewModel, onDismiss = { showPicker = false })
}
```

- [ ] **Step 3: Roborazzi goldens for the sheet.**

Add to `WorkspaceScreenSnapshotTest.kt`:
```kotlin
@Test
fun picker_sheet_populated() {
    composeRule.setContent {
        ClosedSolutionsPickerSheetContent(
            rows = listOf(
                ClosedSolutionRow("c1", "ML experiments", 4, "2 days ago"),
                ClosedSolutionRow("c2", "Old prototype", 1, "6 months ago"),
            ),
            onOpen = {}, onDelete = {},
        )
    }
    composeRule.onRoot().captureRoboImage("src/test/snapshots/roborazzi/Picker_populated.png")
}
```

(Extract a `ClosedSolutionsPickerSheetContent` composable so the sheet body can be rendered without a ModalBottomSheet host.)

- [ ] **Step 4: Build + tests + Read PNGs.**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest -Proborazzi.test.record=true --tests WorkspaceScreenSnapshotTest
```

Read PNGs to confirm.

- [ ] **Step 5: Commit.**

```bash
git add app/src
git commit -m "feat(app): ClosedSolutionsPickerSheet + goldens"
```

---

## Phase F — Nav graph + saved-route migration

### Task F1: New routes + migration

**Files:**
- Modify: `app/src/main/kotlin/ru/sipaha/spkremote/app/ui/nav/AppNavGraph.kt`
- Modify: `app/src/main/kotlin/ru/sipaha/spkremote/app/data/NavStateRepository.kt`

- [ ] **Step 1: Add route migration to `NavStateRepository.loadSavedRoute()`.**

In `loadSavedRoute()`:
```kotlin
fun loadSavedRoute(): String? {
    val raw = prefs.getString(KEY_ROUTE, null) ?: return null
    // Migrate Plan 1 → Plan 2 routes.
    return when {
        raw.matches(Regex("""^solutions/[^/]+/sessions/(.+)$""")) -> {
            val sessionId = Regex("""^solutions/[^/]+/sessions/(.+)$""").find(raw)!!.groupValues[1]
            saveRoute("workspace/sessions/$sessionId")
            "workspace/sessions/$sessionId"
        }
        raw == "solutions" || raw.matches(Regex("""^solutions/[^/]+$""")) -> {
            saveRoute("workspace"); "workspace"
        }
        raw.matches(Regex("""^solutions/([^/]+)/projects$""")) -> {
            val solId = Regex("""^solutions/([^/]+)/projects$""").find(raw)!!.groupValues[1]
            saveRoute("workspace/solutions/$solId/projects")
            "workspace/solutions/$solId/projects"
        }
        else -> raw
    }
}
```

- [ ] **Step 2: Replace `solutions` routes with `workspace` in the nav graph.**

In `AppNavGraph.kt`:
- Remove `composable("solutions") { SolutionsListScreen(...) }`.
- Remove `composable("solutions/{solutionId}") { SolutionDetailScreen(...) }`.
- Add:
```kotlin
composable("workspace") {
    WorkspaceScreen(
        viewModel = viewModel,
        onOpenSession = { sessionId ->
            navController.navigate("workspace/sessions/$sessionId")
        },
        onOpenProjects = { solutionId ->
            navController.navigate("workspace/solutions/$solutionId/projects")
        },
        onOpenSettings = { navController.navigate("settings") },
        onOpenPicker = {},               // internal — handled inside the screen
        onCreateNewSolution = {},        // wire to existing CreateSolutionDialog flow
        onCreateNewSessionFor = { solutionId ->
            // wire to existing NewSessionDialog flow scoped to solutionId
        },
    )
}
composable(
    route = "workspace/sessions/{sessionId}",
    arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
) { entry ->
    val sessionId = entry.arguments?.getString("sessionId").orEmpty()
    SessionDetailScreen(
        viewModel = viewModel,
        sessionId = sessionId,
        onBack = { navController.popBackStack() },
        onOpenSibling = { siblingId ->
            navController.navigate("workspace/sessions/$siblingId") { launchSingleTop = true }
        },
    )
}
composable(
    route = "workspace/solutions/{solutionId}/projects",
    arguments = listOf(navArgument("solutionId") { type = NavType.StringType }),
) { entry ->
    val solutionId = entry.arguments?.getString("solutionId").orEmpty()
    SolutionProjectsScreen(
        viewModel = viewModel,
        solutionId = solutionId,
        onBack = { navController.popBackStack() },
    )
}
```

- [ ] **Step 3: Update `UiState.Connected` redirect to land on `workspace` (not `solutions`).**

In the `LaunchedEffect(uiState)` block of `AppNavGraph.kt`, every reference to the string `"solutions"` as a target route becomes `"workspace"`. The `Disconnected` pop-target stays as-is (`pairing` or `servers`).

- [ ] **Step 4: Compile + smoke.**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

- [ ] **Step 5: Commit.**

```bash
git commit -m "feat(app): swap nav graph to workspace.* routes + migrate saved routes"
```

---

## Phase G — Remove old screens

### Task G1: Delete `SolutionsListScreen`, `SolutionDetailScreen`, `SolutionStore`

**Files:**
- Delete: `app/src/main/kotlin/ru/sipaha/spkremote/app/ui/solutions/SolutionsListScreen.kt`
- Delete: `app/src/main/kotlin/ru/sipaha/spkremote/app/ui/solutions/SolutionDetailScreen.kt`
- Delete: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/SolutionStore.kt`
- Modify: `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/MainViewModel.kt` — remove `solutionStore` field + uses.
- Modify: anywhere that imported these files.

- [ ] **Step 1: Find all references.**

```bash
grep -rn "SolutionsListScreen\|SolutionDetailScreen\|SolutionStore" app/src --include='*.kt' | grep -v "SolutionStore"
```

(Note: `SolutionStore` lives in DESKTOP code too; we're only removing the MOBILE `SolutionStore`. Be precise.)

- [ ] **Step 2: Delete the three files.**

```bash
git rm app/src/main/kotlin/ru/sipaha/spkremote/app/ui/solutions/SolutionsListScreen.kt \
       app/src/main/kotlin/ru/sipaha/spkremote/app/ui/solutions/SolutionDetailScreen.kt \
       app/src/main/kotlin/ru/sipaha/spkremote/app/vm/SolutionStore.kt
```

- [ ] **Step 3: Update `MainViewModel` to remove `solutionStore` field + delegate `solutions` flow to `workspaceStore.state.map { ... }`.**

If anything else (legacy code) still references `viewModel.solutions: StateFlow<UiData<List<SolutionSummary>>>`, replace with derived state from `workspaceStore.state`. Or just drop the field — newly written screens use `workspaceState` directly.

- [ ] **Step 4: Compile + test.**

```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
```

Expected: PASS. If compile errors point to consumers of the deleted types, fix each.

- [ ] **Step 5: Commit.**

```bash
git commit -m "refactor(app): remove SolutionsListScreen + SolutionDetailScreen + SolutionStore (folded into WorkspaceScreen)"
```

---

## Phase H — Final acceptance

### Task H1: Run full test suite + capture acceptance goldens

- [ ] **Step 1: Full JVM test suite.**

```bash
./gradlew test
```

Expected: PASS (all module tests).

- [ ] **Step 2: Roborazzi compare mode across all goldens.**

```bash
./gradlew :app:testDebugUnitTest -Proborazzi.test.compare=true
```

Expected: PASS, no diffs.

- [ ] **Step 3: Manual emulator smoke (optional).**

If `~/Android/Sdk/emulator/emulator` is installed (otherwise `sdkmanager "emulator" "system-images;android-36;google_apis;x86_64"`), boot a headless emulator, install the debug APK, drive a basic flow:

```bash
./gradlew :app:installDebug
adb shell am start -n ru.sipaha.spkremote.app/.MainActivity
# Manually navigate to pairing → workspace → tap a solution → back.
adb exec-out screencap -p > /tmp/manual-smoke.png
```

`Read /tmp/manual-smoke.png` to verify the real app rendered the workspace screen.

If the emulator setup is too involved, skip this step — the Roborazzi goldens are the contract.

- [ ] **Step 4: Commit any final golden adjustments + tag.**

```bash
git tag -a workspace-mobile-done -m "Plan 2 (mobile) complete; ready for integration smoke against the Plan-1 desktop"
```

---

## Self-review notes

**Spec coverage:**
- §2.1 strong consistency — C3 implements seq protocol + gap detection. C5 wires resync triggers.
- §2.2 metrics eventual consistency — C4 implements `onSessionMetricsChanged` without seq advance.
- §3.1 renames — B1, B2.
- §3.3 RPCs — C5 (`WorkspaceClientImpl`).
- §3.4 deltas — C3 + C5 dispatcher.
- §4 mobile data layer — C1-C6.
- §5 mobile UI — D1-D2, E1.
- §5.3 nav graph + saved-route migration — F1.
- §5.4 chat back-navigation — falls out of the nav-graph swap; no separate task needed.
- §7.1 test infra — A1, A2.
- §7.3 store-level tests — C2-C6.
- §7.4 UI-level + Roborazzi — D3, E1 (goldens).

**Placeholder scan:**
- Each task that mentions "look at existing code for pattern X" is an instruction to the engineer to MATCH a real pattern, not a placeholder. The actual code snippet for each step is concrete.
- `TODO()` stubs in D1 are tracked back to D2 fills.

**Type consistency:**
- `WorkspaceSnapshotVM` / `OpenSolutionVM` / `OpenSessionVM` / `ClosedSolutionRow` introduced in C1, used consistently through D-E.
- `WorkspaceClient` interface in C3, implemented in C5.
- DTO names match Plan 1's wire: `WorkspaceSnapshot`, `WorkspaceSolutionOpenedPayload`, etc.

**Gaps deliberately left:**
- Roborazzi golden recording requires `-Proborazzi.test.record=true`. Engineer must remember to re-record after intentional UI changes; otherwise compare-mode CI will fail. Note this in the team's README if convenient.
- The optimistic-UI rollback in C6 only fires on RPC ack failure, not on later delta-mismatch detection. For Plan 2 this is sufficient; a delta correction path can be added later if user reports diverge from expected.
