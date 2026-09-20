/**
 * TypeScript mirrors of the backend's response records.
 *
 * These are hand-written rather than generated from `/v3/api-docs`. For a codebase meant to be read
 * and learned from, a generated client would hide the wire shape behind a build step; the tradeoff
 * is that a backend record change has to be reflected here by hand. The Java side keeps every one of
 * these shapes in an explicit DTO record (never a serialised entity), so the list of things that can
 * drift is at least enumerable - see `PageResponse` and `UserResponse` for that reasoning.
 */

/** The two roles. ADMIN can write; READ_ONLY can view, run, and ask. */
export type Role = 'ADMIN' | 'READ_ONLY';

/** The LLM providers the backend can resolve a credential for. */
export type LlmProviderType = 'ANTHROPIC' | 'OPENAI';

/** How much work the model is asked to do on a run. */
export type AnalysisDepth = 'SHORT' | 'REGULAR' | 'NUCLEAR';

/** A user, as `/api/users` and `/api/auth/me` return one. Never includes `passwordHash`. */
export interface User {
  id: string;
  firstName: string;
  lastName: string;
  username: string;
  email: string;
  role: Role;
  preferredLlmProvider: LlmProviderType | null;
  createdAt: string;
}

/** The successful response from `POST /api/auth/login`. */
export interface LoginResponse {
  token: string;
  tokenType: string;
  /** ISO-8601. Used to drop the session before a request fails rather than after. */
  expiresAt: string;
  user: User;
}

/** The single error shape every failed request returns, from the backend's `ApiErrorResponse`. */
export interface ApiErrorBody {
  /** Short, stable, machine-readable code - e.g. `NOT_FOUND`. Safe to branch on. */
  error: string;
  /** Human-readable and safe to show a user directly; never a stack trace. */
  message: string;
  timestamp: string;
  path: string;
}

/** The envelope every paginated endpoint returns, from the backend's `PageResponse`. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
