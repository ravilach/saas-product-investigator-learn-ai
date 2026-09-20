import { currentToken } from '../auth/session';
import { ApiError } from './ApiError';
import type { ApiErrorBody } from './types';

/**
 * The single fetch wrapper every API call in the app goes through.
 *
 * Centralising it buys three things that are easy to get inconsistently right per-call-site: the
 * bearer header is attached from the stored session on every request (never captured once, so a
 * rotated token applies immediately), every non-2xx response becomes an {@link ApiError} carrying
 * the backend's own `message`, and a 401 triggers exactly one sign-out no matter how many requests
 * fail at once.
 */

/**
 * Base URL for API calls. Empty by default, which means same-origin: Vite proxies `/api` in
 * development and Spring serves the built assets in the packaged image, so both are same-origin and
 * neither needs a value. `VITE_API_BASE_URL` exists for the case the two are deployed apart.
 */
const BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '');

/** Called once when a request comes back 401, so the app can drop to the login screen. */
type UnauthorizedHandler = () => void;

let unauthorizedHandler: UnauthorizedHandler | null = null;

/**
 * Registers the app-wide reaction to a 401.
 *
 * Installed by the auth provider on mount. Kept as a callback rather than having this module clear
 * the session itself so that all session mutation stays in one place, and so the provider can also
 * surface a toast explaining why the user was bounced.
 *
 * @param handler invoked on the first 401 after a period of success
 * @returns a function that unregisters the handler
 */
export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): () => void {
  unauthorizedHandler = handler;
  return () => {
    if (unauthorizedHandler === handler) unauthorizedHandler = null;
  };
}

/** Options accepted by {@link apiFetch}, on top of the standard `RequestInit`. */
export interface ApiRequestOptions extends Omit<RequestInit, 'body'> {
  /** Serialised as JSON with the matching content type. Use `rawBody` for anything else. */
  body?: unknown;
  /** Passed to `fetch` untouched, for the rare non-JSON request. */
  rawBody?: BodyInit;
  /** Appended as a query string; `undefined` and `null` values are dropped rather than sent. */
  query?: Record<string, string | number | boolean | undefined | null>;
  /**
   * Set to `false` to skip the bearer header - only the login call wants this, since sending a
   * stale token to `/api/auth/login` is harmless but misleading in the access log.
   */
  authenticated?: boolean;
}

/**
 * Builds a full request URL from a path and optional query parameters.
 *
 * @param path an API path beginning with `/`
 * @param query parameters to append; empty values are omitted
 * @returns the URL to fetch
 */
export function buildUrl(path: string, query?: ApiRequestOptions['query']): string {
  const url = `${BASE_URL}${path}`;
  if (!query) return url;

  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    params.set(key, String(value));
  }
  const queryString = params.toString();
  return queryString ? `${url}?${queryString}` : url;
}

/**
 * Builds the standard request headers, including the bearer token when there is one.
 *
 * Exported because the SSE reader in `sse.ts` needs the identical header set - the backend's JWT
 * filter reads the `Authorization` header and deliberately does not accept a token in the query
 * string, so a streamed request has to authenticate the same way an ordinary one does.
 *
 * @param extra headers to merge in, overriding the defaults
 * @param authenticated whether to attach the bearer token
 * @returns headers ready to hand to `fetch`
 */
export function buildHeaders(extra?: HeadersInit, authenticated = true): Headers {
  const headers = new Headers(extra);
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');

  if (authenticated) {
    const token = currentToken();
    if (token) headers.set('Authorization', `Bearer ${token}`);
  }
  return headers;
}

/**
 * Turns a failed response into an {@link ApiError}, preferring the backend's own message.
 *
 * @param response the non-2xx response
 * @returns the error to throw
 */
async function toApiError(response: Response): Promise<ApiError> {
  let body: ApiErrorBody | undefined;
  try {
    const parsed = (await response.json()) as ApiErrorBody;
    // Only trust it if it actually looks like the backend's shape. A proxy or ingress returning its
    // own HTML/JSON error page must not end up quoted at the user as if it were our message.
    if (parsed && typeof parsed.message === 'string' && typeof parsed.error === 'string') {
      body = parsed;
    }
  } catch {
    // No body, or not JSON. The status-based fallback below covers it.
  }

  if (body) return new ApiError(response.status, body.error, body.message, body);

  return new ApiError(
    response.status,
    `HTTP_${response.status}`,
    fallbackMessage(response.status),
  );
}

