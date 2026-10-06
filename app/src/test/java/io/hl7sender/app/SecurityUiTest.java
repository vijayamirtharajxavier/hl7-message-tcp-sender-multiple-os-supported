package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.samples.SampleMessages;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.tls.TestCertificates;
import io.hl7sender.core.tls.TlsContexts;
import io.hl7sender.core.tls.TlsSettings;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI tests for TLS, presets and profiles, fan-out and queue encryption. */
@ExtendWith(ApplicationExtension.class)
class SecurityUiTest {

    private static final String PW = TestCertificates.PASSWORD;
    private static TestCertificates certs;

    private Path home;
    private AppPaths paths;
    private AppContext context;
    private MainWindow window;
    private TestListener listener;
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void certificates() throws Exception {
        certs = new TestCertificates(Files.createTempDirectory("hl7sender-p4-certs"));
    }

    @Start
    void start(Stage stage) throws IOException {
        home = Files.createTempDirectory("hl7sender-p4-ui");
        paths = new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs"));
        context = new AppContext(paths);
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        scene.getStylesheets().add(Styles.stylesheet());
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        if (window != null) {
            window.shutdown();
        }
        context.close();
        if (listener != null) {
            listener.close();
        }
    }

    private DeliveryEngine engine() {
        return context.engine().orElseThrow();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, condition::getAsBoolean);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void startListener(boolean tls, boolean requireClientCertificate) throws IOException {
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        if (tls) {
            listener.useTls(TlsContexts.server(certs.serverKeyStore, PW.toCharArray(),
                    requireClientCertificate ? certs.clientCert.toString() : "", null), requireClientCertificate);
        }
        listener.start();
    }

    private static void selectTab(FxRobot robot, String paneId, String tabId) {
        robot.interact(() -> {
            TabPane tabs = robot.lookup(paneId).queryAs(TabPane.class);
            tabs.getTabs().stream().filter(t -> tabId.equals(t.getId())).findFirst()
                    .ifPresent(t -> tabs.getSelectionModel().select(t));
        });
    }

    /** Fires a menu item that opens a modal dialog; firing inside interact() would block on showAndWait. */
    private static void fireLater(MenuItem item) {
        Platform.runLater(item::fire);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static MenuItem menuItem(MenuButton menu, String id) {
        return Fx.call(() -> menu.getItems().stream().filter(i -> id.equals(i.getId())).findFirst().orElseThrow());
    }

    private int count(long destinationId, MessageStatus status) {
        return engine().store().counts(destinationId).getOrDefault(status, 0);
    }

    private static String sample() {
        return SampleMessages.all().get(0).text();
    }

    @Test
    @SuppressWarnings("unchecked")
    void destinationDialogConfiguresMutualTls(FxRobot robot) throws Exception {
        startListener(true, true);
        selectTab(robot, "#mainTabs", "queueTab");
        robot.clickOn("#addDestinationButton");
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            robot.lookup("#destNameField").queryAs(TextField.class).setText("Secure EHR");
            robot.lookup("#destHostField").queryAs(TextField.class).setText("localhost");
            robot.lookup("#destPortField").queryAs(TextField.class).setText(String.valueOf(listener.port()));
        });
        selectTab(robot, "#destTabs", "destTlsTab");
        robot.interact(() -> {
            robot.lookup("#destTlsEnabledBox").queryAs(CheckBox.class).setSelected(true);
            robot.lookup("#destTrustStoreField").queryAs(TextField.class).setText(certs.serverCert.toString());
            robot.lookup("#destKeyStoreField").queryAs(TextField.class).setText(certs.clientKeyStore.toString());
            robot.lookup("#destKeyPasswordField").queryAs(PasswordField.class).setText(PW);
            robot.lookup("#destCheckCertificatesButton").queryAs(Button.class).fire();
        });
        ListView<String> found = robot.lookup("#destCertificateList").queryAs(ListView.class);
        assertThat(found.getItems()).anySatisfy(s -> assertThat(s).startsWith("[OK] Trusted: CN=localhost"));
        assertThat(found.getItems()).anySatisfy(s -> assertThat(s).startsWith("[OK] Client: CN=hl7sender-client"));
        MainWindowUiTest.screenshot(robot, robot.lookup("#destOkButton").query().getScene().getRoot(),
                "13-destination-tls");
        robot.clickOn("#destOkButton");

