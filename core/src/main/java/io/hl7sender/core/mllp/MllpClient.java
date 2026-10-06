package io.hl7sender.core.mllp;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import io.hl7sender.core.tls.TlsOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Blocking MLLP client for one TCP connection. Call {@link #connect()} first, then
 * {@link #send(String)} or {@link #sendAndReceive(String)} as many times as needed on the same connection.
 *
 * <p>Not thread-safe. One thread should own a client at a time.
 */
public final class MllpClient implements Closeable {

    private static final Logger LOG = LoggerFactory.getLogger(MllpClient.class);

    private final MllpClientConfig config;
    private final TlsOptions tls;
    private volatile Socket socket;
    private List<X509Certificate> peerCertificates = List.of();
    private volatile boolean aborted;
    private OutputStream out;
    private DeadlineInputStream in;
    private MllpFrameReader reader;

    public MllpClient(MllpClientConfig config) {
        this(config, null);
    }

    /** @param tls TLS options, or {@code null} for plain TCP */
    public MllpClient(MllpClientConfig config, TlsOptions tls) {
        this.config = config;
        this.tls = tls;
    }

    public boolean isTls() {
        return tls != null;
    }

    /** The server's certificate chain after a TLS handshake (empty for plain TCP). */
    public List<X509Certificate> peerCertificates() {
        return peerCertificates;
    }

    public MllpClientConfig config() {
        return config;
    }

    /**
     * Opens the TCP connection.
     *
     * @throws IOException for an unknown host, refused connection or connect timeout
     */
    public void connect() throws IOException {
        if (isConnected()) {
            return;
        }
        if (aborted) {
            throw new IOException("Connection to " + config.address() + " was aborted");
        }
        Socket s = new Socket();
        // Publish the socket before the blocking connect so abort() can interrupt it.
        this.socket = s;
        try {
            if (aborted) {
                throw new IOException("Connection to " + config.address() + " was aborted");
            }
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(config.host(), config.port()), config.connectTimeoutMs());
            Socket active = tls == null ? s : handshake(s);
            this.socket = active;
            this.out = new BufferedOutputStream(active.getOutputStream());
            this.in = new DeadlineInputStream(active, active.getInputStream());
            this.reader = new MllpFrameReader(in, config.maxFrameBytes());
            LOG.debug("Connected to {} from {}{}", config.address(), s.getLocalSocketAddress(),
                    tls == null ? "" : " using " + ((SSLSocket) active).getSession().getProtocol());
        } catch (IOException | RuntimeException e) {
            Socket current = this.socket;
            if (current != null && current != s) {
                closeQuietly(current);
            }
            closeQuietly(s);
            this.socket = null;
            throw e;
        }
    }

    private Socket handshake(Socket plain) throws IOException {
        SSLSocket ssl = (SSLSocket) tls.context().getSocketFactory()
                .createSocket(plain, config.host(), config.port(), true);
        this.socket = ssl;
        SSLParameters params = ssl.getSSLParameters();
        List<String> supported = List.of(ssl.getSupportedProtocols());
        String[] protocols = tls.protocols().stream().filter(supported::contains).toArray(String[]::new);
        if (protocols.length == 0) {
            throw new SSLException("None of the configured TLS protocols " + tls.protocols() + " is supported");
        }
        params.setProtocols(protocols);
        if (tls.verifyHostname()) {
            params.setEndpointIdentificationAlgorithm("HTTPS");
        }
        ssl.setSSLParameters(params);
        ssl.setSoTimeout(Math.max(1, config.connectTimeoutMs()));
        ssl.startHandshake();
        ssl.setSoTimeout(0);
        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate c : ssl.getSession().getPeerCertificates()) {
            if (c instanceof X509Certificate x) {
                chain.add(x);
            }
        }
        peerCertificates = List.copyOf(chain);
        return ssl;
    }

    public boolean isConnected() {
        Socket s = socket;
        return s != null && s.isConnected() && !s.isClosed() && reader != null;
    }

    /** Sends one message and does not wait for a response. */
    public void send(String message) throws IOException {
        requireConnected();
        out.write(Mllp.frame(message, config.charset()));
        out.flush();
    }

    /**
     * Sends one message and waits for the response frame.
     *
     * @return the decoded response payload
     * @throws java.net.SocketTimeoutException if no complete response arrives within the response timeout
     * @throws EOFException                    if the peer closes the connection without responding
     * @throws MllpProtocolException           if the response breaks MLLP framing
     */
    public String sendAndReceive(String message) throws IOException {
        send(message);
        return receive();
    }

    /** Waits for the next response frame, up to the configured response timeout. */
    public String receive() throws IOException {
        requireConnected();
        in.startDeadline(config.responseTimeoutMs());
        try {
            byte[] frame = reader.readFrame();
            if (frame == null) {
                throw new EOFException("Connection closed by " + config.address() + " before a response was received");
            }
            if (reader.discardedBytes() > 0) {
                LOG.warn("Ignored {} bytes received outside MLLP frames from {}",
                        reader.discardedBytes(), config.address());
            }
            return new String(frame, config.charset());
        } finally {
            in.clearDeadline();
        }
    }

    /**
     * Checks that an idle connection can still be used: the peer has not closed it and has not sent
     * anything unsolicited. Use it before reusing a persistent connection. Receivers often drop idle
     * connections, and writing into a half-closed socket would waste an attempt.
     *
     * @return false if the connection is closed, broken or has unexpected data waiting
     */
    public boolean probe() {
        if (!isConnected() || reader.hasBufferedBytes()) {
            return false;
        }
        try {
            if (socket.getInputStream().available() > 0) {
                return false;
            }
            socket.setSoTimeout(1);
            // Returns -1 if the peer closed the connection. Any byte is unsolicited data.
            socket.getInputStream().read();
            return false;
        } catch (java.net.SocketTimeoutException e) {
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                if (!socket.isClosed()) {
                    socket.setSoTimeout(0);
                }
            } catch (IOException ignored) {
                // Socket is unusable anyway; the next operation will fail and close it.
            }
        }
    }

    /**
     * Closes the socket from another thread to unblock a pending connect, write or read. That
     * operation then fails with an {@link IOException}, and so does any later {@link #connect()}. The
     * owning thread should still call {@link #close()}.
     */
    public void abort() {
        aborted = true;
        Socket s = socket;
        if (s != null) {
            closeQuietly(s);
        }
    }

    @Override
    public void close() {
        if (socket != null) {
            closeQuietly(socket);
            LOG.debug("Disconnected from {}", config.address());
        }
        socket = null;
        out = null;
        in = null;
        reader = null;
    }

    private void requireConnected() throws IOException {
        if (!isConnected()) {
            throw new IOException("Not connected to " + config.address());
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // Nothing useful to do.
        }
    }
}
