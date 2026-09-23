import type {
  ApiFieldError,
  CommentResponse,
  CreateCommentRequest,
  CreateTicketRequest,
  PagedResponse,
  StatusTransitionRequest,
  TicketDetailResponse,
  TicketListQuery,
  TicketSummaryResponse,
  UpdateTicketRequest,
} from './types';

/** Every endpoint lives under the versioned base path (see design.md, API Contract). */
export const API_BASE_PATH = '/api/v1';

/**
 * A backend `ErrorResponse.message` is a short, client-facing summary. Anything
 * longer than this, or containing line breaks, is not a summary — it is a
 * payload dump (stack trace, HTML error page, proxy output). Such text is
 * discarded rather than carried into `ApiError`, so callers structurally cannot
 * render it (Requirement 11.8).
 */
const MAX_SERVER_TEXT_LENGTH = 500;

/**
 * How a request failed.
 *
 * - `http` — the backend answered with a non-2xx status.
 * - `network` — no response was received at all (offline, DNS, CORS, TLS).
 * - `malformed` — a response arrived but its body was not usable JSON.
 */
export type ApiErrorKind = 'http' | 'network' | 'malformed';

interface ApiErrorInit {
  status: number | null;
  kind: ApiErrorKind;
  serverMessage: string | null;
  fieldErrors: readonly ApiFieldError[];
  fallbackMessage: string;
}

/**
 * The single failure shape every caller of this module sees.
 *
 * Whatever went wrong — a documented `ErrorResponse`, an HTML gateway page, a
 * truncated body, or a dead socket — it arrives here normalised to a status, one
 * message, and a (possibly empty) list of field errors. Callers therefore never
 * branch on `Response` objects and never see raw payload text.
 */
export class ApiError extends Error {
  /** HTTP status, or `null` when no response was received (network failure). */
  readonly status: number | null;

  readonly kind: ApiErrorKind;

  /**
   * The backend-supplied summary, present only when the body matched the
   * documented `ErrorResponse` shape and the message looked like a summary.
   * `null` means the backend offered nothing usable — which is what lets the
   * error mapper distinguish "400 with a message" from "400 without one".
   */
  readonly serverMessage: string | null;

  /** Every field-level violation the backend reported; empty when none. */
  readonly fieldErrors: readonly ApiFieldError[];

  constructor(init: ApiErrorInit) {
    super(init.serverMessage ?? init.fallbackMessage);
    this.name = 'ApiError';
    this.status = init.status;
    this.kind = init.kind;
    this.serverMessage = init.serverMessage;
    this.fieldErrors = init.fieldErrors;
  }

  /** True when the request never reached the backend (Requirement 11.8). */
  get isNetworkFailure(): boolean {
    return this.kind === 'network';
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

/**
 * Accepts a value only if it is short, single-line, non-blank text.
 *
 * Used for both `message` and the `field`/`reason` pairs, so a hostile or broken
 * body cannot smuggle a stack trace through any of them.
 */
function safeText(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null;
  }
  const trimmed = value.trim();
  if (trimmed.length === 0 || trimmed.length > MAX_SERVER_TEXT_LENGTH) {
    return null;
  }
  if (/[\r\n]/.test(trimmed)) {
    return null;
  }
  return trimmed;
}

function parseFieldErrors(value: unknown): ApiFieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  const parsed: ApiFieldError[] = [];
  for (const entry of value) {
    if (typeof entry !== 'object' || entry === null) {
      continue;
    }
    const candidate = entry as { field?: unknown; reason?: unknown };
    const field = safeText(candidate.field);
    const reason = safeText(candidate.reason);
    if (field !== null && reason !== null) {
      parsed.push({ field, reason });
    }
  }
  return parsed;
}

/**
 * A status-derived stand-in for logs and for `Error.message` when the backend
 * supplied nothing usable. Deliberately content-free: it is built from the
 * status code alone, never from the payload.
 */
function fallbackMessageForStatus(status: number | null): string {
  return status === null
    ? 'The request did not reach the ticket service.'
    : `The ticket service responded with status ${status}.`;
}

/** Reads a JSON body, yielding `undefined` for an absent or unparseable one. */
async function readJsonBody(response: Response): Promise<unknown> {
  try {
    return (await response.json()) as unknown;
  } catch {
    return undefined;
  }
}

/**
 * Detects a cancellation. Matched on `name` rather than on `instanceof Error`,
 * because `fetch` rejects with a `DOMException` whose prototype chain differs
 * between the browser, Node, and jsdom.
 */
function isAbortError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { name?: unknown }).name === 'AbortError'
  );
}

export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

export interface ApiClientConfig {
  /** Backend origin. Defaults to `NEXT_PUBLIC_API_BASE_URL`, else same-origin. */
  baseUrl?: string;
  /** Injectable for tests; defaults to the platform `fetch`. */
  fetch?: FetchLike;
}

type QueryValue = string | number | undefined;

interface RequestSpec {
  method: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  path: string;
  query?: Record<string, QueryValue>;
  body?: unknown;
  signal?: AbortSignal;
}

function defaultBaseUrl(): string {
  return process.env.NEXT_PUBLIC_API_BASE_URL ?? '';
}

