package io.hl7sender.core.queue;

import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.hl7.validation.ValidationPolicy;
import io.hl7sender.core.mllp.Mllp;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.tls.TlsSettings;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * A named receiver with its own queue and delivery policy.
 *
 * @param id               database ID, or 0 for a destination not yet saved
 * @param name             unique display name
 * @param host             receiver host
 * @param port             receiver port
 * @param connectTimeoutMs TCP connect timeout
 * @param ackTimeoutMs     total time to wait for an ACK
 * @param charset          wire character set
 * @param ackMode          wait for an ACK, or send without one
 * @param connectionMode   persistent or per-message connections
 * @param retry            backoff and attempt limit
 * @param circuitBreaker   failure threshold and cool-down
 * @param ackPolicy        what to do after each outcome
 * @param paused           if true, nothing is sent until resumed
 * @param maxPerSecond     delivery rate limit in messages per second; 0 means unlimited
 * @param validationLevel  how strictly messages are validated when queued
 * @param profilePath      conformance profile file, or empty for none
 * @param watchFolder      folder whose message files are queued automatically, or empty for none
 * @param tls              TLS settings (passwords are kept in the secret store under {@code secretRef})
 * @param secretRef        handle for this destination's secrets; assigned when first saved
 * @param notes            free-text notes, e.g. interface contacts or receiver configuration
 * @param script           JavaScript run on each message as it is queued (see {@code MessageScript}); empty for none
 * @param transport        how messages are sent: {@code mllp} (host and port), or a transport such as {@code http}
 *                         or {@code file}; see {@code Transports}
 * @param transportOptions the transport's settings, e.g. {@code url} for HTTP; empty for MLLP
 * @param appAckPort       port on which HL7 Sender listens for this receiver's application ACKs (enhanced mode), or
 *                         0 for none. With a port, a message answered with CA whose MSH-16 is AL, ER or SU waits for
 *                         its application ACK; without one, CA completes it.
 * @param appAckTimeoutMs  how long such a message waits for its application ACK
 */
