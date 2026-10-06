package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.update.UpdateChecker;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI test for the update check. */
@ExtendWith(ApplicationExtension.class)
class UpdateCheckUiTest {

    private AppContext context;
    private MainWindow window;
    private HttpServer server;

    @Start
    void start(Stage stage) throws IOException {
        Path home = Files.createTempDirectory("hl7sender-p7-ui");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        Styles.apply(scene);
        stage.setScene(scene);
        stage.show();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/latest", ex -> {
            byte[] b = ("{\"tag_name\":\"v9.9.0\",\"html_url\":\"https://example.org/9.9.0\","
                    + "\"body\":\"- Everything is faster\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        window.shutdown();
        context.close();
        server.stop(0);
    }

    private UpdateChecker checker(String current) {
        return new UpdateChecker("http://127.0.0.1:" + server.getAddress().getPort() + "/latest", current);
    }

    @Test
    void updateDialogShowsANewerReleaseAndRemembersChoices(FxRobot robot) throws Exception {
        Platform.runLater(() -> new UpdateDialog(window.getScene().getWindow(), context, null, checker("1.0.0"))
                .show());
        WaitForAsyncUtils.waitForFxEvents();
        Label result = robot.lookup("#updateResultLabel").queryAs(Label.class);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> result.getText().startsWith("Version 9.9.0"));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(result.getText()).contains("(you have 1.0.0)");
        assertThat(robot.lookup("#updateNotes").queryAs(TextArea.class).getText()).isEqualTo("- Everything is faster");
        MainWindowUiTest.screenshot(robot, result.getScene().getRoot(), "26-update");

        assertThat(context.settings().updates().checkAutomatically()).isFalse();
        robot.interact(() -> robot.lookup("#updateAutomaticBox").queryAs(CheckBox.class).setSelected(true));
        assertThat(context.settings().updates().checkAutomatically()).isTrue();
        robot.interact(() -> robot.lookup("#updateSkipButton").queryAs(Button.class).fire());
        assertThat(context.settings().updates().skippedVersion()).isEqualTo("9.9.0");
    }

    @Test
    void automaticCheckIsOptInDailyAndHonoursSkippedVersions() throws Exception {
        java.util.List<String> notified = new java.util.concurrent.CopyOnWriteArrayList<>();
        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()));
        Thread.sleep(300);
        assertThat(notified).as("off by default").isEmpty();

        context.updateSettings(s -> s.withUpdates(new io.hl7sender.core.config.AppSettings.Updates(true, 0, "")));
        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()));
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> notified.size() == 1);
        assertThat(context.settings().updates().lastCheckedMillis()).isPositive();

        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()));
        Thread.sleep(300);
        assertThat(notified).as("at most once a day").hasSize(1);

        context.updateSettings(s -> s.withUpdates(new io.hl7sender.core.config.AppSettings.Updates(true, 0, "9.9.0")));
        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()));
        Thread.sleep(500);
        assertThat(notified).as("skipped version").hasSize(1);
    }
}
