import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { PageLoader } from '../components/states/PageLoader';
import { ErrorState } from '../components/states/ErrorState';
import { ApiError } from '../api/ApiError';

/**
 * Route guards.
 *
 * Both are rendered as layout routes wrapping the routes they protect, so the check lives in the
 * route table where it is visible next to the paths it covers - rather than being repeated as a
 * redirect inside each page component, where a new page can silently forget it.
 *
 * To say it once more because it matters: these control what renders, not what is permitted. The
 * backend's `@PreAuthorize` is the actual authorisation boundary, and it does not trust the client.
 */

/**
 * Requires a signed-in user; redirects to the login page if there is none.
 *
 * The attempted location is passed along in router state so the login page can return the user to
 * where they were headed - which matters most in the case that produced the redirect: a session that
 * expired while they were three levels into a product.
 *
 * @returns the nested routes, a loader, or a redirect
 */
export function RequireAuth() {
  const { isAuthenticated, initialising } = useAuth();
  const location = useLocation();

  // A stored token is still being validated. Redirecting now would bounce a signed-in user to the
  // login page on every reload, which is the classic version of this bug.
  if (initialising) return <PageLoader />;

  if (!isAuthenticated) {
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  }

  return <Outlet />;
}

/**
 * Requires the ADMIN role.
 *
 * A READ_ONLY user who reaches an admin URL directly - from a bookmark, or a link someone shared -
 * gets an explanation rather than a redirect. Silently bouncing them to the dashboard looks like a
 * broken link; saying "this needs an admin account" is the truth and is actionable.
 *
 * @returns the nested routes, or an explanation
 */
export function RequireAdmin() {
  const { isAdmin, initialising } = useAuth();

  if (initialising) return <PageLoader />;

  if (!isAdmin) {
    return (
      <div style={{ maxWidth: '560px' }}>
        <ErrorState
          error={
            new ApiError(
              403,
              'FORBIDDEN',
              'The Admin Console is only available to admin accounts. Ask an admin to grant you the role, or sign in with an admin account.',
            )
          }
          title="Admins only"
        />
      </div>
    );
  }

  return <Outlet />;
}
