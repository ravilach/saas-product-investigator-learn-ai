import type { Page } from '../api/types';

/** Props for {@link Pagination}. */
export interface PaginationProps {
  /** The page envelope from the API. */
  page: Pick<Page<unknown>, 'page' | 'totalPages' | 'totalElements' | 'first' | 'last'>;
  /** Called with the zero-based page number to move to. */
  onChange: (page: number) => void;
  /** What is being counted, e.g. `reports`. Used in the "1-20 of 43 reports" line. */
  label: string;
  /** How many items are on the current page, for the range line. */
  pageSize: number;
}

/**
 * Previous/next paging with a position line.
 *
 * Deliberately not numbered page links: nothing in this app has a reason to jump to page 7 of the
 * audit log, and the count line is what people actually read off a pager - "am I near the end". It
 * renders nothing at all for a single page, so a short list is not decorated with a dead control.
 *
 * @param props see {@link PaginationProps}
 * @returns the pager, or `null` when there is only one page
 */
export function Pagination({ page, onChange, label, pageSize }: PaginationProps) {
  if (page.totalPages <= 1) return null;

  const from = page.page * pageSize + 1;
  const to = Math.min(page.totalElements, (page.page + 1) * pageSize);

  return (
    <div className="row" style={{ justifyContent: 'space-between', flexWrap: 'wrap' }}>
      <span className="muted" style={{ fontSize: 'var(--text-sm)' }}>
        {from}–{to} of {page.totalElements} {label}
      </span>
      <div className="row">
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={() => onChange(page.page - 1)}
          disabled={page.first}
        >
          Previous
        </button>
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={() => onChange(page.page + 1)}
          disabled={page.last}
        >
          Next
        </button>
      </div>
    </div>
  );
}
