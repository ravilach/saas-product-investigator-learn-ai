import { ApiError } from './ApiError';
import { buildHeaders, buildUrl, type ApiRequestOptions } from './client';

/**
 * A Server-Sent Events reader built on `fetch` plus a streamed response body, rather than on the
 * browser's `EventSource`.
 *
 * `EventSource` is the obvious choice and is the wrong one here, for two independent reasons the
 * backend's `JwtAuthenticationFilter` spells out from its side:
 *
 * 1. It cannot send request headers, so it cannot authenticate. The usual workaround is an
 *    `?access_token=` query parameter, which the backend deliberately does not accept - URLs end up
 *    in access logs, proxy logs, and browser history in a way headers do not.
 * 2. It can only issue GETs, and `/ask` is a POST carrying the question in its body.
 *
 * So this module does the small amount of parsing `EventSource` would have done: split the stream on
 * blank lines, read the `event:` and `data:` fields of each block. What it gives up by not using the
 * native API is automatic reconnection - which this app does not want anyway, since a run's events
 * are replayed from the server's own history on a fresh subscribe rather than resumed mid-stream.
 */

/** One parsed SSE message. */
export interface SseMessage {
  /** The `event:` field, or `'message'` when the server did not send one. */
  event: string;
  /** The concatenated `data:` lines, still a string - callers parse it into their own shape. */
  data: string;
}

/** Callbacks and options for {@link streamSse}. */
export interface SseOptions extends Omit<ApiRequestOptions, 'authenticated'> {
  /** Called once per parsed message, in order. */
  onMessage: (message: SseMessage) => void;
  /**
   * Aborts the stream. Wire this to the consuming component's cleanup: an un-aborted stream keeps
   * the request - and the server-side emitter behind it - alive after the view is gone.
   */
  signal?: AbortSignal;
}

/**
 * Opens an SSE stream and invokes `onMessage` for each event until the server closes it.
 *
 * Resolves when the stream ends normally, and also when it is aborted - an abort is the caller
 * saying "I am done", not a failure, and making callers write a try/catch to ignore their own
 * cancellation is a reliable source of spurious error toasts.
 *
 * @param path the SSE endpoint path
 * @param options request options plus the message callback
 * @returns a promise that settles when the stream is finished or aborted
 * @throws ApiError if the request fails before the stream opens, or the connection drops mid-stream
 */
export async function streamSse(path: string, options: SseOptions): Promise<void> {
  const { onMessage, signal, body, rawBody, query, headers, ...rest } = options;

  const requestHeaders = buildHeaders(headers, true);
  requestHeaders.set('Accept', 'text/event-stream');
  // Proxies that buffer a response by default will hold the whole stream until it completes, which
  // turns a live execution view into a single delayed dump. This asks them not to.
  requestHeaders.set('Cache-Control', 'no-cache');

  let payload: BodyInit | undefined = rawBody;
  if (body !== undefined) {
    requestHeaders.set('Content-Type', 'application/json');
    payload = JSON.stringify(body);
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      ...rest,
      headers: requestHeaders,
      body: payload,
      signal,
    });
  } catch (cause) {
    if (isAbort(cause, signal)) return;
    throw new ApiError(0, 'NETWORK_ERROR', 'Lost the connection to the server.');
  }

  if (!response.ok) {
    // An error before the stream opens comes back as ordinary JSON, so the shared error path
    // handles it - a 403 on a run is the same 403 it would be anywhere else.
    let message = `Could not open the event stream (HTTP ${response.status}).`;
    let code = `HTTP_${response.status}`;
    try {
      const parsed = await response.json();
      if (typeof parsed?.message === 'string') message = parsed.message;
      if (typeof parsed?.error === 'string') code = parsed.error;
    } catch {
      // Leave the status-based defaults.
    }
    throw new ApiError(response.status, code, message);
  }

  if (!response.body) {
    throw new ApiError(0, 'STREAM_UNSUPPORTED', 'This browser cannot read streamed responses.');
  }

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  // Events arrive split across arbitrary chunk boundaries - a single `data:` line can be delivered
  // in two pieces - so an incomplete tail is carried over to the next read rather than parsed.
  let buffer = '';

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += value;

      // SSE separates events with a blank line. \r\n\r\n is tolerated for proxies that rewrite
      // line endings, which is rare but silent when it happens.
      let boundary = findBoundary(buffer);
      while (boundary) {
        const block = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary.length);

        const message = parseBlock(block);
        if (message) onMessage(message);

        boundary = findBoundary(buffer);
      }
    }

    // A server that closes without a trailing blank line still sent a complete last event.
    const trailing = parseBlock(buffer);
    if (trailing) onMessage(trailing);
  } catch (cause) {
    if (isAbort(cause, signal)) return;
    throw new ApiError(0, 'STREAM_INTERRUPTED', 'The connection dropped before the run finished.');
  } finally {
    // Releasing the lock lets the body be cancelled; without it an aborted stream can leak the
    // underlying connection until the tab is closed.
    reader.releaseLock();
    if (signal?.aborted) {
      void response.body.cancel().catch(() => {
        // Already closed by the abort. Nothing to report.
      });
    }
  }
}

/**
 * Locates the next event boundary in the buffer.
 *
 * @param buffer the accumulated, not-yet-parsed stream text
 * @returns the boundary's index and length, or `null` if no complete event is buffered yet
 */
function findBoundary(buffer: string): { index: number; length: number } | null {
  const lf = buffer.indexOf('\n\n');
  const crlf = buffer.indexOf('\r\n\r\n');

  if (lf === -1 && crlf === -1) return null;
  if (crlf !== -1 && (lf === -1 || crlf < lf)) return { index: crlf, length: 4 };
  return { index: lf, length: 2 };
}

/**
 * Parses one SSE block into an event name and its data.
 *
 * @param block the text of a single event, without its trailing blank line
 * @returns the parsed message, or `null` for a comment-only or empty block
 */
function parseBlock(block: string): SseMessage | null {
  const trimmed = block.trim();
  if (!trimmed) return null;

  let event = 'message';
  const dataLines: string[] = [];

  for (const rawLine of trimmed.split(/\r?\n/)) {
    // A line starting with ':' is a comment - Spring's SseEmitter sends these as keep-alives, and
    // treating one as data would push an empty event into a live view for no reason.
    if (rawLine.startsWith(':')) continue;

    const separator = rawLine.indexOf(':');
    const field = separator === -1 ? rawLine : rawLine.slice(0, separator);
    // Exactly one optional leading space after the colon is stripped, per the SSE spec - any
    // further whitespace is part of the value.
    const value =
      separator === -1 ? '' : rawLine.slice(separator + 1).replace(/^ /, '');

    if (field === 'event') event = value;
    else if (field === 'data') dataLines.push(value);
    // 'id' and 'retry' are ignored: this client does not reconnect, so neither has an effect here.
  }

  if (dataLines.length === 0) return null;
  return { event, data: dataLines.join('\n') };
}

/**
 * Distinguishes a caller's own cancellation from a real transport failure.
 *
 * @param cause the value the fetch or read rejected with
 * @param signal the abort signal the caller passed, if any
 * @returns `true` when this was an intentional abort
 */
function isAbort(cause: unknown, signal?: AbortSignal): boolean {
  if (signal?.aborted) return true;
  return cause instanceof DOMException && cause.name === 'AbortError';
}
