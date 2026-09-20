import { Suspense } from 'react';
import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { createQueryClient } from './api/queryClient';
import { AuthProvider } from './auth/AuthContext';
import { ErrorBoundary } from './components/ErrorBoundary';
import { PageLoader } from './components/states/PageLoader';
import { ToastProvider } from './components/toast/ToastProvider';
import { AppRoutes } from './routes';
import { ThemeProvider } from './theme/ThemeProvider';

/**
 * Created once at module scope, so the cache survives re-renders of {@link App}. Recreating it
 * inside the component would throw away every cached query on any parent re-render, which looks like
 * a mysteriously slow app rather than like a bug.
 */
const queryClient = createQueryClient();

/**
 * The application root: the provider stack, the top-level error boundary, and the router.
 *
 * The nesting order is not arbitrary - each layer depends on the ones outside it:
 *
 * 1. `ErrorBoundary` outermost, so a crash anywhere below still renders something. It is outside the
 *    providers deliberately: if `ThemeProvider` itself threw, a boundary inside it would never run.
 * 2. `ThemeProvider` next, so even the error panel is themed correctly.
 * 3. `QueryClientProvider` before `ToastProvider`, and both before `AuthProvider`, because
 *    `AuthProvider` clears the query cache on sign-out and raises a toast when a session expires.
 * 4. `BrowserRouter` inside `AuthProvider`, since the guards and the login redirect read auth state.
 *
 * @returns the app
 */
export function App() {
  return (
    <ErrorBoundary>
      <ThemeProvider>
        <QueryClientProvider client={queryClient}>
          <ToastProvider>
            <BrowserRouter>
              <AuthProvider>
                {/* Catches the login route's lazy chunk; routes inside the shell suspend against
                    AppLayout's own boundary, which keeps the chrome on screen while they load. */}
                <Suspense fallback={<PageLoader />}>
                  <AppRoutes />
                </Suspense>
              </AuthProvider>
            </BrowserRouter>
          </ToastProvider>
        </QueryClientProvider>
      </ThemeProvider>
    </ErrorBoundary>
  );
}

export default App;
