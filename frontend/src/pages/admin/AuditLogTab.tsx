import { useState } from 'react';
import { AUDIT_PAGE_SIZE, useAuditLogs, type AuditLogFilters } from '../../api/admin';
import type { AuditAction, AuditLogEntry } from '../../api/types';
import { Pagination } from '../../components/Pagination';
import { EmptyState } from '../../components/states/EmptyState';
import { ErrorState } from '../../components/states/ErrorState';
import { SkeletonTable } from '../../components/states/Skeleton';
import { dayBoundary, formatDateTime, humaniseEnum, timeAgo, todayIsoDate } from '../../utils/format';
import styles from './Admin.module.css';

/**
 * Every action the log records, for the filter dropdown.
 *
 * Listed rather than derived from the data, because the point of a filter is to find the action that
 * is *not* on the current page - deriving the options from the visible rows would hide exactly the
 * value someone is looking for.
 */
const ACTIONS: AuditAction[] = [
  'AUTH_LOGIN_SUCCESS',
  'AUTH_LOGIN_FAILURE',
  'USER_CREATED',
  'USER_DELETED',
  'USER_PASSWORD_RESET',
  'PRODUCT_CREATED',
  'PRODUCT_UPDATED',
  'PRODUCT_DELETED',
  'PRODUCT_RUN_TRIGGERED',
  'PRODUCT_COMPARE_TRIGGERED',
  'PRODUCT_ASK_SUBMITTED',
  'LLM_CREDENTIAL_ADDED',
  'LLM_CREDENTIAL_REMOVED',
  'SYSTEM_CREDENTIAL_OVERRIDE_SET',
  'SYSTEM_CREDENTIAL_OVERRIDE_CLEARED',
  'SYSTEM_SETTINGS_UPDATED',
  'JWT_SECRET_OVERRIDE_SET',
  'JWT_SECRET_OVERRIDE_CLEARED',
  'DATA_EXPLORER_DOCUMENT_UPDATED',
];

/** What the filter form holds, before the dates are widened into instants. */
interface FilterDraft {
  actorUsername: string;
  action: AuditAction | '';
  fromDate: string;
  toDate: string;
}

const EMPTY_FILTERS: FilterDraft = { actorUsername: '', action: '', fromDate: '', toDate: '' };

/**
 * The Admin Console's Audit Log tab: a filtered, paginated table of who did what.
 *
 * The filters are applied on submit rather than on every keystroke. A `from`/`to` pair is only
 * coherent once both ends are typed, and a live-filtering table would fire a request per character of
 * a username against an endpoint that matches exactly.
 *
 * Nothing in the `details` column can contain a secret: the backend writes field *names* there, never
 * values, which is what makes it safe to render the whole map.
 *
 * @returns the audit log tab
 */
