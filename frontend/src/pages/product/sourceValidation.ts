import { isMcpSource, type SourceType } from '../../api/types';

/**
 * Client-side validation for the add-source form.
 *
 * A pure module rather than logic inside the component, for the same reason `runEvents.ts` is: these
 * are the rules worth testing, and testing them as `draft in, errors out` is far cheaper than driving
 * a form to provoke each one.
 *
 * Every rule here mirrors one the backend enforces in `SourceConfigMapper` - this is a courtesy that
 * catches a typo before a round trip, not a substitute. The backend's messages are what the user sees
 * if anything slips through, so the wording here is deliberately close to them.
 */

/** A source being edited in the form, before it becomes a request. */
export interface SourceDraft {
  /**
   * A client-only identity for the row, so React keys and edit targeting survive a rename.
   *
   * The API has no source id - a source is identified by its name within its product - so the form
   * needs one of its own. Stripped before submitting.
   */
  key: string;
  type: SourceType;
  name: string;
  endpointUrl: string;
  /** What the user typed. Empty means "no token"; see {@link SourceDraft.authTokenConfigured}. */
  authToken: string;
  /** True if the *stored* source already has a token, which the form is never shown. */
  authTokenConfigured: boolean;
  /** The masked tail of the stored token, for showing that one exists without revealing it. */
  authTokenLast4: string | null;
  /** True once the user has explicitly cleared a stored token, which submits `""` rather than absent. */
  authTokenCleared: boolean;
  /** Blank means "use the system default". Crawled types only. */
  maxDepth: string;
  maxPages: string;
}

/** Field-keyed validation errors for one source draft. */
export type SourceErrors = Partial<Record<'name' | 'endpointUrl' | 'maxDepth' | 'maxPages', string>>;

/** The ceilings the backend enforces, fetched from `/api/admin/settings` where available. */
export interface CrawlLimits {
  maxAllowedDepth: number;
  maxAllowedPages: number;
}

/**
 * Fallback ceilings, used when the current user cannot read `/api/admin/settings`.
 *
 * Only an ADMIN can read that endpoint, and only an ADMIN can edit a product - so in practice the real
 * values are always available. These exist so a failed settings fetch degrades to a slightly
 * over-permissive client check rather than blocking the form entirely; the backend still rejects
 * anything genuinely out of range.
 */
export const FALLBACK_CRAWL_LIMITS: CrawlLimits = { maxAllowedDepth: 10, maxAllowedPages: 500 };

/**
 * Creates an empty draft for a new source.
 *
 * @param type the type to start on; defaults to `WEBSITE`, the one most people add first
 * @returns a blank draft with a fresh key
 */
export function emptySourceDraft(type: SourceType = 'WEBSITE'): SourceDraft {
  return {
    key: `source-${Math.random().toString(36).slice(2, 10)}`,
    type,
    name: '',
    endpointUrl: '',
    authToken: '',
    authTokenConfigured: false,
    authTokenLast4: null,
    authTokenCleared: false,
    maxDepth: '',
    maxPages: '',
  };
}

/**
 * Validates one source draft in isolation.
 *
 * Name uniqueness is *not* checked here, because it is a property of the list rather than of one
 * source - see {@link validateSources}.
 *
 * @param draft the source being validated
 * @param limits the crawl ceilings to enforce
 * @returns the errors found, keyed by field; empty when the draft is valid
 */
