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

/**
 * The five source types.
 *
 * The three MCP variants are separate types rather than one type with a flag because the backend
 * gives each a different prompt treatment - see SOURCE TYPES in the build prompt.
 */
export type SourceType = 'DOCS_MCP' | 'ATLASSIAN_MCP' | 'GENERIC_MCP' | 'WEBSITE' | 'SAAS_URL';

/** Whether a source's content is fetched by the model (MCP) or crawled by the backend. */
export function isMcpSource(type: SourceType): boolean {
  return type === 'DOCS_MCP' || type === 'ATLASSIAN_MCP' || type === 'GENERIC_MCP';
}

/** A source as it comes back from the API. `authToken` is never included, only its last 4. */
export interface SourceConfigResponse {
  type: SourceType;
  name: string;
  endpointUrl: string;
  authTokenConfigured: boolean;
  authTokenLast4: string | null;
  /** What the user set, or null to mean "use the system default". Crawled types only. */
  maxDepth: number | null;
  maxPages: number | null;
  /** What the crawler will actually use, with the system default already applied. */
  effectiveMaxDepth: number | null;
  effectiveMaxPages: number | null;
}

/** A source as it is sent to the API. */
export interface SourceConfigRequest {
  type: SourceType;
  name: string;
  endpointUrl: string;
  /**
   * Only ever sent when the user typed a new one. Omitted on edit to mean "keep the stored token" -
   * sending back the masked `last4` would overwrite the real token with four characters.
   */
  authToken?: string | null;
  maxDepth?: number | null;
  maxPages?: number | null;
}

/** A tracked product's most recent run, as summarised on the list and detail responses. */
export interface LastRun {
  reportId: string;
  runAt: string;
  runType: RunType;
  analysisDepth: AnalysisDepth;
  runBy: string;
  changeCount: number;
}

/** A tracked SaaS product. */
export interface SaasProduct {
  id: string;
  name: string;
  description: string | null;
  sources: SourceConfigResponse[];
  sourceCount: number;
  createdAt: string;
  createdBy: string;
  /** Null until the product has been run at least once. */
  lastRun: LastRun | null;
}

/** The create/update body for a product. */
export interface SaasProductRequest {
  name: string;
  description?: string | null;
  sources: SourceConfigRequest[];
}

/** `STANDARD` is a live run; `CUSTOM_RANGE` is a compare answered from stored history alone. */
export type RunType = 'STANDARD' | 'CUSTOM_RANGE';

/**
 * The categories a change can be filed under.
 *
 * Lower case because that is what the API actually sends: `ChangeCategory` on the backend is
 * serialised through `@JsonValue wireName()`, deliberately, so one spelling serves both the prompt the
 * model answers and the JSON the browser reads. These were upper case here for most of the build,
 * which type-checked fine and rendered the right *labels* - `humaniseEnum` capitalises either way -
 * while silently losing every badge colour, since the lookup keys never matched.
 */
export type ChangeCategory =
  | 'feature'
  | 'pricing'
  | 'policy'
  | 'bugfix'
  | 'documentation'
  | 'deprecation'
  | 'other';

/** How sure the model is. Three levels, not a number - see ADR 0004. Lower case on the wire, as above. */
export type Confidence = 'high' | 'medium' | 'low';

/** One detected change within a report. */
export interface ChangeResponse {
  sourceName: string;
  sourceType: SourceType;
  /** Serialised as the wire name, e.g. `feature` - not as the Java enum constant. */
  category: ChangeCategory;
  description: string;
  confidence: Confidence;
  evidenceSnippet: string | null;
}

/** Which source contributed to a report, and when its content was captured. */
export interface SourceInclusionResponse {
  sourceName: string;
  sourceType: SourceType;
  fetchedAt: string;
}

/** A stored change report - the durable result of a run or a compare. */
export interface ChangeReport {
  id: string;
  saasProductId: string;
  runAt: string;
  runBy: string;
  runType: RunType;
  analysisDepth: AnalysisDepth;
  /** Set on `CUSTOM_RANGE` reports only. */
  rangeFrom: string | null;
  rangeTo: string | null;
  /**
   * True when part of this comparison came from earlier reports rather than stored content, because
   * MCP servers only ever report their current state. The UI has to say so - see CUSTOM DATE-RANGE
   * COMPARE.
   */
  mcpHistoryLimited: boolean;
  sourcesIncluded: SourceInclusionResponse[];
  overallSummary: string;
  changes: ChangeResponse[];
  changeCount: number;
}

/** The `202` body from `POST /run` and `POST /compare`. */
export interface RunStartedResponse {
  runId: string;
}

/** The SSE event names a run stream emits, matching the backend's `RunEventType.wireName()`. */
export type RunEventType =
  | 'step_started'
  | 'step_progress'
  | 'step_completed'
  | 'step_failed'
  | 'run_completed'
  | 'run_failed';

/** The JSON body of one run event. */
export interface RunEvent {
  runId: string;
  type: RunEventType;
  /** The step's human-readable label, e.g. `Fetching sources`. Null on terminal events. */
  step: string | null;
  /** The real progress line to show, e.g. `Crawling https://... (page 3 of ~20)`. */
  detail: string | null;
  elapsedSeconds: number;
  /** Only on `run_completed`: the persisted report. */
  report: ChangeReport | null;
}

