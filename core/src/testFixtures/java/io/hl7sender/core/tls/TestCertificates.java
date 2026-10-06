package io.hl7sender.core.tls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Generates real certificates with the JDK's {@code keytool} for TLS tests: a server certificate for
 * localhost/127.0.0.1, a client certificate, a short-lived certificate, and PEM exports of each.
 */
public final class TestCertificates {

    public static final String PASSWORD = "changeit";

    public final Path dir;
    /** Server key store (PKCS12), CN=localhost, SAN dns:localhost, ip:127.0.0.1. */
    public final Path serverKeyStore;
    /** Server certificate (PEM), for clients to trust. */
    public final Path serverCert;
    /** Client key store (PKCS12), CN=hl7sender-client. */
    public final Path clientKeyStore;
    /** Client certificate (PEM), for the server to trust. */
    public final Path clientCert;
    /** A certificate that expires in 10 days (PEM). */
    public final Path expiringCert;
    /** A server key store whose certificate is for another host name. */
    public final Path wrongHostKeyStore;
    /** The certificate of {@link #wrongHostKeyStore} (PEM). */
    public final Path wrongHostCert;

    public TestCertificates(Path dir) throws IOException, InterruptedException {
        this.dir = dir;
        serverKeyStore = dir.resolve("server.p12");
        serverCert = dir.resolve("server.pem");
        clientKeyStore = dir.resolve("client.p12");
        clientCert = dir.resolve("client.pem");
        expiringCert = dir.resolve("expiring.pem");
        wrongHostKeyStore = dir.resolve("wronghost.p12");
        wrongHostCert = dir.resolve("wronghost.pem");
        generate(serverKeyStore, "CN=localhost", "SAN=dns:localhost,ip:127.0.0.1", 365);
        export(serverKeyStore, serverCert);
        generate(clientKeyStore, "CN=hl7sender-client", null, 365);
        export(clientKeyStore, clientCert);
        Path expiringStore = dir.resolve("expiring.p12");
        generate(expiringStore, "CN=soon-expiring", "SAN=dns:localhost,ip:127.0.0.1", 10);
        export(expiringStore, expiringCert);
        generate(wrongHostKeyStore, "CN=other.example", "SAN=dns:other.example", 365);
        export(wrongHostKeyStore, wrongHostCert);
    }

    private void generate(Path store, String dname, String ext, int days) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(keytool(), "-genkeypair", "-alias", "key", "-keyalg", "RSA",
                "-keysize", "2048", "-dname", dname, "-validity", String.valueOf(days), "-keystore", store.toString(),
                "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD, "-noprompt"));
        if (ext != null) {
            cmd.add("-ext");
            cmd.add(ext);
        }
        run(cmd);
    }

    private void export(Path store, Path pem) throws IOException, InterruptedException {
        run(List.of(keytool(), "-exportcert", "-rfc", "-alias", "key", "-keystore", store.toString(), "-storetype",
                "PKCS12", "-storepass", PASSWORD, "-file", pem.toString()));
    }

    private static void run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("keytool failed: " + out);
        }
    }

    private static String keytool() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        Path exe = bin.resolve("keytool.exe");
        return Files.exists(exe) ? exe.toString() : bin.resolve("keytool").toString();
    }
}
