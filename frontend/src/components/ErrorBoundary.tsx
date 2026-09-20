import { Component, type ErrorInfo, type ReactNode } from 'react';
import { ApiError } from '../api/ApiError';
import styles from './ErrorBoundary.module.css';

/** Props for {@link ErrorBoundary}. */
export interface ErrorBoundaryProps {
  children: ReactNode;
  /**
   * Rendered instead of the default panel. Receives the error and a reset callback, so a route-level
   * boundary can offer "back to dashboard" where the top-level one can only offer "reload".
   */
  fallback?: (error: Error, reset: () => void) => ReactNode;
  /**
   * Changing this value resets the boundary. Set it to the current pathname at route level so
   * navigating away from a broken page clears the error instead of showing it on the next page too.
   */
  resetKey?: string;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * Catches render-time exceptions so one broken view does not blank the whole app.
 *
 * ERROR HANDLING asks for exactly this at the top level, and it is mounted twice on purpose: once
 * around the entire app (so a crash in the shell still shows something) and once inside the layout
 * around the routed page (so a crash in one page keeps the sidebar and top bar usable, which is the
 * difference between "navigate somewhere else" and "reload and lose your place").
 *
 * Still a class component, because `componentDidCatch` has no hook equivalent - this is the one
 * place in the React API where that is true, not an oversight in this codebase.
 *
 * Note what it does **not** catch: errors thrown in an event handler, a `setTimeout`, or a rejected
 * promise. Those never pass through React's render path. API failures are handled by TanStack Query
 * and the toast layer instead, which is why both exist alongside this.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidUpdate(previous: ErrorBoundaryProps) {
    if (this.state.error && previous.resetKey !== this.props.resetKey) {
      this.setState({ error: null });
    }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // The console is the only sink this app has - there is no frontend error-reporting service
    // wired up. The component stack is the useful half: it names the component that threw, which
    // the message alone usually does not.
    console.error('Unhandled render error:', error, info.componentStack);
  }

  private reset = () => this.setState({ error: null });

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    if (this.props.fallback) return this.props.fallback(error, this.reset);

    return (
      <div className={styles.container} role="alert">
        <div className={styles.panel}>
          <h2 className={styles.heading}>Something broke on this screen</h2>
          <p className={styles.message}>{ApiError.messageFrom(error)}</p>
          <p className={styles.hint}>
            The rest of the app is probably fine. Try again, or reload if it keeps happening.
          </p>
          <div className={styles.actions}>
            <button type="button" className="btn btn-primary" onClick={this.reset}>
              Try again
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={() => window.location.reload()}
            >
              Reload the app
            </button>
          </div>
        </div>
      </div>
    );
  }
}
