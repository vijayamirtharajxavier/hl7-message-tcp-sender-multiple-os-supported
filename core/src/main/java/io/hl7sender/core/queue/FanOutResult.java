package io.hl7sender.core.queue;

import java.util.Map;

/**
 * Result of sending one message to several destinations.
 *
 * @param batchId shared by every copy, to find them together in history
 * @param results per destination ID, in the order requested
 */
public record FanOutResult(String batchId, Map<Long, EnqueueResult> results) {

    public FanOutResult {
        results = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(results));
    }

    public long accepted() {
        return results.values().stream().filter(EnqueueResult::accepted).count();
    }
}