public record DestinationConfig(
        long id,
        String name,
        String host,
        int port,
        int connectTimeoutMs,
        int ackTimeoutMs,
        String charset,
        AckMode ackMode,
        ConnectionMode connectionMode,
        RetryPolicy retry,
        CircuitBreakerSettings circuitBreaker,
        AckPolicy ackPolicy,
        boolean paused,
        int maxPerSecond,
        ValidationLevel validationLevel,
        String profilePath,
        String watchFolder,
        TlsSettings tls,
        String secretRef,
        String notes,
        String script,
        String transport,
        Map<String, String> transportOptions,
        int appAckPort,
        int appAckTimeoutMs) {

    public DestinationConfig {
        Objects.requireNonNull(ackMode, "ackMode");
        Objects.requireNonNull(connectionMode, "connectionMode");
        Objects.requireNonNull(retry, "retry");
        Objects.requireNonNull(circuitBreaker, "circuitBreaker");
        Objects.requireNonNull(ackPolicy, "ackPolicy");
        Objects.requireNonNull(validationLevel, "validationLevel");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Destination name is required");
        }
        name = name.trim();
        if (charset == null || !Charset.isSupported(charset)) {
            throw new IllegalArgumentException("Unsupported charset: " + charset);
        }
        if (maxPerSecond < 0) {
            throw new IllegalArgumentException("Rate limit cannot be negative");
        }
        transport = transport == null || transport.isBlank() ? MLLP : transport.trim().toLowerCase(Locale.ROOT);
        transportOptions = transportOptions == null ? Map.of() : Map.copyOf(new TreeMap<>(transportOptions));
        if (transport.equals(MLLP)) {
            // Validates host, port and timeouts.
            new MllpClientConfig(host, port, connectTimeoutMs, ackTimeoutMs, Charset.forName(charset),
                    Mllp.DEFAULT_MAX_FRAME_BYTES);
        } else if (connectTimeoutMs < 0 || ackTimeoutMs < 0) {
            throw new IllegalArgumentException("Timeouts cannot be negative");
        }
        host = host == null ? "" : host.trim();
        profilePath = profilePath == null ? "" : profilePath.trim();
        watchFolder = watchFolder == null ? "" : watchFolder.trim();
        tls = tls == null ? TlsSettings.DISABLED : tls;
        secretRef = secretRef == null ? "" : secretRef.trim();
        notes = notes == null ? "" : notes;
        script = script == null ? "" : script;
        if (appAckPort < 0 || appAckPort > 65_535) {
            throw new IllegalArgumentException("Application ACK port must be between 0 and 65535");
        }
        if (appAckTimeoutMs < 1_000) {
            throw new IllegalArgumentException("Application ACK timeout must be at least 1 second");
        }
    }

    /** The built-in MLLP/TCP transport. */
    public static final String MLLP = "mllp";

    /** Default wait for an application ACK: 5 minutes. */
    public static final int DEFAULT_APP_ACK_TIMEOUT_MS = 300_000;

    /** A destination without application ACK matching. */
    public DestinationConfig(long id, String name, String host, int port, int connectTimeoutMs, int ackTimeoutMs,
                             String charset, AckMode ackMode, ConnectionMode connectionMode, RetryPolicy retry,
                             CircuitBreakerSettings circuitBreaker, AckPolicy ackPolicy, boolean paused,
                             int maxPerSecond, ValidationLevel validationLevel, String profilePath,
                             String watchFolder, TlsSettings tls, String secretRef, String notes, String script,
                             String transport, Map<String, String> transportOptions) {
        this(id, name, host, port, connectTimeoutMs, ackTimeoutMs, charset, ackMode, connectionMode, retry,
                circuitBreaker, ackPolicy, paused, maxPerSecond, validationLevel, profilePath, watchFolder, tls,
                secretRef, notes, script, transport, transportOptions, 0, DEFAULT_APP_ACK_TIMEOUT_MS);
    }

    /** An MLLP destination without a transform script. */
    public DestinationConfig(long id, String name, String host, int port, int connectTimeoutMs, int ackTimeoutMs,
                             String charset, AckMode ackMode, ConnectionMode connectionMode, RetryPolicy retry,
                             CircuitBreakerSettings circuitBreaker, AckPolicy ackPolicy, boolean paused,
                             int maxPerSecond, ValidationLevel validationLevel, String profilePath,
                             String watchFolder, TlsSettings tls, String secretRef, String notes) {
        this(id, name, host, port, connectTimeoutMs, ackTimeoutMs, charset, ackMode, connectionMode, retry,
                circuitBreaker, ackPolicy, paused, maxPerSecond, validationLevel, profilePath, watchFolder, tls,
                secretRef, notes, "", MLLP, Map.of());
    }

    /** True for the built-in MLLP/TCP transport (host and port). */
    public boolean isMllp() {
        return transport.equals(MLLP);
    }

    /** A new, unsaved destination with default delivery settings. */
    public static DestinationConfig of(String name, String host, int port) {
        return new DestinationConfig(0, name, host, port, MllpClientConfig.DEFAULT_CONNECT_TIMEOUT_MS,
                MllpClientConfig.DEFAULT_RESPONSE_TIMEOUT_MS, "UTF-8", AckMode.EXPECT_ACK,
                ConnectionMode.PERSISTENT, RetryPolicy.DEFAULT, CircuitBreakerSettings.DEFAULT, AckPolicy.DEFAULT,
                false, 0, ValidationLevel.STANDARD, "", "", TlsSettings.DISABLED, "", "");
    }

    /**
     * The MLLP connection settings.
     *
     * @throws IllegalStateException for destinations that use another transport
     */
    public MllpClientConfig clientConfig() {
        if (!isMllp()) {
            throw new IllegalStateException("'" + name + "' sends by " + transport + ", not MLLP");
        }
        return new MllpClientConfig(host, port, connectTimeoutMs, ackTimeoutMs, Charset.forName(charset),
                Mllp.DEFAULT_MAX_FRAME_BYTES);
    }

    /** {@code host:port} for MLLP; for other transports the main setting, such as the URL or folder. */
    public String address() {
        if (!isMllp()) {
            String main = transportOptions.getOrDefault("url", transportOptions.getOrDefault("folder",
                    transportOptions.getOrDefault("baseUrl", "")));
            return main.isEmpty() ? transport : main;
        }
        return host + ":" + port;
    }

    public ValidationPolicy validationPolicy() {
        return new ValidationPolicy(validationLevel, profile());
    }

    public Optional<Path> profile() {
        return profilePath.isEmpty() ? Optional.empty() : Optional.of(Path.of(profilePath));
    }

    public Optional<Path> watchPath() {
        return watchFolder.isEmpty() ? Optional.empty() : Optional.of(Path.of(watchFolder));
    }

    /** Returns a copy with changes applied through a {@link Builder}. */
    public DestinationConfig with(Consumer<Builder> changes) {
        Builder b = new Builder(this);
        changes.accept(b);
        return b.build();
    }

    public DestinationConfig withId(long newId) {
        return with(b -> b.id = newId);
    }

    public DestinationConfig withRetry(RetryPolicy newRetry) {
        return with(b -> b.retry = newRetry);
    }

    public DestinationConfig withCircuitBreaker(CircuitBreakerSettings settings) {
        return with(b -> b.circuitBreaker = settings);
    }

    public DestinationConfig withAckPolicy(AckPolicy policy) {
        return with(b -> b.ackPolicy = policy);
    }

    public DestinationConfig withTimeouts(int connectMs, int ackMs) {
        return with(b -> {
            b.connectTimeoutMs = connectMs;
            b.ackTimeoutMs = ackMs;
        });
    }

    public DestinationConfig withAckMode(AckMode mode) {
        return with(b -> b.ackMode = mode);
    }

    public DestinationConfig withConnectionMode(ConnectionMode mode) {
        return with(b -> b.connectionMode = mode);
    }

    public DestinationConfig withPort(int newPort) {
        return with(b -> b.port = newPort);
    }

    public DestinationConfig withAddress(String newHost, int newPort) {
        return with(b -> {
            b.host = newHost;
            b.port = newPort;
        });
    }

    public DestinationConfig withPaused(boolean newPaused) {
        return with(b -> b.paused = newPaused);
    }

    public DestinationConfig withMaxPerSecond(int rate) {
        return with(b -> b.maxPerSecond = rate);
    }

    public DestinationConfig withValidation(ValidationLevel level, String profile) {
        return with(b -> {
            b.validationLevel = level;
            b.profilePath = profile;
        });
    }

    public DestinationConfig withWatchFolder(String folder) {
        return with(b -> b.watchFolder = folder);
    }

    public DestinationConfig withTls(TlsSettings settings) {
        return with(b -> b.tls = settings);
    }

    public DestinationConfig withNotes(String text) {
        return with(b -> b.notes = text);
    }

    public DestinationConfig withName(String newName) {
        return with(b -> b.name = newName);
    }

    public DestinationConfig withScript(String source) {
        return with(b -> b.script = source);
    }

    public DestinationConfig withSecretRef(String ref) {
        return with(b -> b.secretRef = ref);
    }

    /** {@code tls://host:port} or {@code host:port}. */
    public String displayAddress() {
        if (!isMllp()) {
            return transport.equals("http") || transport.equals("file") ? address() : transport + ": " + address();
        }
        return (tls.enabled() ? "tls://" : "") + address();
    }

    /** Listens for application ACKs on {@code port} (0 = off) and waits up to {@code timeoutMs} for each. */
    public DestinationConfig withAppAck(int port, int timeoutMs) {
        return with(b -> {
            b.appAckPort = port;
            b.appAckTimeoutMs = timeoutMs;
        });
    }

    /** True if messages may wait for application ACKs on {@link #appAckPort()}. */
    public boolean matchesAppAcks() {
        return appAckPort > 0 && isMllp();
    }

    public DestinationConfig withTransport(String id, Map<String, String> options) {
        return with(b -> {
            b.transport = id;
            b.transportOptions = options;
        });
    }

    /** Mutable copy of a {@link DestinationConfig}, for {@link #with(Consumer)}. */
    public static final class Builder {
        public long id;
        public String name;
        public String host;
        public int port;
        public int connectTimeoutMs;
        public int ackTimeoutMs;
        public String charset;
        public AckMode ackMode;
        public ConnectionMode connectionMode;
        public RetryPolicy retry;
        public CircuitBreakerSettings circuitBreaker;
        public AckPolicy ackPolicy;
        public boolean paused;
        public int maxPerSecond;
        public ValidationLevel validationLevel;
        public String profilePath;
        public String watchFolder;
        public TlsSettings tls;
        public String secretRef;
        public String notes;
        public String script;
        public String transport;
        public Map<String, String> transportOptions;
        public int appAckPort;
        public int appAckTimeoutMs;

        Builder(DestinationConfig d) {
            id = d.id;
            name = d.name;
            host = d.host;
            port = d.port;
            connectTimeoutMs = d.connectTimeoutMs;
            ackTimeoutMs = d.ackTimeoutMs;
            charset = d.charset;
            ackMode = d.ackMode;
            connectionMode = d.connectionMode;
            retry = d.retry;
            circuitBreaker = d.circuitBreaker;
            ackPolicy = d.ackPolicy;
            paused = d.paused;
            maxPerSecond = d.maxPerSecond;
            validationLevel = d.validationLevel;
            profilePath = d.profilePath;
            watchFolder = d.watchFolder;
            tls = d.tls;
            secretRef = d.secretRef;
            notes = d.notes;
            script = d.script;
            transport = d.transport;
            transportOptions = d.transportOptions;
            appAckPort = d.appAckPort;
            appAckTimeoutMs = d.appAckTimeoutMs;
        }

        DestinationConfig build() {
            return new DestinationConfig(id, name, host, port, connectTimeoutMs, ackTimeoutMs, charset, ackMode,
                    connectionMode, retry, circuitBreaker, ackPolicy, paused, maxPerSecond, validationLevel,
                    profilePath, watchFolder, tls, secretRef, notes, script, transport, transportOptions, appAckPort,
                    appAckTimeoutMs);
        }
    }
}
