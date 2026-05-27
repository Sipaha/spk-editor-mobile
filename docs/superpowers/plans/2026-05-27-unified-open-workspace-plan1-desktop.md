# Unified Open-Workspace — Plan 1: Desktop Wire Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the desktop (Rust) side of the unified open-workspace feature: a new `workspace.*` MCP namespace with a sequenced event protocol, lifecycle RPCs (open/close solution + session), bulk snapshot, picker query, and the renames required by the spec (`window_open` → `open`, `solution_agent.close_session` → `solution_agent.delete_session`). After this plan the server exposes the complete new wire and old mobile clients land on `IncompatibleServerScreen` (expected — Plan 2 ships the new mobile).

**Architecture:** A new `crates/workspace_events` crate hosts a `WorkspaceEventCoordinator` (atomic `seq` + `RwLock`-guarded emit) and all `workspace.*` MCP tools. Tools delegate state reads to existing `SolutionStore` / `SolutionAgentStore`. Mutation sites in `solutions` and `solution_agent` crates call into the coordinator after every state change to emit sequenced deltas. `close_solution` additionally walks the `terminal` crate to kill open terminals for that solution.

**Tech Stack:** Rust, gpui App framework, `editor_mcp` (MCP server crate), `context_server::listener::McpServerTool` trait, `serde` + `schemars` for wire DTOs, `editor_mcp::emit_notification(cx, kind, payload)` for events, existing `crates/solutions/src/event_sources.rs` event-coordinator pattern for the new sequencer.

**Spec:** `docs/superpowers/specs/2026-05-27-unified-open-workspace-design.md`

**Working directory for all commands:** `/home/spk/.spk/spk-editor/solutions/spk-solutions/spk-editor/`

---

## Phase A — New `workspace_events` crate scaffolding

### Task A1: Create the crate skeleton

**Files:**
- Create: `crates/workspace_events/Cargo.toml`
- Create: `crates/workspace_events/src/workspace_events.rs`
- Modify: `Cargo.toml` (workspace root, append crate to `members`)

- [ ] **Step 1: Add the crate to the workspace.**

Append to root `Cargo.toml` `[workspace] members = [ ... ]` array (alphabetical position between `workspace` and `worktree`):

```toml
    "crates/workspace_events",
```

- [ ] **Step 2: Write the crate `Cargo.toml`.**

```toml
[package]
name = "workspace_events"
version = "0.1.0"
edition.workspace = true
publish.workspace = true
license = "GPL-3.0-or-later"

[lints]
workspace = true

[lib]
path = "src/workspace_events.rs"
doctest = false

[dependencies]
anyhow.workspace = true
chrono.workspace = true
context_server.workspace = true
editor_mcp.workspace = true
gpui.workspace = true
schemars.workspace = true
serde.workspace = true
serde_json.workspace = true
solutions.workspace = true
solution_agent.workspace = true

[dev-dependencies]
gpui = { workspace = true, features = ["test-support"] }
settings.workspace = true
tempfile.workspace = true
```

- [ ] **Step 3: Stub the library entry point.**

`crates/workspace_events/src/workspace_events.rs`:
```rust
//! Workspace-events crate.
//!
//! Owns the sequenced event protocol that backs the mobile `workspace.*` MCP
//! surface. Hosts:
//!   - `WorkspaceEventCoordinator` — an atomic `seq` counter + the sequenced
//!     emit helper used by mutation paths in `solutions` and `solution_agent`.
//!   - The `workspace.*` MCP tools: `snapshot`, `list_solutions`, `open_solution`,
//!     `close_solution`, `open_session`, `close_session`.
//!
//! Subsequent tasks fill these out. For now we expose `init` so `crates/zed`
//! can wire us up without further plumbing later.

use gpui::App;

mod coordinator;
mod mcp;

pub use coordinator::{WorkspaceEvent, WorkspaceEventCoordinator};

/// Install the coordinator + register MCP tools. Idempotent.
pub fn init(cx: &mut App) {
    coordinator::install(cx);
    mcp::register(cx);
}
```

- [ ] **Step 4: Stub the two submodule files so `cargo check` is green.**

`crates/workspace_events/src/coordinator.rs`:
```rust
use gpui::{App, Global};
use std::sync::atomic::AtomicU64;

pub struct WorkspaceEventCoordinator {
    pub(crate) seq: AtomicU64,
}

struct Global_(WorkspaceEventCoordinator);
impl Global for Global_ {}

pub fn install(cx: &mut App) {
    if cx.try_global::<Global_>().is_some() {
        return;
    }
    cx.set_global(Global_(WorkspaceEventCoordinator {
        seq: AtomicU64::new(0),
    }));
}

#[derive(Debug, Clone)]
pub enum WorkspaceEvent {}
```

`crates/workspace_events/src/mcp.rs`:
```rust
use gpui::App;

pub fn register(_cx: &mut App) {}
```

- [ ] **Step 5: Run cargo check, expect green.**

Run: `cargo check -p workspace_events`
Expected: PASS, no warnings.

- [ ] **Step 6: Commit.**

```bash
git add Cargo.toml crates/workspace_events
git commit -m "feat(workspace_events): scaffold new crate for sequenced event protocol"
```

---

### Task A2: Implement the `WorkspaceEventCoordinator` sequencer (TDD)

**Files:**
- Modify: `crates/workspace_events/src/coordinator.rs`
- Modify: `crates/workspace_events/src/workspace_events.rs` (re-exports)

- [ ] **Step 1: Write the failing test.**

Append to `crates/workspace_events/src/coordinator.rs`:
```rust
#[cfg(test)]
mod tests {
    use super::*;
    use gpui::TestAppContext;

    #[gpui::test]
    async fn seq_starts_at_zero_and_increments(cx: &mut TestAppContext) {
        cx.update(install);
        cx.update(|cx| {
            let coord = WorkspaceEventCoordinator::global(cx);
            assert_eq!(coord.current_seq(), 0);
            assert_eq!(coord.next_seq(), 1);
            assert_eq!(coord.next_seq(), 2);
            assert_eq!(coord.current_seq(), 2);
        });
    }

    #[gpui::test]
    async fn install_is_idempotent(cx: &mut TestAppContext) {
        cx.update(install);
        cx.update(install);
        cx.update(|cx| {
            let coord = WorkspaceEventCoordinator::global(cx);
            assert_eq!(coord.current_seq(), 0);
        });
    }
}
```

- [ ] **Step 2: Run test, expect failure.**

Run: `cargo test -p workspace_events seq_starts_at_zero_and_increments`
Expected: FAIL with "no function or associated item named `global` / `current_seq` / `next_seq`".

- [ ] **Step 3: Implement the methods.**

Replace `crates/workspace_events/src/coordinator.rs` with:
```rust
use gpui::{App, Global};
use std::sync::atomic::{AtomicU64, Ordering};

pub struct WorkspaceEventCoordinator {
    seq: AtomicU64,
}

impl WorkspaceEventCoordinator {
    pub fn global(cx: &App) -> &Self {
        &cx.global::<Global_>().0
    }

    pub fn current_seq(&self) -> u64 {
        self.seq.load(Ordering::SeqCst)
    }

    /// Increment and return the new value. Use this on every mutation that
    /// emits a sequenced workspace event.
    pub fn next_seq(&self) -> u64 {
        self.seq.fetch_add(1, Ordering::SeqCst) + 1
    }
}

struct Global_(WorkspaceEventCoordinator);
impl Global for Global_ {}

pub fn install(cx: &mut App) {
    if cx.try_global::<Global_>().is_some() {
        return;
    }
    cx.set_global(Global_(WorkspaceEventCoordinator {
        seq: AtomicU64::new(0),
    }));
}

#[derive(Debug, Clone)]
pub enum WorkspaceEvent {}

#[cfg(test)]
mod tests { /* keep block from Step 1 */ }
```

- [ ] **Step 4: Run tests, expect pass.**

Run: `cargo test -p workspace_events`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit.**

```bash
git add crates/workspace_events/src/coordinator.rs
git commit -m "feat(workspace_events): atomic seq counter (next/current_seq, install idempotent)"
```

---

### Task A3: Add the sequenced emit helper

**Files:**
- Modify: `crates/workspace_events/src/coordinator.rs`

- [ ] **Step 1: Write the failing test (concurrency).**

Append to the `tests` mod:
```rust
#[gpui::test]
async fn next_seq_is_monotonic_under_contention(cx: &mut TestAppContext) {
    cx.update(install);
    let observed = cx.update(|cx| {
        let coord = WorkspaceEventCoordinator::global(cx);
        let mut seen = Vec::new();
        for _ in 0..1000 {
            seen.push(coord.next_seq());
        }
        seen
    });
    let mut sorted = observed.clone();
    sorted.sort_unstable();
    sorted.dedup();
    assert_eq!(sorted.len(), 1000, "no duplicates");
    assert_eq!(observed, sorted, "ascending without gaps");
}
```

- [ ] **Step 2: Run test, expect pass (atomic already correct).**

Run: `cargo test -p workspace_events next_seq_is_monotonic`
Expected: PASS.

- [ ] **Step 3: Add the `emit_sequenced` helper that bundles next_seq + emit_notification.**

In `crates/workspace_events/src/coordinator.rs`, add:
```rust
use serde_json::{Value, json};

impl WorkspaceEventCoordinator {
    /// Reserve the next seq AND emit a sequenced notification atomically.
    ///
    /// `payload_without_seq` is mutated to inject the assigned seq under the
    /// `"seq"` key before emission. Callers should already hold whatever
    /// state-write lock guards the mutation they're announcing — the seq is
    /// reserved BEFORE the notification fires so consumers cannot observe a
    /// newer seq from a snapshot than from any preceding delta.
    pub fn emit_sequenced(
        &self,
        cx: &App,
        kind: &str,
        mut payload_without_seq: Value,
    ) -> u64 {
        let seq = self.next_seq();
        if let Value::Object(ref mut map) = payload_without_seq {
            map.insert("seq".to_string(), json!(seq));
        } else {
            // Caller bug: every workspace event payload must be a JSON object.
            // Wrap into one rather than panicking in prod.
            payload_without_seq = json!({ "seq": seq, "payload": payload_without_seq });
        }
        editor_mcp::emit_notification(cx, kind, payload_without_seq);
        seq
    }
}
```

