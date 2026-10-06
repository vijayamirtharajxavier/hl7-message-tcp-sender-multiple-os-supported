package io.hl7sender.core.batch;

import java.util.List;

/**
 * Messages found in a file or pasted text.
 *
 * @param messages   each message in wire format (CR-separated, ending in CR), in file order
 * @param warnings   problems that did not stop splitting, such as batch trailer counts that do not match
 * @param batches    number of BHS batch headers seen
 * @param fileHeader true if the input had an FHS file header
 */
public record SplitResult(List<String> messages, List<String> warnings, int batches, boolean fileHeader) {

    public SplitResult {
        messages = List.copyOf(messages);
        warnings = List.copyOf(warnings);
    }

    public boolean isBatch() {
        return fileHeader || batches > 0;
    }
}
