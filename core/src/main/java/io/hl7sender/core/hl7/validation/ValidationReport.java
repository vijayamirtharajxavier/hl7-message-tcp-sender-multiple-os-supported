package io.hl7sender.core.hl7.validation;

import io.hl7sender.core.hl7.MessageHeader;
import java.util.List;
import java.util.Optional;

/**
 * Result of validating a message.
 *
 * @param header       MSH summary, present when the MSH segment could be read
 * @param segmentCount number of segments found
 * @param issues       all findings, errors first
 */
public record ValidationReport(Optional<MessageHeader> header, int segmentCount, List<ValidationIssue> issues) {

    public ValidationReport {
        issues = List.copyOf(issues);
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
    }

    public List<ValidationIssue> errors() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.ERROR).toList();
    }

    public List<ValidationIssue> warnings() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.WARNING).toList();
    }

    /** One-line summary, e.g. {@code ADT^A01 v2.5.1, 6 segments, 0 errors, 1 warning}. */
    public String summary() {
        String type = header.map(h -> h.messageType() + (h.version().isEmpty() ? "" : " v" + h.version()))
                .orElse("Unreadable message");
        int e = errors().size();
        int w = warnings().size();
        return type + ", " + segmentCount + " segment" + (segmentCount == 1 ? "" : "s") + ", "
                + e + " error" + (e == 1 ? "" : "s") + ", " + w + " warning" + (w == 1 ? "" : "s");
    }
}
