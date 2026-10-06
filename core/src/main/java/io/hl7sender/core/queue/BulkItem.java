package io.hl7sender.core.queue;

/**
 * One message to add in a bulk import.
 *
 * @param text   message text (any line endings); may contain {@code ${...}} template variables
 * @param source where it came from, e.g. {@code batch.hl7#12}
 */
public record BulkItem(String text, String source) {
}