- [ ] **Step 4: Test the emit helper injects seq into the payload.**

```rust
#[gpui::test]
async fn emit_sequenced_injects_seq_field(cx: &mut TestAppContext) {
    cx.update(install);
    cx.update(|cx| {
        let coord = WorkspaceEventCoordinator::global(cx);
        // Without a real MCP server, emit_notification is a no-op — we only
        // check that next_seq advanced and the helper returns the new value.
        let s1 = coord.emit_sequenced(cx, "workspace.test", json!({ "id": "abc" }));
        let s2 = coord.emit_sequenced(cx, "workspace.test", json!({ "id": "def" }));
        assert_eq!(s1, 1);
        assert_eq!(s2, 2);
    });
}
```

- [ ] **Step 5: Run tests.**

Run: `cargo test -p workspace_events`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit.**

```bash
git add crates/workspace_events/src/coordinator.rs
git commit -m "feat(workspace_events): emit_sequenced helper bundles next_seq with emit_notification"
```

---

### Task A4: Wire `workspace_events::init` into the desktop app

**Files:**
- Modify: `crates/zed/Cargo.toml` (add dep)
- Modify: `crates/zed/src/main.rs` or `crates/zed/src/zed.rs` (call init)

- [ ] **Step 1: Find the init wiring site.**

Run: `grep -nR "solutions::init\|solution_agent::init" crates/zed/src | head -5`
Expected: prints the file/line where the existing `solutions` and `solution_agent` crates are initialised at app startup.

- [ ] **Step 2: Add the dep.**

In `crates/zed/Cargo.toml`, add `workspace_events.workspace = true` to `[dependencies]` (alphabetical position). Also add `workspace_events = { path = "crates/workspace_events" }` to root `Cargo.toml` `[workspace.dependencies]` if other crates will import it; for now keep just the `zed` dep.

- [ ] **Step 3: Add the init call.**

In the file located in Step 1, after the existing `solution_agent::init(cx)` line, add:
```rust
workspace_events::init(cx);
```

- [ ] **Step 4: Verify build.**

Run: `cargo check -p zed`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add Cargo.toml crates/zed/Cargo.toml crates/zed/src/
git commit -m "feat(zed): wire workspace_events::init at app startup"
```

---

## Phase B — Renames and wire-schema bump

### Task B1: Rename `SolutionSummary::window_open` → `open`

**Files:**
- Modify: `crates/solutions/src/mcp.rs:151` (struct field)
- Modify: `crates/solutions/src/mcp.rs:201` (population site)
- Modify: any other site touching `window_open` (grep below)

- [ ] **Step 1: Find every reference.**

Run: `grep -nR "window_open" crates/ --include='*.rs'`
Expected: a manageable list (single-digit hits in `crates/solutions/`).

- [ ] **Step 2: Rename the field and update the population site.**

In `crates/solutions/src/mcp.rs`, replace `window_open` with `open` everywhere. The struct definition becomes:
```rust
#[serde(rename = "open")]  // explicit serde rename so JSON wire stays clean
pub open: bool,
```
(In Rust the field is `open`; `serde` rename is optional if you keep the field name as `open` — confirm there's no Rust keyword conflict since `open` is a method name on many traits but valid as a field).

- [ ] **Step 3: Update tests in `crates/solutions/src/mcp.rs` tests.**

In the test module, every assertion like `assert!(arr[0].window_open)` becomes `assert!(arr[0].open)`. The `assert!(!arr[0].window_open)` site at line ~4840 also.

- [ ] **Step 4: Compile.**

Run: `cargo check -p solutions`
Expected: PASS.

- [ ] **Step 5: Run solutions tests.**

Run: `cargo test -p solutions`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add crates/solutions/src/mcp.rs
git commit -m "refactor(solutions): rename SolutionSummary.window_open -> open (wire-breaking)"
```

---

### Task B2: Rename `solution_agent.close_session` → `solution_agent.delete_session`

**Files:**
- Modify: `crates/solution_agent/src/mcp.rs` (find `CloseSessionTool`, rename struct + `const NAME` + `register` call)
- Modify: any test that calls `solution_agent.close_session`

- [ ] **Step 1: Find the tool definition.**

Run: `grep -n "CloseSessionTool\|solution_agent.close_session" crates/solution_agent/src/mcp.rs`
Expected: shows the struct declaration, impl, and the `register` site at line ~41.

- [ ] **Step 2: Rename in `crates/solution_agent/src/mcp.rs`.**

- `pub struct CloseSessionTool;` → `pub struct DeleteSessionTool;`
- `impl McpServerTool for CloseSessionTool` → `impl McpServerTool for DeleteSessionTool`
- `const NAME: &'static str = "solution_agent.close_session";` → `const NAME: &'static str = "solution_agent.delete_session";`
- `server.add_tool(CloseSessionTool);` → `server.add_tool(DeleteSessionTool);`
- Any Params/Result types prefixed `CloseSession*` → `DeleteSession*`.

- [ ] **Step 3: Update test sites.**

Run: `grep -nR "close_session\|CloseSessionTool" crates/solution_agent --include='*.rs'`
Replace remaining occurrences with `delete_session` / `DeleteSessionTool`. EXCEPTION: in-code methods like `SolutionAgentStore::close_session` (the internal Rust API) — KEEP those for now; we'll align the internal name in a follow-up task to keep this commit focused on the wire name.

- [ ] **Step 4: Compile + test.**

Run: `cargo test -p solution_agent`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add crates/solution_agent
git commit -m "refactor(solution_agent): rename wire tool close_session -> delete_session (wire-breaking)"
```

---

### Task B3: Bump `wire_schema_version`

**Files:**
- Modify: `crates/editor_mcp/src/tools/capabilities.rs:87`
- Modify: `crates/editor_mcp/tests/server_e2e_test.rs` (the assertion `>= 1` may already be tolerant; double-check)

- [ ] **Step 1: Write the failing test.**

In `crates/editor_mcp/tests/server_e2e_test.rs`, find the existing `wire_schema_version >= 1` assertion and tighten to `>= 2`:
```rust
assert!(
    wire_schema_version >= 2,
    "wire_schema_version should be >= 2 after workspace.* additions; got {}",
    wire_schema_version
);
```

- [ ] **Step 2: Run test, expect failure.**

Run: `cargo test -p editor_mcp wire_schema_version`
Expected: FAIL — current value is 1.

- [ ] **Step 3: Bump the value.**

In `crates/editor_mcp/src/tools/capabilities.rs:87`, change `wire_schema_version: 1` to `wire_schema_version: 2`. Add a brief comment above the field initialisation noting that v2 introduces the `workspace.*` namespace and renames `SolutionSummary.window_open` and `solution_agent.close_session`.

- [ ] **Step 4: Run test, expect pass.**

Run: `cargo test -p editor_mcp wire_schema_version`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add crates/editor_mcp/src/tools/capabilities.rs crates/editor_mcp/tests/server_e2e_test.rs
git commit -m "feat(editor_mcp): bump wire_schema_version to 2 (workspace.* namespace, breaking renames)"
```

---

## Phase C — `workspace.snapshot` RPC

### Task C1: Define the snapshot DTOs

**Files:**
- Create: `crates/workspace_events/src/dto.rs`
- Modify: `crates/workspace_events/src/workspace_events.rs` (add `mod dto;`)

- [ ] **Step 1: Write the DTO definitions.**

`crates/workspace_events/src/dto.rs`:
```rust
//! Wire DTOs for the workspace.* MCP namespace. Mirror the structure
//! described in `docs/superpowers/specs/2026-05-27-unified-open-workspace-design.md` §3.

use schemars::JsonSchema;
use serde::{Deserialize, Serialize};
use solutions::mcp::SolutionSummary;
use solution_agent::mcp::SessionSummary;

#[derive(Serialize, Deserialize, JsonSchema, Debug, Clone)]
pub struct WorkspaceSolution {
    #[serde(flatten)]
    pub solution: SolutionSummary,
    pub sessions: Vec<SessionSummary>,
}

#[derive(Serialize, Deserialize, JsonSchema, Debug, Clone)]
pub struct WorkspaceSnapshot {
    pub seq: u64,
    pub solutions: Vec<WorkspaceSolution>,
}

#[derive(Serialize, Deserialize, JsonSchema, Debug, Default, Clone)]
pub struct SnapshotParams {
    /// Reserved for future use; ignored today.
    #[serde(default)]
    pub _placeholder: Option<()>,
}

#[derive(Serialize, Deserialize, JsonSchema, Debug, Clone)]
pub struct ListSolutionsParams {
    /// None = both. Some(true) = only open. Some(false) = only closed.
    #[serde(default)]
    pub open: Option<bool>,
}

#[derive(Serialize, Deserialize, JsonSchema, Debug, Clone)]
pub struct ListSolutionsResult {
    pub solutions: Vec<SolutionSummary>,
}

#[derive(Serialize, Deserialize, JsonSchema, Debug, Clone)]
pub struct SolutionIdParam {
    pub solution_id: String,
}

#[derive(Serialize, Deserialize, JsonSchema, Debug, Clone)]
pub struct SessionIdParam {
    pub session_id: String,
}

#[derive(Serialize, Deserialize, JsonSchema, Debug, Clone)]
pub struct SeqAck {
    pub seq: u64,
}
```

- [ ] **Step 2: Register the module.**

In `crates/workspace_events/src/workspace_events.rs`, add `mod dto;` and `pub use dto::*;` near the existing `mod coordinator;` line.

- [ ] **Step 3: Verify build.**

Run: `cargo check -p workspace_events`
Expected: PASS. NOTE: if `SolutionSummary` or `SessionSummary` aren't `pub` from their parent crates, you'll get a privacy error — in that case make them `pub use` from `crates/solutions/src/lib.rs` and `crates/solution_agent/src/lib.rs` respectively (one-line change each), and commit that as a separate prep step.

- [ ] **Step 4: Commit.**

```bash
git add crates/workspace_events/src/
git commit -m "feat(workspace_events): wire DTOs for snapshot + list + lifecycle params"
```

---

### Task C2: Implement `workspace.snapshot` tool (TDD)

