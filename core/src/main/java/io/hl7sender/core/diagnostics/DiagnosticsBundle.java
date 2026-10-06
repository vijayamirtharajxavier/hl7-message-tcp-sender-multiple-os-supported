package io.hl7sender.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.hl7sender.core.AppInfo;
import io.hl7sender.core.alert.AlertSettings;
import io.hl7sender.core.alert.WebhookSink;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationProfiles;
import io.hl7sender.core.queue.DestinationState;
import io.hl7sender.core.queue.MessageStatus;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a zip file for support: system information, settings, destinations, queue counts, recent alerts
 * and logs. Passwords, keys and webhook tokens are never included, and message content is not either (the
 * logs never contain it). Anything in the logs that looks like a credential is masked as well.
 */
public final class DiagnosticsBundle {

    /** Log files larger than this in total are cut off, newest files first. */
    static final long MAX_LOG_BYTES = 20L * 1024 * 1024;

    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[_-]?key|authorization)(\\s*[=:]\\s*)"
                    + "(?!Bearer\\b)(\"[^\"]*\"|\\S+)");
    private static final Pattern URL_USERINFO = Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]*://)[^/@\\s]+@");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+");

    /**
     * Everything the bundle describes.
     *
     * @param paths          where the app keeps its files
     * @param settings       current settings
     * @param destinations   configured destinations
     * @param states         worker state per destination ID
     * @param counts         message counts per destination ID
     * @param secretBackend  description of the secret store
     * @param queueEncrypted whether the queue database is encrypted
     * @param recentAlerts   recent alert summaries, newest first
     */
    public record Input(AppPaths paths, AppSettings settings, List<DestinationConfig> destinations,
                        Map<Long, DestinationState> states, Map<Long, Map<MessageStatus, Integer>> counts,
                        String secretBackend, boolean queueEncrypted, List<String> recentAlerts) {
    }

    private DiagnosticsBundle() {
    }

    /** Writes the bundle and returns the names of the entries written. */
    public static List<String> write(Input in, Path zip, Clock clock) throws IOException {
        List<String> names = new ArrayList<>();
        Path tmp = Files.createTempFile("hl7sender-profiles", ".json");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            add(z, names, "README.txt", readme(clock));
            add(z, names, "system.txt", system(in, clock));
            add(z, names, "settings.json", settingsJson(in.settings()));
            DestinationProfiles.write(in.destinations(), tmp);
            add(z, names, "destinations.json", Files.readString(tmp, StandardCharsets.UTF_8));
            add(z, names, "queue.txt", queue(in));
            add(z, names, "alerts.txt", in.recentAlerts().isEmpty() ? "No alerts.\n"
                    : String.join("\n", in.recentAlerts()) + "\n");
            long budget = MAX_LOG_BYTES;
            for (Path log : logs(in.paths().logDir())) {
                long size = Files.size(log);
                if (size > budget) {
                    add(z, names, "logs/SKIPPED.txt", "Older log files were left out to keep the bundle small.\n");
                    break;
                }
                budget -= size;
                Path fileName = log.getFileName();
                String name = fileName == null ? "log" : fileName.toString();
                add(z, names, "logs/" + name, redact(Files.readString(log, StandardCharsets.UTF_8)));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return names;
    }

    /** Masks values that look like credentials: {@code password=...}, {@code Bearer ...}, {@code user:pw@host}. */
    public static String redact(String text) {
        String s = BEARER.matcher(text).replaceAll("Bearer ***");
        s = CREDENTIAL.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2) + "***"));
        return URL_USERINFO.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "***@"));
    }

    private static List<Path> logs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".log"))
                    .sorted(Comparator.comparing(DiagnosticsBundle::modified).reversed())
                    .toList();
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void add(ZipOutputStream z, List<String> names, String name, String text) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(text.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
        names.add(name);
    }

    private static String readme(Clock clock) {
        return String.join("\n",
                AppInfo.NAME + " diagnostics bundle, created " + clock.instant(),
                "",
                "Included: system information, settings, destination settings and notes, message counts per",
                "destination, recent alerts, and application logs.",
                "Not included: messages or ACKs (the logs never contain message content), passwords, keys,",
                "the queue database. Webhook URLs are shortened to their host, and anything in the logs that",
                "looks like a credential is masked.",
                "",
                "Review destinations.json before sharing: notes may contain names or phone numbers.",
                "");
    }

    private static String system(Input in, Clock clock) {
        Runtime rt = Runtime.getRuntime();
        StringBuilder b = new StringBuilder();
        line(b, "Application", AppInfo.NAME + " " + AppInfo.version());
        line(b, "Created", clock.instant().toString());
        line(b, "Java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        line(b, "JavaFX", System.getProperty("javafx.runtime.version", "-"));
        line(b, "OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " ("
                + System.getProperty("os.arch") + ")");
        line(b, "CPUs", String.valueOf(rt.availableProcessors()));
        line(b, "Memory", (rt.totalMemory() - rt.freeMemory()) / 1_048_576 + " MB used of "
                + rt.maxMemory() / 1_048_576 + " MB");
        line(b, "Locale", Locale.getDefault().toLanguageTag());
        line(b, "Time zone", ZoneId.systemDefault().getId());
        line(b, "Settings", in.paths().configDir().toString());
        line(b, "Data", in.paths().dataDir().toString());
        line(b, "Logs", in.paths().logDir().toString());
        line(b, "Secret store", in.secretBackend());
        line(b, "Queue encrypted", String.valueOf(in.queueEncrypted()));
        return b.toString();
    }

    private static String queue(Input in) {
        if (in.destinations().isEmpty()) {
            return "No destinations.\n";
        }
        StringBuilder b = new StringBuilder();
        for (DestinationConfig d : in.destinations()) {
            b.append(d.name()).append('\n');
            line(b, "  Address", d.displayAddress() + (d.tls().mutual() ? " (mutual TLS)" : ""));
            line(b, "  ACK mode", d.ackMode() + ", " + d.connectionMode());
            line(b, "  Paused", String.valueOf(d.paused()));
            DestinationState s = in.states().get(d.id());
            if (s != null) {
                line(b, "  State", s.status() + " - " + s.detail());
            }
            Map<MessageStatus, Integer> c = in.counts().getOrDefault(d.id(), Map.of());
            StringBuilder counts = new StringBuilder();
            for (MessageStatus st : MessageStatus.values()) {
                counts.append(st).append('=').append(c.getOrDefault(st, 0)).append(' ');
            }
            line(b, "  Messages", counts.toString().trim());
        }
        return b.toString();
    }

    private static String settingsJson(AppSettings s) throws IOException {
        AlertSettings a = s.alerts();
        AppSettings safe = s.withAlerts(new AlertSettings(a.desktop(), a.deadLetter(), a.deadLetterThreshold(),
                a.circuitOpen(), a.certificateExpiry(), a.webhookEnabled() ? WebhookSink.redact(a.webhookUrl()) : "",
                a.email()));
        return new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(safe);
    }

    private static void line(StringBuilder b, String key, String value) {
        b.append(key).append(": ").append(value).append('\n');
    }
}
