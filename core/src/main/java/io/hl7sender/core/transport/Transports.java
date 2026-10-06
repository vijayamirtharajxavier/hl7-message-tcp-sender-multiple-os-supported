package io.hl7sender.core.transport;

import io.hl7sender.core.ack.AckParseException;
import io.hl7sender.core.ack.AckParser;
import io.hl7sender.core.ack.ParsedAck;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The transports available to destinations: MLLP (built into the delivery engine), HTTP, file, and any found in
 * plugin jars.
 */
public final class Transports implements java.io.Closeable {

    /** The built-in MLLP/TCP transport, handled directly by the delivery engine. */
    public static final String MLLP = "mllp";

    private static final Logger LOG = LoggerFactory.getLogger(Transports.class);

    private final Map<String, TransportFactory> factories;
    private final List<String> problems;
    /** Reads the plugin jars, or null when there are none. */
    private final URLClassLoader pluginLoader;

    private Transports(Map<String, TransportFactory> factories, List<String> problems, URLClassLoader pluginLoader) {
        this.factories = factories;
        this.problems = List.copyOf(problems);
        this.pluginLoader = pluginLoader;
    }

    /** The built-in transports and any on the class path. */
    public static Transports builtIn() {
        return load(null);
    }

    /**
     * The built-in transports plus those in the {@code .jar} files in {@code pluginFolder} (if it exists). A
     * plugin that cannot be loaded is skipped and reported by {@link #problems()}.
     */
    public static Transports load(Path pluginFolder) {
        Map<String, TransportFactory> found = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        URLClassLoader loader = null;
        add(found, problems, new HttpTransport.Factory(), "built in");
        add(found, problems, new FileTransport.Factory(), "built in");
        add(found, problems, new FhirTransport.Factory(), "built in");
        for (TransportFactory f : ServiceLoader.load(TransportFactory.class, Transports.class.getClassLoader())) {
            add(found, problems, f, "class path");
        }
        if (pluginFolder != null && Files.isDirectory(pluginFolder)) {
            List<URL> jars = new ArrayList<>();
            try (Stream<Path> files = Files.list(pluginFolder)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) {
                    jars.add(p.toUri().toURL());
                }
            } catch (IOException e) {
                problems.add("Cannot read the plugins folder " + pluginFolder + ": " + e.getMessage());
            }
            if (!jars.isEmpty()) {
                loader = new URLClassLoader(jars.toArray(URL[]::new), Transports.class.getClassLoader());
                try {
                    for (TransportFactory f : ServiceLoader.load(TransportFactory.class, loader)) {
                        add(found, problems, f, pluginFolder.toString());
                    }
                } catch (java.util.ServiceConfigurationError e) {
                    problems.add("A plugin in " + pluginFolder + " could not be loaded: " + e.getMessage());
                }
            }
        }
        problems.forEach(p -> LOG.warn("{}", p));
        return new Transports(found, problems, loader);
    }

    /**
     * Releases the plugin jars (on Windows they stay locked until then). Only call this once the plugin transports
     * are no longer in use: their classes cannot be loaded afterwards.
     */
    @Override
    public void close() throws IOException {
        if (pluginLoader != null) {
            pluginLoader.close();
        }
    }

    private static void add(Map<String, TransportFactory> found, List<String> problems, TransportFactory f,
                            String where) {
        String id = f.id();
        if (id == null || !id.matches("[a-z0-9-]{1,32}") || id.equals(MLLP)) {
            problems.add("Transport '" + id + "' from " + where + " has an invalid id and was skipped");
        } else if (found.containsKey(id)) {
            if (found.get(id).getClass() != f.getClass()) {
                problems.add("Transport '" + id + "' from " + where + " is already provided and was skipped");
            }
        } else {
            found.put(id, f);
            LOG.debug("Transport '{}' ({}) available from {}", id, f.displayName(), where);
        }
    }

    /** The factory for {@code id}, or empty for MLLP and unknown ids. */
    public Optional<TransportFactory> get(String id) {
        return Optional.ofNullable(factories.get(id));
    }

    /** Transports other than MLLP, in the order they were found. */
    public List<TransportFactory> all() {
        return List.copyOf(factories.values());
    }

    /** Plugins that could not be loaded. */
    public List<String> problems() {
        return problems;
    }

    /**
     * Checks a destination's transport settings.
     *
     * @throws IllegalArgumentException if the transport is unknown or its settings are not valid
     */
    public void validate(String transport, Map<String, String> options) {
        if (MLLP.equals(transport)) {
            return;
        }
        TransportFactory f = get(transport).orElseThrow(() -> new IllegalArgumentException("Unknown transport '"
                + transport + "'. Is its plugin in the plugins folder?"));
        f.validate(options);
    }

    /**
     * Builds the result of one send, for transports.
     *
     * @param ack    the acknowledgment, if the receiver sent an HL7 one
     * @param raw    the raw response, if any
     * @param start  when the send started
     */
    public static SendResult result(SendOutcome outcome, String where, PreparedMessage message, ParsedAck ack,
                                    String raw, String detail, Instant start, Duration roundTrip) {
        return new SendResult(outcome, where, message.controlId(), message.messageType(), message.wire(),
                message.validation(), Optional.ofNullable(ack), Optional.ofNullable(raw), detail == null ? ""
                : detail, start, Duration.ZERO, roundTrip);
    }

    /**
     * Interprets an HL7 acknowledgment returned by a non-MLLP receiver, as for MLLP: AA/CA accepted, AE/CE and
     * AR/CR by code, and a mismatched MSA-2 flagged. Returns empty if {@code body} is not an HL7 ACK.
     */
    public static Optional<SendResult> fromAck(String body, String where, PreparedMessage message, Instant start,
                                               Duration roundTrip) {
        if (body == null || !body.stripLeading().startsWith("MSH")) {
            return Optional.empty();
        }
        ParsedAck ack;
        try {
            ack = AckParser.parse(body);
        } catch (AckParseException e) {
            return Optional.empty();
        }
        String expected = message.controlId();
        if (!expected.isEmpty() && !expected.equals(ack.controlId())) {
            return Optional.of(result(SendOutcome.CONTROL_ID_MISMATCH, where, message, ack, body,
                    "Expected MSA-2 '" + expected + "' but received '" + ack.controlId() + "'", start, roundTrip));
        }
        SendOutcome outcome = switch (ack.code().category()) {
            case ACCEPT -> SendOutcome.ACCEPTED;
            case ERROR -> SendOutcome.APPLICATION_ERROR;
            case REJECT -> SendOutcome.APPLICATION_REJECT;
        };
        String detail = ack.text().isEmpty() && !ack.errors().isEmpty() ? ack.errors().get(0).describe() : ack.text();
        return Optional.of(result(outcome, where, message, ack, body, detail, start, roundTrip));
    }
}
