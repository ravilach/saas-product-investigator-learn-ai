import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { ThemeToggle } from '../theme/ThemeToggle';
import styles from './AppLayout.module.css';

/** Props for {@link TopBar}. */
export interface TopBarProps {
  /** Renders the hamburger and hides the desktop-only affordances. */
  compact: boolean;
  /** Opens the navigation drawer. */
  onOpenNav: () => void;
  /** The current context line - usually the page or product name. */
  title?: string;
}

/**
 * The top bar: current context, theme toggle, account menu.
 *
 * The title is passed in rather than derived from the URL here. Route-to-title mapping in the top bar
 * means the bar has to know every route's display name, and a product detail page's real title is the
 * product's name - which only that page has fetched. So each page sets it (see `usePageTitle`).
 *
 * @param props see {@link TopBarProps}
 * @returns the top bar
 */
export function TopBar({ compact, onOpenNav, title }: TopBarProps) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  /** Closes the account menu on an outside click or Escape - standard menu behaviour, both halves. */
  useEffect(() => {
    if (!menuOpen) return;

    const onPointerDown = (event: MouseEvent) => {
      if (!menuRef.current?.contains(event.target as Node)) setMenuOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };

    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [menuOpen]);

  const signOut = () => {
    setMenuOpen(false);
    logout();
    // Replace rather than push, so Back cannot return to a page that now has no data behind it.
    navigate('/login', { replace: true });
  };

  const initials = user ? `${user.firstName[0] ?? ''}${user.lastName[0] ?? ''}`.toUpperCase() : '';

  return (
    <header className={styles.topbar}>
      {compact ? (
        <button
          type="button"
          className="btn btn-ghost btn-icon"
          onClick={onOpenNav}
          aria-label="Open navigation"
        >
          <MenuIcon />
        </button>
      ) : null}

      <div className={styles.context}>
        {title ? <span className={styles.contextTitle}>{title}</span> : null}
      </div>

      <div className={styles.topbarActions}>
        <ThemeToggle />

        <div className={styles.accountMenu} ref={menuRef}>
          <button
            type="button"
            className={styles.avatarButton}
            onClick={() => setMenuOpen((open) => !open)}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            aria-label={user ? `Account menu for ${user.firstName} ${user.lastName}` : 'Account menu'}
          >
            <span className={styles.avatar} aria-hidden="true">
              {initials}
            </span>
          </button>

          {menuOpen && user ? (
            <div className={styles.menuPanel} role="menu">
              <div className={styles.menuHeader}>
                <strong className={styles.menuName}>
                  {user.firstName} {user.lastName}
                </strong>
                <span className={styles.menuMeta}>{user.username}</span>
                {/* The role is worth showing: it explains why the Admin Console is or is not
                    in the sidebar, which is otherwise a mystery to a READ_ONLY user. */}
                <span className={`pill ${user.role === 'ADMIN' ? 'pill-running' : 'pill-neutral'}`}>
                  {user.role === 'ADMIN' ? 'Admin' : 'Read only'}
                </span>
              </div>

              <Link
                to="/account"
                className={styles.menuItem}
                role="menuitem"
                onClick={() => setMenuOpen(false)}
              >
                Account Settings
              </Link>

              <button
                type="button"
                className={styles.menuItem}
                role="menuitem"
                onClick={signOut}
              >
                Log out
              </button>
            </div>
          ) : null}
        </div>
      </div>
    </header>
  );
}

/** @returns a hamburger glyph */
function MenuIcon() {
  return (
    <svg
      width="20"
      height="20"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <path d="M4 7h16M4 12h16M4 17h16" />
    </svg>
  );
}
