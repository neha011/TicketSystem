'use client';

import { useReducer, type FormEvent } from 'react';
import { isApiError } from '@/lib/api/apiClient';
import { errorMapper } from '@/lib/api/errorMapper';
import { formReducer, initFormState } from '@/lib/forms/formReducer';
import { useAddComment } from './useTicketDetail';

/** The single field this form owns. */
const INITIAL_VALUES = { content: '' } as const;

/**
 * The comment composer for the ticket detail view.
 *
 * On a confirmed submit the mutation folds the backend-returned comment into
 * the detail cache (see {@link useAddComment}), so the new comment appears in
 * the list without a full page reload (Requirement 5.7) and the form resets.
 *
 * On a 400 the entered text is preserved (Requirement 11.3, via
 * {@link formReducer}) and the field-level reason is shown; any other failure
 * surfaces the single status-mapped message from {@link errorMapper}
 * (Requirement 11.9).
 */
export function CommentForm({ ticketId }: { ticketId: string }) {
  const [state, dispatch] = useReducer(formReducer<typeof INITIAL_VALUES>, INITIAL_VALUES, initFormState);
  const mutation = useAddComment(ticketId);

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate(
      { content: state.values.content },
      {
        onSuccess: () => {
          dispatch({ type: 'SUBMISSION_SUCCEEDED', initialValues: INITIAL_VALUES });
        },
        onError: (error) => {
          // A 400 with field errors annotates the field; the reducer preserves
          // the entered text either way.
          if (isApiError(error) && error.status === 400) {
            dispatch({ type: 'SUBMISSION_FAILED', error });
          }
        },
      },
    );
  }

  const fieldError = state.errors.content;
  // A non-400 failure (401/403/404/409/500/network) has no field annotation;
  // show the single status-mapped message instead (Requirement 11.9).
  const requestError =
    mutation.isError && !fieldError && isApiError(mutation.error)
      ? errorMapper(mutation.error)
      : null;

  return (
    <form aria-labelledby="add-comment-heading" onSubmit={handleSubmit}>
      <h4 id="add-comment-heading">Add a comment</h4>
      <label htmlFor="comment-content">Comment</label>
      <textarea
        id="comment-content"
        name="content"
        rows={3}
        value={state.values.content}
        aria-invalid={fieldError !== undefined}
        aria-describedby={fieldError !== undefined ? 'comment-content-error' : undefined}
        disabled={mutation.isPending}
        onChange={(event) =>
          dispatch({ type: 'CHANGE_FIELD', field: 'content', value: event.target.value })
        }
      />
      {fieldError !== undefined ? (
        <p id="comment-content-error" role="alert" style={{ color: 'var(--color-error)' }}>
          {fieldError}
        </p>
      ) : null}
      {requestError !== null ? (
        <p role="alert" style={{ color: 'var(--color-error)' }}>
          {requestError}
        </p>
      ) : null}
      <button type="submit" disabled={mutation.isPending}>
        {mutation.isPending ? 'Adding…' : 'Add comment'}
      </button>
    </form>
  );
}
