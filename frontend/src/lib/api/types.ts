/**
 * Wire-format types for the `/api/v1` contract.
 *
 * These mirror the backend DTOs exactly (camelCase JSON, ISO-8601 UTC
 * timestamps). They are the only shapes the data-fetching layer speaks; views
 * never see raw `Response` objects or untyped JSON.
 */

export const TICKET_STATUSES = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'CANCELLED'] as const;
export type TicketStatus = (typeof TICKET_STATUSES)[number];

export const TICKET_PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'] as const;
export type TicketPriority = (typeof TICKET_PRIORITIES)[number];

/** An ISO-8601 UTC instant, e.g. `2026-09-05T10:15:30Z`. */
export type IsoInstant = string;

export interface CommentResponse {
  id: string;
  author: string;
  content: string;
  createdAt: IsoInstant;
}

export interface TicketSummaryResponse {
  id: string;
  title: string;
  status: TicketStatus;
  priority: TicketPriority;
  /** `null` when the ticket is unassigned (Req 2.7). */
  assignee: string | null;
  createdAt: IsoInstant;
  updatedAt: IsoInstant;
  /** Optimistic-locking version; must be echoed back on every write (Req 4.6). */
  version: number;
}

export interface TicketDetailResponse extends TicketSummaryResponse {
  description: string | null;
  /** Ordered oldest → newest by `(createdAt, id)` (Req 3.6). */
  comments: CommentResponse[];
}

export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Query parameters accepted by `GET /api/v1/tickets`. */
export interface TicketListQuery {
  /** Defaults to 0 server-side when omitted (Req 2.1). */
  page?: number;
  /** Defaults to 20 server-side when omitted; 1–100 (Req 2.1, 2.2). */
  size?: number;
  /** 1–200 characters when present (Req 6.1, 6.4). */
  keyword?: string;
  /** Absent means unfiltered (Req 7.4). */
  status?: TicketStatus;
}

export interface CreateTicketRequest {
  title: string;
  description?: string;
  priority: TicketPriority;
  assignee?: string;
}

/**
 * Partial field update. Omitting a key leaves the field untouched; sending
 * `assignee: null` explicitly clears it. `version` is always required.
 */
export interface UpdateTicketRequest {
  title?: string;
  description?: string | null;
  priority?: TicketPriority;
  assignee?: string | null;
  version: number;
}

export interface StatusTransitionRequest {
  status: TicketStatus;
  version: number;
}

export interface CreateCommentRequest {
  content: string;
}

/** One field-level validation failure from the backend `ErrorResponse`. */
export interface ApiFieldError {
  field: string;
  reason: string;
}

/** The single error shape every backend endpoint returns. */
export interface ErrorResponseBody {
  timestamp: IsoInstant;
  status: number;
  error: string;
  message: string;
  path: string;
  fieldErrors?: ApiFieldError[];
}
