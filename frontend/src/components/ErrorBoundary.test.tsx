import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ErrorBoundary } from './ErrorBoundary';

/**
 * Throws on its first render, then succeeds.
 *
 * @param props.shouldThrow whether to throw
 * @returns the content, if it renders at all
 */
function Boom({ shouldThrow }: { shouldThrow: boolean }) {
  if (shouldThrow) throw new Error('Rendering blew up');
  return <p>Recovered content</p>;
}

describe('ErrorBoundary', () => {
  it('renders a panel instead of a blank screen when a child throws', () => {
    // React logs the caught error itself; silenced so the test output shows only real failures.
    vi.spyOn(console, 'error').mockImplementation(() => {});

    render(
      <ErrorBoundary>
        <Boom shouldThrow />
      </ErrorBoundary>,
    );

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('Something broke on this screen')).toBeInTheDocument();
    expect(screen.getByText('Rendering blew up')).toBeInTheDocument();
  });

  it('renders its children normally when nothing throws', () => {
    render(
      <ErrorBoundary>
        <Boom shouldThrow={false} />
      </ErrorBoundary>,
    );

    expect(screen.getByText('Recovered content')).toBeInTheDocument();
  });

  it('clears the error when resetKey changes', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});

    const { rerender } = render(
      <ErrorBoundary resetKey="/broken">
        <Boom shouldThrow />
      </ErrorBoundary>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();

    // This is what navigating away does: the layout passes the pathname as resetKey, so moving to
    // another page clears the error instead of showing it on the next page too.
    rerender(
      <ErrorBoundary resetKey="/working">
        <Boom shouldThrow={false} />
      </ErrorBoundary>,
    );

    expect(screen.getByText('Recovered content')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('retries on demand', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    let shouldThrow = true;

    /** Reads the mutable flag so "Try again" can succeed the second time. */
    function Flaky() {
      if (shouldThrow) throw new Error('First attempt failed');
      return <p>Recovered content</p>;
    }

    render(
      <ErrorBoundary>
        <Flaky />
      </ErrorBoundary>,
    );
    expect(screen.getByRole('alert')).toBeInTheDocument();

    shouldThrow = false;
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(screen.getByText('Recovered content')).toBeInTheDocument();
  });

  it('uses a custom fallback when given one', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});

    render(
      <ErrorBoundary fallback={(error) => <p>Custom: {error.message}</p>}>
        <Boom shouldThrow />
      </ErrorBoundary>,
    );

    expect(screen.getByText('Custom: Rendering blew up')).toBeInTheDocument();
  });
});
