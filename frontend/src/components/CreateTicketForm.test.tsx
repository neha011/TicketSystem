import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { TicketSummaryResponse } from '@/lib/api/types';
import { renderWithProviders } from '@/test/renderWithProviders';
import { CreateTicketForm } from './CreateTicketForm';

// Mock only the apiClient's network methods; keep `ApiError`/`isApiError` real
// so the component's error branching runs against genuine error shapes.
const { createTicket } = vi.hoisted(() => ({ createTicket: vi.fn() }));

vi.mock('@/lib/api/apiClient', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api/apiClient')>();
  return { ...actual, apiClient: { ...actual.apiClient, createTicket } };
});

const CREATED: TicketSummaryResponse = {
  id: 'aaaaaaaa-0000-0000-0000-000000000001',
  title: 'Printer on fire',
  status: 'OPEN',
  priority: 'HIGH',
  assignee: null,
  createdAt: '2026-09-05T10:15:30Z',
  updatedAt: '2026-09-05T10:15:30Z',
  version: 0,
};

/** The value the CreateTicketForm renders for a created timestamp. */
function expectedCreatedText(iso: string): string {
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? iso : parsed.toLocaleString();
}

describe('CreateTicketForm', () => {
  afterEach(() => {
    vi.clearAllMocks();
  });

  it('should display the created ticket id, title, priority, status, and creation timestamp on success', async () => {
    // Requirement 1.7: the success panel echoes the server-confirmed fields —
    // id, title, priority, status, and creation timestamp.
    createTicket.mockResolvedValue(CREATED);
    renderWithProviders(<CreateTicketForm />);

    await userEvent.type(screen.getByLabelText('Title'), 'Printer on fire');
    await userEvent.click(screen.getByRole('button', { name: 'Create ticket' }));

    const result = await screen.findByRole('status');
    expect(result).toHaveTextContent('Ticket created');

    // Scope the field assertions to the result panel so the priority <select>
    // option "HIGH" (in the form above) cannot satisfy them. Each server-
    // confirmed field is shown as a value in the definition list.
    const panel = within(result);
    expect(panel.getByText('Identifier')).toBeInTheDocument();
    expect(panel.getByText(CREATED.id)).toBeInTheDocument();
    expect(panel.getByText('Printer on fire')).toBeInTheDocument();
    expect(panel.getByText('HIGH')).toBeInTheDocument();
    expect(panel.getByText('OPEN')).toBeInTheDocument();
    expect(panel.getByText(expectedCreatedText(CREATED.createdAt))).toBeInTheDocument();
  });
});
