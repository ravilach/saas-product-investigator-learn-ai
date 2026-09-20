import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { AnalysisDepth } from '../api/types';
import { DepthSelector } from './DepthSelector';

/**
 * A controlled host, for the tests that care about the selection moving rather than about the callback.
 *
 * The component holds no state of its own by design, so a test that clicks a segment and then asserts
 * on `aria-checked` needs something to own that state - otherwise it is asserting that a prop it never
 * changed did not change.
 *
 * @param props.initial the depth to start on
 * @returns the hosted selector
 */
function Hosted({ initial = 'REGULAR' as AnalysisDepth }) {
  const [depth, setDepth] = useState<AnalysisDepth>(initial);
  return <DepthSelector value={depth} onChange={setDepth} />;
}

/**
 * The depth selector.
 *
 * Rendered without the provider stack: it takes no context, reads no query, and adding providers would
 * only make a failure here harder to locate.
 */
describe('DepthSelector', () => {
  it('renders the three depths as a radio group with the current one checked', () => {
    render(<DepthSelector value="REGULAR" onChange={vi.fn()} />);

    const group = screen.getByRole('radiogroup', { name: 'Analysis depth' });
    expect(group).toBeInTheDocument();

    const options = screen.getAllByRole('radio');
    expect(options.map((option) => option.textContent)).toEqual([
      'Short Summary',
      'Regular',
      'Nuclear Analysis',
    ]);

    expect(screen.getByRole('radio', { name: 'Regular' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Short Summary' })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });

  it('reports the chosen depth to its caller', async () => {
    const onChange = vi.fn();
    render(<DepthSelector value="REGULAR" onChange={onChange} />);

    await userEvent.click(screen.getByRole('radio', { name: 'Nuclear Analysis' }));

    expect(onChange).toHaveBeenCalledWith('NUCLEAR');
  });

  it("explains what the selected depth actually does, since the names do not", () => {
    render(<Hosted initial="SHORT" />);

    // The description is what makes the choice meaningful - "Nuclear" on its own says nothing about
    // the cost being accepted - and only the selected depth's description is shown.
    expect(
      screen.getByText(/A few sentences and the 3–5 most significant changes/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/The right choice unless you have a reason/)).not.toBeInTheDocument();
  });

  it('updates the description when the depth changes', async () => {
    render(<Hosted initial="REGULAR" />);

    expect(screen.getByText(/The right choice unless you have a reason/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole('radio', { name: 'Nuclear Analysis' }));

    expect(screen.getByText(/Slower and costs more/)).toBeInTheDocument();
    expect(screen.queryByText(/The right choice unless you have a reason/)).not.toBeInTheDocument();
  });

  it('keeps only the selected segment in the tab order', () => {
    render(<DepthSelector value="NUCLEAR" onChange={vi.fn()} />);

    // Standard radio-group behaviour: Tab moves past the group, arrows move within it. Three tab stops
    // for one choice is the bug this guards against.
    expect(screen.getByRole('radio', { name: 'Nuclear Analysis' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('radio', { name: 'Regular' })).toHaveAttribute('tabindex', '-1');
    expect(screen.getByRole('radio', { name: 'Short Summary' })).toHaveAttribute('tabindex', '-1');
  });

  it('moves the selection with the arrow keys on both axes', async () => {
    render(<Hosted initial="SHORT" />);

    screen.getByRole('radio', { name: 'Short Summary' }).focus();

    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByRole('radio', { name: 'Regular' })).toHaveAttribute('aria-checked', 'true');

    // Down is handled as well as right, because the control stacks vertically on a phone and an arrow
    // that does nothing in that layout reads as a broken control.
    await userEvent.keyboard('{ArrowDown}');
    expect(screen.getByRole('radio', { name: 'Nuclear Analysis' })).toHaveAttribute(
      'aria-checked',
      'true',
    );

    await userEvent.keyboard('{ArrowUp}');
    expect(screen.getByRole('radio', { name: 'Regular' })).toHaveAttribute('aria-checked', 'true');
  });

  it('wraps at both ends rather than stopping', async () => {
    render(<Hosted initial="SHORT" />);

    screen.getByRole('radio', { name: 'Short Summary' }).focus();

    await userEvent.keyboard('{ArrowLeft}');
    // Clamping at the first option makes the end of a radio group feel broken, so it wraps instead.
    expect(screen.getByRole('radio', { name: 'Nuclear Analysis' })).toHaveAttribute(
      'aria-checked',
      'true',
    );

    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByRole('radio', { name: 'Short Summary' })).toHaveAttribute(
      'aria-checked',
      'true',
    );
  });

  it('ignores clicks and arrow keys when disabled', async () => {
    const onChange = vi.fn();
    render(<DepthSelector value="REGULAR" onChange={onChange} disabled />);

    const regular = screen.getByRole('radio', { name: 'Regular' });
    expect(regular).toBeDisabled();

    await userEvent.click(screen.getByRole('radio', { name: 'Nuclear Analysis' }));
    // `keyboard` rather than focusing first: a disabled button cannot take focus, so the event is
    // dispatched at the group, which is where the handler lives.
    await userEvent.type(regular, '{ArrowRight}');

    // A run in flight must not have its depth changed underneath it.
    expect(onChange).not.toHaveBeenCalled();
  });

  it('accepts a custom group label, for a second selector on the same page', () => {
    render(<DepthSelector value="REGULAR" onChange={vi.fn()} label="Compare depth" />);

    // The Compare tab renders one alongside the Run tab's, and two groups both called "Analysis depth"
    // are indistinguishable to a screen reader.
    expect(screen.getByRole('radiogroup', { name: 'Compare depth' })).toBeInTheDocument();
  });

  it('falls back to Regular\'s description if handed a depth it does not know', () => {
    // Defensive: a backend that adds a fourth depth should not blank the hint out from under the
    // control, and `DEPTHS.find` returning undefined would throw on `.description`.
    render(<DepthSelector value={'EXHAUSTIVE' as AnalysisDepth} onChange={vi.fn()} />);

    expect(screen.getByText(/The right choice unless you have a reason/)).toBeInTheDocument();
  });
});