export function validateSource(draft: SourceDraft, limits: CrawlLimits): SourceErrors {
  const errors: SourceErrors = {};

  const name = draft.name.trim();
  if (!name) errors.name = 'Every source needs a name.';
  else if (name.length > 120) errors.name = 'A source name must be 120 characters or fewer.';

  const urlError = validateEndpointUrl(draft.endpointUrl);
  if (urlError) errors.endpointUrl = urlError;

  // Crawl limits do not apply to MCP sources, and the form does not offer the fields for them - so a
  // value here would mean a stale draft whose type was switched after the numbers were typed. Checked
  // rather than assumed, because the backend rejects that combination outright.
  if (!isMcpSource(draft.type)) {
    const depth = validateBoundedInteger(draft.maxDepth, 0, limits.maxAllowedDepth, 'Max depth');
    if (depth) errors.maxDepth = depth;

    const pages = validateBoundedInteger(draft.maxPages, 1, limits.maxAllowedPages, 'Max pages');
    if (pages) errors.maxPages = pages;
  }

  return errors;
}

/**
 * Validates the whole source list, including cross-source name uniqueness.
 *
 * @param drafts every source on the form
 * @param limits the crawl ceilings to enforce
 * @returns errors per draft key; a key is absent when that source is valid
 */
export function validateSources(
  drafts: SourceDraft[],
  limits: CrawlLimits,
): Record<string, SourceErrors> {
  const result: Record<string, SourceErrors> = {};

  // Case-insensitive, matching the backend: "Docs" and "docs" colliding with a message that looks
  // like it is complaining about identical strings is a genuinely confusing five minutes.
  const seen = new Map<string, string>();

  for (const draft of drafts) {
    const errors = validateSource(draft, limits);

    const name = draft.name.trim();
    if (name) {
      const normalised = name.toLocaleLowerCase();
      const firstKey = seen.get(normalised);
      if (firstKey !== undefined && firstKey !== draft.key) {
        errors.name = `Two sources are both named "${name}" (ignoring case). A source's name identifies it in reports and snapshots, so they must be unique within a product.`;
      } else if (firstKey === undefined) {
        seen.set(normalised, draft.key);
      }
    }

    if (Object.keys(errors).length > 0) result[draft.key] = errors;
  }

  return result;
}

/**
 * Checks that a URL is one the backend can actually reach.
 *
 * @param value the raw input
 * @returns the error message, or `null` if the URL is usable
 */
export function validateEndpointUrl(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return 'Every source needs a URL.';

  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    // `URL`'s own error names a character offset, which is accurate and no help at all. An example of
    // the right shape is more useful than a precise description of the wrong one.
    return 'That is not a valid URL. It needs to look like https://example.com/docs.';
  }

  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return 'The URL must be http or https. Both MCP servers and crawled pages are reached over HTTP.';
  }
  if (!url.hostname) {
    return 'That URL has no host - it needs to look like https://example.com/docs.';
  }
  return null;
}

/**
 * Checks an optional whole number against an inclusive range.
 *
 * @param value the raw input; blank means "use the default" and is always valid
 * @param min the lowest allowed value
 * @param max the highest allowed value
 * @param label the field's name, for the message
 * @returns the error message, or `null`
 */
export function validateBoundedInteger(
  value: string,
  min: number,
  max: number,
  label: string,
): string | null {
  const trimmed = value.trim();
  if (!trimmed) return null;

  // A regex rather than `Number()`, which accepts "2.5", "2e3", and " 2 " - all of which would reach
  // the backend as something other than what the user believes they typed.
  if (!/^\d+$/.test(trimmed)) return `${label} must be a whole number.`;

  const parsed = Number(trimmed);
  if (parsed < min || parsed > max) return `${label} must be between ${min} and ${max}.`;
  return null;
}

/**
 * Validates a product's own fields.
 *
 * @param name the product name
 * @param description the product description
 * @returns errors keyed by field; empty when valid
 */
export function validateProduct(
  name: string,
  description: string,
): Partial<Record<'name' | 'description', string>> {
  const errors: Partial<Record<'name' | 'description', string>> = {};

  const trimmed = name.trim();
  if (!trimmed) errors.name = 'A name is required.';
  else if (trimmed.length > 200) errors.name = 'The name must be 200 characters or fewer.';

  if (description.length > 2000) errors.description = 'The description must be 2000 characters or fewer.';

  return errors;
}