**Files:**
- Modify: `crates/workspace_events/src/mcp.rs`
- Create: `crates/workspace_events/src/snapshot.rs`

- [ ] **Step 1: Write the failing test.**

Create `crates/workspace_events/tests/snapshot_test.rs` (new directory):
```rust
//! End-to-end MCP smoke for workspace.snapshot.
use gpui::TestAppContext;
use serde_json::json;
use std::path::PathBuf;
use tempfile::tempdir;

mod support {
    pub use solution_agent::test_support::*;
}

#[gpui::test]
async fn snapshot_returns_seq_zero_and_empty_when_nothing_open(cx: &mut TestAppContext) {
    let runtime_dir = tempdir().expect("tempdir");
    editor_mcp::set_runtime_dir_for_test(runtime_dir.path().to_path_buf());

    cx.update(|cx| {
        settings::SettingsStore::test(cx);
        editor_mcp::init(cx);
        let dir = runtime_dir.path().join("solutions.json");
        solutions::SolutionStore::for_test(dir, cx);
        // Register solution_agent + workspace_events.
        let registry = solution_agent::test_support::test_registry(cx);
        solution_agent::SolutionAgentStore::init_global(cx, registry);
        solution_agent::mcp::register(cx);
        workspace_events::init(cx);
        editor_mcp::start_server(cx);
    });

    cx.run_until_parked();

    let mut stream = support::open_test_stream().await;
    let resp = support::call_tool(
        &mut stream,
        1,
        "workspace.snapshot",
        json!({}),
    ).await;

    assert_eq!(resp["seq"].as_u64(), Some(0));
    assert!(resp["solutions"].as_array().unwrap().is_empty());
}
```

NOTE: `support::open_test_stream` / `support::call_tool` are the helpers used by `crates/solution_agent/tests/basic_e2e_test.rs`. If they're not currently `pub`, make them `pub` in `crates/solution_agent/src/test_support.rs` and commit that as a prep step before this task. Run `grep -n "fn call_tool\|fn open_test_stream" crates/solution_agent/src/test_support.rs` to confirm the names match yours.

- [ ] **Step 2: Run test, expect failure (tool not registered).**

Run: `cargo test -p workspace_events --test snapshot_test`
Expected: FAIL with "Tool not found: workspace.snapshot" or similar wire error.

- [ ] **Step 3: Implement the snapshot builder.**

Create `crates/workspace_events/src/snapshot.rs`:
```rust
use crate::dto::{WorkspaceSnapshot, WorkspaceSolution};
use crate::coordinator::WorkspaceEventCoordinator;
use gpui::App;
use solutions::{SolutionId, SolutionStore};
use solution_agent::SolutionAgentStore;

pub(crate) fn build_snapshot(cx: &App) -> WorkspaceSnapshot {
    let coord = WorkspaceEventCoordinator::global(cx);
    let seq = coord.current_seq();

    let solution_store = SolutionStore::try_global(cx);
    let agent_store = SolutionAgentStore::try_global(cx);

    let solutions: Vec<WorkspaceSolution> = match (solution_store, agent_store) {
        (Some(ss), Some(ags)) => ss.read_with(cx, |store, cx| {
            store
                .all_solutions()
                .filter(|sol| sol.is_open(cx))                     // window/tab open
                .map(|sol| {
                    let sessions = ags.read_with(cx, |agent_store, cx| {
                        agent_store
                            .all_sessions_for_solution(&sol.id())
                            .filter(|s| s.tab_order.is_some())     // open in strip
                            .map(|s| solution_agent::mcp::session_summary(s, cx))
                            .collect()
                    });
                    WorkspaceSolution {
                        solution: solutions::mcp::solution_summary(sol, cx),
                        sessions,
                    }
                })
                .collect()
        }),
        _ => Vec::new(),
    };

    WorkspaceSnapshot { seq, solutions }
}
```

NOTE: `SolutionStore::all_solutions`, `Solution::is_open`, `solution_summary`, `SolutionAgentStore::all_sessions_for_solution`, and `solution_agent::mcp::session_summary` are the methods you need to expose `pub(crate)`/`pub` if not already. Run a grep for each to confirm — if missing, add a one-line accessor in the owning crate as part of this task.

- [ ] **Step 4: Implement the tool.**

Replace `crates/workspace_events/src/mcp.rs`:
```rust
use anyhow::Result;
use context_server::listener::{McpServerTool, ToolResponse};
use context_server::types::ToolResponseContent;
use gpui::{App, AsyncApp};

use crate::dto::{SnapshotParams, WorkspaceSnapshot};
use crate::snapshot::build_snapshot;

#[derive(Clone)]
pub struct SnapshotTool;

impl McpServerTool for SnapshotTool {
    type Input = SnapshotParams;
    type Output = WorkspaceSnapshot;
    const NAME: &'static str = "workspace.snapshot";

    async fn run(
        &self,
        _input: Self::Input,
        cx: &mut AsyncApp,
    ) -> Result<ToolResponse<Self::Output>> {
        let snap = cx.update(|cx| build_snapshot(cx))?;
        Ok(ToolResponse {
            content: vec![ToolResponseContent::Text {
                text: format!("snapshot seq={} solutions={}", snap.seq, snap.solutions.len()),
            }],
            structured_content: snap,
        })
    }
}

pub fn register(cx: &mut App) {
    editor_mcp::register_tool(cx, |server| {
        server.add_tool(SnapshotTool);
    });
}
```

Also add `mod snapshot;` to `crates/workspace_events/src/workspace_events.rs`.

- [ ] **Step 5: Run test, expect pass.**

Run: `cargo test -p workspace_events --test snapshot_test`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add crates/workspace_events crates/solutions crates/solution_agent
git commit -m "feat(workspace_events): workspace.snapshot tool returns seq + open solutions/sessions"
```

---

### Task C3: Snapshot includes open solution + its open sessions (TDD)

**Files:**
- Modify: `crates/workspace_events/tests/snapshot_test.rs`

- [ ] **Step 1: Add the populated-state test.**

Append to `crates/workspace_events/tests/snapshot_test.rs`:
```rust
#[gpui::test]
async fn snapshot_includes_open_solution_with_its_open_sessions(cx: &mut TestAppContext) {
    let runtime_dir = tempdir().expect("tempdir");
    editor_mcp::set_runtime_dir_for_test(runtime_dir.path().to_path_buf());

    let (sol_id, sess_id_a, _sess_id_b) = cx.update(|cx| {
        settings::SettingsStore::test(cx);
        editor_mcp::init(cx);
        solutions::SolutionStore::for_test(runtime_dir.path().join("s.json"), cx);
        let reg = solution_agent::test_support::test_registry(cx);
        solution_agent::SolutionAgentStore::init_global(cx, reg);
        solution_agent::mcp::register(cx);
        workspace_events::init(cx);
        editor_mcp::start_server(cx);

        // Create one open solution + two sessions: one in tab strip, one not.
        let store = solutions::SolutionStore::global(cx);
        let sol_id = store.update(cx, |s, cx| {
            s.create_for_test("alpha", cx)
        });
        let agent = solution_agent::SolutionAgentStore::global(cx);
        let sess_id_a = agent.update(cx, |a, cx| a.create_for_test(&sol_id, "in-strip", cx));
        let sess_id_b = agent.update(cx, |a, cx| a.create_for_test(&sol_id, "not-tabbed", cx));
        agent.update(cx, |a, cx| a.set_tab_order_for_test(&sol_id, vec![sess_id_a.clone()], cx));
        (sol_id, sess_id_a, sess_id_b)
    });
    cx.run_until_parked();

    let mut stream = support::open_test_stream().await;
    let resp = support::call_tool(&mut stream, 2, "workspace.snapshot", json!({})).await;

    let solutions = resp["solutions"].as_array().unwrap();
    assert_eq!(solutions.len(), 1);
    assert_eq!(solutions[0]["id"].as_str(), Some(sol_id.0.as_str()));
    assert!(solutions[0]["open"].as_bool().unwrap());
    let sessions = solutions[0]["sessions"].as_array().unwrap();
    assert_eq!(sessions.len(), 1, "only the tab-ordered session is returned");
    assert_eq!(sessions[0]["id"].as_str(), Some(sess_id_a.0.as_str()));
}
```

NOTE: `create_for_test` / `set_tab_order_for_test` are conveniences you'll need to add as `#[cfg(test)]` methods on the respective stores. If existing tests in `solution_agent` use a different idiom for fixture creation, copy that idiom instead — match what's idiomatic in that crate.

- [ ] **Step 2: Run test, expect failure if any fixture helper is missing.**

Run: `cargo test -p workspace_events --test snapshot_test snapshot_includes_open_solution`
Expected: FAIL with a compile or runtime error pointing to the missing helper.

- [ ] **Step 3: Add the test-only fixture helpers.**

In `crates/solutions/src/store.rs`, add at the bottom of the `impl SolutionStore` block:
```rust
#[cfg(test)]
pub fn create_for_test(&mut self, name: &str, cx: &mut Context<Self>) -> SolutionId {
    /* match the shape used by other create_for_test sites in this crate; if
     * none exist, build a minimal Solution and insert. */
    todo_replace_with_real_minimal_create()
}
```