/** The JSON body of one ask event. `type` is also the SSE event name. */
export interface AskEvent {
  type: 'chunk' | 'done' | 'error';
  /** Present on `chunk`: the next piece of the answer. */
  text: string | null;
  /** Present on `done`: the complete answer. */
  answer: string | null;
  /** Present on `error`: a human-readable reason. */
  message: string | null;
}

/** Where a resolved credential came from. Shown so it is obvious what a change would affect. */
export type CredentialSource = 'PERSONAL' | 'OVERRIDE' | 'ENV_VAR' | 'HOST_MOUNT';

/** One provider's personal-key status. Never carries the key itself. */
export interface CredentialStatus {
  provider: LlmProviderType;
  configured: boolean;
  last4: string | null;
}

/** One provider's system-wide status, including where the key is coming from. */
export interface SystemCredentialStatus extends CredentialStatus {
  source: CredentialSource | null;
}

/** Where the JWT signing secret is coming from. There is deliberately no `last4` - see ADR 0006. */
export type JwtSecretSource = 'ADMIN_OVERRIDE' | 'ENV_VAR' | 'AUTO_GENERATED';

/** The JWT signing secret's status. Never includes any part of the secret. */
export interface JwtSecretStatus {
  configured: boolean;
  source: JwtSecretSource;
}

/** The dashboard-style counters from `/api/admin/stats`. */
export interface AdminStats {
  totalUsers: number;
  totalProducts: number;
  totalSourcesConfigured: number;
  runsLast24h: number;
  runsLast7d: number;
  /** 0-1, or null when nothing ran in the window - which is not the same as a 0% success rate. */
  runSuccessRate7d: number | null;
  avgRunDurationSeconds7d: number | null;
  recentRuns: RecentRun[];
}

/** One entry in the Overview tab's recent-runs list. */
export interface RecentRun {
  productName: string;
  runType: RunType;
  status: string;
  runAt: string;
}

/** The detailed, authenticated health view from `/api/admin/health`. */
export interface AdminHealth {
  status: string;
  components: ComponentHealth[];
  providers: ProviderHealth[];
  lastSuccessfulRun: LastSuccessfulRun | null;
  links: { apiDocs: string; prometheus: string; actuatorHealth: string };
}

/** One infrastructure component's health, straight from a Spring `HealthIndicator`. */
export interface ComponentHealth {
  name: string;
  status: string;
  details: Record<string, unknown> | null;
}

/** Whether a provider's key resolves right now, and from where. */
export interface ProviderHealth {
  provider: LlmProviderType;
  configured: boolean;
  source: CredentialSource | null;
  last4: string | null;
}

/** The most recent run that finished successfully, for the Overview tab. */
export interface LastSuccessfulRun {
  productName: string;
  runAt: string;
  outcome: string;
}

/** The crawl defaults, plus the ceilings a request cannot exceed. */
export interface AdminSettings {
  defaultMaxDepth: number;
  defaultMaxPages: number;
  maxAllowedDepth: number;
  maxAllowedPages: number;
}

/** Every action the audit log can record. Kept in sync with the backend's `AuditAction`. */
export type AuditAction =
  | 'AUTH_LOGIN_SUCCESS'
  | 'AUTH_LOGIN_FAILURE'
  | 'USER_CREATED'
  | 'USER_DELETED'
  | 'USER_PASSWORD_RESET'
  | 'PRODUCT_CREATED'
  | 'PRODUCT_UPDATED'
  | 'PRODUCT_DELETED'
  | 'PRODUCT_RUN_TRIGGERED'
  | 'PRODUCT_COMPARE_TRIGGERED'
  | 'PRODUCT_ASK_SUBMITTED'
  | 'LLM_CREDENTIAL_ADDED'
  | 'LLM_CREDENTIAL_REMOVED'
  | 'SYSTEM_CREDENTIAL_OVERRIDE_SET'
  | 'SYSTEM_CREDENTIAL_OVERRIDE_CLEARED'
  | 'SYSTEM_SETTINGS_UPDATED'
  | 'JWT_SECRET_OVERRIDE_SET'
  | 'JWT_SECRET_OVERRIDE_CLEARED'
  | 'DATA_EXPLORER_DOCUMENT_UPDATED';

/** One audit trail entry. `details` never contains a secret value - only field names. */
export interface AuditLogEntry {
  id: string;
  actorUserId: string | null;
  actorUsername: string;
  action: AuditAction;
  targetType: string | null;
  targetId: string | null;
  details: Record<string, unknown> | null;
  timestamp: string;
}

/** One collection as the Data Explorer lists it, with the fields it will refuse to reveal. */
export interface CollectionSummary {
  name: string;
  documentCount: number;
  secretFields: string[];
}

/**
 * A Data Explorer document.
 *
 * Deliberately an index signature: this is a generic Mongo browser, so the shape is whatever is in
 * the collection. Secret fields arrive already replaced with `SECRET_MASK` - the ciphertext never
 * reaches the browser at all.
 */
export type ExplorerDocument = Record<string, unknown>;

/**
 * What the backend substitutes for a secret field's value.
 *
 * Matching on this string is how the Data Explorer decides to render a disabled chip instead of an
 * input. It pairs with the collection's `secretFields` list rather than replacing it: the list is
 * authoritative for *which* fields are protected, and this catches a masked value inside a nested
 * object, where there is no top-level field name to check against.
 */
export const SECRET_MASK = '[encrypted]';

/** The body for creating a user. */
export interface CreateUserRequest {
  firstName: string;
  lastName: string;
  username: string;
  email: string;
  password: string;
  role: Role;
}
