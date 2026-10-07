package io.hl7sender.core.ack;

import java.util.Locale;
import java.util.Optional;

/**
 * MSA-1 acknowledgment codes (HL7 table 0008).
 *
 * <p>{@code AA}/{@code AE}/{@code AR} are original-mode (application) acknowledgments.
 * {@code CA}/{@code CE}/{@code CR} are enhanced-mode commit acknowledgments.
 */
public enum AckCode {
    AA(Category.ACCEPT, false, "Application Accept"),
    AE(Category.ERROR, false, "Application Error"),
    AR(Category.REJECT, false, "Application Reject"),
    CA(Category.ACCEPT, true, "Commit Accept"),
    CE(Category.ERROR, true, "Commit Error"),
    CR(Category.REJECT, true, "Commit Reject");

    /**
     * The accept, error or reject family a code belongs to, used to pick the matching code in original or enhanced
     * mode. Codes in one family do not mean the same thing to a sender: see
     * {@link io.hl7sender.core.send.SendOutcome#of(AckCode)}.
     */
    public enum Category {
        /** AA or CA. */
        ACCEPT,
        /** AE or CE. */
        ERROR,
        /** AR or CR. */
        REJECT
    }

    private final Category category;
    private final boolean commit;
    private final String description;

    AckCode(Category category, boolean commit, String description) {
        this.category = category;
        this.commit = commit;
        this.description = description;
    }

    public Category category() {
        return category;
    }

    /** True for enhanced-mode commit acknowledgments (CA/CE/CR). */
    public boolean isCommit() {
        return commit;
    }

    public String description() {
        return description;
    }

    /** Looks up a code case-insensitively, ignoring surrounding whitespace. */
    public static Optional<AckCode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The code for {@code category} in original or enhanced mode. */
    public static AckCode of(Category category, boolean commit) {
        for (AckCode code : values()) {
            if (code.category == category && code.commit == commit) {
                return code;
            }
        }
        throw new IllegalStateException("No code for " + category);
    }
}