        await(() -> engine().destinations().size() == 1);
        DestinationConfig d = engine().destinations().get(0);
        assertThat(d.tls().enabled()).isTrue();
        assertThat(d.tls().mutual()).isTrue();
        assertThat(engine().hasKeyStorePassword(d)).isTrue();
        assertThat(engine().hasTrustStorePassword(d)).isFalse();
        // The password is in the secret store, not the queue database.
        for (String f : List.of("queue.db", "queue.db-wal")) {
            Path file = paths.dataDir().resolve(f);
            if (Files.exists(file)) {
                assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).doesNotContain(PW);
            }
        }

        engine().enqueue(d.id(), sample(), SendOptions.DEFAULTS);
        await(() -> count(d.id(), MessageStatus.ACKNOWLEDGED) == 1);
        assertThat(received).hasSize(1);
        TabPane views = robot.lookup("#queueViews").queryAs(TabPane.class);
        await(() -> views.getTabs().get(1).getText().equals("Delivered (1)"));
        assertThat(robot.lookup("#queueHeader").queryAs(Label.class).getText()).contains("tls://localhost:");
        MainWindowUiTest.screenshot(robot, window, "14-queue-tls");
    }

    @Test
    void expiringCertificatesAreFlagged(FxRobot robot) throws Exception {
        DestinationConfig d = engine().saveDestination(DestinationConfig.of("Old cert", "localhost", 2575)
                .withTls(new TlsSettings(true, certs.expiringCert.toString(), "", true, null)).withPaused(true));
        selectTab(robot, "#mainTabs", "queueTab");
        robot.interact(() -> robot.lookup(n -> n instanceof QueuePane).queryAs(QueuePane.class).refreshNow());
        Label warning = robot.lookup("#certificateWarningLabel").queryAs(Label.class);
        await(warning::isVisible);
        assertThat(warning.getText()).contains("CN=soon-expiring").contains("expires in");
        assertThat(engine().certificateWarnings(d)).hasSize(1);
    }

    @Test
    void presetsAndProfilesRoundTrip(FxRobot robot) throws Exception {
        selectTab(robot, "#mainTabs", "queueTab");
        MenuButton more = robot.lookup("#destinationMoreMenu").queryAs(MenuButton.class);
        fireLater(menuItem(more, "preset-Mirth-Connect-NextGen-Connect"));
        assertThat(robot.lookup("#destNameField").queryAs(TextField.class).getText()).isEqualTo("Mirth Connect");
        assertThat(robot.lookup("#destPortField").queryAs(TextField.class).getText()).isEqualTo("6661");
        assertThat(robot.lookup("#destNotesArea").queryAs(TextArea.class).getText()).contains("TCP Listener");
        robot.clickOn("#destOkButton");
        await(() -> engine().destinations().size() == 1);

        QueuePane pane = robot.lookup(n -> n instanceof QueuePane).queryAs(QueuePane.class);
        Path file = home.resolve("profiles.json");
        robot.interact(() -> {
            try {
                assertThat(pane.exportProfiles(file)).isEqualTo(1);
                assertThat(pane.importProfiles(file)).isEqualTo(1);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        assertThat(Files.readString(file)).contains("Mirth Connect").doesNotContain("secretRef");
        await(() -> engine().destinations().size() == 2);
        assertThat(engine().destinations()).extracting(DestinationConfig::name)
                .containsExactlyInAnyOrder("Mirth Connect", "Mirth Connect (2)");
        assertThat(engine().destinations()).allSatisfy(x -> assertThat(x.notes()).contains("MLLP"));
    }

    @Test
    void fanOutQueuesTheSameMessageForEachTickedDestination(FxRobot robot) throws Exception {
        startListener(false, false);
        DestinationConfig a = engine().saveDestination(DestinationConfig.of("A", "127.0.0.1", listener.port()));
        DestinationConfig b = engine().saveDestination(DestinationConfig.of("B", "127.0.0.1", listener.port()));
        MenuButton enqueue = robot.lookup("#enqueueButton").queryAs(MenuButton.class);
        await(() -> Fx.call(() -> enqueue.getItems().stream().anyMatch(i -> "enqueueFanOutItem".equals(i.getId()))));
        fireLater(menuItem(enqueue, "enqueueFanOutItem"));
        assertThat(robot.lookup("#fanOutOkButton").queryAs(Button.class).isDisabled()).isTrue();
        robot.interact(() -> {
            robot.lookup("#fanOut-" + a.id()).queryAs(CheckBox.class).setSelected(true);
            robot.lookup("#fanOut-" + b.id()).queryAs(CheckBox.class).setSelected(true);
        });
        MainWindowUiTest.screenshot(robot, robot.lookup("#fanOutOkButton").query().getScene().getRoot(),
                "15-fan-out");
        robot.clickOn("#fanOutOkButton");
        await(() -> received.size() == 2);
        assertThat(received.get(0).controlId()).isEqualTo(received.get(1).controlId());
        assertThat(robot.lookup("#outcomeBadge").queryAs(Label.class).getText()).isEqualTo("QUEUED");
        assertThat(robot.lookup("#outcomeDetail").queryAs(Label.class).getText())
                .startsWith("Queued for 2 of 2 destination(s) as batch F");
    }

    @Test
    @SuppressWarnings("unchecked")
    void directSendUsesTheTlsSettingsOfASavedDestination(FxRobot robot) throws Exception {
        startListener(true, false);
        DestinationConfig d = engine().saveDestination(DestinationConfig.of("Secure", "localhost", listener.port())
                .withTls(new TlsSettings(true, certs.serverCert.toString(), "", true, null)).withPaused(true));
        ComboBox<SenderPane.TlsChoice> tls = robot.lookup("#tlsBox").queryAs(ComboBox.class);
        await(() -> Fx.call(() -> tls.getItems().size() == 2));
        robot.interact(() -> {
            robot.lookup("#hostField").queryAs(TextField.class).setText("localhost");
            robot.lookup("#portField").queryAs(TextField.class).setText(String.valueOf(listener.port()));
            robot.lookup("#editor").queryAs(CodeArea.class).replaceText(sample());
            tls.setValue(tls.getItems().get(1));
        });
        assertThat(tls.getValue().destination().id()).isEqualTo(d.id());
        robot.clickOn("#sendButton");
        await(() -> "ACCEPTED".equals(robot.lookup("#outcomeBadge").queryAs(Label.class).getText()));
        assertThat(received).hasSize(1);
    }

    @Test
    void testListenerAcceptsTls(FxRobot robot) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        selectTab(robot, "#mainTabs", "listenerTab");
        robot.interact(() -> {
            robot.lookup("#listenerBindField").queryAs(TextField.class).setText("127.0.0.1");
            robot.lookup("#listenerPortField").queryAs(TextField.class).setText(String.valueOf(port));
            robot.lookup("#listenerTlsBox").queryAs(CheckBox.class).setSelected(true);
            robot.lookup("#listenerKeyStoreField").queryAs(TextField.class).setText(certs.serverKeyStore.toString());
            robot.lookup("#listenerKeyPasswordField").queryAs(PasswordField.class).setText(PW);
            robot.lookup("#listenerStartButton").queryAs(Button.class).fire();
        });
        Label badge = robot.lookup("#listenerStateBadge").queryAs(Label.class);
        await(() -> badge.getText().endsWith("(TLS)"));
        MainWindowUiTest.screenshot(robot, window, "16-listener-tls");

        SendResult r = context.sender().send(MllpClientConfig.of("localhost", port),
                TlsContexts.client(new TlsSettings(true, certs.serverCert.toString(), "", true, null), null, null),
                sample(), SendOptions.DEFAULTS);
        assertThat(r.outcome()).isEqualTo(SendOutcome.ACCEPTED);
        TableView<?> table = robot.lookup(n -> n instanceof TableView<?> && n.getScene() != null
                && isInside(n, ListenerPane.class)).queryAs(TableView.class);
        await(() -> table.getItems().size() == 1);
    }

    private static boolean isInside(Node n, Class<?> type) {
        for (Node p = n; p != null; p = p.getParent()) {
            if (type.isInstance(p)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void securityDialogEncryptsTheQueueAtNextStart(FxRobot robot) throws Exception {
        engine().saveDestination(DestinationConfig.of("Keep me", "localhost", 2575).withPaused(true));
        Platform.runLater(() -> new SecurityDialog(window.getScene().getWindow(), context).showAndWait());
        WaitForAsyncUtils.waitForFxEvents();
        // Tests use the file-based secret store, so the dialog explains its limits.
        assertThat(robot.lookup("#securitySecretStoreLabel").queryAs(Label.class).getText())
                .contains("encrypted file");
        robot.interact(() -> robot.lookup("#securityEncryptBox").queryAs(CheckBox.class).setSelected(true));
        assertThat(robot.lookup("#securityQueueStateLabel").queryAs(Label.class).getText())
                .contains("will be encrypted the next time");
        MainWindowUiTest.screenshot(robot, robot.lookup("#securityOkButton").query().getScene().getRoot(),
                "17-security");
        robot.clickOn("#securityOkButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(context.settings().security().encryptQueue()).isTrue();

        // Restart: the queue is encrypted in place and keeps its destinations.
        robot.interact(() -> window.shutdown());
        window = null;
        context.close();
        context = new AppContext(paths);
        assertThat(context.queueEncrypted()).isTrue();
        assertThat(context.databaseKey()).hasValueSatisfying(k -> assertThat(k).matches("[0-9a-f]{64}"));
        assertThat(QueueStore.isEncrypted(paths.dataDir().resolve("queue.db"))).isTrue();
        assertThat(engine().destinations()).extracting(DestinationConfig::name).containsExactly("Keep me");
    }
}
