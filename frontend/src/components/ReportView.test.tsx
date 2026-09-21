import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ChangeReport, ChangeResponse } from '../api/types';
import { ReportView } from './ReportView';

/**
 * The report body - the app's actual output, and until these tests were written the largest component
 * in the frontend with no coverage at all. Two defects lived in it through the whole build as a result:
 * every category badge collapsed onto one colour, and the grouping order documented in
 * `CATEGORY_ORDER` was never applied, because both were keyed on values the API does not send.
 */
function change(overrides: Partial<ChangeResponse> = {}): ChangeResponse {
  return {
    sourceName: 'Changelog',
    sourceType: 'WEBSITE',
    category: 'feature',
    description: 'Something changed, described at enough length to look like real output.',
    confidence: 'high',
    evidenceSnippet: 'evidence',
    ...overrides,
  };
}

function report(overrides: Partial<ChangeReport> = {}): ChangeReport {
  const changes = overrides.changes ?? [change()];
  return {
    id: 'report-1',
    saasProductId: 'product-1',
    runAt: '2026-09-21T16:00:00Z',
    runBy: 'admin',
    runType: 'STANDARD',
    analysisDepth: 'REGULAR',
    rangeFrom: null,
    rangeTo: null,
    mcpHistoryLimited: false,
    sourcesIncluded: [
      { sourceName: 'Changelog', sourceType: 'WEBSITE', fetchedAt: '2026-09-21T16:00:00Z' },
    ],
    overallSummary: 'One line of summary prose.',
    changes,
    changeCount: changes.length,
    ...overrides,
  };
}

/** The metadata row's text - date, who ran it, how many changes. */
function metaText(container: HTMLElement): string {
  const row = container.querySelector('[class*="metaRow"]');
  if (!row) throw new Error('no metadata row rendered');
  return row.textContent ?? '';
}

describe('ReportView', () => {
  it('renders the summary, the change count and the sources included', () => {
    const { container } = render(<ReportView report={report({ changes: [change(), change()] })} />);

    expect(screen.getByText('One line of summary prose.')).toBeInTheDocument();
    expect(screen.getByText('Run by admin')).toBeInTheDocument();
    // Read out of the metadata row specifically. Each category group prints its own count too, so an
    // unscoped search for "2 changes" matches whichever comes first and would pass with the number
    // missing from the row being tested - which is the defect this guards.
    expect(metaText(container)).toContain('2 changes');
  });

  it('pluralises a single change', () => {
    const { container } = render(<ReportView report={report()} />);
    expect(metaText(container)).toContain('1 change');
    expect(metaText(container)).not.toContain('1 changes');
  });

  it('groups changes by category in the documented order, not in the order they arrived', () => {
    const shuffled = [
      change({ category: 'documentation' }),
      change({ category: 'pricing' }),
      change({ category: 'other' }),
      change({ category: 'deprecation' }),
      change({ category: 'feature' }),
    ];

    const { container } = render(<ReportView report={report({ changes: shuffled })} />);

    // Read the group headings in DOM order. CATEGORY_ORDER puts the things worth acting on first, which
    // is the whole point of having an order - and it silently did nothing while the keys mismatched.
    const headings = [...container.querySelectorAll('span.pill')]
      .map((el) => el.textContent?.trim())
      .filter((t) => ['Pricing', 'Deprecation', 'Feature', 'Documentation', 'Other'].includes(t ?? ''));
    expect(headings).toEqual(['Pricing', 'Deprecation', 'Feature', 'Documentation', 'Other']);
  });

  it('counts the changes within each group', () => {
    const changes = [
      change({ category: 'pricing' }),
      change({ category: 'pricing' }),
      change({ category: 'feature' }),
    ];
    render(<ReportView report={report({ changes })} />);

    expect(screen.getByText('2 changes')).toBeInTheDocument();  // the pricing group
    expect(screen.getByText('1 change')).toBeInTheDocument();   // the feature group
  });

  it('says a report with no changes is a real result rather than showing an empty page', () => {
    render(<ReportView report={report({ changes: [], changeCount: 0 })} />);
    expect(screen.getByText('No changes detected')).toBeInTheDocument();
    expect(screen.getByText(/This is a real result, not a failure/)).toBeInTheDocument();
  });

  it('shows the partly-estimated caveat only when the MCP history was limited', () => {
    const { rerender } = render(<ReportView report={report()} />);
    expect(screen.queryByText('Partly estimated.')).not.toBeInTheDocument();

    rerender(<ReportView report={report({ mcpHistoryLimited: true })} />);
    expect(screen.getByText('Partly estimated.')).toBeInTheDocument();
  });

  it('omits an absent evidence snippet rather than rendering an empty quote', () => {
    const { container } = render(
      <ReportView report={report({ changes: [change({ evidenceSnippet: null })] })} />,
    );
    expect(container.querySelector('blockquote')).toBeNull();
  });

  it('hides the run metadata when the caller already shows it', () => {
    // The History timeline renders its own date and depth on the collapsed row, so repeating them
    // inside the expanded body would say the same thing twice.
    render(<ReportView report={report()} hideMeta />);
    expect(screen.queryByText('Run by admin')).not.toBeInTheDocument();
    expect(screen.getByText('One line of summary prose.')).toBeInTheDocument();
  });
});
