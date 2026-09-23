import { TicketEditArea } from '@/components/TicketEditArea';
import { TicketDetailView } from './TicketDetailView';

/**
 * Ticket detail route.
 *
 * It mounts BOTH surfaces built in parallel for this ticket:
 *  - the read-only detail view with the comment thread (task 17.3,
 *    {@link TicketDetailView}), which owns the ticket-title heading
 *    (`ticket-detail-heading`); and
 *  - the editing surface (task 17.5, {@link TicketEditArea}): the field-edit
 *    form and the status transition control.
 *
 * The outer landmark is labelled by a route-level heading with its own id so
 * there is exactly one `ticket-detail-heading` on the page (the one inside
 * {@link TicketDetailView}); the two headings never collide.
 */
export default function TicketDetailPage({ params }: { params: { id: string } }) {
  return (
    <section aria-labelledby="ticket-detail-route-heading">
      <h1 id="ticket-detail-route-heading">Ticket {params.id}</h1>
      <TicketDetailView ticketId={params.id} />
      <TicketEditArea ticketId={params.id} />
    </section>
  );
}
