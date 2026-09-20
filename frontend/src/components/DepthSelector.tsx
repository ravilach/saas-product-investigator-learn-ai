import type { AnalysisDepth } from '../api/types';
import styles from './DepthSelector.module.css';

/**
 * The three depths, in order, with the label and the one-line explanation each shows.
 *
 * The descriptions paraphrase the actual prompt instructions from ANALYSIS DEPTH, because the choice
 * is meaningless without them: "Nuclear" tells you nothing about what you are about to pay for, and
 * this control is the only place in the UI where the difference can be explained at the point of
 * deciding.
 */
const DEPTHS: ReadonlyArray<{ value: AnalysisDepth; label: string; description: string }> = [
  {
    value: 'SHORT',
    label: 'Short Summary',
    description: 'A few sentences and the 3–5 most significant changes. Minor changes are omitted.',
  },
  {
    value: 'REGULAR',
    label: 'Regular',
    description: 'Comprehensive but not exhaustive. The right choice unless you have a reason.',
  },
  {
    value: 'NUCLEAR',
    label: 'Nuclear Analysis',
    description:
      'Every discernible change, however minor, with more evidence quoted. Slower and costs more.',
  },
];

/** Props for {@link DepthSelector}. */
export interface DepthSelectorProps {
  /** The currently selected depth. */
  value: AnalysisDepth;
  /** Called with the newly selected depth. */
  onChange: (depth: AnalysisDepth) => void;
  /** Disables the whole control, e.g. while a run is in flight. */
  disabled?: boolean;
  /** The group's accessible name. Defaults to `Analysis depth`. */
  label?: string;
}

/**
 * The Short / Regular / Nuclear segmented control.
 *
 * Appears next to the Run button and next to the Compare date pickers (see ANALYSIS DEPTH). It is a
 * per-invocation choice rather than a product setting, so the state lives in whichever tab renders it
 * and is passed in here - this component holds none of its own.
 *
 * Built from buttons in a `role="radiogroup"` rather than from real radio inputs: the visual design is
 * a segmented bar, and styling radios into that shape means hiding the input and pinning a label over
 * it, which is more moving parts than announcing the roles directly. Arrow-key navigation is wired up
 * so the group still behaves like a radio group for a keyboard user.
 *
 * @param props see {@link DepthSelectorProps}
 * @returns the control
 */
export function DepthSelector({
  value,
  onChange,
  disabled = false,
  label = 'Analysis depth',
}: DepthSelectorProps) {
  const selected = DEPTHS.find((depth) => depth.value === value) ?? DEPTHS[1];

  /**
   * Moves the selection with the arrow keys, as a radio group is expected to.
   *
   * @param event the keyboard event on a segment
   */
  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    // Both axes, because the control stacks vertically below the phone breakpoint.
    const forward = event.key === 'ArrowRight' || event.key === 'ArrowDown';
    const backward = event.key === 'ArrowLeft' || event.key === 'ArrowUp';
    if (disabled || (!forward && !backward)) return;
    const step = forward ? 1 : -1;

    event.preventDefault();
    const index = DEPTHS.findIndex((depth) => depth.value === value);
    // Wraps, which is what a radio group does; clamping would make the end of the group feel broken.
    const next = DEPTHS[(index + step + DEPTHS.length) % DEPTHS.length];
    onChange(next.value);
  };

  return (
    <div className={styles.wrapper}>
      <div className={styles.group} role="radiogroup" aria-label={label} onKeyDown={onKeyDown}>
        {DEPTHS.map((depth) => {
          const active = depth.value === value;
          return (
            <button
              key={depth.value}
              type="button"
              role="radio"
              aria-checked={active}
              // Only the selected segment is in the tab order, so Tab moves past the whole group and
              // the arrow keys move within it - the standard radio-group behaviour.
              tabIndex={active ? 0 : -1}
              className={`${styles.segment} ${active ? styles.segmentActive : ''}`}
              disabled={disabled}
              onClick={() => onChange(depth.value)}
            >
              {depth.label}
            </button>
          );
        })}
      </div>
      {/* Not `aria-live`: this changes only in direct response to the user's own click, and announcing
          it again on top of the radio's own state change would be duplicate noise. */}
      <p className={styles.hint}>{selected.description}</p>
    </div>
  );
}
