'use client';

import { useReducer, type FormEvent } from 'react';
import { apiClient, isApiError } from '@/lib/api/apiClient';
import { queryKeys } from '@/lib/api/queryKeys';
import { useConfirmedMutation } from '@/lib/api/useConfirmedMutation';
import type {
  TicketDetailResponse,
  TicketPriority,
  UpdateTicketRequest,
} from '@/lib/api/types';
import { TICKET_PRIORITIES } from '@/lib/api/types';
import { formReducer, initFormState } from '@/lib/forms/formReducer';
import { ErrorBanner } from './ErrorBanner';

/** The raw string-valued fields the edit form owns. */
interface EditTicketFormValues extends Record<string, string> {
  title: string;
  description: string;
  priority: string;
  assignee: string;
}

export interface EditTicketFormProps {
  /**
   * The last backend-confirmed ticket. Editable fields seed from it, and it is
   * the *only* source for the "current values" display — never the in-flight
   * edits (Req 4.8).
   */
  ticket: TicketDetailResponse;
}

function toFormValues(ticket: TicketDetailResponse): EditTicketFormValues {
  return {
    title: ticket.title,
    description: ticket.description ?? '',
    priority: ticket.priority,
    assignee: ticket.assignee ?? '',
  };
}

/**
 * Builds the partial-update body.
 *
 * `assignee` distinguishes absent from explicit-null (Req 4.5 wire contract): a
 * blank input clears the assignee (`null`), a non-blank input sets it. The
 * required `version` is echoed from the confirmed ticket so a stale edit is
 * rejected with 409 (Req 4.6).
 */
function toRequest(values: EditTicketFormValues, version: number): UpdateTicketRequest {
  return {
    title: values.title,
    description: values.description.length > 0 ? values.description : null,
    priority: values.priority as TicketPriority,
    assignee: values.assignee.length > 0 ? values.assignee : null,
    version,
  };
}

/** Formats an ISO-8601 instant for display, falling back to the raw value. */
function formatInstant(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toLocaleString();
}

/**
 * The edit form for a ticket's title, description, priority, and assignee.
 *
 * While a submission is in flight, the "Current values" panel keeps showing the
 * last backend-confirmed values from the {@code ticket} prop and never the
 * submitted edits (Req 4.8) — the write runs through {@link useConfirmedMutation},
 * which cannot write the payload into the cache before the backend confirms it.
 * On success the detail query is invalidated so the confirmed values refresh in
 * place without a full page reload (Req 4.7). A 400 preserves the user's edits
 * and only annotates the failing fields (Req 11.3).
 */
export function EditTicketForm({ ticket }: EditTicketFormProps) {
  const [state, dispatch] = useReducer(
    formReducer<EditTicketFormValues>,
    ticket,
    (t) => initFormState(toFormValues(t)),
  );

  const mutation = useConfirmedMutation<TicketDetailResponse, unknown, UpdateTicketRequest>({
    mutationFn: (body) => apiClient.updateTicket(ticket.id, body),
    invalidates: [queryKeys.ticketDetail(ticket.id), queryKeys.ticketLists()],
    onSuccess: (updated) => {
      // Reset the editable fields to the freshly confirmed values so the form
      // reflects what the backend now holds, without a page reload (Req 4.7).
      dispatch({ type: 'SUBMISSION_SUCCEEDED', initialValues: toFormValues(updated) });
    },
    onError: (error) => {
      if (isApiError(error) && error.status === 400) {
        dispatch({ type: 'SUBMISSION_FAILED', error });
      }
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate(toRequest(state.values, ticket.version));
  }

  return (
    <div>
      {/* Always the last backend-confirmed values — never the in-flight edits. */}
      <section aria-labelledby="edit-current-heading">
        <h3 id="edit-current-heading">Current values</h3>
        <dl>
          <dt>Title</dt>
          <dd>{ticket.title}</dd>
          <dt>Description</dt>
          <dd>{ticket.description ?? '—'}</dd>
          <dt>Priority</dt>
          <dd>{ticket.priority}</dd>
          <dt>Assignee</dt>
          <dd>{ticket.assignee ?? 'Unassigned'}</dd>
          <dt>Last updated</dt>
          <dd>{formatInstant(ticket.updatedAt)}</dd>
        </dl>
      </section>

      <form onSubmit={handleSubmit} noValidate aria-label="Edit ticket">
        <div>
          <label htmlFor="edit-title">Title</label>
          <input
            id="edit-title"
            name="title"
            value={state.values.title}
            aria-invalid={state.errors.title !== undefined}
            aria-describedby={state.errors.title !== undefined ? 'edit-title-error' : undefined}
            onChange={(event) =>
              dispatch({ type: 'CHANGE_FIELD', field: 'title', value: event.target.value })
            }
          />
          {state.errors.title !== undefined && (
            <p id="edit-title-error" role="alert" style={{ color: 'var(--color-error)' }}>
              {state.errors.title}
            </p>
          )}
        </div>

        <div>
          <label htmlFor="edit-description">Description</label>
          <textarea
            id="edit-description"
            name="description"
            value={state.values.description}
            aria-invalid={state.errors.description !== undefined}
            aria-describedby={
              state.errors.description !== undefined ? 'edit-description-error' : undefined
            }
            onChange={(event) =>
              dispatch({ type: 'CHANGE_FIELD', field: 'description', value: event.target.value })
            }
          />
          {state.errors.description !== undefined && (
            <p id="edit-description-error" role="alert" style={{ color: 'var(--color-error)' }}>
              {state.errors.description}
            </p>
          )}
        </div>

        <div>
          <label htmlFor="edit-priority">Priority</label>
          <select
            id="edit-priority"
            name="priority"
            value={state.values.priority}
            aria-invalid={state.errors.priority !== undefined}
            onChange={(event) =>
              dispatch({ type: 'CHANGE_FIELD', field: 'priority', value: event.target.value })
            }
          >
            {TICKET_PRIORITIES.map((priority) => (
              <option key={priority} value={priority}>
                {priority}
              </option>
            ))}
          </select>
          {state.errors.priority !== undefined && (
            <p id="edit-priority-error" role="alert" style={{ color: 'var(--color-error)' }}>
              {state.errors.priority}
            </p>
          )}
        </div>

        <div>
          <label htmlFor="edit-assignee">Assignee</label>
          <input
            id="edit-assignee"
            name="assignee"
            value={state.values.assignee}
            aria-invalid={state.errors.assignee !== undefined}
            aria-describedby={
              state.errors.assignee !== undefined ? 'edit-assignee-error' : undefined
            }
            onChange={(event) =>
              dispatch({ type: 'CHANGE_FIELD', field: 'assignee', value: event.target.value })
            }
          />
          {state.errors.assignee !== undefined && (
            <p id="edit-assignee-error" role="alert" style={{ color: 'var(--color-error)' }}>
              {state.errors.assignee}
            </p>
          )}
        </div>

        <button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Saving…' : 'Save changes'}
        </button>
      </form>

      {mutation.isSuccess && <p role="status">Changes saved.</p>}

      {/* A 400 is rendered inline per field; other failures use the single banner. */}
      {mutation.isError && !(isApiError(mutation.error) && mutation.error.status === 400) && (
        <ErrorBanner error={mutation.error} />
      )}
    </div>
  );
}
