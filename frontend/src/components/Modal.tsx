import { useEffect, useId, useRef, type ReactNode } from 'react';
import styles from './Modal.module.css';

/** Props for {@link Modal}. */
export interface ModalProps {
  /** The dialog's heading. Also its accessible name. */
  title: string;
  /** Called on Escape, on a scrim click, and by the dialog's own Cancel button. */
  onClose: () => void;
  /** The dialog's content - usually a form or a sentence of explanation. */
  children: ReactNode;
  /** The action buttons. Rendered right-aligned, and stacked full-width on a phone. */
  footer?: ReactNode;
}

/**
 * The app's dialog.
 *
 * Written rather than reached for from a library because the app needs exactly one of these and the
 * accessible behaviour it requires is small and well-defined: a labelled `role="dialog"` with
 * `aria-modal`, focus moved inside on open and returned on close, Escape to dismiss, and the page
 * behind it not scrolling. Those are the parts that are actually easy to get wrong, so they are here
 * once instead of three times.
 *
 * Every use of this is a confirmation or a single short form. Anything longer belongs on a page.
 *
 * @param props see {@link ModalProps}
 * @returns the dialog
 */
export function Modal({ title, onClose, children, footer }: ModalProps) {
  const titleId = useId();
  const dialogRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    // Captured before focus moves, so it can be handed back on close. Without this, dismissing a
    // dialog opened from a table row drops focus to the top of the document and a keyboard user has
    // to tab back through the whole page to get to the next row.
    const previouslyFocused = document.activeElement as HTMLElement | null;

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    // The first field, if there is one - a password-reset dialog should be ready to type into. Falls
    // back to the dialog itself, which `tabIndex={-1}` makes focusable for exactly this case.
    const target =
      dialogRef.current?.querySelector<HTMLElement>('input, select, textarea, button') ??
      dialogRef.current;
    target?.focus();

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      previouslyFocused?.focus();
    };
  }, [onClose]);

  return (
    <div
      className={styles.scrim}
      // A click on the scrim closes; a click that started inside the dialog and happened to end on
      // the scrim (a drag while selecting text) must not, hence the target check.
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={dialogRef}
        className={styles.dialog}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
      >
        <h2 id={titleId} className={styles.title}>
          {title}
        </h2>
        <div className={styles.body}>{children}</div>
        {footer ? <div className={styles.footer}>{footer}</div> : null}
      </div>
    </div>
  );
}
