package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.RetryPolicy;
import io.hl7sender.core.samples.SampleMessages;
import io.hl7sender.core.send.SendOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI tests for the Queue tab and the Sender tab's "Add to queue". */
@ExtendWith(ApplicationExtension.class)
class QueueUiTest {

    private Path home;
    private AppContext context;
    private MainWindow window;
    private TestListener listener;

    @Start
    void start(Stage stage) throws IOException {
        home = Files.createTempDirectory("hl7sender-queue-ui");
        context = new AppContext(paths(home));
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        scene.getStylesheets().add(Styles.stylesheet());
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

    private static AppPaths paths(Path home) {
        return new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs"));
    }

    private DeliveryEngine engine() {
        return context.engine().orElseThrow();
    }

    private DestinationConfig destination(ResponseMode mode) throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS.withMode(mode), m -> { });
        listener.start();
        return engine().saveDestination(DestinationConfig.of("Test receiver", "127.0.0.1", listener.port())
                .withRetry(new RetryPolicy(3, 50, 100, 0)));
    }

    private void selectTab(FxRobot robot, String tabPaneId, String tabId) {
        robot.interact(() -> {
            TabPane tabs = robot.lookup(tabPaneId).queryAs(TabPane.class);
            for (Tab t : tabs.getTabs()) {
                if (tabId.equals(t.getId()) || t.getText().startsWith(tabId)) {
                    tabs.getSelectionModel().select(t);
                }
            }
        });
    }

    private int count(long destinationId, MessageStatus status) {
        return engine().store().counts(destinationId).get(status);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        WaitForAsyncUtils.waitFor(15, TimeUnit.SECONDS, condition::getAsBoolean);
        WaitForAsyncUtils.waitForFxEvents();
    }

    @SuppressWarnings("unchecked")
    private static <T> TableView<T> table(FxRobot robot, String id) {
        return robot.lookup(id).queryAs(TableView.class);
    }

    @Test
    void enqueueFromSenderIsDeliveredAndShownInQueueTab(FxRobot robot) throws Exception {
        DestinationConfig d = destination(ResponseMode.ACCEPT);
        MenuButton menu = robot.lookup("#enqueueButton").queryAs(MenuButton.class);
        String item = "Test receiver  (" + d.address() + ")";
        await(() -> Fx.call(() -> menu.getItems().stream().anyMatch(i -> item.equals(i.getText()))));
        // Fire the real menu item: clicking inside popups is unreliable on the headless (Monocle) screen.
        robot.interact(() -> menu.getItems().stream().filter(i -> item.equals(i.getText())).findFirst()
                .orElseThrow().fire());
        await(() -> count(d.id(), MessageStatus.ACKNOWLEDGED) == 1);
        assertThat(robot.lookup("#outcomeBadge").queryAs(Label.class).getText()).isEqualTo("QUEUED");

        selectTab(robot, "#mainTabs", "queueTab");
        ListView<?> destinations = robot.lookup("#destinationList").queryAs(ListView.class);
        await(() -> destinations.getSelectionModel().getSelectedItem() != null);
        selectTab(robot, "#queueViews", "Delivered");
        TableView<Object> delivered = table(robot, "#deliveredTable");
        await(() -> delivered.getItems().size() == 1);
        robot.interact(() -> delivered.getSelectionModel().selectFirst());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#queueMessageText").queryAs(CodeArea.class).getText()).startsWith("MSH|");
        assertThat(table(robot, "#attemptsTable").getItems()).hasSize(1);
        assertThat(robot.lookup("#attemptAckText").queryAs(TextArea.class).getText()).contains("MSA|AA|");
        assertThat(robot.lookup("#auditList").queryAs(ListView.class).getItems().size()).isEqualTo(3);
        assertThat(robot.lookup("#queueStateBadge").queryAs(Label.class).getText()).isEqualTo("Idle");
        MainWindowUiTest.screenshot(robot, window, "05-queue-delivered");
    }

    @Test
    void deadLetterCanBeRequeuedFromTheQueueTab(FxRobot robot) throws Exception {
        DestinationConfig d = destination(ResponseMode.ERROR);
        engine().enqueue(d.id(), SampleMessages.all().get(0).text(), SendOptions.DEFAULTS);
        await(() -> count(d.id(), MessageStatus.DEAD_LETTER) == 1);

        selectTab(robot, "#mainTabs", "queueTab");
        ListView<?> destinations = robot.lookup("#destinationList").queryAs(ListView.class);
        await(() -> destinations.getSelectionModel().getSelectedItem() != null);
        selectTab(robot, "#queueViews", "Dead letter");
        TableView<Object> dead = table(robot, "#deadLetterTable");
        await(() -> dead.getItems().size() == 1);
        robot.interact(() -> dead.getSelectionModel().selectFirst());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#attemptAckText").queryAs(TextArea.class).getText()).contains("MSA|AE|");
        MainWindowUiTest.screenshot(robot, window, "06-queue-dead-letter");

        listener.updateSettings(listener.settings().withMode(ResponseMode.ACCEPT));
        robot.clickOn("#requeueButton");
        await(() -> count(d.id(), MessageStatus.ACKNOWLEDGED) == 1);
        await(() -> dead.getItems().isEmpty());
    }

    @Test
    void addDestinationThroughDialog(FxRobot robot) throws Exception {
        selectTab(robot, "#mainTabs", "queueTab");
        robot.clickOn("#addDestinationButton");
        WaitForAsyncUtils.waitForFxEvents();
        // An empty name is rejected and the dialog stays open.
        robot.clickOn("#destOkButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#destErrorLabel").queryAs(Label.class).getText()).contains("name");
        MainWindowUiTest.screenshot(robot, robot.lookup("#destOkButton").query().getScene().getRoot(),
                "07-destination-dialog");

        robot.interact(() -> {
            robot.lookup("#destNameField").queryAs(TextField.class).setText("Mirth test");
            robot.lookup("#destHostField").queryAs(TextField.class).setText("mirth.local");
            robot.lookup("#destPortField").queryAs(TextField.class).setText("6661");
            robot.lookup("#destMaxAttemptsField").queryAs(TextField.class).setText("4");
        });
        robot.clickOn("#destOkButton");
        await(() -> engine().destinations().size() == 1);
        DestinationConfig saved = engine().destinations().get(0);
        assertThat(saved.name()).isEqualTo("Mirth test");
        assertThat(saved.address()).isEqualTo("mirth.local:6661");
        assertThat(saved.retry().maxAttempts()).isEqualTo(4);
        assertThat(robot.lookup("#destinationList").queryAs(ListView.class).getItems().size()).isEqualTo(1);
    }

    @Test
    void secondInstanceRunsWithoutTheQueue() throws Exception {
        try (AppContext second = new AppContext(paths(home))) {
            assertThat(second.engine()).isEmpty();
            assertThat(second.queueUnavailableReason()).contains("already delivering");
        }
        assertThat(context.engine()).isPresent();
        assertThat(engine().store().messages(0, EnumSet.allOf(MessageStatus.class), 1, false)).isEmpty();
    }
}
