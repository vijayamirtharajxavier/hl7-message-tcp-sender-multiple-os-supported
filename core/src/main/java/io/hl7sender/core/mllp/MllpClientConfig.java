package io.hl7sender.core.mllp;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Connection settings for {@link MllpClient}.
 *
 * @param host              remote host name or IP address
 * @param port              remote TCP port (1–65535)
 * @param connectTimeoutMs  maximum time to establish the TCP connection
 * @param responseTimeoutMs maximum total time to wait for the complete response frame
 * @param charset           character set used to encode messages and decode responses
 * @param maxFrameBytes     largest response frame that is accepted
 */
public record MllpClientConfig(
        String host,
        int port,
        int connectTimeoutMs,
        int responseTimeoutMs,
        Charset charset,
        int maxFrameBytes) {

    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_RESPONSE_TIMEOUT_MS = 30_000;

    public MllpClientConfig {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(charset, "charset");
        if (host.isBlank()) {
            throw new IllegalArgumentException("Host is required");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535");
        }
        if (connectTimeoutMs < 0 || responseTimeoutMs < 0) {
            throw new IllegalArgumentException("Timeouts cannot be negative");
        }
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        host = host.trim();
    }

    public static MllpClientConfig of(String host, int port) {
        return new MllpClientConfig(host, port, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_RESPONSE_TIMEOUT_MS,
                StandardCharsets.UTF_8, Mllp.DEFAULT_MAX_FRAME_BYTES);
    }

    public MllpClientConfig withTimeouts(int connectMs, int responseMs) {
        return new MllpClientConfig(host, port, connectMs, responseMs, charset, maxFrameBytes);
    }

    public MllpClientConfig withCharset(Charset newCharset) {
        return new MllpClientConfig(host, port, connectTimeoutMs, responseTimeoutMs, newCharset, maxFrameBytes);
    }

    public String address() {
        return host + ":" + port;
    }
}
