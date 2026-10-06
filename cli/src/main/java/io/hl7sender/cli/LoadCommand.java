package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.load.LatencyStats;
import io.hl7sender.core.load.LoadTest;
import io.hl7sender.core.load.LoadTestPlan;
import io.hl7sender.core.load.LoadTestReport;
import io.hl7sender.core.load.LoadTestSnapshot;
import io.hl7sender.core.load.LoadThresholds;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.tls.TlsOptions;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "load", mixinStandardHelpOptions = true, sortOptions = false,
        description = {"Load-test a receiver: send messages over several connections at a target rate and report "
            + "throughput, ACK latency percentiles and outcomes.",
            "Messages are sent directly, not queued. Each FILE may hold one message, several, or a batch; they are "
                + "sent in turn, and $${...} template variables get new values for every message. Progress is "
                + "printed to standard error every second.",
            "Exits with 10 if a threshold (--max-error-percent, --max-p95, --max-p99, --min-throughput) is not met."},
        footer = {"%nExamples:",
            "  hl7send load -H mirth-test -p 6661 -c 10 -r 200 -t 5m adt.hl7",
            "  hl7send load -d \"EHR test\" -n 10000 -c 20 --max-error-percent 0 --max-p95 250 --json orders/*.hl7"})
final class LoadCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Option(names = {"-H", "--host"}, description = "Receiver host name or IP address.")
    private String host;

    @Option(names = {"-p", "--port"}, description = "Receiver TCP port.")
    private Integer port;

    @Option(names = {"-d", "--destination"}, description = "Use a saved destination's address, timeouts, charset, "
            + "ACK mode and TLS settings.")
    private String destinationName;

    @Option(names = {"-c", "--connections"}, paramLabel = "N", defaultValue = "1",
            description = "Concurrent connections, each sending one message at a time (default: ${DEFAULT-VALUE}).")
    private int connections;

    @Option(names = {"-r", "--rate"}, paramLabel = "PER_SECOND", defaultValue = "0",
            description = "Target messages per second across all connections; 0 = as fast as the receiver answers.")
    private double rate;

    @Option(names = {"-n", "--count"}, paramLabel = "N", defaultValue = "0",
            description = "Stop after N messages.")
    private long count;

    @Option(names = {"-t", "--duration"}, paramLabel = "TIME", converter = DurationConverter.class,
            description = "Stop after this long, e.g. 90s, 5m, 1h. With neither --count nor --duration: 1000 "
                    + "messages.")
    private Duration duration;

    @Option(names = "--connect-timeout", paramLabel = "MS", description = "TCP connect timeout in milliseconds.")
    private Integer connectTimeoutMs;

    @Option(names = "--ack-timeout", paramLabel = "MS", description = "ACK timeout in milliseconds.")
    private Integer ackTimeoutMs;

    @Option(names = "--charset", description = "Character set for the files and the wire.")
    private Charset charset;

    @Option(names = "--no-ack", description = "Do not wait for acknowledgments.")
    private boolean noAck;

    @Option(names = "--keep-control-id", description = "Send MSH-7 and MSH-10 as written instead of new values.")
    private boolean keepControlId;

    @Option(names = "--max-error-percent", paramLabel = "PERCENT",
            description = "Fail if more than this share of messages is not accepted (0 = any failure fails).")
    private Double maxErrorPercent;

    @Option(names = "--max-p95", paramLabel = "MS", description = "Fail if the 95th-percentile latency is higher.")
    private Double maxP95;

    @Option(names = "--max-p99", paramLabel = "MS", description = "Fail if the 99th-percentile latency is higher.")
    private Double maxP99;

    @Option(names = "--min-throughput", paramLabel = "PER_SECOND",
            description = "Fail if fewer messages per second complete.")
    private Double minThroughput;

    @Option(names = {"-q", "--quiet"}, description = "Do not print progress.")
    private boolean quiet;

    @Mixin
    private Output output;

    @Parameters(arity = "1..*", paramLabel = "FILE", description = "Message files, or '-' for standard input.")
    private List<Path> files;

    @Override
    public Integer call() throws InterruptedException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (destinationName == null && (host == null || port == null)) {
            err.println("Give --host and --port, or --destination");
            return ExitCodes.USAGE;
        }
        LoadTestPlan plan;
        try {
            plan = plan(err);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return ExitCodes.USAGE;
        } catch (IOException e) {
            err.println(e.getMessage());
            return ExitCodes.INVALID_INPUT;
        }
        if (plan == null) {
            return ExitCodes.INVALID_INPUT;
        }
        LoadThresholds thresholds = new LoadThresholds(orNone(maxErrorPercent), orNone(maxP95), orNone(maxP99),
                orNone(minThroughput));

        LoadTest test = new LoadTest(plan);
        CountDownLatch finished = new CountDownLatch(1);
        // Ctrl+C stops sending and still prints the report for what was sent.
        Thread hook = new Thread(() -> {
            test.cancel();
            try {
                finished.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "load-test-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            if (!quiet) {
                err.println("Load test: " + plan.describe());
                err.flush();
            }
            LoadTestReport report = test.run(s -> {
                if (!quiet && !s.finished()) {
                    err.println(progressLine(s));
                    err.flush();
                }
            });
            List<String> failures = thresholds.failures(report.result());
            int code = failures.isEmpty() ? ExitCodes.OK : ExitCodes.THRESHOLD;
            if (output.json) {
                var json = JsonViews.loadReport(report, thresholds.any() ? failures : null);
                json.put("exitCode", code);
                Output.printJson(out, json);
            } else {
                print(out, report, thresholds, failures);
            }
            return code;
        } finally {
            finished.countDown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException alreadyShuttingDown) {
                // The hook is running: it waits for the report above.
            }
        }
    }

    private LoadTestPlan plan(PrintWriter err) throws IOException {
        MllpClientConfig target;
        TlsOptions tls = null;
        AckMode ackMode = noAck ? AckMode.NO_ACK : AckMode.EXPECT_ACK;
        if (destinationName != null) {
            try (Workspace ws = Workspace.open(Permission.SEND, "run load tests")) {
                DestinationConfig d = ws.destination(destinationName);
                if (!d.isMllp()) {
                    throw new IllegalArgumentException("'" + d.name() + "' sends by " + d.transport()
                            + "; load tests are for MLLP destinations");
                }
                target = d.clientConfig();
                tls = ws.engine().tlsOptions(d).orElse(null);
                if (!noAck) {
                    ackMode = d.ackMode();
                }
            }
        } else {
            target = MllpClientConfig.of(host, port);
        }
        target = target.withTimeouts(connectTimeoutMs != null ? connectTimeoutMs : target.connectTimeoutMs(),
                ackTimeoutMs != null ? ackTimeoutMs : target.responseTimeoutMs());
        if (charset != null) {
            target = target.withCharset(charset);
        }
        List<String> messages = new ArrayList<>();
        for (Path f : files) {
            String text;
            try {
                text = MessageInput.read(f, target.charset());
            } catch (IOException e) {
                throw new IOException("Cannot read " + f + ": " + e.getMessage(), e);
            }
            messages.addAll(MessageSplitter.split(text).messages());
        }
        if (messages.isEmpty()) {
            err.println("No HL7 messages found in " + files);
            return null;
        }
        long n = count;
        Duration d = duration == null ? Duration.ZERO : duration;
        if (n == 0 && d.isZero()) {
            n = 1000;
        }
        return new LoadTestPlan(target, tls, messages, connections, rate, n, d, ackMode, !keepControlId);
    }

    private static double orNone(Double d) {
        return d == null ? -1 : d;
    }

    static String progressLine(LoadTestSnapshot s) {
        return String.format(Locale.ROOT, "%6.1fs  sent %,d  accepted %,d  failed %,d  %,.1f msg/s  p50 %.1f ms  "
                        + "p95 %.1f ms", s.elapsedMillis() / 1000.0, s.sent(), s.accepted(), s.failed(), s.throughput(),
                s.latency().p50(), s.latency().p95());
    }

    private static void print(PrintWriter out, LoadTestReport report, LoadThresholds thresholds,
                              List<String> failures) {
        LoadTestSnapshot r = report.result();
        LatencyStats l = r.latency();
        out.println((report.cancelled() ? "Load test stopped: " : "Load test finished: ") + report.plan());
        out.printf(Locale.ROOT, "  Duration     %.1f s%n", r.elapsedMillis() / 1000.0);
        out.printf(Locale.ROOT, "  Messages     %,d sent, %,d accepted, %,d not accepted (%.2f%%)%n", r.sent(),
                r.accepted(), r.failed(), r.errorPercent());
        out.printf(Locale.ROOT, "  Throughput   %,.1f msg/s%n", r.throughput());
        out.printf(Locale.ROOT, "  Latency (ms) min %.2f  mean %.2f  p50 %.2f  p90 %.2f  p95 %.2f  p99 %.2f  "
                + "max %.2f%n", l.min(), l.mean(), l.p50(), l.p90(), l.p95(), l.p99(), l.max());
        for (SendOutcome o : SendOutcome.values()) {
            long n = r.outcomes().getOrDefault(o, 0L);
            if (n > 0) {
                out.printf(Locale.ROOT, "  %-20s %,d%n", o.name(), n);
            }
        }
        if (!report.errors().isEmpty()) {
            out.println("  Problems:");
            report.errors().forEach(e -> out.println("    " + e));
        }
        if (thresholds.any()) {
            if (failures.isEmpty()) {
                out.println("  Thresholds: passed");
            } else {
                out.println("  Thresholds: FAILED");
                failures.forEach(f -> out.println("    " + f));
            }
        }
        out.flush();
    }

    /** Parses {@code 90}, {@code 90s}, {@code 500ms}, {@code 5m} and {@code 1h}. */
    static final class DurationConverter implements CommandLine.ITypeConverter<Duration> {
        @Override
        public Duration convert(String value) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            try {
                if (v.endsWith("ms")) {
                    return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2).trim()));
                }
                if (v.endsWith("s")) {
                    return Duration.ofSeconds(Long.parseLong(v.substring(0, v.length() - 1).trim()));
                }
                if (v.endsWith("m")) {
                    return Duration.ofMinutes(Long.parseLong(v.substring(0, v.length() - 1).trim()));
                }
                if (v.endsWith("h")) {
                    return Duration.ofHours(Long.parseLong(v.substring(0, v.length() - 1).trim()));
                }
                return Duration.ofSeconds(Long.parseLong(v));
            } catch (NumberFormatException e) {
                throw new CommandLine.TypeConversionException("'" + value + "' is not a duration such as 90s, 5m "
                        + "or 1h");
            }
        }
    }
}
