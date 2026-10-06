package io.hl7sender.core.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOutcome;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** {@link LoadTest} against the built-in {@link TestListener}. */
@Timeout(60)
class LoadTestTest {

    private static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20200101000000||ADT^A01^ADT_A01|ORIG1|P|2.5.1\r"
            + "PID|1||${RANDOM_MRN}^^^HOSP^MR||DOE^JANE||19800101|F\r"
            + "PV1|1|I";

    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private TestListener listener;
    private MllpClientConfig target;

    @BeforeEach
    void startListener() throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
        target = MllpClientConfig.of("127.0.0.1", listener.port()).withTimeouts(2000, 1000);
    }

    @AfterEach
    void stopListener() {
        listener.close();
    }

    @Test
    void sendsExactlyTheRequestedNumberOverSeveralConnections() throws Exception {
        LoadTestPlan plan = LoadTestPlan.of(target, List.of(MESSAGE), 200).withConnections(8);
        LoadTestReport report = new LoadTest(plan).run(null);

        LoadTestSnapshot r = report.result();
        assertThat(report.cancelled()).isFalse();
        assertThat(r.finished()).isTrue();
        assertThat(r.sent()).isEqualTo(200);
        assertThat(r.completed()).isEqualTo(200);
        assertThat(r.accepted()).isEqualTo(200);
        assertThat(r.errorPercent()).isZero();
        assertThat(received).hasSize(200);
        // Every message got its own control ID and its own MRN from the template.
        assertThat(received.stream().map(ReceivedMessage::controlId).distinct()).hasSize(200);
        assertThat(received.stream().map(m -> m.payload().split("\r")[1]).distinct().count()).isGreaterThan(150);
        // Several connections were really used at once.
        Set<String> remotes = ConcurrentHashMap.newKeySet();
        received.forEach(m -> remotes.add(m.remote()));
        assertThat(remotes).hasSizeGreaterThan(1).hasSizeLessThanOrEqualTo(8);

        LatencyStats latency = r.latency();
        assertThat(latency.count()).isEqualTo(200);
        assertThat(latency.min()).isLessThanOrEqualTo(latency.p50());
        assertThat(latency.p50()).isLessThanOrEqualTo(latency.p95());
        assertThat(latency.p95()).isLessThanOrEqualTo(latency.p99());
        assertThat(latency.p99()).isLessThanOrEqualTo(latency.max());
        assertThat(r.timeline()).isNotEmpty();
        assertThat(r.timeline().stream().mapToLong(LoadTestSnapshot.Second::completed).sum()).isEqualTo(200);
    }

    @Test
    void rateLimitSpacesMessagesOut() throws Exception {
        // 20 messages at 40/s must take at least ~0.475 s (the last one is due at 19/40 s).
        LoadTestPlan plan = LoadTestPlan.of(target, List.of(MESSAGE), 20).withConnections(4).withRate(40);
        long start = System.nanoTime();
        LoadTestReport report = new LoadTest(plan).run(null);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(report.result().accepted()).isEqualTo(20);
        assertThat(millis).isGreaterThanOrEqualTo(450);
    }

    @Test
    void durationLimitStopsTheTestAndReportsProgress() throws Exception {
        LoadTestPlan plan = LoadTestPlan.forDuration(target, List.of(MESSAGE), Duration.ofMillis(1500))
                .withConnections(2).withRate(50);
        List<LoadTestSnapshot> progress = new CopyOnWriteArrayList<>();
        LoadTestReport report = new LoadTest(plan).run(progress::add);

        assertThat(report.result().elapsedMillis()).isBetween(1400L, 5000L);
        // About 75 messages at 50/s for 1.5 s.
        assertThat(report.result().completed()).isBetween(50L, 80L);
        assertThat(progress).isNotEmpty();
        assertThat(progress.get(progress.size() - 1).finished()).isTrue();
        assertThat(progress.stream().filter(LoadTestSnapshot::finished)).hasSize(1);
    }

    @Test
    void negativeAcknowledgmentsAndTimeoutsAreCountedAndSummarised() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.ERROR));
        LoadTestReport errors = new LoadTest(LoadTestPlan.of(target, List.of(MESSAGE), 10)).run(null);
        assertThat(errors.result().outcomes()).containsEntry(SendOutcome.APPLICATION_ERROR, 10L);
        assertThat(errors.result().errorPercent()).isEqualTo(100.0);
        assertThat(errors.errors()).singleElement().asString().startsWith("APPLICATION_ERROR").endsWith("(x10)");

        listener.updateSettings(listener.settings().withMode(ResponseMode.NO_RESPONSE));
        MllpClientConfig fast = target.withTimeouts(1000, 200);
        LoadTestReport timeouts = new LoadTest(LoadTestPlan.of(fast, List.of(MESSAGE), 3)).run(null);
        assertThat(timeouts.result().outcomes()).containsEntry(SendOutcome.ACK_TIMEOUT, 3L);
        // Timeouts count in the latency, which is then at least the timeout.
        assertThat(timeouts.result().latency().min()).isGreaterThanOrEqualTo(190);
    }

    @Test
    void noAckModeCountsMessagesAsSent() throws Exception {
        LoadTestPlan plan = LoadTestPlan.of(target, List.of(MESSAGE), 25).withAckMode(AckMode.NO_ACK);
        LoadTestReport report = new LoadTest(plan).run(null);
        assertThat(report.result().outcomes()).containsEntry(SendOutcome.SENT_NO_ACK, 25L);
        assertThat(report.result().accepted()).isEqualTo(25);
    }

    @Test
    void receiverThatIsDownFailsFastWithoutHammering() throws Exception {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        MllpClientConfig down = MllpClientConfig.of("127.0.0.1", closedPort).withTimeouts(500, 500);
        LoadTestPlan plan = LoadTestPlan.forDuration(down, List.of(MESSAGE), Duration.ofSeconds(1));
        LoadTestReport report = new LoadTest(plan).run(null);

        long failed = report.result().outcomes().getOrDefault(SendOutcome.CONNECTION_FAILED, 0L);
        assertThat(failed).isBetween(1L, 10L);
        assertThat(report.result().latency().count()).isZero();
    }

    @Test
    void cancelStopsARunningTestPromptly() throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.NO_RESPONSE));
        MllpClientConfig slow = target.withTimeouts(1000, 30_000);
        LoadTest test = new LoadTest(LoadTestPlan.forDuration(slow, List.of(MESSAGE), Duration.ofMinutes(5))
                .withConnections(3));
        AtomicReference<LoadTestReport> report = new AtomicReference<>();
        Thread runner = Thread.ofPlatform().start(() -> {
            try {
                report.set(test.run(null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread.sleep(500);
        long start = System.nanoTime();
        test.cancel();
        runner.join(5000);

        assertThat(runner.isAlive()).isFalse();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(3000);
        assertThat(report.get().cancelled()).isTrue();
        // Messages abandoned by the cancel are not reported as receiver failures.
        assertThat(report.get().result().completed()).isZero();
    }

    @Test
    void planRejectsNonsense() {
        assertThatThrownBy(() -> LoadTestPlan.of(target, List.of(), 1)).hasMessageContaining("at least one message");
        assertThatThrownBy(() -> LoadTestPlan.of(target, List.of(MESSAGE), 0))
                .hasMessageContaining("count, a duration");
        assertThatThrownBy(() -> LoadTestPlan.forDuration(target, List.of(MESSAGE), Duration.ZERO))
                .hasMessageContaining("count, a duration");
        assertThatThrownBy(() -> LoadTestPlan.of(target, List.of(MESSAGE), 1).withConnections(0))
                .hasMessageContaining("Connections");
        assertThatThrownBy(() -> LoadTestPlan.of(target, List.of(MESSAGE), 1).withRate(-1))
                .hasMessageContaining("rate");
        assertThat(LoadTestPlan.of(target, List.of(MESSAGE), 100).withConnections(10).withRate(500).describe())
                .isEqualTo("10 connections, 500 msg/s, 100 messages -> " + target.address());
    }

    @Test
    void histogramPercentilesAreAccurate() {
        LatencyHistogram h = new LatencyHistogram();
        for (int i = 1; i <= 100_000; i++) {
            h.record(i * 10L);
        }
        assertThat(h.count()).isEqualTo(100_000);
        assertThat(h.minMicros()).isEqualTo(10);
        assertThat(h.maxMicros()).isEqualTo(1_000_000);
        assertThat(h.percentileMicros(50)).isCloseTo(500_000, org.assertj.core.data.Percentage.withPercentage(0.3));
        assertThat(h.percentileMicros(99)).isCloseTo(990_000, org.assertj.core.data.Percentage.withPercentage(0.3));
        assertThat(h.percentileMicros(100)).isEqualTo(1_000_000);
        assertThat(h.meanMicros()).isCloseTo(500_005, org.assertj.core.data.Offset.offset(1.0));
        for (long v : new long[] {0, 1, 1023, 1024, 1025, 2047, 2048, 123_456_789, (1L << 40) - 1}) {
            int i = LatencyHistogram.index(v);
            assertThat(LatencyHistogram.lowerBound(i)).isLessThanOrEqualTo(v);
            assertThat(LatencyHistogram.lowerBound(i) + LatencyHistogram.width(i)).isGreaterThan(v);
        }
    }
}
