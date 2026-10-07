package io.hl7sender.app;

import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.QueueFollowUps;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.ResponseRule;
import io.hl7sender.core.listener.ResponseRules;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.mllp.Mllp;
import io.hl7sender.core.tls.TlsContexts;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javax.net.ssl.SSLContext;

/**
 * The built-in mock MLLP receiver. Use it to test the sender, or another system's sender, against
 * any ACK behaviour: accept, error, reject, wrong control ID, malformed, silence, or a dropped
 * connection, with an optional delay. Response settings apply immediately, even while running.
 */
final class ListenerPane extends BorderPane {

    /** Keep the table bounded during long runs. */
    private static final int MAX_ROWS = 5_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final AppContext context;
    private final Consumer<String> status;
    private TestListener listener;

    private final TextField bindField = new TextField();
    private final TextField portField;
    private final ComboBox<ResponseMode> modeBox = new ComboBox<>(FXCollections.observableArrayList(
            java.util.Arrays.stream(ResponseMode.values()).filter(m -> m != ResponseMode.CUSTOM).toList()));
    private final TextField delayField;
    private final CheckBox commitCodesBox = new CheckBox("Enhanced mode (CA/CE/CR)");
    private final TextField responseTextField = new TextField();
    /** Enhanced mode: send an application ACK to this port of the sender after the CA; empty or 0 = off. */
    private final TextField appAckPortField = Fields.integer("listenerAppAckPortField", 0, 5, 5);
    private final ComboBox<io.hl7sender.core.ack.AckCode> appAckCodeBox = new ComboBox<>(
            FXCollections.observableArrayList(io.hl7sender.core.ack.AckCode.AA, io.hl7sender.core.ack.AckCode.AE,
                    io.hl7sender.core.ack.AckCode.AR));
    private final CheckBox tlsBox = new CheckBox("Use TLS");
    private final TextField keyStoreField = new TextField();
    private final PasswordField keyPasswordField = new PasswordField();
    private final CheckBox requireClientCertBox = new CheckBox("Require a client certificate");
    private final TextField clientTrustField = new TextField();
    private final PasswordField clientTrustPasswordField = new PasswordField();
    private final Button startButton = new Button("Start");
    private final Label stateBadge = new Label("Stopped");
    private final ListView<ResponseRule> rulesList = new ListView<>();
    private final TextField saveFolderField = new TextField();
    private final TableView<ReceivedMessage> table = new TableView<>();
    private final TextArea receivedText = new TextArea();
    private final TextArea responseText = new TextArea();

    ListenerPane(AppContext context, Consumer<String> status) {
        this.context = context;
        this.status = status;
        AppSettings.Listener s = context.settings().listener();
        this.portField = Fields.integer("listenerPortField", s.port(), 5, 5);
        this.delayField = Fields.integer("listenerDelayField", s.delayMs(), 7, 6);
        bindField.setText(s.bindAddress());
        modeBox.setValue(s.mode());
        commitCodesBox.setSelected(s.commitCodes());
        rulesList.getItems().setAll(s.rules());
        saveFolderField.setText(s.saveFolder());

        setTop(buildForm());
        setCenter(buildMessages());
        Styles.badge(stateBadge, null);

        modeBox.valueProperty().addListener((o, a, b) -> applyLiveSettings());
        commitCodesBox.selectedProperty().addListener((o, a, b) -> applyLiveSettings());
        delayField.textProperty().addListener((o, a, b) -> applyLiveSettings());
        responseTextField.textProperty().addListener((o, a, b) -> applyLiveSettings());
        appAckPortField.textProperty().addListener((o, a, b) -> applyLiveSettings());
        appAckCodeBox.valueProperty().addListener((o, a, b) -> applyLiveSettings());
        saveFolderField.textProperty().addListener((o, a, b) -> applyLiveSettings());
    }

    boolean isRunning() {
        return listener != null && listener.isRunning();
    }

