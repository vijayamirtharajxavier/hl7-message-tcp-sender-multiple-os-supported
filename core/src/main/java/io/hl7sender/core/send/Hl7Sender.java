package io.hl7sender.core.send;

import io.hl7sender.core.ack.AckParseException;
import io.hl7sender.core.ack.AckParser;
import io.hl7sender.core.ack.ParsedAck;
import io.hl7sender.core.hl7.ControlIdGenerator;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.hl7.Hl7Timestamps;
import io.hl7sender.core.hl7.MessageHeader;
import io.hl7sender.core.hl7.MshEditor;
import io.hl7sender.core.hl7.validation.ConformanceProfile;
import io.hl7sender.core.hl7.validation.MessageValidator;
import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.hl7.validation.ValidationReport;
import io.hl7sender.core.mllp.MllpClient;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.mllp.MllpProtocolException;
import io.hl7sender.core.tls.TlsOptions;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends a single HL7 message over MLLP and interprets the acknowledgment.
 *
 * <p>A send connects (or reuses a caller-owned connection), writes the message, waits for the ACK
 * (unless {@link AckMode#NO_ACK}) and matches it to the message by MSA-2 = MSH-10. This method never throws
 * for network or protocol problems. They are reported as a {@link SendOutcome} instead.
 *
 * <p>Logs contain only metadata (type, control ID, outcome, timings), never message content,
 * because messages may contain PHI.
 *
 * <p>Thread-safe.
 */
public final class Hl7Sender {

    private static final Logger LOG = LoggerFactory.getLogger(Hl7Sender.class);

    private final MessageValidator validator;
    private final ControlIdGenerator controlIds;
    private final Clock clock;
    private final boolean logEachSend;

    public Hl7Sender() {
        this(new MessageValidator(), new ControlIdGenerator(), Clock.systemDefaultZone());
    }

    public Hl7Sender(MessageValidator validator, ControlIdGenerator controlIds, Clock clock) {
        this(validator, controlIds, clock, true);
    }

    private Hl7Sender(MessageValidator validator, ControlIdGenerator controlIds, Clock clock, boolean logEachSend) {
        this.validator = validator;
        this.controlIds = controlIds;
        this.clock = clock;
        this.logEachSend = logEachSend;
    }

    /**
     * A sender that logs each result at DEBUG instead of INFO/WARN. For load tests, which send thousands of
     * messages a second and report the results in aggregate.
     */
    public Hl7Sender quiet() {
        return new Hl7Sender(validator, controlIds, clock, false);
    }

    /** Normalizes, stamps and validates {@code text} without sending it (standard validation). */
    public PreparedMessage prepare(String text, SendOptions options) {
        return prepare(text, options, ValidationLevel.STANDARD, null);
    }

    /**
     * Normalizes, stamps and validates {@code text} at {@code level}, optionally against a conformance
     * profile.
     */
    public PreparedMessage prepare(String text, SendOptions options, ValidationLevel level,
                                   ConformanceProfile profile) {
        String original = Hl7Text.normalize(text);
        String wire = original;
        ValidationReport structure = validator.validate(wire, ValidationLevel.LENIENT, null);
        if (structure.header().isEmpty()) {
            return new PreparedMessage(wire, "", "", structure);
        }
        if (options.generateTimestamp()) {
            wire = MshEditor.setField(wire, 7, Hl7Timestamps.now(clock));
        }
        if (options.generateControlId()) {
            wire = MshEditor.setField(wire, 10, controlIds.next());
        }
        ValidationReport report = level == ValidationLevel.LENIENT && profile == null && wire.equals(original)
                ? structure : validator.validate(wire, level, profile);
        MessageHeader h = report.header().orElseThrow();
        return new PreparedMessage(wire, h.controlId(), h.messageType(), report);
    }

    /** Prepares and sends {@code text} to {@code destination}. */
    public SendResult send(MllpClientConfig destination, String text, SendOptions options) {
        return send(destination, prepare(text, options), options.ackMode());
    }

    /** Prepares and sends {@code text} over TLS (or plain TCP when {@code tls} is null). */
    public SendResult send(MllpClientConfig destination, TlsOptions tls, String text, SendOptions options) {
        try (MllpClient client = new MllpClient(destination, tls)) {
            return send(client, prepare(text, options), options.ackMode());
        }
    }

    /** Sends an already-prepared message on a new connection, which is closed afterwards. */
    public SendResult send(MllpClientConfig destination, PreparedMessage message, AckMode ackMode) {
        try (MllpClient client = new MllpClient(destination)) {
            return send(client, message, ackMode);
        }
    }

    /**
     * Sends on {@code client}, connecting first if it is not connected. The caller owns the
     * connection. If {@link SendOutcome#connectionReusable()} is false for the result, the caller
     * must close it before sending again.
     */
    public SendResult send(MllpClient client, PreparedMessage message, AckMode ackMode) {
        MllpClientConfig destination = client.config();
        Attempt attempt = new Attempt(destination, message, Instant.now(clock), logEachSend);
        if (message.validation().hasErrors()) {
            return attempt.finish(SendOutcome.VALIDATION_FAILED,
                    message.validation().errors().get(0).message());
        }

        if (!client.isConnected()) {
            long connectStart = System.nanoTime();
            try {
                client.connect();
            } catch (IOException e) {
                return attempt.finish(SendOutcome.CONNECTION_FAILED, describe(e));
            }
            attempt.connectTime = Duration.ofNanos(System.nanoTime() - connectStart);
        }

        try {
            client.send(message.wire());
        } catch (IOException e) {
            return attempt.finish(SendOutcome.SEND_FAILED, describe(e));
        }
        if (ackMode == AckMode.NO_ACK) {
            return attempt.finish(SendOutcome.SENT_NO_ACK, "");
        }

        long sentAt = System.nanoTime();
        String response;
        try {
            response = client.receive();
        } catch (SocketTimeoutException e) {
            attempt.roundTrip = Duration.ofNanos(System.nanoTime() - sentAt);
            return attempt.finish(SendOutcome.ACK_TIMEOUT,
                    "No ACK within " + destination.responseTimeoutMs() + " ms");
        } catch (EOFException e) {
            return attempt.finish(SendOutcome.CONNECTION_CLOSED, e.getMessage());
        } catch (MllpProtocolException e) {
            return attempt.finish(SendOutcome.PROTOCOL_ERROR, e.getMessage());
        } catch (IOException e) {
            return attempt.finish(SendOutcome.CONNECTION_CLOSED, describe(e));
        }
        attempt.roundTrip = Duration.ofNanos(System.nanoTime() - sentAt);
        attempt.rawResponse = response;
        return interpret(attempt, response);
    }

    private static SendResult interpret(Attempt attempt, String response) {
        ParsedAck ack;
        try {
            ack = AckParser.parse(response);
        } catch (AckParseException e) {
            return attempt.finish(SendOutcome.INVALID_ACK, e.getMessage());
        }
        attempt.ack = ack;
        String expected = attempt.message.controlId();
        if (!expected.isEmpty() && !expected.equals(ack.controlId())) {
            return attempt.finish(SendOutcome.CONTROL_ID_MISMATCH,
                    "Expected MSA-2 '" + expected + "' but received '" + ack.controlId() + "'");
        }
        String detail = ack.text();
        if (detail.isEmpty() && !ack.errors().isEmpty()) {
            detail = ack.errors().get(0).describe();
        }
        return attempt.finish(SendOutcome.of(ack.code()), detail);
    }

    private static String describe(IOException e) {
        if (e instanceof SSLException) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String detail = root.getMessage() == null ? e.getMessage() : root.getMessage();
            return "TLS handshake failed: " + detail;
        }
        String type = e.getClass().getSimpleName();
        return e.getMessage() == null ? type : type + ": " + e.getMessage();
    }

    /** Mutable state collected during one attempt. */
    private static final class Attempt {
        private final MllpClientConfig destination;
        private final PreparedMessage message;
        private final Instant startedAt;
        private Duration connectTime = Duration.ZERO;
        private Duration roundTrip = Duration.ZERO;
        private ParsedAck ack;
        private String rawResponse;
        private final boolean log;

        Attempt(MllpClientConfig destination, PreparedMessage message, Instant startedAt, boolean log) {
            this.destination = destination;
            this.message = message;
            this.startedAt = startedAt;
            this.log = log;
        }

        SendResult finish(SendOutcome outcome, String detail) {
            SendResult result = new SendResult(
                    outcome,
                    destination.address(),
                    message.controlId(),
                    message.messageType(),
                    message.wire(),
                    message.validation(),
                    Optional.ofNullable(ack),
                    Optional.ofNullable(rawResponse),
                    detail == null ? "" : detail,
                    startedAt,
                    connectTime,
                    roundTrip);
            if (!log) {
                LOG.debug("{}", result.summary());
                return result;
            }
            switch (outcome.severity()) {
                case SUCCESS -> LOG.info("{}", result.summary());
                case WARNING, FAILURE -> LOG.warn("{}", result.summary());
                default -> throw new IllegalStateException();
            }
            return result;
        }
    }
}
