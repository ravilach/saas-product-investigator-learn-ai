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
import { ApiError } from '../../api/ApiError';
import styles from './Toast.module.css';

/**
 * The app-wide toast queue: the one place a transient message appears, whatever raised it.
 *
 * ERROR HANDLING asks that a failed API call show "an inline error or toast with a human-readable
 * message, not a raw error object". The division of labour this app uses:
 *
 * - **Inline** ({@link ErrorState}) for a failure that means the view in front of you has nothing to
 *   show - a list that could not load. The error belongs where the content would have been.
 * - **Toast** (here) for a failure or success attached to an action you just took - saving a
 *   credential, resetting a password. The view is still fine; the news is about the action.
 *
 * {@link useToast}'s `showError` takes an `unknown` on purpose, because that is what a `catch` block
 * and a TanStack Query `onError` both actually hand you.
 */

/** How a toast is styled and announced. */
export type ToastTone = 'success' | 'error' | 'warning' | 'info';

/** A queued toast. */
export interface Toast {
  id: string;
  tone: ToastTone;
  message: string;
  /** Optional bold lead-in above the message, e.g. "Could not save source". */
  title?: string;
}

/** What {@link useToast} returns. */
export interface ToastContextValue {
  /**
   * Shows a toast.
   *
   * @param toast the tone, message, and optional title
   * @returns the new toast's id, so a caller can dismiss it early
   */
  showToast: (toast: Omit<Toast, 'id'>) => string;
  /**
   * Shows a success toast.
   *
   * @param message what happened
   */
  showSuccess: (message: string) => void;
  /**
   * Shows an error toast for any thrown value.
   *
   * A 401 is swallowed deliberately: the auth layer is already redirecting to the login screen with
   * its own explanation, and a toast on top of that is two notifications for one event.
   *
   * @param error the thrown value - an {@link ApiError}, an `Error`, or anything else
   * @param title optional lead-in naming the action that failed
   */
  showError: (error: unknown, title?: string) => void;
  /**
   * Dismisses a toast early.
   *
   * @param id the toast's id
   */
  dismissToast: (id: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

/** Errors linger; confirmations do not need to. */
const DURATIONS: Record<ToastTone, number> = {
  success: 4000,
  info: 5000,
  warning: 7000,
  error: 9000,
};

/** Beyond this, older toasts are dropped rather than stacking off the top of the screen. */
const MAX_VISIBLE = 4;

/**
 * Provides the toast queue and renders it.
 *
 * @param props.children the app
 * @returns the provider plus the live region the toasts render into
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const timers = useRef(new Map<string, number>());

  const dismissToast = useCallback((id: string) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
    const timer = timers.current.get(id);
    if (timer !== undefined) {
      window.clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const showToast = useCallback(
    (toast: Omit<Toast, 'id'>) => {
      const id = `toast-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      setToasts((current) => [...current, { ...toast, id }].slice(-MAX_VISIBLE));

      timers.current.set(
        id,
        window.setTimeout(() => dismissToast(id), DURATIONS[toast.tone]),
      );
      return id;
    },
    [dismissToast],
  );

  const showSuccess = useCallback(
    (message: string) => {
      showToast({ tone: 'success', message });
    },
    [showToast],
  );

  const showError = useCallback(
    (error: unknown, title?: string) => {
      // The sign-out flow owns the messaging for an expired session; see the note on showError.
      if (error instanceof ApiError && error.isUnauthorized) return;
      showToast({ tone: 'error', title, message: ApiError.messageFrom(error) });
    },
    [showToast],
  );

  // Clears pending timers on unmount, so a timeout cannot fire against a torn-down tree in tests.
  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach((timer) => window.clearTimeout(timer));
      pending.clear();
    };
  }, []);

  const value = useMemo<ToastContextValue>(
    () => ({ showToast, showSuccess, showError, dismissToast }),
    [showToast, showSuccess, showError, dismissToast],
  );

  return (
    <ToastContext.Provider value={value}>
      {children}
      {/*
        One live region, rendered whether or not there are toasts. A region that only appears along
        with its first message is often not announced at all, because assistive tech has nothing to
        have been watching. `polite` rather than `assertive` so a background success does not
        interrupt someone mid-sentence.
      */}
      <div className={styles.viewport} role="region" aria-label="Notifications">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={`${styles.toast} ${styles[toast.tone]}`}
            role={toast.tone === 'error' ? 'alert' : 'status'}
            aria-live={toast.tone === 'error' ? 'assertive' : 'polite'}
          >
            <div className={styles.content}>
              {toast.title ? <strong className={styles.title}>{toast.title}</strong> : null}
              <span className={styles.message}>{toast.message}</span>
            </div>
            <button
              type="button"
              className={styles.dismiss}
              onClick={() => dismissToast(toast.id)}
              aria-label="Dismiss notification"
            >
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

/**
 * Reads the toast API.
 *
 * @returns the toast context
 * @throws Error if called outside a {@link ToastProvider}
 */
export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) throw new Error('useToast must be used inside a ToastProvider.');
  return context;
}
