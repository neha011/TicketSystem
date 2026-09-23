import { describe, expect, it, vi } from 'vitest';
import { ApiError, API_BASE_PATH, createApiClient, isApiError, type FetchLike } from './apiClient';
import type { ErrorResponseBody } from './types';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function errorBody(overrides: Partial<ErrorResponseBody> = {}): ErrorResponseBody {
  return {
    timestamp: '2026-09-05T10:15:30Z',
    status: 400,
    error: 'Bad Request',
    message: 'Validation failed for 1 field(s)',
    path: '/api/v1/tickets',
    ...overrides,
  };
}

/** Records the single call a client makes, so URL/method/body can be asserted. */
function recordingFetch(response: Response | (() => Promise<Response>)) {
  const calls: Array<{ url: string; init: RequestInit | undefined }> = [];
  const fetchImpl: FetchLike = async (url, init) => {
    calls.push({ url, init });
    return typeof response === 'function' ? response() : response;
  };
  return { calls, fetchImpl };
}

describe('createApiClient request building', () => {
  it('should prefix every path with the versioned base path and the configured origin', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse({ content: [] }));
    const client = createApiClient({ baseUrl: 'https://tickets.example.com', fetch: fetchImpl });

    await client.listTickets();

    expect(calls[0]?.url).toBe(`https://tickets.example.com${API_BASE_PATH}/tickets`);
  });

  it('should collapse a trailing slash in the base URL rather than emit a double slash', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse({ content: [] }));
    const client = createApiClient({ baseUrl: 'https://tickets.example.com/', fetch: fetchImpl });

    await client.listTickets();

    expect(calls[0]?.url).toBe(`https://tickets.example.com${API_BASE_PATH}/tickets`);
  });

  it('should omit absent list parameters so the backend applies its own defaults', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse({ content: [] }));
    const client = createApiClient({ fetch: fetchImpl });

    await client.listTickets({ page: 2 });

    expect(calls[0]?.url).toBe(`${API_BASE_PATH}/tickets?page=2`);
  });

  it('should send every supplied list parameter, encoding the keyword', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse({ content: [] }));
    const client = createApiClient({ fetch: fetchImpl });

    await client.listTickets({ page: 0, size: 50, keyword: '100% down', status: 'OPEN' });

    expect(calls[0]?.url).toBe(
      `${API_BASE_PATH}/tickets?page=0&size=50&keyword=100%25+down&status=OPEN`,
    );
  });

  it('should forward an out-of-range parameter untouched so the backend stays the authority', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse(errorBody(), 400));
    const client = createApiClient({ fetch: fetchImpl });

    await expect(client.listTickets({ page: -5, size: 999 })).rejects.toBeInstanceOf(ApiError);

    expect(calls[0]?.url).toContain('page=-5&size=999');
  });

  it('should serialise a write body as JSON with a JSON content type', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse({ id: 't-1' }, 201));
    const client = createApiClient({ fetch: fetchImpl });

    await client.createTicket({ title: 'Login fails', priority: 'HIGH' });

    expect(calls[0]?.init?.method).toBe('POST');
    expect(calls[0]?.init?.body).toBe(JSON.stringify({ title: 'Login fails', priority: 'HIGH' }));
    expect((calls[0]?.init?.headers as Record<string, string>)['Content-Type']).toBe(
      'application/json',
    );
  });

  it('should not set a content type on a GET, which has no body', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse({ id: 't-1' }));
    const client = createApiClient({ fetch: fetchImpl });

    await client.getTicket('t-1');

    expect((calls[0]?.init?.headers as Record<string, string>)['Content-Type']).toBeUndefined();
  });

  it('should send credentials because every endpoint is authenticated', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse({ id: 't-1' }));
    const client = createApiClient({ fetch: fetchImpl });

    await client.getTicket('t-1');

    expect(calls[0]?.init?.credentials).toBe('include');
  });

  it('should target the documented path for each endpoint', async () => {
    // A fresh Response per call: a body can only be consumed once.
    const { calls, fetchImpl } = recordingFetch(() => Promise.resolve(jsonResponse({})));
    const client = createApiClient({ fetch: fetchImpl });

    await client.updateTicket('t-1', { title: 'x', version: 3 });
    await client.transitionTicketStatus('t-1', { status: 'IN_PROGRESS', version: 3 });
    await client.addComment('t-1', { content: 'Escalated.' });

    expect(calls.map((call) => `${String(call.init?.method)} ${call.url}`)).toEqual([
      `PATCH ${API_BASE_PATH}/tickets/t-1`,
      `PATCH ${API_BASE_PATH}/tickets/t-1/status`,
      `POST ${API_BASE_PATH}/tickets/t-1/comments`,
    ]);
  });

  it('should escape a path variable so a hostile id cannot reshape the URL', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse({}));
    const client = createApiClient({ fetch: fetchImpl });

    await client.getTicket('../../admin');

    expect(calls[0]?.url).toBe(`${API_BASE_PATH}/tickets/..%2F..%2Fadmin`);
  });
});