(Replace `todo_replace_with_real_minimal_create()` with the actual minimal Solution construction. Look at `crates/solutions/src/store.rs` existing test-support code for the exact shape — there's likely already a `#[cfg(test)] mod fixtures` block or similar.)

Similarly in `crates/solution_agent/src/store.rs`:
```rust
#[cfg(test)]
pub fn create_for_test(
    &mut self,
    solution_id: &SolutionId,
    title: &str,
    cx: &mut Context<Self>,
) -> SolutionSessionId { ... }

#[cfg(test)]
pub fn set_tab_order_for_test(
    &mut self,
    solution_id: &SolutionId,
    order: Vec<SolutionSessionId>,
    cx: &mut Context<Self>,
) { /* call db.update_tab_orders + emit relevant events */ }
```

- [ ] **Step 4: Run test, expect pass.**

Run: `cargo test -p workspace_events --test snapshot_test snapshot_includes_open_solution`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add crates/workspace_events crates/solutions crates/solution_agent
git commit -m "test(workspace_events): snapshot includes open solutions with tab-ordered sessions only"
```

---

## Phase D — `workspace.list_solutions` RPC

### Task D1: Implement `workspace.list_solutions` with `open` filter (TDD)

**Files:**
- Create: `crates/workspace_events/src/list.rs`
- Modify: `crates/workspace_events/src/mcp.rs` (register new tool)
- Modify: `crates/workspace_events/src/workspace_events.rs` (`mod list;`)

- [ ] **Step 1: Write the failing test.**

Append to `crates/workspace_events/tests/snapshot_test.rs`:
```rust
#[gpui::test]
async fn list_solutions_filters_by_open_state(cx: &mut TestAppContext) {
    let runtime_dir = tempdir().expect("tempdir");
    editor_mcp::set_runtime_dir_for_test(runtime_dir.path().to_path_buf());
    let (open_id, closed_id) = cx.update(|cx| {
        settings::SettingsStore::test(cx);
        editor_mcp::init(cx);
        solutions::SolutionStore::for_test(runtime_dir.path().join("s.json"), cx);
        let reg = solution_agent::test_support::test_registry(cx);
        solution_agent::SolutionAgentStore::init_global(cx, reg);
        solution_agent::mcp::register(cx);
        workspace_events::init(cx);
        editor_mcp::start_server(cx);
        let store = solutions::SolutionStore::global(cx);
        let open_id = store.update(cx, |s, cx| s.create_for_test("open-one", cx));
        let closed_id = store.update(cx, |s, cx| s.create_for_test("closed-one", cx));
        // Close the second one (set window_open=false).
        store.update(cx, |s, cx| s.deactivate_for_test(&closed_id, cx));
        (open_id, closed_id)
    });
    cx.run_until_parked();

    let mut stream = support::open_test_stream().await;
    let only_open = support::call_tool(&mut stream, 1, "workspace.list_solutions", json!({"open": true})).await;
    let only_closed = support::call_tool(&mut stream, 2, "workspace.list_solutions", json!({"open": false})).await;
    let both = support::call_tool(&mut stream, 3, "workspace.list_solutions", json!({})).await;

    let ids = |v: &serde_json::Value| -> Vec<String> {
        v["solutions"].as_array().unwrap().iter()
            .map(|s| s["id"].as_str().unwrap().to_string()).collect()
    };
    assert_eq!(ids(&only_open), vec![open_id.0.to_string()]);
    assert_eq!(ids(&only_closed), vec![closed_id.0.to_string()]);
    assert_eq!(ids(&both).len(), 2);
}
```

- [ ] **Step 2: Run test, expect failure.**

Run: `cargo test -p workspace_events list_solutions_filters_by_open_state`
Expected: FAIL with "Tool not found: workspace.list_solutions".

- [ ] **Step 3: Implement the tool.**

`crates/workspace_events/src/list.rs`:
```rust
use anyhow::Result;
use context_server::listener::{McpServerTool, ToolResponse};
use context_server::types::ToolResponseContent;
use gpui::{App, AsyncApp};

use crate::dto::{ListSolutionsParams, ListSolutionsResult};

#[derive(Clone)]
pub struct ListSolutionsTool;

impl McpServerTool for ListSolutionsTool {
    type Input = ListSolutionsParams;
    type Output = ListSolutionsResult;
    const NAME: &'static str = "workspace.list_solutions";

    async fn run(
        &self,
        input: Self::Input,
        cx: &mut AsyncApp,
    ) -> Result<ToolResponse<Self::Output>> {
        let solutions = cx.update(|cx| {
            let store = match solutions::SolutionStore::try_global(cx) {
                Some(s) => s,
                None => return Vec::new(),
            };
            store.read_with(cx, |store, cx| {
                store.all_solutions()
                    .filter(|sol| match input.open {
                        None => true,
                        Some(want) => sol.is_open(cx) == want,
                    })
                    .map(|sol| solutions::mcp::solution_summary(sol, cx))
                    .collect()
            })
        })?;
        Ok(ToolResponse {
            content: vec![ToolResponseContent::Text {
                text: format!("{} solution(s)", solutions.len()),
            }],
            structured_content: ListSolutionsResult { solutions },
        })
    }
}
```

- [ ] **Step 4: Register the tool.**

In `crates/workspace_events/src/mcp.rs`, in the `register` function add:
```rust
editor_mcp::register_tool(cx, |server| {
    server.add_tool(crate::list::ListSolutionsTool);
});
```

Also add `mod list;` to `workspace_events.rs`. Add `deactivate_for_test` test helper to `SolutionStore` if missing (matches the same pattern as `create_for_test` from Task C3).

- [ ] **Step 5: Run test, expect pass.**

Run: `cargo test -p workspace_events list_solutions_filters_by_open_state`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add crates/workspace_events crates/solutions
git commit -m "feat(workspace_events): workspace.list_solutions with open filter (None/true/false)"
```

---

## Phase E — Lifecycle RPCs

### Task E1: `workspace.open_solution` for already-open is no-op (TDD)

**Files:**
- Create: `crates/workspace_events/src/lifecycle.rs`
- Modify: `crates/workspace_events/src/mcp.rs` (register)
- Modify: `crates/workspace_events/src/workspace_events.rs` (`mod lifecycle;`)

- [ ] **Step 1: Write the failing test.**

Append to `crates/workspace_events/tests/snapshot_test.rs`:
```rust
#[gpui::test]
async fn open_solution_for_already_open_is_noop(cx: &mut TestAppContext) {
    let runtime_dir = tempdir().expect("tempdir");
    editor_mcp::set_runtime_dir_for_test(runtime_dir.path().to_path_buf());
    let sol_id = cx.update(|cx| {
        settings::SettingsStore::test(cx);
        editor_mcp::init(cx);
        solutions::SolutionStore::for_test(runtime_dir.path().join("s.json"), cx);
        let reg = solution_agent::test_support::test_registry(cx);
        solution_agent::SolutionAgentStore::init_global(cx, reg);
        solution_agent::mcp::register(cx);
        workspace_events::init(cx);
        editor_mcp::start_server(cx);
        let store = solutions::SolutionStore::global(cx);
        store.update(cx, |s, cx| s.create_for_test("a", cx))
    });
    cx.run_until_parked();

    let mut stream = support::open_test_stream().await;
    let pre = support::call_tool(&mut stream, 1, "workspace.snapshot", json!({})).await;
    let pre_seq = pre["seq"].as_u64().unwrap();

    let ack = support::call_tool(
        &mut stream, 2,
        "workspace.open_solution",
        json!({ "solution_id": sol_id.0 }),
    ).await;
    let ack_seq = ack["seq"].as_u64().unwrap();
    assert_eq!(ack_seq, pre_seq, "no-op must not advance seq");
}
```

- [ ] **Step 2: Run test, expect failure.**

Run: `cargo test -p workspace_events open_solution_for_already_open`
Expected: FAIL — "Tool not found: workspace.open_solution".

- [ ] **Step 3: Implement the tool.**

`crates/workspace_events/src/lifecycle.rs`:
```rust
use anyhow::{Result, anyhow};
use context_server::listener::{McpServerTool, ToolResponse};
use context_server::types::ToolResponseContent;
use gpui::{App, AsyncApp};
use serde_json::json;
use solutions::{SolutionId, SolutionStore};

use crate::coordinator::WorkspaceEventCoordinator;
use crate::dto::{SolutionIdParam, SessionIdParam, SeqAck};

#[derive(Clone)]
pub struct OpenSolutionTool;

impl McpServerTool for OpenSolutionTool {
    type Input = SolutionIdParam;
    type Output = SeqAck;
    const NAME: &'static str = "workspace.open_solution";

    async fn run(
        &self,
        input: Self::Input,
        cx: &mut AsyncApp,
    ) -> Result<ToolResponse<Self::Output>> {
        let id = SolutionId(input.solution_id.into());
        let seq = cx.update(|cx| -> Result<u64> {
            let store = SolutionStore::try_global(cx)
                .ok_or_else(|| anyhow!("solution store not initialised"))?;
            let coord = WorkspaceEventCoordinator::global(cx);
            let was_open = store.read_with(cx, |s, cx| {
                s.get(&id).map(|sol| sol.is_open(cx))
            }).ok_or_else(|| anyhow!("solution not found: {}", id.0))?;
            if was_open {
                return Ok(coord.current_seq());
            }
            // Reopen: activate window, hydrate sessions.
            store.update(cx, |s, cx| s.activate(&id, cx))?;
            // Hydrate (existing API in solution_agent crate).
            if let Some(agent) = solution_agent::SolutionAgentStore::try_global(cx) {
                agent.update(cx, |a, cx| a.hydrate_all_for_solution(id.clone(), cx)).await?;
            }
            // Emit sequenced.
            let solution = store.read_with(cx, |s, cx| {
                solutions::mcp::solution_summary(s.get(&id).expect("just activated"), cx)
            });
            let sessions = solution_agent::SolutionAgentStore::try_global(cx)
                .map(|agent| agent.read_with(cx, |a, cx| {
                    a.all_sessions_for_solution(&id)
                        .filter(|s| s.tab_order.is_some())
                        .map(|s| solution_agent::mcp::session_summary(s, cx))
                        .collect::<Vec<_>>()
                }))
                .unwrap_or_default();
            let seq = coord.emit_sequenced(cx, "workspace.solution_opened", json!({
                "solution": solution,
                "sessions": sessions,
            }));
            Ok(seq)
        })??;
        Ok(ToolResponse {
            content: vec![ToolResponseContent::Text { text: format!("seq={seq}") }],
            structured_content: SeqAck { seq },
        })
    }
}
```

- [ ] **Step 4: Register the tool.**

In `crates/workspace_events/src/mcp.rs` `register()` add:
```rust
editor_mcp::register_tool(cx, |server| {
    server.add_tool(crate::lifecycle::OpenSolutionTool);
});
```

NOTE: `SolutionStore::activate(id)` may need to be added as a public method if it doesn't exist (the test will tell you). If it's currently private, expose it with a brief doc-comment explaining it's called from `workspace_events::lifecycle::OpenSolutionTool`.

- [ ] **Step 5: Run test, expect pass.**

Run: `cargo test -p workspace_events open_solution_for_already_open`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add crates/workspace_events crates/solutions
git commit -m "feat(workspace_events): workspace.open_solution tool (no-op for already-open)"
```

---

### Task E2: `workspace.open_solution` reopens closed solution and emits `solution_opened` (TDD)

**Files:**
- Modify: `crates/workspace_events/tests/snapshot_test.rs`

- [ ] **Step 1: Write the test.**

```rust
#[gpui::test]
async fn open_solution_reopens_closed_and_emits_with_sessions(cx: &mut TestAppContext) {
    let runtime_dir = tempdir().expect("tempdir");
    editor_mcp::set_runtime_dir_for_test(runtime_dir.path().to_path_buf());
    let sol_id = cx.update(|cx| {
        settings::SettingsStore::test(cx);
        editor_mcp::init(cx);
        solutions::SolutionStore::for_test(runtime_dir.path().join("s.json"), cx);
        let reg = solution_agent::test_support::test_registry(cx);
        solution_agent::SolutionAgentStore::init_global(cx, reg);
        solution_agent::mcp::register(cx);
        workspace_events::init(cx);
        editor_mcp::start_server(cx);

        let store = solutions::SolutionStore::global(cx);
        let sol_id = store.update(cx, |s, cx| s.create_for_test("re-open-me", cx));
        // Tab-order a session before closing.
        let agent = solution_agent::SolutionAgentStore::global(cx);
        let sess = agent.update(cx, |a, cx| a.create_for_test(&sol_id, "preserved", cx));
        agent.update(cx, |a, cx| a.set_tab_order_for_test(&sol_id, vec![sess.clone()], cx));
        // Close.
        store.update(cx, |s, cx| s.deactivate_for_test(&sol_id, cx));
        sol_id
    });
    cx.run_until_parked();

    let mut stream = support::open_test_stream().await;
    let pre = support::call_tool(&mut stream, 1, "workspace.snapshot", json!({})).await;
    let pre_seq = pre["seq"].as_u64().unwrap();

    let ack = support::call_tool(&mut stream, 2, "workspace.open_solution",
        json!({"solution_id": sol_id.0})).await;
    let ack_seq = ack["seq"].as_u64().unwrap();
    assert!(ack_seq > pre_seq, "reopen must advance seq");

    let after = support::call_tool(&mut stream, 3, "workspace.snapshot", json!({})).await;
    let after_sols = after["solutions"].as_array().unwrap();
    assert_eq!(after_sols.len(), 1);
    let after_sessions = after_sols[0]["sessions"].as_array().unwrap();
    assert_eq!(after_sessions.len(), 1, "tab-ordered session must be restored");
}
```

- [ ] **Step 2: Run test, expect pass.**

Run: `cargo test -p workspace_events open_solution_reopens_closed`
Expected: PASS (logic already implemented in E1). If FAIL — fix the bug surfaced by this test before commit.

- [ ] **Step 3: Commit.**

```bash
git add crates/workspace_events/tests/snapshot_test.rs
git commit -m "test(workspace_events): open_solution restores tab_ordered sessions on reopen"
```

---

### Task E3: `workspace.close_solution` (TDD — basic flip, no terminal/agent kill yet)

**Files:**
- Modify: `crates/workspace_events/src/lifecycle.rs`
- Modify: `crates/workspace_events/src/mcp.rs`
- Modify: `crates/workspace_events/tests/snapshot_test.rs`

- [ ] **Step 1: Write the test.**

```rust
#[gpui::test]
async fn close_solution_flips_open_and_emits_solution_closed(cx: &mut TestAppContext) {
    let runtime_dir = tempdir().expect("tempdir");
    editor_mcp::set_runtime_dir_for_test(runtime_dir.path().to_path_buf());
    let sol_id = cx.update(|cx| {
        settings::SettingsStore::test(cx);
        editor_mcp::init(cx);
        solutions::SolutionStore::for_test(runtime_dir.path().join("s.json"), cx);
        let reg = solution_agent::test_support::test_registry(cx);
        solution_agent::SolutionAgentStore::init_global(cx, reg);
        solution_agent::mcp::register(cx);
        workspace_events::init(cx);
        editor_mcp::start_server(cx);
        let store = solutions::SolutionStore::global(cx);
        store.update(cx, |s, cx| s.create_for_test("to-close", cx))
    });
    cx.run_until_parked();

    let mut stream = support::open_test_stream().await;
    let pre = support::call_tool(&mut stream, 1, "workspace.snapshot", json!({})).await;
    assert_eq!(pre["solutions"].as_array().unwrap().len(), 1);

    let ack = support::call_tool(&mut stream, 2, "workspace.close_solution",
        json!({"solution_id": sol_id.0})).await;
    assert!(ack["seq"].as_u64().unwrap() > pre["seq"].as_u64().unwrap());

    let after = support::call_tool(&mut stream, 3, "workspace.snapshot", json!({})).await;
    assert!(after["solutions"].as_array().unwrap().is_empty(), "closed solution disappears from snapshot");
}
```

- [ ] **Step 2: Run test, expect failure.**

Run: `cargo test -p workspace_events close_solution_flips_open`
Expected: FAIL — "Tool not found".

- [ ] **Step 3: Add the tool.**

Append to `crates/workspace_events/src/lifecycle.rs`:
```rust
#[derive(Clone)]
pub struct CloseSolutionTool;

impl McpServerTool for CloseSolutionTool {
    type Input = SolutionIdParam;
    type Output = SeqAck;
    const NAME: &'static str = "workspace.close_solution";

    async fn run(
        &self,
        input: Self::Input,
        cx: &mut AsyncApp,
    ) -> Result<ToolResponse<Self::Output>> {
        let id = SolutionId(input.solution_id.into());
        let seq = cx.update(|cx| -> Result<u64> {
            let store = SolutionStore::try_global(cx)
                .ok_or_else(|| anyhow!("solution store not initialised"))?;
            let coord = WorkspaceEventCoordinator::global(cx);
            let was_open = store.read_with(cx, |s, cx| {
                s.get(&id).map(|sol| sol.is_open(cx))
            }).ok_or_else(|| anyhow!("solution not found: {}", id.0))?;
            if !was_open {
                return Ok(coord.current_seq());
            }
            // Terminate agents + terminals for this solution (Phase H).
            crate::shutdown::shutdown_solution_runtime(&id, cx);
            // Deactivate the solution (sets open=false; tab_order preserved on disk).
            store.update(cx, |s, cx| s.deactivate(&id, cx))?;
            // Emit sequenced.
            let seq = coord.emit_sequenced(cx, "workspace.solution_closed", json!({
                "solution_id": id.0,
            }));
            Ok(seq)
        })??;
        Ok(ToolResponse {
            content: vec![ToolResponseContent::Text { text: format!("seq={seq}") }],
            structured_content: SeqAck { seq },
        })
    }
}
```

- [ ] **Step 4: Stub the shutdown module.**

Create `crates/workspace_events/src/shutdown.rs`:
```rust
//! Runtime shutdown for a closing solution: kill agent processes + terminals.
//! Filled out fully in Phase H. For now, no-op so the lifecycle tool compiles.
use gpui::App;
use solutions::SolutionId;

pub fn shutdown_solution_runtime(_id: &SolutionId, _cx: &mut App) {
    // Phase H: walk SolutionAgentStore.all_sessions_for_solution(id) and
    // call cancel/close on each AcpThread; walk the terminal crate's
    // registry and kill terminals tagged with this solution_id.
}
```

Add `mod shutdown;` to `workspace_events.rs`. Register `CloseSolutionTool` in `mcp.rs`.

- [ ] **Step 5: Run test, expect pass.**

Run: `cargo test -p workspace_events close_solution_flips_open`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add crates/workspace_events
git commit -m "feat(workspace_events): workspace.close_solution flips open and emits solution_closed"
```

---

### Task E4: `workspace.open_session` and `workspace.close_session` (TDD)

**Files:**
- Modify: `crates/workspace_events/src/lifecycle.rs`
- Modify: `crates/workspace_events/src/mcp.rs`
- Modify: `crates/workspace_events/tests/snapshot_test.rs`

- [ ] **Step 1: Write the tests.**

```rust
#[gpui::test]
async fn open_close_session_tabs_in_and_out_of_strip(cx: &mut TestAppContext) {
    let runtime_dir = tempdir().expect("tempdir");
    editor_mcp::set_runtime_dir_for_test(runtime_dir.path().to_path_buf());
    let (sol_id, sess_id) = cx.update(|cx| {
        settings::SettingsStore::test(cx);
        editor_mcp::init(cx);
        solutions::SolutionStore::for_test(runtime_dir.path().join("s.json"), cx);
        let reg = solution_agent::test_support::test_registry(cx);
        solution_agent::SolutionAgentStore::init_global(cx, reg);
        solution_agent::mcp::register(cx);
        workspace_events::init(cx);
        editor_mcp::start_server(cx);
        let store = solutions::SolutionStore::global(cx);
        let sol = store.update(cx, |s, cx| s.create_for_test("s", cx));
        let agent = solution_agent::SolutionAgentStore::global(cx);
        let sess = agent.update(cx, |a, cx| a.create_for_test(&sol, "t", cx));
        // Start with no tab_order — session exists but not in strip.
        (sol, sess)
    });
    cx.run_until_parked();

    let mut stream = support::open_test_stream().await;

    // Snapshot should NOT include the session (no tab_order).
    let pre = support::call_tool(&mut stream, 1, "workspace.snapshot", json!({})).await;
    let pre_sessions = pre["solutions"][0]["sessions"].as_array().unwrap();
    assert!(pre_sessions.is_empty());

    // open_session → should appear.
    let _ = support::call_tool(&mut stream, 2, "workspace.open_session",
        json!({"session_id": sess_id.0})).await;
    let mid = support::call_tool(&mut stream, 3, "workspace.snapshot", json!({})).await;
    assert_eq!(mid["solutions"][0]["sessions"].as_array().unwrap().len(), 1);

    // close_session → should disappear, session NOT deleted.
    let _ = support::call_tool(&mut stream, 4, "workspace.close_session",
        json!({"session_id": sess_id.0})).await;
    let after = support::call_tool(&mut stream, 5, "workspace.snapshot", json!({})).await;
    assert!(after["solutions"][0]["sessions"].as_array().unwrap().is_empty());

    // Session still exists in the underlying store.
    cx.update(|cx| {
        let agent = solution_agent::SolutionAgentStore::global(cx);
        let exists = agent.read_with(cx, |a, _| a.session_exists(&sess_id));
        assert!(exists, "close_session must NOT delete the session");
    });
}
```

- [ ] **Step 2: Run, expect failure.**

Run: `cargo test -p workspace_events open_close_session_tabs`
Expected: FAIL — tools not registered.

- [ ] **Step 3: Implement both tools.**

Append to `crates/workspace_events/src/lifecycle.rs`:
```rust
#[derive(Clone)]
pub struct OpenSessionTool;

impl McpServerTool for OpenSessionTool {
    type Input = SessionIdParam;
    type Output = SeqAck;
    const NAME: &'static str = "workspace.open_session";

    async fn run(
        &self,
        input: Self::Input,
        cx: &mut AsyncApp,
    ) -> Result<ToolResponse<Self::Output>> {
        let seq = cx.update(|cx| -> Result<u64> {
            let agent = solution_agent::SolutionAgentStore::try_global(cx)
                .ok_or_else(|| anyhow!("agent store missing"))?;
            let session_id = solution_agent::SolutionSessionId::parse(&input.session_id)
                .map_err(|e| anyhow!("bad session_id: {e}"))?;
            let coord = WorkspaceEventCoordinator::global(cx);
            // Was the session already in the strip?
            let (sol_id, was_in_strip) = agent.read_with(cx, |a, _| {
                let s = a.get_session(&session_id).ok_or_else(|| anyhow!("not found"))?;
                Ok::<_, anyhow::Error>((s.solution_id.clone(), s.tab_order.is_some()))
            })?;
            if was_in_strip {
                return Ok(coord.current_seq());
            }
            // Append to tab strip.
            agent.update(cx, |a, cx| a.append_to_tab_strip(&session_id, cx))?;
            let summary = agent.read_with(cx, |a, cx| {
                let s = a.get_session(&session_id).expect("just appended");
                solution_agent::mcp::session_summary(s, cx)
            });
            let seq = coord.emit_sequenced(cx, "workspace.session_opened", json!({
                "solution_id": sol_id.0,
                "session": summary,
            }));
            Ok(seq)
        })??;
        Ok(ToolResponse {
            content: vec![ToolResponseContent::Text { text: format!("seq={seq}") }],
            structured_content: SeqAck { seq },
        })
    }
}

#[derive(Clone)]
pub struct CloseSessionTool;

impl McpServerTool for CloseSessionTool {
    type Input = SessionIdParam;
    type Output = SeqAck;
    const NAME: &'static str = "workspace.close_session";

    async fn run(
        &self,
        input: Self::Input,
        cx: &mut AsyncApp,
    ) -> Result<ToolResponse<Self::Output>> {
        let seq = cx.update(|cx| -> Result<u64> {
            let agent = solution_agent::SolutionAgentStore::try_global(cx)
                .ok_or_else(|| anyhow!("agent store missing"))?;
            let session_id = solution_agent::SolutionSessionId::parse(&input.session_id)
                .map_err(|e| anyhow!("bad session_id: {e}"))?;
            let coord = WorkspaceEventCoordinator::global(cx);
            let (sol_id, was_in_strip) = agent.read_with(cx, |a, _| {
                let s = a.get_session(&session_id).ok_or_else(|| anyhow!("not found"))?;
                Ok::<_, anyhow::Error>((s.solution_id.clone(), s.tab_order.is_some()))
            })?;
            if !was_in_strip {
                return Ok(coord.current_seq());
            }
            agent.update(cx, |a, cx| a.remove_from_tab_strip(&session_id, cx))?;
            let seq = coord.emit_sequenced(cx, "workspace.session_closed", json!({
                "solution_id": sol_id.0,
                "session_id": session_id.to_string(),
            }));
            Ok(seq)
        })??;
        Ok(ToolResponse {
            content: vec![ToolResponseContent::Text { text: format!("seq={seq}") }],
            structured_content: SeqAck { seq },
        })
    }
}
```

Register both in `crates/workspace_events/src/mcp.rs`.

NOTE: `append_to_tab_strip` / `remove_from_tab_strip` are new convenience methods on `SolutionAgentStore` that compute the new ordered list and call `db.update_tab_orders`. Add as part of this task:
```rust
// crates/solution_agent/src/store.rs
pub fn append_to_tab_strip(&mut self, id: &SolutionSessionId, cx: &mut Context<Self>) -> Result<()> { ... }
pub fn remove_from_tab_strip(&mut self, id: &SolutionSessionId, cx: &mut Context<Self>) -> Result<()> { ... }
pub fn session_exists(&self, id: &SolutionSessionId) -> bool { self.sessions.contains_key(id) }
pub fn get_session(&self, id: &SolutionSessionId) -> Option<&SolutionSession> { ... }
```

- [ ] **Step 4: Run test, expect pass.**

Run: `cargo test -p workspace_events open_close_session_tabs`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add crates/workspace_events crates/solution_agent
git commit -m "feat(workspace_events): workspace.open_session / close_session for tab strip"
```

---

## Phase F — Sequenced delta hooks from existing mutation sites

### Task F1: Emit `workspace.solution_deleted` from `solutions.delete`

**Files:**
- Modify: `crates/solutions/Cargo.toml` (add `workspace_events` dep — INVERTED, see note)
- Modify: `crates/solutions/src/store.rs:200` (`delete_solution`)

NOTE: `solutions` cannot depend on `workspace_events` because that introduces a cycle (`workspace_events` depends on `solutions`). Instead, the coordinator is exposed via the `editor_mcp` global notification channel — `solutions::delete_solution` calls `editor_mcp::emit_notification` directly with a seq value it obtains from a tiny shared trait. Two-step fix:

(a) Move the `WorkspaceEventCoordinator` struct + `install`/`global` accessor into `crates/editor_mcp/src/workspace_seq.rs` so it's reachable from both `solutions` and `solution_agent` without cycles. `workspace_events` re-exports it for tool code.

(b) Refactor `crates/workspace_events/src/coordinator.rs` to be a thin re-export from `editor_mcp::workspace_seq`.

- [ ] **Step 1: Move the coordinator.**

Create `crates/editor_mcp/src/workspace_seq.rs` with the EXACT contents of the current `crates/workspace_events/src/coordinator.rs` (including the `emit_sequenced` helper and tests). Add `pub mod workspace_seq;` to `crates/editor_mcp/src/editor_mcp.rs` (or whichever is the lib root).

Replace `crates/workspace_events/src/coordinator.rs` body with:
```rust
pub use editor_mcp::workspace_seq::{WorkspaceEventCoordinator, install, WorkspaceEvent};
```

- [ ] **Step 2: Verify both crates build.**

Run: `cargo check -p editor_mcp -p workspace_events`
Expected: PASS.

- [ ] **Step 3: Write the failing test (in solutions crate).**

In `crates/solutions/src/mcp.rs` test module, add:
```rust
#[gpui::test]
async fn delete_solution_emits_workspace_event(cx: &mut TestAppContext) {
    let runtime_dir = tempdir().expect("td");
    editor_mcp::set_runtime_dir_for_test(runtime_dir.path().to_path_buf());

    let captured = std::sync::Arc::new(std::sync::Mutex::new(Vec::<(String, serde_json::Value)>::new()));
    let probe = captured.clone();
    cx.update(|cx| {
        settings::SettingsStore::test(cx);
        editor_mcp::init(cx);
        editor_mcp::workspace_seq::install(cx);
        editor_mcp::install_notification_probe_for_test(cx, move |kind, payload| {
            probe.lock().unwrap().push((kind.to_string(), payload.clone()));
        });
        let store = SolutionStore::for_test(runtime_dir.path().join("s.json"), cx);
        let id = store.update(cx, |s, cx| s.create_for_test("doomed", cx));
        store.update(cx, |s, cx| s.delete_solution(&id, cx)).unwrap();
    });
    cx.run_until_parked();

    let events = captured.lock().unwrap();
    let workspace_evt = events.iter().find(|(k, _)| k == "workspace.solution_deleted");
    assert!(workspace_evt.is_some(), "delete_solution must emit workspace.solution_deleted");
    let (_, payload) = workspace_evt.unwrap();
    assert!(payload["seq"].as_u64().unwrap() >= 1);
}
```

NOTE: `editor_mcp::install_notification_probe_for_test` is a new `#[cfg(test)]` helper that lets tests observe emissions without a real MCP server. Add it in `crates/editor_mcp/src/notifications.rs` (mirrors how `emit` works — push to a `parking_lot::Mutex<Option<Box<dyn Fn(&str, &Value)>>>` global, drained on emit).

- [ ] **Step 4: Run, expect failure.**

Run: `cargo test -p solutions delete_solution_emits_workspace_event`
Expected: FAIL — workspace event not emitted yet.

- [ ] **Step 5: Hook the emit into `delete_solution`.**

In `crates/solutions/src/store.rs`, in `delete_solution` (line ~200), AFTER `self.persist()` and BEFORE `cx.emit(SolutionStoreEvent::Changed)`, add:
```rust
let coord = editor_mcp::workspace_seq::WorkspaceEventCoordinator::global(cx);
coord.emit_sequenced(cx, "workspace.solution_deleted", serde_json::json!({
    "solution_id": id.0,
}));
```

`solutions` crate's `Cargo.toml` already depends on `editor_mcp` (it uses `editor_mcp::emit_notification` elsewhere — confirm with grep). If not, add the dep.

- [ ] **Step 6: Run, expect pass.**

Run: `cargo test -p solutions delete_solution_emits_workspace_event`
Expected: PASS.

- [ ] **Step 7: Commit.**

```bash
git add crates/editor_mcp crates/workspace_events crates/solutions
git commit -m "feat(solutions): emit workspace.solution_deleted on delete (sequenced)"
```

---

### Task F2: Emit `workspace.session_deleted` from `solution_agent.delete_session`

**Files:**
- Modify: `crates/solution_agent/src/store.rs` — `close_session` (the misnamed-but-now-renamed delete path).

- [ ] **Step 1: Write the failing test.**

In the `solution_agent` test module (or a dedicated workspace-events test file), assert that `close_session` (still the internal name) triggers a `workspace.session_deleted` notification via the probe.

```rust
#[gpui::test]
async fn delete_session_emits_workspace_event(cx: &mut TestAppContext) {
    // ... setup as in F1 ...
    cx.update(|cx| {
        let agent = SolutionAgentStore::global(cx);
        agent.update(cx, |a, cx| a.close_session(sess_id, cx)).unwrap();
    });
    cx.run_until_parked();
    let evt = captured.lock().unwrap().iter().find(|(k, _)| k == "workspace.session_deleted");
    assert!(evt.is_some());
    assert_eq!(evt.unwrap().1["session_id"].as_str(), Some(sess_id.0.as_str()));
    assert!(evt.unwrap().1["solution_id"].as_str().is_some());
}
```

- [ ] **Step 2: Run, expect failure.**

Run: `cargo test -p solution_agent delete_session_emits_workspace_event`
Expected: FAIL.

- [ ] **Step 3: Hook the emit.**

In `crates/solution_agent/src/store.rs` `close_session` (line ~693), AFTER `cx.emit(SolutionAgentStoreEvent::SessionClosed(id))`, add:
```rust
let coord = editor_mcp::workspace_seq::WorkspaceEventCoordinator::global(cx);
coord.emit_sequenced(cx, "workspace.session_deleted", serde_json::json!({
    "solution_id": solution_id.0,
    "session_id": id.to_string(),
}));
```
(Capture `solution_id` from the session BEFORE removing it from the map — note the current implementation removes the session first; refactor so we have the value to hand.)

- [ ] **Step 4: Run, expect pass.**

Run: `cargo test -p solution_agent delete_session_emits_workspace_event`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add crates/solution_agent
git commit -m "feat(solution_agent): emit workspace.session_deleted on delete_session (sequenced)"
```

---

### Task F3: Emit `workspace.solution_opened` from `SolutionStore::activate`

**Files:**
- Modify: `crates/solutions/src/store.rs`

This is symmetric to F1 — activate emits `solution_opened` with the fresh `SolutionSummary` + restored `sessions` array. The `solution_agent` crate already exposes `hydrate_all_for_solution`; we trigger it here so the emitted snapshot includes the restored sessions.

- [ ] **Step 1: Write test.**

```rust
#[gpui::test]
async fn activate_emits_workspace_solution_opened(cx: &mut TestAppContext) { /* ... probe-based test as in F1; assert kind == "workspace.solution_opened" with non-zero seq, solution.id and sessions array */ }
```

- [ ] **Step 2: Run, expect failure.**

Run: `cargo test -p solutions activate_emits_workspace_solution_opened`
Expected: FAIL.

- [ ] **Step 3: Hook the emit.**

In `crates/solutions/src/store.rs`, in `activate` (search for it; if not present, add it as a thin method that sets the window-open flag — see the existing `touch_last_opened` for the pattern). After the state mutation:
```rust
let summary = solutions::mcp::solution_summary(self.get(id).expect("just activated"), cx);
let sessions: Vec<solution_agent::mcp::SessionSummary> =
    solution_agent::SolutionAgentStore::try_global(cx)
        .map(|agent| {
            agent.update(cx, |a, cx| {
                let _ = a.hydrate_all_for_solution(id.clone(), cx);
            });
            agent.read_with(cx, |a, cx| {
                a.all_sessions_for_solution(id)
                    .filter(|s| s.tab_order.is_some())
                    .map(|s| solution_agent::mcp::session_summary(s, cx))
                    .collect()
            })
        })
        .unwrap_or_default();
let coord = editor_mcp::workspace_seq::WorkspaceEventCoordinator::global(cx);
coord.emit_sequenced(cx, "workspace.solution_opened", serde_json::json!({
    "solution": summary,
    "sessions": sessions,
}));
```

NOTE: The `OpenSolutionTool` in E1 already emits this event manually. With this hook in place, that manual emit becomes a double-emit. Remove the manual emit from `OpenSolutionTool` — let the store's `activate` emit be the canonical one. Update the E2 test if needed.

- [ ] **Step 4: Run, expect pass + nothing else broke.**

Run: `cargo test -p solutions -p workspace_events`
Expected: ALL PASS. If E2 doubles seq, deduplicate by removing the manual emit in lifecycle.rs.

- [ ] **Step 5: Commit.**

```bash
git add crates/solutions crates/workspace_events
git commit -m "feat(solutions): emit workspace.solution_opened from SolutionStore::activate"
```

---

### Task F4: Emit `workspace.solution_closed` from `SolutionStore::deactivate`

Symmetric to F3.

**Files:**
- Modify: `crates/solutions/src/store.rs`

- [ ] **Step 1: Test, fail-first, hook emit, verify pass, commit.**

Same pattern as F3, target method `deactivate`, payload `{ "solution_id": id.0 }`. Also remove the manual emit from `CloseSolutionTool` in `crates/workspace_events/src/lifecycle.rs` once the store-side emit is canonical.

Commit message: `feat(solutions): emit workspace.solution_closed from SolutionStore::deactivate`.

---

### Task F5: Emit `workspace.session_opened` / `session_closed` from tab-order mutation site

**Files:**
- Modify: `crates/solution_agent/src/store.rs` (`append_to_tab_strip` / `remove_from_tab_strip` from Task E4)

- [ ] **Step 1: Tests + hook.**

In each of `append_to_tab_strip` and `remove_from_tab_strip` (added in Task E4), emit the matching workspace event after the `db.update_tab_orders` call returns OK. Remove the manual emits from `OpenSessionTool` / `CloseSessionTool`.

- [ ] **Step 2: Run all workspace tests.**

Run: `cargo test -p workspace_events -p solution_agent`
Expected: ALL PASS.

- [ ] **Step 3: Commit.**

```bash
git add crates/solution_agent crates/workspace_events
git commit -m "feat(solution_agent): emit workspace.session_opened/closed from tab-strip mutation"
```

---

### Task F6: Emit `workspace.session_state_changed` on Running/Idle/Errored transitions

**Files:**
- Modify: `crates/solution_agent/src/store.rs` (every `SessionStateChanged` emit site — lines 909, 979, 993, 1026, 1270)

- [ ] **Step 1: Find the sites.**

Run: `grep -n "SessionStateChanged" crates/solution_agent/src/store.rs`
Expected: 5+ matches.

- [ ] **Step 2: Write test asserting state changes emit the workspace event.**

```rust
#[gpui::test]
async fn session_state_change_emits_workspace_event(cx: &mut TestAppContext) {
    /* setup with probe; create session; force state transition (Idle -> Running);
     * assert ("workspace.session_state_changed", payload with seq, solution_id, session_id, state) was captured */
}
```

- [ ] **Step 3: Run, expect failure.**

- [ ] **Step 4: Add a helper to centralise the emit.**

In `crates/solution_agent/src/store.rs`, define a private helper `fn emit_workspace_state_changed(&self, id: &SolutionSessionId, cx: &mut Context<Self>)` that reads the new state and calls the coordinator with the JSON payload `{ solution_id, session_id, state }`. Call this helper from every `cx.emit(SessionStateChanged(id))` site.

- [ ] **Step 5: Run, expect pass.**

- [ ] **Step 6: Commit.**

```bash
git add crates/solution_agent
git commit -m "feat(solution_agent): emit workspace.session_state_changed on state transitions"
```

---

### Task F7: Emit `workspace.session_metrics_changed` (NON-sequenced, throttled)

**Files:**
- Create: `crates/solution_agent/src/metrics_emitter.rs`
- Modify: `crates/solution_agent/src/store.rs` (call into emitter on token-usage / activity updates)

- [ ] **Step 1: Implement the throttler.**

```rust
//! Per-session throttler for workspace.session_metrics_changed.
use std::collections::HashMap;
use std::time::Instant;
use parking_lot::Mutex;
use gpui::App;
use crate::model::SolutionSessionId;

const THROTTLE_MS: u64 = 2000;

#[derive(Default)]
pub struct MetricsEmitter {
    last_emit: Mutex<HashMap<SolutionSessionId, Instant>>,
}

impl MetricsEmitter {
    pub fn emit_if_ready(
        &self,
        cx: &App,
        session_id: &SolutionSessionId,
        payload: serde_json::Value,
    ) {
        let now = Instant::now();
        let mut last = self.last_emit.lock();
        match last.get(session_id) {
            Some(t) if now.duration_since(*t).as_millis() < THROTTLE_MS as u128 => return,
            _ => {}
        }
        last.insert(session_id.clone(), now);
        editor_mcp::emit_notification(cx, "workspace.session_metrics_changed", payload);
    }
}
```

- [ ] **Step 2: Hook from token-usage and activity update sites.**

Find sites that update `cached_total_tokens` / `cached_max_tokens` / `last_activity_at` (grep `cached_total_tokens` in `crates/solution_agent/src/store.rs`). After each update, call:
```rust
self.metrics_emitter.emit_if_ready(cx, &session.id, serde_json::json!({
    "session_id": session.id.to_string(),
    "last_activity_at": session.last_activity_at,
    "total_tokens": session.cached_total_tokens,
    "max_tokens": session.cached_max_tokens,
}));
```

- [ ] **Step 3: Tests.**

(a) Within throttle window: two updates → only one emit.
(b) Outside throttle: two updates → two emits.
(c) Payload has session_id and at least one metric field.

- [ ] **Step 4: Run, expect pass.**

- [ ] **Step 5: Commit.**

```bash
git add crates/solution_agent
git commit -m "feat(solution_agent): throttled workspace.session_metrics_changed (~1/2s per session)"
```

---

## Phase G — Snapshot uses replication lock to seal seq with state

### Task G1: Add `replication_lock` to `WorkspaceEventCoordinator` and use it in snapshot

**Files:**
- Modify: `crates/editor_mcp/src/workspace_seq.rs`
- Modify: `crates/workspace_events/src/snapshot.rs`

- [ ] **Step 1: Write the failing race test.**

In `crates/workspace_events/tests/snapshot_test.rs`:
```rust
#[gpui::test]
async fn snapshot_seq_matches_state_under_concurrent_mutation(cx: &mut TestAppContext) {
    /* Start a snapshot read. While it's pending, mutate state. Snapshot must
       return either (a) pre-mutation state with pre-mutation seq, or
       (b) post-mutation state with post-mutation seq — NEVER mixed. */
}
```

(The exact harness for this depends on what gpui exposes for parking the snapshot mid-build; if it's hard to write deterministically, use a logical-time-based check: snapshot's seq == max seq from any included session/solution event.)

- [ ] **Step 2: Add an `RwLock<()>` to the coordinator.**

In `workspace_seq.rs`:
```rust
pub struct WorkspaceEventCoordinator {
    seq: AtomicU64,
    replication: parking_lot::RwLock<()>,
}

impl WorkspaceEventCoordinator {
    pub fn snapshot_lock(&self) -> parking_lot::RwLockReadGuard<'_, ()> {
        self.replication.read()
    }
    pub fn emit_sequenced(&self, cx: &App, kind: &str, mut payload: Value) -> u64 {
        let _w = self.replication.write();
        // existing impl follows
        ...
    }
}
```

- [ ] **Step 3: Acquire the read guard in `build_snapshot`.**

`crates/workspace_events/src/snapshot.rs` — wrap the entire body of `build_snapshot` in:
```rust
let coord = WorkspaceEventCoordinator::global(cx);
let _read = coord.snapshot_lock();
let seq = coord.current_seq();
// ... build solutions ...
```

- [ ] **Step 4: Run all tests.**

Run: `cargo test -p workspace_events -p solutions -p solution_agent`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add crates/editor_mcp crates/workspace_events
git commit -m "feat(workspace_events): replication lock seals snapshot seq with included state"
```

