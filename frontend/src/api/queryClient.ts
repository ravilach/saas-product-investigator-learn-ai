import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './ApiError';

/**
 * The application's TanStack Query client and its query-key registry.
 *
 * REACTIVITY & RESPONSIVE DESIGN asks for two specific behaviours from the cache, and the defaults
 * below are where they come from: a view does not refetch data it already has just because it
 * mounted again (`staleTime`), and a mutation invalidates the keys it affected so the relevant list
 * or detail re-renders without a manual reload (see the `queryKeys` registry).
 */

/**
 * Creates a configured query client.
 *
 * A factory rather than a module-level singleton so each test gets an isolated cache - a shared one
 * leaks state between test files in ways that are maddening to debug.
 *
 * @returns a query client with this app's defaults applied
 */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        /**
         * Data is considered fresh for 30s. Navigating dashboard → product → back re-renders from
         * cache instead of re-requesting, which is the point PERFORMANCE makes about leaning on the
         * cache rather than re-fetching on every mount. Anything that needs to be live (a run's
         * progress) is driven by SSE, not by polling, so a short stale time buys nothing there.
         */
        staleTime: 30_000,
        gcTime: 5 * 60_000,

        /**
         * Retrying a 401, 403, or 404 cannot succeed: the token is not going to become valid, the
         * role is not going to change, and the document is not going to reappear. Retrying them
         * delays the error message the user needs and triples the server load doing it. A 500 or a
         * dropped connection is worth one more attempt.
         */
        retry: (failureCount, error) => {
          if (error instanceof ApiError) {
            if (error.status === 401 || error.status === 403 || error.status === 404) return false;
            if (error.status >= 400 && error.status < 500) return false;
          }
          return failureCount < 1;
        },

        // The window regaining focus is not evidence that server data changed, and a refetch storm
        // on every tab switch is the most common complaint about default Query setups.
        refetchOnWindowFocus: false,
      },
      mutations: {
        // A failed write must not be retried automatically: several of this app's mutations are not
        // idempotent (creating a product, resetting a password), and a silent second attempt after a
        // timeout is how duplicates appear.
        retry: false,
      },
    },
  });
}

/**
 * Every query key the app uses, in one place.
 *
 * Invalidation is only reliable if the invalidating mutation and the query it affects derive their
 * key from the same expression. Scattered inline arrays are how you get a mutation that appears to
 * work but leaves a stale list on screen - the bug REACTIVITY & RESPONSIVE DESIGN is warning about.
 *
 * Keys are hierarchical, so invalidating a prefix invalidates everything under it: invalidating
 * `queryKeys.products.all` catches every page of the paginated list at once.
 */
export const queryKeys = {
  /** The authenticated user, from `/api/auth/me`. */
  currentUser: ['currentUser'] as const,

  products: {
    all: ['products'] as const,
    list: (page: number, size: number) => ['products', 'list', page, size] as const,
    detail: (id: string) => ['products', 'detail', id] as const,
    reports: (id: string, page: number) => ['products', id, 'reports', page] as const,
  },

  users: {
    all: ['users'] as const,
    list: () => ['users', 'list'] as const,
  },

  credentials: {
    /** The caller's own BYOK credentials. */
    mine: ['credentials', 'mine'] as const,
    /** The admin-set system-wide overrides. */
    system: ['credentials', 'system'] as const,
    /** The JWT signing secret's configured/source status - never its value. */
    jwtSecret: ['credentials', 'jwtSecret'] as const,
  },

  admin: {
    stats: ['admin', 'stats'] as const,
    health: ['admin', 'health'] as const,
    settings: ['admin', 'settings'] as const,
    auditLogs: (filters: Record<string, unknown>) => ['admin', 'auditLogs', filters] as const,
    collections: ['admin', 'collections'] as const,
    documents: (collection: string, page: number) =>
      ['admin', 'collections', collection, 'documents', page] as const,
    document: (collection: string, id: string) =>
      ['admin', 'collections', collection, 'documents', id] as const,
  },
} as const;
