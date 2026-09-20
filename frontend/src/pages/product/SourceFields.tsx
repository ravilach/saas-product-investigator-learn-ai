import { useId } from 'react';
import { isMcpSource, type SourceType } from '../../api/types';
import { sourceTypeLabel } from '../../utils/format';
import type { CrawlLimits, SourceDraft, SourceErrors } from './sourceValidation';

/** Every source type, in the order the dropdown offers them. */
const SOURCE_TYPES: readonly SourceType[] = [
  'WEBSITE',
  'SAAS_URL',
  'DOCS_MCP',
  'ATLASSIAN_MCP',
  'GENERIC_MCP',
];

/** A short line explaining what each type is for, shown under the dropdown. */
const TYPE_HINTS: Record<SourceType, string> = {
  WEBSITE: 'Crawled: same-origin links are followed breadth-first from this URL, respecting robots.txt.',
  SAAS_URL: 'Crawled the same way as Website - typically a changelog or release-notes page.',
  DOCS_MCP: 'An MCP server exposing documentation search/read tools. The model calls its tools itself.',
  ATLASSIAN_MCP: "Atlassian's MCP server - Jira issues and changelogs, and Confluence pages.",
  GENERIC_MCP: 'Any other MCP server. The model decides which of its tools are relevant.',
};

/** Props for {@link SourceFields}. */
export interface SourceFieldsProps {
  /** The source being edited. */
  draft: SourceDraft;
  /** Validation errors for this source, if any. */
  errors: SourceErrors | undefined;
  /** The crawl ceilings, shown in the hints and enforced by the inputs' `max`. */
  limits: CrawlLimits;
  /** The system-wide defaults, so the placeholders can say what blank actually means. */
  defaults: { maxDepth: number; maxPages: number } | undefined;
  /** Called with the changed fields. */
  onChange: (change: Partial<SourceDraft>) => void;
  /** Removes this source from the list. */
  onRemove: () => void;
}

/**
 * The fields for one source.
 *
 * Covers what DESIGN & UX asks of the add-source form: a type dropdown with all five types, no cap on
 * how many of any type a product has, and the Website/SaaS App URL types revealing the optional crawl
 * limits - "defaulted so most users never need to touch them", which is why they are blank-means-default
 * inputs with the real default in the placeholder rather than pre-filled numbers.
 *
 * The same component serves adding and editing, because they differ in exactly one way: an existing
 * source may already have a stored auth token, which the form is never shown. See the auth token field
 * below for how the three states of that field map onto the backend's contract.
 *
 * @param props see {@link SourceFieldsProps}
 * @returns the fields
 */
