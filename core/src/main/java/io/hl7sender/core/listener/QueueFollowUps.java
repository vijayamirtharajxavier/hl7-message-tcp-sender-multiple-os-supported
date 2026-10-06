package io.hl7sender.core.listener;

import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.EnqueueResult;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.send.SendOptions;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Queues responder follow-up messages to the named destination, so they are delivered, retried and recorded like
 * any other message. The message is queued as written by the rule's template (use {@code ${CONTROL_ID}} and
 * {@code ${NOW}} for MSH-10 and MSH-7).
 */
public final class QueueFollowUps implements TestListener.FollowUpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(QueueFollowUps.class);

    private final DeliveryEngine engine;
    private final Consumer<QueuedMessage> queued;

    /**
     * @param engine the queue to add follow-ups to
     * @param queued called after each follow-up is queued, for example to tell the delivering process
     */
    public QueueFollowUps(DeliveryEngine engine, Consumer<QueuedMessage> queued) {
        this.engine = engine;
        this.queued = queued;
    }

    /** Names of follow-up destinations used by {@code rules} that do not exist. */
    public static List<String> missingDestinations(List<ResponseRule> rules, List<DestinationConfig> destinations) {
        return rules.stream().filter(r -> r.followUp() != null).map(r -> r.followUp().destination()).distinct()
                .filter(name -> destinations.stream().noneMatch(d -> d.name().equalsIgnoreCase(name))).toList();
    }

    @Override
    public void followUp(ResponseRule rule, String message) {
        String name = rule.followUp().destination();
        DestinationConfig d = engine.destinations().stream().filter(x -> x.name().equalsIgnoreCase(name))
                .findFirst().orElseThrow(() -> new IllegalStateException("there is no destination named '" + name
                        + "'"));
        EnqueueResult r = engine.enqueue(d.id(), message, SendOptions.AS_IS, "Responder rule: " + rule.name());
        if (!r.accepted()) {
            throw new IllegalStateException("the follow-up message is not valid: "
                    + r.validation().errors().get(0).message());
        }
        QueuedMessage m = r.message().orElseThrow();
        LOG.info("Rule '{}': follow-up {} [{}] queued to '{}' as message {}", rule.name(), m.messageType(),
                m.controlId(), d.name(), m.id());
        if (queued != null) {
            queued.accept(m);
        }
    }
}
