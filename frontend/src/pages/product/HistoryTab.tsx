import { useState } from 'react';
import { downloadReport, REPORTS_PAGE_SIZE, useReports, type ExportFormat } from '../../api/reports';
import type { ChangeReport } from '../../api/types';
import { DepthBadge } from '../../components/Badges';
import { Pagination } from '../../components/Pagination';
import { ReportView, reportScopeLabel } from '../../components/ReportView';
import { EmptyState } from '../../components/states/EmptyState';
import { ErrorState } from '../../components/states/ErrorState';
import { SkeletonTable } from '../../components/states/Skeleton';
import { useToast } from '../../components/toast/ToastProvider';
import { formatDateTime, timeAgo } from '../../utils/format';
import styles from './ProductDetail.module.css';

/** Props for {@link HistoryTab}. */
export interface HistoryTabProps {
  /** The product whose reports are listed. */
  productId: string;
}

/**
 * The History tab: an expandable report timeline with per-entry export.
 *
 * Collapsed by default and expanded on click, rather than showing every report in full: a product run
 * daily for a month has thirty reports, and the useful first question is "when did something change",
 * which the collapsed row's change count answers. The expanded body is the same {@link ReportView} the
 * Run tab shows, so a report read here and a report read fresh are identical.
 *
 * @param props see {@link HistoryTabProps}
 * @returns the tab
 */
export function HistoryTab({ productId }: HistoryTabProps) {
  const [page, setPage] = useState(0);
  const { data, isPending, isError, error, refetch } = useReports(productId, page);

  if (isPending) return <SkeletonTable rows={5} columns={3} label="Loading run history" />;

  if (isError) return <ErrorState error={error} onRetry={() => refetch()} />;

  if (!data || data.content.length === 0) {
    return (
      <EmptyState
        title="No runs yet"
        description="Every run and every date-range comparison is kept here, newest first, with the depth it was produced at and an export in PDF or Word."
      />
    );
  }

  return (
    <div className="stack">
      <ul className={styles.timeline}>
        {data.content.map((report) => (
          <HistoryEntry key={report.id} report={report} productId={productId} />
        ))}
      </ul>
      <Pagination page={data} onChange={setPage} label="reports" pageSize={REPORTS_PAGE_SIZE} />
    </div>
  );
}

/**
 * One timeline entry: a collapsed summary row that expands into the full report.
 *
 * @param props.report the report
 * @param props.productId the owning product, needed for the export URL
 * @returns the entry
 */
function HistoryEntry({ report, productId }: { report: ChangeReport; productId: string }) {
  const [open, setOpen] = useState(false);
  const [exporting, setExporting] = useState<ExportFormat | null>(null);
  const { showError } = useToast();

  /**
   * Downloads this report in one format.
   *
   * @param format `pdf` or `docx`
   */
  const onExport = async (format: ExportFormat) => {
    setExporting(format);
    try {
      await downloadReport(productId, report.id, format);
    } catch (cause) {
      // A failed export is a toast, not an inline error: the report itself is still on screen and
      // perfectly readable, so replacing it with an error panel would be a worse outcome than the
      // failure itself.
      showError(cause, 'Export failed');
    } finally {
      setExporting(null);
    }
  };

  const panelId = `report-${report.id}`;

  return (
    <li className={styles.entry}>
      <button
        type="button"
        className={styles.entryHeader}
        onClick={() => setOpen((current) => !current)}
        aria-expanded={open}
        aria-controls={panelId}
      >
        <span className={`${styles.chevron} ${open ? styles.chevronOpen : ''}`} aria-hidden="true">
          <ChevronIcon />
        </span>

        <span className={styles.entryTitle}>
          {reportScopeLabel(report)}
          <span className="muted" style={{ fontWeight: 400 }}>
            {' · '}
            {formatDateTime(report.runAt)} · {timeAgo(report.runAt)}
          </span>
        </span>

        <span className={styles.entryBadges}>
          <DepthBadge depth={report.analysisDepth} />
          <span className={`pill ${report.changeCount > 0 ? 'pill-success' : 'pill-neutral'}`}>
            {report.changeCount} change{report.changeCount === 1 ? '' : 's'}
          </span>
        </span>
      </button>

      {/*
        Rendered only when open rather than hidden with CSS: an expanded report is a few hundred DOM
        nodes, and thirty of them built up front is exactly the kind of thing that makes a page feel
        slow for no benefit.
      */}
      {open ? (
        <div id={panelId} className={styles.entryBody}>
          <ReportView report={report} hideMeta />

          <div className={styles.entryActions}>
            <span className="muted" style={{ fontSize: 'var(--text-xs)', alignSelf: 'center' }}>
              Export:
            </span>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={() => onExport('pdf')}
              disabled={exporting !== null}
            >
              {exporting === 'pdf' ? 'Preparing…' : 'PDF'}
            </button>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={() => onExport('docx')}
              disabled={exporting !== null}
            >
              {exporting === 'docx' ? 'Preparing…' : 'Word (DOCX)'}
            </button>
          </div>
        </div>
      ) : null}
    </li>
  );
}

/** @returns a right-pointing chevron, rotated by CSS when the entry is open */
function ChevronIcon() {
  return (
    <svg
      width="16"
      height="16"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M9 5l7 7-7 7" />
    </svg>
  );
}
