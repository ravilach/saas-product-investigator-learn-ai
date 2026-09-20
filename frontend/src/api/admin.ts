import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query';
import { apiFetch } from './client';
import { queryKeys } from './queryClient';
import type {
  AdminHealth,
  AdminSettings,
  AdminStats,
  AuditAction,
  AuditLogEntry,
  CollectionSummary,
  CreateUserRequest,
  ExplorerDocument,
  JwtSecretStatus,
  LlmProviderType,
  Page,
  SystemCredentialStatus,
  User,
} from './types';

/**
 * Everything behind the Admin Console.
 *
 * Every endpoint here is `@PreAuthorize("hasRole('ADMIN')")` on the server. The console is only
 * reachable through an admin-only route, but that is a convenience - a READ_ONLY user who typed the
 * URL gets a 403 from the backend, which is the actual enforcement.
 */

/** Overview: the aggregate counters. */
export function useAdminStats(): UseQueryResult<AdminStats> {
  return useQuery({
    queryKey: queryKeys.admin.stats,
    queryFn: () => apiFetch<AdminStats>('/api/admin/stats'),
  });
}

/** Overview: infrastructure and provider health. */
export function useAdminHealth(): UseQueryResult<AdminHealth> {
  return useQuery({
    queryKey: queryKeys.admin.health,
    queryFn: () => apiFetch<AdminHealth>('/api/admin/health'),
    // Health is the one thing on the page that is genuinely time-sensitive: it is what someone looks
    // at while a provider or the database is misbehaving, and a 30-second-stale answer there is
    // actively misleading.
    staleTime: 0,
  });
}

/** Settings: the crawl defaults, plus the ceilings the form must respect. */
export function useAdminSettings(): UseQueryResult<AdminSettings> {
  return useQuery({
    queryKey: queryKeys.admin.settings,
    queryFn: () => apiFetch<AdminSettings>('/api/admin/settings'),
  });
}

/** The editable half of {@link AdminSettings}. */
export interface AdminSettingsUpdate {
  defaultMaxDepth: number;
  defaultMaxPages: number;
}

/** Settings: saves new crawl defaults. */
export function useUpdateAdminSettings(): UseMutationResult<
  AdminSettings,
  Error,
  AdminSettingsUpdate
> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (update: AdminSettingsUpdate) =>
      apiFetch<AdminSettings>('/api/admin/settings', { method: 'PUT', body: update }),
    onSuccess: (settings) => {
      queryClient.setQueryData(queryKeys.admin.settings, settings);
      // Changing the defaults changes every product's *effective* crawl limits, which the product
      // detail page displays next to each source.
      queryClient.invalidateQueries({ queryKey: queryKeys.products.all });
    },
  });
}

/** Users: the full list. Small enough that the backend does not paginate it. */
export function useUsers(): UseQueryResult<User[]> {
  return useQuery({
    queryKey: queryKeys.users.list(),
    queryFn: () => apiFetch<User[]>('/api/users'),
  });
}

/** Users: creates one. */
export function useCreateUser(): UseMutationResult<User, Error, CreateUserRequest> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (request: CreateUserRequest) =>
      apiFetch<User>('/api/users', { method: 'POST', body: request }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.users.all });
      queryClient.invalidateQueries({ queryKey: queryKeys.admin.stats });
    },
  });
}

/** Users: deletes one. */
export function useDeleteUser(): UseMutationResult<void, Error, string> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/api/users/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.users.all });
      queryClient.invalidateQueries({ queryKey: queryKeys.admin.stats });
    },
  });
}

/** The arguments for a password reset. */
export interface ResetPasswordParams {
  id: string;
  newPassword: string;
}

/**
 * Users: sets a new password for someone.
 *
 * There is deliberately no endpoint that reads a password, so the dialog behind this only ever
 * writes - it cannot show the current one, because nothing can.
 */
export function useResetPassword(): UseMutationResult<User, Error, ResetPasswordParams> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ id, newPassword }: ResetPasswordParams) =>
      apiFetch<User>(`/api/users/${id}/password`, { method: 'PUT', body: { newPassword } }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: queryKeys.users.all }),
  });
}

/** Secrets: the system-wide provider keys and where each currently resolves from. */
export function useSystemCredentials(): UseQueryResult<SystemCredentialStatus[]> {
  return useQuery({
    queryKey: queryKeys.credentials.system,
    queryFn: () => apiFetch<SystemCredentialStatus[]>('/api/admin/system-credentials'),
  });
}

/** Secrets: sets a system-wide override for one provider. */
export function useSetSystemCredential(): UseMutationResult<
  SystemCredentialStatus,
  Error,
  { provider: LlmProviderType; apiKey: string }
> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (params) =>
      apiFetch<SystemCredentialStatus>('/api/admin/system-credentials', {
        method: 'PUT',
        body: params,
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.credentials.system });
      queryClient.invalidateQueries({ queryKey: queryKeys.admin.health });
    },
  });
}

/**
 * Secrets: clears a system-wide override.
 *
 * Clearing does not necessarily leave the provider unconfigured - resolution falls back to the
 * environment variable or the host mount - which is why the UI re-reads the source after this rather
 * than assuming the row becomes empty.
 */
