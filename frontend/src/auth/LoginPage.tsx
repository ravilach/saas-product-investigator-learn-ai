import { useId, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/ApiError';
import { ThemeToggle } from '../theme/ThemeToggle';
import { useAuth } from './AuthContext';
import styles from './LoginPage.module.css';

/** Router state set by {@link RequireAuth} when it redirects here. */
interface LocationState {
  from?: string;
}

/**
 * The login screen: username and password, in exchange for a JWT.
 *
 * Login is by **username**, not email - the backend's `AuthController` is explicit about that, and
 * the field label says so rather than leaving someone to discover it by failing.
 *
 * Validation is deliberately thin. Both fields are required, and that is the only thing checked
 * client-side: any further rule here (a minimum length, a character class) would be guessing at the
 * server's rules and would reject a valid password with a message the server never sent.
 *
 * @returns the login page
 */
export function LoginPage() {
  const { login, isAuthenticated, initialising } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // useId keeps label/input association correct even if this form is ever rendered twice on a page.
  const usernameId = useId();
  const passwordId = useId();
  const errorId = useId();

  const destination = (location.state as LocationState | null)?.from ?? '/';

  // Someone who is already signed in has no business here; send them where they were going. Also
  // covers the reload case, where the session is restored before this page would have rendered.
  if (!initialising && isAuthenticated) return <Navigate to={destination} replace />;

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);

    if (!username.trim() || !password) {
      setError('Enter both your username and password.');
      return;
    }

    setSubmitting(true);
    try {
      await login(username.trim(), password);
      navigate(destination, { replace: true });
    } catch (cause) {
      // The backend returns an identical 401 for an unknown username and a wrong password, so that
      // nobody can enumerate valid usernames from this form. Its message is shown as-is rather than
      // being "improved" into something more specific, because specificity is the leak.
      setError(ApiError.messageFrom(cause));
      setPassword('');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className={styles.page}>
      <div className={styles.themeCorner}>
        <ThemeToggle />
      </div>

      <main className={styles.panel}>
        <div className={styles.lockup}>
          <span className={styles.mark} aria-hidden="true">
            SPI
          </span>
          <h1 className={styles.title}>SaaS Product Investigator</h1>
          <p className={styles.tagline}>
            Track how the SaaS products you depend on change over time.
          </p>
        </div>

        <form className={styles.form} onSubmit={submit} noValidate>
          <div className="field">
            <label className="label" htmlFor={usernameId}>
              Username
            </label>
            <input
              id={usernameId}
              className="input"
              name="username"
              type="text"
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
              autoFocus
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              aria-invalid={error !== null}
              aria-describedby={error ? errorId : undefined}
              disabled={submitting}
            />
            <span className="hint">Sign in with your username, not your email address.</span>
          </div>

          <div className="field">
            <label className="label" htmlFor={passwordId}>
              Password
            </label>
            <input
              id={passwordId}
              className="input"
              name="password"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              aria-invalid={error !== null}
              aria-describedby={error ? errorId : undefined}
              disabled={submitting}
            />
          </div>

          {/*
            role="alert" so the failure is announced rather than only appearing. Rendered inside the
            form, above the button, because that is where a keyboard user's focus already is.
          */}
          {error ? (
            <p id={errorId} className={styles.error} role="alert">
              {error}
            </p>
          ) : null}

          <button type="submit" className={`btn btn-primary ${styles.submit}`} disabled={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </main>
    </div>
  );
}

export default LoginPage;
