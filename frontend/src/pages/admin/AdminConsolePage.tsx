import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { usePageTitle } from '../../layout/usePageTitle';
import { UnderConstruction } from '../UnderConstruction';
import styles from './AdminConsolePage.module.css';

/**
 * The consolidated Admin Console and its sub-nav.
 *
 * DESIGN & UX asks for one sidebar entry rather than separate top-level items for Users, Audit Log,
 * Settings and so on, so those are tabs here. They are real nested routes rather than local tab state
 * for two reasons: a deep link to the Audit Log has to work, and the browser's Back button has to move
 * between tabs the way a user expects it to.
 *
 * The role check lives on the route that renders this page (see `routes.tsx`), not inside it.
 *
 * @returns the admin console
 */
export function AdminConsolePage() {
  usePageTitle('Admin Console');

  return (
    <div className="stack">
      <h1>Admin Console</h1>

      {/* Horizontally scrollable on a phone rather than wrapping into two rows of tabs, which reads
          as two separate navigations. */}
      <nav className={styles.tabs} aria-label="Admin Console sections">
        <AdminTab to="/admin/overview" label="Overview" />
        <AdminTab to="/admin/users" label="Users" />
        <AdminTab to="/admin/secrets" label="Secrets" />
        <AdminTab to="/admin/audit-log" label="Audit Log" />
        <AdminTab to="/admin/settings" label="Settings" />
        <AdminTab to="/admin/data-explorer" label="Data Explorer" />
      </nav>

      <Routes>
        <Route index element={<Navigate to="/admin/overview" replace />} />
        <Route
          path="overview"
          element={
            <UnderConstruction
              page="Overview"
              description="Will show the stat cards from /api/admin/stats and the status list from /api/admin/health, plus a link to /swagger-ui.html."
            />
          }
        />
        <Route
          path="users"
          element={
            <UnderConstruction
              page="Users"
              description="Will list users with a per-row password reset dialog - which sets a new password and never displays the current one."
            />
          }
        />
        <Route
          path="secrets"
          element={
            <UnderConstruction
              page="Secrets"
              description="Will list the provider overrides and the JWT signing secret with a source indicator each, and a confirmation dialog on the JWT row."
            />
          }
        />
        <Route
          path="audit-log"
          element={
            <UnderConstruction
              page="Audit Log"
              description="Will show a filterable, paginated table of actions, actor, target, and timestamp."
            />
          }
        />
        <Route
          path="settings"
          element={
            <UnderConstruction
              page="Settings"
              description="Will hold the crawl defaults from /api/admin/settings."
            />
          }
        />
        <Route
          path="data-explorer"
          element={
            <UnderConstruction
              page="Data Explorer"
              description="Will show a collection picker, a paginated document table, and a detail panel where masked fields render as a disabled [encrypted] chip."
            />
          }
        />
        <Route path="*" element={<Navigate to="/admin/overview" replace />} />
      </Routes>
    </div>
  );
}

/**
 * One tab in the admin sub-nav.
 *
 * @param props.to the tab's path
 * @param props.label the tab's text
 * @returns the tab link
 */
function AdminTab({ to, label }: { to: string; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) => `${styles.tab} ${isActive ? styles.tabActive : ''}`}
    >
      {label}
    </NavLink>
  );
}

export default AdminConsolePage;
