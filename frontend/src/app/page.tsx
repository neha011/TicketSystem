import { TicketListView } from './tickets/TicketListView';

/**
 * The list is driven entirely by a client-side TanStack Query against the
 * authenticated API, so there is nothing to prerender at build time. Marking
 * the route dynamic keeps Next.js from attempting to statically generate a view
 * whose data only exists per-request.
 */
export const dynamic = 'force-dynamic';

/**
 * Ticket list route. The interactive list — discriminated loading / error /
 * empty / populated states, pagination, status filter, and keyword search —
 * lives in the client {@link TicketListView} component.
 */
export default function TicketListPage() {
  return <TicketListView />;
}
