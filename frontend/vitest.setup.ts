import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// Tests must be independent and order-independent: unmount every rendered tree
// so no DOM state leaks into the next test.
afterEach(() => {
  cleanup();
});
