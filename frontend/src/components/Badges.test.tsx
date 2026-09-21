import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ChangeCategory, Confidence } from '../api/types';
import { CategoryBadge, ConfidenceBadge, DepthBadge, LastRunPill } from './Badges';

/**
 * The categorical badges.
 *
 * These tests exist because of a bug they would have caught and nothing else did. `CATEGORY_CLASS` and
 * `ConfidenceBadge` were keyed on upper-case values (`FEATURE`, `HIGH`) while the API sends the enums'
 * lower-case wire names, so every lookup missed and every badge fell back to one grey - seven
 * categories and three confidence levels rendered in a single colour. Nothing noticed: the *labels*
 * were right either way, because `humaniseEnum` capitalises whatever it is given, and no test rendered
 * these components at all.
 *
 * So the assertions below are about the values being the ones the wire actually carries, and about the
 * colours being distinct. A test that asserted `humaniseEnum` output would have passed throughout.
 */
describe('CategoryBadge', () => {
  const CATEGORIES: ChangeCategory[] = [
    'feature', 'pricing', 'policy', 'bugfix', 'documentation', 'deprecation', 'other',
  ];

  it('labels each wire category in prose', () => {
    render(
      <>
        {CATEGORIES.map((category) => <CategoryBadge key={category} category={category} />)}
      </>,
    );

    expect(CATEGORIES.map((c) => screen.getByText(labelFor(c)).textContent)).toEqual([
      'Feature', 'Pricing', 'Policy', 'Bugfix', 'Documentation', 'Deprecation', 'Other',
    ]);
  });

  it('gives the visually distinguished categories their own class rather than falling back to one', () => {
    const { container } = render(
      <>
        {CATEGORIES.map((category) => <CategoryBadge key={category} category={category} />)}
      </>,
    );

    const classes = [...container.querySelectorAll('span.pill')].map((el) => el.className);
    // Seven categories, seven distinct class names. `documentation` and `other` share a *rule* in the
    // stylesheet, but CSS modules still emits a name per selector, so distinctness is the right signal
    // here - and it is exactly what the bug destroyed: all seven used to come back identical.
    expect(new Set(classes).size).toBe(7);

    // Named explicitly, because "all distinct" would also be satisfied by seven wrong classes: no
    // category other than `other` may resolve to the `other` style.
    const otherClass = classes[CATEGORIES.indexOf('other')];
    expect(classes.filter((c) => c === otherClass)).toHaveLength(1);
  });

  it('falls back to a rendered badge for a category this build does not know', () => {
    // A backend that adds a category should not blank the badge out.
    render(<CategoryBadge category={'quantum' as ChangeCategory} />);
    expect(screen.getByText('Quantum')).toBeInTheDocument();
  });
});

describe('ConfidenceBadge', () => {
  it.each<[Confidence, string, string]>([
    ['high', 'High confidence', 'pill-success'],
    ['medium', 'Medium confidence', 'pill-neutral'],
    ['low', 'Low confidence', 'pill-warning'],
  ])('tones %s as %s', (confidence, label, tone) => {
    render(<ConfidenceBadge confidence={confidence} />);
    const badge = screen.getByText(label);
    expect(badge).toHaveClass(tone);
  });

  it('gives the three levels three different tones', () => {
    const { container } = render(
      <>
        <ConfidenceBadge confidence="high" />
        <ConfidenceBadge confidence="medium" />
        <ConfidenceBadge confidence="low" />
      </>,
    );
    const tones = [...container.querySelectorAll('span.pill')]
      .map((el) => [...el.classList].find((c) => c.startsWith('pill-')));
    expect(new Set(tones).size).toBe(3);
  });
});

describe('DepthBadge', () => {
  it('names the depth the way the rest of the UI does', () => {
    render(<DepthBadge depth="NUCLEAR" />);
    expect(screen.getByText('Nuclear Analysis')).toBeInTheDocument();
  });
});

describe('LastRunPill', () => {
  it('distinguishes never run, no changes, and a count', () => {
    const { rerender } = render(<LastRunPill lastRun={null} />);
    expect(screen.getByText('Never run')).toBeInTheDocument();

    const lastRun = {
      reportId: 'r1', runAt: '2026-09-21T12:00:00Z', runType: 'STANDARD' as const,
      analysisDepth: 'REGULAR' as const, runBy: 'admin', changeCount: 0,
    };
    rerender(<LastRunPill lastRun={lastRun} />);
    expect(screen.getByText('No changes')).toBeInTheDocument();

    rerender(<LastRunPill lastRun={{ ...lastRun, changeCount: 1 }} />);
    expect(screen.getByText('1 change')).toBeInTheDocument();

    rerender(<LastRunPill lastRun={{ ...lastRun, changeCount: 4 }} />);
    expect(screen.getByText('4 changes')).toBeInTheDocument();
  });
});

/** The prose label a wire category is expected to render as. */
function labelFor(category: string): string {
  return category.charAt(0).toUpperCase() + category.slice(1);
}
