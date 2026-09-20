import type { ChangeReport, RunEvent } from './types';

/**
 * The live execution view's state machine, as a pure function of events.
 *
 * Kept out of the React hook deliberately: this is the part with the actual logic - which step a
 * `step_progress` belongs to, what a `step_failed` does and does not imply, what happens to a step
 * that never reported completion - and it is far easier to test as `events in, state out` than
 * through a component rendering a stream.
 */

/** Which flow is being narrated. The two differ only in their first two steps. */
export type RunKind = 'run' | 'compare';

/**
 * The step labels each flow passes through, in order.
 *
 * These duplicate the backend's `RunStep` labels, which is a real if small piece of coupling: the UI
 * has to draw the whole list of pending steps *before* any event arrives, so it cannot derive the
 * sequence from the stream. The labels are matched exactly as the backend sends them in `step`.
 *
 * Drift is handled rather than assumed away - {@link applyRunEvent} appends an unrecognised step
 * instead of dropping its events, so a backend that adds a step shows an extra row here rather than
 * silently swallowing that phase of the run.
 */
export const RUN_STEPS: Record<RunKind, readonly string[]> = {
  run: ['Fetching sources', 'Consulting MCP tools', 'Comparing', 'Summarizing'],
  compare: ['Loading historical snapshots', 'Aggregating MCP history', 'Comparing', 'Summarizing'],
};

/** A step's visual status, which maps one-to-one onto the status pills. */
export type StepStatus = 'pending' | 'running' | 'success' | 'failed';

/** One row of the live execution view. */
export interface StepState {
  /** The backend's own label, used as the identity of the row. */
  label: string;
  status: StepStatus;
  /** The most recent real progress line for this step, e.g. `Crawling https://... (page 3 of ~20)`. */
  detail: string | null;
  /** Seconds since the run started, as of this step's last event. */
  elapsedSeconds: number | null;
}

/** The whole view's state. */
export interface RunViewState {
  kind: RunKind;
  /**
   * `starting` covers the gap between the POST and the first event: the run exists server-side but
   * has not narrated anything yet, and showing nothing there reads as a dead button.
   */
  phase: 'idle' | 'starting' | 'running' | 'completed' | 'failed';
  runId: string | null;
  steps: StepState[];
  /** Set once `run_completed` arrives. This is the durable result; the narration above is not. */
  report: ChangeReport | null;
  /** Set on `run_failed`, already human-readable. */
  failureMessage: string | null;
  /** The highest elapsed time any event reported, so the total keeps counting up monotonically. */
  elapsedSeconds: number;
}

/**
 * The state before anything has been started.
 *
 * @param kind which flow's step list to draw
 * @returns the initial state, with every step pending
 */
export function initialRunState(kind: RunKind): RunViewState {
  return {
    kind,
    phase: 'idle',
    runId: null,
    steps: RUN_STEPS[kind].map((label) => ({
      label,
      status: 'pending',
      detail: null,
      elapsedSeconds: null,
    })),
    report: null,
    failureMessage: null,
    elapsedSeconds: 0,
  };
}

/**
 * Applies one event to the state.
 *
 * @param state the current state
 * @param event the event just received
 * @returns the new state; the same object is never mutated
 */
export function applyRunEvent(state: RunViewState, event: RunEvent): RunViewState {
  const elapsedSeconds = Math.max(state.elapsedSeconds, event.elapsedSeconds ?? 0);
  const base: RunViewState = { ...state, elapsedSeconds, runId: event.runId ?? state.runId };

  switch (event.type) {
    case 'step_started':
      return { ...base, phase: 'running', steps: updateStep(base.steps, event, 'running') };

    case 'step_progress':
      // A progress event can name a step other than the one most recently started: the model's MCP
      // tool calls arrive while `Comparing` is the current step, because both happen inside one
      // provider call. So this updates the named step and leaves the others alone.
      return { ...base, phase: 'running', steps: updateStep(base.steps, event, 'running') };

    case 'step_completed':
      return { ...base, phase: 'running', steps: updateStep(base.steps, event, 'success') };

    case 'step_failed':
      // Not fatal. A source that cannot be fetched fails its own step and the run carries on to
      // report that source as unavailable, so the phase stays `running` and only this row goes red.
      return { ...base, phase: 'running', steps: updateStep(base.steps, event, 'failed') };

    case 'run_completed':
      return {
        ...base,
        phase: 'completed',
        report: event.report ?? base.report,
        // Any step still showing as running did finish - the run completed. Leaving a spinner on a
        // finished run is the single most common way this kind of view ends up lying.
        steps: base.steps.map((step) =>
          step.status === 'running' || step.status === 'pending'
            ? { ...step, status: step.status === 'running' ? 'success' : step.status }
            : step,
        ),
      };

    case 'run_failed':
      return {
        ...base,
        phase: 'failed',
        failureMessage: event.detail ?? 'The run failed. Check the steps above for where it stopped.',
        steps: base.steps.map((step) =>
          step.status === 'running' ? { ...step, status: 'failed' } : step,
        ),
      };

    default:
      // An event type this build does not know about. Ignored rather than thrown: an unrecognised
      // narration event must not take down a run that is otherwise proceeding fine.
      return base;
  }
}

/**
 * Sets one step's status and detail, appending the step if it is not already listed.
 *
 * @param steps the current steps
 * @param event the event naming the step
 * @param status the status to apply
 * @returns the new step list
 */
function updateStep(steps: StepState[], event: RunEvent, status: StepStatus): StepState[] {
  if (!event.step) return steps;

  const index = steps.findIndex((step) => step.label === event.step);
  const next: StepState = {
    label: event.step,
    status,
    // A progress event with no detail should not blank out the detail already on screen.
    detail: event.detail ?? (index >= 0 ? steps[index].detail : null),
    elapsedSeconds: event.elapsedSeconds ?? null,
  };

  if (index < 0) return [...steps, next];
  return steps.map((step, i) => (i === index ? next : step));
}
