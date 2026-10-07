package io.hl7sender.core.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.hl7sender.core.hl7.validation.ValidationIssue;
import io.hl7sender.core.hl7.validation.ValidationReport;
import io.hl7sender.core.load.LatencyStats;
import io.hl7sender.core.load.LoadTestReport;
import io.hl7sender.core.load.LoadTestSnapshot;
import io.hl7sender.core.monitor.DestinationStats;
import io.hl7sender.core.queue.AttemptRecord;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationState;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.schedule.Schedule;
import io.hl7sender.core.send.SendOutcome;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON shapes shared by the local REST API and the CLI's {@code --json} output, so scripts see the same field
 * names from both. Timestamps are ISO-8601 UTC strings; absent values are {@code null}.
 */
public final class JsonViews {

    /** Pretty-printing mapper for CLI output. */
    public static final ObjectMapper PRETTY = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    /** Compact mapper for the API. */
    public static final ObjectMapper COMPACT = new ObjectMapper();

    private JsonViews() {
    }

    /** {@code value} as indented JSON. */
    public static String pretty(Object value) {
        try {
            return PRETTY.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot write JSON: " + e.getMessage(), e);
        }
    }

    public static Map<String, Object> destination(DestinationConfig d, DestinationState state,
                                                  Map<MessageStatus, Integer> counts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("name", d.name());
        m.put("transport", d.transport());
        m.put("address", d.address());
        m.put("host", d.host());
        m.put("port", d.port());
        m.put("tls", d.tls().enabled());
        m.put("mutualTls", d.tls().enabled() && d.tls().mutual());
        m.put("ackMode", d.ackMode().name());
        m.put("connectionMode", d.connectionMode().name());
        m.put("paused", d.paused());
        m.put("maxPerSecond", d.maxPerSecond());
        m.put("validationLevel", d.validationLevel().name());
        m.put("watchFolder", d.watchFolder().isEmpty() ? null : d.watchFolder());
        if (state != null) {
            m.put("state", state.status().name());
            m.put("stateDetail", state.detail());
            m.put("nextAttemptAt", time(state.nextAttemptAt()));
            m.put("connected", state.connected());
            m.put("consecutiveFailures", state.consecutiveFailures());
        }
        if (counts != null) {
            m.put("counts", counts(counts));
            m.put("pending", counts.getOrDefault(MessageStatus.QUEUED, 0)
                    + counts.getOrDefault(MessageStatus.IN_FLIGHT, 0)
                    + counts.getOrDefault(MessageStatus.RETRY_PENDING, 0));
            m.put("deadLetter", counts.getOrDefault(MessageStatus.DEAD_LETTER, 0));
        }
        return m;
    }

