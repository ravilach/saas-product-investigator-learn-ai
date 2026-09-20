import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import { Route, Routes } from 'react-router-dom';
import { RequireAdmin, RequireAuth } from './guards';
import {
  jsonResponse,
  makeUser,
  renderWithProviders,
} from '../test/renderWithProviders';

/**
 * Route guard tests.
 *
 * The cases worth covering are the two easy bugs: bouncing a *signed-in* user to the login page
 * because their stored token had not finished validating yet, and silently redirecting a READ_ONLY
 * user away from an admin URL so that a shared link looks broken rather than forbidden.
 */
describe('RequireAuth', () => {
  /**
   * Renders a protected route plus a stand-in login page.
   *
   * @param signedInAs the user to sign in first, if any
   * @returns the render result
   */
  function renderGuarded(signedInAs?: ReturnType<typeof makeUser>) {
    return renderWithProviders(
      <Routes>
        <Route path="/login" element={<p>Login screen</p>} />
        <Route element={<RequireAuth />}>
          <Route path="/secret" element={<p>Protected content</p>} />
        </Route>
      </Routes>,
      { route: '/secret', signedInAs },
    );
  }

  it('redirects an anonymous visitor to the login page', async () => {
    vi.stubGlobal('fetch', vi.fn());
    renderGuarded();

    expect(await screen.findByText('Login screen')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('lets a signed-in user through without bouncing via the login page', async () => {
    // /api/auth/me is what the auth provider calls to re-validate a restored session.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(makeUser())));

    renderGuarded(makeUser());

    expect(await screen.findByText('Protected content')).toBeInTheDocument();
    // The regression this guards against: a reload that flashes the login page before the token
    // finishes validating, losing whatever page the user was on.
    expect(screen.queryByText('Login screen')).not.toBeInTheDocument();
  });

  it('keeps the user signed in when /api/auth/me fails for a reason other than 401', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    renderGuarded(makeUser());

    // The backend being unreachable is not evidence the token is invalid. Signing the user out here
    // would drop them on a login page that also cannot work.
    expect(await screen.findByText('Protected content')).toBeInTheDocument();
  });
});

describe('RequireAdmin', () => {
  /**
   * Renders an admin-only route as the given user.
   *
   * @param role the signed-in user's role
   * @returns the render result
   */
  function renderAdminRoute(role: 'ADMIN' | 'READ_ONLY') {
    const user = makeUser({ role });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(user)));

    return renderWithProviders(
      <Routes>
        <Route element={<RequireAdmin />}>
          <Route path="/admin" element={<p>Admin console</p>} />
        </Route>
      </Routes>,
      { route: '/admin', signedInAs: user },
    );
  }

  it('renders the console for an admin', async () => {
    renderAdminRoute('ADMIN');
    expect(await screen.findByText('Admin console')).toBeInTheDocument();
  });

  it('explains the refusal to a read-only user rather than redirecting', async () => {
    renderAdminRoute('READ_ONLY');

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument());
    expect(screen.getByText('Admins only')).toBeInTheDocument();
    expect(screen.queryByText('Admin console')).not.toBeInTheDocument();

    // A 403 is not retryable, so no "Try again" button - offering one would be a small lie.
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });
});
