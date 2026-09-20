import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LoginPage } from './LoginPage';
import { jsonResponse, makeLoginResponse, renderWithProviders } from '../test/renderWithProviders';
import { loadSession } from './session';

/**
 * Login form tests.
 *
 * `fetch` is stubbed rather than `apiFetch`, so these exercise the real client: the bearer-header
 * logic, the error-body parsing, and the session write are all on the path under test. Mocking
 * `apiFetch` would leave all three untested and would still pass if the client stopped sending the
 * request body.
 */
describe('LoginPage', () => {
  it('requires both fields before it will call the API', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    renderWithProviders(<LoginPage />, { route: '/login' });
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Enter both your username and password.',
    );
    // The point of the assertion: a form that validates but submits anyway is the bug.
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('posts the credentials and stores the session on success', async () => {
    const response = makeLoginResponse('ADMIN');
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(response));
    vi.stubGlobal('fetch', fetchMock);

    renderWithProviders(<LoginPage />, { route: '/login' });

    await userEvent.type(screen.getByLabelText('Username'), 'admin');
    await userEvent.type(screen.getByLabelText('Password'), 'admin');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/auth/login');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ username: 'admin', password: 'admin' });

    // The login call must not send a bearer token - it is the one endpoint that does not read one.
    expect(new Headers(init.headers).has('Authorization')).toBe(false);

    await waitFor(() => expect(loadSession()?.token).toBe('test-token'));
  });

  it('trims a username with stray whitespace rather than sending it', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(makeLoginResponse()));
    vi.stubGlobal('fetch', fetchMock);

    renderWithProviders(<LoginPage />, { route: '/login' });

    await userEvent.type(screen.getByLabelText('Username'), '  admin  ');
    await userEvent.type(screen.getByLabelText('Password'), 'admin');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    // A copy-pasted username with a trailing space would otherwise fail a login that should work.
    expect(JSON.parse(init.body as string).username).toBe('admin');
  });

  it("shows the server's own message on a rejected login and clears the password", async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            error: 'UNAUTHORIZED',
            message: 'Invalid username or password.',
            timestamp: new Date().toISOString(),
            path: '/api/auth/login',
          },
          401,
        ),
      ),
    );

    renderWithProviders(<LoginPage />, { route: '/login' });

    await userEvent.type(screen.getByLabelText('Username'), 'admin');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    // Shown verbatim: the backend returns the same message for an unknown username and a wrong
    // password on purpose, so that this form cannot be used to enumerate usernames. Rewriting it
    // into something more specific here would undo that.
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('Invalid username or password.'),
    );

    expect(screen.getByLabelText('Password')).toHaveValue('');
    expect(loadSession()).toBeNull();
  });

  it('reports an unreachable backend in plain language', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    renderWithProviders(<LoginPage />, { route: '/login' });

    await userEvent.type(screen.getByLabelText('Username'), 'admin');
    await userEvent.type(screen.getByLabelText('Password'), 'admin');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));

    // "Failed to fetch" is the raw browser message and tells a user nothing. ERROR HANDLING asks for
    // something actionable instead.
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(
        'Could not reach the server. Check that the backend is running.',
      ),
    );
  });

  it('labels the username field as a username, not an email', () => {
    vi.stubGlobal('fetch', vi.fn());
    renderWithProviders(<LoginPage />, { route: '/login' });

    // Login is by username per the backend's AuthController; saying so up front saves a failed
    // attempt with an email address.
    expect(
      screen.getByText('Sign in with your username, not your email address.'),
    ).toBeInTheDocument();
  });
});
