import type { ReactElement, ReactNode } from 'react';
import { render, type RenderResult } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { createQueryClient } from '../api/queryClient';
import { AuthProvider } from '../auth/AuthContext';
import { ToastProvider } from '../components/toast/ToastProvider';
import { ThemeProvider } from '../theme/ThemeProvider';
import type { LoginResponse, Role, User } from '../api/types';
import { saveSession, sessionFromLogin } from '../auth/session';

/** Options for {@link renderWithProviders}. */
export interface RenderOptions {
  /** Initial router entries. Defaults to `['/']`. */
  route?: string;
  /** Signs a user in before rendering, by writing a session as a real login would. */
  signedInAs?: User;
}

/**
 * Renders a component inside the same provider stack `App` uses.
 *
 * The stack is duplicated here rather than importing `App`, because a test needs `MemoryRouter`
 * instead of `BrowserRouter` and its own query client - a shared client leaks cached data between
 * test files. The order matches `App`'s for the reason documented there: `AuthProvider` depends on
 * both the query client and the toast queue.
 *
 * @param ui the element under test
 * @param options see {@link RenderOptions}
 * @returns Testing Library's render result
 */
export function renderWithProviders(ui: ReactElement, options: RenderOptions = {}): RenderResult {
  const { route = '/', signedInAs } = options;

  if (signedInAs) {
    saveSession(
      sessionFromLogin({
        token: 'test-token',
        tokenType: 'Bearer',
        // An hour out, so `loadSession` does not treat it as expired mid-test.
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        user: signedInAs,
      }),
    );
  }

  const queryClient = createQueryClient();

  const Wrapper = ({ children }: { children: ReactNode }) => (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <MemoryRouter initialEntries={[route]}>
            <AuthProvider>{children}</AuthProvider>
          </MemoryRouter>
        </ToastProvider>
      </QueryClientProvider>
    </ThemeProvider>
  );

  return render(ui, { wrapper: Wrapper });
}

/**
 * Builds a test user.
 *
 * @param overrides fields to replace on the default admin
 * @returns a user
 */
export function makeUser(overrides: Partial<User> = {}): User {
  return {
    id: 'user-1',
    firstName: 'Admin',
    lastName: 'User',
    username: 'admin',
    email: 'admin@localhost',
    role: 'ADMIN',
    preferredLlmProvider: null,
    createdAt: new Date('2026-01-01T00:00:00Z').toISOString(),
    ...overrides,
  };
}

/**
 * Builds a login response for a user.
 *
 * @param role the role to issue it for
 * @returns a login response whose token is valid for an hour
 */
export function makeLoginResponse(role: Role = 'ADMIN'): LoginResponse {
  return {
    token: 'test-token',
    tokenType: 'Bearer',
    expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    user: makeUser({ role }),
  };
}

/**
 * Builds a `Response` carrying a JSON body, for stubbing `fetch`.
 *
 * @param body the JSON body
 * @param status the HTTP status; defaults to 200
 * @returns a response the API client will parse
 */
export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}
