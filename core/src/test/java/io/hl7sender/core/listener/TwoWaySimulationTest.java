package io.hl7sender.core.listener;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.hl7.FieldPath;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A full two-way simulation: a "lab" responder acknowledges each order and queues a result back to the ordering
 * "EHR", which the delivery engine sends like any other message.
 */
@Timeout(30)
class TwoWaySimulationTest {

    private static final String ORDER = "MSH|^~\\&|EHR|HOSP|LAB|LABFAC|20260101120000||ORM^O01^ORM_O01|ORD1|P|2.5.1\r"
            + "PID|1||MRN123^^^HOSP^MR||DOE^JANE\rORC|NW|PLACER77\rOBR|1|PLACER77||CBC^Complete blood count^L\r";
    private static final String RESULT = "MSH|^~\\&|LAB|LABFAC|EHR|HOSP|${NOW}||ORU^R01^ORU_R01|${CONTROL_ID}|P|2.5.1\r"
            + "PID|1||${IN:PID-3.1}^^^HOSP^MR||${IN:PID-5}\r"
            + "OBR|1|${IN:ORC-2}||${IN:OBR-4}\r"
            + "OBX|1|NM|WBC^White blood cells||7.5|10*9/L|4.0-11.0|N|||F\r";

    @TempDir
    Path dir;

    private final List<ReceivedMessage> atEhr = new CopyOnWriteArrayList<>();
    private TestListener ehr;
    private TestListener lab;
    private QueueStore store;
    private DeliveryEngine engine;

    @BeforeEach
    void setUp() throws IOException {
        ehr = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, atEhr::add);
        ehr.start();
        store = QueueStore.open(dir.resolve("queue.db"));
        engine = new DeliveryEngine(store, new Hl7Sender());
        engine.saveDestination(DestinationConfig.of("EHR results", "127.0.0.1", ehr.port()));
        engine.start();

        lab = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS.withRules(List.of(
                ResponseRule.of("Orders get results", "ORM^O01", ResponseMode.ACCEPT)
                        .withFollowUp(new ResponseRule.FollowUp("ehr RESULTS", 0, RESULT)))), m -> { });
        lab.setFollowUpHandler(new QueueFollowUps(engine, null));
        lab.start();
    }

    @AfterEach
    void tearDown() {
        lab.close();
        engine.close();
        store.close();
        ehr.close();
    }

    @Test
    void anOrderIsAcknowledgedAndItsResultIsDeliveredBack() throws Exception {
        var ack = new Hl7Sender().send(MllpClientConfig.of("127.0.0.1", lab.port()), ORDER, SendOptions.AS_IS);
        assertThat(ack.outcome()).isEqualTo(SendOutcome.ACCEPTED);

        long deadline = System.currentTimeMillis() + 10_000;
        while (atEhr.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(atEhr).hasSize(1);
        ParsedMessage result = ParsedMessage.parse(atEhr.get(0).payload());
        assertThat(result.header().messageType()).isEqualTo("ORU^R01");
        assertThat(FieldPath.parse("PID-3.1").valueIn(result)).isEqualTo("MRN123");
        assertThat(FieldPath.parse("OBR-2").valueIn(result)).isEqualTo("PLACER77");

        long id = engine.destinations().get(0).id();
        List<QueuedMessage> queued = List.of();
        while (System.currentTimeMillis() < deadline) {
            queued = store.messages(id, java.util.EnumSet.allOf(MessageStatus.class), 10, false);
            if (queued.size() == 1 && queued.get(0).status() == MessageStatus.ACKNOWLEDGED) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(queued).singleElement().satisfies(m -> {
            assertThat(m.status()).isEqualTo(MessageStatus.ACKNOWLEDGED);
            assertThat(m.source()).contains("Responder rule: Orders get results");
        });
    }

    @Test
    void missingFollowUpDestinationsAreFound() {
        List<ResponseRule> rules = List.of(
                ResponseRule.of("a", "ORM", ResponseMode.ACCEPT).withFollowUp(new ResponseRule.FollowUp("EHR RESULTS",
                        0, "MSH|")),
                ResponseRule.of("b", "ADT", ResponseMode.ACCEPT).withFollowUp(new ResponseRule.FollowUp("Nowhere", 0,
                        "MSH|")));
        assertThat(QueueFollowUps.missingDestinations(rules, engine.destinations())).containsExactly("Nowhere");
    }
}
