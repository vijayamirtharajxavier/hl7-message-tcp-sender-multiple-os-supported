package io.hl7sender.core.transport;

import io.hl7sender.core.tls.TlsOptions;
import java.util.Optional;

/**
 * What the delivery engine gives a transport when it opens it.
 *
 * @param tls the destination's TLS settings with passwords loaded, if TLS is enabled for it
 */
public record TransportContext(Optional<TlsOptions> tls) {
}
