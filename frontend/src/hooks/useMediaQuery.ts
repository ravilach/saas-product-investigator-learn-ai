import { useEffect, useState } from 'react';

/**
 * The app's breakpoints, in one place, matching REACTIVITY & RESPONSIVE DESIGN: mobile (<640px),
 * tablet (640–1024px), desktop (>1024px).
 *
 * Declared here as well as in CSS because a handful of decisions genuinely cannot be made in CSS
 * alone - whether the sidebar renders as a drawer with a focus trap and an `aria-modal`, or as a
 * plain persistent nav, is a difference in markup and behaviour, not only in layout. Everything that
 * *can* be done with a media query in CSS is, and is not duplicated here.
 */
export const breakpoints = {
  mobile: 640,
  tablet: 1024,
} as const;

/**
 * Subscribes to a media query.
 *
 * @param query a CSS media query string, e.g. `'(max-width: 640px)'`
 * @returns whether the query currently matches
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => window.matchMedia?.(query).matches ?? false);

  useEffect(() => {
    const list = window.matchMedia?.(query);
    if (!list) return;

    // Read once on subscribe as well as on change: between the initial render and this effect the
    // viewport may already have changed (an orientation flip during hydration, a restored window).
    setMatches(list.matches);

    const onChange = (event: MediaQueryListEvent) => setMatches(event.matches);
    list.addEventListener?.('change', onChange);
    return () => list.removeEventListener?.('change', onChange);
  }, [query]);

  return matches;
}

/**
 * Whether the viewport is narrow enough that the sidebar should be a slide-over drawer.
 *
 * True below the desktop breakpoint - so on tablets too, not only phones. A 248px sidebar on a
 * 768px tablet leaves the content barely 500px wide, which is the "just shrinking" outcome the
 * responsive spec asks us to avoid; a drawer gives the content the whole width and costs one tap.
 *
 * @returns `true` on mobile and tablet widths
 */
export function useIsCompactLayout(): boolean {
  return useMediaQuery(`(max-width: ${breakpoints.tablet}px)`);
}

/**
 * Whether the viewport is phone-sized.
 *
 * @returns `true` below the mobile breakpoint
 */
export function useIsMobile(): boolean {
  return useMediaQuery(`(max-width: ${breakpoints.mobile}px)`);
}
