package io.hl7sender.app;

import io.hl7sender.core.hl7.validation.ConformanceProfile;
import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.queue.AckPolicy;
import io.hl7sender.core.queue.CircuitBreakerSettings;
import io.hl7sender.core.queue.ConnectionMode;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.RetryPolicy;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.tls.CertificateInfo;
import io.hl7sender.core.tls.TlsSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Creates or edits a queue destination: address, timeouts, connection mode, retry policy,
 * circuit breaker, what to do on AE, AR and timeouts, TLS, and notes.
 */
final class DestinationDialog extends Dialog<DestinationDialog.Result> {

    private static final String RETRY = "Retry";
    private static final String DEAD_LETTER = "Dead-letter";
    private static final String KEEP_PASSWORD = "stored; leave blank to keep";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * What the user entered.
     *
     * @param config             the destination (id 0 if new)
     * @param trustStorePassword new trust-store password, {@code ""} to remove it, or null to keep it
     * @param keyStorePassword   new key-store password, {@code ""} to remove it, or null to keep it
     */
    record Result(DestinationConfig config, String trustStorePassword, String keyStorePassword) {
    }

    private final DestinationConfig original;
    private final DeliveryEngine engine;
    private final TextField name = new TextField();
    private final TextField host = new TextField();
    private final TextField port;
    private final TextField connectTimeout;
    private final TextField ackTimeout;
    private final ComboBox<String> charset = new ComboBox<>(FXCollections.observableArrayList(
            "UTF-8", "ISO-8859-1", "windows-1252", "US-ASCII", "UTF-16"));
    private final CheckBox waitForAck = new CheckBox("Wait for ACK");
    private final ComboBox<ConnectionMode> connectionMode =
            new ComboBox<>(FXCollections.observableArrayList(ConnectionMode.values()));
    private final TextField maxAttempts;
    private final TextField baseDelay;
    private final TextField maxDelay;
    private final TextField jitter;
    private final TextField cbThreshold;
    private final TextField cbCoolDown;
    private final ComboBox<String> onReject = actionBox();
    private final ComboBox<String> onError = actionBox();
    private final ComboBox<String> onCommitReject = actionBox();
    private final ComboBox<String> onCommitError = actionBox();
    private final ComboBox<String> onTimeout = actionBox();
    private final TextField maxPerSecond;
    private final ComboBox<ValidationLevel> validationLevel =
            new ComboBox<>(FXCollections.observableArrayList(ValidationLevel.values()));
    private final TextField profilePath = new TextField();
    private final TextField watchFolder = new TextField();
    private final CheckBox tlsEnabled = new CheckBox("Use TLS (MLLP over TLS)");
    private final TextField trustStore = new TextField();
    private final PasswordField trustPassword = new PasswordField();
    private final TextField keyStore = new TextField();
    private final PasswordField keyPassword = new PasswordField();
    private final CheckBox verifyHostname = new CheckBox("Verify that the server certificate matches the host");
    private final TextField protocols = new TextField();
    private final CheckBox forgetPasswords = new CheckBox("Forget the stored passwords");
    private final ListView<String> certificates = new ListView<>();
    private final TextArea notes = new TextArea();
    private final TextArea script = new TextArea();
    private final ComboBox<TransportChoice> transportBox = new ComboBox<>();
    private final GridPane transportGrid = new GridPane();
    private final java.util.Map<String, javafx.scene.control.TextInputControl> transportFields =
            new java.util.LinkedHashMap<>();
    private java.util.Map<String, String> transportValues = java.util.Map.of();
    private final TextArea scriptInput = new TextArea();
    private final TextArea scriptOutput = new TextArea();
    private final TabPane tabs = new TabPane();
    private final Label error = new Label();

