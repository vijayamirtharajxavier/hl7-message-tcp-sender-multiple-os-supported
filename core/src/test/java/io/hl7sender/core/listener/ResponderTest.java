package io.hl7sender.core.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hl7sender.core.ack.AckCode;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.config.SettingsStore;
import io.hl7sender.core.hl7.FieldPath;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.template.TemplateEngine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The Test Listener as a responder: rules, custom responses, follow-up messages and saving what arrives. */
@Timeout(30)
class ResponderTest {

    private static final String ORDER = "MSH|^~\\&|EHR|HOSP|LAB|LABFAC|20260101120000||ORM^O01^ORM_O01|ORD1|P|2.5.1\r"
            + "PID|1||MRN123^^^HOSP^MR~ALT9^^^OTHER^MR||DOE^JANE^Q||19800101|F\r"
            + "ORC|NW|PLACER77\r"
            + "OBR|1|PLACER77||CBC^Complete blood count^L\r";
    private static final String ADMIT = "MSH|^~\\&|EHR|HOSP|LAB|LABFAC|20260101120000||ADT^A01^ADT_A01|ADM1|P|2.5.1\r"
            + "PID|1||MRN999^^^HOSP^MR||ROE^RICHARD\r";
    private static final String ADMIT_OTHER_FACILITY = ADMIT.replace("|EHR|HOSP|", "|EHR|CLINIC|");

    @TempDir
    Path dir;

    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private final Hl7Sender sender = new Hl7Sender();
    private TestListener listener;
    private MllpClientConfig target;

