package io.hl7sender.core.listener;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A receiver that commits a message but drops the connection before its ACK, and the duplicate the sender's retry
 * then delivers: the listener's "close on the first attempt" response and its duplicate count.
 */
@Timeout(30)
class DuplicateDeliveryTest {

    private static final String ADMIT = "MSH|^~\\&|EHR|HOSP|LAB|LABFAC|20260101120000||ADT^A01^ADT_A01|ADM1|P|2.5.1\r"
            + "PID|1||MRN999^^^HOSP^MR||ROE^RICHARD\r";
    private static final String ORDER = "MSH|^~\\&|EHR|HOSP|LAB|LABFAC|20260101120000||ORM^O01^ORM_O01|ORD1|P|2.5.1\r"
            + "PID|1||MRN123^^^HOSP^MR||DOE^JANE\r";

    @TempDir
    Path dir;

    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private final Hl7Sender sender = new Hl7Sender();
    private TestListener listener;
    private MllpClientConfig target;

    @BeforeEach
    void start() throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
        target = MllpClientConfig.of("127.0.0.1", listener.port()).withTimeouts(2000, 2000);
    }

    @AfterEach
    void stop() {
        listener.close();
    }

    /** Sends {@code message} exactly as written, so a resend carries the same MSH-10, as a retry does. */
    private SendOutcome send(String message) {
        return sender.send(target, message, SendOptions.AS_IS).outcome();
    }

    @Test
    void firstAttemptIsKeptButNotAcknowledgedAndTheRetryIsAccepted() throws Exception {
        Path saved = dir.resolve("received");
        listener.updateSettings(listener.settings().withMode(ResponseMode.CLOSE_FIRST_ATTEMPT).withSaveFolder(saved));

        assertThat(send(ADMIT)).isEqualTo(SendOutcome.CONNECTION_CLOSED);
        assertThat(received.get(0).responseCode()).isEqualTo("closed");
        assertThat(received.get(0).response()).isEmpty();
        assertThat(received.get(0).copy()).isEqualTo(1);
        assertThat(received.get(0).duplicate()).isFalse();
        // The receiver "committed" it: the message was saved before the connection was dropped.
        try (Stream<Path> files = Files.list(saved)) {
            assertThat(files).hasSize(1);
        }

        assertThat(send(ADMIT)).isEqualTo(SendOutcome.ACCEPTED);
        ReceivedMessage retry = received.get(1);
        assertThat(retry.controlId()).isEqualTo("ADM1");
        assertThat(retry.responseCode()).isEqualTo("AA");
        assertThat(retry.copy()).isEqualTo(2);
        assertThat(retry.duplicate()).isTrue();

        // Every further repeat is accepted and counted.
        assertThat(send(ADMIT)).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(received.get(2).copy()).isEqualTo(3);
    }

    @Test
    void messagesAreTrackedBySenderAndControlId() {
        listener.updateSettings(listener.settings().withMode(ResponseMode.CLOSE_FIRST_ATTEMPT));
        assertThat(send(ADMIT)).isEqualTo(SendOutcome.CONNECTION_CLOSED);
        // The same control ID from another facility is a different message.
        assertThat(send(ADMIT.replace("|EHR|HOSP|", "|EHR|CLINIC|"))).isEqualTo(SendOutcome.CONNECTION_CLOSED);
        assertThat(send(ADMIT.replace("|ADM1|", "|ADM2|"))).isEqualTo(SendOutcome.CONNECTION_CLOSED);
        assertThat(received).extracting(ReceivedMessage::copy).containsExactly(1, 1, 1);

        // Clear forgets them: the next copy counts as the first again.
        listener.forgetReceived();
        assertThat(send(ADMIT)).isEqualTo(SendOutcome.CONNECTION_CLOSED);
        assertThat(received.get(3).copy()).isEqualTo(1);
    }

    @Test
    void enhancedModeAcceptsTheRetryWithACommitAck() {
        listener.updateSettings(listener.settings().withMode(ResponseMode.CLOSE_FIRST_ATTEMPT).withCommitCodes(true));
        assertThat(send(ADMIT)).isEqualTo(SendOutcome.CONNECTION_CLOSED);
        assertThat(send(ADMIT)).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(received.get(1).responseCode()).isEqualTo("CA");
    }

    @Test
    void aRuleCanDropOnlySomeMessagesAndDuplicatesAreFlaggedInAnyMode() {
        listener.updateSettings(listener.settings().withRules(List.of(
                ResponseRule.of("Orders drop once", "ORM^O01", ResponseMode.CLOSE_FIRST_ATTEMPT))));
        assertThat(send(ORDER)).isEqualTo(SendOutcome.CONNECTION_CLOSED);
        assertThat(send(ORDER)).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(received.get(1).rule()).isEqualTo("Orders drop once");

        // Other messages get the normal response, and a resend is still reported as a duplicate.
        assertThat(send(ADMIT)).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(send(ADMIT)).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(received).extracting(ReceivedMessage::copy).containsExactly(1, 2, 1, 2);
    }
}
