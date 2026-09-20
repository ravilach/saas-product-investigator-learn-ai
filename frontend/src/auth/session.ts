import type { LoginResponse, User } from '../api/types';

/**
 * The stored session: where the JWT lives between page loads, and the one place that knows the
 * storage key.
 *
 * This module is deliberately plain TypeScript with no React in it. The API client needs the token
 * on every request and the auth context needs to write it on login, and if that state lived in the
 * context then the client would have to import the context - a cycle, and a rule that the token is
 * only reachable from inside the component tree. A small shared module is the simpler shape.
 *
 * **On `localStorage` versus a cookie.** A token in `localStorage` is readable by any script that
 * gets injected into the page, where an `HttpOnly` cookie would not be. The tradeoff is accepted
 * here because the backend is stateless-JWT by design (see its `AuthController`: there is no logout
 * endpoint, and no session to attach a cookie to) and because a cookie would need CSRF protection
 * that the current API does not implement. It is the standard shape for this stack, not the most
 * hardened one available - worth knowing which of those you are choosing.
 */

/** Everything persisted about a signed-in user. */
export interface Session {
  /** The raw JWT, sent as `Authorization: Bearer <token>`. */
  token: string;
  /** ISO-8601 expiry, from the login response. */
  expiresAt: string;
  /** The user as the server described them at login. Refreshed from `/api/auth/me` on reload. */
  user: User;
}

const STORAGE_KEY = 'spi.session';

/** Notified whenever the stored session changes, including from another tab. */
type Listener = (session: Session | null) => void;

const listeners = new Set<Listener>();

/**
 * Reads the persisted session, if it is still usable.
 *
 * An expired token is treated as no session at all, and cleared on the spot: keeping it would mean
 * the app renders as signed-in, fires its first query, and only then discovers the 401 - a worse
 * first impression than the login page.
 *
 * @returns the stored session, or `null` if absent, unparsable, or expired
 */
export function loadSession(): Session | null {
  let raw: string | null;
  try {
    raw = localStorage.getItem(STORAGE_KEY);
  } catch {
    // Private-browsing modes and blocked third-party storage both throw here. The app still works,
    // it just cannot remember you past a reload.
    return null;
  }
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as Session;
    if (!parsed?.token || !parsed?.user || !parsed?.expiresAt) return null;
    if (new Date(parsed.expiresAt).getTime() <= Date.now()) {
      clearSession();
      return null;
    }
    return parsed;
  } catch {
    // Written by an older version of the app, or hand-edited. Drop it rather than guess.
    clearSession();
    return null;
  }
}

/**
 * Persists a session and notifies listeners.
 *
 * @param session the session to store
 */
export function saveSession(session: Session): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  } catch {
    // Non-fatal: the session stays in memory for this tab, it just will not survive a reload.
  }
  listeners.forEach((listener) => listener(session));
}

/** Removes the stored session and notifies listeners. */
export function clearSession(): void {
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Nothing to do - if we could not read it we cannot remove it either.
  }
  listeners.forEach((listener) => listener(null));
}

/**
 * Returns the bearer token for the current session, or `null`.
 *
 * Read on every request by the API client rather than captured once, so that a rotated or cleared
 * token takes effect on the next call instead of the next reload.
 *
 * @returns the raw JWT, or `null` when signed out
 */
export function currentToken(): string | null {
  return loadSession()?.token ?? null;
}

/**
 * Builds a session from a login response.
 *
 * @param response the body of a successful `POST /api/auth/login`
 * @returns the session to persist
 */
export function sessionFromLogin(response: LoginResponse): Session {
  return { token: response.token, expiresAt: response.expiresAt, user: response.user };
}

/**
 * Subscribes to session changes, including changes made in another browser tab.
 *
 * The cross-tab half matters for a specific flow this app really has: an admin rotating the JWT
 * signing secret signs out every session, their own included. Without the `storage` listener, a
 * second tab would sit there looking signed in until someone clicked something.
 *
 * @param listener called with the new session, or `null` on sign-out
 * @returns an unsubscribe function
 */
export function onSessionChange(listener: Listener): () => void {
  listeners.add(listener);

  const onStorage = (event: StorageEvent) => {
    if (event.key !== STORAGE_KEY && event.key !== null) return;
    listener(loadSession());
  };
  window.addEventListener('storage', onStorage);

  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}
