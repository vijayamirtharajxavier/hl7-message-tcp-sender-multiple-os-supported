package io.hl7sender.core.listener;

import io.hl7sender.core.ack.AckBuilder;
import io.hl7sender.core.ack.AckCode;
import io.hl7sender.core.hl7.Hl7FormatException;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.hl7.MessageHeader;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.mllp.Mllp;
import io.hl7sender.core.mllp.MllpFrameReader;
import io.hl7sender.core.template.TemplateEngine;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A mock MLLP receiver for testing senders. It accepts any number of concurrent connections, reads
 * messages, and answers each one according to the current {@link ListenerSettings}: ACK/NACK,
 * delay, silence, a malformed reply, or a dropped connection.
 *
 * <p>As a responder it simulates the other side of an interface: {@link ResponseRule}s choose the response by
 * message type and field values, can answer with a custom template, and can send a follow-up message back
 * (an ORU^R01 result for each ORM^O01 order, say) through a {@link FollowUpHandler}.
 *
 * <p>This is a test tool, not a production receiver. Messages are stored only in the optional save folder
 * and through the {@code onMessage} callback.
 */
public final class TestListener implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(TestListener.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    /** Receives follow-up messages produced by {@link ResponseRule#followUp()} rules. */
    @FunctionalInterface
    public interface FollowUpHandler {
        /**
         * Sends or queues {@code message} to the rule's follow-up destination. Called on its own thread after
         * the follow-up delay. Throwing is logged.
         */
        void followUp(ResponseRule rule, String message) throws Exception;
    }

    /** Settings with their rules compiled, swapped as one unit. */
    private record Active(ListenerSettings settings, List<ResponseRule.Compiled> rules) {
        static Active of(ListenerSettings s) {
            return new Active(s, s.rules().stream().map(ResponseRule::compile).toList());
        }
    }

    private final String bindAddress;
    private final int requestedPort;
    private final Consumer<ReceivedMessage> onMessage;
    private final AckBuilder ackBuilder = new AckBuilder();
    private final TemplateEngine templates = new TemplateEngine();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile Active active;
    private volatile FollowUpHandler followUps;
    private volatile ServerSocket server;
    private SSLContext tlsContext;
    private boolean requireClientCertificate;

    /**
     * @param bindAddress interface to listen on, e.g. {@code 0.0.0.0} for all or {@code 127.0.0.1} for local only
     * @param port        TCP port, or 0 to pick a free port (see {@link #port()})
     * @param settings    initial response behaviour
     * @param onMessage   called on a listener thread for each message, after the response is decided
     *                    and just before it is written
     */
    public TestListener(String bindAddress, int port, ListenerSettings settings, Consumer<ReceivedMessage> onMessage) {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Port must be between 0 and 65535");
        }
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.active = Active.of(settings);
        this.onMessage = onMessage;
    }

    /**
     * Accepts MLLP over TLS instead of plain TCP. Call before {@link #start()}.
     *
     * @param requireClientCertificate reject clients that do not present a trusted certificate (mutual TLS)
     */
    public synchronized TestListener useTls(SSLContext context, boolean requireClientCertificate) {
        if (server != null) {
            throw new IllegalStateException("Configure TLS before starting the listener");
        }
        this.tlsContext = context;
        this.requireClientCertificate = requireClientCertificate;
        return this;
    }

    public boolean isTls() {
        return tlsContext != null;
    }

    /** Binds the port and starts accepting connections. */
    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("Listener already started");
        }
        ServerSocket s;
        if (tlsContext != null) {
            SSLServerSocket ssl = (SSLServerSocket) tlsContext.getServerSocketFactory().createServerSocket();
            ssl.setNeedClientAuth(requireClientCertificate);
            s = ssl;
        } else {
            s = new ServerSocket();
        }
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), requestedPort));
        } catch (IOException e) {
            s.close();
            throw e;
        }
        server = s;
        Thread.ofPlatform().daemon().name("mllp-listener-" + s.getLocalPort()).start(this::acceptLoop);
        LOG.info("Test listener started on {}:{}{}", bindAddress, s.getLocalPort(),
                tlsContext == null ? "" : requireClientCertificate ? " (TLS, client certificate required)" : " (TLS)");
    }

    public boolean isRunning() {
        ServerSocket s = server;
        return s != null && !s.isClosed();
    }

    /** The bound port. Only valid after {@link #start()}. */
    public int port() {
        ServerSocket s = server;
        if (s == null) {
            throw new IllegalStateException("Listener not started");
        }
        return s.getLocalPort();
    }

    public ListenerSettings settings() {
        return active.settings();
    }

    public void updateSettings(ListenerSettings newSettings) {
        this.active = Active.of(newSettings);
    }

    /** Where follow-up messages go; without a handler they are logged and dropped. */
    public void setFollowUpHandler(FollowUpHandler handler) {
        this.followUps = handler;
    }

    /**
     * Closes every open client connection but keeps listening. This simulates a receiver dropping idle
     * connections, as interface engines and firewalls do.
     */
    public void disconnectClients() {
        for (Socket c : clients) {
            closeQuietly(c);
        }
    }

    /** Stops listening and closes all open connections. */
    @Override
    public synchronized void close() {
        ServerSocket s = server;
        server = null;
        if (s == null) {
            return;
        }
        closeQuietly(s);
        for (Socket c : clients) {
            closeQuietly(c);
        }
        clients.clear();
        LOG.info("Test listener on port {} stopped", s.getLocalPort());
    }

    private void acceptLoop() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try {
                Socket client = s.accept();
                clients.add(client);
                Thread.ofVirtual().name("mllp-conn-" + client.getRemoteSocketAddress()).start(() -> serve(client));
            } catch (IOException e) {
                if (!s.isClosed()) {
                    LOG.warn("Accept failed: {}", e.getMessage());
                }
            }
        }
    }

    private void serve(Socket client) {
        String remote = describe(client);
        LOG.debug("Connection from {}", remote);
        try (client) {
            MllpFrameReader reader = new MllpFrameReader(client.getInputStream(), settings().maxFrameBytes());
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            byte[] frame;
            while ((frame = reader.readFrame()) != null) {
                if (!handle(frame, active, remote, client.getInetAddress(), out)) {
                    break;
                }
            }
        } catch (SocketException e) {
            LOG.debug("Connection from {} ended: {}", remote, e.getMessage());
        } catch (IOException e) {
            LOG.warn("Connection from {} failed: {}", remote, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            clients.remove(client);
        }
    }

    /** Handles one frame. Returns false if the connection should be closed. */
    private boolean handle(byte[] frame, Active a, String remote, InetAddress remoteAddress, OutputStream out)
            throws IOException, InterruptedException {
        ListenerSettings s = a.settings();
        Instant receivedAt = Instant.now();
        String payload = new String(frame, s.charset());
        ParsedMessage parsed = tryParse(payload);
        MessageHeader header = parsed == null ? null : parsed.header();
        ResponseRule rule = parsed == null ? null : a.rules().stream().filter(r -> r.matches(parsed)).findFirst()
                .map(ResponseRule.Compiled::rule).orElse(null);
        ResponseMode mode = rule == null ? s.mode() : rule.mode();
        int delayMs = rule == null ? s.delayMs() : rule.delayMs();
        String text = rule == null ? s.responseText() : rule.responseText();

        if (s.saveFolder() != null) {
            save(s.saveFolder(), receivedAt, header, frame);
        }
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }

        String response = null;
        String code;
        boolean keepOpen = true;
        switch (mode) {
            case ACCEPT, ERROR, REJECT, WRONG_CONTROL_ID -> {
                AckCode ackCode = codeFor(mode, s.commitCodes(), parsed == null);
                if (parsed == null) {
                    response = ackBuilder.buildForUnparseable(ackCode, "Unable to parse message");
                } else {
                    String override = mode == ResponseMode.WRONG_CONTROL_ID ? "WRONG-" + header.controlId() : null;
                    response = ackBuilder.build(parsed, ackCode, text, override);
                }
                code = ackCode.name();
            }
            case CUSTOM -> {
                response = Hl7Text.normalize(templates.expand(rule.customResponse(), parsed).text());
                code = customCode(response);
            }
            case MALFORMED -> {
                response = "THIS IS NOT AN HL7 ACKNOWLEDGMENT\r";
                code = "malformed";
            }
            case NO_RESPONSE -> code = "none";
            case CLOSE_CONNECTION -> {
                code = "closed";
                keepOpen = false;
            }
            default -> throw new IllegalStateException("Unhandled mode " + mode);
        }

        LOG.info("Received {} [{}] from {} -> {}{}",
                header == null ? "unparseable message" : header.messageType(),
                header == null ? "" : header.controlId(), remote, code,
                rule == null ? "" : " (rule '" + rule.name() + "')");
        // Notify before responding: once a sender has its ACK, the message is guaranteed to be visible
        // to observers. This avoids races in tests and in the UI's received-messages table.
        notifyListener(new ReceivedMessage(
                receivedAt,
                remote,
                payload,
                header == null ? "" : header.messageType(),
                header == null ? "" : header.controlId(),
                mode,
                code,
                Optional.ofNullable(response),
                rule == null ? "" : rule.name()));
        if (response != null) {
            out.write(Mllp.frame(response, s.charset()));
            out.flush();
        }
        if (rule != null && rule.followUp() != null) {
            scheduleFollowUp(rule, parsed);
        }
        if (s.appAck() != null && "CA".equals(code) && parsed != null) {
            scheduleApplicationAck(s, parsed, remoteAddress);
        }
        return keepOpen;
    }

    /**
     * True if a receiver in enhanced mode sends {@code code} as the application ACK for a message with MSH-16
     * {@code applicationAckType}: AL always, ER only errors, SU only success, NE or empty never.
     */
    static boolean sendsApplicationAck(String applicationAckType, AckCode code) {
        return switch (applicationAckType.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "AL" -> true;
            case "ER" -> code != AckCode.AA;
            case "SU" -> code == AckCode.AA;
            default -> false;
        };
    }

    /** Sends the application ACK for {@code original} to the sender's application ACK port, after the delay. */
    private void scheduleApplicationAck(ListenerSettings s, ParsedMessage original, InetAddress sender) {
        ListenerSettings.AppAck appAck = s.appAck();
        if (!sendsApplicationAck(original.header().applicationAckType(), appAck.code())) {
            return;
        }
        String ack = ackBuilder.build(original, appAck.code(), s.responseText(), null);
        Thread.ofVirtual().name("app-ack-" + original.header().controlId()).start(() -> {
            try {
                if (appAck.delayMs() > 0) {
                    Thread.sleep(appAck.delayMs());
                }
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(sender, appAck.port()), 10_000);
                    socket.setSoTimeout(10_000);
                    OutputStream out = socket.getOutputStream();
                    out.write(Mllp.frame(ack, s.charset()));
                    out.flush();
                    byte[] reply = new MllpFrameReader(socket.getInputStream(), s.maxFrameBytes()).readFrame();
                    LOG.info("Sent application ACK {} for [{}] to {}:{}{}", appAck.code(),
                            original.header().controlId(), sender.getHostAddress(), appAck.port(),
                            reply == null ? "" : " -> " + customCode(new String(reply, s.charset())));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOG.warn("Sending the application ACK for [{}] to {}:{} failed: {}", original.header().controlId(),
                        sender.getHostAddress(), appAck.port(), e.getMessage());
            }
        });
    }

    private void scheduleFollowUp(ResponseRule rule, ParsedMessage inbound) {
        ResponseRule.FollowUp f = rule.followUp();
        Thread.ofVirtual().name("follow-up-" + rule.name()).start(() -> {
            try {
                if (f.delayMs() > 0) {
                    Thread.sleep(f.delayMs());
                }
                FollowUpHandler handler = followUps;
                if (handler == null) {
                    LOG.warn("Rule '{}': no follow-up handler, so the follow-up message was not sent", rule.name());
                    return;
                }
                handler.followUp(rule, templates.expand(f.template(), inbound).text());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOG.warn("Rule '{}': follow-up message to '{}' failed: {}", rule.name(), f.destination(),
                        e.getMessage());
            }
        });
    }

    /** Writes a received message to {@code folder}, one file per message; failures are logged. */
    private static void save(Path folder, Instant receivedAt, MessageHeader header, byte[] frame) {
        String id = header == null || header.controlId().isEmpty() ? "unparseable"
                : header.controlId().replaceAll("[^A-Za-z0-9._-]", "_");
        String base = FILE_TIME.format(receivedAt) + "-" + id;
        try {
            Files.createDirectories(folder);
            Path file = folder.resolve(base + ".hl7");
            for (int i = 2; Files.exists(file); i++) {
                file = folder.resolve(base + "-" + i + ".hl7");
            }
            Files.write(file, frame, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            LOG.warn("Cannot save the received message to {}: {}", folder, e.getMessage());
        }
    }

    /** MSA-1 of a custom response, for the received-messages list. */
    private static String customCode(String response) {
        ParsedMessage p = tryParse(response);
        if (p == null) {
            return "custom";
        }
        return p.first("MSA").map(m -> m.field(1)).filter(c -> !c.isEmpty()).orElse("custom");
    }

    private static AckCode codeFor(ResponseMode mode, boolean commitCodes, boolean unparseable) {
        AckCode.Category category = switch (mode) {
            case ERROR -> AckCode.Category.ERROR;
            case REJECT -> AckCode.Category.REJECT;
            default -> AckCode.Category.ACCEPT;
        };
        if (unparseable) {
            category = AckCode.Category.REJECT;
        }
        return AckCode.of(category, commitCodes);
    }

    private void notifyListener(ReceivedMessage message) {
        if (onMessage == null) {
            return;
        }
        try {
            onMessage.accept(message);
        } catch (RuntimeException e) {
            LOG.warn("Message callback failed", e);
        }
    }

    private static ParsedMessage tryParse(String payload) {
        try {
            return ParsedMessage.parse(payload);
        } catch (Hl7FormatException | IllegalArgumentException e) {
            return null;
        }
    }

    private static String describe(Socket s) {
        InetSocketAddress a = (InetSocketAddress) s.getRemoteSocketAddress();
        return a == null ? "unknown" : a.getAddress().getHostAddress() + ":" + a.getPort();
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}
