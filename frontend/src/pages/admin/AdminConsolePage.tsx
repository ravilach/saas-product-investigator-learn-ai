import { NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { usePageTitle } from '../../layout/usePageTitle';
import { AuditLogTab } from './AuditLogTab';
import { DataExplorerTab } from './DataExplorerTab';
import { OverviewTab } from './OverviewTab';
import { SecretsTab } from './SecretsTab';
import { SettingsTab } from './SettingsTab';
import { UsersTab } from './UsersTab';
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
        <Route path="overview" element={<OverviewTab />} />
        <Route path="users" element={<UsersTab />} />
        <Route path="secrets" element={<SecretsTab />} />
        <Route path="audit-log" element={<AuditLogTab />} />
        <Route path="settings" element={<SettingsTab />} />
        <Route path="data-explorer" element={<DataExplorerTab />} />
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
