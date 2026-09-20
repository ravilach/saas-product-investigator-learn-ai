import { SkeletonText } from './Skeleton';

/**
 * The Suspense fallback for a lazily-loaded route.
 *
 * Shown for the moment between clicking a link and that page's chunk arriving - the direct cost of
 * the route-level code-splitting PERFORMANCE asks for. It is deliberately a page-shaped skeleton
 * rather than a spinner, so the transition looks the same as a slow data load rather than
 * introducing a second visual language for waiting.
 *
 * @returns the fallback
 */
export function PageLoader() {
  return (
    <div className="stack" style={{ padding: 'var(--space-5) 0', maxWidth: '640px' }}>
      <SkeletonText lines={4} label="Loading page" />
    </div>
  );
}
