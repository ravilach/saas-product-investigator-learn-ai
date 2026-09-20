import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { apiFetch, setUnauthorizedHandler } from '../api/client';
import type { LoginResponse, Role, User } from '../api/types';
import { useToast } from '../components/toast/ToastProvider';
import {
  clearSession,
  loadSession,
  onSessionChange,
  saveSession,
  sessionFromLogin,
} from './session';

/**
 * Who is signed in, and the two operations that change that.
 *
 * The context holds the *user*; the token lives in `session.ts` (see the note there on why it is not
 * in React state). What this provider owns is the lifecycle around it: restoring a session on reload,
 * re-validating it against the server, and tearing it down on sign-out or on any 401.
 */

/** What {@link useAuth} returns. */
export interface AuthContextValue {
  /** The signed-in user, or `null`. */
  user: User | null;
  /** `true` while the stored session is still being validated on first load. */
  initialising: boolean;
  /** Convenience for `user !== null`. */
  isAuthenticated: boolean;
  /**
   * `true` if the signed-in user has the ADMIN role.
   *
   * Used only to decide what to *render*. The backend enforces the same rule with `@PreAuthorize`,
   * and that is the enforcement - hiding a button is a courtesy to the user, not a control. Anything
   * that relied on this flag for security would be defeated by the browser devtools.
   */
  isAdmin: boolean;
  /**
   * Exchanges credentials for a session.
   *
   * @param username the login identifier - the username, not the email
   * @param password the password
   * @returns the authenticated user
   * @throws ApiError with status 401 on bad credentials
   */
  login: (username: string, password: string) => Promise<User>;
  /** Discards the session and clears cached data. */
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Provides authentication state to the app.
 *
 * Must be rendered inside both the TanStack Query provider (it clears the cache on sign-out) and the
 * toast provider (it explains an expired session).
 *
 * @param props.children the app
 * @returns the provider
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  // Seeded synchronously from storage so a reload renders as signed-in immediately rather than
  // bouncing through the login page and back once /api/auth/me answers.
  const [user, setUser] = useState<User | null>(() => loadSession()?.user ?? null);
  const [initialising, setInitialising] = useState(() => loadSession() !== null);

  const queryClient = useQueryClient();
  const { showToast } = useToast();

  // Guards against a burst of parallel 401s each announcing the same sign-out.
  const signingOut = useRef(false);

  const logout = useCallback(() => {
    clearSession();
    setUser(null);
    // Cached data belongs to the user who fetched it. Without this, signing in as a READ_ONLY user
    // after an admin would briefly render the admin's cached lists.
    queryClient.clear();
  }, [queryClient]);

  /**
   * Re-validates the stored token against the server on first load.
   *
   * The stored user object is only as fresh as the last login, so a role change or a rename would
   * otherwise persist until the token expired - and, more importantly, a token invalidated by a JWT
   * secret rotation would look valid locally. `/api/auth/me` reads from the database on purpose for
   * exactly this, per its own Javadoc.
   */
  useEffect(() => {
    if (!loadSession()) {
      setInitialising(false);
      return;
    }

    let active = true;
    apiFetch<User>('/api/auth/me')
      .then((fresh) => {
        if (!active) return;
        setUser(fresh);
        // Keep the persisted copy in step, so the next reload starts from the current role.
        const session = loadSession();
        if (session) saveSession({ ...session, user: fresh });
      })
      .catch(() => {
        // A 401 has already been handled by the unauthorized handler below. Anything else - the
        // backend being down, say - should not sign a user out: they may well still have a valid
        // token, and bouncing them to a login page they also cannot use helps nobody. The queries
        // each view fires will surface the real problem in their own error states.
      })
      .finally(() => {
        if (active) setInitialising(false);
      });

    return () => {
      active = false;
    };
  }, []);

  /** Installs the app-wide 401 reaction. */
  useEffect(() => {
    return setUnauthorizedHandler(() => {
      if (signingOut.current || !loadSession()) return;
      signingOut.current = true;

      logout();
      showToast({
        tone: 'warning',
        title: 'Signed out',
        // Deliberately names the rotation case: it is the one reason a session dies mid-use rather
        // than from sitting idle, and it is the confusing one if unexplained.
        message:
          'Your session is no longer valid. This happens when it expires, or when an admin rotates the signing secret.',
      });

      // Reset after the current task so a simultaneous second 401 is still suppressed, but a real
      // later sign-out is not.
      window.setTimeout(() => {
        signingOut.current = false;
      }, 0);
    });
  }, [logout, showToast]);

  /** Follows sign-outs performed in another tab (see `onSessionChange`). */
  useEffect(() => {
    return onSessionChange((session) => {
      if (!session) setUser(null);
    });
  }, []);

  const login = useCallback(
    async (username: string, password: string) => {
      const response = await apiFetch<LoginResponse>('/api/auth/login', {
        method: 'POST',
        body: { username, password },
        // No point sending a stale bearer token to the one endpoint that does not read it.
        authenticated: false,
      });

      saveSession(sessionFromLogin(response));
      // Anything cached from a previous user in this tab is not this user's to see.
      queryClient.clear();
      setUser(response.user);
      return response.user;
    },
    [queryClient],
  );

  const value = useMemo<AuthContextValue>(
    () => ({
      user,
      initialising,
      isAuthenticated: user !== null,
      isAdmin: user?.role === 'ADMIN',
      login,
      logout,
    }),
    [user, initialising, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/**
 * Reads the current authentication state.
 *
 * @returns the auth context
 * @throws Error if called outside an {@link AuthProvider}
 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth must be used inside an AuthProvider.');
  return context;
}

/**
 * Whether a user holds a role.
 *
 * @param user the user, or `null`
 * @param role the role to check for
 * @returns `true` if the user has that role
 */
export function hasRole(user: User | null, role: Role): boolean {
  return user?.role === role;
}
