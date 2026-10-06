package io.hl7sender.core.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Builds SSL contexts from key store files and inspects their certificates.
 *
 * <p>Trust stores may be PKCS12, JKS, or PEM files ({@code .pem/.crt/.cer}, one or more certificates).
 * Key stores (client or server certificate with private key) must be PKCS12 or JKS.
 */
public final class TlsContexts {

    private TlsContexts() {
    }

    /** A client context: trusts {@code trustStore} (or the JVM defaults) and presents {@code keyStore} if given. */
    public static TlsOptions client(TlsSettings settings, char[] trustPassword, char[] keyPassword)
            throws IOException {
        try {
            TrustManager[] trust = trustManagers(settings.trustStorePath(), trustPassword);
            KeyManager[] keys = settings.keyStorePath().isEmpty() ? null
                    : keyManagers(Path.of(settings.keyStorePath()), keyPassword);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keys, trust, null);
            return new TlsOptions(ctx, settings.verifyHostname(), settings.protocolList());
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS setup failed: " + e.getMessage(), e);
        }
    }

    /** A server context presenting {@code keyStore}; {@code trustStore} verifies client certificates (may be empty). */
    public static SSLContext server(Path keyStore, char[] keyPassword, String trustStore, char[] trustPassword)
            throws IOException {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(keyManagers(keyStore, keyPassword), trustManagers(trustStore, trustPassword), null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS setup failed: " + e.getMessage(), e);
        }
    }

    /** Loads a PKCS12, JKS or PEM file. PEM files contain certificates only. */
    public static KeyStore load(Path file, char[] password) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("File not found: " + file);
        }
        try {
            if (isPem(file)) {
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                try (InputStream in = Files.newInputStream(file)) {
                    Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509")
                            .generateCertificates(in);
                    if (certs.isEmpty()) {
                        throw new IOException("No certificates in " + file);
                    }
                    int i = 0;
                    for (Certificate c : certs) {
                        ks.setCertificateEntry("cert-" + i++, c);
                    }
                }
                return ks;
            }
            IOException first = null;
            for (String type : List.of("PKCS12", "JKS")) {
                try (InputStream in = Files.newInputStream(file)) {
                    KeyStore ks = KeyStore.getInstance(type);
                    ks.load(in, password);
                    return ks;
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    }
                }
            }
            throw new IOException("Cannot open " + file.getFileName() + " (wrong password or not a PKCS12/JKS/PEM "
                    + "file): " + (first == null ? "" : first.getMessage()), first);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** Every certificate in a store file. */
    public static List<CertificateInfo> inspect(Path file, char[] password, String source) throws IOException {
        KeyStore ks = load(file, password);
        List<CertificateInfo> out = new ArrayList<>();
        try {
            for (String alias : Collections.list(ks.aliases())) {
                Certificate[] chain = ks.isKeyEntry(alias) ? ks.getCertificateChain(alias)
                        : new Certificate[] {ks.getCertificate(alias)};
                if (chain == null) {
                    continue;
                }
                for (Certificate c : chain) {
                    if (c instanceof X509Certificate x) {
                        out.add(info(source, alias, x));
                    }
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot read " + file + ": " + e.getMessage(), e);
        }
        return out;
    }

    public static CertificateInfo info(String source, String alias, X509Certificate x) {
        return new CertificateInfo(source, alias, x.getSubjectX500Principal().getName(),
                x.getIssuerX500Principal().getName(), x.getNotBefore().toInstant(), x.getNotAfter().toInstant());
    }

    private static TrustManager[] trustManagers(String trustStore, char[] password)
            throws IOException, GeneralSecurityException {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore == null || trustStore.isEmpty() ? null : load(Path.of(trustStore), password));
        return tmf.getTrustManagers();
    }

    private static KeyManager[] keyManagers(Path keyStore, char[] password)
            throws IOException, GeneralSecurityException {
        if (isPem(keyStore)) {
            throw new IOException("Client and server certificates need a PKCS12 or JKS key store (with the private "
                    + "key); PEM is only supported for trusted certificates");
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(load(keyStore, password), password == null ? new char[0] : password);
        return kmf.getKeyManagers();
    }

    private static boolean isPem(Path file) {
        String n = String.valueOf(file.getFileName()).toLowerCase(Locale.ROOT);
        return n.endsWith(".pem") || n.endsWith(".crt") || n.endsWith(".cer");
    }
}
