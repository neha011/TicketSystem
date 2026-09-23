import { useQuery } from '@tanstack/react-query';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useRef } from 'react';
import { describe, expect, it } from 'vitest';
import { queryKeys } from './queryKeys';
import { useConfirmedMutation } from './useConfirmedMutation';
import { renderWithProviders } from '@/test/renderWithProviders';

const TICKET_ID = '11111111-1111-1111-1111-111111111111';

/**
 * A minimal stand-in for the edit form: it renders the title the backend last
 * confirmed and submits a different one. `resolveServer` lets the test hold the
 * request in flight and inspect what is on screen meanwhile.
 */
function TitleEditor({
  confirmedTitles,
  submittedTitle,
  resolveServer,
}: {
  confirmedTitles: string[];
  submittedTitle: string;
  resolveServer: Promise<void>;
}) {
  // A ref, not a local: the counter must survive re-renders so the second fetch
  // genuinely returns the next server value.
  const fetchCount = useRef(0);

  const detail = useQuery({
    queryKey: queryKeys.ticketDetail(TICKET_ID),
    queryFn: async () => {
      const index = Math.min(fetchCount.current, confirmedTitles.length - 1);
      fetchCount.current += 1;
      return { title: confirmedTitles[index] as string };
    },
  });

  const save = useConfirmedMutation<{ title: string }, Error, string>({
    mutationFn: async (title) => {
      await resolveServer;
      return { title };
    },
    invalidates: [queryKeys.ticketDetail(TICKET_ID)],
  });

  return (
    <div>
      <p data-testid="displayed-title">{detail.data?.title ?? 'loading'}</p>
      <p data-testid="pending">{save.isPending ? 'saving' : 'idle'}</p>
      <button type="button" onClick={() => save.mutate(submittedTitle)}>
        Save
      </button>
    </div>
  );
}

describe('useConfirmedMutation', () => {
  it('should keep displaying the last backend-confirmed value while a submission is in flight', async () => {
    let releaseServer = () => {};
    const resolveServer = new Promise<void>((resolve) => {
      releaseServer = resolve;
    });

    renderWithProviders(
      <TitleEditor
        confirmedTitles={['confirmed title', 'confirmed title v2']}
        submittedTitle="submitted title"
        resolveServer={resolveServer}
      />,
    );

    await screen.findByText('confirmed title');

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(screen.getByTestId('pending')).toHaveTextContent('saving'));

    // The submitted value must not be rendered as the ticket's current value.
    expect(screen.getByTestId('displayed-title')).toHaveTextContent('confirmed title');
    expect(screen.queryByText('submitted title')).not.toBeInTheDocument();

    releaseServer();

    // Once confirmed, the refreshed server value replaces it without a reload.
    await waitFor(() => expect(screen.getByTestId('displayed-title')).toHaveTextContent('confirmed title v2'));
  });

  it('should leave the cache untouched when a submission fails', async () => {
    function FailingEditor() {
      const save = useConfirmedMutation<{ title: string }, Error, string>({
        mutationFn: async () => {
          throw new Error('rejected');
        },
        invalidates: [queryKeys.ticketDetail(TICKET_ID)],
      });

      return (
        <button type="button" onClick={() => save.mutate('submitted title')}>
          Save
        </button>
      );
    }

    const { queryClient } = renderWithProviders(<FailingEditor />);
    queryClient.setQueryData(queryKeys.ticketDetail(TICKET_ID), { title: 'confirmed title' });

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(queryClient.getQueryData(queryKeys.ticketDetail(TICKET_ID))).toEqual({
        title: 'confirmed title',
      }),
    );
  });
});
