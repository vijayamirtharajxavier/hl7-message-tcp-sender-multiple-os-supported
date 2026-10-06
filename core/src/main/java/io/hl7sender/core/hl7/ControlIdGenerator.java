package io.hl7sender.core.hl7;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates unique MSH-10 message control IDs: a millisecond timestamp followed by a 3-digit
 * sequence ({@code yyyyMMddHHmmssSSS} + {@code NNN}), 20 characters in total. That fits the 20-character
 * limit of HL7 v2.3–v2.4 and sorts by creation time.
 */
public final class ControlIdGenerator {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final Clock clock;
    private final AtomicInteger sequence = new AtomicInteger();

    public ControlIdGenerator() {
        this(Clock.systemDefaultZone());
    }

    public ControlIdGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        int seq = Math.floorMod(sequence.getAndIncrement(), 1000);
        return FORMAT.format(LocalDateTime.now(clock)) + String.format("%03d", seq);
    }
}