export function AuditLogTab() {
  const [draft, setDraft] = useState<FilterDraft>(EMPTY_FILTERS);
  const [applied, setApplied] = useState<AuditLogFilters>({});
  const [page, setPage] = useState(0);

  const { data, isPending, isError, error, refetch } = useAuditLogs({ ...applied, page });

  const today = todayIsoDate();
  const rangeInverted = Boolean(draft.fromDate && draft.toDate && draft.fromDate > draft.toDate);

  const onApply = (event: React.FormEvent) => {
    event.preventDefault();
    if (rangeInverted) return;
    setApplied({
      actorUsername: draft.actorUsername.trim() || undefined,
      action: draft.action || undefined,
      // Widened to whole local days: the API takes instants, so a bare date would mean midnight and
      // silently exclude everything that happened on the "to" day.
      from: dayBoundary(draft.fromDate, 'start'),
      to: dayBoundary(draft.toDate, 'end'),
    });
    setPage(0);
  };

  const onReset = () => {
    setDraft(EMPTY_FILTERS);
    setApplied({});
    setPage(0);
  };

  const filtered = Object.values(applied).some(Boolean);

  const set = <K extends keyof FilterDraft>(key: K, value: FilterDraft[K]) =>
    setDraft((previous) => ({ ...previous, [key]: value }));

  return (
    <div className="stack">
      <section className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>Filters</h2>

        <form onSubmit={onApply}>
          <div className={styles.filters}>
            <div className={styles.filterField}>
              <label className="label" htmlFor="audit-actor">
                Username
              </label>
              <input
                id="audit-actor"
                className="input"
                value={draft.actorUsername}
                autoComplete="off"
                onChange={(event) => set('actorUsername', event.target.value)}
              />
            </div>

            <div className={styles.filterField}>
              <label className="label" htmlFor="audit-action">
                Action
              </label>
              <select
                id="audit-action"
                className="select"
                value={draft.action}
                onChange={(event) => set('action', event.target.value as AuditAction | '')}
              >
                <option value="">Any action</option>
                {ACTIONS.map((action) => (
                  <option key={action} value={action}>
                    {humaniseEnum(action)}
                  </option>
                ))}
              </select>
            </div>

            <div className={styles.filterField}>
              <label className="label" htmlFor="audit-from">
                From
              </label>
              <input
                id="audit-from"
                className="input"
                type="date"
                max={today}
                value={draft.fromDate}
                onChange={(event) => set('fromDate', event.target.value)}
              />
            </div>

            <div className={styles.filterField}>
              <label className="label" htmlFor="audit-to">
                To
              </label>
              <input
                id="audit-to"
                className="input"
                type="date"
                min={draft.fromDate || undefined}
                max={today}
                value={draft.toDate}
                onChange={(event) => set('toDate', event.target.value)}
              />
            </div>
          </div>

          {rangeInverted ? (
            <p className="field-error" role="alert">
              The "from" date has to be on or before the "to" date.
            </p>
          ) : null}

          <p className="hint">The username must match exactly - this is not a substring search.</p>

          <div className="row" style={{ marginTop: 'var(--space-4)', flexWrap: 'wrap' }}>
            <button type="submit" className="btn btn-primary" disabled={rangeInverted}>
              Apply filters
            </button>
            {filtered ? (
              <button type="button" className="btn btn-ghost" onClick={onReset}>
                Clear
              </button>
            ) : null}
          </div>
        </form>
      </section>

      <section className="card">
        <h2 style={{ fontSize: 'var(--text-lg)', marginBottom: 'var(--space-4)' }}>Activity</h2>

        {isPending ? (
          <SkeletonTable rows={6} columns={4} label="Loading the audit log" />
        ) : isError ? (
          <ErrorState error={error} title="Could not load the audit log" onRetry={() => refetch()} />
        ) : !data || data.content.length === 0 ? (
          <EmptyState
            title={filtered ? 'Nothing matches those filters' : 'Nothing recorded yet'}
            description={
              filtered
                ? 'Try widening the date range, or check the username - it has to match exactly.'
                : 'Sign-ins, product changes, runs, credential changes and settings changes are all recorded here as they happen.'
            }
            action={
              filtered ? (
                <button type="button" className="btn btn-secondary" onClick={onReset}>
                  Clear filters
                </button>
              ) : undefined
            }
          />
        ) : (
          <div className="stack">
            <div className="table-scroll">
              <table className={styles.table}>
                <thead>
                  <tr>
                    <th scope="col">When</th>
                    <th scope="col">Who</th>
                    <th scope="col">Action</th>
                    <th scope="col">Target</th>
                    <th scope="col">Details</th>
                  </tr>
                </thead>
                <tbody>
                  {data.content.map((entry) => (
                    <AuditRow key={entry.id} entry={entry} />
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination
              page={data}
              onChange={setPage}
              label="entries"
              pageSize={AUDIT_PAGE_SIZE}
            />
          </div>
        )}
      </section>
    </div>
  );
}

/**
 * One audit entry's row.
 *
 * @param props.entry the entry
 * @returns the row
 */
function AuditRow({ entry }: { entry: AuditLogEntry }) {
  return (
    <tr>
      <td>
        {formatDateTime(entry.timestamp)}
        <div className="muted" style={{ fontSize: 'var(--text-xs)' }}>
          {timeAgo(entry.timestamp)}
        </div>
      </td>
      <td className={styles.mono}>
        {entry.actorUsername}
        {/* A failed login has a username but no user id - the point being that the account may not
            exist at all. Saying so is more useful than an empty cell. */}
        {entry.actorUserId === null ? (
          <div className="muted" style={{ fontSize: 'var(--text-xs)' }}>
            no account
          </div>
        ) : null}
      </td>
      <td>
        <span className={`pill ${actionClass(entry.action)}`}>{humaniseEnum(entry.action)}</span>
      </td>
      <td>
        {entry.targetType ? (
          <>
            {entry.targetType}
            {entry.targetId ? <div className={styles.mono}>{entry.targetId}</div> : null}
          </>
        ) : (
          <span className="muted">—</span>
        )}
      </td>
      <td className={styles.mono}>{formatDetails(entry.details)}</td>
    </tr>
  );
}

/**
 * Renders an entry's `details` map as `key=value` pairs.
 *
 * Safe to render wholesale: the backend writes field names and non-secret facts here and never a
 * password, API key, auth token, or signing secret - see the note on `AuditLogEntry`. Nested values are
 * JSON-stringified rather than dropped, because for the Data Explorer entries the changed-field list
 * *is* the detail.
 *
 * @param details the detail map, or null
 * @returns a one-line rendering, or an em dash
 */
function formatDetails(details: Record<string, unknown> | null): string {
  if (!details || Object.keys(details).length === 0) return '—';

  return Object.entries(details)
    .map(([key, value]) => {
      const rendered =
        typeof value === 'object' && value !== null ? JSON.stringify(value) : String(value);
      return `${key}=${rendered}`;
    })
    .join('  ');
}

/**
 * Picks a pill class for an action.
 *
 * Only three cases are distinguished - failures, deletions, and everything else - because seventeen
 * differently-coloured pills in one column is decoration rather than information.
 *
 * @param action the audit action
 * @returns the pill class
 */
function actionClass(action: AuditAction): string {
  if (action === 'AUTH_LOGIN_FAILURE') return 'pill-error';
  if (action.includes('DELETED') || action.includes('CLEARED')) return 'pill-warning';
  return 'pill-neutral';
}

export default AuditLogTab;