/**
 * A human-readable message for a failure that carried no usable body.
 *
 * @param status the HTTP status
 * @returns something actionable to show the user
 */
function fallbackMessage(status: number): string {
  switch (status) {
    case 401:
      return 'Your session has expired. Please sign in again.';
    case 403:
      return 'You do not have permission to do that.';
    case 404:
      return 'That item no longer exists.';
    case 409:
      return 'That conflicts with something that already exists.';
    case 502:
    case 503:
    case 504:
      return 'The server is not reachable right now. Please try again in a moment.';
    default:
      return status >= 500
        ? 'The server hit an unexpected error. Please try again.'
        : `Request failed (HTTP ${status}).`;
  }
}

/**
 * Performs an API request and returns the parsed JSON body.
 *
 * @typeParam T the expected response shape
 * @param path an API path beginning with `/`, e.g. `/api/saas-products`
 * @param options request options; `body` is JSON-serialised automatically
 * @returns the parsed response body, or `undefined` for a `204 No Content`
 * @throws ApiError on any non-2xx response, on a network failure, or on an unparsable body
 */
export async function apiFetch<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const { body, rawBody, query, authenticated = true, headers, ...rest } = options;

  const requestHeaders = buildHeaders(headers, authenticated);
  let payload: BodyInit | undefined = rawBody;

  if (body !== undefined) {
    requestHeaders.set('Content-Type', 'application/json');
    payload = JSON.stringify(body);
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      ...rest,
      headers: requestHeaders,
      body: payload,
    });
  } catch {
    // A rejected fetch means the request never got a response: offline, DNS, a refused connection,
    // or a blocked cross-origin preflight. Status 0 distinguishes it from any server answer.
    throw new ApiError(
      0,
      'NETWORK_ERROR',
      'Could not reach the server. Check that the backend is running.',
      undefined,
    );
  }

  if (response.status === 401 && authenticated) {
    // Drop the session before throwing, so a burst of parallel 401s cannot each start their own
    // sign-out; the handler is responsible for being idempotent for the same reason.
    unauthorizedHandler?.();
  }

  if (!response.ok) throw await toApiError(response);

  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return undefined as T;
  }

  const contentType = response.headers.get('Content-Type') ?? '';
  if (!contentType.includes('json')) {
    return (await response.text()) as unknown as T;
  }

  return (await response.json()) as T;
}

/**
 * Downloads a binary response as a `Blob`, for the report export endpoints.
 *
 * Kept separate from {@link apiFetch} rather than bolted on with a flag, because the two differ in
 * their whole return contract - and because a PDF is the one response where guessing at the body
 * type would produce a corrupt file rather than an error.
 *
 * @param path the export path
 * @param options request options
 * @returns the response body and the filename the server suggested, if any
 * @throws ApiError on any non-2xx response
 */
export async function apiDownload(
  path: string,
  options: ApiRequestOptions = {},
): Promise<{ blob: Blob; filename: string | null }> {
  // `body` and `rawBody` are pulled out and discarded: an export is always a GET, and letting them
  // fall through into `...rest` would hand `fetch` an unserialisable `unknown`.
  const { query, authenticated = true, headers, body: _body, rawBody: _rawBody, ...rest } = options;

  const requestHeaders = buildHeaders(headers, authenticated);
  requestHeaders.set('Accept', '*/*');

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), { ...rest, headers: requestHeaders });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Could not reach the server to start the download.');
  }

  if (response.status === 401 && authenticated) unauthorizedHandler?.();
  if (!response.ok) throw await toApiError(response);

  return { blob: await response.blob(), filename: filenameFrom(response) };
}

/**
 * Extracts a filename from a `Content-Disposition` header.
 *
 * @param response the download response
 * @returns the suggested filename, or `null` if the header was absent or unparsable
 */
function filenameFrom(response: Response): string | null {
  const disposition = response.headers.get('Content-Disposition');
  if (!disposition) return null;
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
  return match ? decodeURIComponent(match[1]) : null;
}
