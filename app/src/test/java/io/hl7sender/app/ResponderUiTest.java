package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.hl7.FieldPath;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.ResponseRule;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI test for the Test Listener's responder rules. */
@ExtendWith(ApplicationExtension.class)
class ResponderUiTest {

    private static final String ORDER = "MSH|^~\\&|EHR|HOSP|LAB|LABFAC|20260101120000||ORM^O01^ORM_O01|ORD1|P|2.5.1\r"
            + "PID|1||MRN123^^^HOSP^MR||DOE^JANE||19800101|F\r"
            + "ORC|NW|PLACER77\r"
            + "OBR|1|PLACER77||CBC^Complete blood count^L\r";

    private final List<ReceivedMessage> atEhr = new CopyOnWriteArrayList<>();
    private AppContext context;
    private MainWindow window;
    private TestListener ehr;
    private Path home;

    @Start
    void start(Stage stage) throws IOException {
        home = Files.createTempDirectory("hl7sender-responder-ui");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 900);
        Styles.apply(scene);
        stage.setScene(scene);
        stage.show();
        ehr = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, atEhr::add);
        ehr.start();
        context.engine().orElseThrow().saveDestination(DestinationConfig.of("EHR", "127.0.0.1", ehr.port()));
    }

    @AfterEach
    void tearDown() {
        window.shutdown();
        context.close();
        ehr.close();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private ListenerPane openListener(FxRobot robot) {
        robot.interact(() -> {
            TabPane tabs = robot.lookup("#mainTabs").queryAs(TabPane.class);
            tabs.getSelectionModel().select(tabs.getTabs().stream().filter(t -> "listenerTab".equals(t.getId()))
                    .findFirst().orElseThrow());
            robot.lookup("#listenerRulesPane").queryAs(javafx.scene.control.TitledPane.class).setExpanded(true);
        });
        return findPane(robot);
    }

    private static ListenerPane findPane(FxRobot robot) {
        javafx.scene.Node n = robot.lookup("#listenerRulesList").query();
        while (n != null && !(n instanceof ListenerPane)) {
            n = n.getParent();
        }
        return (ListenerPane) n;
    }

    @Test
    void ruleAddedInTheDialogAnswersOrdersAndQueuesAResult(FxRobot robot) throws Exception {
        ListenerPane pane = openListener(robot);
        robot.clickOn("#listenerRuleAdd");
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            robot.lookup("#ruleNameField").queryAs(TextField.class).setText("Orders get results");
            robot.lookup("#ruleTypeField").queryAs(TextField.class).setText("ORM^O01");
            robot.lookup("#ruleResponseTextField").queryAs(TextField.class).setText("Order accepted");
            robot.lookup("#ruleFollowUpBox").queryAs(CheckBox.class).setSelected(true);
            @SuppressWarnings("unchecked")
            ComboBox<String> dest = robot.lookup("#ruleDestinationBox").queryAs(ComboBox.class);
            dest.setValue("EHR");
            dest.getEditor().setText("EHR");
        });
        // Choosing a follow-up fills in a sample result that copies the order's values.
        assertThat(robot.lookup("#ruleFollowUpTemplate").queryAs(TextArea.class).getText())
                .contains("${IN:PID-3}").contains("ORU^R01");
        MainWindowUiTest.screenshot(robot, robot.lookup("#ruleNameField").queryAs(TextField.class).getScene()
                .getRoot(), "28-responder-rule");
        robot.clickOn("#ruleOkButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(pane.rules()).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("Orders get results");
            assertThat(r.followUp().destination()).isEqualTo("EHR");
        });
        // Rules are saved with the settings.
        assertThat(context.settings().listener().rules()).hasSize(1);

        int port = freePort();
        robot.interact(() -> {
            robot.lookup("#listenerBindField").queryAs(TextField.class).setText("127.0.0.1");
            robot.lookup("#listenerPortField").queryAs(TextField.class).setText(String.valueOf(port));
        });
        robot.clickOn("#listenerStartButton");
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> Fx.call(() -> robot.lookup("#listenerStateBadge")
                .queryAs(Label.class).getText().startsWith("Listening")));

        SendResult r = new Hl7Sender().send(MllpClientConfig.of("127.0.0.1", port), ORDER, SendOptions.AS_IS);
        assertThat(r.outcome()).isEqualTo(SendOutcome.ACCEPTED);
        assertThat(r.ack().orElseThrow().text()).isEqualTo("Order accepted");

        @SuppressWarnings("unchecked")
        TableView<ReceivedMessage> table = robot.lookup("#listenerTable").queryAs(TableView.class);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> Fx.call(() -> table.getItems().size()) == 1);
        assertThat(Fx.call(() -> table.getItems().get(0).rule())).isEqualTo("Orders get results");

        // The follow-up result is queued and delivered to the EHR destination.
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> atEhr.size() == 1);
        ParsedMessage oru = ParsedMessage.parse(atEhr.get(0).payload());
        assertThat(oru.header().messageType()).isEqualTo("ORU^R01");
        assertThat(FieldPath.parse("PID-3.1").valueIn(oru)).isEqualTo("MRN123");
        assertThat(FieldPath.parse("OBR-2").valueIn(oru)).isEqualTo("PLACER77");
        assertThat(oru.header().receivingApplication()).isEqualTo("EHR");
        MainWindowUiTest.screenshot(robot, window, "29-responder");
        robot.clickOn("#listenerStartButton");
    }

    @Test
    void invalidRuleIsExplainedAndNotSaved(FxRobot robot) {
        ListenerPane pane = openListener(robot);
        robot.clickOn("#listenerRuleAdd");
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            robot.lookup("#ruleFieldField").queryAs(TextField.class).setText("PID3");
        });
        robot.clickOn("#ruleOkButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#ruleErrorLabel").queryAs(Label.class).getText()).contains("not a field");
        robot.interact(() -> robot.lookup("#ruleFieldField").queryAs(TextField.class).setText("PID-3.1"));
        robot.clickOn("#ruleOkButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(pane.rules()).hasSize(1);
    }

    @Test
    void rulesExportAndImportAsJson(FxRobot robot) throws IOException {
        ListenerPane pane = openListener(robot);
        Path file = home.resolve("rules.json");
        io.hl7sender.core.listener.ResponseRules.write(file, List.of(
                ResponseRule.of("Reject clinics", "ADT", ResponseMode.REJECT).withField("MSH-4", "CLINIC"),
                ResponseRule.of("Slow orders", "ORM", ResponseMode.ACCEPT).withDelay(100)));
        robot.interact(() -> pane.importRules(file));
        assertThat(pane.rules()).extracting(ResponseRule::name).containsExactly("Reject clinics", "Slow orders");
        robot.interact(() -> {
            robot.lookup("#listenerRulesList").queryAs(ListView.class).getSelectionModel().select(1);
        });
        robot.clickOn("#listenerRuleUp");
        assertThat(pane.rules()).extracting(ResponseRule::name).containsExactly("Slow orders", "Reject clinics");
        Path out = home.resolve("out.json");
        robot.interact(() -> pane.exportRules(out));
        assertThat(Files.readString(out)).contains("Slow orders").contains("\"field\" : \"MSH-4\"");
    }
}
