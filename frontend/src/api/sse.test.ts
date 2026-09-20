import { describe, expect, it, vi } from 'vitest';
import { streamSse, type SseMessage } from './sse';
import { ApiError } from './ApiError';

/**
 * Builds a `Response` that streams the given chunks as an event stream.
 *
 * The chunk boundaries are the interesting part: a real network splits an SSE stream at arbitrary
 * byte offsets, so the tests below deliberately cut events in half to prove the reader reassembles
 * them rather than relying on each chunk happening to contain whole events.
 *
 * @param chunks the exact strings to emit, in order
 * @returns a streaming response
 */
function streamingResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(encoder.encode(chunk)));
      controller.close();
    },
  });

  return new Response(body, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

/**
 * Runs the reader over a canned stream and collects what it produced.
 *
 * @param chunks the stream's chunks
 * @returns every message the reader emitted
 */
async function collect(chunks: string[]): Promise<SseMessage[]> {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(streamingResponse(chunks)));

  const messages: SseMessage[] = [];
  await streamSse('/api/test/events', { onMessage: (message) => messages.push(message) });
  return messages;
}

describe('streamSse', () => {
  it('parses events split across chunk boundaries', async () => {
    const messages = await collect([
      'event: step_started\ndata: {"step":"Fetch',
      'ing sources"}\n\nevent: step_prog',
      'ress\ndata: {"detail":"Crawling page 3 of ~20"}\n\n',
    ]);

    expect(messages).toEqual([
      { event: 'step_started', data: '{"step":"Fetching sources"}' },
      { event: 'step_progress', data: '{"detail":"Crawling page 3 of ~20"}' },
    ]);
  });

  it('ignores keep-alive comments', async () => {
    // Spring's SseEmitter sends these to hold the connection open. Treating one as data would push
    // an empty event into the live execution view for no reason.
    const messages = await collect([':keep-alive\n\n', 'event: run_completed\ndata: {}\n\n']);

    expect(messages).toHaveLength(1);
    expect(messages[0].event).toBe('run_completed');
  });

  it('joins multi-line data with newlines', async () => {
    // An LLM's streamed answer contains newlines, and SSE encodes each as its own `data:` line.
    const messages = await collect(['event: token\ndata: first line\ndata: second line\n\n']);

    expect(messages[0].data).toBe('first line\nsecond line');
  });

  it('tolerates CRLF line endings', async () => {
    const messages = await collect(['event: ping\r\ndata: ok\r\n\r\n']);
    expect(messages).toEqual([{ event: 'ping', data: 'ok' }]);
  });

  it("defaults the event name to 'message' when the server sends none", async () => {
    const messages = await collect(['data: bare\n\n']);
    expect(messages).toEqual([{ event: 'message', data: 'bare' }]);
  });

  it('emits a trailing event the server did not terminate with a blank line', async () => {
    const messages = await collect(['event: run_failed\ndata: {"reason":"no provider"}']);
    expect(messages).toHaveLength(1);
    expect(messages[0].event).toBe('run_failed');
  });

  it('strips exactly one leading space after the colon, per the SSE spec', async () => {
    const messages = await collect(['data:  two spaces\n\n']);
    // The first space is the separator; the second is part of the value.
    expect(messages[0].data).toBe(' two spaces');
  });

  it('raises the backend error when the stream cannot be opened', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            error: 'PROVIDER_UNAVAILABLE',
            message: 'No LLM provider is configured. Add a key in Account Settings.',
            timestamp: new Date().toISOString(),
            path: '/api/test/events',
          }),
          { status: 503, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );

    // A failure before the stream opens is an ordinary JSON error response, and has to surface as the
    // same ApiError any other endpoint would produce - otherwise the run view needs its own error
    // renderer.
    await expect(
      streamSse('/api/test/events', { onMessage: () => {} }),
    ).rejects.toMatchObject({
      status: 503,
      code: 'PROVIDER_UNAVAILABLE',
      message: 'No LLM provider is configured. Add a key in Account Settings.',
    });
  });

  it('resolves rather than throwing when the caller aborts', async () => {
    const controller = new AbortController();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(() => {
        controller.abort();
        return Promise.reject(new DOMException('Aborted', 'AbortError'));
      }),
    );

    // A component unmounting mid-run aborts its own stream. That is not a failure, and making callers
    // catch their own cancellation is a reliable source of spurious error toasts.
    await expect(
      streamSse('/api/test/events', { onMessage: () => {}, signal: controller.signal }),
    ).resolves.toBeUndefined();
  });

  it('reports a network failure as an ApiError, not a raw TypeError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));

    await expect(streamSse('/api/test/events', { onMessage: () => {} })).rejects.toBeInstanceOf(
      ApiError,
    );
  });

  it('sends the bearer token as a header', async () => {
    localStorage.setItem(
      'spi.session',
      JSON.stringify({
        token: 'stream-token',
        expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        user: { id: 'u1', username: 'admin', role: 'ADMIN' },
      }),
    );

    const fetchMock = vi.fn().mockResolvedValue(streamingResponse(['data: ok\n\n']));
    vi.stubGlobal('fetch', fetchMock);

    await streamSse('/api/test/events', { onMessage: () => {} });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = new Headers(init.headers);
    // The whole reason this module exists instead of EventSource: the backend reads the token from
    // the header and deliberately refuses an `?access_token=` query parameter.
    expect(headers.get('Authorization')).toBe('Bearer stream-token');
    expect(headers.get('Accept')).toBe('text/event-stream');
  });
});
