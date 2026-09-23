import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { CommentResponse } from '@/lib/api/types';
import { CommentList } from './CommentList';

const COMMENT: CommentResponse = {
  id: 'cccccccc-0000-0000-0000-000000000001',
  author: 'bob',
  content: 'Looking into it.',
  createdAt: '2026-09-05T10:15:30Z',
};

describe('CommentList', () => {
  it('should show the no-comments indication only when the collection is empty', () => {
    // Requirement 3.7: the no-comments indication appears iff there are none.
    render(<CommentList comments={[]} />);

    expect(screen.getByTestId('no-comments')).toHaveTextContent('No comments yet.');
    // The comment list itself must not render alongside the empty indication.
    expect(screen.queryByRole('list', { name: 'Comments' })).not.toBeInTheDocument();
  });

  it('should render the comment list and no indication when comments are present', () => {
    render(<CommentList comments={[COMMENT]} />);

    expect(screen.queryByTestId('no-comments')).not.toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Comments' })).toBeInTheDocument();
    expect(screen.getByText('Looking into it.')).toBeInTheDocument();
  });
});
