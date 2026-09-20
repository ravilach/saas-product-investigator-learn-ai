import { useState } from 'react';
import { useRunStream } from '../../api/runs';
import type { AnalysisDepth, SaasProduct } from '../../api/types';
import { DepthSelector } from '../../components/DepthSelector';
import { ReportView } from '../../components/ReportView';
import { RunExecutionView } from '../../components/RunExecutionView';
import { EmptyState } from '../../components/states/EmptyState';
import { timeAgo } from '../../utils/format';
import styles from './ProductDetail.module.css';

/** Props for {@link RunTab}. */
export interface RunTabProps {
  /** The product to run. */
  product: SaasProduct;
}

/**
 * The Run tab: the depth control, the Run button, and the live execution view.
 *
 * Running is available to both roles - a READ_ONLY user can still analyse, they just cannot change what
 * is being analysed (see USERS & ROLES). So there is no role check here.
 *
 * @param props see {@link RunTabProps}
 * @returns the tab
 */
export function RunTab({ product }: RunTabProps) {
  const [depth, setDepth] = useState<AnalysisDepth>('REGULAR');
  const { state, startRun, reset, busy } = useRunStream(product.id, 'run');

  return (
    <div className="stack">
      <div className={styles.runControls}>
        <DepthSelector value={depth} onChange={setDepth} disabled={busy} />

        <div className="row" style={{ flexWrap: 'wrap' }}>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => startRun(depth)}
            disabled={busy || product.sources.length === 0}
          >
            {busy ? 'Running…' : 'Run'}
          </button>
          {/* Only offered once there is something to clear, and never mid-run: resetting would abort
              the stream and leave the run going server-side with nothing watching it. */}
          {!busy && state.phase !== 'idle' ? (
            <button type="button" className="btn btn-ghost" onClick={reset}>
              Clear
            </button>
          ) : null}
        </div>
      </div>

      {product.sources.length === 0 ? (
        <p className="muted" style={{ fontSize: 'var(--text-sm)' }}>
          This product has no sources yet, so a run would have nothing to fetch or compare.
        </p>
      ) : null}

      {state.phase === 'idle' ? (
        <EmptyState
          title="Ready to run"
          description={
            product.lastRun
              ? `Each source is fetched, compared against the snapshot from its last run (${timeAgo(product.lastRun.runAt)}), and the differences are summarised. Progress appears here as it happens.`
              : 'Each source is fetched and stored as a first snapshot. There is nothing to compare against yet, so this first run mainly establishes the baseline.'
          }
        />
      ) : (
        <div className="card">
          <RunExecutionView state={state} />
        </div>
      )}

      {state.report ? (
        <section className="card">
          <h3 style={{ marginBottom: 'var(--space-3)' }}>Result</h3>
          <ReportView report={state.report} />
        </section>
      ) : null}
    </div>
  );
}
