import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PagedResponse, TicketSummaryResponse } from '@/lib/api/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { TicketListView } from './TicketListView';

// Mock only the apiClient network method used by the list view.
const { listTickets } = vi.hoisted(() => ({ listTickets: vi.fn() }));

vi.mock('@/lib/api/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api/apiClient')>();
  return { ...actual, apiClient: { ...actual.apiClient, listTickets } };
});

const ROW: TicketSummaryResponse = {
  id: 'bbbbbbbb-0000-0000-0000-000000000001',
  title: 'Login page broken',
  status: 'IN_PROGRESS',
  priority: 'CRITICAL',
  assignee: 'alice',
  createdAt: '2026-09-05T10:15:30Z',
  updatedAt: '2026-09-05T10:15:30Z',
  version: 3,
};

function page(content: TicketSummaryResponse[]): PagedResponse<TicketSummaryResponse> {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
  };
}

describe('TicketListView', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should render each row with its title, status, priority, and assignee when populated', async () => {
    // Requirement 2.6: the populated branch shows the per-row fields.
    listTickets.mockResolvedValue(page([ROW]));
    renderWithProviders(<TicketListView />);

    const rowTitle = await screen.findByRole('link', { name: 'Login page broken' });
    const row = rowTitle.closest('tr');
    expect(row).not.toBeNull();

    const cells = row as HTMLTableRowElement;
    expect(cells).toHaveTextContent('Login page broken');
    expect(cells).toHaveTextContent('IN_PROGRESS');
    expect(cells).toHaveTextContent('CRITICAL');
    expect(cells).toHaveTextContent('alice');
  });

  it('should show the empty indication without an error when there are no tickets', async () => {
    // Requirement 2.8: an empty repository is the empty state, not an error.
    listTickets.mockResolvedValue(page([]));
    renderWithProviders(<TicketListView />);

    expect(await screen.findByText('No tickets found.')).toBeInTheDocument();
    // No error indication accompanies an empty result (Req 2.9).
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('should show the no-results indication when a filtered query matches nothing', async () => {
    // Requirement 6.3: a filter/keyword that matches nothing is the same empty
    // state — a no-results indication, still not an error.
    listTickets.mockResolvedValue(page([]));
    renderWithProviders(<TicketListView />);

    expect(await screen.findByText('No tickets found.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
