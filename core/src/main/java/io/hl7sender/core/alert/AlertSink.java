package io.hl7sender.core.alert;

import java.io.IOException;

/** A channel that delivers alerts, such as a webhook or e-mail. */
public interface AlertSink {

    /** Short name for status messages, e.g. {@code Webhook}. */
    String name();

    /** Delivers the alert, or throws with a message that says why it could not. */
    void send(Alert alert) throws IOException;
}