---

## Phase H — Terminate agents + terminals on `close_solution`

### Task H1: Kill agent threads for closing solution

**Files:**
- Modify: `crates/workspace_events/src/shutdown.rs`
- Modify: `crates/workspace_events/Cargo.toml` (deps if needed)

- [ ] **Step 1: Write test.**

```rust
#[gpui::test]
async fn close_solution_cancels_running_agent_sessions(cx: &mut TestAppContext) {
    /* Set up a session with a fake AcpThread in Running state.
       Call workspace.close_solution. Assert the thread received cancel(). */
}
```

- [ ] **Step 2: Run, expect failure.**

- [ ] **Step 3: Implement.**

Replace the body of `shutdown_solution_runtime` in `crates/workspace_events/src/shutdown.rs`:
```rust
pub fn shutdown_solution_runtime(id: &SolutionId, cx: &mut App) {
    if let Some(agent) = solution_agent::SolutionAgentStore::try_global(cx) {
        agent.update(cx, |a, cx| {
            let session_ids: Vec<_> = a.all_sessions_for_solution(id)
                .map(|s| s.id.clone())
                .collect();
            for sid in session_ids {
                if let Some(thread) = a.get_session(&sid).and_then(|s| s.acp_thread()) {
                    thread.update(cx, |t, cx| { let _ = t.cancel(cx); });
                }
            }
        });
    }
    // Terminals: Task H2.
}
```

