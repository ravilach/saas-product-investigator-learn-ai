import { createContext, useContext, useEffect } from 'react';

/** Setter published by {@link AppLayout} so the routed page can name itself in the top bar. */
export const PageTitleContext = createContext<((title: string | undefined) => void) | null>(null);

/**
 * Sets the top bar's context line for as long as the calling page is mounted.
 *
 * Inverted from the obvious design, where the top bar maps routes to titles. That version breaks on
 * the first page whose title is data: the product detail page's title is the product's name, which
 * only that page has fetched. Letting the page push its own title keeps the bar ignorant of routes
 * and lets the title arrive late, once the query resolves.
 *
 * Also sets `document.title`, since a browser tab is the other place the same string belongs.
 *
 * @param title the context line, or `undefined` while it is still loading
 */
export function usePageTitle(title: string | undefined): void {
  const setTitle = useContext(PageTitleContext);

  useEffect(() => {
    setTitle?.(title);
    document.title = title ? `${title} · SaaS Product Investigator` : 'SaaS Product Investigator';

    // Cleared on unmount so a stale title cannot survive into the next page during the moment
    // between one page unmounting and the next one's effect running.
    return () => setTitle?.(undefined);
  }, [title, setTitle]);
}
