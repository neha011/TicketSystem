'use client';

import { QueryClientProvider } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { createQueryClient } from '@/lib/api/queryClient';

/**
 * Wires the TanStack Query cache into the React tree.
 *
 * The client is held in `useState` so exactly one instance is created per mount
 * and it is never shared across server requests. Optimistic updates are
 * disabled by `createQueryClient` (Requirement 4.8).
 */
export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(createQueryClient);

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