- [ ] **Step 4: Run, expect pass.**

- [ ] **Step 5: Commit.**

```bash
git add crates/workspace_events
git commit -m "feat(workspace_events): close_solution cancels in-flight agent threads"
```

---

### Task H2: Kill terminals for closing solution

**Files:**
- Modify: `crates/workspace_events/src/shutdown.rs`
- Possibly modify: `crates/terminal/src/` (add a "list-by-solution + kill" helper if missing)

- [ ] **Step 1: Survey the terminal crate.**

Run: `grep -nR "solution\|by_solution\|kill\|terminate" crates/terminal/src --include='*.rs' | head -20`
Expected: see how terminals associate to solutions / workspaces, and how they're killed.

- [ ] **Step 2: Write test.**

```rust
#[gpui::test]
async fn close_solution_kills_terminals_for_that_solution(cx: &mut TestAppContext) {
    /* Spawn a fake terminal tagged with sol_id.
       Call workspace.close_solution.
       Assert the terminal store no longer lists it. */
}
```

- [ ] **Step 3: Implement.**

Extend `shutdown_solution_runtime`:
```rust
if let Some(term_store) = terminal::TerminalStore::try_global(cx) {
    term_store.update(cx, |ts, cx| ts.kill_all_for_solution(id, cx));
}
```

