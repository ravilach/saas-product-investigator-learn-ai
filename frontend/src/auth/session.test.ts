import { describe, expect, it } from 'vitest';
import { clearSession, currentToken, loadSession, saveSession } from './session';
import { makeUser } from '../test/renderWithProviders';

/**
 * Builds a session whose token expires at the given offset from now.
 *
 * @param offsetMs milliseconds from now; negative for an already-expired token
 * @returns the session
 */
function sessionExpiringIn(offsetMs: number) {
  return {
    token: 'token-123',
    expiresAt: new Date(Date.now() + offsetMs).toISOString(),
    user: makeUser(),
  };
}

describe('session storage', () => {
  it('round-trips a session', () => {
    saveSession(sessionExpiringIn(3_600_000));

    expect(loadSession()?.token).toBe('token-123');
    expect(currentToken()).toBe('token-123');
  });

  it('treats an expired token as no session, and clears it', () => {
    saveSession(sessionExpiringIn(-1_000));

    // Keeping it would mean the app renders as signed-in, fires its first query, and only then
    // discovers the 401 - a worse first impression than the login page.
    expect(loadSession()).toBeNull();
    expect(localStorage.getItem('spi.session')).toBeNull();
  });

  it('discards a stored value it cannot parse', () => {
    localStorage.setItem('spi.session', 'not json at all');

    expect(loadSession()).toBeNull();
    expect(localStorage.getItem('spi.session')).toBeNull();
  });

  it('discards a session missing its token', () => {
    localStorage.setItem(
      'spi.session',
      JSON.stringify({ expiresAt: new Date(Date.now() + 1000).toISOString(), user: makeUser() }),
    );

    expect(loadSession()).toBeNull();
  });

  it('clears on request', () => {
    saveSession(sessionExpiringIn(3_600_000));
    clearSession();

    expect(loadSession()).toBeNull();
    expect(currentToken()).toBeNull();
  });
});
