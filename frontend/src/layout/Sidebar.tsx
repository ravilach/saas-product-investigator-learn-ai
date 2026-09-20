import { useEffect, useRef } from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import styles from './AppLayout.module.css';

/** Props for {@link Sidebar}. */
export interface SidebarProps {
  /** `true` when rendering as a slide-over drawer rather than a persistent rail. */
  compact: boolean;
  /** Whether the drawer is open (compact) or the rail is expanded (desktop). */
  open: boolean;
  /** Closes the drawer, or collapses the rail. */
  onClose: () => void;
  /** Toggles the desktop rail between full width and icons-only. */
  onToggleCollapse: () => void;
}

/**
 * The left navigation.
 *
 * One component, two presentations, as REACTIVITY & RESPONSIVE DESIGN requires: a persistent
 * collapsible rail on desktop, and a slide-over drawer behind the top bar's hamburger below the
 * desktop breakpoint. Sharing the component rather than writing two is what keeps the nav items from
 * diverging - a link added for desktop and forgotten on mobile is the usual result of splitting them.
 *
 * Per DESIGN & UX the admin area is a *single* "Admin Console" entry rather than separate top-level
 * items for Users, Audit Log, Settings, and so on. Those are its sub-nav, not siblings of the product
 * list.
 *
 * @param props see {@link SidebarProps}
 * @returns the sidebar
 */
export function Sidebar({ compact, open, onClose, onToggleCollapse }: SidebarProps) {
  const { isAdmin } = useAuth();
  const panelRef = useRef<HTMLElement>(null);
  const collapsed = !compact && !open;

  /**
   * Drawer-only behaviour: Escape closes it, and focus moves inside on open.
   *
   * Only wired up in compact mode. On desktop the sidebar is an ordinary part of the page, and
   * stealing focus or swallowing Escape there would be surprising rather than helpful.
   */
  useEffect(() => {
    if (!compact || !open) return;

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);

    // Moving focus into the drawer is what makes it usable by keyboard and screen reader; without
    // it, focus stays on the hamburger and Tab walks through the page behind the overlay.
    panelRef.current?.querySelector<HTMLElement>('a, button')?.focus();

    // The page behind a modal drawer must not scroll under it.
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [compact, open, onClose]);

  const className = [
    styles.sidebar,
    compact ? styles.sidebarDrawer : styles.sidebarRail,
    compact && open ? styles.sidebarDrawerOpen : '',
    collapsed ? styles.sidebarCollapsed : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <>
      {/* The scrim is only rendered in compact mode, where the drawer is genuinely modal. */}
      {compact && open ? (
        <div className={styles.scrim} onClick={onClose} aria-hidden="true" />
      ) : null}

      <nav
        ref={panelRef}
        className={className}
        aria-label="Main navigation"
        // A drawer is modal and is hidden from the accessibility tree when closed; a rail is neither.
        aria-modal={compact ? open : undefined}
        role={compact ? 'dialog' : undefined}
        aria-hidden={compact && !open ? true : undefined}
      >
        <div className={styles.sidebarHeader}>
          <span className={styles.lockup}>
            <span className={styles.lockupMark} aria-hidden="true">
              SPI
            </span>
            {!collapsed ? <span className={styles.lockupText}>Investigator</span> : null}
          </span>

          {compact ? (
            <button
              type="button"
              className="btn btn-ghost btn-icon"
              onClick={onClose}
              aria-label="Close navigation"
            >
              <CloseIcon />
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-ghost btn-icon"
              onClick={onToggleCollapse}
              aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
              title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            >
              <ChevronIcon direction={collapsed ? 'right' : 'left'} />
            </button>
          )}
        </div>

        <ul className={styles.navList}>
          <NavItem to="/" label="SaaS Products" collapsed={collapsed} onNavigate={onClose}>
            <GridIcon />
          </NavItem>

          {/* Admin-only, and only a rendering decision - the backend enforces the role itself. */}
          {isAdmin ? (
            <NavItem
              to="/admin"
              label="Admin Console"
              collapsed={collapsed}
              onNavigate={onClose}
            >
              <ShieldIcon />
            </NavItem>
          ) : null}
        </ul>

        <div className={styles.sidebarFooter}>
          <NavItem
            to="/account"
            label="Account Settings"
            collapsed={collapsed}
            onNavigate={onClose}
          >
            <UserIcon />
          </NavItem>
        </div>
      </nav>
    </>
  );
}

/** Props for {@link NavItem}. */
interface NavItemProps {
  to: string;
  label: string;
  collapsed: boolean;
  /** Called after a navigation, so the drawer closes itself on mobile. */
  onNavigate: () => void;
  children: React.ReactNode;
}

/**
 * One navigation row.
 *
 * `NavLink` rather than `Link` because it supplies the active state from the router, so the
 * highlighted row cannot disagree with the URL the way a manually-tracked `activeTab` would.
 *
 * @param props see {@link NavItemProps}
 * @returns the row
 */
function NavItem({ to, label, collapsed, onNavigate, children }: NavItemProps) {
  return (
    <li>
      <NavLink
        to={to}
        // `end` on the root so "SaaS Products" is not permanently active on every nested route.
        end={to === '/'}
        className={({ isActive }) =>
          `${styles.navLink} ${isActive ? styles.navLinkActive : ''}`
        }
        onClick={onNavigate}
        // Collapsed to icons: the visible text is gone, so the label has to survive as a title and
        // an accessible name.
        title={collapsed ? label : undefined}
        aria-label={collapsed ? label : undefined}
      >
        <span className={styles.navIcon} aria-hidden="true">
          {children}
        </span>
        {!collapsed ? <span className={styles.navLabel}>{label}</span> : null}
      </NavLink>
    </li>
  );
}

/** @returns a 3x3 grid glyph, for the product list */
function GridIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <rect x="3" y="3" width="8" height="8" rx="2" />
      <rect x="13" y="3" width="8" height="8" rx="2" />
      <rect x="3" y="13" width="8" height="8" rx="2" />
      <rect x="13" y="13" width="8" height="8" rx="2" />
    </svg>
  );
}

/** @returns a shield glyph, for the Admin Console */
function ShieldIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <path d="M12 3l7 3v6c0 4.2-2.9 7.9-7 9-4.1-1.1-7-4.8-7-9V6l7-3Z" />
    </svg>
  );
}

/** @returns a person glyph, for Account Settings */
function UserIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx="12" cy="8" r="3.5" />
      <path d="M5 20c0-3.3 3.1-6 7-6s7 2.7 7 6" />
    </svg>
  );
}

/** @returns an X glyph */
function CloseIcon() {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  );
}

/**
 * @param props.direction which way the chevron points
 * @returns a chevron glyph
 */
function ChevronIcon({ direction }: { direction: 'left' | 'right' }) {
  return (
    <svg
      width="18"
      height="18"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d={direction === 'left' ? 'M15 5l-7 7 7 7' : 'M9 5l7 7-7 7'} />
    </svg>
  );
}
