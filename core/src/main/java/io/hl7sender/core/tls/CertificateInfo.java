package io.hl7sender.core.tls;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Summary of one X.509 certificate.
 *
 * @param source    where it came from, e.g. {@code trust store}, {@code client certificate}, {@code server}
 * @param alias     key store alias, if any
 * @param subject   subject distinguished name
 * @param issuer    issuer distinguished name
 * @param notBefore start of validity
 * @param notAfter  end of validity
 */
public record CertificateInfo(String source, String alias, String subject, String issuer, Instant notBefore,
                              Instant notAfter) {

    /** Certificates expiring within this many days produce a warning. */
    public static final int WARN_DAYS = 30;

    public enum Status { VALID, EXPIRING_SOON, EXPIRED, NOT_YET_VALID }

    public Status status(Clock clock) {
        Instant now = clock.instant();
        if (now.isBefore(notBefore)) {
            return Status.NOT_YET_VALID;
        }
        if (!now.isBefore(notAfter)) {
            return Status.EXPIRED;
        }
        return Duration.between(now, notAfter).toDays() < WARN_DAYS ? Status.EXPIRING_SOON : Status.VALID;
    }

    public long daysLeft(Clock clock) {
        return Duration.between(clock.instant(), notAfter).toDays();
    }

    /** A warning sentence, or empty if the certificate is fine. */
    public String warning(Clock clock) {
        return switch (status(clock)) {
            case VALID -> "";
            case EXPIRING_SOON -> source + " certificate '" + subject + "' expires in " + daysLeft(clock) + " day(s) ("
                    + notAfter + ")";
            case EXPIRED -> source + " certificate '" + subject + "' expired on " + notAfter;
            case NOT_YET_VALID -> source + " certificate '" + subject + "' is not valid until " + notBefore;
        };
    }
}
