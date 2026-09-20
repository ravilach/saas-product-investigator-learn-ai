import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { apiFetch } from './client';
import { queryKeys } from './queryClient';
import type { Page, SaasProduct, SaasProductRequest } from './types';

/**
 * Queries and mutations for tracked SaaS products.
 *
 * Every mutation here invalidates through {@link queryKeys} rather than writing its own key array,
 * which is what makes "the list updates after a create without a reload" reliable instead of
 * incidental.
 */

/** The dashboard's page size. One request covers a realistic instance without a pager. */
export const PRODUCTS_PAGE_SIZE = 24;

/**
 * Lists tracked products, newest first.
 *
 * @param page zero-based page number
 * @param size page size
 * @returns the query result for one page of products
 */
export function useProducts(page = 0, size = PRODUCTS_PAGE_SIZE): UseQueryResult<Page<SaasProduct>> {
  return useQuery({
    queryKey: queryKeys.products.list(page, size),
    queryFn: () => apiFetch<Page<SaasProduct>>('/api/saas-products', { query: { page, size } }),
  });
}

/**
 * Loads one product with its sources.
 *
 * @param id the product id, or `undefined` while the route param is not yet known
 * @returns the query result for the product
 */
export function useProduct(id: string | undefined): UseQueryResult<SaasProduct> {
  return useQuery({
    queryKey: queryKeys.products.detail(id ?? ''),
    queryFn: () => apiFetch<SaasProduct>(`/api/saas-products/${id}`),
    // `enabled` rather than a non-null assertion: a route param can legitimately be undefined for a
    // render, and firing `/api/saas-products/undefined` would produce a 404 toast for a non-problem.
    enabled: Boolean(id),
  });
}

/**
 * Creates a product.
 *
 * @returns the mutation; resolves with the created product, whose `id` the caller navigates to
 */
export function useCreateProduct(): UseMutationResult<SaasProduct, Error, SaasProductRequest> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (request: SaasProductRequest) =>
      apiFetch<SaasProduct>('/api/saas-products', { method: 'POST', body: request }),
    onSuccess: (created) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.products.all });
      // Seeded so navigating straight to the detail page renders it without a second round trip -
      // the response body is the same shape that page's query wants.
      queryClient.setQueryData(queryKeys.products.detail(created.id), created);
    },
  });
}

/**
 * Updates a product, including its whole source list.
 *
 * The backend treats `sources` as a replacement rather than a patch, which is why the edit form
 * always submits the complete list.
 *
 * @returns the mutation
 */
export function useUpdateProduct(
  id: string,
): UseMutationResult<SaasProduct, Error, SaasProductRequest> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (request: SaasProductRequest) =>
      apiFetch<SaasProduct>(`/api/saas-products/${id}`, { method: 'PUT', body: request }),
    onSuccess: (updated) => {
      queryClient.setQueryData(queryKeys.products.detail(id), updated);
      queryClient.invalidateQueries({ queryKey: queryKeys.products.all });
    },
  });
}

/**
 * Deletes a product and everything the backend cascades with it.
 *
 * @returns the mutation
 */
export function useDeleteProduct(): UseMutationResult<void, Error, string> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/api/saas-products/${id}`, { method: 'DELETE' }),
    onSuccess: (_result, id) => {
      // Removed rather than invalidated: refetching a product that was just deleted produces a 404,
      // and a 404 error state is a worse thing to show than nothing at all.
      queryClient.removeQueries({ queryKey: queryKeys.products.detail(id) });
      queryClient.invalidateQueries({ queryKey: queryKeys.products.all });
    },
  });
}
