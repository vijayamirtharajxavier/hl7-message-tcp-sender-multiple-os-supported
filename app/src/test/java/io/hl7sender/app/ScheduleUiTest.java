package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.schedule.Schedule;
import io.hl7sender.core.send.SendOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
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

/** Headless UI test for scheduled sends and replay from History. */
@ExtendWith(ApplicationExtension.class)
class ScheduleUiTest {

    private static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A08^ADT_A01|C1|P|2.5.1\r"
            + "EVN|A08|20260101\rPID|1||${RANDOM_MRN}^^^H^MR||DOE^JANE\rPV1|1|I\r";

    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private AppContext context;
    private MainWindow window;
    private TestListener listener;
    private DeliveryEngine engine;
    private DestinationConfig dest;

    @Start
    void start(Stage stage) throws IOException {
        Path home = Files.createTempDirectory("hl7sender-schedule-ui");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        Styles.apply(scene);
        stage.setScene(scene);
        stage.show();
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
        engine = context.engine().orElseThrow();
        dest = engine.saveDestination(DestinationConfig.of("Lab", "127.0.0.1", listener.port()));
    }

    @AfterEach
    void tearDown() {
        window.shutdown();
        context.close();
        listener.close();
    }

    @Test
    void scheduleIsCreatedInTheEditorAndRunNow(FxRobot robot) throws Exception {
        robot.interact(() -> new SchedulesDialog(window.getScene().getWindow(), context, () -> MESSAGE, s -> { })
                .show());
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn("#schedulesAdd");
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            robot.lookup("#scheduleNameField").queryAs(TextField.class).setText("Morning A08");
            robot.lookup("#scheduleCronField").queryAs(TextField.class).setText("0 7 * * 1-5");
        });
        robot.clickOn("#scheduleFromEditor");
        assertThat(robot.lookup("#scheduleMessageArea").queryAs(TextArea.class).getText()).startsWith("MSH|");
        assertThat(robot.lookup("#schedulePreviewLabel").queryAs(Label.class).getText()).contains("07:00");
        robot.interact(() -> robot.lookup("#scheduleCronField").queryAs(TextField.class).setText("bad"));
        assertThat(robot.lookup("#schedulePreviewLabel").queryAs(Label.class).getText()).contains("five fields");
        robot.interact(() -> robot.lookup("#scheduleCronField").queryAs(TextField.class).setText("0 7 * * 1-5"));
        MainWindowUiTest.screenshot(robot, robot.lookup("#scheduleNameField").queryAs(TextField.class).getScene()
                .getRoot(), "30-schedule-editor");
        robot.clickOn("#scheduleOkButton");
        WaitForAsyncUtils.waitForFxEvents();

        List<Schedule> saved = engine.store().schedules();
        assertThat(saved).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("Morning A08");
            assertThat(s.destinationId()).isEqualTo(dest.id());
        });
        @SuppressWarnings("unchecked")
        TableView<Schedule> table = robot.lookup("#schedulesTable").queryAs(TableView.class);
        robot.interact(() -> table.getSelectionModel().select(0));
        robot.clickOn("#schedulesRun");
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> received.size() == 1);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> Fx.call(() -> robot.lookup("#schedulesSummary")
                .queryAs(Label.class).getText()).contains("1 queued"));
        MainWindowUiTest.screenshot(robot, table.getScene().getRoot(), "31-schedules");
        robot.interact(() -> table.getScene().getWindow().hide());
    }

    @Test
    void historyReplaysSelectedMessages(FxRobot robot) throws Exception {
        QueuedMessage m = engine.enqueue(dest.id(), MESSAGE.replace("${RANDOM_MRN}", "MRN1"), SendOptions.AS_IS)
                .message().orElseThrow();
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> received.size() == 1);
        HistoryPane[] history = new HistoryPane[1];
        robot.interact(() -> {
            javafx.scene.Node n = robot.lookup("#historyTable").query();
            while (!(n instanceof HistoryPane)) {
                n = n.getParent();
            }
            history[0] = (HistoryPane) n;
            history[0].replay(List.of(m.id()), null, true);
        });
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, () -> received.size() == 2);
        assertThat(received.get(1).controlId()).isNotEqualTo(received.get(0).controlId());
        assertThat(robot.lookup("#statusLabel").queryAs(Label.class).getText()).contains("1 message(s) queued again");
        // The History tab now shows the replay batch.
        assertThat(robot.lookup("#historyBatch").queryAs(TextField.class).getText()).startsWith("R");
        Platform.runLater(() -> { });
    }
}
