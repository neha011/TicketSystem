import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { TicketDetailResponse } from '@/lib/api/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { EditTicketForm } from './EditTicketForm';

const { updateTicket } = vi.hoisted(() => ({ updateTicket: vi.fn() }));

vi.mock('@/lib/api/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api/apiClient')>();
  return { ...actual, apiClient: { ...actual.apiClient, updateTicket } };
});

const TICKET: TicketDetailResponse = {
  id: 'dddddddd-0000-0000-0000-000000000001',
  title: 'Original title',
  description: 'Original description',
  status: 'OPEN',
  priority: 'LOW',
  assignee: 'alice',
  createdAt: '2026-09-05T10:15:30Z',
  updatedAt: '2026-09-05T10:15:30Z',
  version: 2,
  comments: [],
};

describe('EditTicketForm', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should reflect the confirmed updated values after a successful save without a page reload', async () => {
    // Requirements 4.7, 4.8: on confirmation the form refreshes to the
    // backend-confirmed values and shows a saved confirmation, in place.
    const confirmed: TicketDetailResponse = {
      ...TICKET,
      title: 'Updated title',
      priority: 'HIGH',
      version: 3,
    };
    updateTicket.mockResolvedValue(confirmed);

    renderWithProviders(<EditTicketForm ticket={TICKET} />);

    const titleInput = screen.getByLabelText('Title');
    await userEvent.clear(titleInput);
    await userEvent.type(titleInput, 'Updated title');
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    // Confirmation message appears without a reload.
    expect(await screen.findByText('Changes saved.')).toBeInTheDocument();

    // The editable field now holds the freshly confirmed value.
    await waitFor(() => expect(screen.getByLabelText('Title')).toHaveValue('Updated title'));
    expect(updateTicket).toHaveBeenCalledWith(
      TICKET.id,
      expect.objectContaining({ title: 'Updated title', version: TICKET.version }),
    );
  });
});
