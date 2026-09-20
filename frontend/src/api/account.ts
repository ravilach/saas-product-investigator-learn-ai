import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { apiFetch } from './client';
import { queryKeys } from './queryClient';
import type { CredentialStatus, LlmProviderType, User } from './types';

/**
 * The signed-in user's own settings: their bring-your-own-key LLM credentials and which provider
 * they prefer.
 *
 * These endpoints live under `/api/users/me` and are available to both roles - a READ_ONLY user can
 * still run analyses, so they still need a key of their own. See `MeCredentialController`, which
 * explains why "me" is a path segment rather than a user id.
 */

/**
 * Lists which providers the caller has a personal key stored for.
 *
 * @returns the query result; each entry carries `last4` at most, never the key
 */
export function useMyCredentials(): UseQueryResult<CredentialStatus[]> {
  return useQuery({
    queryKey: queryKeys.credentials.mine,
    queryFn: () => apiFetch<CredentialStatus[]>('/api/users/me/credentials'),
  });
}

/** The body for storing a personal key. */
export interface SaveCredentialParams {
  provider: LlmProviderType;
  apiKey: string;
}

/**
 * Stores or replaces the caller's personal key for one provider.
 *
 * @returns the mutation, resolving with the new masked status
 */
export function useSaveMyCredential(): UseMutationResult<
  CredentialStatus,
  Error,
  SaveCredentialParams
> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (params: SaveCredentialParams) =>
      apiFetch<CredentialStatus>('/api/users/me/credentials', { method: 'POST', body: params }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.credentials.mine });
      // A newly resolvable provider changes what the Admin Console's health tab reports, so that is
      // invalidated too rather than being left to go stale until someone reloads.
      queryClient.invalidateQueries({ queryKey: queryKeys.admin.health });
    },
  });
}

/**
 * Removes the caller's personal key for one provider.
 *
 * @returns the mutation
 */
export function useDeleteMyCredential(): UseMutationResult<void, Error, LlmProviderType> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (provider: LlmProviderType) =>
      apiFetch<void>(`/api/users/me/credentials/${provider}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.credentials.mine });
      queryClient.invalidateQueries({ queryKey: queryKeys.admin.health });
    },
  });
}

/**
 * Sets which provider the caller's runs should use.
 *
 * Resolves with the updated user record. The caller is expected to hand that to the auth context's
 * `applyUser`, because the signed-in user is held there and in the persisted session rather than in
 * the query cache - leaving it to go stale would show the new preference on this page and the old one
 * everywhere else.
 *
 * @returns the mutation, resolving with the updated user
 */
export function useSetPreferredProvider(): UseMutationResult<User, Error, LlmProviderType | null> {
  return useMutation({
    mutationFn: (provider: LlmProviderType | null) =>
      apiFetch<User>('/api/users/me/preferred-provider', { method: 'PUT', body: { provider } }),
  });
}
