package io.hl7sender.core.transport;

import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** A plugin transport used by the tests: it keeps messages in memory. Loaded from a jar built by the test. */
public final class MemoryTransportFactory implements TransportFactory {

    public static final List<String> SENT = new CopyOnWriteArrayList<>();

    @Override
    public String id() {
        return "memory";
    }

    @Override
    public String displayName() {
        return "Memory (test plugin)";
    }

    @Override
    public List<TransportOption> options() {
        return List.of(TransportOption.required("bucket", "Bucket", "any name"));
    }

    @Override
    public Transport open(DestinationConfig destination, TransportContext context) {
        String bucket = destination.transportOptions().get("bucket");
        return new Transport() {
            @Override
            public SendResult send(PreparedMessage message, AckMode ackMode) {
                SENT.add(bucket + ":" + message.controlId());
                return Transports.result(SendOutcome.ACCEPTED, "memory:" + bucket, message, null, null, "",
                        Instant.now(), Duration.ZERO);
            }
        };
    }
}