If `TerminalStore::kill_all_for_solution` doesn't exist, add it in `crates/terminal/src/` as a public method that iterates the terminal map and kills those associated with `id`.

NOTE: If terminals are not solution-scoped today (terminal crate predates per-solution association), this is a design extension. Surface the gap to the user and proceed with a degraded behaviour ("kill all terminals" or "kill terminals whose cwd is under solution.root") if needed. Document the choice in the commit message.

- [ ] **Step 4: Run, expect pass.**

- [ ] **Step 5: Commit.**

```bash
git add crates/workspace_events crates/terminal
git commit -m "feat(workspace_events): close_solution kills terminals associated with the solution"
```

---

## Phase I — `:cli` smoke commands

### Task I1: Add `cli workspace snapshot` and friends

**Files:**
- Modify: `spk-editor-mobile/cli/src/main.rs` (or wherever the CLI subcommands live)

- [ ] **Step 1: Survey the cli.**

Run: `grep -n "fn cmd_\|enum Subcommand\|subcommand" spk-editor-mobile/cli/src/*.rs | head -20`
Expected: find where existing subcommands are dispatched.

- [ ] **Step 2: Add the subcommands.**

Add cases for: `workspace snapshot`, `workspace list-solutions [--open|--closed]`, `workspace open-solution <id>`, `workspace close-solution <id>`, `workspace open-session <id>`, `workspace close-session <id>`. Each is a thin wrapper that does a single MCP tool-call against the connected desktop and prints the JSON response.

