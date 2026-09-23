import { describe, expect, it } from 'vitest';
import { ApiError, type ApiErrorKind } from '@/lib/api/apiClient';
import type { ApiFieldError } from '@/lib/api/types';
import { type FormState, formReducer, initFormState } from './formReducer';

/** A representative ticket-creation form shape for the tests. */
interface CreateTicketForm extends Record<string, string> {
  title: string;
  description: string;
  priority: string;
}

function makeState(
  values: CreateTicketForm,
  errors: Record<string, string> = {},
): FormState<CreateTicketForm> {
  return { values, errors };
}

/** Builds an ApiError carrying the given field errors, without any network. */
function validationError(
  fieldErrors: ApiFieldError[],
  kind: ApiErrorKind = 'http',
): ApiError {
  return new ApiError({
    status: 400,
    kind,
    serverMessage: 'Validation failed',
    fieldErrors,
    fallbackMessage: 'The ticket service responded with status 400.',
  });
}

describe('initFormState', () => {
  it('should start from the given values with no errors', () => {
    const state = initFormState<CreateTicketForm>({
      title: 'Login broken',
      description: '',
      priority: 'HIGH',
    });

    expect(state.values).toEqual({ title: 'Login broken', description: '', priority: 'HIGH' });
    expect(state.errors).toEqual({});
  });
});

describe('formReducer / CHANGE_FIELD', () => {
  it('should update only the changed field and leave the others untouched', () => {
    const state = makeState({ title: 'a', description: 'b', priority: 'LOW' });

    const next = formReducer(state, { type: 'CHANGE_FIELD', field: 'title', value: 'new title' });

    expect(next.values).toEqual({ title: 'new title', description: 'b', priority: 'LOW' });
  });

  it('should clear only the edited field error, keeping other field errors', () => {
    const state = makeState(
      { title: 'a', description: 'b', priority: 'LOW' },
      { title: 'must not be blank', priority: 'unknown priority' },
    );

    const next = formReducer(state, { type: 'CHANGE_FIELD', field: 'title', value: 'fixed' });

    expect(next.errors).toEqual({ priority: 'unknown priority' });
  });

  it('should preserve whitespace-laden and empty values verbatim', () => {
    const state = makeState({ title: '', description: '', priority: '' });

    const next = formReducer(state, {
      type: 'CHANGE_FIELD',
      field: 'description',
      value: '   ',
    });

    expect(next.values.description).toBe('   ');
  });
});

describe('formReducer / SUBMISSION_FAILED (Requirement 11.3)', () => {
  it('should preserve every entered value and add only error annotations on a 400', () => {
    const values: CreateTicketForm = {
      title: '  Spaces & <special> chars  ',
      description: 'multi\nline?',
      priority: 'CRITICAL',
    };
    const state = makeState(values);

    const next = formReducer(state, {
      type: 'SUBMISSION_FAILED',
      error: validationError([
        { field: 'title', reason: 'must be 1–200 characters' },
        { field: 'priority', reason: 'invalid priority' },
      ]),
    });

    // Values are untouched — the only delta is the error map.
    expect(next.values).toBe(state.values);
    expect(next.values).toEqual(values);
    expect(next.errors).toEqual({
      title: 'must be 1–200 characters',
      priority: 'invalid priority',
    });
  });

  it('should never clear, default, or reset a value even for fields named in the 400', () => {
    const state = makeState({ title: 'keep me', description: 'keep me too', priority: 'HIGH' });

    const next = formReducer(state, {
      type: 'SUBMISSION_FAILED',
      error: validationError([{ field: 'title', reason: 'bad' }]),
    });

    expect(next.values.title).toBe('keep me');
    expect(next.values.description).toBe('keep me too');
    expect(next.values.priority).toBe('HIGH');
  });

  it('should ignore field errors that name fields the form does not own', () => {
    const state = makeState({ title: 'a', description: 'b', priority: 'LOW' });

    const next = formReducer(state, {
      type: 'SUBMISSION_FAILED',
      error: validationError([
        { field: 'title', reason: 'too short' },
        { field: 'nonExistentField', reason: 'orphan error' },
      ]),
    });

    expect(next.errors).toEqual({ title: 'too short' });
  });

  it('should replace any prior errors with the new payload', () => {
    const state = makeState(
      { title: 'a', description: 'b', priority: 'LOW' },
      { description: 'stale error' },
    );

    const next = formReducer(state, {
      type: 'SUBMISSION_FAILED',
      error: validationError([{ field: 'title', reason: 'fresh error' }]),
    });

    expect(next.errors).toEqual({ title: 'fresh error' });
  });

  it('should clear all errors when a 400 carries no field errors', () => {
    const state = makeState(
      { title: 'a', description: 'b', priority: 'LOW' },
      { title: 'old' },
    );

    const next = formReducer(state, {
      type: 'SUBMISSION_FAILED',
      error: validationError([]),
    });

    expect(next.errors).toEqual({});
    expect(next.values).toBe(state.values);
  });
});

describe('formReducer / SUBMISSION_SUCCEEDED', () => {
  it('should reset values and errors to the supplied clean slate on success', () => {
    const state = makeState(
      { title: 'draft', description: 'draft body', priority: 'HIGH' },
      { title: 'was invalid' },
    );

    const cleanSlate: CreateTicketForm = { title: '', description: '', priority: 'MEDIUM' };
    const next = formReducer(state, { type: 'SUBMISSION_SUCCEEDED', initialValues: cleanSlate });

    expect(next.values).toEqual(cleanSlate);
    expect(next.errors).toEqual({});
  });
});

describe('formReducer / immutability', () => {
  it('should not mutate the input state on a failed submission', () => {
    const values: CreateTicketForm = { title: 'a', description: 'b', priority: 'LOW' };
    const state = makeState(values, {});

    formReducer(state, {
      type: 'SUBMISSION_FAILED',
      error: validationError([{ field: 'title', reason: 'bad' }]),
    });

    expect(state.errors).toEqual({});
    expect(state.values).toEqual({ title: 'a', description: 'b', priority: 'LOW' });
  });
});
