import { CreateTicketForm } from '@/components/CreateTicketForm';

/**
 * Ticket creation route. Renders the create form, which on success shows the
 * created ticket's id, title, priority, status, and creation timestamp
 * (Req 1.7).
 */
export default function CreateTicketPage() {
  return (
    <section aria-labelledby="create-ticket-heading">
      <h2 id="create-ticket-heading">Create a ticket</h2>
      <CreateTicketForm />
    </section>
  );
}
