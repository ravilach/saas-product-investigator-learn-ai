import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

/**
 * Light/dark theme state, persisted to `localStorage` and applied as `data-theme` on `<html>`.
 *
 * The switch itself costs nothing at runtime: `tokens.css` redeclares every colour custom property
 * under `[data-theme='dark']`, so setting one attribute repaints the whole app through the cascade.
 * No component subscribes to the theme in order to pick a colour, and none should - a component that
 * branches on the theme in JavaScript is a component that will be missed the next time a token
 * changes.
 */

/** The two concrete themes. There is no `'system'` value stored - see {@link ThemeContextValue}. */
export type Theme = 'light' | 'dark';

/** What {@link useTheme} returns. */
export interface ThemeContextValue {
  /** The theme currently applied. Always concrete, never `'system'`. */
  theme: Theme;
  /**
   * `true` while the theme is following the OS preference - i.e. nothing has been explicitly
   * chosen yet. Lets the toggle's tooltip say "following your system setting" rather than implying
   * the user picked light mode when they did not.
   */
  followingSystem: boolean;
  /** Switches to the other theme and persists the choice. */
  toggleTheme: () => void;
  /**
   * Sets a specific theme and persists it.
   *
   * @param theme the theme to apply
   */
  setTheme: (theme: Theme) => void;
}

const STORAGE_KEY = 'spi.theme';
const ATTRIBUTE = 'data-theme';

const ThemeContext = createContext<ThemeContextValue | null>(null);

/**
 * Reads the persisted preference.
 *
 * @returns the stored theme, or `null` when the user has not chosen one
 */
function storedTheme(): Theme | null {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === 'light' || value === 'dark' ? value : null;
  } catch {
    // Storage blocked. The app still themes correctly for this page load, it just cannot remember.
    return null;
  }
}

/**
 * The OS-level preference.
 *
 * @returns `'dark'` if the system asks for dark mode, otherwise `'light'`
 */
function systemTheme(): Theme {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/**
 * Provides theme state to the app.
 *
 * The initial value is read synchronously during the first render rather than in an effect. An
 * effect would run after the first paint, which is one frame of the wrong theme - the same flash
 * `index.html`'s inline script exists to prevent before React even loads.
 *
 * @param props.children the app
 * @returns the provider
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [explicit, setExplicit] = useState<Theme | null>(() => storedTheme());
  const [system, setSystem] = useState<Theme>(() => systemTheme());

  const theme = explicit ?? system;

  // Keeps following the OS while nothing has been explicitly chosen, so a user whose machine
  // switches to dark at sunset sees the app follow - which is the behaviour they asked their OS for.
  useEffect(() => {
    const query = window.matchMedia?.('(prefers-color-scheme: dark)');
    if (!query?.addEventListener) return;

    const onChange = (event: MediaQueryListEvent) => setSystem(event.matches ? 'dark' : 'light');
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);

  useEffect(() => {
    document.documentElement.setAttribute(ATTRIBUTE, theme);
    // Tells the browser to theme its own chrome too - form controls, scrollbars, the autofill
    // background - which otherwise stay light and look pasted-on over the navy.
    document.documentElement.style.colorScheme = theme;
  }, [theme]);

  const setTheme = useCallback((next: Theme) => {
    setExplicit(next);
    try {
      localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Non-fatal: the choice applies now and is forgotten on reload.
    }
  }, []);

  const toggleTheme = useCallback(() => {
    setTheme((explicit ?? system) === 'dark' ? 'light' : 'dark');
  }, [explicit, system, setTheme]);

  const value = useMemo<ThemeContextValue>(
    () => ({ theme, followingSystem: explicit === null, toggleTheme, setTheme }),
    [theme, explicit, toggleTheme, setTheme],
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

/**
 * Reads the current theme and its setters.
 *
 * @returns the theme context
 * @throws Error if called outside a {@link ThemeProvider}
 */
export function useTheme(): ThemeContextValue {
  const context = useContext(ThemeContext);
  if (!context) throw new Error('useTheme must be used inside a ThemeProvider.');
  return context;
}
