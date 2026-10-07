package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.queue.AttemptRecord;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.send.SendOptions;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
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

/** Headless UI test: the Test Listener tab sends application ACKs and the queue matches them. */
@ExtendWith(ApplicationExtension.class)
class ApplicationAckUiTest {

    private AppContext context;
    private MainWindow window;

    @Start
    void start(Stage stage) throws IOException {
        Path home = Files.createTempDirectory("hl7sender-app-ack-ui");
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
    }

    private DeliveryEngine engine() {
        return context.engine().orElseThrow();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out");
            }
            Thread.sleep(50);
        }
    }

    @Test
    void theListenerTabSendsApplicationAcksThatCompleteQueuedMessages(FxRobot robot) throws Exception {
        int listenPort = freePort();
        int appAckPort = freePort();
        robot.interact(() -> {
            TabPane tabs = robot.lookup("#mainTabs").queryAs(TabPane.class);
            tabs.getSelectionModel().select(tabs.getTabs().stream()
                    .filter(t -> "listenerTab".equals(t.getId())).findFirst().orElseThrow());
        });
        TextField appAckField = robot.lookup("#listenerAppAckPortField").queryAs(TextField.class);
        // Application ACKs follow a commit ACK, so the setting is only available in enhanced mode.
        assertThat(appAckField.isDisabled()).isTrue();
        robot.interact(() -> {
            robot.lookup("#listenerBindField").queryAs(TextField.class).setText("127.0.0.1");
            robot.lookup("#listenerPortField").queryAs(TextField.class).setText(String.valueOf(listenPort));
            robot.lookup("#listenerCommitCodesBox").queryAs(CheckBox.class).setSelected(true);
            appAckField.setText(String.valueOf(appAckPort));
        });
        assertThat(appAckField.isDisabled()).isFalse();
        robot.clickOn("#listenerStartButton");
        await(() -> robot.lookup("#listenerStateBadge").queryAs(Label.class).getText().startsWith("Listening"));

        DestinationConfig d = engine().saveDestination(DestinationConfig.of("EHR", "127.0.0.1", listenPort)
                .withAppAck(appAckPort, 60_000));
        await(() -> engine().isListeningForAppAcks(d.id()));
        String message = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|ORIG|P|2.5.1|||AL|AL\r"
                + "EVN|A01|20260101\rPID|1||MRN1^^^H^MR||DOE^JANE\rPV1|1|I\r";
        QueuedMessage m = engine().enqueue(d.id(), message, SendOptions.DEFAULTS).message().orElseThrow();

        await(() -> engine().store().message(m.id()).orElseThrow().status() == MessageStatus.ACKNOWLEDGED);
        List<AttemptRecord> history = engine().store().attempts(m.id());
        assertThat(history).extracting(a -> a.ackCode().orElse("")).containsExactly("CA", "AA");
        assertThat(history.get(1).applicationAck()).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
        robot.clickOn("#listenerStartButton");
    }
}
