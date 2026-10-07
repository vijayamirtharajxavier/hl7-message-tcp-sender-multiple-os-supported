package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.samples.SampleMessages;
import io.hl7sender.core.send.SendOptions;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI tests for the dashboard, alerts, logs, diagnostics, themes, i18n and the welcome wizard. */
@ExtendWith(ApplicationExtension.class)
class MonitoringUiTest {

    private Path home;
    private AppContext context;
    private MainWindow window;
    private TestListener listener;
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();

    @Start
    void start(Stage stage) throws IOException {
        home = Files.createTempDirectory("hl7sender-p5-ui");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        Styles.apply(scene);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        window.shutdown();
        context.close();
        if (listener != null) {
            listener.close();
        }
    }

    private DeliveryEngine engine() {
        return context.engine().orElseThrow();
    }

    private void startListener(ResponseMode mode) throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS.withMode(mode), received::add);
        listener.start();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, condition::getAsBoolean);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static void selectTab(FxRobot robot, String id) {
        robot.interact(() -> {
            TabPane tabs = robot.lookup("#mainTabs").queryAs(TabPane.class);
            tabs.getTabs().stream().filter(t -> id.equals(t.getId())).findFirst()
                    .ifPresent(t -> tabs.getSelectionModel().select(t));
        });
    }

    private static String text(FxRobot robot, String id) {
        return robot.lookup(id).queryAs(Label.class).getText();
    }

    private int count(long destinationId, MessageStatus status) {
        return engine().store().counts(destinationId).getOrDefault(status, 0);
    }

    private static String sample() {
        return SampleMessages.all().get(0).text();
    }

    @Test
    void dashboardShowsQueueAndDeliveryStatistics(FxRobot robot) throws Exception {
        startListener(ResponseMode.ACCEPT);
        DestinationConfig d = engine().saveDestination(DestinationConfig.of("Mirth", "127.0.0.1", listener.port()));
        for (int i = 0; i < 3; i++) {
            engine().enqueue(d.id(), sample(), SendOptions.DEFAULTS);
        }
        await(() -> count(d.id(), MessageStatus.ACKNOWLEDGED) == 3);
        selectTab(robot, "dashboardTab");
        await(() -> robot.lookup("#dash-" + d.id() + "-accepted").tryQuery().isPresent()
                && "100.0%".equals(text(robot, "#dash-" + d.id() + "-accepted")));
        assertThat(text(robot, "#dash-" + d.id() + "-depth")).isEqualTo("0");
        assertThat(text(robot, "#dash-" + d.id() + "-dead")).isEqualTo("0");
        assertThat(text(robot, "#dash-" + d.id() + "-nack")).isEqualTo("0.0%");
        assertThat(text(robot, "#dash-" + d.id() + "-throughput")).isEqualTo("0.1");
        assertThat(text(robot, "#dash-" + d.id() + "-latency")).endsWith(")").contains(" ms (max ");
        assertThat(text(robot, "#dash-" + d.id() + "-state")).isIn("Idle", "Sending");
        assertThat(text(robot, "#dashSummary")).isEqualTo("1 destination(s): 0 pending, 0 in flight, 0 dead-lettered");
        assertThat(robot.lookup("#dashCard-" + d.id()).query().getAccessibleText())
                .startsWith("Mirth: ").contains("Queue depth 0");
        MainWindowUiTest.screenshot(robot, window, "18-dashboard");
    }

    @Test
    void dashboardCardsEditAndDeleteTheirDestination(FxRobot robot) throws Exception {
        DestinationConfig d = engine().saveDestination(DestinationConfig.of("Lab", "lab-typo.example.org", 2575)
                .withPaused(true));
        selectTab(robot, "dashboardTab");
        await(() -> robot.lookup("#dash-" + d.id() + "-edit").tryQuery().isPresent());
        MainWindowUiTest.screenshot(robot, window, "18b-dashboard-actions");

        // Edit: the destination dialog opens with the card's settings; fixing the host updates the card.
        press(robot, "#dash-" + d.id() + "-edit");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#destNameField").queryAs(TextField.class).getText()).isEqualTo("Lab");
        robot.interact(() -> robot.lookup("#destHostField").queryAs(TextField.class).setText("lab.example.org"));
        robot.clickOn("#destOkButton");
        await(() -> engine().destinations().stream().anyMatch(x -> x.id() == d.id()
                && x.host().equals("lab.example.org")));
        await(() -> robot.lookup("#dash-" + d.id() + "-address").tryQuery().isPresent()
                && text(robot, "#dash-" + d.id() + "-address").startsWith("lab.example.org:2575"));
        assertThat(engine().destinations()).hasSize(1);

        // Delete: asks first; Cancel keeps it, OK removes it and its card.
        press(robot, "#dash-" + d.id() + "-delete");
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn(robot.lookup((javafx.scene.Node n) -> n instanceof Button b
                && "Cancel".equals(b.getText())).queryButton());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(engine().destinations()).hasSize(1);
        press(robot, "#dash-" + d.id() + "-delete");
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn(robot.lookup((javafx.scene.Node n) -> n instanceof Button b
                && "OK".equals(b.getText())).queryButton());
        await(() -> engine().destinations().isEmpty());
        await(() -> robot.lookup("#dashCard-" + d.id()).tryQuery().isEmpty());
    }

    /**
     * Presses a dashboard card's button. The lookup and the press run in one JavaFX task: the dashboard rebuilds a
     * card when its destination's state changes, and a button looked up before a rebuild is no longer on screen when
     * the robot clicks it. It does not wait for the press to finish, since the button may open a modal dialog.
     */
    private static void press(FxRobot robot, String query) {
        Platform.runLater(() -> robot.lookup(query).queryButton().fire());
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deadLetterRaisesANotificationAndIsListedOnTheDashboard(FxRobot robot) throws Exception {
        startListener(ResponseMode.ERROR);
        DestinationConfig d = engine().saveDestination(DestinationConfig.of("Lab", "127.0.0.1", listener.port()));
        engine().enqueue(d.id(), sample(), SendOptions.DEFAULTS);
        await(() -> count(d.id(), MessageStatus.DEAD_LETTER) == 1);
        await(() -> window.notifier().lastShown().startsWith("1 message(s) dead-lettered for Lab"));
        assertThat(robot.lookup("#toast").tryQuery()).isPresent();
        assertThat(text(robot, "#statusLabel")).isEqualTo("Alert: 1 message(s) dead-lettered for Lab");
        selectTab(robot, "dashboardTab");
        ListView<String> alerts = robot.lookup("#dashAlerts").queryAs(ListView.class);
        await(() -> !alerts.getItems().isEmpty());
        assertThat(alerts.getItems().get(0)).contains("[CRITICAL] 1 message(s) dead-lettered for Lab");
        await(() -> "1".equals(text(robot, "#dash-" + d.id() + "-dead")));
        MainWindowUiTest.screenshot(robot, window, "19-alert");
    }

    @Test
    void alertSettingsAreSavedAndTested(FxRobot robot) throws Exception {
        HttpServer hook = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> bodies = new CopyOnWriteArrayList<>();
        hook.createContext("/h", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        hook.start();
        try {
            Platform.runLater(() -> new AlertSettingsDialog(window.getScene().getWindow(), context).showAndWait());
            WaitForAsyncUtils.waitForFxEvents();
            String url = "http://127.0.0.1:" + hook.getAddress().getPort() + "/h";
            robot.interact(() -> {
                robot.lookup("#alertWebhookField").queryAs(TextField.class).setText(url);
                robot.lookup("#alertThresholdField").queryAs(TextField.class).setText("5");
                robot.lookup("#alertTestButton").queryAs(Button.class).fire();
            });
            Label result = robot.lookup("#alertResultLabel").queryAs(Label.class);
            await(() -> "Webhook: sent".equals(result.getText()));
            assertThat(bodies).singleElement().satisfies(b -> assertThat(b).contains("Test alert"));
            MainWindowUiTest.screenshot(robot, robot.lookup("#alertOkButton").query().getScene().getRoot(),
                    "20-alert-settings");
            robot.clickOn("#alertOkButton");
            WaitForAsyncUtils.waitForFxEvents();
            assertThat(context.settings().alerts().webhookUrl()).isEqualTo(url);
            assertThat(context.settings().alerts().deadLetterThreshold()).isEqualTo(5);
        } finally {
            hook.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void logsTabShowsLiveLogWithFilters(FxRobot robot) throws Exception {
        selectTab(robot, "logsTab");
        LoggerFactory.getLogger("io.hl7sender.test.Probe").info("probe info line");
        LoggerFactory.getLogger("io.hl7sender.test.Probe").warn("probe warning line");
        TableView<LogBuffer.Entry> table = robot.lookup("#logTable").queryAs(TableView.class);
        await(() -> Fx.call(() -> table.getItems().stream().anyMatch(e -> e.message().equals("probe warning line"))));
        robot.interact(() -> {
            robot.lookup("#logLevelBox").queryAs(ComboBox.class).setValue(LogsPane.MinLevel.WARN);
            robot.lookup("#logFilterField").queryAs(TextField.class).setText("probe");
        });
        assertThat(table.getItems()).extracting(LogBuffer.Entry::message).containsExactly("probe warning line");
        assertThat(table.getItems()).singleElement().satisfies(e -> assertThat(e.logger()).isEqualTo("Probe"));
        assertThat(text(robot, "#logCountLabel")).startsWith("1 of ");
        MainWindowUiTest.screenshot(robot, window, "21-logs");
    }

    @Test
    void diagnosticsBundleIsWritten() throws Exception {
        engine().saveDestination(DestinationConfig.of("EHR", "ehr.example.org", 2575).withPaused(true));
        Path zip = home.resolve("diag.zip");
        List<String> entries = window.writeDiagnostics(zip);
        assertThat(entries).contains("README.txt", "system.txt", "settings.json", "destinations.json", "queue.txt");
        assertThat(Files.size(zip)).isPositive();
    }

    @Test
    void darkThemeAppliesToTheWindowAndDialogs(FxRobot robot) throws Exception {
        Styles.watchWindows();
        try {
            robot.interact(() -> window.setTheme(AppSettings.Ui.Theme.DARK));
            assertThat(window.getScene().getStylesheets()).contains(Styles.darkStylesheet());
            assertThat(context.settings().ui().theme()).isEqualTo(AppSettings.Ui.Theme.DARK);
            Platform.runLater(() -> new AlertSettingsDialog(window.getScene().getWindow(), context).show());
            WaitForAsyncUtils.waitForFxEvents();
            Window dialog = robot.lookup("#alertOkButton").query().getScene().getWindow();
            assertThat(dialog.getScene().getStylesheets()).contains(Styles.darkStylesheet());
            MainWindowUiTest.screenshot(robot, window, "22-dark-theme");
            robot.interact(() -> ((javafx.stage.Stage) dialog).close());
        } finally {
            robot.interact(() -> window.setTheme(AppSettings.Ui.Theme.LIGHT));
        }
        assertThat(window.getScene().getStylesheets()).doesNotContain(Styles.darkStylesheet());
    }

    @Test
    void messagesAreTranslatedAndFallBackToEnglish() {
        try {
            Messages.setLanguage("es");
            assertThat(Messages.get("tab.queue")).isEqualTo("Cola");
            assertThat(Messages.get("dashboard.summary", 2, 5, 1, 0))
                    .isEqualTo("2 destino(s): 5 pendientes, 1 en curso, 0 en la cola de fallidos");
            Messages.setLanguage("fr");
            assertThat(Messages.get("tab.queue")).isEqualTo("Queue");
            assertThat(Messages.get("wizard.hostRequired")).isEqualTo("Enter the receiver's host name or IP address.");
        } finally {
            Messages.setLanguage("");
        }
    }

    @Test
    void welcomeWizardCreatesADestinationTestsItAndSendsASample(FxRobot robot) throws Exception {
        Platform.runLater(window::showWelcomeWizardIfFirstRun);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#wizardUseListener").tryQuery()).isPresent();
        MainWindowUiTest.screenshot(robot, robot.lookup("#wizardNextButton").query().getScene().getRoot(),
                "23-wizard-destination");
        robot.clickOn("#wizardNextButton");
        await(() -> engine().destinations().size() == 1);
        DestinationConfig d = engine().destinations().get(0);
        assertThat(d.name()).isEqualTo("Test listener");
        assertThat(text(robot, "#wizardTarget")).startsWith("Destination Test listener at 127.0.0.1:");

        robot.clickOn("#wizardTestButton");
        Label test = robot.lookup("#wizardTestResult").queryAs(Label.class);
        await(() -> test.getText().startsWith("Connected to 127.0.0.1:"));
        robot.clickOn("#wizardNextButton");

        robot.clickOn("#wizardSendButton");
        Label sent = robot.lookup("#wizardSendResult").queryAs(Label.class);
        await(() -> sent.getText().startsWith("Delivered and acknowledged"));
        MainWindowUiTest.screenshot(robot, robot.lookup("#wizardFinishButton").query().getScene().getRoot(),
                "24-wizard-sent");
        robot.clickOn("#wizardFinishButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(context.settings().ui().firstRunDone()).isTrue();
        assertThat(count(d.id(), MessageStatus.ACKNOWLEDGED)).isEqualTo(1);
        assertThat(robot.lookup("#mainTabs").queryAs(TabPane.class).getSelectionModel().getSelectedItem().getId())
                .isEqualTo("dashboardTab");

        // Not shown again once completed.
        Platform.runLater(window::showWelcomeWizardIfFirstRun);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#wizardNextButton").tryQuery()).isEmpty();
    }
}
