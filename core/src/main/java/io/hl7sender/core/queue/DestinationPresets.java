package io.hl7sender.core.queue;

import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.tls.TlsSettings;
import java.util.List;

/** Starting points for common receivers, with notes on how the receiver side must be configured. */
public final class DestinationPresets {

    /**
     * A named template for a new destination.
     *
     * @param name        menu label
     * @param description one-line summary
     * @param config      unsaved destination with recommended settings and notes
     */
    public record Preset(String name, String description, DestinationConfig config) {

        @Override
        public String toString() {
            return name;
        }
    }

    private DestinationPresets() {
    }

    public static List<Preset> all() {
        return List.of(
                new Preset("Mirth Connect / NextGen Connect", "TCP Listener with MLLP, original-mode ACKs",
                        DestinationConfig.of("Mirth Connect", "localhost", 6661).withNotes(String.join("\n",
                                "Mirth: Source connector = TCP Listener, Transmission Mode = MLLP.",
                                "Response: 'Auto-generate (After source transformer)' or a destination's response.",
                                "Check the listener's port and whether it expects MLLP over TLS (Mirth Connect "
                                        + "TLS requires the SSL Manager extension)."))),
                new Preset("Apache NiFi (ListenTCP, no ACK)", "Fire-and-forget; delivery cannot be confirmed",
                        DestinationConfig.of("Apache NiFi", "localhost", 7001).withAckMode(AckMode.NO_ACK)
                                .withConnectionMode(ConnectionMode.PERSISTENT).withNotes(String.join("\n",
                                        "NiFi's ListenTCP does not send HL7 ACKs, so messages are marked "
                                                + "SENT_UNCONFIRMED.",
                                        "For confirmed delivery, add a flow that returns an MLLP ACK and switch "
                                                + "this destination to 'Wait for ACK'.",
                                        "ListenTCP must use the MLLP delimiters (end of message 0x1C 0x0D)."))),
                new Preset("EHR interface (production, TLS)", "MLLP over TLS, strict validation, never give up",
                        DestinationConfig.of("EHR production", "ehr.example.org", 6661)
                                .withTls(new TlsSettings(true, "", "", true, TlsSettings.DEFAULT_PROTOCOLS))
                                .withValidation(ValidationLevel.STRICT, "")
                                .withRetry(new RetryPolicy(0, 5_000, 600_000, 0.2))
                                .withCircuitBreaker(new CircuitBreakerSettings(5, 120_000))
                                .withAckPolicy(AckPolicy.DEFAULT.with(SendOutcome.APPLICATION_REJECT,
                                        AckPolicy.Action.RETRY))
                                .withMaxPerSecond(20)
                                .withNotes(String.join("\n",
                                        "Import the EHR's CA or server certificate as the trust store; add a client "
                                                + "certificate if the interface requires mutual TLS.",
                                        "Confirm the ACK mode (MSH-15/16), expected ACK timeout and any message "
                                                + "rate limit with the EHR interface team.",
                                        "Attach the interface specification's conformance profile, if one exists."))),
                new Preset("Local test listener", "The built-in mock receiver on this computer",
                        DestinationConfig.of("Local test listener", "127.0.0.1", 2575)
                                .withRetry(new RetryPolicy(5, 1_000, 10_000, 0.2))
                                .withNotes("Start it on the Test Listener tab.")));
    }
}
