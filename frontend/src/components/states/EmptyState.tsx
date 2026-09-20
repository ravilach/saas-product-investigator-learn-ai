import type { ReactNode } from 'react';
import styles from './States.module.css';

/** Props for {@link EmptyState}. */
export interface EmptyStateProps {
  /** What is empty, e.g. "No SaaS Products yet". */
  title: string;
  /** Why it is empty and what to do about it. */
  description?: string;
  /** The action that fixes it - usually a single primary button or link. */
  action?: ReactNode;
}

/**
 * The "nothing here yet" panel.
 *
 * A distinct state from loading and from error, which is the whole point: ERROR HANDLING asks that
 * every async view have all three rather than assuming the happy path. An empty list rendered as a
 * blank area is indistinguishable from a list that failed silently, and a first-time user meets the
 * empty state before they meet anything else - so it is the screen that has to tell them what to do
 * next.
 *
 * @param props see {@link EmptyStateProps}
 * @returns the empty panel
 */
export function EmptyState({ title, description, action }: EmptyStateProps) {
  return (
    <div className={styles.empty}>
      <h3 className={styles.emptyHeading}>{title}</h3>
      {description ? <p className={styles.emptyDescription}>{description}</p> : null}
      {action ? <div className={styles.actions}>{action}</div> : null}
    </div>
  );
}
