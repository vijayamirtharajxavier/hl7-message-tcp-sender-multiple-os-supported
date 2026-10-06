package io.hl7sender.core.hl7.validation;

import java.nio.file.Path;
import java.util.Optional;

/**
 * How a destination validates messages.
 *
 * @param level   strictness
 * @param profile conformance profile file, if any
 */
public record ValidationPolicy(ValidationLevel level, Optional<Path> profile) {

    public static final ValidationPolicy STANDARD = new ValidationPolicy(ValidationLevel.STANDARD, Optional.empty());
}
