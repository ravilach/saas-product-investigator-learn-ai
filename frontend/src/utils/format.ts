/**
 * Display formatting for the values the API returns.
 *
 * Collected here rather than inlined per screen because most of these appear on three or four pages
 * each, and an audit-log timestamp formatted one way on the Audit Log and another way on the Overview
 * tab reads as two different systems. Everything here is pure, which is also what makes it testable
 * without a DOM.
 */

/** Human-readable labels for the five source types. */
const SOURCE_TYPE_LABELS: Record<string, string> = {
  DOCS_MCP: 'Docs MCP',
  ATLASSIAN_MCP: 'Atlassian MCP',
  GENERIC_MCP: 'Generic MCP',
  WEBSITE: 'Website',
  SAAS_URL: 'SaaS App URL',
};

/** Human-readable labels for the three analysis depths, as DESIGN & UX names them. */
const DEPTH_LABELS: Record<string, string> = {
  SHORT: 'Short Summary',
  REGULAR: 'Regular',
  NUCLEAR: 'Nuclear Analysis',
};

/**
 * Names a source type the way the UI refers to it.
 *
 * @param type the enum name from the API
 * @returns the display label, or the raw value if this build does not know the type
 */
export function sourceTypeLabel(type: string): string {
  return SOURCE_TYPE_LABELS[type] ?? type;
}

/**
 * Names an analysis depth.
 *
 * @param depth the enum name from the API
 * @returns the display label
 */
export function depthLabel(depth: string): string {
  return DEPTH_LABELS[depth] ?? depth;
}

/**
 * Vendor names for the LLM providers.
 *
 * Hand-written rather than humanised, because the mechanical transform gets `OPENAI` wrong: "Openai"
 * is not how the company spells it, and a settings page that misspells the vendor it is asking for an
 * API key to looks untrustworthy at exactly the wrong moment.
 */
const PROVIDER_LABELS: Record<string, string> = {
  ANTHROPIC: 'Anthropic',
  OPENAI: 'OpenAI',
};

/**
 * Names an LLM provider the way its vendor does.
 *
 * @param provider the enum name from the API
 * @returns the display label, or the raw value if this build does not know the provider
 */
export function providerLabel(provider: string): string {
  return PROVIDER_LABELS[provider] ?? provider;
}

/**
 * Turns an enum name into title case, for the enums with too many values to label by hand.
 *
 * Used for audit actions and change categories: `USER_PASSWORD_RESET` → `User password reset`. A
 * lookup table would be nineteen entries that add nothing over the mechanical transform, and would
 * silently miss any value the backend adds later.
 *
 * @param value the enum name
 * @returns the humanised form
 */
