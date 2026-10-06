package io.hl7sender.core.mllp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class MllpClientTest {

    /** Accepts one connection, reads a frame and runs {@code responder} on the socket. */
    private static CompletableFuture<Void> serveOnce(ServerSocket server, SocketAction responder) {
        return CompletableFuture.runAsync(() -> {
            try (Socket s = server.accept()) {
                new MllpFrameReader(s.getInputStream()).readFrame();
                responder.run(s);
            } catch (IOException e) {
                // Test peer: the client may close first.
            }
        });
    }

    @FunctionalInterface
    private interface SocketAction {
        void run(Socket s) throws IOException;
    }

    @Test
    void sendsAndReceivesResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> peer = serveOnce(server, s ->
                    s.getOutputStream().write(Mllp.frame("MSH|^~\\&|ACK\rMSA|AA|1\r", StandardCharsets.UTF_8)));
            try (MllpClient client = new MllpClient(MllpClientConfig.of("127.0.0.1", server.getLocalPort()))) {
                client.connect();
                assertThat(client.sendAndReceive("MSH|^~\\&|X\r")).isEqualTo("MSH|^~\\&|ACK\rMSA|AA|1\r");
            }
            peer.join();
        }
    }

    @Test
    void responseTimeoutIsATotalDeadlineNotPerRead() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            // Trickle one byte every 100 ms, never finishing the frame. A per-read timeout of 500 ms
            // would never fire; the total deadline must.
            serveOnce(server, s -> {
                OutputStream out = s.getOutputStream();
                out.write(Mllp.START_BLOCK);
                for (int i = 0; i < 100; i++) {
                    out.write('x');
                    out.flush();
                    sleep(100);
                }
            });
            MllpClientConfig config = MllpClientConfig.of("127.0.0.1", server.getLocalPort()).withTimeouts(2000, 500);
            try (MllpClient client = new MllpClient(config)) {
                client.connect();
                long start = System.nanoTime();
                assertThatThrownBy(() -> client.sendAndReceive("MSH|^~\\&|X\r"))
                        .isInstanceOf(SocketTimeoutException.class);
                assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2000);
            }
        }
    }

    @Test
    void reportsPeerClosingWithoutResponse() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            serveOnce(server, s -> { });
            try (MllpClient client = new MllpClient(MllpClientConfig.of("127.0.0.1", server.getLocalPort()))) {
                client.connect();
                assertThatThrownBy(() -> client.sendAndReceive("MSH|^~\\&|X\r")).isInstanceOf(EOFException.class);
            }
        }
    }

    @Test
    void validatesConfig() {
        assertThatThrownBy(() -> MllpClientConfig.of("", 2575)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MllpClientConfig.of("h", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MllpClientConfig.of("h", 70000)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