    /**
     * @param existing the destination to edit; one with id 0 (e.g. a preset) is used as the starting point
     *                 for a new destination; null starts from defaults
     * @param engine   used to find stored passwords and inspect certificates
     */
    DestinationDialog(Window owner, DestinationConfig existing, DeliveryEngine engine) {
        this.original = existing != null && existing.id() != 0 ? existing : null;
        this.engine = engine;
        DestinationConfig d = existing != null ? existing : DestinationConfig.of("New destination", "localhost", 2575);
        initOwner(owner);
        setTitle(original == null ? "Add destination" : "Edit destination");
        setHeaderText(original == null ? "Messages queued to this destination are delivered in order, with retries."
                : "Changes apply to the next delivery attempt.");

        port = Fields.integer("destPortField", d.port(), 5, 5);
        connectTimeout = Fields.integer("destConnectTimeoutField", d.connectTimeoutMs(), 7, 6);
        ackTimeout = Fields.integer("destAckTimeoutField", d.ackTimeoutMs(), 7, 6);
        maxAttempts = Fields.integer("destMaxAttemptsField", d.retry().maxAttempts(), 5, 5);
        baseDelay = Fields.integer("destBaseDelayField", (int) d.retry().baseDelayMs(), 9, 7);
        maxDelay = Fields.integer("destMaxDelayField", (int) d.retry().maxDelayMs(), 9, 7);
        jitter = Fields.integer("destJitterField", (int) Math.round(d.retry().jitter() * 100), 3, 3);
        cbThreshold = Fields.integer("destCbThresholdField", d.circuitBreaker().failureThreshold(), 4, 4);
        cbCoolDown = Fields.integer("destCbCoolDownField", (int) d.circuitBreaker().coolDownMs(), 9, 7);
        maxPerSecond = Fields.integer("destMaxPerSecondField", d.maxPerSecond(), 5, 5);
        maxPerSecond.setTooltip(new Tooltip("Maximum messages per second; 0 = as fast as the receiver acknowledges"));
        validationLevel.setId("destValidationLevelBox");
        validationLevel.setValue(d.validationLevel());
        profilePath.setId("destProfileField");
        profilePath.setText(d.profilePath());
        profilePath.setPromptText("optional conformance profile (.xml)");
        watchFolder.setId("destWatchFolderField");
        watchFolder.setText(d.watchFolder());
        watchFolder.setPromptText("optional inbox folder; files are queued automatically");

        name.setId("destNameField");
        name.setText(existing == null ? "" : d.name());
        buildTls(d);
        notes.setId("destNotesArea");
        notes.setText(d.notes());
        notes.setWrapText(true);
        notes.setPromptText("Contacts, receiver configuration, go-live dates...");
        notes.setPrefRowCount(14);
        name.setPromptText("e.g. Mirth test");
        host.setId("destHostField");
        host.setText(d.host());
        charset.setEditable(true);
        charset.setValue(d.charset());
        waitForAck.setId("destWaitForAckBox");
        waitForAck.setSelected(d.ackMode() == AckMode.EXPECT_ACK);
        connectionMode.setId("destConnectionModeBox");
        connectionMode.setValue(d.connectionMode());
        connectionMode.setTooltip(new Tooltip("PERSISTENT keeps one connection open; PER_MESSAGE reconnects for "
                + "every message"));
        maxAttempts.setTooltip(new Tooltip("0 = retry forever"));
        cbThreshold.setTooltip(new Tooltip("Consecutive failures that pause delivery; 0 = disabled"));
        onReject.setId("destOnRejectBox");
        onReject.setValue(label(d.ackPolicy().actionFor(SendOutcome.APPLICATION_REJECT)));
        onError.setId("destOnErrorBox");
        onError.setValue(label(d.ackPolicy().actionFor(SendOutcome.APPLICATION_ERROR)));
        onCommitReject.setId("destOnCommitRejectBox");
        onCommitReject.setValue(label(d.ackPolicy().actionFor(SendOutcome.COMMIT_REJECT)));
        onCommitReject.setTooltip(new Tooltip("CR: the receiver does not accept the message type, version or "
                + "processing ID, so resending the same message cannot help"));
        onCommitError.setId("destOnCommitErrorBox");
        onCommitError.setValue(label(d.ackPolicy().actionFor(SendOutcome.COMMIT_ERROR)));
        onCommitError.setTooltip(new Tooltip("CE: the receiver could not commit the message for another reason, "
                + "such as a sequence number error, which may clear"));
        onTimeout.setId("destOnTimeoutBox");
        onTimeout.setValue(label(d.ackPolicy().actionFor(SendOutcome.ACK_TIMEOUT)));
        error.setId("destErrorLabel");
        error.getStyleClass().add("issue-error");
        error.setWrapText(true);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-pane");
        int r = 0;
        grid.addRow(r++, Fields.label("Name"), name);
        grid.addRow(r++, Fields.label("Host"), host, Fields.label("Port"), port);
        grid.addRow(r++, Fields.label("Connect timeout (ms)"), connectTimeout, Fields.label("ACK timeout (ms)"),
                ackTimeout);
        grid.addRow(r++, Fields.label("Charset"), charset, Fields.label("Connection"), connectionMode);
        grid.addRow(r++, new Label(), waitForAck);
        grid.add(section("Retry"), 0, r++, 4, 1);
        grid.addRow(r++, Fields.label("Max attempts"), maxAttempts, Fields.label("Jitter (%)"), jitter);
        grid.addRow(r++, Fields.label("First delay (ms)"), baseDelay, Fields.label("Max delay (ms)"), maxDelay);
        grid.add(section("Circuit breaker"), 0, r++, 4, 1);
        grid.addRow(r++, Fields.label("Open after failures"), cbThreshold, Fields.label("Cool-down (ms)"),
                cbCoolDown);
        grid.add(section("Acknowledgment policy"), 0, r++, 4, 1);
        grid.addRow(r++, Fields.label("On AR (reject)"), onReject, Fields.label("On AE (error)"), onError);
        grid.addRow(r++, Fields.label("On CR (commit reject)"), onCommitReject, Fields.label("On CE (commit error)"),
                onCommitError);
        grid.addRow(r++, Fields.label("On timeout / no ACK"), onTimeout);
        grid.add(section("Validation"), 0, r++, 4, 1);
        grid.addRow(r++, Fields.label("Level"), validationLevel);
        GridPane.setColumnSpan(validationLevel, 3);
        grid.addRow(r++, Fields.label("Conformance profile"), withBrowse(profilePath, "Choose conformance profile",
                false, new FileChooser.ExtensionFilter("Conformance profile", "*.xml")));
        GridPane.setColumnSpan(profilePath.getParent(), 3);
        grid.add(section("Intake and throughput"), 0, r++, 4, 1);
        grid.addRow(r++, Fields.label("Max messages/second"), maxPerSecond);
        grid.addRow(r++, Fields.label("Watch folder"), withBrowse(watchFolder, "Choose watch folder", true));
        GridPane.setColumnSpan(watchFolder.getParent(), 3);

        // The form is taller than small screens (a 1366 x 768 laptop), so it scrolls rather than pushing the OK
        // button off the screen. The rest of the dialog (title, tabs, error and buttons) needs about 240 px.
        javafx.scene.control.ScrollPane generalScroll = new javafx.scene.control.ScrollPane(grid);
        generalScroll.setId("destGeneralScroll");
        generalScroll.setFitToWidth(true);
        generalScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        generalScroll.setMaxHeight(Math.max(240, javafx.stage.Screen.getPrimary().getVisualBounds().getHeight()
                - 240));
        Tab general = new Tab("Connection and delivery", generalScroll);
        Tab security = new Tab("TLS", tlsPane());
        security.setId("destTlsTab");
        Tab notesTab = new Tab("Notes", new VBox(notes));
        notesTab.setId("destNotesTab");
        Tab transportTab = new Tab(Messages.get("transport.tab"), transportPane(d));
        transportTab.setId("destTransportTab");
        Tab scriptTab = new Tab(Messages.get("script.tab"), scriptPane(d));
        scriptTab.setId("destScriptTab");
        tabs.getTabs().addAll(general, transportTab, security, scriptTab, notesTab);
        tabs.setId("destTabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        VBox content = new VBox(8, tabs, error);
        content.setPadding(new Insets(0, 0, 4, 0));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("destOkButton");
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            if (build().isEmpty()) {
                e.consume();
            }
        });
        setResultConverter(button -> button == ButtonType.OK ? build().orElse(null) : null);
    }

    private void buildTls(DestinationConfig d) {
        TlsSettings t = d.tls();
        tlsEnabled.setId("destTlsEnabledBox");
        tlsEnabled.setSelected(t.enabled());
        trustStore.setId("destTrustStoreField");
        trustStore.setText(t.trustStorePath());
        trustStore.setPromptText("empty = the system's trusted certificate authorities");
        keyStore.setId("destKeyStoreField");
        keyStore.setText(t.keyStorePath());
        keyStore.setPromptText("only for mutual TLS: your client certificate and key (.p12 / .jks)");
        trustPassword.setId("destTrustPasswordField");
        keyPassword.setId("destKeyPasswordField");
        boolean trustStored = original != null && engine.hasTrustStorePassword(original);
        boolean keyStored = original != null && engine.hasKeyStorePassword(original);
        trustPassword.setPromptText(trustStored ? KEEP_PASSWORD : "if the trust store has one");
        keyPassword.setPromptText(keyStored ? KEEP_PASSWORD : "key store password");
        forgetPasswords.setId("destForgetPasswordsBox");
        forgetPasswords.setVisible(trustStored || keyStored);
        forgetPasswords.setManaged(trustStored || keyStored);
        verifyHostname.setId("destVerifyHostnameBox");
        verifyHostname.setSelected(t.verifyHostname());
        verifyHostname.setTooltip(new Tooltip("Turn off only for test servers whose certificate names another host"));
        protocols.setId("destProtocolsField");
        protocols.setText(t.protocols());
        certificates.setId("destCertificateList");
        certificates.setPrefHeight(150);
        certificates.setPlaceholder(new Label("Click Check certificates to list them and their expiry dates."));
    }

    private VBox tlsPane() {
        Button check = new Button("Check certificates");
        check.setId("destCheckCertificatesButton");
        check.setOnAction(e -> checkCertificates());

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-pane");
        int r = 0;
        grid.addRow(r++, new Label(), tlsEnabled);
        grid.addRow(r++, Fields.label("Trust store"), withBrowse(trustStore, "Choose trust store", false, stores()));
        grid.addRow(r++, Fields.label("Trust store password"), trustPassword);
        grid.addRow(r++, Fields.label("Client key store"), withBrowse(keyStore, "Choose client key store", false,
                stores()));
        grid.addRow(r++, Fields.label("Key store password"), keyPassword);
        grid.addRow(r++, new Label(), forgetPasswords);
        grid.addRow(r++, new Label(), verifyHostname);
        grid.addRow(r++, Fields.label("Protocols"), protocols);
        GridPane.setHgrow(trustStore.getParent(), Priority.ALWAYS);

        Label where = new Label("Passwords are kept in the " + engine.secrets().description()
                + ", never in the queue database or exported profiles.");
        where.getStyleClass().add("field-label");
        where.setWrapText(true);
        HBox actions = new HBox(8, check);
        VBox box = new VBox(8, grid, where, actions, certificates);
        box.setPadding(new Insets(8, 0, 0, 0));
        for (Node n : List.of(trustStore.getParent(), trustPassword, keyStore.getParent(), keyPassword,
                verifyHostname, protocols, forgetPasswords, check)) {
            n.disableProperty().bind(tlsEnabled.selectedProperty().not());
        }
        return box;
    }

    private static FileChooser.ExtensionFilter[] stores() {
        return new FileChooser.ExtensionFilter[] {
            new FileChooser.ExtensionFilter("Key stores and certificates", "*.p12", "*.pfx", "*.jks", "*.pem",
                    "*.crt", "*.cer"),
            new FileChooser.ExtensionFilter("All files", "*.*")};
    }

    private TlsSettings tlsSettings() {
        String p = protocols.getText() == null ? "" : protocols.getText().replace(" ", "");
        for (String proto : p.split(",")) {
            if (!proto.isEmpty() && !proto.matches("TLSv1(\\.[0-3])?")) {
                throw new IllegalArgumentException("Unknown TLS protocol: " + proto + " (use e.g. TLSv1.3,TLSv1.2)");
            }
        }
        return new TlsSettings(tlsEnabled.isSelected(), trustStore.getText(), keyStore.getText(),
                verifyHostname.isSelected(), p);
    }

    /** The typed password, "" to forget a stored one, or null to keep what is stored. */
    private String password(PasswordField field) {
        String typed = field.getText();
        if (typed != null && !typed.isEmpty()) {
            return typed;
        }
        return forgetPasswords.isSelected() ? "" : null;
    }

    private void checkCertificates() {
        try {
            TlsSettings t = tlsSettings();
            DestinationConfig base = original != null ? original : DestinationConfig.of("check", "localhost", 2575);
            DestinationConfig probe = base.withTls(new TlsSettings(true, t.trustStorePath(), t.keyStorePath(),
                    t.verifyHostname(), t.protocols()));
            List<CertificateInfo> found = engine.certificates(probe, password(trustPassword),
                    password(keyPassword));
            Clock clock = Clock.systemUTC();
            certificates.getItems().setAll(found.stream().map(c -> describe(c, clock)).toList());
            if (found.isEmpty()) {
                certificates.getItems().setAll(t.trustStorePath().isEmpty()
                        ? "No trust store: the system's certificate authorities are trusted."
                        : "No certificates found.");
            }
            error.setText("");
        } catch (IOException | IllegalArgumentException e) {
            certificates.getItems().clear();
            error.setText(e.getMessage());
        }
    }

    static String describe(CertificateInfo c, Clock clock) {
        String when = DAY.format(c.notAfter().atZone(ZoneId.systemDefault()));
        String state = switch (c.status(clock)) {
            case VALID -> "OK";
            case EXPIRING_SOON -> "EXPIRES SOON";
            case EXPIRED -> "EXPIRED";
            case NOT_YET_VALID -> "NOT YET VALID";
        };
        return "[" + state + "] " + c.source() + ": " + c.subject() + ", valid until " + when
                + (c.status(clock) == CertificateInfo.Status.EXPIRED ? "" : " (" + c.daysLeft(clock) + " days)");
    }

    /** Builds the result from the form, or shows the problem and returns empty. */
    private Optional<Result> build() {
        try {
            AckPolicy policy = AckPolicy.DEFAULT
                    .with(SendOutcome.APPLICATION_REJECT, action(onReject))
                    .with(SendOutcome.APPLICATION_ERROR, action(onError))
                    .with(SendOutcome.COMMIT_REJECT, action(onCommitReject))
                    .with(SendOutcome.COMMIT_ERROR, action(onCommitError));
            for (SendOutcome o : new SendOutcome[] {SendOutcome.ACK_TIMEOUT, SendOutcome.CONNECTION_CLOSED,
                SendOutcome.INVALID_ACK, SendOutcome.CONTROL_ID_MISMATCH}) {
                policy = policy.with(o, action(onTimeout));
            }
            long base = Fields.parse(baseDelay, "First delay", 0, Integer.MAX_VALUE);
            long max = Fields.parse(maxDelay, "Max delay", 0, Integer.MAX_VALUE);
            if (max < base) {
                throw new IllegalArgumentException("Max delay must be at least the first delay");
            }
            DestinationConfig d = new DestinationConfig(
                    original == null ? 0 : original.id(),
                    name.getText(),
                    host.getText() == null ? "" : host.getText(),
                    mllp() || !port.getText().isBlank() ? Fields.parse(port, "Port", mllp() ? 1 : 0, 65_535) : 0,
                    Fields.parse(connectTimeout, "Connect timeout", 1, 600_000),
                    Fields.parse(ackTimeout, "ACK timeout", 1, 3_600_000),
                    charset.getValue() == null ? "" : charset.getValue().trim(),
                    waitForAck.isSelected() ? AckMode.EXPECT_ACK : AckMode.NO_ACK,
                    connectionMode.getValue() == null ? ConnectionMode.PERSISTENT : connectionMode.getValue(),
                    new RetryPolicy(Fields.parse(maxAttempts, "Max attempts", 0, 100_000), base, max,
                            Fields.parse(jitter, "Jitter", 0, 100) / 100.0),
                    new CircuitBreakerSettings(Fields.parse(cbThreshold, "Circuit breaker threshold", 0, 10_000),
                            Fields.parse(cbCoolDown, "Cool-down", 0, Integer.MAX_VALUE)),
                    policy,
                    original != null && original.paused(),
                    Fields.parse(maxPerSecond, "Max messages/second", 0, 100_000),
                    validationLevel.getValue() == null ? ValidationLevel.STANDARD : validationLevel.getValue(),
                    profilePath.getText(),
                    watchFolder.getText(),
                    tlsSettings(),
                    original == null ? "" : original.secretRef(),
                    notes.getText() == null ? "" : notes.getText()).withScript(script.getText() == null ? ""
                    : script.getText().strip()).withTransport(transportBox.getValue().id(), transportOptions());
            try {
                engine.transports().validate(d.transport(), d.transportOptions());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(Messages.get("transport.invalid", e.getMessage()));
            }
            if (!d.script().isEmpty()) {
                try {
                    io.hl7sender.core.script.MessageScript.compile(d.script());
                } catch (io.hl7sender.core.script.MessageScript.ScriptFailure e) {
                    throw new IllegalArgumentException(Messages.get("script.invalid", e.getMessage()));
                }
            }
            if (!d.profilePath().isEmpty()) {
                try {
                    ConformanceProfile.load(Path.of(d.profilePath()));
                } catch (IOException | RuntimeException e) {
                    throw new IllegalArgumentException("Conformance profile: " + e.getMessage());
                }
            }
            if (!d.watchFolder().isEmpty() && !Files.isDirectory(Path.of(d.watchFolder()))) {
                throw new IllegalArgumentException("Watch folder does not exist: " + d.watchFolder());
            }
            if (d.tls().enabled()) {
                requireFile(d.tls().trustStorePath(), "Trust store");
                requireFile(d.tls().keyStorePath(), "Client key store");
            }
            error.setText("");
            return Optional.of(new Result(d, password(trustPassword), password(keyPassword)));
        } catch (IllegalArgumentException e) {
            error.setText(e.getMessage() == null ? "Please complete all fields" : e.getMessage());
            return Optional.empty();
        }
    }

    private static void requireFile(String path, String what) {
        if (!path.isEmpty() && !Files.isRegularFile(Path.of(path))) {
            throw new IllegalArgumentException(what + " not found: " + path);
        }
    }

    private HBox withBrowse(TextField field, String title, boolean directory,
                            FileChooser.ExtensionFilter... filters) {
        Button browse = new Button("Browse...");
        browse.setId(field.getId() + "Browse");
        browse.setOnAction(e -> {
            java.io.File chosen;
            if (directory) {
                DirectoryChooser chooser = new DirectoryChooser();
                chooser.setTitle(title);
                chosen = chooser.showDialog(getDialogPane().getScene().getWindow());
            } else {
                FileChooser chooser = new FileChooser();
                chooser.setTitle(title);
                chooser.getExtensionFilters().addAll(filters);
                chosen = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
            }
            if (chosen != null) {
                field.setText(chosen.getAbsolutePath());
            }
        });
        HBox box = new HBox(6, field, browse);
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    private static ComboBox<String> actionBox() {
        return new ComboBox<>(FXCollections.observableArrayList(RETRY, DEAD_LETTER));
    }

    private static AckPolicy.Action action(ComboBox<String> box) {
        return DEAD_LETTER.equals(box.getValue()) ? AckPolicy.Action.DEAD_LETTER : AckPolicy.Action.RETRY;
    }

    private static String label(AckPolicy.Action action) {
        return action == AckPolicy.Action.DEAD_LETTER ? DEAD_LETTER : RETRY;
    }

    private static Node section(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("section-title");
        VBox box = new VBox(4, new Separator(), label);
        box.setPadding(new Insets(6, 0, 0, 0));
        return box;
    }

    /** The Script tab: the transform, an example, and a tester that runs it on a sample message. */
    private javafx.scene.control.ScrollPane scriptPane(DestinationConfig d) {
        script.setId("destScriptArea");
        script.setText(d.script());
        script.setPromptText(Messages.get("script.prompt"));
        script.setPrefRowCount(7);
        Styles.mono(script);
        scriptInput.setId("destScriptInput");
        scriptInput.setText(io.hl7sender.core.samples.SampleMessages.all().get(0).text().replace("\r", "\n"));
        scriptInput.setPrefRowCount(4);
        Styles.mono(scriptInput);
        scriptOutput.setId("destScriptOutput");
        scriptOutput.setEditable(false);
        scriptOutput.setPrefRowCount(4);
        Styles.mono(scriptOutput);
        Button example = new Button(Messages.get("script.example"));
        example.setId("destScriptExample");
        example.setOnAction(e -> script.setText(EXAMPLE_SCRIPT));
        Button test = new Button(Messages.get("script.test"));
        test.setId("destScriptTest");
        test.setOnAction(e -> scriptOutput.setText(testScript(script.getText(), scriptInput.getText())));
        Label help = new Label(Messages.get("script.help"));
        help.setWrapText(true);
        help.getStyleClass().add("field-label");
        help.setPrefWidth(660);
        help.setMinHeight(Region.USE_PREF_SIZE);
        scriptInput.setPrefColumnCount(30);
        scriptOutput.setPrefColumnCount(30);
        HBox buttons = new HBox(8, example, test);
        VBox input = new VBox(4, Fields.label(Messages.get("script.input")), scriptInput);
        VBox output = new VBox(4, Fields.label(Messages.get("script.output")), scriptOutput);
        HBox tester = new HBox(8, input, output);
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox.setHgrow(output, Priority.ALWAYS);
        VBox box = new VBox(6, help, script, buttons, tester);
        box.setPadding(new Insets(8));
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(360);
        scroll.setPrefViewportWidth(680);
        return scroll;
    }

    static final String EXAMPLE_SCRIPT = """
            // Runs on every message queued to this destination.
            msg.set('MSH-6', 'TESTFAC');            // receiving facility
            msg.remove('NK1');                       // no next-of-kin details
            if (msg.type() == 'ADT^A08' && msg.get('PV1-2') == 'O') {
                filter('outpatient updates are not needed');
            }
            log('MRN ' + msg.get('PID-3.1'));
            """;

    /** Runs {@code source} on {@code message} and describes the result, for the tester. */
    static String testScript(String source, String message) {
        if (source == null || source.isBlank()) {
            return Messages.get("script.none");
        }
        try {
            io.hl7sender.core.script.MessageScript.Result r =
                    io.hl7sender.core.script.MessageScript.compile(source).apply(message);
            StringBuilder sb = new StringBuilder();
            if (r.filtered()) {
                sb.append(Messages.get("script.filtered", r.filterReason())).append('\n');
            } else {
                sb.append(io.hl7sender.core.hl7.Hl7Text.toDisplay(r.message())).append('\n');
            }
            r.log().forEach(l -> sb.append("log: ").append(l).append('\n'));
            return sb.toString();
        } catch (io.hl7sender.core.script.MessageScript.ScriptFailure e) {
            return e.getMessage();
        }
    }

    /** MLLP/TCP, or a transport such as HTTP, file or a plugin. */
    record TransportChoice(String id, String label, io.hl7sender.core.transport.TransportFactory factory) {
        @Override
        public String toString() {
            return label;
        }
    }

    private boolean mllp() {
        return transportBox.getValue() == null || transportBox.getValue().factory() == null;
    }

    /** The Transport tab: how messages are sent, and the chosen transport's settings. */
    private VBox transportPane(DestinationConfig d) {
        transportBox.setId("destTransportBox");
        transportBox.getItems().add(new TransportChoice(DestinationConfig.MLLP, Messages.get("transport.mllp"),
                null));
        for (io.hl7sender.core.transport.TransportFactory f : engine.transports().all()) {
            transportBox.getItems().add(new TransportChoice(f.id(), f.displayName(), f));
        }
        TransportChoice current = transportBox.getItems().stream().filter(c -> c.id().equals(d.transport()))
                .findFirst().orElse(null);
        if (current == null) {
            // A plugin that is no longer installed: keep the destination's settings as they are.
            current = new TransportChoice(d.transport(), d.transport() + " " + Messages.get("transport.missing"),
                    null);
            transportBox.getItems().add(current);
        }
        transportValues = d.transportOptions();
        transportBox.setValue(current);
        transportGrid.setHgap(8);
        transportGrid.setVgap(6);
        transportBox.valueProperty().addListener((o, a, b) -> {
            transportValues = transportOptions();
            showTransportOptions();
        });
        showTransportOptions();
        Label help = new Label(Messages.get("transport.help", engine.transports().all().isEmpty() ? ""
                : String.join(", ", engine.transports().all().stream().map(f -> f.displayName()).toList())));
        help.setWrapText(true);
        help.setPrefWidth(640);
        help.setMinHeight(Region.USE_PREF_SIZE);
        help.getStyleClass().add("field-label");
        VBox box = new VBox(10, new HBox(8, Fields.label(Messages.get("transport.sendBy")), transportBox), help,
                transportGrid);
        box.setPadding(new Insets(8));
        return box;
    }

    private void showTransportOptions() {
        transportGrid.getChildren().clear();
        transportFields.clear();
        boolean isMllp = mllp();
        host.setDisable(!isMllp);
        port.setDisable(!isMllp);
        connectionMode.setDisable(!isMllp);
        io.hl7sender.core.transport.TransportFactory f = transportBox.getValue().factory();
        if (f == null) {
            return;
        }
        int row = 0;
        for (io.hl7sender.core.transport.TransportOption o : f.options()) {
            javafx.scene.control.TextInputControl field = o.multiline() ? new TextArea() : new TextField();
            if (field instanceof TextArea a) {
                a.setPrefRowCount(3);
                a.setPrefColumnCount(40);
            } else {
                ((TextField) field).setPrefColumnCount(40);
            }
            field.setId("destTransport_" + o.key());
            field.setText(transportValues.getOrDefault(o.key(), o.defaultValue()));
            field.setPromptText(o.help());
            transportFields.put(o.key(), field);
            transportGrid.addRow(row++, Fields.label(o.label() + (o.required() ? " *" : "")), field);
        }
    }

    private java.util.Map<String, String> transportOptions() {
        if (transportFields.isEmpty()) {
            return mllp() ? java.util.Map.of() : transportValues;
        }
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        transportFields.forEach((k, f) -> {
            String v = f.getText() == null ? "" : f.getText().strip();
            if (!v.isEmpty()) {
                out.put(k, v);
            }
        });
        return out;
    }
}
