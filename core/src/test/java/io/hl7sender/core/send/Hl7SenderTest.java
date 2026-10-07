package io.hl7sender.core.send;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.ack.AckCode;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.mllp.MllpClientConfig;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** End-to-end: {@link Hl7Sender} against the built-in {@link TestListener} in every response mode. */
@Timeout(30)
class Hl7SenderTest {

    private static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20200101000000||ADT^A01^ADT_A01|ORIG1|P|2.5.1\n"
            + "EVN|A01|20200101000000\n"
            + "PID|1||MRN1^^^HOSP^MR||DOE^JANE||19800101|F\n"
            + "PV1|1|I";

    private final Hl7Sender sender = new Hl7Sender();
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private TestListener listener;
    private MllpClientConfig destination;

    @BeforeEach
    void startListener() throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
        destination = MllpClientConfig.of("127.0.0.1", listener.port()).withTimeouts(2000, 1500);
    }

    @AfterEach
    void stopListener() {
        listener.close();
    }

    private SendResult sendWith(ResponseMode mode) {
        listener.updateSettings(listener.settings().withMode(mode));
        return sender.send(destination, MESSAGE, SendOptions.DEFAULTS);
    }

    @Test
    void acceptedMessageIsCorrelatedAndStamped() {
        SendResult r = sendWith(ResponseMode.ACCEPT);
        assertThat(r.outcome()).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(r.ack()).get().satisfies(a -> {
            assertThat(a.code()).isEqualTo(AckCode.AA);
            assertThat(a.controlId()).isEqualTo(r.controlId());
        });
        assertThat(r.controlId()).hasSize(20).isNotEqualTo("ORIG1");
        assertThat(ParsedMessage.parse(r.sentMessage()).header().timestamp()).isNotEqualTo("20200101000000");
        assertThat(r.sentMessage()).doesNotContain("\n").endsWith("\r");
        assertThat(r.messageType()).isEqualTo("ADT^A01");
        assertThat(r.destination()).isEqualTo("127.0.0.1:" + listener.port());
        assertThat(r.summary()).startsWith("ACCEPTED (AA): ADT^A01 [");

        assertThat(received).singleElement().satisfies(m -> {
            assertThat(m.controlId()).isEqualTo(r.controlId());
            assertThat(m.responseCode()).isEqualTo("AA");
            assertThat(m.payload()).isEqualTo(r.sentMessage());
        });
    }

    @Test
    void keepsOriginalHeaderWhenAsked() {
        SendResult r = sender.send(destination, MESSAGE, SendOptions.AS_IS);
        assertThat(r.outcome()).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(r.controlId()).isEqualTo("ORIG1");
        assertThat(r.sentMessage()).contains("|20200101000000||");
    }

    @Test
    void applicationErrorIsNotRetryable() {
        SendResult r = sendWith(ResponseMode.ERROR);
        assertThat(r.outcome()).isEqualTo(SendOutcome.APPLICATION_ERROR);
        assertThat(r.outcome().retryable()).isFalse();
        assertThat(r.ack()).get().satisfies(a -> assertThat(a.errors()).isNotEmpty());
        assertThat(r.detail()).contains("Application internal error");
    }

    @Test
    void rejectIsRetryable() {
        SendResult r = sendWith(ResponseMode.REJECT);
        assertThat(r.outcome()).isEqualTo(SendOutcome.APPLICATION_REJECT);
        assertThat(r.outcome().retryable()).isTrue();
    }

    @Test
    void enhancedModeCommitCodes() {
        listener.updateSettings(new ListenerSettings(ResponseMode.ACCEPT, 0, true, "", destination.charset(),
                destination.maxFrameBytes()));
        SendResult r = sender.send(destination, MESSAGE, SendOptions.DEFAULTS);
        assertThat(r.outcome()).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(r.ack().orElseThrow().code()).isEqualTo(AckCode.CA);
    }

    @Test
    void commitErrorAndRejectHaveTheirOwnOutcomes() {
        listener.updateSettings(listener.settings().withCommitCodes(true).withMode(ResponseMode.ERROR));
        SendResult ce = sender.send(destination, MESSAGE, SendOptions.DEFAULTS);
        assertThat(ce.ack().orElseThrow().code()).isEqualTo(AckCode.CE);
        assertThat(ce.outcome()).isEqualTo(SendOutcome.COMMIT_ERROR);
        // CE is "any other reason", such as a sequence number error, which may clear.
        assertThat(ce.outcome().retryable()).isTrue();

        listener.updateSettings(listener.settings().withMode(ResponseMode.REJECT));
        SendResult cr = sender.send(destination, MESSAGE, SendOptions.DEFAULTS);
        assertThat(cr.ack().orElseThrow().code()).isEqualTo(AckCode.CR);
        assertThat(cr.outcome()).isEqualTo(SendOutcome.COMMIT_REJECT);
        // CR rejects MSH-9, MSH-11 or MSH-12, which resending the same message cannot fix.
        assertThat(cr.outcome().retryable()).isFalse();
    }

    @Test
    void everyAckCodeHasAnOutcome() {
        assertThat(SendOutcome.of(AckCode.AA)).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(SendOutcome.of(AckCode.CA)).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(SendOutcome.of(AckCode.AE)).isEqualTo(SendOutcome.APPLICATION_ERROR);
        assertThat(SendOutcome.of(AckCode.AR)).isEqualTo(SendOutcome.APPLICATION_REJECT);
        assertThat(SendOutcome.of(AckCode.CE)).isEqualTo(SendOutcome.COMMIT_ERROR);
        assertThat(SendOutcome.of(AckCode.CR)).isEqualTo(SendOutcome.COMMIT_REJECT);
    }

    @Test
    void mismatchedControlIdIsDetected() {
        SendResult r = sendWith(ResponseMode.WRONG_CONTROL_ID);
        assertThat(r.outcome()).isEqualTo(SendOutcome.CONTROL_ID_MISMATCH);
        assertThat(r.detail()).contains("WRONG-" + r.controlId());
    }

    @Test
    void malformedResponseIsInvalidAck() {
        SendResult r = sendWith(ResponseMode.MALFORMED);
        assertThat(r.outcome()).isEqualTo(SendOutcome.INVALID_ACK);
        assertThat(r.rawResponse()).get().asString().contains("NOT AN HL7");
    }

    @Test
    void silentReceiverTimesOut() {
        SendResult r = sendWith(ResponseMode.NO_RESPONSE);
        assertThat(r.outcome()).isEqualTo(SendOutcome.ACK_TIMEOUT);
        assertThat(r.roundTrip().toMillis()).isGreaterThanOrEqualTo(1400);
        assertThat(received).hasSize(1);
    }

    @Test
    void delayShorterThanTimeoutStillSucceeds() {
        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT).withDelayMs(300));
        SendResult r = sender.send(destination, MESSAGE, SendOptions.DEFAULTS);
        assertThat(r.outcome()).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(r.roundTrip().toMillis()).isGreaterThanOrEqualTo(290);
    }

    @Test
    void delayLongerThanTimeoutTimesOut() {
        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT).withDelayMs(2500));
        assertThat(sender.send(destination, MESSAGE, SendOptions.DEFAULTS).outcome())
                .isEqualTo(SendOutcome.ACK_TIMEOUT);
    }

    @Test
    void droppedConnectionIsReported() {
        assertThat(sendWith(ResponseMode.CLOSE_CONNECTION).outcome()).isEqualTo(SendOutcome.CONNECTION_CLOSED);
    }

    @Test
    void noAckModeDoesNotWait() {
        listener.updateSettings(listener.settings().withMode(ResponseMode.NO_RESPONSE));
        SendResult r = sender.send(destination, MESSAGE, new SendOptions(true, true, AckMode.NO_ACK));
        assertThat(r.outcome()).isEqualTo(SendOutcome.SENT_NO_ACK);
        assertThat(r.ack()).isEmpty();
    }

    @Test
    void refusedConnectionIsReported() throws IOException {
        int freePort;
        try (ServerSocket s = new ServerSocket(0)) {
            freePort = s.getLocalPort();
        }
        SendResult r = sender.send(MllpClientConfig.of("127.0.0.1", freePort), MESSAGE, SendOptions.DEFAULTS);
        assertThat(r.outcome()).isEqualTo(SendOutcome.CONNECTION_FAILED);
        assertThat(r.detail()).isNotEmpty();
    }

    @Test
    void invalidMessageIsNotSent() {
        SendResult r = sender.send(destination, "PID|1||X", SendOptions.DEFAULTS);
        assertThat(r.outcome()).isEqualTo(SendOutcome.VALIDATION_FAILED);
        assertThat(received).isEmpty();
    }

    @Test
    void listenerRejectsUnparseableInput() {
        MllpClientConfig d = destination;
        SendResult r = sender.send(d, new PreparedMessage("hello\r", "", "", new io.hl7sender.core.hl7.validation
                .ValidationReport(java.util.Optional.empty(), 1, List.of())), AckMode.EXPECT_ACK);
        assertThat(r.outcome()).isEqualTo(SendOutcome.APPLICATION_REJECT);
    }

    @Test
    void persistentConnectionHandlesSeveralMessages() throws IOException {
        try (var client = new io.hl7sender.core.mllp.MllpClient(destination)) {
            client.connect();
            for (int i = 0; i < 5; i++) {
                PreparedMessage p = sender.prepare(MESSAGE, SendOptions.DEFAULTS);
                String ack = client.sendAndReceive(p.wire());
                assertThat(ack).contains("MSA|AA|" + p.controlId());
            }
        }
        assertThat(received).hasSize(5);
    }
}
