import type { ApiErrorBody } from './types';

/**
 * A failed API call, carrying the backend's parsed error body when there was one.
 *
 * Every rejection out of {@link apiFetch} is one of these, which is what lets the shared error
 * display and the toast helper render any failure without type-testing an `unknown` first - the
 * pattern ERROR HANDLING asks for, where a user sees a human-readable message rather than a raw
 * error object.
 */
export class ApiError extends Error {
  /** HTTP status, or 0 when the request never reached the server (offline, DNS, CORS). */
  readonly status: number;

  /** The backend's stable error code, or a synthetic one for transport-level failures. */
  readonly code: string;

  /** The parsed backend body, when the response had one. */
  readonly body?: ApiErrorBody;

  /**
   * @param status HTTP status, or 0 for a transport failure
   * @param code stable machine-readable code
   * @param message human-readable message, safe to display
   * @param body the parsed backend error body, if present
   */
  constructor(status: number, code: string, message: string, body?: ApiErrorBody) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.body = body;
  }

  /** True when the session is missing, expired, or signed by a rotated secret. */
  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  /** True when the caller is authenticated but their role does not allow this. */
  get isForbidden(): boolean {
    return this.status === 403;
  }

  /**
   * Narrows an unknown thrown value to a displayable message.
   *
   * Used by the toast helper and the error boundary, both of which receive `unknown` from a
   * `catch` or from React and still have to put a sentence on screen.
   *
   * @param error any thrown value
   * @returns the best human-readable message available for it
   */
  static messageFrom(error: unknown): string {
    if (error instanceof ApiError) return error.message;
    if (error instanceof Error && error.message) return error.message;
    return 'Something went wrong. Please try again.';
  }
}
