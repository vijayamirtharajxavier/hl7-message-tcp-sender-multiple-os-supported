package io.hl7sender.core.queue;

/**
 * Receives progress from a bulk import, and can stop it.
 */
public interface BulkProgress {

    BulkProgress NONE = new BulkProgress() {
    };

    /** Called after each chunk is stored. */
    default void onProgress(int processed, int total, int accepted, int rejected) {
    }

    /** Checked between items. Return true to stop the import. */
    default boolean isCancelled() {
        return false;
    }
}
