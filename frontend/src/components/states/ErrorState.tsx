import type { ReactNode } from 'react';
import { ApiError } from '../../api/ApiError';
import styles from './States.module.css';

/** Props for {@link ErrorState}. */
export interface ErrorStateProps {
  /** The thrown value. Rendered through {@link ApiError.messageFrom}, never stringified raw. */
  error: unknown;
  /** Overrides the heading. Defaults to something appropriate for the error's status. */
  title?: string;
  /** Wire to a query's `refetch` to offer a retry. Omitted when retrying cannot help. */
  onRetry?: () => void;
  /** Extra actions, e.g. a link to Account Settings when no LLM provider is configured. */
  children?: ReactNode;
}

/**
 * The inline error panel used wherever a view's own content failed to load.
 *
 * The counterpart to a toast: this one takes the place of the content, because there is no content
 * to show (see the note in `ToastProvider`). Every async view in the app renders one of these for its
 * error state rather than inventing its own, so a failure looks the same on the Dashboard as it does
 * in the Audit Log.
 *
 * A retry button is only offered when the caller passes `onRetry` *and* the error is one a retry
 * could plausibly fix. Offering "try again" on a 403 is a small lie.
 *
 * @param props see {@link ErrorStateProps}
 * @returns the error panel
 */
export function ErrorState({ error, title, onRetry, children }: ErrorStateProps) {
  const apiError = error instanceof ApiError ? error : null;
  const retryable = !apiError || apiError.status === 0 || apiError.status >= 500;

  return (
    <div className={styles.error} role="alert">
      <h3 className={styles.errorHeading}>{title ?? defaultTitle(apiError)}</h3>
      <p className={styles.errorMessage}>{ApiError.messageFrom(error)}</p>
      {(onRetry && retryable) || children ? (
        <div className={styles.actions}>
          {onRetry && retryable ? (
            <button type="button" className="btn btn-secondary btn-sm" onClick={onRetry}>
              Try again
            </button>
          ) : null}
          {children}
        </div>
      ) : null}
    </div>
  );
}

/**
 * Picks a heading that matches what actually went wrong.
 *
 * @param error the API error, or `null` for a non-API failure
 * @returns the heading text
 */
function defaultTitle(error: ApiError | null): string {
  if (!error) return 'Something went wrong';
  if (error.status === 0) return 'Cannot reach the server';
  if (error.isForbidden) return 'Not allowed';
  if (error.status === 404) return 'Not found';
  if (error.status >= 500) return 'Server error';
  return 'Could not load this';
}
