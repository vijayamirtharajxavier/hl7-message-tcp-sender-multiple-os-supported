package io.hl7sender.core.queue;

import io.hl7sender.core.ack.AckBuilder;
import io.hl7sender.core.ack.AckCode;
import io.hl7sender.core.ack.AckParseException;
import io.hl7sender.core.ack.AckParser;
import io.hl7sender.core.ack.ParsedAck;
import io.hl7sender.core.hl7.Hl7FormatException;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.mllp.Mllp;
import io.hl7sender.core.mllp.MllpFrameReader;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens on a destination's application ACK port for the acknowledgments its receiver sends later in enhanced
 * mode (MSH-16 AL, ER or SU), and hands each one to the engine, which matches it to the waiting message by MSA-2.
 *
 * <p>Only connections from the destination's host (any of its addresses) or from this machine are accepted, so
 * another system on the network cannot complete or fail messages. Each ACK is answered with a commit accept (CA),
 * or AA if it has no MSH-15, and not at all if its MSH-15 is NE. Anything that is not an acknowledgment is
 * rejected.
 */
final class ApplicationAckListener implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationAckListener.class);

    private final long destinationId;
    private final int port;
    private final DeliveryEngine engine;
    private final AckBuilder ackBuilder = new AckBuilder();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private volatile ServerSocket server;

    ApplicationAckListener(long destinationId, int port, DeliveryEngine engine) {
        this.destinationId = destinationId;
        this.port = port;
        this.engine = engine;
    }

    int port() {
        return port;
    }

    /** Binds the port on all interfaces and starts accepting connections. */
    synchronized void start() throws IOException {
        ServerSocket s = new ServerSocket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            s.close();
            throw e;
        }
        server = s;
        Thread.ofPlatform().daemon().name("app-ack-" + destinationId + "-" + port).start(this::acceptLoop);
        LOG.info("Listening for application ACKs for destination {} on port {}", destinationId, port);
    }

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
        LOG.info("Stopped listening for application ACKs for destination {} on port {}", destinationId, port);
    }

    private void acceptLoop() {
        ServerSocket s = server;
        while (s != null && !s.isClosed()) {
            try {
                Socket client = s.accept();
                clients.add(client);
                Thread.ofVirtual().name("app-ack-conn-" + client.getRemoteSocketAddress()).start(() -> serve(client));
            } catch (IOException e) {
                if (!s.isClosed()) {
                    LOG.warn("Application ACK listener on port {}: accept failed: {}", port, e.getMessage());
                }
            }
        }
    }

    private void serve(Socket client) {
        InetAddress remoteAddress = client.getInetAddress();
        String remote = remoteAddress.getHostAddress();
        try (client) {
            Optional<DestinationConfig> config = engine.store().destination(destinationId);
            if (config.isEmpty()) {
                return;
            }
            if (!allowed(remoteAddress, config.get().host())) {
                String detail = "Refused an application ACK connection from " + remote + " on port " + port
                        + ": only " + config.get().host() + " may send application ACKs for this destination";
                LOG.warn("Destination '{}': {}", config.get().name(), detail);
                engine.store().auditDestination(destinationId, QueueStore.ACTOR_ENGINE, detail);
                return;
            }
            Charset charset = Charset.forName(config.get().charset());
            MllpFrameReader reader = new MllpFrameReader(client.getInputStream(), Mllp.DEFAULT_MAX_FRAME_BYTES);
            OutputStream out = new BufferedOutputStream(client.getOutputStream());
            byte[] frame;
            while ((frame = reader.readFrame()) != null) {
                String reply = handle(new String(frame, charset), remote);
                if (reply != null) {
                    out.write(Mllp.frame(reply, charset));
                    out.flush();
                }
            }
        } catch (SocketException e) {
            LOG.debug("Application ACK connection from {} ended: {}", remote, e.getMessage());
        } catch (IOException | RuntimeException e) {
            LOG.warn("Application ACK connection from {} failed: {}", remote, e.getMessage());
        } finally {
            clients.remove(client);
        }
    }

    /** Applies one received message and returns the reply to send, or null for none. */
    private String handle(String payload, String remote) {
        ParsedMessage parsed;
        try {
            parsed = ParsedMessage.parse(payload);
        } catch (Hl7FormatException | IllegalArgumentException e) {
            return ackBuilder.buildForUnparseable(AckCode.CR, "Unable to parse message");
        }
        ParsedAck ack;
        try {
            ack = AckParser.parse(payload);
        } catch (AckParseException e) {
            return ackBuilder.build(parsed, AckCode.CR,
                    "Only acknowledgments (MSA segment) are accepted on this port", null);
        }
        engine.applyApplicationAck(destinationId, ack, remote);
        String acceptAckType = parsed.header().acceptAckType().toUpperCase(Locale.ROOT);
        if (acceptAckType.equals("NE")) {
            return null;
        }
        return ackBuilder.build(parsed, acceptAckType.isEmpty() ? AckCode.AA : AckCode.CA, "", null);
    }

    /** True for this machine, or for any address of {@code host}. */
    static boolean allowed(InetAddress remote, String host) {
        if (remote.isLoopbackAddress()) {
            return true;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.equals(remote)) {
                    return true;
                }
            }
        } catch (UnknownHostException e) {
            LOG.debug("Cannot resolve {}: {}", host, e.getMessage());
        }
        return false;
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}
