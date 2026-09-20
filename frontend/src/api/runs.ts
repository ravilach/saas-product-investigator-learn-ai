import { useCallback, useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from './ApiError';
import { apiFetch } from './client';
import { queryKeys } from './queryClient';
import { applyRunEvent, initialRunState, type RunKind, type RunViewState } from './runEvents';
import { streamSse } from './sse';
import type { AnalysisDepth, AskEvent, RunEvent, RunStartedResponse } from './types';

/**
 * The live-run and ask hooks: the two places in the app where the UI is driven by a server stream
 * rather than by a query.
 *
 * Both follow the same shape - a user action opens a stream, an `AbortController` in a ref closes it
 * on unmount or on the next action, and the stream's own terminal event decides when it is done.
 * Neither goes through TanStack Query: a stream is not a cacheable resource, and the durable result
 * (the persisted report) is fetched through Query afterwards by invalidating the keys below.
 */

/** What a compare needs: two days, and how hard to think about them. */
export interface CompareParams {
  /** `YYYY-MM-DD`, which is what the backend's `LocalDate` binding expects. */
  fromDate: string;
  toDate: string;
  analysisDepth: AnalysisDepth;
}

/** What {@link useRunStream} exposes to the view. */
export interface RunStreamController {
  state: RunViewState;
  /** Starts a standard run: fetches and crawls everything, then compares against the last snapshots. */
  startRun: (analysisDepth: AnalysisDepth) => Promise<void>;
  /** Starts a compare over stored history. Never fetches anything. */
  startCompare: (params: CompareParams) => Promise<void>;
  /** Clears the view back to idle, for "run again" without leaving the previous result on screen. */
  reset: () => void;
  /** True between the POST and the terminal event, i.e. when the Run button should be disabled. */
  busy: boolean;
}

/**
 * Drives the live execution view for one product.
 *
 * The POST and the stream are two requests on purpose - that is the backend's contract (`202` with a
 * `runId`, then subscribe) - and the reason it is worth it is replay: subscribing late still delivers
 * everything already emitted, so a slow first render does not lose the first crawl's progress.
 *
 * @param productId the product being run
 * @param kind which step list to draw before events arrive
 * @returns the state and the actions that drive it
 */
export function useRunStream(productId: string, kind: RunKind): RunStreamController {
  const queryClient = useQueryClient();
  const [state, setState] = useState<RunViewState>(() => initialRunState(kind));
  const abortRef = useRef<AbortController | null>(null);

  // Aborting on unmount matters more here than for an ordinary fetch: the server holds an open
  // emitter per subscriber, so a navigation away from a running view would otherwise leave it
  // publishing into a connection nobody is reading.
  useEffect(() => () => abortRef.current?.abort(), []);

  const reset = useCallback(() => {
    abortRef.current?.abort();
    abortRef.current = null;
    setState(initialRunState(kind));
  }, [kind]);

  const begin = useCallback(
    async (start: () => Promise<RunStartedResponse>) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setState({ ...initialRunState(kind), phase: 'starting' });

      let runId: string;
      try {
        runId = (await start()).runId;
      } catch (cause) {
        // A failure here is the request being refused outright - at capacity, or a range the backend
        // cannot answer - which is a different thing from a run that started and then broke. It has a
        // real message from the backend, so it is shown as the failure rather than re-worded.
        setState((previous) => ({
          ...previous,
          phase: 'failed',
          failureMessage: ApiError.messageFrom(cause),
        }));
        return;
      }

      setState((previous) => ({ ...previous, runId, phase: 'running' }));

      try {
        await streamSse(`/api/saas-products/${productId}/runs/${runId}/events`, {
          signal: controller.signal,
          onMessage: ({ data }) => {
            let event: RunEvent;
            try {
              event = JSON.parse(data) as RunEvent;
            } catch {
              // One unparsable frame is not worth ending a run over; the next event will still be
              // applied, and a run that completes still delivers its report.
              return;
            }
            setState((previous) => applyRunEvent(previous, event));
          },
        });
      } catch (cause) {
        // The stream dropped. The run itself is server-side and its report is persisted regardless,
        // so this says so rather than claiming the run failed.
        setState((previous) =>
          previous.phase === 'completed'
            ? previous
            : {
                ...previous,
                phase: 'failed',
                failureMessage: `${ApiError.messageFrom(cause)} The run itself may still be going - check History in a moment.`,
              },
        );
        return;
      }

      // Invalidated after the stream closes rather than on the completion event, so the refetch lands
      // once, with the report already persisted.
      queryClient.invalidateQueries({ queryKey: queryKeys.products.detail(productId) });
      queryClient.invalidateQueries({ queryKey: queryKeys.products.all });
    },
    [kind, productId, queryClient],
  );

  const startRun = useCallback(
    (analysisDepth: AnalysisDepth) =>
      begin(() =>
        apiFetch<RunStartedResponse>(`/api/saas-products/${productId}/run`, {
          method: 'POST',
          body: { analysisDepth },
        }),
      ),
    [begin, productId],
  );

  const startCompare = useCallback(
    (params: CompareParams) =>
      begin(() =>
        apiFetch<RunStartedResponse>(`/api/saas-products/${productId}/compare`, {
          method: 'POST',
          body: params,
        }),
      ),
    [begin, productId],
  );

  return {
    state,
    startRun,
    startCompare,
    reset,
    busy: state.phase === 'starting' || state.phase === 'running',
  };
}

