package io.hl7sender.cli;

import io.hl7sender.core.queue.QueuedMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Waits for queued messages to be delivered, delivering from this process if no app or service is running. */
final class Delivery {

    private static final long POLL_MS = 100;

    private Delivery() {
    }

    /**
     * Waits until every message is delivered or dead-lettered, or {@code timeoutSeconds} pass. Returns the latest
     * state of each message.
     */
    static List<QueuedMessage> await(Workspace ws, List<Long> ids, int timeoutSeconds) throws InterruptedException {
        if (ws.owner() && !ws.engine().isStarted()) {
            ws.startDelivery();
        }
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (true) {
            List<QueuedMessage> now = load(ws, ids);
            boolean done = now.stream().allMatch(m -> ExitCodes.terminal(m.status()));
            if (done || System.currentTimeMillis() >= deadline) {
                return now;
            }
            Thread.sleep(POLL_MS);
        }
    }

    static List<QueuedMessage> load(Workspace ws, List<Long> ids) {
        List<QueuedMessage> out = new ArrayList<>();
        for (long id : ids) {
            Optional<QueuedMessage> m = ws.store().message(id);
            m.ifPresent(out::add);
        }
        return out;
    }
}
