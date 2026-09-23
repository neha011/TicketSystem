import fc from 'fast-check';
import { describe, expect, it } from 'vitest';
import { TICKET_PRIORITIES, TICKET_STATUSES } from './types';

/**
 * Smoke check that the fast-check harness is wired into Vitest, so the later
 * property tests (Properties 21–25) have a working runner. Also pins the enum
 * value sets the UI relies on against the backend contract.
 */
describe('wire-format enums', () => {
  it('should round-trip every status and priority through JSON unchanged', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(...TICKET_STATUSES),
        fc.constantFrom(...TICKET_PRIORITIES),
        (status, priority) => {
          const parsed = JSON.parse(JSON.stringify({ status, priority }));
          return parsed.status === status && parsed.priority === priority;
        },
      ),
      { numRuns: 25 },
    );
  });

  it('should match the statuses and priorities defined by the backend', () => {
    expect(TICKET_STATUSES).toEqual(['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED']);
    expect(TICKET_PRIORITIES).toEqual(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']);
  });
});
