package io.hl7sender.core.queue;

import static io.hl7sender.core.queue.DeliveryEngineTest.await;
import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.ack.AckBuilder;
import io.hl7sender.core.ack.AckCode;
import io.hl7sender.core.ack.AckParser;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.mllp.Mllp;
import io.hl7sender.core.mllp.MllpFrameReader;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Enhanced acknowledgment mode: messages committed with CA wait for the application ACK their MSH-16 asks for. */
@Timeout(60)
class ApplicationAckTest {

    @TempDir
    Path dir;

    private QueueStore store;
    private DeliveryEngine engine;
    private TestListener receiver;
    private int appAckPort;
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        store = QueueStore.open(dir.resolve("queue.db"));
        engine = new DeliveryEngine(store, new Hl7Sender(), Clock.systemUTC(), new Random(7));
        receiver = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS.withCommitCodes(true), received::add);
        receiver.start();
        try (ServerSocket probe = new ServerSocket(0)) {
            appAckPort = probe.getLocalPort();
        }
        engine.start();
    }

    @AfterEach
    void tearDown() {
        engine.close();
        store.close();
        receiver.close();
    }

    /** An ADT^A01 asking for a commit ACK (MSH-15 AL) and an application ACK per {@code msh16}. */
    private static String message(String msh16) {
        return "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|ORIG|P|2.5.1|||AL|" + msh16 + "\r"
                + "EVN|A01|20260101\rPID|1||MRN1^^^H^MR||DOE^JANE\rPV1|1|I\r";
    }

    private void receiverSends(AckCode code) {
        receiver.updateSettings(receiver.settings().withAppAck(new ListenerSettings.AppAck(appAckPort, code, 200)));
    }

    private DestinationConfig destination(int timeoutMs) throws InterruptedException {
        DestinationConfig d = engine.saveDestination(DestinationConfig.of("EHR", "127.0.0.1", receiver.port())
                .withTimeouts(1_000, 2_000).withRetry(new RetryPolicy(3, 50, 200, 0))
                .withAppAck(appAckPort, timeoutMs));
        await("the application ACK listener", () -> engine.isListeningForAppAcks(d.id()));
        return d;
    }

    private QueuedMessage enqueue(DestinationConfig d, String msh16) {
        return engine.enqueue(d.id(), message(msh16), SendOptions.DEFAULTS).message().orElseThrow();
    }

    private QueuedMessage reload(QueuedMessage m) {
        return store.message(m.id()).orElseThrow();
    }

    private void awaitStatus(QueuedMessage m, MessageStatus status) throws InterruptedException {
        await(m.controlId() + " to become " + status, () -> reload(m).status() == status);
    }

    /** Sends {@code message} to the application ACK port as the receiver would and returns the reply. */
    private String sendToAppAckPort(String message) throws Exception {
        try (Socket s = new Socket("127.0.0.1", appAckPort)) {
            s.setSoTimeout(5_000);
            OutputStream out = s.getOutputStream();
            out.write(Mllp.frame(message, StandardCharsets.UTF_8));
            out.flush();
            byte[] reply = new MllpFrameReader(s.getInputStream(), Mllp.DEFAULT_MAX_FRAME_BYTES).readFrame();
            return reply == null ? null : new String(reply, StandardCharsets.UTF_8);
        }
    }

    @Test
    void anApplicationAcceptCompletesACommittedMessage() throws Exception {
        receiverSends(AckCode.AA);
        DestinationConfig d = destination(30_000);
        QueuedMessage m = enqueue(d, "AL");

        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
        QueuedMessage done = reload(m);
        assertThat(done.lastOutcome()).contains("ACCEPTED");
        // The commit ACK and the application ACK are both in the history.
        List<AttemptRecord> history = store.attempts(m.id());
        assertThat(history).extracting(AttemptRecord::ackCode).containsExactly(java.util.Optional.of("CA"),
                java.util.Optional.of("AA"));
        assertThat(history.get(1).applicationAck()).isTrue();
        assertThat(store.audit(m.id())).anySatisfy(e -> assertThat(e.toStatus()).contains("AWAITING_APP_ACK"));
    }

    @Test
    void anApplicationErrorDeadLettersWithoutResending() throws Exception {
        receiverSends(AckCode.AE);
        DestinationConfig d = destination(30_000);
        QueuedMessage m = enqueue(d, "AL");

        awaitStatus(m, MessageStatus.DEAD_LETTER);
        assertThat(reload(m).lastOutcome()).contains("APPLICATION_ERROR");
        assertThat(reload(m).attempts()).isEqualTo(1);
        assertThat(received).hasSize(1);
    }

    @Test
    void noApplicationAckInTimeDeadLettersAndALateOneStillCounts() throws Exception {
        DestinationConfig d = destination(1_000);
        QueuedMessage m = enqueue(d, "AL");

        awaitStatus(m, MessageStatus.AWAITING_APP_ACK);
        awaitStatus(m, MessageStatus.DEAD_LETTER);
        assertThat(reload(m).lastOutcome()).contains(QueueStore.APP_ACK_TIMEOUT);

        String late = new AckBuilder().build(ParsedMessage.parse(reload(m).payload()), AckCode.AA, "", null);
        // The ACK has no MSH-15 (original mode), so it is answered with AA rather than CA.
        assertThat(sendToAppAckPort(late)).contains("MSA|AA|");
        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
        assertThat(reload(m).lastOutcome()).contains("ACCEPTED");
    }

    @Test
    void withErrorsOnlySilenceMeansSuccess() throws Exception {
        // ER: the receiver reports only errors, so it sends nothing for an accepted message.
        receiverSends(AckCode.AA);
        DestinationConfig d = destination(1_000);
        QueuedMessage m = enqueue(d, "ER");

        awaitStatus(m, MessageStatus.AWAITING_APP_ACK);
        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
        assertThat(reload(m).lastOutcome()).contains(QueueStore.APP_ACK_NOT_SENT);
    }

    @Test
    void withErrorsOnlyAnErrorStillDeadLetters() throws Exception {
        receiverSends(AckCode.AR);
        DestinationConfig d = destination(30_000);
        QueuedMessage m = enqueue(d, "ER");
        awaitStatus(m, MessageStatus.DEAD_LETTER);
        assertThat(reload(m).lastOutcome()).contains("APPLICATION_REJECT");
    }

    @Test
    void aWaitingMessageDoesNotBlockTheQueue() throws Exception {
        DestinationConfig d = destination(60_000);
        QueuedMessage first = enqueue(d, "AL");
        QueuedMessage second = enqueue(d, "NE");
        awaitStatus(second, MessageStatus.ACKNOWLEDGED);
        assertThat(reload(first).status()).isEqualTo(MessageStatus.AWAITING_APP_ACK);
        assertThat(received).hasSize(2);

        // A user can stop waiting.
        assertThat(engine.moveToDeadLetter(first.id(), "no application ACK expected after all")).isTrue();
        assertThat(reload(first).status()).isEqualTo(MessageStatus.DEAD_LETTER);
    }

    @Test
    void withoutAnApplicationAckPortACommitCompletesTheMessage() throws Exception {
        DestinationConfig d = engine.saveDestination(DestinationConfig.of("EHR", "127.0.0.1", receiver.port()));
        QueuedMessage m = engine.enqueue(d.id(), message("AL"), SendOptions.DEFAULTS).message().orElseThrow();
        awaitStatus(m, MessageStatus.ACKNOWLEDGED);
        assertThat(engine.isListeningForAppAcks(d.id())).isFalse();
    }

    @Test
    void unmatchedAcksAreAnsweredAndAuditedAndOtherMessagesRejected() throws Exception {
        DestinationConfig d = destination(30_000);
        String unknown = "MSH|^~\\&|RAPP|RFAC|APP|FAC|20260101||ACK^A01^ACK|R1|P|2.5.1|||AL|NE\rMSA|AA|NOSUCHID\r";
        assertThat(sendToAppAckPort(unknown)).contains("MSA|CA|R1");
        assertThat(store.destinationAudit(d.id(), 10)).anySatisfy(e ->
                assertThat(e.detail().orElse("")).contains("NOSUCHID").contains("matches no waiting message"));

        assertThat(sendToAppAckPort(message("AL"))).contains("MSA|CR|");
        // MSH-15 NE: the receiver does not want its ACK acknowledged.
        String noReply = unknown.replace("|||AL|NE", "|||NE|NE");
        try (Socket s = new Socket("127.0.0.1", appAckPort)) {
            s.setSoTimeout(1_000);
            s.getOutputStream().write(Mllp.frame(noReply, StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            assertThat(s.getInputStream().read()).isEqualTo(-1);
        } catch (java.net.SocketTimeoutException expected) {
            // No reply within the timeout is also fine.
        }
    }

    @Test
    void lateAcksDoNotTouchARequeuedMessage() throws Exception {
        DestinationConfig d = destination(1_000);
        QueuedMessage m = enqueue(d, "AL");
        awaitStatus(m, MessageStatus.DEAD_LETTER);
        engine.close();
        assertThat(store.requeue(m.id(), QueueStore.ACTOR_USER)).isTrue();
        String late = new AckBuilder().build(ParsedMessage.parse(reload(m).payload()), AckCode.AA, "", null);
        assertThat(store.applyApplicationAck(d.id(), AckParser.parse(late), "test")).isEmpty();
        assertThat(reload(m).status()).isEqualTo(MessageStatus.QUEUED);
    }

    @Test
    void onlyTheDestinationsHostMaySendApplicationAcks() throws Exception {
        assertThat(ApplicationAckListener.allowed(InetAddress.getLoopbackAddress(), "ehr.example.org")).isTrue();
        InetAddress other = InetAddress.getByName("192.0.2.10");
        assertThat(ApplicationAckListener.allowed(other, "192.0.2.10")).isTrue();
        assertThat(ApplicationAckListener.allowed(other, "192.0.2.11")).isFalse();
        assertThat(ApplicationAckListener.allowed(other, "no-such-host.invalid")).isFalse();
    }

    @Test
    void settingsArePersistedAndExported() throws Exception {
        DestinationConfig d = store.saveDestination(DestinationConfig.of("EHR", "127.0.0.1", 2575)
                .withAppAck(6700, 90_000));
        DestinationConfig loaded = store.destination(d.id()).orElseThrow();
        assertThat(loaded.appAckPort()).isEqualTo(6700);
        assertThat(loaded.appAckTimeoutMs()).isEqualTo(90_000);
        assertThat(loaded.matchesAppAcks()).isTrue();

        Path profiles = dir.resolve("profiles.json");
        DestinationProfiles.write(List.of(loaded), profiles);
        DestinationConfig imported = DestinationProfiles.read(profiles).get(0);
        assertThat(imported.appAckPort()).isEqualTo(6700);
        assertThat(imported.appAckTimeoutMs()).isEqualTo(90_000);
    }
}