    /**
     * Starts the listener on this computer only (plain TCP, accepting every message) unless it is already
     * running, and returns its port, or -1 if it could not start. Used by the first-run wizard.
     */
    int startLocal(int port) {
        if (!isRunning()) {
            int p = port;
            if (p == 0) {
                try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
                    p = s.getLocalPort();
                } catch (IOException e) {
                    return -1;
                }
            }
            bindField.setText("127.0.0.1");
            portField.setText(String.valueOf(p));
            tlsBox.setSelected(false);
            modeBox.setValue(io.hl7sender.core.listener.ResponseMode.ACCEPT);
            toggle();
        }
        return isRunning() ? listener.port() : -1;
    }

    void shutdown() {
        if (listener != null) {
            listener.close();
            listener = null;
        }
    }

    private Region buildForm() {
        bindField.setId("listenerBindField");
        bindField.setPrefColumnCount(10);
        bindField.setTooltip(new Tooltip("0.0.0.0 = all interfaces, 127.0.0.1 = this computer only"));
        modeBox.setId("listenerModeBox");
        commitCodesBox.setId("listenerCommitCodesBox");
        responseTextField.setId("listenerResponseTextField");
        responseTextField.setPromptText("optional MSA-3 text");
        responseTextField.setPrefColumnCount(16);
        appAckPortField.setText("");
        appAckPortField.setPromptText("off");
        appAckPortField.setTooltip(new Tooltip("After a CA, send an application ACK one second later to this port on "
                + "the sender (its destination's Application ACK port), as MSH-16 asks: AL always, ER only AE/AR, "
                + "SU only AA"));
        appAckCodeBox.setId("listenerAppAckCodeBox");
        appAckCodeBox.setValue(io.hl7sender.core.ack.AckCode.AA);
        appAckPortField.disableProperty().bind(commitCodesBox.selectedProperty().not());
        appAckCodeBox.disableProperty().bind(commitCodesBox.selectedProperty().not());
        startButton.setId("listenerStartButton");
        startButton.setPrefWidth(90);
        startButton.setOnAction(e -> toggle());
        stateBadge.setId("listenerStateBadge");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-pane");
        grid.addRow(0, Fields.label("Bind address"), bindField, Fields.label("Port"), portField,
                Fields.label("Response"), modeBox, Fields.label("Delay (ms)"), delayField);
        Button clear = new Button("Clear");
        clear.setId("listenerClearButton");
        clear.setOnAction(e -> {
            table.getItems().clear();
            receivedText.clear();
            responseText.clear();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(12, commitCodesBox, Fields.label("App ACK to port"), appAckPortField, appAckCodeBox,
                Fields.label("MSA-3 text"), responseTextField, spacer, clear, stateBadge, startButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(0, 10, 10, 10));
        return new VBox(grid, actions, buildTlsForm(), buildRulesForm());
    }

    private Region buildRulesForm() {
        rulesList.setId("listenerRulesList");
        rulesList.setPrefHeight(110);
        rulesList.setMaxHeight(140);
        rulesList.setPlaceholder(new Label(Messages.get("rules.empty")));
        rulesList.setCellFactory(v -> new ListCell<>() {
            @Override
            protected void updateItem(ResponseRule r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : r.name() + "  -  " + r.describe());
            }
        });
        rulesList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editRule();
            }
        });
        Button add = ruleButton("listenerRuleAdd", "rules.add", this::addRule);
        Button edit = ruleButton("listenerRuleEdit", "rules.edit", this::editRule);
        Button remove = ruleButton("listenerRuleRemove", "rules.remove", () -> {
            int i = rulesList.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                rulesList.getItems().remove(i);
                rulesChanged();
            }
        });
        Button up = ruleButton("listenerRuleUp", "rules.up", () -> moveRule(-1));
        Button down = ruleButton("listenerRuleDown", "rules.down", () -> moveRule(1));
        Button importRules = ruleButton("listenerRuleImport", "rules.import", this::importRules);
        Button exportRules = ruleButton("listenerRuleExport", "rules.export", this::exportRules);
        edit.disableProperty().bind(rulesList.getSelectionModel().selectedItemProperty().isNull());
        remove.disableProperty().bind(rulesList.getSelectionModel().selectedItemProperty().isNull());
        VBox buttons = new VBox(4, add, edit, remove, up, down, importRules, exportRules);
        buttons.getChildren().forEach(b -> ((Button) b).setMaxWidth(Double.MAX_VALUE));
        HBox rules = new HBox(8, rulesList, buttons);
        HBox.setHgrow(rulesList, Priority.ALWAYS);

        saveFolderField.setId("listenerSaveFolderField");
        saveFolderField.setPromptText(Messages.get("rules.saveFolder.prompt"));
        Button browse = new Button(Messages.get("rules.saveFolder.browse"));
        browse.setId("listenerSaveFolderBrowse");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(Messages.get("rules.saveFolder"));
            java.io.File f = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
            if (f != null) {
                saveFolderField.setText(f.getAbsolutePath());
            }
        });
        HBox save = new HBox(8, Fields.label(Messages.get("rules.saveFolder")), saveFolderField, browse);
        save.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(saveFolderField, Priority.ALWAYS);

        Label intro = new Label(Messages.get("rules.intro"));
        intro.setWrapText(true);
        intro.getStyleClass().add("field-label");
        TitledPane pane = new TitledPane(Messages.get("rules.title"), new VBox(8, intro, rules, save));
        pane.setId("listenerRulesPane");
        pane.setExpanded(!rulesList.getItems().isEmpty());
        pane.setAnimated(false);
        VBox box = new VBox(pane);
        box.setPadding(new Insets(0, 10, 10, 10));
        return box;
    }

    private static Button ruleButton(String id, String key, Runnable action) {
        Button b = new Button(Messages.get(key));
        b.setId(id);
        b.setOnAction(e -> action.run());
        return b;
    }

    private List<String> destinationNames() {
        return context.engine().map(DeliveryEngine::destinations).orElse(List.of()).stream()
                .map(DestinationConfig::name).toList();
    }

    private void addRule() {
        new ResponseRuleDialog(getScene() == null ? null : getScene().getWindow(), null, destinationNames())
                .showAndWait().ifPresent(r -> {
                    rulesList.getItems().add(r);
                    rulesList.getSelectionModel().select(r);
                    rulesChanged();
                });
    }

    private void editRule() {
        int i = rulesList.getSelectionModel().getSelectedIndex();
        if (i < 0) {
            return;
        }
        new ResponseRuleDialog(getScene() == null ? null : getScene().getWindow(), rulesList.getItems().get(i),
                destinationNames()).showAndWait().ifPresent(r -> {
                    rulesList.getItems().set(i, r);
                    rulesList.getSelectionModel().select(i);
                    rulesChanged();
                });
    }

    private void moveRule(int delta) {
        int i = rulesList.getSelectionModel().getSelectedIndex();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= rulesList.getItems().size()) {
            return;
        }
        ResponseRule r = rulesList.getItems().remove(i);
        rulesList.getItems().add(j, r);
        rulesList.getSelectionModel().select(j);
        rulesChanged();
    }

    private void importRules() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("rules.import"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File f = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (f != null) {
            importRules(f.toPath());
        }
    }

    /** Replaces the rules with those in {@code file} (for the Import button and tests). */
    void importRules(Path file) {
        try {
            List<ResponseRule> rules = ResponseRules.read(file);
            rulesList.getItems().setAll(rules);
            rulesChanged();
            status.accept(Messages.get("rules.imported", rules.size(), file));
        } catch (IOException e) {
            status.accept(Messages.get("rules.importFailed", e.getMessage()));
        }
    }

    private void exportRules() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("rules.export"));
        chooser.setInitialFileName("responder-rules.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File f = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (f != null) {
            exportRules(f.toPath());
        }
    }

    /** Writes the rules to {@code file}, for {@code hl7send listen --rules}. */
    void exportRules(Path file) {
        try {
            ResponseRules.write(file, List.copyOf(rulesList.getItems()));
            status.accept(Messages.get("rules.exported", rulesList.getItems().size(), file));
        } catch (IOException e) {
            status.accept(Messages.get("rules.exportFailed", e.getMessage()));
        }
    }

    /** The current rules, in order. */
    List<ResponseRule> rules() {
        return List.copyOf(rulesList.getItems());
    }

    private void rulesChanged() {
        persist();
        applyLiveSettings();
        List<String> missing = QueueFollowUps.missingDestinations(rules(),
                context.engine().map(DeliveryEngine::destinations).orElse(List.of()));
        if (!missing.isEmpty()) {
            status.accept(Messages.get("rules.missingDestination", String.join(", ", missing)));
        }
    }

    /** Saves the listener settings, including rules and the save folder. */
    private void persist() {
        List<ResponseRule> rules = new ArrayList<>(rulesList.getItems());
        String folder = saveFolderField.getText() == null ? "" : saveFolderField.getText().trim();
        context.updateSettings(a -> a.withListener(a.listener().withRules(rules, folder)));
    }

    private Region buildTlsForm() {
        tlsBox.setId("listenerTlsBox");
        tlsBox.setTooltip(new Tooltip("Accept MLLP over TLS, presenting the server certificate in the key store"));
        keyStoreField.setId("listenerKeyStoreField");
        keyStoreField.setPromptText("server certificate and key (.p12 / .jks)");
        keyStoreField.setPrefColumnCount(22);
        keyPasswordField.setId("listenerKeyPasswordField");
        keyPasswordField.setPromptText("key store password");
        requireClientCertBox.setId("listenerRequireClientCertBox");
        clientTrustField.setId("listenerClientTrustField");
        clientTrustField.setPromptText("client certificates / CAs to accept (.p12 / .jks / .pem)");
        clientTrustField.setPrefColumnCount(22);
        clientTrustPasswordField.setId("listenerClientTrustPasswordField");
        clientTrustPasswordField.setPromptText("password, if any");

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-pane");
        grid.addRow(0, tlsBox, Fields.label("Key store"), browse(keyStoreField, "Choose server key store"),
                Fields.label("Password"), keyPasswordField);
        grid.addRow(1, requireClientCertBox, Fields.label("Trusted clients"),
                browse(clientTrustField, "Choose trusted client certificates"), Fields.label("Password"),
                clientTrustPasswordField);
        for (javafx.scene.Node n : List.of(keyStoreField.getParent(), keyPasswordField, requireClientCertBox)) {
            n.disableProperty().bind(tlsBox.selectedProperty().not().or(startButton.textProperty().isEqualTo("Stop")));
        }
        for (javafx.scene.Node n : List.of(clientTrustField.getParent(), clientTrustPasswordField)) {
            n.disableProperty().bind(tlsBox.selectedProperty().and(requireClientCertBox.selectedProperty()).not()
                    .or(startButton.textProperty().isEqualTo("Stop")));
        }
        tlsBox.disableProperty().bind(startButton.textProperty().isEqualTo("Stop"));
        TitledPane pane = new TitledPane("TLS", grid);
        pane.setId("listenerTlsPane");
        pane.setExpanded(false);
        pane.setAnimated(false);
        VBox box = new VBox(pane);
        box.setPadding(new Insets(0, 10, 10, 10));
        return box;
    }

    private HBox browse(TextField field, String title) {
        Button b = new Button("Browse...");
        b.setId(field.getId() + "Browse");
        b.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(title);
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("Key stores and certificates", "*.p12", "*.pfx", "*.jks",
                            "*.pem", "*.crt", "*.cer"),
                    new FileChooser.ExtensionFilter("All files", "*.*"));
            java.io.File f = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
            if (f != null) {
                field.setText(f.getAbsolutePath());
            }
        });
        HBox box = new HBox(6, field, b);
        HBox.setHgrow(field, Priority.ALWAYS);
        return box;
    }

    /** The TLS context for the listener, or null for plain TCP. */
    private SSLContext tlsContext() throws IOException {
        if (!tlsBox.isSelected()) {
            return null;
        }
        String keyStore = keyStoreField.getText() == null ? "" : keyStoreField.getText().trim();
        if (keyStore.isEmpty()) {
            throw new IllegalArgumentException("TLS needs a key store with the listener's certificate");
        }
        String trust = requireClientCertBox.isSelected() && clientTrustField.getText() != null
                ? clientTrustField.getText().trim() : "";
        return TlsContexts.server(Path.of(keyStore), keyPasswordField.getText().toCharArray(), trust,
                clientTrustPasswordField.getText().isEmpty() ? null : clientTrustPasswordField.getText().toCharArray());
    }

    private Region buildMessages() {
        table.setId("listenerTable");
        table.setPlaceholder(new Label("No messages received. Start the listener and send to its port."));
        table.getColumns().add(column("Time", 110,
                m -> TIME.format(LocalTime.ofInstant(m.receivedAt(), ZoneId.systemDefault()))));
        table.getColumns().add(column("From", 160, ReceivedMessage::remote));
        table.getColumns().add(column("Type", 110, m -> m.messageType().isEmpty() ? "?" : m.messageType()));
        table.getColumns().add(column("Control ID", 200, ReceivedMessage::controlId));
        table.getColumns().add(column("Response", 110, ReceivedMessage::responseCode));
        table.getColumns().add(column(Messages.get("rules.column"), 180, ReceivedMessage::rule));
        table.getSelectionModel().selectedItemProperty().addListener((o, old, m) -> {
            receivedText.setText(m == null ? "" : Hl7Text.toDisplay(m.payload()));
            responseText.setText(m == null ? "" : m.response().map(Hl7Text::toDisplay).orElse("(no response sent)"));
        });

        receivedText.setId("listenerReceivedText");
        responseText.setId("listenerResponseText");
        for (TextArea area : new TextArea[] {receivedText, responseText}) {
            area.setEditable(false);
            Styles.mono(area);
        }
        VBox received = titled("Received message", receivedText);
        VBox response = titled("Response sent", responseText);
        SplitPane detail = new SplitPane(received, response);
        detail.setDividerPositions(0.6);

        SplitPane split = new SplitPane(table, detail);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.5);
        return split;
    }

    private static VBox titled(String title, TextArea area) {
        Label label = new Label(title);
        label.getStyleClass().add("section-title");
        VBox box = new VBox(label, area);
        VBox.setVgrow(area, Priority.ALWAYS);
        box.setPadding(new Insets(6, 10, 10, 10));
        return box;
    }

    private static TableColumn<ReceivedMessage, String> column(String title, double width,
                                                               Function<ReceivedMessage, String> value) {
        TableColumn<ReceivedMessage, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return col;
    }

    private void toggle() {
        if (isRunning()) {
            shutdown();
            setRunningState(false, "Stopped");
            status.accept("Test listener stopped");
            return;
        }
        String bind;
        int port;
        ListenerSettings settings;
        try {
            bind = bindField.getText().trim();
            if (bind.isEmpty()) {
                throw new IllegalArgumentException("Bind address is required");
            }
            port = Fields.parse(portField, "Port", 1, 65_535);
            settings = readSettings();
        } catch (IllegalArgumentException e) {
            status.accept(e.getMessage());
            return;
        }
        TestListener l = new TestListener(bind, port, settings, m -> Platform.runLater(() -> add(m)));
        l.setFollowUpHandler(followUpHandler());
        try {
            SSLContext tls = tlsContext();
            if (tls != null) {
                l.useTls(tls, requireClientCertBox.isSelected());
            }
            l.start();
        } catch (IOException | RuntimeException e) {
            status.accept("Cannot listen on " + bind + ":" + port + ": " + e.getMessage());
            Styles.badge(stateBadge, io.hl7sender.core.send.SendOutcome.Severity.FAILURE);
            stateBadge.setText("Failed");
            return;
        }
        listener = l;
        context.updateSettings(s -> s.withListener(new AppSettings.Listener(bind, port, settings.mode(),
                settings.delayMs(), settings.commitCodes(), settings.rules(), saveFolderField.getText().trim())));
        String how = l.isTls() ? (requireClientCertBox.isSelected() ? " (mutual TLS)" : " (TLS)") : "";
        setRunningState(true, "Listening on " + l.port() + how);
        status.accept("Test listener started on " + bind + ":" + l.port() + how);
    }

    private void setRunningState(boolean running, String text) {
        startButton.setText(running ? "Stop" : "Start");
        bindField.setDisable(running);
        portField.setDisable(running);
        Styles.badge(stateBadge, running ? io.hl7sender.core.send.SendOutcome.Severity.SUCCESS : null);
        stateBadge.setText(text);
    }

    private ListenerSettings readSettings() {
        int delay = delayField.getText().isBlank() ? 0 : Fields.parse(delayField, "Delay", 0, 3_600_000);
        ResponseMode mode = modeBox.getValue() == null ? ResponseMode.ACCEPT : modeBox.getValue();
        String folder = saveFolderField.getText() == null ? "" : saveFolderField.getText().trim();
        int appAckPort = appAckPortField.getText().isBlank() ? 0
                : Fields.parse(appAckPortField, "Application ACK port", 0, 65_535);
        ListenerSettings.AppAck appAck = !commitCodesBox.isSelected() || appAckPort == 0 ? null
                : new ListenerSettings.AppAck(appAckPort, appAckCodeBox.getValue(), 1_000);
        return new ListenerSettings(mode, delay, commitCodesBox.isSelected(), responseTextField.getText(),
                StandardCharsets.UTF_8, Mllp.DEFAULT_MAX_FRAME_BYTES, rules(),
                folder.isEmpty() ? null : Path.of(folder), appAck);
    }

    /** Queues follow-up messages when this window delivers from the queue; otherwise explains why not. */
    private TestListener.FollowUpHandler followUpHandler() {
        return context.engine()
                .<TestListener.FollowUpHandler>map(engine -> new QueueFollowUps(engine, m -> Platform.runLater(() ->
                        status.accept(Messages.get("rules.followUpQueued", m.messageType(), m.controlId())))))
                .orElse((rule, message) -> Platform.runLater(() -> status.accept(Messages.get(
                        "rules.followUpNoQueue", rule.name()))));
    }

    private void applyLiveSettings() {
        if (!isRunning()) {
            persist();
            return;
        }
        try {
            ListenerSettings s = readSettings();
            listener.updateSettings(s);
            context.updateSettings(a -> a.withListener(new AppSettings.Listener(a.listener().bindAddress(),
                    a.listener().port(), s.mode(), s.delayMs(), s.commitCodes(), s.rules(),
                    s.saveFolder() == null ? "" : s.saveFolder().toString())));
        } catch (IllegalArgumentException e) {
            status.accept(e.getMessage());
        }
    }

    private void add(ReceivedMessage m) {
        table.getItems().add(0, m);
        if (table.getItems().size() > MAX_ROWS) {
            table.getItems().remove(MAX_ROWS, table.getItems().size());
        }
        status.accept("Listener received " + (m.messageType().isEmpty() ? "a message" : m.messageType())
                + " from " + m.remote() + " -> " + m.responseCode()
                + (m.rule().isEmpty() ? "" : " (rule: " + m.rule() + ")"));
    }
}
