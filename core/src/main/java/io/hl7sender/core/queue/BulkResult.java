package io.hl7sender.core.queue;

import java.util.List;

/**
 * Outcome of a bulk import.
 *
 * @param batchId   ID shared by every message stored from this import; use it to search history
 * @param total     items offered
 * @param accepted  messages stored in the queue
 * @param rejected  items not stored, with reasons
 * @param warnings  non-blocking notes (duplicate control IDs, ...)
 * @param cancelled true if the import was stopped early; items processed before that were stored
 */
public record BulkResult(String batchId, int total, int accepted, List<Rejection> rejected, List<String> warnings,
                         boolean cancelled) {

    public BulkResult {
        rejected = List.copyOf(rejected);
        warnings = List.copyOf(warnings);
    }

    /**
     * An item that was not queued.
     *
     * @param index  0-based position in the import
     * @param source where it came from
     * @param reason first blocking validation error
     */
    public record Rejection(int index, String source, String reason) {
    }
}
