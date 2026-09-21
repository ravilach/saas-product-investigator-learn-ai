import type { AnalysisDepth, ChangeCategory, LastRun, SourceType } from '../api/types';
import { depthLabel, humaniseEnum, sourceTypeLabel } from '../utils/format';
import styles from './Badges.module.css';

/**
 * The small categorical badges that appear across several screens.
 *
 * DESIGN & UX asks for "colored pill badges for categorical/severity-like data in tables" and for the
 * change categories specifically. Collected in one file so the same category gets the same colour in
 * the History timeline, in a fresh run's result, and in the report detail - rather than each screen
 * picking its own.
 */

/** Which CSS class each change category uses. */
const CATEGORY_CLASS: Record<ChangeCategory, string> = {
  feature: styles.feature,
  pricing: styles.pricing,
  policy: styles.policy,
  bugfix: styles.bugfix,
  documentation: styles.documentation,
  deprecation: styles.deprecation,
  other: styles.other,
};

/**
 * A change's category, as a coloured pill.
 *
 * @param props.category the category from the report
 * @returns the badge
 */
export function CategoryBadge({ category }: { category: ChangeCategory }) {
  return (
    <span className={`pill ${CATEGORY_CLASS[category] ?? styles.other}`}>
      {humaniseEnum(category)}
    </span>
  );
}

/**
 * The analysis depth a report was produced at.
 *
 * Part of what History has to show per entry (see DESIGN & UX): the same product run at SHORT and at
 * NUCLEAR produces very differently-sized reports, and without this the shorter one looks like a run
 * that found less rather than one that was asked for less.
 *
 * @param props.depth the depth
 * @returns the badge
 */
export function DepthBadge({ depth }: { depth: AnalysisDepth }) {
  return <span className={`pill ${styles.depth}`}>{depthLabel(depth)}</span>;
}

/**
 * A confidence level, coloured by how much weight it deserves.
 *
 * @param props.confidence the model's confidence in a change
 * @returns the badge
 */
export function ConfidenceBadge({ confidence }: { confidence: string }) {
  // Compared against the wire values, which are lower case. Upper-case comparisons here matched
  // nothing, so every level rendered as the neutral middle one.
  const tone =
    confidence === 'high' ? 'pill-success' : confidence === 'low' ? 'pill-warning' : 'pill-neutral';
  return <span className={`pill ${tone}`}>{humaniseEnum(confidence)} confidence</span>;
}

/**
 * A product's last-run status, for the Dashboard cards.
 *
 * A product that has never run gets its own neutral pill rather than an empty space: "never run" is
 * useful information and is the state a newly-created product is legitimately in.
 *
 * @param props.lastRun the product's last run, or null
 * @returns the pill
 */
export function LastRunPill({ lastRun }: { lastRun: LastRun | null }) {
  if (!lastRun) return <span className="pill pill-neutral">Never run</span>;

  // The count is the interesting part of a completed run's status - "Success" alone is true of a run
  // that found nothing and of one that found fourteen things, which are not the same news.
  if (lastRun.changeCount === 0) return <span className="pill pill-neutral">No changes</span>;

  return (
    <span className="pill pill-success">
      {lastRun.changeCount} change{lastRun.changeCount === 1 ? '' : 's'}
    </span>
  );
}

/**
 * A source's type and name, as a chip.
 *
 * @param props.type the source type
 * @param props.name the source's own name
 * @returns the chip
 */
export function SourceChip({ type, name }: { type: SourceType; name: string }) {
  return (
    <span className={styles.sourceChip}>
      <span className={styles.sourceChipType}>{sourceTypeLabel(type)}</span>
      <span className={styles.sourceChipName}>{name}</span>
    </span>
  );
}

/**
 * The Data Explorer's stand-in for a secret field.
 *
 * Rendered instead of - never alongside - an input, per ADMIN CONSOLE: a generic "edit any field" tool
 * that let someone type over `passwordHash` or `apiKeyEncrypted` would quietly undo the protections
 * built everywhere else. The value behind this never reaches the browser at all; the backend
 * substitutes the mask before responding.
 *
 * @returns the disabled chip
 */
export function EncryptedChip() {
  return (
    <span className={styles.encrypted} aria-disabled="true">
      <LockIcon />
      [encrypted]
    </span>
  );
}

/** @returns a small padlock glyph */
function LockIcon() {
  return (
    <svg
      width="12"
      height="12"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2.5"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V8a4 4 0 0 1 8 0v3" />
    </svg>
  );
}
