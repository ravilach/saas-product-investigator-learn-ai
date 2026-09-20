import type { RunViewState, StepState, StepStatus } from '../api/runEvents';
import { formatElapsed } from '../utils/format';
import { ErrorState } from './states/ErrorState';
import styles from './RunExecutionView.module.css';

/** Props for {@link RunExecutionView}. */
export interface RunExecutionViewProps {
  /** The state from `useRunStream`. */
  state: RunViewState;
}

/**
 * The live execution view.
 *
 * Renders whatever the SSE stream has actually said, which is the point REACTIVITY & RESPONSIVE DESIGN
 * makes: each step's trace line is the real `detail` string the backend sent - a URL being crawled, a
 * tool being called, a count of sources being analysed - not a fixed "Thinking...". A step with no
 * detail yet shows none rather than inventing one.
 *
 * Shared by the Run and Compare tabs unchanged. The two differ only in their step labels, which come
 * from the state, so there is nothing to branch on here.
 *
 * @param props see {@link RunExecutionViewProps}
 * @returns the step list, with the run's total elapsed time and any failure message
 */
export function RunExecutionView({ state }: RunExecutionViewProps) {
  return (
    <div>
      <div className={styles.totalRow}>
        <PhasePill state={state} />
        {state.elapsedSeconds > 0 ? (
          <span className={styles.elapsed}>{formatElapsed(state.elapsedSeconds)} elapsed</span>
        ) : null}
      </div>

      {/*
        `aria-live="polite"` on the list, so a screen reader hears the steps change rather than being
        left with a silent page for the two minutes a NUCLEAR run takes. Polite rather than assertive
        because a crawl emits an event per page and interrupting on each one would be unusable.
      */}
      <ol className={styles.steps} aria-live="polite">
        {state.steps.map((step) => (
          <StepRow key={step.label} step={step} />
        ))}
      </ol>

      {state.phase === 'failed' && state.failureMessage ? (
        <div style={{ marginTop: 'var(--space-4)' }}>
          {/* Wrapped in an Error so ErrorState's own handling applies; the message is already
              human-readable, having come from the backend or from the stream handler. */}
          <ErrorState error={new Error(state.failureMessage)} title="Run failed" />
        </div>
      ) : null}
    </div>
  );
}

/**
 * One step row: marker, label, real detail line, status pill, elapsed time.
 *
 * @param props.step the step's state
 * @returns the row
 */
function StepRow({ step }: { step: StepState }) {
  return (
    <li className={styles.step}>
      <span className={styles.rail} aria-hidden="true">
        <span className={`${styles.marker} ${MARKER_CLASS[step.status]}`} />
      </span>

      <div className={styles.body}>
        <div className={`${styles.label} ${step.status === 'pending' ? styles.labelPending : ''}`}>
          {step.label}
        </div>
        {step.detail ? <p className={styles.detail}>{step.detail}</p> : null}
      </div>

      <div className={styles.meta}>
        <StatusPill status={step.status} />
        {step.elapsedSeconds !== null ? (
          <span className={styles.elapsed}>{formatElapsed(step.elapsedSeconds)}</span>
        ) : null}
      </div>
    </li>
  );
}

/** Which marker class each status uses. */
const MARKER_CLASS: Record<StepStatus, string> = {
  pending: '',
  running: styles.markerRunning,
  success: styles.markerSuccess,
  failed: styles.markerFailed,
};

/** The pill text and tone for each step status. */
const STATUS_PILL: Record<StepStatus, { text: string; tone: string } | null> = {
  // A pending step gets no pill: four grey "Pending" pills before anything has happened is noise, and
  // the muted label already says it.
  pending: null,
  running: { text: 'Running', tone: 'pill-running' },
  success: { text: 'Success', tone: 'pill-success' },
  failed: { text: 'Failed', tone: 'pill-error' },
};

/**
 * One step's status pill.
 *
 * @param props.status the step's status
 * @returns the pill, or `null` for a pending step
 */
function StatusPill({ status }: { status: StepStatus }) {
  const pill = STATUS_PILL[status];
  if (!pill) return null;
  return <span className={`pill ${pill.tone}`}>{pill.text}</span>;
}

/**
 * The run's overall state, as one pill.
 *
 * `starting` has its own wording rather than reusing "Running": it covers the gap between the POST
 * being accepted and the first event arriving, and "Starting" is the honest description of a run that
 * exists but has not narrated anything yet.
 *
 * @param props.state the run state
 * @returns the pill
 */
function PhasePill({ state }: { state: RunViewState }) {
  switch (state.phase) {
    case 'starting':
      return <span className="pill pill-running">Starting</span>;
    case 'running':
      return <span className="pill pill-running">Running</span>;
    case 'completed':
      return <span className="pill pill-success">Completed</span>;
    case 'failed':
      return <span className="pill pill-error">Failed</span>;
    default:
      return <span className="pill pill-neutral">Not started</span>;
  }
}
