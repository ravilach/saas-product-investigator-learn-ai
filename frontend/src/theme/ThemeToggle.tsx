import { useTheme } from './ThemeProvider';

/**
 * The top bar's light/dark switch.
 *
 * Renders the icon of the theme it will switch *to*, which is the convention most users already
 * read: a moon means "go dark", not "you are in dark mode". The accessible name says so in words
 * rather than relying on that convention holding.
 *
 * @returns the toggle button
 */
export function ThemeToggle() {
  const { theme, followingSystem, toggleTheme } = useTheme();
  const target = theme === 'dark' ? 'light' : 'dark';

  return (
    <button
      type="button"
      className="btn btn-ghost btn-icon"
      onClick={toggleTheme}
      aria-label={`Switch to ${target} mode`}
      title={
        followingSystem
          ? `Following your system setting (${theme}). Click for ${target} mode.`
          : `Switch to ${target} mode`
      }
    >
      {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
    </button>
  );
}

/**
 * Inline sun glyph.
 *
 * Icons are inline SVG rather than an icon library: there are about a dozen in the whole app, and a
 * dependency that ships a thousand of them would be most of the initial bundle for the sake of
 * those twelve. `currentColor` makes them theme-aware for free.
 *
 * @returns the icon
 */
function SunIcon() {
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
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M2 12h2M20 12h2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}

/**
 * Inline moon glyph.
 *
 * @returns the icon
 */
function MoonIcon() {
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
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
    </svg>
  );
}
