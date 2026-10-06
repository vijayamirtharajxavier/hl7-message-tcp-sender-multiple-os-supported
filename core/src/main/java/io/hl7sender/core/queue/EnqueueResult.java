package io.hl7sender.core.queue;

import io.hl7sender.core.hl7.validation.ValidationReport;
import java.util.List;
import java.util.Optional;

/**
 * Result of adding a message to a queue.
 *
 * @param message    the stored message, empty if it was rejected (validation errors)
 * @param validation validation report for the prepared message
 * @param warnings   non-blocking concerns, such as a duplicate control ID
 */
public record EnqueueResult(Optional<QueuedMessage> message, ValidationReport validation, List<String> warnings) {

    public EnqueueResult {
        warnings = List.copyOf(warnings);
    }

    public boolean accepted() {
        return message.isPresent();
    }
}
