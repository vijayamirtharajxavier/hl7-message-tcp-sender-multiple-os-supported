package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI test for the Load Test tab. */
@ExtendWith(ApplicationExtension.class)
class LoadTestUiTest {

    private static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|C1|P|2.5.1\r"
            + "PID|1||${RANDOM_MRN}^^^H^MR||DOE^JANE\rPV1|1|I\r";

    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private AppContext context;
    private MainWindow window;
    private TestListener listener;
    private Path home;

    @Start
    void start(Stage stage) throws IOException {
        home = Files.createTempDirectory("hl7sender-load-ui");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        Styles.apply(scene);
        stage.setScene(scene);
        stage.show();
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
    }

    @AfterEach
    void tearDown() {
        window.shutdown();
        context.close();
        listener.close();
    }

    private LoadTestPane openTab(FxRobot robot) {
        robot.interact(() -> {
            TabPane tabs = robot.lookup("#mainTabs").queryAs(TabPane.class);
            tabs.getSelectionModel().select(tabs.getTabs().stream().filter(t -> "loadTab".equals(t.getId()))
                    .findFirst().orElseThrow());
        });
        return robot.lookup("#loadTestPane").queryAs(LoadTestPane.class);
    }

    @Test
    void runsATestFromFilesAndShowsResults(FxRobot robot) throws Exception {
        Path file = Files.writeString(home.resolve("adt.hl7"), MESSAGE);
        LoadTestPane pane = openTab(robot);
        robot.interact(() -> {
            robot.lookup("#loadHostField").queryAs(TextField.class).setText("127.0.0.1");
            robot.lookup("#loadPortField").queryAs(TextField.class).setText(String.valueOf(listener.port()));
            robot.lookup("#loadConnectionsField").queryAs(TextField.class).setText("3");
            robot.lookup("#loadRateField").queryAs(TextField.class).setText("0");
            robot.lookup("#loadCountField").queryAs(TextField.class).setText("60");
            robot.lookup("#loadMaxErrorField").queryAs(TextField.class).setText("0");
            pane.setFiles(List.of(file));
        });
        robot.clickOn("#loadStartButton");
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> pane.lastReport() != null);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(received).hasSize(60);
        assertThat(robot.lookup("#loadAccepted").queryAs(Label.class).getText()).isEqualTo("60");
        assertThat(robot.lookup("#loadFailed").queryAs(Label.class).getText()).startsWith("0");
        assertThat(robot.lookup("#loadLatency").queryAs(Label.class).getText()).contains("p95");
        assertThat(robot.lookup("#loadThresholds").queryAs(Label.class).getText()).isEqualTo("Passed");
        assertThat(robot.lookup("#loadStateBadge").queryAs(Label.class).getText()).isEqualTo("Finished");
        assertThat(robot.lookup("#loadStartButton").queryAs(Button.class).getText()).isEqualTo("Start");
        @SuppressWarnings("unchecked")
        LineChart<Number, Number> rateChart = robot.lookup("#loadRateChart").queryAs(LineChart.class);
        int points = Fx.call(() -> rateChart.getData().get(0).getData().size());
        assertThat(points).isPositive();
        MainWindowUiTest.screenshot(robot, pane, "27-load-test");

        Path export = home.resolve("report.json");
        robot.interact(() -> pane.exportTo(export));
        String json = Files.readString(export);
        assertThat(json).contains("\"accepted\" : 60").contains("\"passed\" : true").contains("\"p95Ms\"");
    }

    @Test
    void stopEndsARunningTestAndFailuresAreShown(FxRobot robot) throws Exception {
        listener.updateSettings(listener.settings().withMode(ResponseMode.REJECT));
        LoadTestPane pane = openTab(robot);
        robot.interact(() -> {
            robot.lookup("#loadPortField").queryAs(TextField.class).setText(String.valueOf(listener.port()));
            robot.lookup("#loadRateField").queryAs(TextField.class).setText("20");
            robot.lookup("#loadCountField").queryAs(TextField.class).setText("0");
            robot.lookup("#loadDurationField").queryAs(TextField.class).setText("60");
        });
        // The Sender tab's sample message is used.
        robot.clickOn("#loadStartButton");
        WaitForAsyncUtils.sleep(1500, TimeUnit.MILLISECONDS);
        assertThat(Fx.call(pane::isRunning)).isTrue();
        robot.clickOn("#loadStartButton");
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> pane.lastReport() != null);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(pane.lastReport().cancelled()).isTrue();
        assertThat(robot.lookup("#loadStateBadge").queryAs(Label.class).getText()).isEqualTo("Stopped");
        assertThat(Fx.call(() -> String.join("\n", robot.lookup("#loadOutcomesList")
                .queryListView().getItems().stream().map(String::valueOf).toList())))
                .contains("Receiver rejected the message");
        assertThat(received).isNotEmpty();
    }

    @Test
    void invalidSettingsAreReportedWithoutStarting(FxRobot robot) {
        LoadTestPane pane = openTab(robot);
        robot.interact(() -> {
            robot.lookup("#loadPortField").queryAs(TextField.class).setText(String.valueOf(listener.port()));
            robot.lookup("#loadCountField").queryAs(TextField.class).setText("0");
            robot.lookup("#loadDurationField").queryAs(TextField.class).setText("0");
        });
        robot.clickOn("#loadStartButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(pane.isRunning()).isFalse();
        assertThat(robot.lookup("#loadStateBadge").queryAs(Label.class).getText()).isEqualTo("Check the settings");
        assertThat(robot.lookup("#statusLabel").queryAs(Label.class).getText()).contains("count, a duration");
    }
}
