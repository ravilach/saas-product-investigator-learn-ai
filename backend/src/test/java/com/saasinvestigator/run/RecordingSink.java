package com.saasinvestigator.run;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link RunEventSink} that keeps what it was sent, and can be told to start failing.
 *
 * <p>This is the reason {@code RunEventSink} exists as an interface at all. An {@code SseEmitter} cannot be driven or
 * inspected outside a servlet async context, so the ordering guarantees that matter most in {@link RunSession} - that
 * a late subscriber sees every earlier event exactly once, in order - would be untestable if the session wrote to an
 * emitter directly.
 */
final class RecordingSink implements RunEventSink {

    private final List<RunEvent> received = new ArrayList<>();
    private int keepAlives;
    private boolean broken;

    @Override
    public void send(RunEvent event) throws IOException {
        if (broken) {
            throw new IOException("simulated disconnect");
        }
        received.add(event);
    }

    @Override
    public void keepAlive() throws IOException {
        if (broken) {
            throw new IOException("simulated disconnect");
        }
        keepAlives++;
    }

    /** Makes every subsequent write fail, standing in for a closed browser tab. */
    void disconnect() {
        broken = true;
    }

    List<RunEvent> received() {
        return List.copyOf(received);
    }

    /** @return the {@code type} of each event received, which is what most ordering assertions are about */
    List<RunEventType> types() {
        return received.stream().map(RunEvent::type).toList();
    }

    /** @return the {@code detail} of each event received, in order */
    List<String> details() {
        return received.stream().map(RunEvent::detail).toList();
    }

    int keepAlives() {
        return keepAlives;
    }
}