    @BeforeEach
    void start() throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
        target = MllpClientConfig.of("127.0.0.1", listener.port()).withTimeouts(2000, 2000);
    }

    @AfterEach
    void stop() {
        listener.close();
    }

    private SendResult send(String message) {
        return sender.send(target, message, SendOptions.AS_IS);
    }

    @Test
    void fieldPaths() {
        ParsedMessage m = ParsedMessage.parse(ORDER + "OBX|1|NM|WBC||7.5\rOBX|2|NM|RBC||4.8\r");
        assertThat(FieldPath.parse("PID-3.1").valueIn(m)).isEqualTo("MRN123");
        assertThat(FieldPath.parse("PID-3[2].1").valueIn(m)).isEqualTo("ALT9");
        assertThat(FieldPath.parse("PID-3").valueIn(m)).isEqualTo("MRN123^^^HOSP^MR~ALT9^^^OTHER^MR");
        assertThat(FieldPath.parse("pid-5.2").valueIn(m)).isEqualTo("JANE");
        assertThat(FieldPath.parse("OBX(2)-5").valueIn(m)).isEqualTo("4.8");
        assertThat(FieldPath.parse("OBX-5").valueIn(m)).isEqualTo("7.5");
        assertThat(FieldPath.parse("MSH-9.2").valueIn(m)).isEqualTo("O01");
        assertThat(FieldPath.parse("MSH-10").valueIn(m)).isEqualTo("ORD1");
        assertThat(FieldPath.parse("MSH-2").valueIn(m)).isEqualTo("^~\\&");
        assertThat(FieldPath.parse("OBR-4.1.1").valueIn(m)).isEqualTo("CBC");
        assertThat(FieldPath.parse("NK1-2").valueIn(m)).isEmpty();
        assertThat(FieldPath.parse("OBX(3)-5").valueIn(m)).isEmpty();
        assertThat(FieldPath.parse("PID-3[2].1").toString()).isEqualTo("PID-3[2].1");
        assertThatThrownBy(() -> FieldPath.parse("PID3")).hasMessageContaining("not a field");
        assertThatThrownBy(() -> FieldPath.parse("PID-0")).hasMessageContaining("start at 1");
    }

    @Test
    void templatesCopyValuesFromTheInboundMessage() {
        ParsedMessage m = ParsedMessage.parse(ORDER);
        String out = new TemplateEngine().expand("PID|1||${IN:PID-3.1}||${IN:PID-5}|${IN:NK1-2}|${IN:bad}",
                m).text();
        assertThat(out).isEqualTo("PID|1||MRN123||DOE^JANE^Q||${IN:bad}");
        // Without an inbound message, ${IN:...} is left as written.
        assertThat(new TemplateEngine().expand("${IN:PID-3.1}").text()).isEqualTo("${IN:PID-3.1}");
    }

    @Test
    void firstMatchingRuleDecidesTheResponse() {
        listener.updateSettings(ListenerSettings.DEFAULTS.withRules(List.of(
                ResponseRule.of("Reject other facilities", "ADT", ResponseMode.REJECT)
                        .withField("MSH-4", "(?!HOSP$).*").withResponseText("Unknown facility"),
                ResponseRule.of("Orders fail", "ORM^O0?", ResponseMode.ERROR).withResponseText("No such test"),
                ResponseRule.of("Everything else", "", ResponseMode.ACCEPT))));

        SendResult ok = send(ADMIT);
        assertThat(ok.outcome()).isEqualTo(SendOutcome.ACCEPTED);
        SendResult rejected = send(ADMIT_OTHER_FACILITY);
        assertThat(rejected.outcome()).isEqualTo(SendOutcome.APPLICATION_REJECT);
        assertThat(rejected.ack().orElseThrow().text()).isEqualTo("Unknown facility");
        SendResult error = send(ORDER);
        assertThat(error.outcome()).isEqualTo(SendOutcome.APPLICATION_ERROR);
        assertThat(error.detail()).isEqualTo("No such test");

        assertThat(received).extracting(ReceivedMessage::rule)
                .containsExactly("Everything else", "Reject other facilities", "Orders fail");
    }

    @Test
    void messagesMatchingNoRuleGetTheDefaultResponse() {
        listener.updateSettings(ListenerSettings.DEFAULTS.withMode(ResponseMode.REJECT)
                .withRules(List.of(ResponseRule.of("Orders", "ORM", ResponseMode.ACCEPT).withDelay(50))));
        assertThat(send(ORDER).outcome()).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(send(ADMIT).outcome()).isEqualTo(SendOutcome.APPLICATION_REJECT);
        assertThat(received.get(1).rule()).isEmpty();
    }

    @Test
    void customResponseIsATemplateOverTheInboundMessage() {
        String custom = "MSH|^~\\&|LAB|LABFAC|${IN:MSH-3}|${IN:MSH-4}|${NOW}||ACK^O01^ACK|${CONTROL_ID}|P|2.5.1\n"
                + "MSA|AA|${IN:MSH-10}|Order ${IN:ORC-2} received for ${IN:PID-3.1}\n";
        listener.updateSettings(ListenerSettings.DEFAULTS.withRules(List.of(
                ResponseRule.of("Vendor ACK", "ORM^O01", ResponseMode.ACCEPT).withCustomResponse(custom))));

        SendResult r = send(ORDER);
        assertThat(r.outcome()).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(r.ack().orElseThrow().code()).isEqualTo(AckCode.AA);
        assertThat(r.ack().orElseThrow().text()).isEqualTo("Order PLACER77 received for MRN123");
        assertThat(r.rawResponse().orElseThrow()).startsWith("MSH|^~\\&|LAB|LABFAC|EHR|HOSP|").contains("\rMSA|")
                .doesNotContain("\n");
        assertThat(received.get(0).responseCode()).isEqualTo("AA");
    }

    @Test
    void followUpMessageIsBuiltFromTheOrderAndHandedOver() throws Exception {
        String result = "MSH|^~\\&|LAB|LABFAC|EHR|HOSP|${NOW}||ORU^R01^ORU_R01|${CONTROL_ID}|P|2.5.1\r"
                + "PID|1||${IN:PID-3.1}^^^HOSP^MR||${IN:PID-5}\r"
                + "OBR|1|${IN:ORC-2}||${IN:OBR-4}|||${NOW}\r"
                + "OBX|1|NM|WBC^White blood cells||7.5|10*9/L|4.0-11.0|N|||F\r";
        listener.updateSettings(ListenerSettings.DEFAULTS.withRules(List.of(
                ResponseRule.of("Orders get results", "ORM^O01", ResponseMode.ACCEPT)
                        .withFollowUp(new ResponseRule.FollowUp("Mirth", 100, result)))));
        LinkedBlockingQueue<String[]> handed = new LinkedBlockingQueue<>();
        listener.setFollowUpHandler((rule, message) -> handed.add(new String[] {rule.followUp().destination(),
            message}));

        long start = System.nanoTime();
        assertThat(send(ORDER).outcome()).isEqualTo(SendOutcome.ACCEPTED);
        String[] followUp = handed.poll(5, TimeUnit.SECONDS);

        assertThat(followUp).isNotNull();
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(100);
        assertThat(followUp[0]).isEqualTo("Mirth");
        ParsedMessage oru = ParsedMessage.parse(followUp[1]);
        assertThat(oru.header().messageType()).isEqualTo("ORU^R01");
        assertThat(FieldPath.parse("PID-3.1").valueIn(oru)).isEqualTo("MRN123");
        assertThat(FieldPath.parse("PID-5").valueIn(oru)).isEqualTo("DOE^JANE^Q");
        assertThat(FieldPath.parse("OBR-2").valueIn(oru)).isEqualTo("PLACER77");
        assertThat(FieldPath.parse("OBR-4.2").valueIn(oru)).isEqualTo("Complete blood count");
        assertThat(oru.header().controlId()).hasSize(20);
        // Only matching messages trigger a follow-up.
        send(ADMIT);
        assertThat(handed.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void receivedMessagesAreSavedOneFileEach() throws IOException {
        Path folder = dir.resolve("received");
        listener.updateSettings(ListenerSettings.DEFAULTS.withSaveFolder(folder));
        send(ORDER);
        send(ORDER);
        send(ADMIT);

        List<Path> files;
        try (Stream<Path> list = Files.list(folder)) {
            files = list.sorted().toList();
        }
        assertThat(files).hasSize(3);
        assertThat(files.stream().map(f -> f.getFileName().toString()))
                .anyMatch(n -> n.endsWith("-ORD1.hl7")).anyMatch(n -> n.endsWith("-ORD1-2.hl7") || n.endsWith(
                        "-ORD1.hl7")).anyMatch(n -> n.endsWith("-ADM1.hl7"));
        assertThat(Files.readString(files.get(files.size() - 1))).startsWith("MSH|");
    }

    @Test
    void invalidRulesAreRefusedWithAReason() {
        assertThatThrownBy(() -> ResponseRule.of("x", "ADT", ResponseMode.ACCEPT).withField("PID3", ""))
                .hasMessageContaining("not a field");
        assertThatThrownBy(() -> ResponseRule.of("x", "ADT", ResponseMode.ACCEPT).withField("PID-3", "(["))
                .hasMessageContaining("invalid pattern");
        assertThatThrownBy(() -> ResponseRule.of("x", "ADT", ResponseMode.CUSTOM))
                .hasMessageContaining("custom response");
        assertThatThrownBy(() -> new ResponseRule.FollowUp("", 0, "MSH|"))
                .hasMessageContaining("destination");
        assertThatThrownBy(() -> ListenerSettings.DEFAULTS.withMode(ResponseMode.CUSTOM))
                .hasMessageContaining("per rule");
    }

    @Test
    void rulesRoundTripThroughJsonAndSettings() throws IOException {
        List<ResponseRule> rules = List.of(
                ResponseRule.of("Orders get results", "ORM^O01", ResponseMode.ACCEPT)
                        .withFollowUp(new ResponseRule.FollowUp("Mirth", 2000, "MSH|^~\\&|LAB\rPID|1||${IN:PID-3.1}")),
                ResponseRule.of("Reject", "ADT", ResponseMode.REJECT).withField("MSH-4", "CLINIC")
                        .withResponseText("Unknown facility").withDelay(250));
        Path file = dir.resolve("rules.json");
        ResponseRules.write(file, rules);
        assertThat(ResponseRules.read(file)).isEqualTo(rules);

        SettingsStore store = new SettingsStore(dir.resolve("settings.json"));
        AppSettings settings = AppSettings.defaults();
        store.save(settings.withListener(settings.listener().withRules(rules, "/tmp/in")));
        AppSettings loaded = store.load();
        assertThat(loaded.listener().rules()).isEqualTo(rules);
        assertThat(loaded.listener().saveFolder()).isEqualTo("/tmp/in");

        assertThatThrownBy(() -> ResponseRules.parse("[{\"name\":\"x\",\"field\":\"PID-3\",\"pattern\":\"([\"}]"))
                .hasMessageContaining("Invalid rule").hasMessageContaining("invalid pattern");
        assertThatThrownBy(() -> ResponseRules.parse("{not json")).hasMessageContaining("Not a rules file");
        assertThat(rules.get(1).describe()).isEqualTo("ADT, MSH-4 ~ CLINIC -> Reject (AR / CR) after 250 ms");
    }
}
