package com.saasinvestigator.run;

import java.io.IOException;

/**
 * Where a {@link RunSession} delivers events - in production, one browser's SSE connection.
 *
 * <p>An interface rather than the {@code SseEmitter} itself for one reason worth the indirection:
 * {@link RunSession}'s interesting behaviour is its buffer-and-replay logic, and an {@code SseEmitter} cannot be
 * driven or inspected outside a servlet async context. With this seam, the ordering guarantees that matter - that a
 * subscriber arriving mid-run sees every earlier event before any later one, exactly once - are testable with a
 * list.
 *
 * <p>Both methods are allowed to throw {@link IOException}, which is not an error condition to handle but the
 * normal way a client goes away: someone closed the tab while a four-minute run was in progress. A session that
 * sees one simply drops that subscriber and carries on, because the run's value is the persisted report, not the
 * stream.
 */
public interface RunEventSink {

    /**
     * Delivers one event, in order.
     *
     * @param event the event to send
     * @throws IOException if the client is gone; the session will unsubscribe this sink
     */
    void send(RunEvent event) throws IOException;

    /**
     * Sends whatever this transport uses to prove it is still alive, carrying no event.
     *
     * <p>Needed because the {@code Comparing} step is one long provider call. Between its
     * {@code step_started} and its {@code step_completed} there can be minutes of genuine silence, which an
     * intermediate proxy is entitled to read as an abandoned connection and close - so the user would watch a run
     * "stop" at the exact moment it started doing the actual work. The SSE implementation sends a comment line,
     * which {@code EventSource} ignores without dispatching an event.
     *
     * @throws IOException if the client is gone
     */
    void keepAlive() throws IOException;
}
