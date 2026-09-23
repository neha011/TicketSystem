import type { ApiError } from '@/lib/api/apiClient';

/**
 * The state a submittable form owns while the user fills, submits, and corrects
 * it.
 *
 * The two halves are deliberately independent:
 *
 * - `values` is the single source of truth for what the user has typed. It is
 *   the *only* thing a submission reads, and — critically — a validation
 *   failure never touches it (Requirement 11.3). The user's work survives a
 *   rejected submit so they can correct and resubmit.
 * - `errors` is a derived annotation layer: field name → the message to show
 *   beside that field. It is rebuilt from a 400's `fieldErrors` and discarded
 *   on the next successful submit, but it can never stand in for or overwrite a
 *   value.
 *
 * Keeping the map keyed by field name (rather than, say, an array parallel to
 * the inputs) means applying a 400 is a pure rebuild of `errors` alone, which
 * is what makes "values are preserved" hold by construction.
 */
export interface FormState<TValues extends Record<string, string>> {
  /** Exactly what the user has entered; whitespace, casing, and all. */
  readonly values: TValues;
  /**
   * Field name → validation message for the fields the backend rejected.
   * Empty whenever the form has no outstanding validation errors. Keys are a
   * subset of `values` keys only insofar as the backend named known fields;
   * unknown field names from a 400 are ignored (see {@link formReducer}).
   */
  readonly errors: Readonly<Record<string, string>>;
}

/**
 * The closed set of things that can happen to a form.
 *
 * - `CHANGE_FIELD` — the user edited one input. Updates that value and clears
 *   only that field's stale error, so a corrected field stops showing its old
 *   complaint without disturbing the others.
 * - `SUBMISSION_FAILED` — a submit came back 400. Adds/replaces error
 *   annotations from the payload and does nothing else.
 * - `SUBMISSION_SUCCEEDED` — a submit came back 2xx. The only action that
 *   resets the form, back to a caller-supplied clean slate.
 */
export type FormAction<TValues extends Record<string, string>> =
  | { type: 'CHANGE_FIELD'; field: keyof TValues & string; value: string }
  | { type: 'SUBMISSION_FAILED'; error: ApiError }
  | { type: 'SUBMISSION_SUCCEEDED'; initialValues: TValues };

/** Builds the pristine state a form starts (and, on success, restarts) from. */
export function initFormState<TValues extends Record<string, string>>(
  initialValues: TValues,
): FormState<TValues> {
  return { values: initialValues, errors: {} };
}

/**
 * Turns an {@link ApiError}'s `fieldErrors` into the `errors` annotation map.
 *
 * Only fields the form actually owns are kept: a 400 that names a field the UI
 * has no input for is dropped rather than surfaced as an orphan error, and the
 * first reason wins if the backend reports the same field twice. The result is
 * always a fresh object, so callers never alias the error list.
 */
function toErrorMap<TValues extends Record<string, string>>(
  error: ApiError,
  knownFields: ReadonlySet<string>,
): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const { field, reason } of error.fieldErrors) {
    if (knownFields.has(field) && !(field in errors)) {
      errors[field] = reason;
    }
  }
  return errors as Record<keyof TValues & string, string>;
}

/**
 * The pure reducer that owns a form's entered values and error annotations.
 *
 * Its defining guarantee (Requirement 11.3): a `SUBMISSION_FAILED` action —
 * i.e. a 400 Bad Request after submit — returns state whose `values` are
 * *reference-identical* to the input state's `values`. No field is cleared,
 * defaulted, or reset by a failure; the sole delta is a rebuilt `errors` map.
 * State is reset only by `SUBMISSION_SUCCEEDED`.
 *
 * The function never mutates its arguments; every branch returns either the
 * same state object or a new one, so it is safe to use with React `useReducer`
 * and to reason about under property tests.
 *
 * @param state the current form state
 * @param action what happened
 * @returns the next form state
 */
export function formReducer<TValues extends Record<string, string>>(
  state: FormState<TValues>,
  action: FormAction<TValues>,
): FormState<TValues> {
  switch (action.type) {
    case 'CHANGE_FIELD': {
      const values: TValues = { ...state.values, [action.field]: action.value };
      // Clear only this field's stale error; leave every other annotation in
      // place so unrelated fields keep showing their outstanding complaints.
      if (action.field in state.errors) {
        const errors = { ...state.errors };
        delete errors[action.field];
        return { values, errors };
      }
      return { values, errors: state.errors };
    }

    case 'SUBMISSION_FAILED': {
      // Rebuild the annotation layer from the 400 payload and return the same
      // `values` reference untouched — this is Requirement 11.3 by construction.
      const knownFields = new Set(Object.keys(state.values));
      return { values: state.values, errors: toErrorMap<TValues>(action.error, knownFields) };
    }

    case 'SUBMISSION_SUCCEEDED': {
      // The one action that discards the user's input, deliberately, because
      // the submit was accepted.
      return initFormState(action.initialValues);
    }

    default: {
      // Exhaustiveness guard: adding a new action type without a case is a
      // compile error here, and an unknown action at runtime leaves state as-is.
      return state;
    }
  }
}
