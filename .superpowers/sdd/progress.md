# Phase 6 — Kotlin mobile delta client + rebrand (spk-editor-mobile)

Plan: ../../sawe/docs/superpowers/plans/2026-06-26-phase6-mobile-delta-client.md
Spec: ../../sawe/docs/superpowers/specs/2026-06-26-mobile-delta-sync-design.md §Client
Branch: phase6-mobile-delta-sync (off main). Commit-per-task. NEVER push. NO Co-Authored-By.
BASE (phase start): main @ 40b3b7d

Execution: subagent-driven-development (TDD implementer + per-task opus review + whole-phase opus review).

Task order (recommended, pending user confirm rebrand-first vs delta-first):
- Task R : app-identity rebrand → "Sawe Mobile" / ru.sipaha.sawe.* + legacy scheme выпил — mechanical — IN PROGRESS
- Task 1 : :core delta DTOs (GetSessionChangesResult, epoch/currentSeq on GetSessionResult) + RemoteClient.getSessionChanges — TDD — PENDING
- Task 2 : pure delta applier applySessionDelta in :core — TDD — PENDING
- Task 3 : CachedSessionHistory stores (epoch, lastSeq), schemaVersion 2 — PENDING
- Task 4 : cache-first open + delta applier wired into SessionDetailStore (single-writer) — PENDING
- Task 5 : push events → debounced poll triggers; delete resumeSession/fetchAndReplaceEntry/heal/resync; sweep server-protocol legacy — PENDING
- Final  : whole-phase opus review + device-verify handoff — PENDING
