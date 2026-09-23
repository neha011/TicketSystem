import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Providers } from './providers';
import './globals.css';

export const metadata: Metadata = {
  title: 'Support Ticket Management',
  description: 'Create, track, and resolve customer support tickets.',
};

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Providers>
          <a className="skip-link" href="#main">
            Skip to main content
          </a>
          <header>
            <h1>Support Tickets</h1>
          </header>
          <main id="main">{children}</main>
        </Providers>
      </body>
    </html>
  );
}
