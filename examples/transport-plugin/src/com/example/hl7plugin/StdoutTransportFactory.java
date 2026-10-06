package com.example.hl7plugin;

import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.transport.Transport;
import io.hl7sender.core.transport.TransportContext;
import io.hl7sender.core.transport.TransportFactory;
import io.hl7sender.core.transport.TransportOption;
import io.hl7sender.core.transport.Transports;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * An example transport plugin: prints each message to standard output with a prefix. A real plugin (SFTP, a
 * message broker, a cloud queue) has the same shape: an id, its settings, and a Transport that sends one message
 * and reports the outcome. Never throw for delivery problems; return CONNECTION_FAILED or SEND_FAILED to retry,
 * APPLICATION_ERROR to dead-letter.
 */
public final class StdoutTransportFactory implements TransportFactory {

    @Override
    public String id() {
        return "stdout";
    }

    @Override
    public String displayName() {
        return "Standard output (example plugin)";
    }

    @Override
    public List<TransportOption> options() {
        return List.of(TransportOption.optional("prefix", "Prefix", "printed before each message", "HL7>"));
    }

    @Override
    public Transport open(DestinationConfig destination, TransportContext context) {
        String prefix = destination.transportOptions().getOrDefault("prefix", "HL7>");
        return new Transport() {
            @Override
            public SendResult send(PreparedMessage message, AckMode ackMode) {
                Instant start = Instant.now();
                System.out.println(prefix + " " + message.wire().replace('\r', '\n'));
                return Transports.result(SendOutcome.SENT_NO_ACK, "stdout", message, null, null, "", start,
                        Duration.ZERO);
            }
        };
    }
}
