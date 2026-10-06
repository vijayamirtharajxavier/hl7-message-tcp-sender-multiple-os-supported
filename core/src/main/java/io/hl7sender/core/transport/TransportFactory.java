package io.hl7sender.core.transport;

import io.hl7sender.core.queue.DestinationConfig;
import java.util.List;
import java.util.Map;

/**
 * A kind of destination, such as HTTP or a file folder. Plugins provide more (SFTP, a message broker, ...) by
 * implementing this interface and listing the class in {@code META-INF/services/io.hl7sender.core.transport
 * .TransportFactory} in a jar placed in the {@code plugins} folder under the settings folder.
 */
public interface TransportFactory {

    /** Stored with destinations, e.g. {@code http}. Lower case letters, digits and dashes. */
    String id();

    /** Shown in the destination editor, e.g. {@code HTTP(S) POST}. */
    String displayName();

    /** Settings this transport needs. */
    List<TransportOption> options();

    /**
     * Checks the settings before a destination is saved.
     *
     * @throws IllegalArgumentException with a message for the user if they are not valid
     */
    default void validate(Map<String, String> options) {
        for (TransportOption o : options()) {
            if (o.required() && options.getOrDefault(o.key(), "").isBlank()) {
                throw new IllegalArgumentException(displayName() + ": " + o.label() + " is required");
            }
        }
    }

    /** A short description of where messages go, e.g. the URL, for lists and logs. */
    default String describe(Map<String, String> options) {
        return options().isEmpty() ? id() : id() + ":" + options.getOrDefault(options().get(0).key(), "");
    }

    /** Opens a transport for {@code destination}; called again after its settings change. */
    Transport open(DestinationConfig destination, TransportContext context) throws Exception;
}
