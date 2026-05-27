# Unified Open-Workspace Screen (mobile) — design

Date: 2026-05-27
Scope: `spk-editor-mobile` (Kotlin/Android) + `spk-editor` (Rust desktop) — wire-protocol changes touch both.

---

## 1. Problem & goal

Mobile today has a 2-screen flow: solutions list → solution detail (sessions list). The screens show ALL solutions and ALL sessions for a solution, with no notion of "what's currently active on the desktop".

We want a single mobile screen that mirrors the desktop's open-workspace concept:

- Only **open** solutions (those whose desktop window is open).
- Inside each, only **open** sessions (those present in the desktop's tab strip).
- Full live sync with the desktop — opening/closing a solution or session on either side reflects in the other.
- Ability to create new solutions and new sessions, and to open existing closed solutions via a picker.
- Ability to close (kill agent processes + terminals, keep transcripts) and delete (full removal) solutions and sessions.

**Non-goals:** redesigning the chat / session-detail screen, exposing terminals on mobile, changing the pairing/server-switch flow.

---

## 2. Consistency contract

The mobile snapshot is a derived view of server state. Two zones of consistency, by design:

### 2.1 Strong consistency (sequenced)

Structural fields — presence of a solution/session, `open` state, `title`, structured `state` — are kept consistent via a sequence-number protocol:

- The desktop runs a single `WorkspaceEventCoordinator` with an atomic `seq: u64`. Every mutation that produces a workspace event increments `seq` BEFORE the notification is emitted and BEFORE the mutation-RPC returns. The increment + state-write + emit happens under one replication lock so a snapshot read can never split the increment from the state change.
- The bulk RPC `workspace.snapshot` returns `{ seq, ... }` — the `seq` value at the moment the snapshot was sealed.
- Every sequenced notification carries `seq: u64`.
- The mobile `WorkspaceStore` tracks `lastAppliedSeq`. Delta-handling rule:

| `notif.seq` vs `lastAppliedSeq` | Action |
|---|---|
| `≤ lastAppliedSeq` | Drop (duplicate / replay). |
| `== lastAppliedSeq + 1` | Apply, advance. |
| `> lastAppliedSeq + 1` | Gap detected — trigger `resync()`, buffer the notification. |

- Between sending `workspace.snapshot` and receiving its response, any incoming sequenced notification is buffered. On the snapshot response: notifications with `seq > snapshot.seq` are applied in order; the rest are dropped.

- **Mandatory resync points** (full bulk-refetch):
  1. Each successful connection (the `RemoteClient` is single-shot — every reconnect ⇒ new bulk).
  2. Gap detection (see table).
  3. App returning to foreground (`ForegroundEventBus` resume event).
  4. User pull-to-refresh.

- **Optimistic UI, NOT optimistic seq.** When the user invokes a lifecycle action, the effect is reflected in the locally-rendered snapshot immediately, but `lastAppliedSeq` is NOT advanced on ack. Reason: the ack may return `seq=N` while an unrelated mutation produced a delta `seq=N-1` that hasn't arrived yet — if we naïvely set `lastAppliedSeq=N`, the unrelated delta lands and is dropped as "duplicate", losing state. Correct flow:

  1. User tap → kick off RPC, mutate snapshot optimistically (e.g. remove the row), track the pending mutation in an in-memory buffer.
  2. RPC ack success → drop the buffered pending mutation; no `seq` change.
  3. Matching delta arrives later → applied idempotently (re-removing an already-removed row is a no-op); `seq` advances normally via the delta.
  4. RPC ack failure → roll back the optimistic mutation, surface error.

  Idempotency requirement: every delta-apply path must tolerate the target already being in the post-state (solution already absent, session already in the requested state, etc.).

- **Reset-during-write guard.** `WorkspaceStore.reset()` acquires the same `snapshotMutex` that delta- and bulk-appliers hold. This prevents in-flight applies from resurrecting state post-reset (the yellow-verdict issue from the 2026-05-18 audit on `SessionStore.reset()`).

### 2.2 Eventual consistency (chatty metrics)

Three fields are deliberately outside the sequenced protocol:

- `last_activity_at`
- `total_tokens`
- `max_tokens`

They are pushed via a separate non-sequenced notification `workspace.session_metrics_changed { session_id, last_activity_at?, total_tokens?, max_tokens? }`. Server throttles to one emit per session per ~2 seconds, sends only on real change. Mobile patches the matching session in the snapshot, or ignores if absent.

Contract: these fields are eventually consistent within seconds while connected, and guaranteed accurate after any bulk-refetch. A dropped metric notification does NOT trigger resync — that would amplify any small loss into a full snapshot refetch.

---

## 3. Wire protocol

Three namespaces with clean responsibilities.

### 3.1 Renames (breaking)

- `SolutionSummary.window_open` → `open`. The "window" hook leaks a desktop-only concept; the field is now a generic "is this solution currently active in the workspace".
- `agent_session_close` (current RPC — full-delete despite the name) → `solution_agent.delete_session`. The verb now matches semantics; `workspace.close_session` becomes the genuine "untab" action.

Both are breaking wire changes. The wire schema version (`wire_schema_version` in `CapabilitiesDto`) is bumped; the mobile `supportedWireSchemaVersion` constant is raised. Old mobile vs new desktop (or vice versa) lands on the existing `IncompatibleServerScreen` gate in `AppNavGraph.kt`.

### 3.2 Module ownership

- `solutions.*` — CRUD on the Solution entity: `solutions.create`, `solutions.delete`, `solutions.add_member`, `solutions.add_empty_member`, members read.
- `solution_agent.*` — CRUD on the Session entity: `solution_agent.create_session`, `solution_agent.delete_session` (renamed), transcript reads.
- `workspace.*` — **view + lifecycle** of what is currently live: snapshot, picker query, open/close mutations, sequenced delta stream.

### 3.3 New `workspace.*` RPCs

```text
workspace.snapshot
  params: {}
  result: {
    seq: u64,
    solutions: [
      {
        id: String, name: String, open: true, member_count: Int,
        sessions: [SessionSummary, ...]  // only sessions whose tab_order IS NOT NULL
      },
      ...
    ]
  }
```

```text
workspace.list_solutions
  params: { open: Option<bool> }       // None = both; true = open; false = closed
  result: [{ id, name, open, member_count, last_opened_at }]
  // Light-weight; for the closed-solutions picker.
  // Does NOT participate in the seq protocol; refetched on sheet open.
```

```text
workspace.open_solution   { solution_id: String }  -> { seq: u64 }
workspace.close_solution  { solution_id: String }  -> { seq: u64 }
workspace.open_session    { session_id:  String }  -> { seq: u64 }
workspace.close_session   { session_id:  String }  -> { seq: u64 }
```

Semantics:

- `open_solution` for already-open: no-op, returns current `seq`, no notification emitted.
- `close_solution`: server terminates the solution's agent processes AND its terminals. `tab_order` of sessions is preserved on disk. Solution flips to `open=false`. Emits one `workspace.solution_closed { seq, solution_id }`. Sessions remain in DB; they reappear when the solution is later re-opened.
- `open_solution` for a closed solution: server reopens the window, hydrates every session with non-null `tab_order` (sessions start in `Idle` state — agent is restarted on first user interaction in the chat screen). Emits `workspace.solution_opened { seq, solution: SolutionSummary, sessions: [SessionSummary, ...] }`.
- `open_session` / `close_session`: add/remove the session's `tab_order`. `close_session` does NOT delete the session — the transcript stays. To remove the session entirely the client calls `solution_agent.delete_session`.

### 3.4 New `workspace.*` notifications

Sequenced (carry `seq: u64`, participate in gap detection):

| Notification | Payload | When |
|---|---|---|
| `workspace.solution_opened` | `{ seq, solution: SolutionSummary, sessions: [SessionSummary] }` | New solution becomes open (created OR reopened from closed). Sessions are the restored set. |
| `workspace.solution_closed` | `{ seq, solution_id }` | Solution flips to `open=false`. Its sessions implicitly disappear from the snapshot. |
| `workspace.solution_deleted` | `{ seq, solution_id }` | Solution permanently deleted (from `solutions.delete`). |
| `workspace.session_opened` | `{ seq, solution_id, session: SessionSummary }` | Session added to tab strip (created OR `open_session` on an existing closed-tab one). |
| `workspace.session_closed` | `{ seq, solution_id, session_id }` | Session removed from tab strip (transcript preserved). |
| `workspace.session_deleted` | `{ seq, solution_id, session_id }` | Session permanently deleted. |
| `workspace.session_state_changed` | `{ seq, solution_id, session_id, state: SessionStateDto }` | Structured state transition (Running ↔ Idle ↔ Errored). |

Non-sequenced (independent of the protocol):

| Notification | Payload | When |
|---|---|---|
| `workspace.session_metrics_changed` | `{ session_id, last_activity_at?, total_tokens?, max_tokens? }` | Coalesced ~1 / 2s per session, only on real change. |

### 3.5 Sequencer hook from neighbouring crates

`solutions.delete` and `solution_agent.delete_session` live in their own crates but call into `WorkspaceEventCoordinator::increment_and_emit(...)` to fire the matching `workspace.*_deleted` notification. The coordinator is a global (per editor process), accessed via the same pattern as `editor_mcp::emit_notification`. This keeps CRUD ownership where it belongs while giving `workspace.*` a single source of truth for what's currently live.

### 3.6 Deprecation

After this change, the mobile no longer calls `solutions.list` or `solution_agent.list_sessions` directly — `workspace.snapshot` + `workspace.list_solutions` cover the use case. The two old tools stay on the wire for now (other consumers / `:cli` debugging) but are out-of-scope for further evolution. Removing them is a follow-up cleanup, not part of this work.

---

## 4. Mobile data layer

### 4.1 New `WorkspaceStore` (in `app/vm/`)

Replaces `SolutionStore` outright. Coexists with `SessionListStore` and `SessionDetailStore`, which are narrowed to chat-screen-only concerns.

State:

```kotlin
sealed interface WorkspaceUiState {
    object Loading : WorkspaceUiState
    data class Loaded(val snapshot: WorkspaceSnapshot, val stale: Boolean) : WorkspaceUiState
    data class Error(val message: String) : WorkspaceUiState
}

data class WorkspaceSnapshot(
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
```

Public flows:

- `state: StateFlow<WorkspaceUiState>` — the live snapshot.
- `closedSolutions: StateFlow<UiData<List<ClosedSolutionRow>>>` — picker contents, lazy-loaded.

### 4.2 Delta application

Single coroutine, single `snapshotMutex`. Pseudocode:

```kotlin
suspend fun onNotification(notif: WorkspaceNotification) = snapshotMutex.withLock {
    when (notif) {
        is Sequenced -> applySequenced(notif)
        is MetricsChanged -> patchMetrics(notif)
    }
}

private fun applySequenced(notif: Sequenced) {
    val cur = state.value as? Loaded ?: run { pending += notif; return }
    when {
        notif.seq <= cur.snapshot.seq -> return                 // duplicate
        notif.seq == cur.snapshot.seq + 1 -> {
            val next = mutate(cur.snapshot, notif)
            _state.value = Loaded(next, stale = false)
        }
        else -> { pending += notif; launchResync() }            // gap
    }
}
```

The `pending` buffer absorbs notifications that arrive (a) before the first snapshot, (b) during a resync. After each resync, `pending` is replayed with the new `lastAppliedSeq` as the filter. The buffer is bounded (e.g. 256 entries) — overflow signals a pathological gap and forces an immediate resync rather than growing unbounded.

### 4.3 Resync triggers

- `ConnectionLifecycle.onConnected()` (already exists; add hook).
- Gap detection (`applySequenced` above).
- `ForegroundEventBus.onResume()` (exists for other purposes; add hook).
- `MainViewModel.refreshWorkspace()` (wired to pull-to-refresh).

`stale = true` during resync — the UI keeps rendering the previous snapshot with a top progress bar instead of flashing a spinner.

### 4.4 Lifecycle wrappers on `MainViewModel`

```kotlin
fun openSolution(id: String)
fun closeSolution(id: String)
fun deleteSolution(id: String)          // -> solutions.delete
fun openSession(id: String)
fun closeSession(id: String)
fun deleteSession(id: String)           // -> solution_agent.delete_session
```

Each lifecycle RPC ack advances `lastAppliedSeq` optimistically — the matching delta arriving later is a no-op.

### 4.5 Caching for cold launch

Persist the last applied snapshot to disk (analogue of existing `ListCacheRepository`). On cold start, show the cached snapshot as `stale = true` immediately; the network roundtrip replaces it. Stale-cache is **never** used to drive lifecycle RPCs — those wait for at least one fresh snapshot.

---

## 5. Mobile UI

### 5.1 New `WorkspaceScreen` (replaces `SolutionsListScreen`)

Single `LazyColumn`, sectioned per open solution.

```
┌─────────────────────────────────────────────┐
│  ●  voxelcraft              3 members   ⋮  │ ← sticky header
├─────────────────────────────────────────────┤
│  🟢 Refactor renderer       Running   2m ago│
│  ⚪ Sprite editor           Idle      1h ago│
│  ⊕  New console                            │ ← inline create row
├─────────────────────────────────────────────┤
│  ●  SPK Solutions           5 members   ⋮  │
├─────────────────────────────────────────────┤
│  🟡 Mobile redesign         Errored   5m ago│
│  🟢 Wire protocol           Running   30s ag│
│  ⊕  New console                            │
└─────────────────────────────────────────────┘

                                    [+ ▾ FAB]
```

**Solution header (sticky).** Name, member count, overflow `⋮`:
- `Projects` — opens existing `SolutionProjectsScreen` (kept).
- `Close solution` — confirm dialog ("Terminate agents & terminals. Conversations are kept."), then `workspace.close_solution`.
- `Delete solution` — destructive confirm, then `solutions.delete`.

**Session row.** State-pill, title, relative time. Short tap → chat. Trailing overflow `⋮` icon opens a small menu: `Close console` (`workspace.close_session`) / `Delete session` (`solution_agent.delete_session`, destructive confirm). No long-tap gesture (less discoverable, and on Compose lists long-tap conflicts with scroll initiation).

**Inline `⊕ New console` row** at the end of each section — opens the existing `NewSessionDialog`, scoped to that solution.

**FAB.** Compose `SmallFloatingActionButton` with expanding menu:
- `New solution` → existing `CreateSolutionDialog`.
- `Open closed solution…` → new `ClosedSolutionsPickerSheet`.

**`ClosedSolutionsPickerSheet`** (Material 3 `ModalBottomSheet`). On open, fires `workspace.list_solutions(open=false)`. List rows show name, `last_opened_at` relative, `[Open]` button → `workspace.open_solution` → sheet dismisses; the solution shows up in the snapshot via the resulting `solution_opened` delta. Row overflow `⋮` also offers `Delete`.

**Stale banner.** While `state.stale == true`, render a thin `LinearProgressIndicator` immediately under the top app bar.

**Empty state.** Zero open solutions: centered text "No open solutions. Tap + to create one or open an existing one."

### 5.2 Removed screens

- `SolutionsListScreen` — replaced by `WorkspaceScreen`.
- `SolutionDetailScreen` — content is folded into a `WorkspaceScreen` section. The Projects entry-point moves to the section overflow.

### 5.3 Nav graph

Before: `pairing` | `connecting` | `servers` | `solutions` | `solutions/{id}` | `solutions/{id}/sessions/{sessionId}` | `solutions/{id}/projects` | `settings` | `crash-logs`.

After: `pairing` | `connecting` | `servers` | `workspace` | `workspace/sessions/{sessionId}` | `workspace/solutions/{id}/projects` | `settings` | `crash-logs`.

`{solutionId}` is no longer part of the session route — `SessionStore` already resolves the parent solution from the session id.

**Saved-route migration** in `NavStateRepository`:
- `solutions/x/sessions/y` → `workspace/sessions/y`.
- `solutions/x` → `workspace`.
- `solutions/x/projects` → `workspace/solutions/x/projects`.

Migration runs once at cold start in `loadSavedRoute()`, then the rewritten value is re-saved.

### 5.4 Chat back-navigation

Back from `SessionDetailScreen` lands on `WorkspaceScreen` (root). No intermediate solution-detail stop. Acceptable: the user came from the workspace, sees the workspace.

---

## 6. Desktop implementation

### 6.1 New module `crates/workspace_events` (or sub-module in `crates/solutions`)

Houses:
- `WorkspaceEventCoordinator` — `AtomicU64` sequencer + `Mutex`-guarded emit. Exposed as a global, akin to `EventSourceCoordinator`.
- The MCP tool implementations for `workspace.*` RPCs.

The tools delegate state reads/writes to `SolutionStore` and `SolutionAgentStore` (already exist), then call `WorkspaceEventCoordinator::increment_and_emit(...)` under the same lock the state mutation runs in.

### 6.2 Sequencer + replication lock

```rust
impl WorkspaceEventCoordinator {
    fn increment_and_emit(&self, kind: WorkspaceEvent, cx: &mut App) {
        // Caller already holds the relevant state-write lock(s).
        let seq = self.seq.fetch_add(1, Ordering::SeqCst) + 1;
        let payload = kind.into_payload(seq);
        editor_mcp::emit_notification(cx, kind.wire_name(), payload);
    }

    fn snapshot(&self, cx: &App) -> (u64, WorkspaceSnapshot) {
        let _read = self.replication_lock.read();  // blocks writers
        let seq = self.seq.load(Ordering::SeqCst);
        let snap = build_snapshot(cx);
        (seq, snap)
    }
}
```

The mutual exclusion between snapshot read and mutation increment-and-emit is what guarantees the snapshot's `seq` truly reflects the state included in it.

### 6.3 Hook points

- `SolutionStore::activate(id)` / `deactivate(id)` / `delete(id)` — wrap with sequencer calls.
- `SolutionAgentStore::create_session` / `delete_session` / `set_tab_order` — wrap.
- `Session::set_state` (Running/Idle/Errored transitions) — wrap.
- Token-usage / activity updates → emit non-sequenced `session_metrics_changed`, throttled per-session.

### 6.4 `close_solution` implementation

1. Iterate solution's open agent processes → terminate (`AcpThread::cancel` or equivalent).
2. Iterate solution's terminals (in `terminals` crate, registered with the workspace) → kill.
3. `SolutionStore::deactivate(id)` (sets `open=false`; `tab_order` of sessions preserved).
4. `increment_and_emit(SolutionClosed { id })`.

### 6.5 `open_solution` for closed

1. `SolutionStore::activate(id)` — opens the window.
2. `SolutionAgentStore::hydrate_all_for_solution(id)` (exists today).
3. Build `Vec<SessionSummary>` over sessions with `tab_order IS NOT NULL`.
4. `increment_and_emit(SolutionOpened { solution_summary, sessions })`.

Agent processes are NOT auto-started; sessions land in `Idle` state and resume on first user interaction in the chat screen (matches current desktop UX for "reopened tab").

---

## 7. Testing strategy

### 7.1 Test infrastructure (prerequisite — first PR)

Add to `:app/build.gradle.kts`:

- `androidx.compose.ui:ui-test-junit4`
- `androidx.compose.ui:ui-test-manifest`
- `org.robolectric:robolectric`
- `io.github.takahirom.roborazzi:roborazzi-compose`
- `io.github.takahirom.roborazzi:roborazzi-junit-rule`

Robolectric config (`@Config(sdk = [33])`) for stable rendering. Roborazzi golden directory `app/src/test/snapshots/` (committed). One sanity test up front (`WorkspaceScreen_emptyState_matchesGolden`) to prove the rig works before feature tests are added.

### 7.2 Desktop (Rust)

Unit tests on `WorkspaceEventCoordinator`:
- `seq` monotonicity under concurrent mutations.
- Increment-then-emit atomicity (no notification before state write).
- `snapshot` returns `seq` consistent with included state.

Tool-level tests:
- `open_solution` for already-open: no-op, no seq increment, no notification.
- `close_solution`: agents+terminals terminated (mocked), exactly one notification emitted, sessions' `tab_order` preserved in DB.
- `open_solution` for closed: sessions restored with their `tab_order`, notification carries the session array.
- `solutions.delete` (existing tool, modified) emits `workspace.solution_deleted`.
- `solution_agent.delete_session` (renamed) emits `workspace.session_deleted`.

E2E (in `solution_agent/tests/`): two MCP clients subscribed; after a series of mutations, both observe identical `seq` and snapshot.

Race test: snapshot in-flight while mutation lands → client merges correctly via the seq protocol.

### 7.3 Mobile (Kotlin) — store-level

JUnit + `kotlinx-coroutines-test` for `WorkspaceStore`:
- Cold bulk → `Loaded` with correct `seq`.
- In-order deltas `seq+1, seq+2, ...` → each applies.
- `seq ≤ current` → dropped (idempotency).
- `seq > current+1` → triggers `resync()`, delta buffered, applied after resync.
- Deltas arriving BEFORE snapshot response → buffered, then applied filtered by `seq > snapshot.seq`.
- `session_metrics_changed` does NOT trigger gap detection on loss.
- `session_metrics_changed` for unknown session id → silently ignored.
- Lifecycle-RPC ack → `lastAppliedSeq` advances optimistically; matching delta is a no-op.
- `reset()` under `snapshotMutex` → no in-flight resurrection.
- `ConnectionLifecycle.onConnected` → forces resync.
- `ForegroundEventBus` resume → forces resync.
- `NavStateRepository` migration for legacy routes.

### 7.4 Mobile — UI level

Compose-UI-test + Robolectric:
- `WorkspaceScreen` renders sections in snapshot order; sticky header behaviour.
- Tap on session row invokes `onOpenSession` with the right id.
- FAB menu `New solution` opens `CreateSolutionDialog`; `Open closed solution…` opens picker sheet.
- Picker sheet refreshes on open; tap `Open` calls `workspace.open_solution` and dismisses.
- Stale-banner appears during resync, content does not flash.
- Empty state renders when snapshot has zero solutions.

Roborazzi golden screenshots:
- `WorkspaceScreen_populated` (2 solutions, mixed session states).
- `WorkspaceScreen_emptyState`.
- `WorkspaceScreen_staleBanner`.
- `ClosedSolutionsPickerSheet_populated`.
- `ClosedSolutionsPickerSheet_empty`.

### 7.5 Wire smoke (`:cli`)

Extend `:cli` with single-shot commands for new tools:
- `cli workspace snapshot`
- `cli workspace open-solution <id>` / `close-solution <id>`
- `cli workspace open-session <id>` / `close-session <id>`
- `cli workspace list-solutions [--open|--closed]`

Useful for hand-debugging the desktop without launching mobile.

### 7.6 Optional acceptance script

`scripts/emulator-acceptance.sh` (not in CI): boots headless emulator (`emulator -no-window -no-audio`), installs debug APK, drives a basic flow (pair → assert workspace screen → close a solution → reopen). Run manually before merging large changes.

---

## 8. Migration & rollout

This is delivered as one logical change spanning desktop + mobile because the wire schema bumps and the mobile UX rebuild are entangled (no useful intermediate state).

PR sequencing inside the feature branch:

1. Test infrastructure (Robolectric + Roborazzi setup, one sanity test).
2. Desktop: `WorkspaceEventCoordinator` + new wire tools, no consumer yet.
3. Desktop: hook existing mutation paths to emit `workspace.*` events; rename `agent_session_close` → `solution_agent.delete_session`; rename `window_open` → `open`; bump `wire_schema_version`.
4. Mobile: `WorkspaceStore` + DTO additions + `supportedWireSchemaVersion` bump.
5. Mobile: `WorkspaceScreen` + `ClosedSolutionsPickerSheet` + nav-graph swap + saved-route migration.
6. Remove `SolutionsListScreen`, `SolutionDetailScreen`, and now-unused `SolutionStore`.
7. End-to-end smoke + Roborazzi goldens green.

Steps 2-6 land together (they're behind the wire-version bump anyway).

---

## 9. Open items

None at design time — the four points raised during brainstorming (chatty-field strategy, tool naming, delete architecture, close-solution semantics for terminals/sessions) are all resolved above. Detailed implementation order is the writing-plans deliverable, not this spec.
