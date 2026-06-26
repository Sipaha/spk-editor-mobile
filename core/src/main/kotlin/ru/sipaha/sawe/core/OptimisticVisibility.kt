package ru.sipaha.sawe.core

/**
 * Drop optimistic entries whose send has already been echoed back by the
 * server — i.e. whose `clientSendId` already appears (as `clientSendId` or in
 * `clientSendIds`) on a server entry or a queued bundle. This makes the
 * optimistic→echo handoff atomic from the UI's perspective: the optimistic
 * bubble disappears in the SAME derivation that surfaces its echo, with no
 * one-frame gap (the flicker bug) and no transient duplicate (same csid on
 * two bubbles would also collide in the LazyColumn key space).
 *
 * Optimistic entries always carry a `clientSendId` (invariant); an optimistic
 * entry with a null csid is kept (can't be matched, shouldn't happen).
 */
fun visibleOptimistic(
    optimistic: List<EntrySummary>,
    echoedCsids: Set<Long>,
): List<EntrySummary> =
    optimistic.filter { it.clientSendId == null || it.clientSendId !in echoedCsids }
