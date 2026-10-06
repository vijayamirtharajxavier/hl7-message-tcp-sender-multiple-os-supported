package io.hl7sender.core.tls;

import java.util.Arrays;
import java.util.List;

/**
 * Persisted TLS settings for a destination. Passwords are not part of this record. They live in the
 * {@link io.hl7sender.core.secrets.SecretStore}.
 *
 * @param enabled        use TLS (MLLP over TLS)
 * @param trustStorePath certificates to trust (PKCS12, JKS or PEM); empty = the JVM's default trust store
 * @param keyStorePath   client certificate and key for mutual TLS (PKCS12 or JKS); empty = none
 * @param verifyHostname check that the server certificate matches the host name or IP address
 * @param protocols      allowed protocol versions, comma-separated, e.g. {@code TLSv1.3,TLSv1.2}
 */
public record TlsSettings(boolean enabled, String trustStorePath, String keyStorePath, boolean verifyHostname,
                          String protocols) {

    public static final String DEFAULT_PROTOCOLS = "TLSv1.3,TLSv1.2";
    public static final TlsSettings DISABLED = new TlsSettings(false, "", "", true, DEFAULT_PROTOCOLS);

    public TlsSettings {
        trustStorePath = trustStorePath == null ? "" : trustStorePath.trim();
        keyStorePath = keyStorePath == null ? "" : keyStorePath.trim();
        protocols = protocols == null || protocols.isBlank() ? DEFAULT_PROTOCOLS : protocols.replace(" ", "");
    }

    public List<String> protocolList() {
        return Arrays.stream(protocols.split(",")).filter(p -> !p.isBlank()).toList();
    }

    public boolean mutual() {
        return !keyStorePath.isEmpty();
    }
}
