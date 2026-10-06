package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.samples.SampleMessages;
import io.hl7sender.core.send.SendOptions;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Drives the real main window headlessly (Monocle) against the built-in test listener. */
@ExtendWith(ApplicationExtension.class)
class MainWindowUiTest {

    /** Set this system property to a directory to save screenshots of the window during tests. */
    private static final String SCREENSHOT_DIR = System.getProperty("hl7sender.screenshotDir");

    private AppContext context;
    private MainWindow window;
    private TestListener listener;

    @Start
    void start(Stage stage) throws IOException {
        // Fresh settings per test: the window saves what it is given, so tests must not share a settings file.
        Path home = Files.createTempDirectory("hl7sender-ui-test");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        scene.getStylesheets().add(Styles.stylesheet());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        window.shutdown();
        context.close();
    }

    private void pointSenderAt(FxRobot robot, int port) {
        robot.interact(() -> {
            robot.lookup("#hostField").queryAs(TextField.class).setText("127.0.0.1");
            robot.lookup("#portField").queryAs(TextField.class).setText(String.valueOf(port));
            robot.lookup("#ackTimeoutField").queryAs(TextField.class).setText("1500");
        });
    }

    private TestListener startListener(ResponseMode mode) throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS.withMode(mode), m -> { });
        listener.start();
        return listener;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, condition::getAsBoolean);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static String text(FxRobot robot, String query) {
        return robot.lookup(query).queryAs(Label.class).getText();
    }

    @Test
    void startsWithSampleMessageValidatedAndParsed(FxRobot robot) throws Exception {
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#editor").queryAs(CodeArea.class).getText()).startsWith("MSH|");
        assertThat(text(robot, "#validationSummary")).contains("ADT^A01").contains("0 errors");
        assertThat(robot.lookup("#structureTree").queryAs(TreeView.class).getRoot().getChildren().size()).isEqualTo(6);
        assertThat(text(robot, "#outcomeBadge")).isEqualTo("Not sent");
        screenshot(robot, "01-startup");
    }

    @Test
    void sendsAndShowsAcceptedAck(FxRobot robot) throws Exception {
        startListener(ResponseMode.ACCEPT);
        pointSenderAt(robot, listener.port());
        robot.clickOn("#sendButton");
        await(() -> text(robot, "#outcomeBadge").equals("ACCEPTED"));

        Label badge = robot.lookup("#outcomeBadge").queryAs(Label.class);
        assertThat(badge.getStyleClass()).contains(Styles.SUCCESS);
        assertThat(text(robot, "#ackCodeValue")).startsWith("AA");
        assertThat(text(robot, "#ackControlIdValue")).isEqualTo(text(robot, "#sentControlIdValue")).hasSize(20);
        assertThat(robot.lookup("#rawAck").queryAs(TextArea.class).getText()).contains("MSA|AA|");
        assertThat(robot.lookup("#history").queryAs(ListView.class).getItems().size()).isEqualTo(1);
        assertThat(text(robot, "#statusLabel")).startsWith("ACCEPTED");
        screenshot(robot, "02-accepted");
    }

    @Test
    void showsApplicationErrorInRed(FxRobot robot) throws Exception {
        startListener(ResponseMode.ERROR);
        pointSenderAt(robot, listener.port());
        robot.clickOn("#sendButton");
        await(() -> text(robot, "#outcomeBadge").equals("APPLICATION ERROR"));
        assertThat(robot.lookup("#outcomeBadge").queryAs(Label.class).getStyleClass()).contains(Styles.FAILURE);
        assertThat(robot.lookup("#errList").queryAs(ListView.class).getItems().size()).isEqualTo(1);
        screenshot(robot, "03-application-error");
    }

    @Test
    void showsTimeoutInAmber(FxRobot robot) throws Exception {
        startListener(ResponseMode.NO_RESPONSE);
        pointSenderAt(robot, listener.port());
        robot.clickOn("#sendButton");
        await(() -> text(robot, "#outcomeBadge").equals("ACK TIMEOUT"));
        assertThat(robot.lookup("#outcomeBadge").queryAs(Label.class).getStyleClass()).contains(Styles.WARNING);
    }

    @Test
    void invalidMessageIsNotSent(FxRobot robot) throws Exception {
        startListener(ResponseMode.ACCEPT);
        pointSenderAt(robot, listener.port());
        robot.interact(() -> robot.lookup("#editor").queryAs(CodeArea.class).replaceText("PID|1||X"));
        robot.clickOn("#sendButton");
        await(() -> text(robot, "#outcomeBadge").equals("VALIDATION FAILED"));
        assertThat(text(robot, "#validationSummary")).contains("1 error");
    }

    @Test
    void invalidPortIsReportedWithoutSending(FxRobot robot) throws Exception {
        robot.interact(() -> robot.lookup("#portField").queryAs(TextField.class).setText(""));
        robot.clickOn("#sendButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(text(robot, "#outcomeBadge")).isEqualTo("NOT SENT");
        assertThat(text(robot, "#outcomeDetail")).contains("Port is required");
    }

    @Test
    void generateOptionsCanBeTurnedOff(FxRobot robot) throws Exception {
        startListener(ResponseMode.ACCEPT);
        pointSenderAt(robot, listener.port());
        robot.interact(() -> robot.lookup("#generateControlIdBox").queryAs(CheckBox.class).setSelected(false));
        robot.clickOn("#sendButton");
        await(() -> text(robot, "#outcomeBadge").equals("ACCEPTED"));
        assertThat(text(robot, "#sentControlIdValue")).isEqualTo("MSG00001");
    }

    @Test
    void builtInListenerReceivesAndAnswers(FxRobot robot) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        robot.interact(() -> {
            TabPane tabs = robot.lookup("#mainTabs").queryAs(TabPane.class);
            tabs.getSelectionModel().select(tabs.getTabs().stream()
                    .filter(t -> "listenerTab".equals(t.getId())).findFirst().orElseThrow());
        });
        int listenPort = port;
        robot.interact(() -> {
            robot.lookup("#listenerBindField").queryAs(TextField.class).setText("127.0.0.1");
            robot.lookup("#listenerPortField").queryAs(TextField.class).setText(String.valueOf(listenPort));
        });
        robot.clickOn("#listenerStartButton");
        await(() -> text(robot, "#listenerStateBadge").startsWith("Listening"));

        MllpClientConfig dest = MllpClientConfig.of("127.0.0.1", port);
        String sample = SampleMessages.all().get(3).text();
        assertThat(context.sender().send(dest, sample, SendOptions.DEFAULTS).outcome().name()).isEqualTo("ACCEPTED");

        @SuppressWarnings("unchecked")
        TableView<Object> table = robot.lookup("#listenerTable").queryAs(TableView.class);
        await(() -> table.getItems().size() == 1);

        // Switching the response mode applies immediately to the running listener.
        robot.interact(() -> {
            @SuppressWarnings("unchecked")
            ComboBox<ResponseMode> mode = robot.lookup("#listenerModeBox").queryAs(ComboBox.class);
            mode.setValue(ResponseMode.REJECT);
        });
        assertThat(context.sender().send(dest, sample, SendOptions.DEFAULTS).outcome().name())
                .isEqualTo("APPLICATION_REJECT");
        await(() -> table.getItems().size() == 2);
        robot.interact(() -> table.getSelectionModel().select(0));
        assertThat(robot.lookup("#listenerResponseText").queryAs(TextArea.class).getText()).contains("MSA|AR|");
        screenshot(robot, "04-listener");

        robot.clickOn("#listenerStartButton");
        await(() -> text(robot, "#listenerStateBadge").equals("Stopped"));
    }

    private void screenshot(FxRobot robot, String name) throws Exception {
        screenshot(robot, window, name);
    }

    /** Saves a PNG of {@code node} when {@link #SCREENSHOT_DIR} is set; otherwise does nothing. */
    static void screenshot(FxRobot robot, Node node, String name) throws Exception {
        if (SCREENSHOT_DIR == null) {
            return;
        }
        WaitForAsyncUtils.waitForFxEvents();
        WritableImage[] holder = new WritableImage[1];
        robot.interact(() -> holder[0] = node.snapshot(null, null));
        WritableImage img = holder[0];
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        Path dir = Files.createDirectories(Path.of(SCREENSHOT_DIR));
        ImageIO.write(out, "png", dir.resolve(name + ".png").toFile());
    }
}
