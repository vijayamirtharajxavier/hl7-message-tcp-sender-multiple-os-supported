package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.update.UpdateChecker;
import io.hl7sender.core.update.UpdateInstaller;
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

    /** Release notes as GitHub returns them: Markdown with a link, an image tag, emphasis, code and a table. */
    private static final String NOTES_JSON = "A small update to [HL7 Sender 1.0.0](https://example.org/1.0.0).\\n\\n"
            + "<img src=\\\"https://example.org/icon.png\\\" width=\\\"128\\\">\\n\\n## Changed\\n\\n"
            + "- **New icon.** Fields split by pipes, as in `MSH|^~\\\\&|`.\\n\\n## Install\\n\\n"
            + "| System | File |\\n|---|---|\\n| Windows | `HL7.Sender-9.9.0.msi` |\\n";

    private static final String TARBALL = "hl7-sender-9.9.0-linux-x64.tar.gz";
    private static final byte[] TARBALL_BYTES = "pretend installer".getBytes(StandardCharsets.UTF_8);

    private AppContext context;
    private MainWindow window;
    private HttpServer server;
    private Path home;

    @Start
    void start(Stage stage) throws IOException {
        home = Files.createTempDirectory("hl7sender-p7-ui");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        Styles.apply(scene);
        stage.setScene(scene);
        stage.show();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/latest", ex -> {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/files/";
            byte[] b = ("{\"tag_name\":\"v9.9.0\",\"html_url\":\"https://example.org/9.9.0\","
                    + "\"body\":\"" + NOTES_JSON + "\",\"assets\":["
                    + "{\"name\":\"" + TARBALL + "\",\"browser_download_url\":\"" + base + TARBALL + "\"},"
                    + "{\"name\":\"SHA256SUMS-Linux.txt\",\"browser_download_url\":\"" + base
                    + "SHA256SUMS-Linux.txt\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/files/", ex -> {
            byte[] b;
            if (ex.getRequestURI().getPath().endsWith(".txt")) {
                Path probe = Files.createTempFile("probe", ".bin");
                Files.write(probe, TARBALL_BYTES);
                b = (sha256(probe) + "  " + TARBALL + "\n").getBytes(StandardCharsets.UTF_8);
            } else {
                b = TARBALL_BYTES;
            }
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

    private static String sha256(Path file) throws IOException {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Pretends this copy is the portable Linux folder in the test's home. */
    private void portableInstallation() {
        Path dir = home.resolve("apps").resolve("hl7-sender");
        context.setUpdateDetection(new UpdateInstaller.Detection(java.util.Optional.of(new UpdateInstaller.Installation(
                UpdateInstaller.Kind.PORTABLE, dir, dir.resolve("bin").resolve("hl7-sender"))),
                java.util.Optional.empty()));
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
        assertThat(robot.lookup("#updateNotes").queryAs(TextArea.class).getText()).isEqualTo("""
                A small update to HL7 Sender 1.0.0.

                Changed

                \u2022 New icon. Fields split by pipes, as in MSH|^~\\&|.

                Install

                Windows: HL7.Sender-9.9.0.msi""");
        // The window grew to fit the result, notes and buttons that arrived after it opened.
        javafx.scene.control.DialogPane pane = (javafx.scene.control.DialogPane) result.getScene().getRoot();
        assertThat(pane.getHeight()).isGreaterThanOrEqualTo(pane.prefHeight(pane.getWidth()) - 1);
        assertThat(robot.lookup("#updateSkipButton").queryAs(Button.class).isVisible()).isTrue();
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
        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()),
                p -> notified.add("ready " + p.version()));
        Thread.sleep(300);
        assertThat(notified).as("off by default").isEmpty();

        context.updateSettings(s -> s.withUpdates(new io.hl7sender.core.config.AppSettings.Updates(true, 0, "")));
        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()),
                p -> notified.add("ready " + p.version()));
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> notified.size() == 1);
        assertThat(context.settings().updates().lastCheckedMillis()).isPositive();
        assertThat(notified).containsExactly("9.9.0");
        assertThat(context.pendingUpdate()).as("checking only: nothing installed").isEmpty();

        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()),
                p -> notified.add("ready " + p.version()));
        Thread.sleep(300);
        assertThat(notified).as("at most once a day").hasSize(1);

        context.updateSettings(s -> s.withUpdates(new io.hl7sender.core.config.AppSettings.Updates(true, 0, "9.9.0")));
        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()),
                p -> notified.add("ready " + p.version()));
        Thread.sleep(500);
        assertThat(notified).as("skipped version").hasSize(1);
    }

    @Test
    void installAutomaticallyDownloadsAndInstallsWhenTheAppCloses() throws Exception {
        portableInstallation();
        java.util.List<String> notified = new java.util.concurrent.CopyOnWriteArrayList<>();
        context.updateSettings(s -> s.withUpdates(s.updates().withCheckAutomatically(true)
                .withInstallAutomatically(true)));
        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()),
                p -> notified.add("ready " + p.version()));
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> notified.size() == 1);
        assertThat(notified).containsExactly("ready 9.9.0");
        AppContext.PendingUpdate pending = context.pendingUpdate().orElseThrow();
        assertThat(pending.relaunch()).as("installed when the user closes the app, not restarted").isFalse();
        assertThat(pending.update().file()).hasBinaryContent(TARBALL_BYTES);
        assertThat(pending.update().file().startsWith(home.resolve("data").resolve("updates"))).isTrue();

        // Next start, before the daily check is due: the download is picked up again without asking GitHub.
        context.cancelInstallOnExit();
        UpdateDialog.checkInBackground(context, checker("1.0.0"), r -> notified.add(r.latest().version()),
                p -> notified.add("ready " + p.version()));
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> notified.size() == 2);
        assertThat(context.pendingUpdate()).isPresent();
    }

    @Test
    void installNowDownloadsVerifiesAndRestarts(FxRobot robot) throws Exception {
        portableInstallation();
        java.util.concurrent.atomic.AtomicBoolean exited = new java.util.concurrent.atomic.AtomicBoolean();
        Platform.runLater(() -> {
            UpdateDialog d = new UpdateDialog(window.getScene().getWindow(), context, null, checker("1.0.0"));
            d.setExitApp(() -> exited.set(true));
            d.show();
        });
        WaitForAsyncUtils.waitForFxEvents();
        Button install = robot.lookup("#updateInstallButton").queryAs(Button.class);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, install::isVisible);
        assertThat(robot.lookup("#updateInstallAutomaticBox").queryAs(CheckBox.class).isDisabled()).isFalse();
        robot.interact(install::fire);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> robot.lookup("#updateReadyPane").tryQuery().isPresent());
        WaitForAsyncUtils.waitForFxEvents();
        javafx.scene.control.DialogPane ready = robot.lookup("#updateReadyPane")
                .queryAs(javafx.scene.control.DialogPane.class);
        assertThat(ready.getHeaderText()).isEqualTo("Version 9.9.0 is downloaded and matches its published checksum.");
        MainWindowUiTest.screenshot(robot, ready, "44-update-ready");
        robot.interact(() -> ((Button) ready.lookupButton(ready.getButtonTypes().get(0))).fire());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(exited).isTrue();
        AppContext.PendingUpdate pending = context.pendingUpdate().orElseThrow();
        assertThat(pending.relaunch()).isTrue();
        assertThat(pending.update().version()).isEqualTo("9.9.0");
        assertThat(pending.update().installation().kind()).isEqualTo(UpdateInstaller.Kind.PORTABLE);
    }

    @Test
    void installingCanBeTurnedOffByTheAdministrator(FxRobot robot) throws Exception {
        context.setUpdateDetection(new UpdateInstaller.Detection(java.util.Optional.empty(),
                java.util.Optional.of(UpdateInstaller.Unsupported.DISABLED)));
        Platform.runLater(() -> new UpdateDialog(window.getScene().getWindow(), context, null, checker("1.0.0"))
                .show());
        WaitForAsyncUtils.waitForFxEvents();
        Label note = robot.lookup("#updateInstallNote").queryAs(Label.class);
        WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, note::isVisible);
        assertThat(note.getText()).contains("your administrator turned it off");
        assertThat(robot.lookup("#updateInstallButton").queryAs(Button.class).isVisible()).isFalse();
        assertThat(robot.lookup("#updateInstallAutomaticBox").queryAs(CheckBox.class).isDisabled()).isTrue();
    }
}
