package io.hl7sender.core.update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Comparing versions and reading the latest release. */
class UpdateCheckerTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("{}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> userAgent = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/latest", ex -> {
            userAgent.set(ex.getRequestHeaders().getFirst("User-Agent"));
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private UpdateChecker checker(String current) {
        return new UpdateChecker("http://127.0.0.1:" + server.getAddress().getPort() + "/latest", current);
    }

    @Test
    void semanticVersionPrecedence() {
        assertThat(UpdateChecker.compare("1.0.0", "1.0.0")).isZero();
        assertThat(UpdateChecker.compare("v1.0.1", "1.0.0")).isPositive();
        assertThat(UpdateChecker.compare("1.10.0", "1.9.9")).isPositive();
        assertThat(UpdateChecker.compare("2.0.0", "10.0.0")).isNegative();
        assertThat(UpdateChecker.compare("1.0.0", "1.0.0-SNAPSHOT")).isPositive();
        assertThat(UpdateChecker.compare("1.0.0-rc.2", "1.0.0-rc.10")).isNegative();
        assertThat(UpdateChecker.compare("1.0.0-alpha", "1.0.0-alpha.1")).isNegative();
        assertThat(UpdateChecker.compare("1.0.0-alpha.beta", "1.0.0-alpha.1")).isPositive();
        assertThat(UpdateChecker.compare("1.0.0+build.5", "1.0.0")).isZero();
        assertThat(UpdateChecker.isNewer("1.0.0", "dev")).isFalse();
        assertThatThrownBy(() -> UpdateChecker.compare("latest", "1.0.0")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsANewerRelease() throws IOException {
        body.set("{\"tag_name\":\"v1.2.0\",\"html_url\":\"https://example.org/r/1.2.0\",\"body\":\"- Faster\","
                + "\"published_at\":\"2026-10-01T09:00:00Z\"}");
        UpdateChecker.Result r = checker("1.1.3").check();
        assertThat(r.newer()).isTrue();
        assertThat(r.latest().version()).isEqualTo("1.2.0");
        assertThat(r.latest().pageUrl()).isEqualTo("https://example.org/r/1.2.0");
        assertThat(r.latest().notes()).isEqualTo("- Faster");
        assertThat(r.latest().publishedAt()).isPresent();
        assertThat(userAgent.get()).isEqualTo("HL7-Sender/1.1.3");

        assertThat(checker("1.2.0").check().newer()).isFalse();
        assertThat(checker("1.2.0-SNAPSHOT").check().newer()).isTrue();
        assertThat(checker("1.3.0").check().newer()).isFalse();
    }

    @Test
    void errorsAreReported() {
        status.set(404);
        assertThatThrownBy(() -> checker("1.0.0").check()).hasMessage("No release has been published yet");
        status.set(503);
        assertThatThrownBy(() -> checker("1.0.0").check()).hasMessage("Update server returned HTTP 503");
        status.set(200);
        body.set("{\"tag_name\":\"nightly\"}");
        assertThatThrownBy(() -> checker("1.0.0").check()).hasMessage("Unexpected release version: nightly");
    }
}
