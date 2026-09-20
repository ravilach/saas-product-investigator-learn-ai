import { lazy } from 'react';
import { Route, Routes } from 'react-router-dom';
import { AppLayout } from './layout/AppLayout';
import { RequireAdmin, RequireAuth } from './auth/guards';

/**
 * The route table.
 *
 * Every page is a `lazy()` import, which is the route-level code-splitting PERFORMANCE asks for:
 * Vite emits one chunk per page, and the initial download is the shell plus the login screen rather
 * than the whole app including an Admin Console that most users never open. The `Suspense` boundary
 * these resolve against lives in `AppLayout` (and in `App` for the login route, which is outside the
 * layout).
 *
 * The guards are layout routes rather than checks inside each page, so the auth requirement sits
 * next to the paths it covers and a page added later inherits it by position. See `guards.tsx` for
 * why that is a rendering concern only.
 */

const LoginPage = lazy(() => import('./auth/LoginPage'));
const DashboardPage = lazy(() => import('./pages/DashboardPage'));
const ProductDetailPage = lazy(() => import('./pages/ProductDetailPage'));
const ProductFormPage = lazy(() => import('./pages/ProductFormPage'));
const AccountSettingsPage = lazy(() => import('./pages/AccountSettingsPage'));
const AdminConsolePage = lazy(() => import('./pages/admin/AdminConsolePage'));
const NotFoundPage = lazy(() => import('./pages/NotFoundPage'));

/**
 * Declares every route in the app.
 *
 * @returns the route tree
 */
export function AppRoutes() {
  return (
    <Routes>
      {/* Outside the shell: no sidebar or top bar to show someone who is not signed in yet. */}
      <Route path="/login" element={<LoginPage />} />

      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route index element={<DashboardPage />} />

          {/* `new` before `:id` so the literal path is not swallowed by the parameter. */}
          <Route path="products/new" element={<ProductFormPage />} />
          <Route path="products/:productId" element={<ProductDetailPage />} />
          <Route path="products/:productId/edit" element={<ProductFormPage />} />

          <Route path="account" element={<AccountSettingsPage />} />

          {/*
            The Admin Console owns its own sub-nav, so it takes a splat: its tabs are its business,
            not the top-level route table's. The role guard wraps it here rather than being checked
            inside it, so a tab added later cannot forget the check.
          */}
          <Route element={<RequireAdmin />}>
            <Route path="admin/*" element={<AdminConsolePage />} />
          </Route>

          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  );
}
