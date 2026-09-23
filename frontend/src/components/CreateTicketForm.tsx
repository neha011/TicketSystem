'use client';

import { useReducer, type FormEvent } from 'react';
import { apiClient, isApiError } from '@/lib/api/apiClient';
import { queryKeys } from '@/lib/api/queryKeys';
import { useConfirmedMutation } from '@/lib/api/useConfirmedMutation';
import type {
  CreateTicketRequest,
  TicketPriority,
  TicketSummaryResponse,
} from '@/lib/api/types';
import { TICKET_PRIORITIES } from '@/lib/api/types';
import { formReducer, initFormState } from '@/lib/forms/formReducer';
import { ErrorBanner } from './ErrorBanner';

/** The raw string-valued fields the create form owns. */
interface CreateTicketFormValues extends Record<string, string> {
  title: string;
  description: string;
  priority: string;
  assignee: string;
}

const EMPTY_VALUES: CreateTicketFormValues = {
  title: '',
  description: '',
  priority: 'MEDIUM',
  assignee: '',
};

/** Formats an ISO-8601 instant for display, falling back to the raw value. */
function formatInstant(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toLocaleString();
}

/**
 * Builds the request body from the entered values.
 *
 * Optional fields are omitted when blank so the backend applies its own
 * handling; validity is the backend's decision, so nothing is rejected here
 * (Req 10.1). `priority` is sent as-is — an out-of-range value is the backend's
 * 400 to make.
 */
function toRequest(values: CreateTicketFormValues): CreateTicketRequest {
  const body: CreateTicketRequest = {
    title: values.title,
    priority: values.priority as TicketPriority,
  };
  if (values.description.length > 0) {
    body.description = values.description;
  }
  if (values.assignee.length > 0) {
    body.assignee = values.assignee;
  }
  return body;
}

/**
 * The create-ticket form.
 *
 * On success it displays the created ticket's identifier, title, priority,
 * status, and creation timestamp exactly as the backend returned them
 * (Req 1.7) — the values shown come from the server response, never from the
 * submitted form, because the write runs through {@link useConfirmedMutation}.
 * A 400 preserves every entered value and only annotates the failing fields
 * (Req 11.3), which is guaranteed by {@link formReducer}.
 */
export function CreateTicketForm() {
  const [state, dispatch] = useReducer(formReducer<CreateTicketFormValues>, EMPTY_VALUES, initFormState);

  const mutation = useConfirmedMutation<TicketSummaryResponse, unknown, CreateTicketRequest>({
    mutationFn: (body) => apiClient.createTicket(body),
    invalidates: [queryKeys.ticketLists()],
    onSuccess: () => {
      dispatch({ type: 'SUBMISSION_SUCCEEDED', initialValues: EMPTY_VALUES });
    },
    onError: (error) => {
      if (isApiError(error) && error.status === 400) {
        dispatch({ type: 'SUBMISSION_FAILED', error });
      }
    },
  });

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate(toRequest(state.values));
  }

  const created = mutation.isSuccess ? mutation.data : null;

  return (
    <div>
      <form onSubmit={handleSubmit} noValidate>
        <div>
          <label htmlFor="create-title">Title</label>
          <input
            id="create-title"
            name="title"
            value={state.values.title}
            aria-invalid={state.errors.title !== undefined}
            aria-describedby={state.errors.title !== undefined ? 'create-title-error' : undefined}
            onChange={(event) =>
              dispatch({ type: 'CHANGE_FIELD', field: 'title', value: event.target.value })
            }
          />
          {state.errors.title !== undefined && (
            <p id="create-title-error" role="alert" style={{ color: 'var(--color-error)' }}>
              {state.errors.title}
            </p>
          )}
        </div>

        <div>
          <label htmlFor="create-description">Description</label>
          <textarea
            id="create-description"
            name="description"
            value={state.values.description}
            aria-invalid={state.errors.description !== undefined}
            aria-describedby={
              state.errors.description !== undefined ? 'create-description-error' : undefined
            }
            onChange={(event) =>
              dispatch({ type: 'CHANGE_FIELD', field: 'description', value: event.target.value })
            }
          />
          {state.errors.description !== undefined && (
            <p id="create-description-error" role="alert" style={{ color: 'var(--color-error)' }}>
              {state.errors.description}
            </p>
          )}
        </div>

        <div>
          <label htmlFor="create-priority">Priority</label>
          <select
            id="create-priority"
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
            <p id="create-priority-error" role="alert" style={{ color: 'var(--color-error)' }}>
              {state.errors.priority}
            </p>
          )}
        </div>

        <div>
          <label htmlFor="create-assignee">Assignee</label>
          <input
            id="create-assignee"
            name="assignee"
            value={state.values.assignee}
            aria-invalid={state.errors.assignee !== undefined}
            aria-describedby={
              state.errors.assignee !== undefined ? 'create-assignee-error' : undefined
            }
            onChange={(event) =>
              dispatch({ type: 'CHANGE_FIELD', field: 'assignee', value: event.target.value })
            }
          />
          {state.errors.assignee !== undefined && (
            <p id="create-assignee-error" role="alert" style={{ color: 'var(--color-error)' }}>
              {state.errors.assignee}
            </p>
          )}
        </div>

        <button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? 'Creating…' : 'Create ticket'}
        </button>
      </form>

      {/* A 400 is rendered inline per field; other failures use the single banner. */}
      {mutation.isError && !(isApiError(mutation.error) && mutation.error.status === 400) && (
        <ErrorBanner error={mutation.error} />
      )}

      {created !== null && (
        <section aria-labelledby="create-result-heading" role="status">
          <h3 id="create-result-heading">Ticket created</h3>
          <dl>
            <dt>Identifier</dt>
            <dd>{created.id}</dd>
            <dt>Title</dt>
            <dd>{created.title}</dd>
            <dt>Priority</dt>
            <dd>{created.priority}</dd>
            <dt>Status</dt>
            <dd>{created.status}</dd>
            <dt>Created</dt>
            <dd>{formatInstant(created.createdAt)}</dd>
          </dl>
        </section>
      )}
    </div>
  );
}