/** One exchange in the Ask tab. */
export interface AskExchange {
  question: string;
  /** Grows as chunks arrive. Replaced by the complete answer on `done`. */
  answer: string;
  /** True while tokens are still arriving, which is what drives the caret and the disabled input. */
  streaming: boolean;
  /** Set if the stream reported a problem instead of an answer. */
  error: string | null;
}

/** What {@link useAskStream} exposes to the view. */
export interface AskController {
  /** Newest last, so the view can render it as a transcript. */
  exchanges: AskExchange[];
  ask: (question: string) => Promise<void>;
  /** True while an answer is streaming; the input is disabled rather than queueing a second question. */
  busy: boolean;
}

/**
 * Drives the Ask tab's streaming answer.
 *
 * @param productId the product being asked about
 * @returns the transcript and the action that adds to it
 */
export function useAskStream(productId: string): AskController {
  const [exchanges, setExchanges] = useState<AskExchange[]>([]);
  const [busy, setBusy] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  const ask = useCallback(
    async (question: string) => {
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setBusy(true);
      setExchanges((previous) => [
        ...previous,
        { question, answer: '', streaming: true, error: null },
      ]);

      /**
       * Applies a change to the exchange currently streaming - always the last one.
       *
       * @param change what to merge into it
       */
      const updateCurrent = (change: Partial<AskExchange>) =>
        setExchanges((previous) =>
          previous.map((exchange, index) =>
            index === previous.length - 1 ? { ...exchange, ...change } : exchange,
          ),
        );

      try {
        await streamSse(`/api/saas-products/${productId}/ask`, {
          method: 'POST',
          body: { question },
          signal: controller.signal,
          onMessage: ({ data }) => {
            let event: AskEvent;
            try {
              event = JSON.parse(data) as AskEvent;
            } catch {
              return;
            }

            if (event.type === 'chunk' && event.text) {
              // Appended to whatever has arrived so far. `done` then replaces the whole thing with
              // the server's complete answer, so a dropped chunk cannot leave a hole in the text.
              setExchanges((previous) =>
                previous.map((exchange, index) =>
                  index === previous.length - 1
                    ? { ...exchange, answer: exchange.answer + event.text }
                    : exchange,
                ),
              );
            } else if (event.type === 'done') {
              updateCurrent({ answer: event.answer ?? '', streaming: false });
            } else if (event.type === 'error') {
              updateCurrent({
                streaming: false,
                error: event.message ?? 'The answer could not be completed.',
              });
            }
          },
        });
      } catch (cause) {
        updateCurrent({ streaming: false, error: ApiError.messageFrom(cause) });
      } finally {
        setBusy(false);
        // Anything still marked as streaming here never got its `done` event - the stream closed
        // early. Clearing the flag stops the caret blinking forever on a finished exchange.
        setExchanges((previous) =>
          previous.map((exchange) => (exchange.streaming ? { ...exchange, streaming: false } : exchange)),
        );
      }
    },
    [productId],
  );

  return { exchanges, ask, busy };
}
