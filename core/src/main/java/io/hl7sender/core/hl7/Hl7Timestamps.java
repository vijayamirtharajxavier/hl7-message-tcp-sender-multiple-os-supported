package io.hl7sender.core.hl7;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** HL7 DTM/TS formatting. */
public final class Hl7Timestamps {

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private Hl7Timestamps() {
    }

    /**
     * The current local time as {@code yyyyMMddHHmmss}. The time-zone offset is left out on purpose,
     * because several receiving systems reject it in MSH-7.
     */
    public static String now(Clock clock) {
        return SECONDS.format(LocalDateTime.now(clock));
    }

    public static String now() {
        return now(Clock.systemDefaultZone());
    }
}
