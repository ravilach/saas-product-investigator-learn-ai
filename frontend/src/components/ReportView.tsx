import type { ChangeCategory, ChangeReport, ChangeResponse } from '../api/types';
import { formatDateTime, humaniseEnum, sourceTypeLabel } from '../utils/format';
import { CategoryBadge, ConfidenceBadge, SourceChip } from './Badges';
import { EmptyState } from './states/EmptyState';
import styles from './ReportView.module.css';

/**
 * The order categories are grouped in.
 *
 * Roughly by how much someone tracking a SaaS product cares: a pricing or policy change is the kind of
 * thing that needs acting on, a documentation tweak usually is not. Grouping in a fixed order rather
 * than in the model's output order also means two reports on the same product read the same way.
 */
const CATEGORY_ORDER: readonly ChangeCategory[] = [
  'PRICING',
  'POLICY',
  'DEPRECATION',
  'FEATURE',
  'BUGFIX',
  'DOCUMENTATION',
  'OTHER',
];

/** Props for {@link ReportView}. */
export interface ReportViewProps {
  /** The report to render. */
  report: ChangeReport;
  /** Hides the run metadata line, for callers that already show it (the History timeline does). */
  hideMeta?: boolean;
}

/**
 * Renders one change report.
 *
 * Shared by the Run tab's result, the Compare tab's result, and each expanded History entry - so a
 * report looks the same wherever it is read, and the `mcpHistoryLimited` caveat cannot be shown in one
 * place and forgotten in another.
 *
 * Everything here is text content from an LLM or from a crawled page, and every field is rendered as
 * a text node. There is no `dangerouslySetInnerHTML` anywhere in this app, which is the defence ADR
 * 0008 relies on given the token is readable by script.
 *
 * @param props see {@link ReportViewProps}
 * @returns the report body
 */
export function ReportView({ report, hideMeta = false }: ReportViewProps) {
  const grouped = groupByCategory(report.changes);

  return (
    <div>
      {!hideMeta ? (
        <div className={styles.metaRow} style={{ marginBottom: 'var(--space-3)' }}>
          <span>{formatDateTime(report.runAt)}</span>
          <span aria-hidden="true">·</span>
          <span>Run by {report.runBy}</span>
          <span aria-hidden="true">·</span>
          <span>
            {report.changeCount} change{report.changeCount === 1 ? '' : 's'}
          </span>
        </div>
      ) : null}

      <p className={styles.summary}>{report.overallSummary}</p>

      {report.mcpHistoryLimited ? (
        <div className={styles.caveat} role="note">
          <strong>Partly estimated.</strong>
          <span>
            MCP sources only ever report their current state, so the MCP half of this comparison was
            assembled from changes recorded in earlier reports in this date range rather than from
            stored content. The crawled sources were compared directly and are exact.
          </span>
        </div>
      ) : null}

      {report.changes.length === 0 ? (
        <div style={{ marginTop: 'var(--space-4)' }}>
          <EmptyState
            title="No changes detected"
            description="Every source was checked and nothing had changed since the previous snapshot. This is a real result, not a failure."
          />
        </div>
      ) : (
        grouped.map(([category, changes]) => (
          <section key={category} className={styles.group}>
            <div className={styles.groupHeading}>
              <CategoryBadge category={category} />
              <span className="muted" style={{ fontSize: 'var(--text-sm)' }}>
                {changes.length} change{changes.length === 1 ? '' : 's'}
              </span>
            </div>

            <ul className={styles.changes}>
              {changes.map((change, index) => (
                // Index-keyed because a change has no id of its own - it is an element of the report's
                // embedded array, and the list is never reordered or filtered after render.
                <li key={index} className={styles.change}>
                  <div className={styles.changeHeader}>
                    <span>
                      {change.sourceName} · {sourceTypeLabel(change.sourceType)}
                    </span>
                    <ConfidenceBadge confidence={change.confidence} />
                  </div>
                  <p className={styles.changeDescription}>{change.description}</p>
                  {change.evidenceSnippet ? (
                    <blockquote className={styles.evidence}>{change.evidenceSnippet}</blockquote>
                  ) : null}
                </li>
              ))}
            </ul>
          </section>
        ))
      )}

      {report.sourcesIncluded.length > 0 ? (
        <div className={styles.group}>
          <h4 className="muted" style={{ fontSize: 'var(--text-sm)' }}>
            Sources included
          </h4>
          <div className={styles.sources}>
            {report.sourcesIncluded.map((source) => (
              <SourceChip
                key={`${source.sourceName}-${source.fetchedAt}`}
                type={source.sourceType}
                // The capture time is the point of this list on a CUSTOM_RANGE report: it says which
                // stored snapshot each source's content actually came from.
                name={`${source.sourceName} · ${formatDateTime(source.fetchedAt)}`}
              />
            ))}
          </div>
        </div>
      ) : null}
    </div>
  );
}

/**
 * Groups changes by category, in {@link CATEGORY_ORDER}, dropping empty categories.
 *
 * @param changes the report's changes
 * @returns category/changes pairs, ordered
 */
function groupByCategory(changes: ChangeResponse[]): Array<[ChangeCategory, ChangeResponse[]]> {
  const buckets = new Map<ChangeCategory, ChangeResponse[]>();
  for (const change of changes) {
    const bucket = buckets.get(change.category);
    if (bucket) bucket.push(change);
    else buckets.set(change.category, [change]);
  }

  const ordered: Array<[ChangeCategory, ChangeResponse[]]> = [];
  for (const category of CATEGORY_ORDER) {
    const bucket = buckets.get(category);
    if (bucket) {
      ordered.push([category, bucket]);
      buckets.delete(category);
    }
  }
  // Anything left is a category this build does not know about. Appended rather than dropped, so a
  // backend that adds one shows its changes instead of hiding them.
  for (const [category, bucket] of buckets) ordered.push([category, bucket]);

  return ordered;
}

/**
 * A one-line description of what a report covers, for the History timeline's collapsed rows.
 *
 * @param report the report
 * @returns `Run` for a standard run, or the date range for a compare
 */
export function reportScopeLabel(report: ChangeReport): string {
  if (report.runType === 'STANDARD') return 'Run';
  if (report.rangeFrom && report.rangeTo) return `${report.rangeFrom} → ${report.rangeTo}`;
  return humaniseEnum(report.runType);
}
