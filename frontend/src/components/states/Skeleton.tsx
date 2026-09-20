import styles from './States.module.css';

/** Props for {@link Skeleton}. */
export interface SkeletonProps {
  /** CSS width, e.g. `'60%'` or `'8rem'`. Defaults to full width. */
  width?: string;
  /** CSS height. Defaults to one line of body text. */
  height?: string;
  /** Renders as a circle, for an avatar or an icon placeholder. */
  circle?: boolean;
  className?: string;
}

/**
 * A single shimmering placeholder block.
 *
 * REACTIVITY & RESPONSIVE DESIGN asks for skeletons "shaped like the real content rather than a bare
 * spinner, so the person sees what's coming rather than just that something, unspecified, is
 * loading". That only pays off if the shapes are actually right, which is why the composites below
 * ({@link SkeletonCardGrid}, {@link SkeletonTable}) mirror specific screens rather than being one
 * generic box reused everywhere.
 *
 * @param props see {@link SkeletonProps}
 * @returns the placeholder
 */
export function Skeleton({ width, height, circle, className }: SkeletonProps) {
  return (
    <span
      className={`${styles.skeleton} ${circle ? styles.skeletonCircle : ''} ${className ?? ''}`}
      style={{ width, height }}
      // The wrapper carries the accessible "loading" announcement, so the individual blocks are
      // hidden - a screen reader reading out fourteen anonymous placeholders is worse than silence.
      aria-hidden="true"
    />
  );
}

/**
 * Placeholder for the Dashboard's product card grid.
 *
 * @param props.count how many cards to draw; default 6
 * @returns the placeholder grid
 */
export function SkeletonCardGrid({ count = 6 }: { count?: number }) {
  return (
    <div className={styles.skeletonGrid} role="status" aria-label="Loading SaaS Products">
      {Array.from({ length: count }, (_, index) => (
        <div key={index} className={`card ${styles.skeletonCard}`}>
          <Skeleton width="55%" height="1.25rem" />
          <Skeleton width="80%" />
          <div className={styles.skeletonCardFooter}>
            <Skeleton width="4.5rem" height="1.25rem" />
            <Skeleton width="6rem" />
          </div>
        </div>
      ))}
    </div>
  );
}

/**
 * Placeholder for a data table (Users, Audit Log, Data Explorer).
 *
 * @param props.rows how many body rows to draw; default 6
 * @param props.columns how many cells per row; default 4
 * @param props.label what is loading, for the accessible announcement
 * @returns the placeholder table
 */
export function SkeletonTable({
  rows = 6,
  columns = 4,
  label = 'Loading',
}: {
  rows?: number;
  columns?: number;
  label?: string;
}) {
  return (
    <div className={styles.skeletonTable} role="status" aria-label={label}>
      {Array.from({ length: rows }, (_, rowIndex) => (
        <div key={rowIndex} className={styles.skeletonRow}>
          {Array.from({ length: columns }, (_, columnIndex) => (
            <Skeleton
              key={columnIndex}
              // Uneven widths, so the block reads as text rather than as a grid of grey bars.
              width={columnIndex === 0 ? '30%' : columnIndex === columns - 1 ? '15%' : '22%'}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

/**
 * Placeholder for a stack of text lines, e.g. a report body or a detail panel.
 *
 * @param props.lines how many lines to draw; default 3
 * @param props.label what is loading, for the accessible announcement
 * @returns the placeholder lines
 */
export function SkeletonText({ lines = 3, label = 'Loading' }: { lines?: number; label?: string }) {
  return (
    <div className={styles.skeletonText} role="status" aria-label={label}>
      {Array.from({ length: lines }, (_, index) => (
        // The last line is short, the way a real paragraph's last line is.
        <Skeleton key={index} width={index === lines - 1 ? '45%' : '100%'} />
      ))}
    </div>
  );
}
