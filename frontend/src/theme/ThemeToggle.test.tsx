import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from './ThemeProvider';
import { ThemeToggle } from './ThemeToggle';

/**
 * Stubs `prefers-color-scheme`.
 *
 * @param prefersDark what the OS should claim to prefer
 */
function stubColorScheme(prefersDark: boolean) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: (query: string) => ({
      matches: query.includes('dark') ? prefersDark : false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

/**
 * Renders the toggle in a provider.
 *
 * @returns the render result
 */
function renderToggle() {
  return render(
    <ThemeProvider>
      <ThemeToggle />
    </ThemeProvider>,
  );
}

describe('ThemeToggle', () => {
  it('defaults to the system preference when nothing is stored', () => {
    stubColorScheme(true);
    renderToggle();

    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
    // The button offers the *other* theme, which is the convention it is named for.
    expect(screen.getByRole('button', { name: 'Switch to light mode' })).toBeInTheDocument();
  });

  it('flips the theme, the attribute, and the stored preference on click', async () => {
    stubColorScheme(false);
    renderToggle();

    expect(document.documentElement).toHaveAttribute('data-theme', 'light');

    await userEvent.click(screen.getByRole('button', { name: 'Switch to dark mode' }));

    // The attribute is the whole mechanism: tokens.css redeclares every colour under
    // [data-theme='dark'], so if this is wrong nothing else about the theme can be right.
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
    expect(localStorage.getItem('spi.theme')).toBe('dark');
    expect(screen.getByRole('button', { name: 'Switch to light mode' })).toBeInTheDocument();
  });

  it('prefers an explicit stored choice over the system preference', () => {
    localStorage.setItem('spi.theme', 'light');
    // The OS says dark; the user has said light. The user wins - that is the point of the toggle.
    stubColorScheme(true);
    renderToggle();

    expect(document.documentElement).toHaveAttribute('data-theme', 'light');
  });

  it('says it is following the system setting until a choice is made', async () => {
    stubColorScheme(false);
    renderToggle();

    expect(screen.getByRole('button')).toHaveAttribute(
      'title',
      'Following your system setting (light). Click for dark mode.',
    );

    await userEvent.click(screen.getByRole('button'));

    // Once chosen, the tooltip stops claiming to follow the OS, because it no longer does.
    expect(screen.getByRole('button')).toHaveAttribute('title', 'Switch to light mode');
  });

  it('ignores a junk stored value rather than applying it', () => {
    localStorage.setItem('spi.theme', 'chartreuse');
    stubColorScheme(false);
    renderToggle();

    expect(document.documentElement).toHaveAttribute('data-theme', 'light');
  });
});
