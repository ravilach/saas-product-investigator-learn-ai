import { Suspense, useCallback, useEffect, useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { ErrorBoundary } from '../components/ErrorBoundary';
import { PageLoader } from '../components/states/PageLoader';
import { useIsCompactLayout } from '../hooks/useMediaQuery';
import { Sidebar } from './Sidebar';
import { TopBar } from './TopBar';
import { PageTitleContext } from './usePageTitle';
import styles from './AppLayout.module.css';

const COLLAPSE_KEY = 'spi.sidebarCollapsed';

/**
 * The signed-in application shell: sidebar, top bar, and the routed page between them.
 *
 * Holds three pieces of state that outlive any single page - the sidebar's open/collapsed state, the
 * top bar's context line, and the inner error boundary - which is exactly why it is a layout route
 * rather than something each page renders for itself. A page that rendered its own chrome would reset
 * the sidebar on every navigation.
 *
 * @returns the shell
 */
export function AppLayout() {
  const compact = useIsCompactLayout();
  const location = useLocation();

  // Two meanings for one flag, by viewport: in compact mode `navOpen` is "the drawer is showing",
  // and on desktop it is "the rail is expanded". They are the same control, so they share the state,
  // and each presentation reads it as its own thing - see Sidebar.
  // `compact` is part of the initial value, not only of the effect below: the effect runs after the
  // first paint, so seeding this from the saved desktop preference alone makes a phone render one
  // frame with the drawer open and then animate it shut on every single page load.
  const [navOpen, setNavOpen] = useState(() => !compact && !restoreCollapsed());
  const [pageTitle, setPageTitle] = useState<string | undefined>(undefined);

  /**
   * Re-resolves the sidebar when crossing the breakpoint.
   *
   * Without this, opening the drawer on a phone and then rotating to landscape would leave the
   * desktop rail expanded-or-not according to a decision made about a drawer, which is not the same
   * question. Crossing into compact closes the drawer; crossing out restores the saved preference.
   */
  useEffect(() => {
    setNavOpen(compact ? false : !restoreCollapsed());
  }, [compact]);

  // The drawer is modal, so it must not stay open across a navigation - and on desktop, navigating
  // should not disturb the rail at all.
  useEffect(() => {
    if (compact) setNavOpen(false);
  }, [location.pathname, compact]);

  const closeNav = useCallback(() => setNavOpen(false), []);

  const toggleCollapse = useCallback(() => {
    setNavOpen((open) => {
      const next = !open;
      try {
        localStorage.setItem(COLLAPSE_KEY, String(!next));
      } catch {
        // Non-fatal: the rail just forgets its width on the next reload.
      }
      return next;
    });
  }, []);

  return (
    <PageTitleContext.Provider value={setPageTitle}>
      <div className={`${styles.shell} ${!compact && !navOpen ? styles.shellCollapsed : ''}`}>
        <Sidebar
          compact={compact}
          open={navOpen}
          onClose={closeNav}
          onToggleCollapse={toggleCollapse}
        />

        <div className={styles.main}>
          <TopBar compact={compact} onOpenNav={() => setNavOpen(true)} title={pageTitle} />

          {/*
            A second error boundary, inside the chrome. The top-level one in App.tsx catches a crash
            in the shell itself; this one catches a crash in a page and leaves the sidebar and top bar
            working, so the user can navigate away instead of reloading. Its resetKey is the pathname,
            so doing exactly that clears the error.

            The Suspense boundary is what makes the lazy route imports in routes.tsx work.
          */}
          <main className={styles.content} id="main-content">
            <ErrorBoundary resetKey={location.pathname}>
              <Suspense fallback={<PageLoader />}>
                <Outlet />
              </Suspense>
            </ErrorBoundary>
          </main>
        </div>
      </div>
    </PageTitleContext.Provider>
  );
}

/**
 * Reads the persisted desktop sidebar preference.
 *
 * @returns `true` if the rail was last left collapsed
 */
function restoreCollapsed(): boolean {
  try {
    return localStorage.getItem(COLLAPSE_KEY) === 'true';
  } catch {
    return false;
  }
}