describe('createApiClient success handling', () => {
  it('should return the parsed body on success', async () => {
    const page = {
      content: [{ id: 't-1', title: 'Login fails' }],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    };
    const { fetchImpl } = recordingFetch(jsonResponse(page));
    const client = createApiClient({ fetch: fetchImpl });

    await expect(client.listTickets()).resolves.toEqual(page);
  });

  it('should reject a 2xx whose body is not usable JSON instead of returning undefined data', async () => {
    const { fetchImpl } = recordingFetch(new Response('<html>proxy warm-up</html>', { status: 200 }));
    const client = createApiClient({ fetch: fetchImpl });

    const error = await client.getTicket('t-1').catch((caught: unknown) => caught);

    expect(isApiError(error)).toBe(true);
    expect((error as ApiError).kind).toBe('malformed');
    expect((error as ApiError).status).toBe(200);
    expect((error as ApiError).serverMessage).toBeNull();
  });
});

describe('createApiClient error normalisation', () => {
  it('should carry the status, backend message, and field errors from an ErrorResponse body', async () => {
    const body = errorBody({
      message: 'Validation failed for 2 field(s)',
      fieldErrors: [
        { field: 'title', reason: 'must be 1 to 200 characters after trimming' },
        { field: 'priority', reason: 'must be one of LOW, MEDIUM, HIGH, CRITICAL' },
      ],
    });
    const { fetchImpl } = recordingFetch(jsonResponse(body, 400));
    const client = createApiClient({ fetch: fetchImpl });

    const error = (await client
      .createTicket({ title: '', priority: 'HIGH' })
      .catch((caught: unknown) => caught)) as ApiError;

    expect(error.status).toBe(400);
    expect(error.kind).toBe('http');
    expect(error.serverMessage).toBe('Validation failed for 2 field(s)');
    expect(error.fieldErrors).toEqual(body.fieldErrors);
  });

  it('should expose an empty field-error list when the backend reports none', async () => {
    const { fetchImpl } = recordingFetch(jsonResponse(errorBody({ status: 404 }), 404));
    const client = createApiClient({ fetch: fetchImpl });

    const error = (await client.getTicket('t-1').catch((caught: unknown) => caught)) as ApiError;

    expect(error.fieldErrors).toEqual([]);
  });

  it('should drop field-error entries missing a field or reason rather than surface partial ones', async () => {
    const body = errorBody({
      fieldErrors: [
        { field: 'title', reason: 'must not be blank' },
        { field: 'priority' } as never,
        { reason: 'orphaned' } as never,
      ],
    });
    const { fetchImpl } = recordingFetch(jsonResponse(body, 400));
    const client = createApiClient({ fetch: fetchImpl });

    const error = (await client
      .createTicket({ title: '', priority: 'HIGH' })
      .catch((caught: unknown) => caught)) as ApiError;

    expect(error.fieldErrors).toEqual([{ field: 'title', reason: 'must not be blank' }]);
  });

  it('should discard a multi-line payload posing as a message so no stack trace reaches callers', async () => {
    const stackTrace = [
      'org.postgresql.util.PSQLException: ERROR: relation "ticket" does not exist',
      '\tat com.ticketsystem.ticket.service.TicketServiceImpl.create(TicketServiceImpl.java:88)',
    ].join('\n');
    const { fetchImpl } = recordingFetch(jsonResponse(errorBody({ status: 500, message: stackTrace }), 500));
    const client = createApiClient({ fetch: fetchImpl });

    const error = (await client
      .createTicket({ title: 'x', priority: 'LOW' })
      .catch((caught: unknown) => caught)) as ApiError;

    expect(error.serverMessage).toBeNull();
    expect(error.message).not.toContain('PSQLException');
    expect(error.message).not.toContain('TicketServiceImpl');
  });

  it('should discard an over-long single-line message, which is a payload dump not a summary', async () => {
    const longMessage = 'x'.repeat(501);
    const { fetchImpl } = recordingFetch(jsonResponse(errorBody({ status: 500, message: longMessage }), 500));
    const client = createApiClient({ fetch: fetchImpl });

    const error = (await client
      .createTicket({ title: 'x', priority: 'LOW' })
      .catch((caught: unknown) => caught)) as ApiError;

    expect(error.serverMessage).toBeNull();
    expect(error.message).not.toContain(longMessage);
  });

  it('should treat a blank message as absent so the mapper can substitute generic text', async () => {
    const { fetchImpl } = recordingFetch(jsonResponse(errorBody({ message: '   ' }), 400));
    const client = createApiClient({ fetch: fetchImpl });

    const error = (await client
      .createTicket({ title: '', priority: 'HIGH' })
      .catch((caught: unknown) => caught)) as ApiError;

    expect(error.serverMessage).toBeNull();
  });

  it('should normalise a non-JSON error body to a status-only ApiError, leaking none of its text', async () => {
    const html = '<html><body>502 Bad Gateway — upstream nginx/1.24.0 at /srv/app</body></html>';
    const { fetchImpl } = recordingFetch(new Response(html, { status: 502 }));
    const client = createApiClient({ fetch: fetchImpl });

    const error = (await client.getTicket('t-1').catch((caught: unknown) => caught)) as ApiError;

    expect(error.status).toBe(502);
    expect(error.serverMessage).toBeNull();
    expect(error.message).not.toContain('nginx');
    expect(error.message).not.toContain('/srv/app');
  });

  it('should normalise a rejected fetch into a network ApiError with a null status', async () => {
    const client = createApiClient({
      fetch: () => Promise.reject(new TypeError('Failed to fetch')),
    });

    const error = (await client.getTicket('t-1').catch((caught: unknown) => caught)) as ApiError;

    expect(isApiError(error)).toBe(true);
    expect(error.status).toBeNull();
    expect(error.kind).toBe('network');
    expect(error.isNetworkFailure).toBe(true);
    expect(error.serverMessage).toBeNull();
    expect(error.message).not.toContain('Failed to fetch');
  });

  it('should preserve a caller-initiated abort as an AbortError, not report it as a failure', async () => {
    const abort = new DOMException('The operation was aborted.', 'AbortError');
    const client = createApiClient({ fetch: () => Promise.reject(abort) });

    const error = await client.getTicket('t-1').catch((caught: unknown) => caught);

    expect(isApiError(error)).toBe(false);
    expect((error as Error).name).toBe('AbortError');
  });

  it('should forward an abort signal to fetch', async () => {
    const { calls, fetchImpl } = recordingFetch(jsonResponse({}));
    const client = createApiClient({ fetch: fetchImpl });
    const controller = new AbortController();

    await client.getTicket('t-1', { signal: controller.signal });

    expect(calls[0]?.init?.signal).toBe(controller.signal);
  });

  it('should use the platform fetch when none is injected', async () => {
    const globalFetch = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(jsonResponse({ id: 't-1' }));
    const client = createApiClient({ baseUrl: 'https://tickets.example.com' });

    await client.getTicket('t-1');

    expect(globalFetch).toHaveBeenCalledOnce();
    globalFetch.mockRestore();
  });
});