/** Drops a trailing slash so `baseUrl + path` never produces a double slash. */
function normaliseBaseUrl(baseUrl: string): string {
  return baseUrl.endsWith('/') ? baseUrl.slice(0, -1) : baseUrl;
}

function buildQueryString(query: Record<string, QueryValue> | undefined): string {
  if (query === undefined) {
    return '';
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    // An omitted parameter must stay omitted so the backend applies its own
    // default (Req 2.1); an empty-string keyword is passed through unchanged so
    // the backend, not the UI, decides that it is invalid (Req 6.4, 10.1).
    if (value !== undefined) {
      params.append(key, String(value));
    }
  }
  const serialised = params.toString();
  return serialised.length === 0 ? '' : `?${serialised}`;
}

export interface ApiClient {
  request: <T>(spec: RequestSpec) => Promise<T>;
  listTickets: (
    query?: TicketListQuery,
    options?: { signal?: AbortSignal },
  ) => Promise<PagedResponse<TicketSummaryResponse>>;
  getTicket: (id: string, options?: { signal?: AbortSignal }) => Promise<TicketDetailResponse>;
  createTicket: (body: CreateTicketRequest) => Promise<TicketSummaryResponse>;
  updateTicket: (id: string, body: UpdateTicketRequest) => Promise<TicketDetailResponse>;
  transitionTicketStatus: (
    id: string,
    body: StatusTransitionRequest,
  ) => Promise<TicketDetailResponse>;
  addComment: (ticketId: string, body: CreateCommentRequest) => Promise<CommentResponse>;
}

/**
 * Builds a client bound to one backend origin and one `fetch` implementation.
 *
 * A factory rather than a bare set of functions so tests can inject `fetch`
 * without patching globals, and so a different origin can be targeted without
 * mutating module state.
 */
export function createApiClient(config: ApiClientConfig = {}): ApiClient {
  const baseUrl = normaliseBaseUrl(config.baseUrl ?? defaultBaseUrl());
  const fetchImpl: FetchLike =
    config.fetch ?? ((input, init) => globalThis.fetch(input as RequestInfo, init));

  async function request<T>(spec: RequestSpec): Promise<T> {
    const url = `${baseUrl}${API_BASE_PATH}${spec.path}${buildQueryString(spec.query)}`;
    const headers: Record<string, string> = { Accept: 'application/json' };
    if (spec.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }

    const init: RequestInit = {
      method: spec.method,
      headers,
      // Every endpoint is authenticated (Req 3.4); the session cookie must ride
      // along even when the backend sits on another origin.
      credentials: 'include',
    };
    if (spec.body !== undefined) {
      init.body = JSON.stringify(spec.body);
    }
    if (spec.signal !== undefined) {
      init.signal = spec.signal;
    }

    let response: Response;
    try {
      response = await fetchImpl(url, init);
    } catch (error) {
      // A caller-initiated cancellation is not a failure to report to the user.
      if (isAbortError(error)) {
        throw error;
      }
      throw new ApiError({
        status: null,
        kind: 'network',
        serverMessage: null,
        fieldErrors: [],
        fallbackMessage: fallbackMessageForStatus(null),
      });
    }

    if (!response.ok) {
      const body = await readJsonBody(response);
      const envelope =
        typeof body === 'object' && body !== null
          ? (body as { message?: unknown; fieldErrors?: unknown })
          : {};
      throw new ApiError({
        status: response.status,
        kind: 'http',
        serverMessage: safeText(envelope.message),
        fieldErrors: parseFieldErrors(envelope.fieldErrors),
        fallbackMessage: fallbackMessageForStatus(response.status),
      });
    }

    if (response.status === 204) {
      return undefined as T;
    }

    const body = await readJsonBody(response);
    if (body === undefined) {
      throw new ApiError({
        status: response.status,
        kind: 'malformed',
        serverMessage: null,
        fieldErrors: [],
        fallbackMessage: fallbackMessageForStatus(response.status),
      });
    }
    return body as T;
  }

  return {
    request,

    listTickets: (query = {}, options = {}) =>
      request<PagedResponse<TicketSummaryResponse>>({
        method: 'GET',
        path: '/tickets',
        query: {
          page: query.page,
          size: query.size,
          keyword: query.keyword,
          status: query.status,
        },
        ...(options.signal !== undefined ? { signal: options.signal } : {}),
      }),

    getTicket: (id, options = {}) =>
      request<TicketDetailResponse>({
        method: 'GET',
        path: `/tickets/${encodeURIComponent(id)}`,
        ...(options.signal !== undefined ? { signal: options.signal } : {}),
      }),

    createTicket: (body) =>
      request<TicketSummaryResponse>({ method: 'POST', path: '/tickets', body }),

    updateTicket: (id, body) =>
      request<TicketDetailResponse>({
        method: 'PATCH',
        path: `/tickets/${encodeURIComponent(id)}`,
        body,
      }),

    transitionTicketStatus: (id, body) =>
      request<TicketDetailResponse>({
        method: 'PATCH',
        path: `/tickets/${encodeURIComponent(id)}/status`,
        body,
      }),

    addComment: (ticketId, body) =>
      request<CommentResponse>({
        method: 'POST',
        path: `/tickets/${encodeURIComponent(ticketId)}/comments`,
        body,
      }),
  };
}

/** The client the app uses; tests build their own with an injected `fetch`. */
export const apiClient = createApiClient();
