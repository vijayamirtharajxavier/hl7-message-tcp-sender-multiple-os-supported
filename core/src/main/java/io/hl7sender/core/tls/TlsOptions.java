package io.hl7sender.core.tls;

import java.util.List;
import javax.net.ssl.SSLContext;

/**
 * A ready-to-use TLS client configuration.
 *
 * @param context        SSL context with the trust store and (optional) client key loaded
 * @param verifyHostname check the server certificate against the host
 * @param protocols      allowed protocol versions
 */
public record TlsOptions(SSLContext context, boolean verifyHostname, List<String> protocols) {

    public TlsOptions {
        protocols = List.copyOf(protocols);
    }
}