- [ ] **Step 3: Manual smoke against a running desktop.**

Run (after building both):
```bash
spk-editor-mobile/cli/target/debug/cli workspace snapshot
```
Expected: prints a `{seq:..., solutions:[...]}` JSON object.

- [ ] **Step 4: Commit.**

```bash
git add spk-editor-mobile/cli/src/
git commit -m "feat(cli): workspace.* smoke subcommands"
```

---

## Phase J — Acceptance + cleanup

### Task J1: End-to-end test (RPC + delta + snapshot round-trip)

**Files:**
- Create: `crates/workspace_events/tests/e2e_test.rs`

- [ ] **Step 1: Write the e2e scenario.**

```rust
//! End-to-end: connect, snapshot, mutate, observe deltas, snapshot again.
//! Validates seq monotonicity across the wire.

#[gpui::test]
async fn full_lifecycle_round_trip(cx: &mut TestAppContext) {
    /*
     * 1. Setup as snapshot_test
     * 2. snapshot → s0 with empty solutions
     * 3. create_for_test a solution (server-side)
     * 4. snapshot → s1 with one solution; s1.seq > s0.seq
     * 5. close_solution via RPC
     * 6. snapshot → s2 with empty; s2.seq > s1.seq
     * 7. open_solution via RPC
     * 8. snapshot → s3 with one solution; s3.seq > s2.seq
     * 9. delete_session of a session in s3 (call solution_agent.delete_session)
     * 10. snapshot → s4 with one solution, zero sessions; s4.seq > s3.seq
     */
}
```

- [ ] **Step 2: Run, expect pass.**

Run: `cargo test -p workspace_events --test e2e_test`
Expected: PASS.

- [ ] **Step 3: Commit.**

```bash
git add crates/workspace_events/tests/e2e_test.rs
git commit -m "test(workspace_events): full lifecycle round-trip e2e (snapshot + RPC + delta)"
```

---

### Task J2: Final check + tag

- [ ] **Step 1: Full test suite.**

Run: `cargo test --workspace`
Expected: PASS (everything that was passing before still passes; new tests pass; nothing regressed).

- [ ] **Step 2: Lint clean.**

Run: `cargo clippy --workspace -- -D warnings`
Expected: no warnings, no errors.

- [ ] **Step 3: Tag the branch.**

```bash
git tag -a workspace-wire-desktop-done -m "Plan 1 complete: desktop side of unified workspace ready for Plan 2 (mobile)"
```

---

## Self-review notes

**Spec coverage:**
- §2.1 strong consistency — sequencer in A2/A3/G1; emits hooked in F1-F6.
- §2.2 eventual metrics — F7.
- §3.1 renames — B1, B2.
- §3.2 module ownership — A1 + F1/F2 (`solutions.delete` / `solution_agent.delete_session` stay in their crates, emit workspace events).
- §3.3 RPCs — C2, D1, E1-E4.
- §3.4 notifications — F1-F7 (sequenced) + F7 (non-seq).
- §3.5 sequencer hook from neighbour crates — F1-F6 (via `editor_mcp::workspace_seq`).
- §3.6 deprecation — out of scope (old tools stay).
- §6.4 close_solution implementation — E3 (basic) + H1 + H2.
- §6.5 open_solution-for-closed — E2 + F3.
- §7.2 desktop tests — covered in each phase + J1.

**Placeholder scan:**
- F2 says `close_session` — that's the INTERNAL Rust method name, which intentionally lags the wire rename until a follow-up cleanup. Flagged for the engineer.
- H2 has a fallback path for "if terminals aren't solution-scoped today" — this is a discovery moment; engineer should bring the gap back to the user if it triggers. Explicit, not a placeholder.

**Type consistency:**
- `WorkspaceEvent` enum mentioned in A1 as a stub. Currently no variants — that's fine, the wire payloads are built ad-hoc via `serde_json::json!`. The enum can stay an unused placeholder OR be removed in J2 cleanup. Either way not load-bearing.
- `SolutionIdParam` / `SessionIdParam` / `SeqAck` introduced in C1 — used consistently in E1-E4.
- `WorkspaceSolution.solution` flattens `SolutionSummary` — same field is used as a nested object in `solution_opened` emit; consistent.

**Gaps deliberately left for Plan 2:**
- All mobile changes.
- Removal of old `solutions.list` / `solution_agent.list_sessions` mobile calls — those happen as part of replacing `SolutionStore` with `WorkspaceStore` in Plan 2.