export function humaniseEnum(value: string): string {
  if (!value) return value;
  const words = value.toLowerCase().replaceAll('_', ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * Formats an instant as a date and time in the viewer's own locale and zone.
 *
 * Deliberately local rather than UTC: every timestamp in this app is "when did this happen to me",
 * and asking someone to convert from UTC to work out whether a run was this morning is a cost with
 * no benefit. The API always sends an ISO-8601 instant with a zone, so the conversion is unambiguous.
 *
 * @param iso an ISO-8601 instant, or null
 * @returns the formatted string, or an em dash for null/unparsable input
 */
export function formatDateTime(iso: string | null | undefined): string {
  const date = parse(iso);
  if (!date) return '—';
  return date.toLocaleString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Formats an instant or a `YYYY-MM-DD` date as a date alone.
 *
 * @param iso an ISO-8601 instant or local date, or null
 * @returns the formatted date, or an em dash
 */
export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';

  // A bare `YYYY-MM-DD` is parsed by `Date` as midnight *UTC*, so formatting it in a negative-offset
  // zone shifts it to the previous day. These values are the compare range's `LocalDate`s, which have
  // no zone and should not acquire one, so they are split rather than parsed.
  const localDate = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (localDate) {
    const [, year, month, day] = localDate;
    return new Date(Number(year), Number(month) - 1, Number(day)).toLocaleDateString(undefined, {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
    });
  }

  const date = parse(iso);
  if (!date) return '—';
  return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
}

/**
 * Describes how long ago something happened, in the coarsest unit that is still informative.
 *
 * @param iso an ISO-8601 instant, or null
 * @param now the reference time; injectable so tests do not depend on the clock
 * @returns e.g. `4 minutes ago`, `Yesterday`, `3 weeks ago`, or `Never` for null
 */
export function timeAgo(iso: string | null | undefined, now: Date = new Date()): string {
  const date = parse(iso);
  if (!date) return 'Never';

  const seconds = Math.round((now.getTime() - date.getTime()) / 1000);

  // A clock skew between browser and server can put a just-created timestamp slightly in the future.
  // "In 2 seconds" for something that already happened is worse than rounding it to "Just now".
  if (seconds < 45) return 'Just now';

  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return plural(minutes, 'minute');

  const hours = Math.round(minutes / 60);
  if (hours < 24) return plural(hours, 'hour');

  const days = Math.round(hours / 24);
  if (days === 1) return 'Yesterday';
  if (days < 7) return plural(days, 'day');

  const weeks = Math.round(days / 7);
  if (weeks < 5) return plural(weeks, 'week');

  const months = Math.round(days / 30);
  if (months < 12) return plural(months, 'month');

  return plural(Math.round(days / 365), 'year');
}

/**
 * Formats a duration in seconds for the live execution view's elapsed counter.
 *
 * @param seconds the elapsed seconds
 * @returns `12s` under a minute, `4m 07s` above it
 */
export function formatElapsed(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return '';
  const whole = Math.max(0, Math.floor(seconds));
  if (whole < 60) return `${whole}s`;
  // Zero-padded seconds, so the string's width does not change every tick and shift the layout.
  return `${Math.floor(whole / 60)}m ${String(whole % 60).padStart(2, '0')}s`;
}

/**
 * Formats a 0-1 rate as a percentage.
 *
 * @param rate the rate, or null when the window had no runs to measure
 * @returns e.g. `92%`, or `No runs yet` for null - which is not the same as 0%
 */
export function formatRate(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return 'No runs yet';
  return `${Math.round(rate * 100)}%`;
}

/**
 * Formats an average duration in seconds.
 *
 * @param seconds the average, or null when there is nothing to average
 * @returns e.g. `48s`, `2m 30s`, or an em dash
 */
export function formatDuration(seconds: number | null | undefined): string {
  if (seconds === null || seconds === undefined) return '—';
  return formatElapsed(seconds) || '—';
}

/**
 * Today's date as `YYYY-MM-DD` in the viewer's own zone.
 *
 * Used as the `max` on the compare date inputs, because the backend rejects a future date. Built from
 * the local parts rather than `toISOString().slice(0, 10)`, which would give UTC's date and so offer
 * a date the backend calls "in the future" to anyone west of Greenwich.
 *
 * @param now the reference time; injectable for tests
 * @returns the local date in `YYYY-MM-DD` form
 */
export function todayIsoDate(now: Date = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

/**
 * Widens a `YYYY-MM-DD` date to the instant that starts or ends that whole local day.
 *
 * The Audit Log's filters are `Instant`s on the backend but dates in the UI, and the naive conversion
 * - sending midnight for both - makes a single-day filter match nothing at all.
 *
 * @param date a `YYYY-MM-DD` string, or an empty string
 * @param edge `start` for 00:00:00.000, `end` for 23:59:59.999
 * @returns an ISO-8601 instant, or undefined if no date was given
 */
export function dayBoundary(date: string, edge: 'start' | 'end'): string | undefined {
  if (!date) return undefined;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return undefined;

  const [, year, month, day] = match;
  const local =
    edge === 'start'
      ? new Date(Number(year), Number(month) - 1, Number(day), 0, 0, 0, 0)
      : new Date(Number(year), Number(month) - 1, Number(day), 23, 59, 59, 999);
  return local.toISOString();
}

/**
 * Parses an ISO instant, tolerating null and garbage.
 *
 * @param iso the candidate string
 * @returns the date, or null
 */
function parse(iso: string | null | undefined): Date | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

/**
 * Pluralises a count with its unit.
 *
 * @param count how many
 * @param unit the singular unit name
 * @returns e.g. `1 hour ago`, `3 hours ago`
 */
function plural(count: number, unit: string): string {
  return `${count} ${unit}${count === 1 ? '' : 's'} ago`;
}
