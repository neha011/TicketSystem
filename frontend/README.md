# Ticket_UI — Support Ticket Management frontend

React/Next.js (App Router) frontend for the Support Ticket Management System. It
consumes the `ticket-service` REST API at `/api/v1` and holds no business rules
of its own: the backend is the sole authority on validity, and this app renders
only values the backend has confirmed.

## Running locally

```bash
npm install
cp .env.example .env.local     # point NEXT_PUBLIC_API_BASE_URL at your backend
npm run dev                    # http://localhost:3000
```

The backend must be running separately (`mvn spring-boot:run` from the repository
root).

## Running tests

```bash
npm test           # unit + property tests, single run
npm run test:watch # watch mode
npm run typecheck  # tsc --noEmit
npm run lint
npm run build
```

Unit and component tests use Vitest with React Testing Library. Property-based
tests use fast-check and live alongside the code they cover as
`<name>.property.test.ts`.

## Environment variables

| Variable | Required | Purpose |
|---|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | yes (defaults to `http://localhost:8080`) | Base URL of the ticket-service backend. Public by definition — never place secrets here. |

## Structure

```
src/
├── app/                        Next.js App Router
│   ├── layout.tsx              root layout, wraps the tree in Providers
│   ├── providers.tsx           TanStack Query provider
│   ├── page.tsx                ticket list route
│   └── tickets/
│       ├── new/page.tsx        create form route
│       └── [id]/page.tsx       detail route
├── lib/api/
│   ├── types.ts                wire-format types mirroring the backend DTOs
│   ├── queryKeys.ts            centralised cache keys
│   ├── queryClient.ts          QueryClient factory + optimistic-update guard
│   └── useConfirmedMutation.ts the only sanctioned way to run a write
└── test/renderWithProviders.tsx
```

## Why optimistic updates are disabled

Requirement 4.8 states that while a submitted ticket update awaits confirmation,
the UI must keep displaying the last backend-confirmed values and must not
display the submitted values as the ticket's current values. Optimistic updates
do precisely the opposite: they write the submitted payload into the query cache
before the backend has confirmed it.

This is enforced in three places rather than left to per-call-site discipline:

1. `useConfirmedMutation` omits `onMutate` from its options type, so a
   pre-confirmation cache write is a compile error.
2. `withoutOptimisticUpdate` throws at runtime if an `onMutate` callback is
   supplied through an untyped or dynamically assembled options object.
3. An ESLint rule flags any `onMutate` property anywhere in the source tree.

Confirmed values reach the cache only from a server response: a mutation
invalidates the affected keys in `onSuccess`, and the refetched server data is
what renders (Requirements 2.6, 4.7).
