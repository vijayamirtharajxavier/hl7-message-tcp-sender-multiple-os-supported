package io.hl7sender.core.transport;

/**
 * A setting a transport needs, shown in the destination editor and stored with the destination.
 *
 * @param key          stored name, e.g. {@code url}
 * @param label        shown in the editor, e.g. {@code URL}
 * @param help         one line of help, or empty
 * @param defaultValue value for new destinations, or empty
 * @param required     the destination cannot be saved without it
 * @param multiline    edited in a text area (e.g. HTTP headers, one per line)
 */
public record TransportOption(String key, String label, String help, String defaultValue, boolean required,
                              boolean multiline) {

    public static TransportOption required(String key, String label, String help) {
        return new TransportOption(key, label, help, "", true, false);
    }

    public static TransportOption optional(String key, String label, String help, String defaultValue) {
        return new TransportOption(key, label, help, defaultValue, false, false);
    }

    public TransportOption asMultiline() {
        return new TransportOption(key, label, help, defaultValue, required, true);
    }
}