export function useClearSystemCredential(): UseMutationResult<void, Error, LlmProviderType> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (provider: LlmProviderType) =>
      apiFetch<void>(`/api/admin/system-credentials/${provider}`, { method: 'DELETE' }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.credentials.system });
      queryClient.invalidateQueries({ queryKey: queryKeys.admin.health });
    },
  });
}

/** Secrets: where the JWT signing secret comes from. Never its value - see ADR 0006. */
export function useJwtSecretStatus(): UseQueryResult<JwtSecretStatus> {
  return useQuery({
    queryKey: queryKeys.credentials.jwtSecret,
    queryFn: () => apiFetch<JwtSecretStatus>('/api/admin/jwt-secret'),
  });
}

/**
 * Secrets: sets the JWT signing secret override.
 *
 * Every existing token, including the caller's own, stops verifying the moment this succeeds. The
 * next request therefore 401s and the app signs itself out - which is correct, and is why the UI puts
 * an explicit confirmation dialog in front of it rather than treating it as another save button.
 */
export function useSetJwtSecret(): UseMutationResult<unknown, Error, string> {
  return useMutation({
    mutationFn: (value: string) =>
      apiFetch<unknown>('/api/admin/jwt-secret', { method: 'PUT', body: { value } }),
    // No cache invalidation on purpose: the caller's token is already invalid, so a refetch would
    // just produce a 401 racing the sign-out. The sign-out is the feedback.
  });
}

/** Secrets: clears the JWT override, falling back to the env var or the generated secret. */
export function useClearJwtSecret(): UseMutationResult<void, Error, void> {
  return useMutation({
    mutationFn: () => apiFetch<void>('/api/admin/jwt-secret', { method: 'DELETE' }),
  });
}

/** Audit Log filters. Every field is optional and they combine with AND. */
export interface AuditLogFilters {
  /** An exact username - the backend does not do substring search, by design. */
  actorUsername?: string;
  action?: AuditAction | '';
  /** ISO-8601 instants. The UI collects dates and widens them to whole days. */
  from?: string;
  to?: string;
  page?: number;
}

/** Audit Log page size. */
export const AUDIT_PAGE_SIZE = 25;

/** Audit Log: one filtered page, newest first. */
export function useAuditLogs(filters: AuditLogFilters): UseQueryResult<Page<AuditLogEntry>> {
  const page = filters.page ?? 0;

  return useQuery({
    queryKey: queryKeys.admin.auditLogs({ ...filters, page }),
    queryFn: () =>
      apiFetch<Page<AuditLogEntry>>('/api/audit-logs', {
        query: {
          actorUsername: filters.actorUsername || undefined,
          action: filters.action || undefined,
          from: filters.from || undefined,
          to: filters.to || undefined,
          page,
          size: AUDIT_PAGE_SIZE,
        },
      }),
  });
}

/** Data Explorer: the collections, with the fields each one masks. */
export function useCollections(): UseQueryResult<CollectionSummary[]> {
  return useQuery({
    queryKey: queryKeys.admin.collections,
    queryFn: () => apiFetch<CollectionSummary[]>('/api/admin/data-explorer/collections'),
  });
}

/** Data Explorer page size. Kept small: these rows are wide. */
export const EXPLORER_PAGE_SIZE = 10;

/** Data Explorer: one page of documents, secrets already masked server-side. */
export function useCollectionDocuments(
  collection: string | undefined,
  page: number,
): UseQueryResult<Page<ExplorerDocument>> {
  return useQuery({
    queryKey: queryKeys.admin.documents(collection ?? '', page),
    queryFn: () =>
      apiFetch<Page<ExplorerDocument>>(
        `/api/admin/data-explorer/collections/${collection}/documents`,
        { query: { page, pageSize: EXPLORER_PAGE_SIZE } },
      ),
    enabled: Boolean(collection),
  });
}

/** The arguments for saving an edited document. */
export interface UpdateDocumentParams {
  collection: string;
  id: string;
  /** Only the fields being changed. A masked field here is rejected with a 400 that says where to go. */
  document: ExplorerDocument;
}

/** Data Explorer: saves edits to non-secret fields. */
export function useUpdateDocument(): UseMutationResult<
  ExplorerDocument,
  Error,
  UpdateDocumentParams
> {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ collection, id, document }: UpdateDocumentParams) =>
      apiFetch<ExplorerDocument>(
        `/api/admin/data-explorer/collections/${collection}/documents/${id}`,
        { method: 'PUT', body: document },
      ),
    onSuccess: (_result, { collection }) => {
      // Invalidating the collection prefix rather than the one page: an edit can change the field the
      // table sorts or filters on, so which page a document appears on is not guaranteed to be stable.
      queryClient.invalidateQueries({ queryKey: ['admin', 'collections', collection] });
      // Editing a document out from under another view is exactly what this tool is for, so the
      // product and user caches are dropped too rather than left showing pre-edit values.
      queryClient.invalidateQueries({ queryKey: queryKeys.products.all });
      queryClient.invalidateQueries({ queryKey: queryKeys.users.all });
    },
  });
}