    public static Map<String, Object> counts(Map<MessageStatus, Integer> counts) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (MessageStatus s : MessageStatus.values()) {
            m.put(s.name(), counts.getOrDefault(s, 0));
        }
        return m;
    }

    /** Message metadata; the payload (which may contain PHI) only when asked for. */
    public static Map<String, Object> message(QueuedMessage q, boolean payload) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.id());
        m.put("destinationId", q.destinationId());
        m.put("controlId", q.controlId());
        m.put("messageType", q.messageType());
        m.put("status", q.status().name());
        m.put("attempts", q.attempts());
        m.put("possibleDuplicate", q.possibleDuplicate());
        m.put("lastOutcome", q.lastOutcome().orElse(null));
        m.put("lastError", q.lastError().orElse(null));
        m.put("nextAttemptAt", q.status() == MessageStatus.RETRY_PENDING ? q.nextAttemptAt().toString() : null);
        m.put("createdAt", q.createdAt().toString());
        m.put("updatedAt", q.updatedAt().toString());
        m.put("completedAt", time(q.completedAt()));
        m.put("source", q.source().orElse(null));
        m.put("batchId", q.batchId().orElse(null));
        if (payload) {
            m.put("payload", q.payload());
        }
        return m;
    }

    public static Map<String, Object> attempt(AttemptRecord a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("attempt", a.attemptNo());
        m.put("startedAt", a.startedAt().toString());
        m.put("finishedAt", time(a.finishedAt()));
        m.put("outcome", a.outcome().orElse(null));
        m.put("detail", a.detail().orElse(null));
        m.put("roundTripMs", a.roundTripMs().orElse(null));
        m.put("ackCode", a.ackCode().orElse(null));
        m.put("applicationAck", a.applicationAck());
        return m;
    }

    public static Map<String, Object> stats(DestinationStats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("destinationId", s.destinationId());
        m.put("from", s.windowStart().toString());
        m.put("to", s.windowEnd().toString());
        m.put("attempts", s.attempts());
        m.put("accepted", s.accepted());
        m.put("nacks", s.nacks());
        m.put("failures", s.failures());
        m.put("acceptRate", s.acceptRate() < 0 ? null : s.acceptRate());
        m.put("nackRate", s.nackRate() < 0 ? null : s.nackRate());
        m.put("throughputPerMinute", s.throughputPerMinute());
        m.put("avgLatencyMs", s.avgLatencyMs() < 0 ? null : s.avgLatencyMs());
        m.put("maxLatencyMs", s.maxLatencyMs() < 0 ? null : s.maxLatencyMs());
        Map<String, Object> outcomes = new LinkedHashMap<>();
        for (SendOutcome o : SendOutcome.values()) {
            if (s.count(o) > 0) {
                outcomes.put(o.name(), s.count(o));
            }
        }
        m.put("outcomes", outcomes);
        m.put("acceptedPerMinute", s.acceptedPerMinute());
        return m;
    }

    /** A schedule, with its destination's name and next run (null if disabled or never). */
    public static Map<String, Object> schedule(Schedule s, String destinationName, Instant nextRun) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("name", s.name());
        m.put("cron", s.cron());
        m.put("zone", s.zone());
        m.put("destinationId", s.destinationId());
        m.put("destination", destinationName);
        m.put("count", s.count());
        m.put("enabled", s.enabled());
        m.put("nextRunAt", nextRun == null ? null : nextRun.toString());
        m.put("lastRunAt", time(s.lastRunAt()));
        m.put("lastResult", s.lastResult().isEmpty() ? null : s.lastResult());
        return m;
    }

    /** A load test result; {@code thresholdFailures} is null when no thresholds were set. */
    public static Map<String, Object> loadReport(LoadTestReport report, List<String> thresholdFailures) {
        LoadTestSnapshot r = report.result();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("plan", report.plan());
        m.put("startedAt", report.startedAt().toString());
        m.put("cancelled", report.cancelled());
        m.put("elapsedMs", r.elapsedMillis());
        m.put("sent", r.sent());
        m.put("completed", r.completed());
        m.put("accepted", r.accepted());
        m.put("failed", r.failed());
        m.put("errorPercent", r.errorPercent());
        m.put("throughputPerSecond", r.throughput());
        LatencyStats l = r.latency();
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("count", l.count());
        latency.put("minMs", l.min());
        latency.put("meanMs", l.mean());
        latency.put("p50Ms", l.p50());
        latency.put("p90Ms", l.p90());
        latency.put("p95Ms", l.p95());
        latency.put("p99Ms", l.p99());
        latency.put("maxMs", l.max());
        m.put("latency", latency);
        Map<String, Object> outcomes = new LinkedHashMap<>();
        for (SendOutcome o : SendOutcome.values()) {
            long n = r.outcomes().getOrDefault(o, 0L);
            if (n > 0) {
                outcomes.put(o.name(), n);
            }
        }
        m.put("outcomes", outcomes);
        m.put("errors", report.errors());
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (LoadTestSnapshot.Second s : r.timeline()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("second", s.second());
            t.put("completed", s.completed());
            t.put("failed", s.failed());
            t.put("meanLatencyMs", s.meanLatencyMs());
            t.put("maxLatencyMs", s.maxLatencyMs());
            timeline.add(t);
        }
        m.put("timeline", timeline);
        if (thresholdFailures != null) {
            Map<String, Object> th = new LinkedHashMap<>();
            th.put("passed", thresholdFailures.isEmpty());
            th.put("failures", thresholdFailures);
            m.put("thresholds", th);
        }
        return m;
    }

    public static List<Map<String, Object>> issues(ValidationReport report) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ValidationIssue i : report.issues()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", i.severity().name());
            m.put("source", i.source().name());
            m.put("message", i.message());
            out.add(m);
        }
        return out;
    }

    private static String time(Optional<Instant> t) {
        return t.map(Instant::toString).orElse(null);
    }
}
