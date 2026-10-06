package io.hl7sender.app;

import io.hl7sender.core.ack.AckError;
import io.hl7sender.core.ack.ParsedAck;
import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.batch.SplitResult;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.hl7.Hl7FormatException;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.hl7.ParsedMessage;
import io.hl7sender.core.hl7.validation.ValidationIssue;
import io.hl7sender.core.hl7.validation.ValidationReport;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.EnqueueResult;
import io.hl7sender.core.queue.FanOutResult;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueListener;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.samples.SampleMessages;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.template.TemplateEngine;
import io.hl7sender.core.template.TemplateStore;
import io.hl7sender.core.tls.TlsOptions;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;

/**
 * The sender view. It has a destination form and a message editor with a live structure tree and
 * validation. It sends in the background and shows the acknowledgment as a colour-coded result.
 */
final class SenderPane extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AppContext context;
    private final Consumer<String> status;

    private final TextField hostField = new TextField();
    private final TextField portField;
    private final TextField connectTimeoutField;
    private final TextField ackTimeoutField;
    private final ComboBox<String> charsetBox = new ComboBox<>(FXCollections.observableArrayList(
            "UTF-8", "ISO-8859-1", "windows-1252", "US-ASCII", "UTF-16"));
    private final CheckBox waitForAckBox = new CheckBox("Wait for ACK");
    private final CheckBox generateTimestampBox = new CheckBox("Generate MSH-7 timestamp");
    private final CheckBox generateControlIdBox = new CheckBox("Generate MSH-10 control ID");
    private final ComboBox<TlsChoice> tlsBox = new ComboBox<>();
    private final Button sendButton = new Button("Send");

    private final Hl7Editor editor = new Hl7Editor("editor");
    private final CheckBox expandVariablesBox = new CheckBox("Expand ${variables}");
    private final TreeView<String> structureTree = new TreeView<>();
    private final ListView<ValidationIssue> issuesList = new ListView<>();
    private final Label validationSummary = new Label();
    private final Tab validationTab = new Tab("Validation");
    private final Tab fhirTab = new Tab(Messages.get("fhir.tab"));
    private final TextArea fhirArea = new TextArea();
    private final Label fhirSummary = new Label();
    private final PauseTransition validationDelay = new PauseTransition(Duration.millis(350));

    private final Label outcomeBadge = new Label("Not sent");
    private final Label outcomeDetail = new Label();
    private final Label ackCodeValue = new Label("-");
    private final Label ackControlIdValue = new Label("-");
    private final Label sentControlIdValue = new Label("-");
    private final Label ackTextValue = new Label("-");
    private final Label timingValue = new Label("-");
    private final ListView<String> errList = new ListView<>();
    private final TextArea rawAck = new TextArea();
    private final TextArea sentMessage = new TextArea();
    private final ListView<String> history = new ListView<>();
    private QueueListener queueListener;
    private volatile boolean closed;

    /** "Off", or the TLS settings and passwords of a saved destination, used for direct sends. */
    record TlsChoice(String label, DestinationConfig destination) {
        static final TlsChoice OFF = new TlsChoice("Off (plain TCP)", null);

        @Override
        public String toString() {
            return label;
        }
    }

    SenderPane(AppContext context, Consumer<String> status) {
        this.context = context;
        this.status = status;
        AppSettings.Destination d = context.settings().destination();
        this.portField = Fields.integer("portField", d.port(), 5, 5);
        this.connectTimeoutField = Fields.integer("connectTimeoutField", d.connectTimeoutMs(), 7, 6);
        this.ackTimeoutField = Fields.integer("ackTimeoutField", d.ackTimeoutMs(), 7, 6);

        setTop(buildDestinationForm());
        SplitPane vertical = new SplitPane(buildEditorArea(), buildResponseArea());
        vertical.setOrientation(Orientation.VERTICAL);
        vertical.setDividerPositions(0.58);
        setCenter(vertical);

        loadSettings();
        editor.textProperty().addListener((obs, old, text) -> validationDelay.playFromStart());
        validationDelay.setOnFinished(e -> refreshValidation());
        editor.setText(SampleMessages.all().get(0).text());
        refreshValidation();
        Styles.badge(outcomeBadge, null);
    }

    /** Opens a file chooser and loads the selected message into the editor. */
    void openFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open HL7 message");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("HL7 messages", "*.hl7", "*.txt", "*.msg"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            SplitResult split = MessageSplitter.split(Files.readString(file.toPath(), selectedCharset()));
            if (split.messages().isEmpty()) {
                status.accept(file.getName() + " contains no HL7 messages");
                return;
            }
            editor.setText(Hl7Text.toDisplay(split.messages().get(0)));
            status.accept(split.messages().size() == 1 ? "Loaded " + file.getName()
                    : "Loaded the first of " + split.messages().size() + " messages in " + file.getName()
                    + "; use Import... to queue them all");
        } catch (IOException | RuntimeException e) {
            status.accept("Could not open " + file.getName() + ": " + e.getMessage());
        }
    }

    void shutdown() {
        closed = true;
        if (queueListener != null) {
            context.engine().ifPresent(engine -> engine.removeListener(queueListener));
        }
    }

    /** Saves the destination and sender options. */
    void saveSettings() {
        try {
            AppSettings.Destination destination = readDestination();
            context.updateSettings(s -> s.withDestination(destination).withSender(
                    new AppSettings.Sender(generateControlIdBox.isSelected(), generateTimestampBox.isSelected())));
        } catch (IllegalArgumentException e) {
            // Invalid form values are not saved; the previous settings remain.
        }
    }

    private Region buildDestinationForm() {
        hostField.setId("hostField");
        hostField.setPrefColumnCount(18);
        hostField.setPromptText("host name or IP");
        charsetBox.setId("charsetBox");
        charsetBox.setEditable(true);
        waitForAckBox.setId("waitForAckBox");
        waitForAckBox.setTooltip(new Tooltip(
                "Clear this only for receivers that never acknowledge (e.g. a plain NiFi ListenTCP flow)."));
        generateTimestampBox.setId("generateTimestampBox");
        generateControlIdBox.setId("generateControlIdBox");
        generateControlIdBox.setTooltip(new Tooltip(
                "A unique MSH-10 lets the ACK (MSA-2) be matched to this message."));

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-pane");
        grid.addRow(0, Fields.label("Host"), hostField, Fields.label("Port"), portField,
                Fields.label("Charset"), charsetBox, Fields.label("TLS"), tlsBox);
        grid.addRow(1, Fields.label("Connect timeout (ms)"), connectTimeoutField,
                Fields.label("ACK timeout (ms)"), ackTimeoutField);
        tlsBox.setId("tlsBox");
        tlsBox.setTooltip(new Tooltip("Send over TLS using the certificates and passwords of a saved destination "
                + "(set them up on the Queue tab)"));
        rebuildTlsChoices();

        MenuButton samples = new MenuButton("Samples & templates");
        samples.setId("samplesButton");
        samples.setOnShowing(e -> rebuildSamplesMenu(samples));
        rebuildSamplesMenu(samples);
        expandVariablesBox.setId("expandVariablesBox");
        expandVariablesBox.setSelected(true);
        expandVariablesBox.setTooltip(new Tooltip("Replace ${NOW}, ${SEQ}, ${RANDOM_MRN}, ... with fresh values "
                + "for every message sent or queued"));
        Button importButton = new Button("Import...");
        importButton.setId("importButton");
        importButton.setTooltip(new Tooltip("Queue many messages from files, a folder or a template"));
        importButton.setOnAction(e -> openImport());
        Button open = new Button("Open...");
        open.setId("openButton");
        open.setOnAction(e -> openFile());
        Button validate = new Button("Validate");
        validate.setId("validateButton");
        validate.setOnAction(e -> {
            refreshValidation();
            validationTab.getTabPane().getSelectionModel().select(validationTab);
        });
        MenuButton enqueue = new MenuButton("Add to queue");
        enqueue.setId("enqueueButton");
        enqueue.setTooltip(new Tooltip("Store the message in a destination's durable queue; it is delivered in "
                + "order and retried until acknowledged"));
        rebuildEnqueueMenu(enqueue);
        // Rebuild when destinations change (not while the popup opens, which would swap items under the cursor).
        queueListener = new QueueListener() {
            @Override
            public void onDestinationsChanged() {
                Platform.runLater(() -> {
                    if (closed) {
                        return;
                    }
                    rebuildEnqueueMenu(enqueue);
                    rebuildTlsChoices();
                });
            }
        };
        context.engine().ifPresent(engine -> engine.addListener(queueListener));
        sendButton.setId("sendButton");
        sendButton.setDefaultButton(false);
        sendButton.setTooltip(new Tooltip("Send (Ctrl+Enter)"));
        sendButton.setOnAction(e -> send());
        sendButton.setPrefWidth(110);

        HBox options = new HBox(16, waitForAckBox, generateTimestampBox, generateControlIdBox, expandVariablesBox);
        options.setAlignment(Pos.CENTER_LEFT);
        options.setPadding(new Insets(0, 10, 8, 10));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, open, samples, importButton, validate, spacer, enqueue, sendButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(0, 10, 10, 10));
        for (javafx.scene.Node n : actions.getChildren()) {
            if (n instanceof javafx.scene.control.Control c) {
                c.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        for (javafx.scene.Node n : options.getChildren()) {
            ((javafx.scene.control.Control) n).setMinWidth(Region.USE_PREF_SIZE);
        }
        return new VBox(grid, options, actions);
    }

    private Region buildEditorArea() {
        editor.area().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN).match(e)) {
                send();
                e.consume();
            }
        });
        Label title = new Label("Message");
        title.getStyleClass().add("section-title");
        VBox left = new VBox(title, editor);
        VBox.setVgrow(editor, Priority.ALWAYS);
        left.setPadding(new Insets(0, 0, 0, 10));

        structureTree.setId("structureTree");
        structureTree.setShowRoot(false);
        Styles.mono(structureTree);
        issuesList.setId("issuesList");
        issuesList.setCellFactory(list -> new IssueCell());
        validationSummary.setId("validationSummary");
        validationSummary.setPadding(new Insets(6));
        validationSummary.setWrapText(true);
        VBox validationBox = new VBox(validationSummary, issuesList);
        VBox.setVgrow(issuesList, Priority.ALWAYS);
        validationTab.setContent(validationBox);
        Tab structureTab = new Tab("Structure", structureTree);
        fhirArea.setId("fhirArea");
        fhirArea.setEditable(false);
        Styles.mono(fhirArea);
        fhirSummary.setId("fhirSummary");
        fhirSummary.setWrapText(true);
        fhirSummary.setPadding(new Insets(6));
        VBox fhirBox = new VBox(fhirSummary, fhirArea);
        VBox.setVgrow(fhirArea, Priority.ALWAYS);
        fhirTab.setContent(fhirBox);
        fhirTab.setId("fhirTab");
        fhirTab.setOnSelectionChanged(e -> {
            if (fhirTab.isSelected()) {
                refreshFhir();
            }
        });
        TabPane right = new TabPane(structureTab, validationTab, fhirTab);
        right.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.62);
        return split;
    }

    private Region buildResponseArea() {
        outcomeBadge.setId("outcomeBadge");
        outcomeBadge.setAccessibleHelp("Outcome of the last send");
        outcomeDetail.setId("outcomeDetail");
        outcomeDetail.setWrapText(true);
        ackCodeValue.setId("ackCodeValue");
        ackControlIdValue.setId("ackControlIdValue");
        sentControlIdValue.setId("sentControlIdValue");
        ackTextValue.setId("ackTextValue");
        timingValue.setId("timingValue");
        HBox header = new HBox(12, outcomeBadge, outcomeDetail);
        header.setAlignment(Pos.CENTER_LEFT);

        GridPane details = new GridPane();
        details.getStyleClass().add("form-pane");
        details.setPadding(new Insets(8, 0, 8, 0));
        details.addRow(0, Fields.label("MSA-1 code"), ackCodeValue, Fields.label("MSA-2 control ID"),
                ackControlIdValue, Fields.label("Sent MSH-10"), sentControlIdValue);
        details.addRow(1, Fields.label("MSA-3 text"), ackTextValue, Fields.label("Timing"), timingValue);
        GridPane.setColumnSpan(timingValue, 3);

        errList.setId("errList");
        errList.setPrefHeight(84);
        errList.setPlaceholder(new Label("No ERR segments"));
        rawAck.setId("rawAck");
        rawAck.setEditable(false);
        Styles.mono(rawAck);
        VBox ackBox = new VBox(6, header, details, errList, rawAck);
        VBox.setVgrow(rawAck, Priority.ALWAYS);
        ackBox.setPadding(new Insets(10));

        sentMessage.setId("sentMessage");
        sentMessage.setEditable(false);
        Styles.mono(sentMessage);
        history.setId("history");
        history.setPlaceholder(new Label("Nothing sent yet in this session"));

        TabPane tabs = new TabPane(
                new Tab("Acknowledgment", ackBox),
                new Tab("Sent message", sentMessage),
                new Tab("Session history", history));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return tabs;
    }

    /** The editor text as typed, with any template variables unexpanded. */
    String editorText() {
        return editor.getText();
    }

    /**
     * The editor text with template variables expanded (if enabled). {@code advance} is false for
     * validation previews so ${SEQ} only counts real sends.
     */
    private String effectiveText(boolean advance) {
        String text = editor.getText();
        if (!expandVariablesBox.isSelected() || !TemplateEngine.hasVariables(text)) {
            return text;
        }
        TemplateEngine.Expansion e = advance ? context.templates().expand(text)
                : context.templates().preview(text, java.util.Map.of());
        if (advance && !e.unknown().isEmpty()) {
            status.accept("Unknown template variable(s) left unchanged: " + String.join(", ", e.unknown()));
        }
        return e.text();
    }

    private void rebuildSamplesMenu(MenuButton menu) {
        menu.getItems().clear();
        Menu samplesMenu = new Menu("Sample messages");
        for (SampleMessages.Sample sample : SampleMessages.all()) {
            samplesMenu.getItems().add(loadItem(sample.name(), sample.text()));
        }
        Menu templatesMenu = new Menu("Templates");
        for (SampleMessages.Sample t : SampleMessages.templates()) {
            templatesMenu.getItems().add(loadItem(t.name(), t.text()));
        }
        try {
            List<TemplateStore.Template> saved = context.templateStore().list();
            if (!saved.isEmpty()) {
                templatesMenu.getItems().add(new SeparatorMenuItem());
                for (TemplateStore.Template t : saved) {
                    templatesMenu.getItems().add(loadItem(t.name(), t.text()));
                }
            }
        } catch (IOException e) {
            status.accept("Could not read templates: " + e.getMessage());
        }
        MenuItem save = new MenuItem("Save editor as template...");
        save.setId("saveTemplateItem");
        save.setOnAction(e -> saveTemplate());
        MenuItem help = new MenuItem("Template variables...");
        help.setOnAction(e -> showVariablesHelp());
        menu.getItems().addAll(samplesMenu, templatesMenu, new SeparatorMenuItem(), save, help);
    }

    private MenuItem loadItem(String name, String text) {
        MenuItem item = new MenuItem(name);
        item.setOnAction(e -> {
            editor.setText(text);
            status.accept("Loaded " + name);
        });
        return item;
    }

    private void saveTemplate() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.setTitle("Save template");
        dialog.setHeaderText("Save the editor's message as a reusable template.\n"
                + "Use ${NOW}, ${SEQ}, ${RANDOM_MRN}, ... for values that change per message.");
        dialog.setContentText("Name:");
        dialog.showAndWait().ifPresent(name -> {
            try {
                TemplateStore.Template t = context.templateStore().save(name, editor.getText());
                status.accept("Saved template " + t.name() + " in " + context.templateStore().dir());
            } catch (IOException | IllegalArgumentException e) {
                status.accept("Could not save template: " + e.getMessage());
            }
        });
    }

    private void showVariablesHelp() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(getScene() == null ? null : getScene().getWindow());
        alert.setTitle("Template variables");
        alert.setHeaderText("Variables are replaced each time a message is sent or queued");
        alert.setContentText("${NOW}  ${NOW:yyyyMMdd}  ${TODAY}\n${SEQ}  ${SEQ:6}  (counts up per message)\n"
                + "${UUID}  ${CONTROL_ID}  ${RANDOM:6}\n${RANDOM_MRN}  ${RANDOM_FIRST_NAME}  ${RANDOM_LAST_NAME}\n"
                + "${RANDOM_DOB}  ${RANDOM_SEX}\n\nEach variable has one value per message, so the same MRN can "
                + "be used in several fields. Write $${ for a literal ${.");
        alert.showAndWait();
    }

    void openImportFromMenu() {
        openImport();
    }

    private void openImport() {
        if (!context.can(io.hl7sender.core.auth.Permission.SEND)) {
            showLocalError(Messages.get("access.denied"));
            return;
        }
        if (context.engine().isEmpty()) {
            showLocalError(context.queueUnavailableReason());
            return;
        }
        String editorText = editor.getText();
        new BulkImportDialog(getScene() == null ? null : getScene().getWindow(), context, status,
                editorText.isBlank() ? null : editorText).show();
    }

    private void rebuildEnqueueMenu(MenuButton menu) {
        menu.getItems().clear();
        if (context.engine().isEmpty()) {
            MenuItem unavailable = new MenuItem("Queue unavailable");
            unavailable.setDisable(true);
            menu.getItems().add(unavailable);
            return;
        }
        List<DestinationConfig> destinations = context.engine().get().destinations();
        if (destinations.isEmpty()) {
            MenuItem none = new MenuItem("No destinations - add one on the Queue tab");
            none.setDisable(true);
            menu.getItems().add(none);
            return;
        }
        for (DestinationConfig d : destinations) {
            MenuItem item = new MenuItem(d.name() + "  (" + d.address() + ")" + (d.paused() ? "  [paused]" : ""));
            item.setId("enqueue-" + d.id());
            item.setOnAction(e -> enqueue(d));
            menu.getItems().add(item);
        }
        if (destinations.size() > 1) {
            MenuItem several = new MenuItem("Several destinations...");
            several.setId("enqueueFanOutItem");
            several.setOnAction(e -> chooseFanOut(destinations));
            menu.getItems().addAll(new SeparatorMenuItem(), several);
        }
    }

    private void rebuildTlsChoices() {
        Long selected = Optional.ofNullable(tlsBox.getValue()).map(TlsChoice::destination)
                .map(DestinationConfig::id).orElse(null);
        List<TlsChoice> choices = new ArrayList<>();
        choices.add(TlsChoice.OFF);
        context.engine().ifPresent(engine -> engine.destinations().stream().filter(d -> d.isMllp() && d.tls().enabled())
                .forEach(d -> choices.add(new TlsChoice("Settings of '" + d.name() + "'", d))));
        tlsBox.getItems().setAll(choices);
        tlsBox.setValue(choices.stream().filter(c -> c.destination() != null
                && Long.valueOf(c.destination().id()).equals(selected))
                .findFirst().orElse(TlsChoice.OFF));
        tlsBox.setDisable(choices.size() == 1);
    }

    private void chooseFanOut(List<DestinationConfig> destinations) {
        Dialog<List<Long>> dialog = new Dialog<>();
        dialog.initOwner(getScene() == null ? null : getScene().getWindow());
        dialog.setTitle("Queue for several destinations");
        dialog.setHeaderText("The same message (same control ID) is queued for each destination you tick.\n"
                + "Each destination delivers and retries its copy independently.");
        VBox boxes = new VBox(6);
        List<CheckBox> checks = new ArrayList<>();
        for (DestinationConfig d : destinations) {
            CheckBox c = new CheckBox(d.name() + "  (" + d.displayAddress() + ")" + (d.paused() ? "  [paused]" : ""));
            c.setId("fanOut-" + d.id());
            c.setUserData(d.id());
            checks.add(c);
        }
        boxes.getChildren().addAll(checks);
        dialog.getDialogPane().setContent(boxes);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().getStylesheets().add(Styles.stylesheet());
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("fanOutOkButton");
        ok.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> checks.stream().noneMatch(CheckBox::isSelected),
                checks.stream().map(CheckBox::selectedProperty).toArray(javafx.beans.Observable[]::new)));
        dialog.setResultConverter(b -> b == ButtonType.OK ? checks.stream().filter(CheckBox::isSelected)
                .map(c -> (Long) c.getUserData()).toList() : null);
        dialog.showAndWait().filter(ids -> !ids.isEmpty()).ifPresent(this::enqueueFanOut);
    }

    /** Queues the editor's message, once stamped, for each of the given destinations. */
    void enqueueFanOut(List<Long> destinationIds) {
        DeliveryEngine engine = context.engine().orElse(null);
        if (engine == null) {
            showLocalError(context.queueUnavailableReason());
            return;
        }
        saveSettings();
        SendOptions options = new SendOptions(generateControlIdBox.isSelected(), generateTimestampBox.isSelected(),
                AckMode.EXPECT_ACK);
        try {
            FanOutResult result = engine.enqueueFanOut(destinationIds, effectiveText(true), options, "editor");
            List<String> rejected = new ArrayList<>();
            for (Map.Entry<Long, EnqueueResult> e : result.results().entrySet()) {
                if (!e.getValue().accepted()) {
                    String name = engine.destinations().stream().filter(d -> d.id() == e.getKey())
                            .map(DestinationConfig::name).findFirst().orElse("#" + e.getKey());
                    rejected.add(name + ": " + e.getValue().validation().errors().get(0).message());
                }
            }
            if (result.accepted() == 0) {
                showLocalError("Not queued. " + String.join("; ", rejected));
                return;
            }
            Styles.badge(outcomeBadge, rejected.isEmpty() ? io.hl7sender.core.send.SendOutcome.Severity.SUCCESS
                    : io.hl7sender.core.send.SendOutcome.Severity.WARNING);
            outcomeBadge.setText("QUEUED");
            outcomeDetail.setText("Queued for " + result.accepted() + " of " + destinationIds.size()
                    + " destination(s) as batch " + result.batchId()
                    + (rejected.isEmpty() ? "" : ". Rejected: " + String.join("; ", rejected)));
            status.accept("Queued for " + result.accepted() + " destination(s), batch " + result.batchId()
                    + "; see the Queue tab for delivery status");
        } catch (QueueException e) {
            showLocalError("Could not queue the message: " + e.getMessage());
        }
    }

    /** Adds the editor's message to a destination's durable queue. */
    void enqueue(DestinationConfig destination) {
        DeliveryEngine engine = context.engine().orElse(null);
        if (engine == null) {
            showLocalError(context.queueUnavailableReason());
            return;
        }
        saveSettings();
        SendOptions options = new SendOptions(generateControlIdBox.isSelected(), generateTimestampBox.isSelected(),
                destination.ackMode());
        try {
            EnqueueResult result = engine.enqueue(destination.id(), effectiveText(true), options, "editor");
            if (!result.accepted()) {
                refreshValidation();
                validationTab.getTabPane().getSelectionModel().select(validationTab);
                showLocalError("Not queued: " + result.validation().errors().get(0).message());
                return;
            }
            QueuedMessage m = result.message().orElseThrow();
            Styles.badge(outcomeBadge, io.hl7sender.core.send.SendOutcome.Severity.SUCCESS);
            outcomeBadge.setText("QUEUED");
            outcomeDetail.setText("Message " + m.id() + " [" + m.controlId() + "] queued for " + destination.name()
                    + (result.warnings().isEmpty() ? "" : ". Warning: " + String.join("; ", result.warnings())));
            status.accept("Queued " + m.messageType() + " [" + m.controlId() + "] for " + destination.name()
                    + "; see the Queue tab for delivery status");
        } catch (QueueException e) {
            showLocalError("Could not queue the message: " + e.getMessage());
        }
    }

    private void loadSettings() {
        AppSettings s = context.settings();
        hostField.setText(s.destination().host());
        charsetBox.setValue(s.destination().charset());
        waitForAckBox.setSelected(s.destination().ackMode() == AckMode.EXPECT_ACK);
        generateTimestampBox.setSelected(s.sender().generateTimestamp());
        generateControlIdBox.setSelected(s.sender().generateControlId());
    }

    private AppSettings.Destination readDestination() {
        String host = hostField.getText() == null ? "" : hostField.getText().trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Host is required");
        }
        return new AppSettings.Destination(
                host,
                Fields.parse(portField, "Port", 1, 65_535),
                Fields.parse(connectTimeoutField, "Connect timeout", 1, 600_000),
                Fields.parse(ackTimeoutField, "ACK timeout", 1, 3_600_000),
                selectedCharset().name(),
                waitForAckBox.isSelected() ? AckMode.EXPECT_ACK : AckMode.NO_ACK);
    }

    private Charset selectedCharset() {
        String name = charsetBox.getValue();
        try {
            return name == null || name.isBlank() ? StandardCharsets.UTF_8 : Charset.forName(name.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported charset '" + name + "'");
        }
    }

    private void refreshValidation() {
        String text = effectiveText(false);
        ValidationReport report = context.validator().validate(text);
        issuesList.setItems(FXCollections.observableArrayList(report.issues()));
        validationSummary.setText(report.summary());
        String tabTitle = "Validation";
        if (report.hasErrors()) {
            tabTitle += " (" + report.errors().size() + " errors)";
        } else if (!report.warnings().isEmpty()) {
            tabTitle += " (" + report.warnings().size() + " warnings)";
        }
        validationTab.setText(tabTitle);
        try {
            TreeItem<String> root = MessageTree.build(ParsedMessage.parse(text));
            structureTree.setRoot(root);
        } catch (Hl7FormatException | IllegalArgumentException e) {
            structureTree.setRoot(null);
        }
        if (fhirTab.isSelected()) {
            refreshFhir();
        }
    }

    /** Shows the editor's message as a FHIR R4 transaction Bundle. */
    void refreshFhir() {
        try {
            io.hl7sender.core.fhir.V2ToFhir.Result r = io.hl7sender.core.fhir.V2ToFhir.convert(effectiveText(false));
            fhirArea.setText(r.json());
            String counts = String.join(", ", r.resources().entrySet().stream()
                    .map(e -> e.getValue() + " " + e.getKey()).toList());
            fhirSummary.setText(Messages.get("fhir.summary", counts.isEmpty() ? "-" : counts)
                    + (r.notes().isEmpty() ? "" : " " + String.join("; ", r.notes()) + "."));
        } catch (Hl7FormatException | IllegalArgumentException e) {
            fhirArea.clear();
            fhirSummary.setText(Messages.get("fhir.failed", e.getMessage()));
        }
    }

    private void send() {
        if (sendButton.isDisabled() || !sendButton.isVisible()) {
            return;
        }
        MllpClientConfig destination;
        AppSettings.Destination dest;
        TlsOptions tls;
        try {
            dest = readDestination();
            destination = dest.toClientConfig();
            tls = tlsOptions();
        } catch (IllegalArgumentException e) {
            showLocalError(e.getMessage());
            return;
        }
        saveSettings();
        SendOptions options = new SendOptions(generateControlIdBox.isSelected(), generateTimestampBox.isSelected(),
                dest.ackMode());
        String text = effectiveText(true);

        Task<SendResult> task = new Task<>() {
            @Override
            protected SendResult call() {
                return context.sender().send(destination, tls, text, options);
            }
        };
        task.setOnSucceeded(e -> {
            sendButton.setDisable(false);
            show(task.getValue());
        });
        task.setOnFailed(e -> {
            sendButton.setDisable(false);
            Throwable ex = task.getException();
            showLocalError("Unexpected error: " + (ex == null ? "unknown" : ex.getMessage()));
        });
        sendButton.setDisable(true);
        Styles.badge(outcomeBadge, null);
        outcomeBadge.setText("Sending...");
        String where = (tls == null ? "" : "tls://") + destination.address();
        outcomeDetail.setText("to " + where);
        status.accept("Sending to " + where + "...");
        context.executor().submit(task);
    }

    /** TLS options for a direct send, or null for plain TCP. */
    private TlsOptions tlsOptions() {
        TlsChoice choice = tlsBox.getValue();
        if (choice == null || choice.destination() == null || context.engine().isEmpty()) {
            return null;
        }
        try {
            return context.engine().get().tlsOptions(choice.destination()).orElse(null);
        } catch (IOException e) {
            throw new IllegalArgumentException("TLS settings of '" + choice.destination().name() + "': "
                    + e.getMessage());
        }
    }

    private void show(SendResult r) {
        Styles.badge(outcomeBadge, r.outcome().severity());
        outcomeBadge.setText(r.outcome().name().replace('_', ' '));
        outcomeDetail.setText(r.outcome().description() + (r.detail().isEmpty() ? "" : ": " + r.detail()));
        sentControlIdValue.setText(orDash(r.controlId()));
        ParsedAck ack = r.ack().orElse(null);
        ackCodeValue.setText(ack == null ? "-" : ack.code() + " (" + ack.code().description() + ")");
        ackControlIdValue.setText(ack == null ? "-" : orDash(ack.controlId()));
        ackTextValue.setText(ack == null ? "-" : orDash(ack.text()));
        errList.setItems(FXCollections.observableArrayList(
                ack == null ? java.util.List.of() : ack.errors().stream().map(AckError::describe).toList()));
        timingValue.setText(r.connectTime().isZero() ? "-"
                : "connect " + r.connectTime().toMillis() + " ms"
                + (r.roundTrip().isZero() ? "" : ", ACK round trip " + r.roundTrip().toMillis() + " ms"));
        rawAck.setText(r.rawResponse().map(raw -> {
            String display = Hl7Text.toDisplay(raw);
            return display.isEmpty() ? raw : display;
        }).orElse(""));
        sentMessage.setText(Hl7Text.toDisplay(r.sentMessage()));
        history.getItems().add(0, TIME.format(LocalTime.ofInstant(r.startedAt(), ZoneId.systemDefault()))
                + "  " + r.summary());
        status.accept(r.summary());
        if (r.validation().hasErrors()) {
            validationTab.getTabPane().getSelectionModel().select(validationTab);
        }
    }

    private void showLocalError(String message) {
        Styles.badge(outcomeBadge, io.hl7sender.core.send.SendOutcome.Severity.FAILURE);
        outcomeBadge.setText("NOT SENT");
        outcomeDetail.setText(message);
        status.accept(message);
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    /** Colours validation issues by severity. */
    private static final class IssueCell extends ListCell<ValidationIssue> {
        @Override
        protected void updateItem(ValidationIssue item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("issue-error", "issue-warning");
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item.severity() + "  " + item.message());
            setWrapText(true);
            setPrefWidth(0);
            getStyleClass().add(item.severity() == ValidationIssue.Severity.ERROR ? "issue-error" : "issue-warning");
        }
    }
}
