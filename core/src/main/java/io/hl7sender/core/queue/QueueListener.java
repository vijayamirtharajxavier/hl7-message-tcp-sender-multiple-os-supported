package io.hl7sender.core.queue;

/**
 * Receives delivery engine notifications. Callbacks run on engine threads and must return quickly.
 * UI code should hand them to its own thread.
 */
public interface QueueListener {

    /** A message of this destination changed state, or messages were added or removed. */
    default void onQueueChanged(long destinationId) {
    }

    /** The destination's worker changed what it is doing. */
    default void onStateChanged(DestinationState state) {
    }

    /** Destinations were added, edited, paused/resumed or deleted. */
    default void onDestinationsChanged() {
    }
}
