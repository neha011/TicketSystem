import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/lib/api/apiClient';
import { ERROR_MESSAGES } from '@/lib/api/errorMapper';
import type { TicketDetailResponse } from '@/lib/api/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { StatusTransitionControl } from './StatusTransitionControl';

const { transitionTicketStatus } = vi.hoisted(() => ({ transitionTicketStatus: vi.fn() }));

vi.mock('@/lib/api/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api/apiClient')>();
  return { ...actual, apiClient: { ...actual.apiClient, transitionTicketStatus } };
});

const OPEN_TICKET: Pick<TicketDetailResponse, 'id' | 'status' | 'version'> = {
  id: 'eeeeeeee-0000-0000-0000-000000000001',
  status: 'OPEN',
  version: 1,
};

function confirmedAs(status: TicketDetailResponse['status']): TicketDetailResponse {
  return {
    id: OPEN_TICKET.id,
    title: 'A ticket',
    description: null,
    status,
    priority: 'MEDIUM',
    assignee: null,
    createdAt: '2026-09-05T10:15:30Z',
    updatedAt: '2026-09-05T10:20:00Z',
    version: 2,
    comments: [],
  };
}

describe('StatusTransitionControl', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should render the confirmation message naming the new status on a successful transition', async () => {
    // Requirement 8.10: a successful transition confirms the new status.
    transitionTicketStatus.mockResolvedValue(confirmedAs('IN_PROGRESS'));
    renderWithProviders(<StatusTransitionControl ticket={OPEN_TICKET} />);

    await userEvent.click(screen.getByRole('button', { name: 'Move to in progress' }));

    const confirmation = await screen.findByRole('status');
    expect(confirmation).toHaveTextContent('Status updated to in progress.');
  });

  it('should render the specific backend message on a rejection that carries one', async () => {
    // Requirement 11.1: a rejection with a backend message surfaces that exact
    // message (here a 400 routed through errorMapper).
    const serverMessage = 'Cannot transition from OPEN to RESOLVED.';
    transitionTicketStatus.mockRejectedValue(
      new ApiError({
        status: 400,
        kind: 'http',
        serverMessage,
        fieldErrors: [],
        fallbackMessage: 'The ticket service responded with status 400.',
      }),
    );
    renderWithProviders(<StatusTransitionControl ticket={OPEN_TICKET} />);

    await userEvent.click(screen.getByRole('button', { name: 'Move to in progress' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(serverMessage);
    expect(screen.queryByText(ERROR_MESSAGES.genericValidation)).not.toBeInTheDocument();
  });

  it('should render the generic validation message on a rejection without a message', async () => {
    // Requirement 11.2: a 400 with no usable summary falls back to the generic
    // validation copy through errorMapper.
    transitionTicketStatus.mockRejectedValue(
      new ApiError({
        status: 400,
        kind: 'http',
        serverMessage: null,
        fieldErrors: [],
        fallbackMessage: 'The ticket service responded with status 400.',
      }),
    );
    renderWithProviders(<StatusTransitionControl ticket={OPEN_TICKET} />);

    await userEvent.click(screen.getByRole('button', { name: 'Move to in progress' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(ERROR_MESSAGES.genericValidation);
  });
});
