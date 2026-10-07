package io.hl7sender.core.queue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.tls.TlsSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Exports and imports destination profiles as JSON, so interface configurations can be shared between
 * machines or kept in version control.
 *
 * <p>Exports never contain secrets (key-store passwords) or internal IDs. Imported profiles are new,
 * unsaved destinations. TLS passwords must be entered again on the importing machine.
 */
public final class DestinationProfiles {

    /** Format version written to every export. */
    public static final int FORMAT = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private DestinationProfiles() {
    }

    public static void write(List<DestinationConfig> destinations, Path file) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", FORMAT);
        root.put("application", "HL7 Sender");
        ArrayNode list = root.putArray("destinations");
        for (DestinationConfig d : destinations) {
            ObjectNode n = list.addObject();
            n.put("name", d.name());
            n.put("host", d.host());
            n.put("port", d.port());
            n.put("connectTimeoutMs", d.connectTimeoutMs());
            n.put("ackTimeoutMs", d.ackTimeoutMs());
            n.put("charset", d.charset());
            n.put("ackMode", d.ackMode().name());
            n.put("connectionMode", d.connectionMode().name());
            ObjectNode retry = n.putObject("retry");
            retry.put("maxAttempts", d.retry().maxAttempts());
            retry.put("baseDelayMs", d.retry().baseDelayMs());
            retry.put("maxDelayMs", d.retry().maxDelayMs());
            retry.put("jitter", d.retry().jitter());
            ObjectNode cb = n.putObject("circuitBreaker");
            cb.put("failureThreshold", d.circuitBreaker().failureThreshold());
            cb.put("coolDownMs", d.circuitBreaker().coolDownMs());
            n.put("ackPolicy", d.ackPolicy().encode());
            n.put("maxPerSecond", d.maxPerSecond());
            n.put("validationLevel", d.validationLevel().name());
            n.put("profilePath", d.profilePath());
            n.put("watchFolder", d.watchFolder());
            ObjectNode tls = n.putObject("tls");
            tls.put("enabled", d.tls().enabled());
            tls.put("trustStorePath", d.tls().trustStorePath());
            tls.put("keyStorePath", d.tls().keyStorePath());
            tls.put("verifyHostname", d.tls().verifyHostname());
            tls.put("protocols", d.tls().protocols());
            n.put("notes", d.notes());
            if (d.appAckPort() > 0) {
                n.put("appAckPort", d.appAckPort());
                n.put("appAckTimeoutMs", d.appAckTimeoutMs());
            }
            if (!d.script().isEmpty()) {
                n.put("script", d.script());
            }
            if (!d.isMllp()) {
                n.put("transport", d.transport());
                ObjectNode options = n.putObject("transportOptions");
                d.transportOptions().forEach(options::put);
            }
        }
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        MAPPER.writeValue(file.toFile(), root);
    }

    /** Reads profiles. Missing fields take their defaults, so older and hand-written files still import. */
    public static List<DestinationConfig> read(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        JsonNode list = root == null ? null : root.get("destinations");
        if (list == null || !list.isArray()) {
            throw new IOException("Not an HL7 Sender destination export (no \"destinations\" list)");
        }
        if (root.path("format").asInt(FORMAT) > FORMAT) {
            throw new IOException("This export was made by a newer version of HL7 Sender");
        }
        List<DestinationConfig> out = new ArrayList<>();
        int i = 0;
        for (JsonNode n : list) {
            i++;
            try {
                out.add(fromJson(n));
            } catch (IllegalArgumentException e) {
                throw new IOException("Destination " + i + " is invalid: " + e.getMessage(), e);
            }
        }
        return out;
    }

    private static DestinationConfig fromJson(JsonNode n) {
        String transport = n.path("transport").asText(DestinationConfig.MLLP);
        boolean mllp = transport.isBlank() || transport.equalsIgnoreCase(DestinationConfig.MLLP);
        // Non-MLLP destinations may have no host and port; start from a valid MLLP one and replace them below.
        DestinationConfig d = DestinationConfig.of(n.path("name").asText(""),
                mllp ? n.path("host").asText("") : "localhost", mllp ? n.path("port").asInt(0) : 2575);
        java.util.Map<String, String> options = new java.util.TreeMap<>();
        n.path("transportOptions").properties().forEach(e -> options.put(e.getKey(), e.getValue().asText()));
        JsonNode retry = n.path("retry");
        JsonNode cb = n.path("circuitBreaker");
        JsonNode tls = n.path("tls");
        return d.with(b -> {
            b.connectTimeoutMs = n.path("connectTimeoutMs").asInt(d.connectTimeoutMs());
            b.ackTimeoutMs = n.path("ackTimeoutMs").asInt(d.ackTimeoutMs());
            b.charset = n.path("charset").asText(d.charset());
            b.ackMode = AckMode.valueOf(n.path("ackMode").asText(d.ackMode().name()));
            b.connectionMode = ConnectionMode.valueOf(n.path("connectionMode").asText(d.connectionMode().name()));
            b.retry = new RetryPolicy(retry.path("maxAttempts").asInt(d.retry().maxAttempts()),
                    retry.path("baseDelayMs").asLong(d.retry().baseDelayMs()),
                    retry.path("maxDelayMs").asLong(d.retry().maxDelayMs()),
                    retry.path("jitter").asDouble(d.retry().jitter()));
            b.circuitBreaker = new CircuitBreakerSettings(cb.path("failureThreshold")
                    .asInt(d.circuitBreaker().failureThreshold()), cb.path("coolDownMs")
                    .asLong(d.circuitBreaker().coolDownMs()));
            b.ackPolicy = AckPolicy.decode(n.path("ackPolicy").asText(""));
            b.maxPerSecond = n.path("maxPerSecond").asInt(0);
            b.validationLevel = ValidationLevel.valueOf(n.path("validationLevel").asText("STANDARD"));
            b.profilePath = n.path("profilePath").asText("");
            b.watchFolder = n.path("watchFolder").asText("");
            b.tls = new TlsSettings(tls.path("enabled").asBoolean(false), tls.path("trustStorePath").asText(""),
                    tls.path("keyStorePath").asText(""), tls.path("verifyHostname").asBoolean(true),
                    tls.path("protocols").asText(TlsSettings.DEFAULT_PROTOCOLS));
            b.notes = n.path("notes").asText("");
            b.script = n.path("script").asText("");
            b.appAckPort = n.path("appAckPort").asInt(0);
            b.appAckTimeoutMs = n.path("appAckTimeoutMs").asInt(DestinationConfig.DEFAULT_APP_ACK_TIMEOUT_MS);
            b.transport = transport;
            b.transportOptions = options;
            if (!mllp) {
                b.host = n.path("host").asText("");
                b.port = n.path("port").asInt(0);
            }
        });
    }

    /** {@code name}, or {@code name (2)}, {@code name (3)}, ... if it is already taken. */
    public static String uniqueName(String name, Set<String> taken) {
        if (!taken.contains(name)) {
            return name;
        }
        int i = 2;
        while (taken.contains(name + " (" + i + ")")) {
            i++;
        }
        return name + " (" + i + ")";
    }
}
