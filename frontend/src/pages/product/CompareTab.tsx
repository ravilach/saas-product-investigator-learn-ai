import { useState } from 'react';
import { useRunStream } from '../../api/runs';
import type { AnalysisDepth, SaasProduct } from '../../api/types';
import { DepthBadge } from '../../components/Badges';
import { DepthSelector } from '../../components/DepthSelector';
import { ReportView } from '../../components/ReportView';
import { RunExecutionView } from '../../components/RunExecutionView';
import { formatDateTime, timeAgo, todayIsoDate } from '../../utils/format';
import styles from './ProductDetail.module.css';

/** Props for {@link CompareTab}. */
export interface CompareTabProps {
  /** The product being compared. */
  product: SaasProduct;
  /** Switches the page to the Run tab, for the "Since last run" preset. */
  onGoToRun: () => void;
}

/**
 * The Compare tab: two day-granularity date inputs, the depth control, and the same live execution view.
 *
 * Two things here are easy to get subtly wrong, and both are spelled out in CUSTOM DATE-RANGE COMPARE:
 *
 * 1. "Since last run" is *not* this endpoint. It is an ordinary Run, because a run already compares
 *    against the previous snapshot - which is exactly what "since last run" means. So the preset sends
 *    the user to the Run tab rather than filling in dates that would approximate the same thing less
 *    accurately.
 * 2. A compare never fetches anything. It reasons only over stored snapshots and stored reports, which
 *    is why its MCP half carries the `mcpHistoryLimited` caveat that `ReportView` renders.
 *
 * @param props see {@link CompareTabProps}
 * @returns the tab
 */
export function CompareTab({ product, onGoToRun }: CompareTabProps) {
  const [depth, setDepth] = useState<AnalysisDepth>('REGULAR');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState(() => todayIsoDate());
  const { state, startCompare, busy } = useRunStream(product.id, 'compare');

  const today = todayIsoDate();
  // Checked client-side because the backend's own validation of this is a 400 after a round trip, and
  // an inverted range is the single most likely mistake with two date inputs.
  const rangeInverted = Boolean(fromDate && toDate && fromDate > toDate);
  const ready = Boolean(fromDate && toDate) && !rangeInverted;

  return (
    <div className="stack">
      {/*
        The last-run summary sits above the picker rather than next to the preset, because DESIGN & UX
        asks that the person can see what "since last run" actually resolves to *before* clicking it.
      */}
      <div className={styles.lastRunSummary}>
        {product.lastRun ? (
          <>
            <strong>Last run {timeAgo(product.lastRun.runAt)}</strong> ·{' '}
            {formatDateTime(product.lastRun.runAt)} · {product.lastRun.changeCount} change
            {product.lastRun.changeCount === 1 ? '' : 's'} found
          </>
        ) : (
          <strong>This product has never been run.</strong>
        )}
        <div className="row" style={{ marginTop: 'var(--space-3)', flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-secondary btn-sm" onClick={onGoToRun}>
            Since last run
          </button>
          <span className="muted" style={{ fontSize: 'var(--text-xs)' }}>
            That is an ordinary Run - it compares against the snapshots the last run stored, which is
            more exact than picking dates. Opens the Run tab.
          </span>
        </div>
      </div>

      <div className={styles.dateRow}>
        <div className={styles.dateField}>
          <label className="label" htmlFor="compare-from">
            From
          </label>
          <input
            id="compare-from"
            className="input"
            type="date"
            value={fromDate}
            // Neither date may be in the future; the backend rejects that, so the picker should not
            // offer it in the first place.
            max={today}
            onChange={(event) => setFromDate(event.target.value)}
            disabled={busy}
          />
        </div>

        <div className={styles.dateField}>
          <label className="label" htmlFor="compare-to">
            To
          </label>
          <input
            id="compare-to"
            className="input"
            type="date"
            value={toDate}
            min={fromDate || undefined}
            max={today}
            onChange={(event) => setToDate(event.target.value)}
            disabled={busy}
          />
        </div>
      </div>

      {rangeInverted ? (
        <p className="field-error" role="alert">
          The "from" date has to be on or before the "to" date.
        </p>
      ) : null}

      <div className={styles.runControls}>
        <DepthSelector value={depth} onChange={setDepth} disabled={busy} />
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => startCompare({ fromDate, toDate, analysisDepth: depth })}
          disabled={busy || !ready}
        >
          {busy ? 'Comparing…' : 'Compare'}
        </button>
      </div>

      <p className="hint">
        Answered entirely from stored history - nothing is fetched or crawled. A range with no stored
        snapshot at or before the "from" date is refused, with a message naming the earliest date data
        actually exists for.
      </p>

      {state.phase !== 'idle' ? (
        <div className="card">
          <RunExecutionView state={state} />
        </div>
      ) : null}

      {state.report ? (
        <section className="card">
          <div className="row" style={{ marginBottom: 'var(--space-3)', flexWrap: 'wrap' }}>
            <h3>Result</h3>
            <DepthBadge depth={state.report.analysisDepth} />
          </div>
          <ReportView report={state.report} />
        </section>
      ) : null}
    </div>
  );
}
