import type { TicketStatus } from '@/lib/api/types';

/**
 * The ticket status state machine, mirrored on the client.
 *
 * This table is a *duplicate* of the backend's authoritative
 * `TicketStatus.ALLOWED` map (design.md, State Machine Design). It exists only
 * to drive UI affordances — which transitions to offer, and when to hide the
 * control entirely for a terminal state (Req 8.1, 8.6). The backend re-checks
 * every transition, so a client mirror that drifts stale can at worst offer a
 * target the server then rejects with a 409; it can never authorise a
 * transition the server forbids.
 *
 *   OPEN        → { IN_PROGRESS, CANCELLED }
 *   IN_PROGRESS → { RESOLVED, CANCELLED }
 *   RESOLVED    → { CLOSED }
 *   CLOSED      → {}                 (terminal)
 *   CANCELLED   → {}                 (terminal)
 *
 * Because no state lists itself as a target, a same-status transition is never
 * offered — matching the backend, where same-status is a 409 conflict, not a
 * no-op (Req 8.4).
 */
const ALLOWED_TARGETS: Readonly<Record<TicketStatus, readonly TicketStatus[]>> = {
  OPEN: ['IN_PROGRESS', 'CANCELLED'],
  IN_PROGRESS: ['RESOLVED', 'CANCELLED'],
  RESOLVED: ['CLOSED'],
  CLOSED: [],
  CANCELLED: [],
} as const;

/**
 * The statuses a ticket in {@code from} may move to next, as an affordance.
 *
 * Returns a fresh array so callers cannot mutate the shared table. The order
 * mirrors the backend row order for a stable, predictable control layout.
 */
export function permittedTargets(from: TicketStatus): TicketStatus[] {
  return [...ALLOWED_TARGETS[from]];
}

/**
 * True when {@code status} has no outgoing transitions (CLOSED or CANCELLED).
 *
 * The transition control is hidden entirely for a terminal state rather than
 * shown empty, so the UI presents no dead affordance (Req 8.6).
 */
export function isTerminal(status: TicketStatus): boolean {
  return ALLOWED_TARGETS[status].length === 0;
}

/**
 * True iff moving from {@code from} to {@code to} is offered by the table.
 *
 * Pure mirror of the backend decision; kept for symmetry and for tests that
 * assert the client table matches the documented matrix.
 */
export function isPermitted(from: TicketStatus, to: TicketStatus): boolean {
  return ALLOWED_TARGETS[from].includes(to);
}