export function SourceFields({
  draft,
  errors,
  limits,
  defaults,
  onChange,
  onRemove,
}: SourceFieldsProps) {
  const id = useId();
  const crawled = !isMcpSource(draft.type);

  return (
    <div className="card" style={{ background: 'var(--bg)' }}>
      <div
        className="row"
        style={{ justifyContent: 'space-between', marginBottom: 'var(--space-4)' }}
      >
        <strong style={{ fontSize: 'var(--text-sm)' }}>
          {draft.name.trim() || 'New source'}
        </strong>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={onRemove}
          // Named, because a page can hold several of these and "Remove" alone leaves a screen-reader
          // user guessing which one they are on.
          aria-label={`Remove source ${draft.name.trim() || '(unnamed)'}`}
        >
          Remove
        </button>
      </div>

      <div className="field">
        <label className="label" htmlFor={`${id}-type`}>
          Type
        </label>
        <select
          id={`${id}-type`}
          className="select"
          value={draft.type}
          onChange={(event) => {
            const type = event.target.value as SourceType;
            // Switching to an MCP type clears the crawl limits rather than hiding them with values
            // still in state: the backend rejects an MCP source that carries them, and a hidden field
            // that fails validation is the least debuggable kind of form bug.
            onChange(isMcpSource(type) ? { type, maxDepth: '', maxPages: '' } : { type });
          }}
        >
          {SOURCE_TYPES.map((type) => (
            <option key={type} value={type}>
              {sourceTypeLabel(type)}
            </option>
          ))}
        </select>
        <span className="hint">{TYPE_HINTS[draft.type]}</span>
      </div>

      <div className="field">
        <label className="label" htmlFor={`${id}-name`}>
          Name
        </label>
        <input
          id={`${id}-name`}
          className="input"
          value={draft.name}
          onChange={(event) => onChange({ name: event.target.value })}
          aria-invalid={errors?.name ? true : undefined}
          aria-describedby={errors?.name ? `${id}-name-error` : `${id}-name-hint`}
          maxLength={120}
          placeholder="Changelog"
        />
        {errors?.name ? (
          <span id={`${id}-name-error`} className="field-error">
            {errors.name}
          </span>
        ) : (
          <span id={`${id}-name-hint`} className="hint">
            How this source is labelled in reports and snapshots. Must be unique within the product.
          </span>
        )}
      </div>

      <div className="field">
        <label className="label" htmlFor={`${id}-url`}>
          {crawled ? 'Starting URL' : 'MCP server URL'}
        </label>
        <input
          id={`${id}-url`}
          className="input"
          type="url"
          inputMode="url"
          value={draft.endpointUrl}
          onChange={(event) => onChange({ endpointUrl: event.target.value })}
          aria-invalid={errors?.endpointUrl ? true : undefined}
          aria-describedby={errors?.endpointUrl ? `${id}-url-error` : undefined}
          placeholder="https://example.com/changelog"
        />
        {errors?.endpointUrl ? (
          <span id={`${id}-url-error`} className="field-error">
            {errors.endpointUrl}
          </span>
        ) : null}
      </div>

      <AuthTokenField id={id} draft={draft} onChange={onChange} />

      {crawled ? (
        <div className="row" style={{ alignItems: 'flex-start', gap: 'var(--space-4)' }}>
          <div className="field" style={{ flex: 1, marginBottom: 0 }}>
            <label className="label" htmlFor={`${id}-depth`}>
              Max depth <span className="muted">(optional)</span>
            </label>
            <input
              id={`${id}-depth`}
              className="input"
              type="number"
              inputMode="numeric"
              min={0}
              max={limits.maxAllowedDepth}
              value={draft.maxDepth}
              onChange={(event) => onChange({ maxDepth: event.target.value })}
              aria-invalid={errors?.maxDepth ? true : undefined}
              aria-describedby={`${id}-depth-hint`}
              placeholder={defaults ? String(defaults.maxDepth) : 'default'}
            />
            {errors?.maxDepth ? (
              <span className="field-error">{errors.maxDepth}</span>
            ) : (
              <span id={`${id}-depth-hint`} className="hint">
                {defaults
                  ? `Blank uses the system default of ${defaults.maxDepth}.`
                  : 'Blank uses the system default.'}
              </span>
            )}
          </div>

          <div className="field" style={{ flex: 1, marginBottom: 0 }}>
            <label className="label" htmlFor={`${id}-pages`}>
              Max pages <span className="muted">(optional)</span>
            </label>
            <input
              id={`${id}-pages`}
              className="input"
              type="number"
              inputMode="numeric"
              min={1}
              max={limits.maxAllowedPages}
              value={draft.maxPages}
              onChange={(event) => onChange({ maxPages: event.target.value })}
              aria-invalid={errors?.maxPages ? true : undefined}
              aria-describedby={`${id}-pages-hint`}
              placeholder={defaults ? String(defaults.maxPages) : 'default'}
            />
            {errors?.maxPages ? (
              <span className="field-error">{errors.maxPages}</span>
            ) : (
              <span id={`${id}-pages-hint`} className="hint">
                {defaults
                  ? `Blank uses the system default of ${defaults.maxPages}.`
                  : 'Blank uses the system default.'}
              </span>
            )}
          </div>
        </div>
      ) : null}
    </div>
  );
}

/**
 * The auth token field, whose three states map onto the backend's three meanings for `authToken`.
 *
 * Absent means "leave the stored token alone", `""` means "remove it", and anything else replaces it.
 * A form that simply submitted whatever was in the box would send the empty string on every edit and
 * silently delete the token - so the field never holds the stored value, and clearing is a separate,
 * explicit action.
 *
 * @param props.id the owning fieldset's id prefix
 * @param props.draft the source being edited
 * @param props.onChange called with the changed fields
 * @returns the field
 */
function AuthTokenField({
  id,
  draft,
  onChange,
}: {
  id: string;
  draft: SourceDraft;
  onChange: (change: Partial<SourceDraft>) => void;
}) {
  const stored = draft.authTokenConfigured && !draft.authTokenCleared;

  return (
    <div className="field">
      <label className="label" htmlFor={`${id}-token`}>
        Auth token <span className="muted">(optional)</span>
      </label>

      {stored ? (
        <div className="row" style={{ flexWrap: 'wrap' }}>
          <span className="pill pill-neutral">
            Stored · ends {draft.authTokenLast4 ?? '????'}
          </span>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => onChange({ authTokenCleared: true, authToken: '' })}
          >
            Replace or remove
          </button>
        </div>
      ) : (
        <input
          id={`${id}-token`}
          className="input"
          // `password`, so it is not readable over someone's shoulder and browsers do not offer to
          // remember it as an ordinary form value.
          type="password"
          autoComplete="off"
          value={draft.authToken}
          onChange={(event) => onChange({ authToken: event.target.value })}
          placeholder={draft.authTokenConfigured ? 'Leave blank to remove the stored token' : ''}
        />
      )}

      <span className="hint">
        {stored
          ? 'Only the last 4 characters are ever sent back to this page. The token itself stays encrypted on the server.'
          : 'Sent once, encrypted at rest, and never returned in full. Leave blank if the source needs no credential.'}
      </span>
    </div>
  );
}
