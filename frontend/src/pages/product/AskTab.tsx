import { useEffect, useRef, useState } from 'react';
import { useAskStream } from '../../api/runs';
import { EmptyState } from '../../components/states/EmptyState';
import styles from './ProductDetail.module.css';

/** Props for {@link AskTab}. */
export interface AskTabProps {
  /** The product being asked about. */
  productId: string;
  /** The product's name, used in the empty state's example questions. */
  productName: string;
}

/**
 * The Ask tab: a query bar with the answer streaming in below it, chat-style.
 *
 * The streaming is real - a POST to `/ask` returning `text/event-stream`, token events applied as they
 * arrive (see AD-HOC ASK). The blinking caret is the only part that is decoration, and it is tied to
 * whether tokens are still arriving rather than running on a timer.
 *
 * Nothing here is persisted, by design: the backend stores no ask history, so the transcript lives for
 * as long as this tab is mounted and no longer. The empty state says so, because a transcript that
 * silently vanishes on navigation is worse than one you were told was temporary.
 *
 * @param props see {@link AskTabProps}
 * @returns the tab
 */
export function AskTab({ productId, productName }: AskTabProps) {
  const [question, setQuestion] = useState('');
  const { exchanges, ask, busy } = useAskStream(productId);
  const transcriptEnd = useRef<HTMLDivElement>(null);

  /** Keeps the newest answer in view as it grows. */
  useEffect(() => {
    if (exchanges.length === 0) return;
    // `nearest` rather than `start`: scrolling the whole page to pin the answer to the top would yank
    // the query bar off screen mid-question.
    transcriptEnd.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [exchanges]);

  const onSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const trimmed = question.trim();
    if (!trimmed || busy) return;
    // Cleared immediately: the question is already in the transcript, and leaving it in the box makes
    // it look like it has not been sent.
    setQuestion('');
    void ask(trimmed);
  };

  return (
    <div>
      <form className={styles.askForm} onSubmit={onSubmit}>
        <label className="sr-only" htmlFor="ask-question">
          Ask a question about {productName}
        </label>
        <input
          id="ask-question"
          className={`input ${styles.askInput}`}
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="What changed in the pricing page recently?"
          disabled={busy}
          autoComplete="off"
        />
        <button type="submit" className="btn btn-primary" disabled={busy || !question.trim()}>
          {busy ? 'Thinking…' : 'Ask'}
        </button>
      </form>

      <p className="hint" style={{ marginTop: 'var(--space-2)' }}>
        Answered from this product's latest stored snapshots and most recent report. Nothing is fetched,
        and nothing here is saved.
      </p>

      {exchanges.length === 0 ? (
        <div style={{ marginTop: 'var(--space-5)' }}>
          <EmptyState
            title="Ask anything about this product"
            description={`Questions are answered from what has already been collected about ${productName} - the latest snapshot of each source plus the most recent report. Try "summarise the last three changes" or "did anything change about rate limits?".`}
          />
        </div>
      ) : (
        <div className={styles.transcript}>
          {exchanges.map((exchange, index) => (
            // Index-keyed: the transcript is append-only within one mount and is never reordered.
            <div key={index}>
              <p className={styles.question}>{exchange.question}</p>

              {exchange.error ? (
                <p className="field-error" role="alert" style={{ padding: '0 var(--space-4)' }}>
                  {exchange.error}
                </p>
              ) : (
                <p className={styles.answer}>
                  {exchange.answer}
                  {exchange.streaming ? <span className={styles.caret} aria-hidden="true" /> : null}
                  {/* An answer that is still empty while streaming would otherwise render as a bare
                      caret with no indication that anything is happening. */}
                  {exchange.streaming && !exchange.answer ? (
                    <span className="muted">Waiting for the first tokens…</span>
                  ) : null}
                </p>
              )}
            </div>
          ))}
          <div ref={transcriptEnd} />
        </div>
      )}
    </div>
  );
}
