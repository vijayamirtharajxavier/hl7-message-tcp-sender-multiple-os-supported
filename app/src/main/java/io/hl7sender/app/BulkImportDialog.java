package io.hl7sender.app;

import io.hl7sender.core.batch.MessageFiles;
import io.hl7sender.core.batch.SplitResult;
import io.hl7sender.core.queue.BulkItem;
import io.hl7sender.core.queue.BulkProgress;
import io.hl7sender.core.queue.BulkResult;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageQuery;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.template.TemplateEngine;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Queues many messages at once: from files (including HL7 batch files), every message file in a folder,
 * or N copies of the editor's template. The import validates each message per the destination's policy,
 * stores valid ones in chunks while delivery starts, and then tracks delivery of the batch.
 */
final class BulkImportDialog extends Stage {

    private final DeliveryEngine engine;
    private final AppContext context;
    private final Consumer<String> status;
    private final String editorTemplate;

    private final List<BulkItem> items = new ArrayList<>();
    private final ListView<String> sources = new ListView<>();
    private final Label summary = new Label("Choose files, a folder or the editor template.");
    private final ComboBox<DestinationConfig> destination = new ComboBox<>();
    private final CheckBox generateControlId = new CheckBox("Generate new MSH-10 control IDs");
    private final CheckBox generateTimestamp = new CheckBox("Set MSH-7 to now");
    private final CheckBox expandVariables = new CheckBox("Expand ${variables}");
    private final Spinner<Integer> copies = new Spinner<>(1, 1_000_000, 100);
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressLabel = new Label();
    private final Label deliveryLabel = new Label();
    private final ListView<String> problems = new ListView<>();
    private final Button importButton = new Button("Import");
    private final Button cancelButton = new Button("Cancel");
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Timeline deliveryTracker;
    private boolean running;

    BulkImportDialog(Window owner, AppContext context, Consumer<String> status, String editorTemplate) {
        this.context = context;
        this.engine = context.engine().orElseThrow();
        this.status = status;
        this.editorTemplate = editorTemplate;
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setTitle("Import messages into a queue");

        Button files = new Button("Add files...");
        files.setId("importFilesButton");
        files.setOnAction(e -> chooseFiles());
        Button folder = new Button("Add folder...");
        folder.setId("importFolderButton");
        folder.setOnAction(e -> chooseFolder());
        Button template = new Button("Add editor template x");
        template.setId("importTemplateButton");
        template.setDisable(editorTemplate == null);
        template.setOnAction(e -> addTemplateCopies());
        copies.setId("importCopies");
        copies.setEditable(true);
        copies.setPrefWidth(110);
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            items.clear();
            sources.getItems().clear();
            updateSummary(List.of());
        });
        HBox sourceButtons = new HBox(6, files, folder, template, copies, clear);
        sourceButtons.setAlignment(Pos.CENTER_LEFT);

        sources.setId("importSources");
        sources.setPrefHeight(140);
        summary.setId("importSummary");
        summary.setWrapText(true);

        destination.setId("importDestination");
        destination.setItems(FXCollections.observableArrayList(engine.destinations()));
        destination.getSelectionModel().selectFirst();
        destination.setCellFactory(l -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(DestinationConfig d, boolean empty) {
                super.updateItem(d, empty);
                setText(empty || d == null ? null : d.name() + "  (" + d.address() + ")");
            }
        });
        destination.setButtonCell(destination.getCellFactory().call(null));
        generateControlId.setId("importGenerateControlId");
        generateTimestamp.setId("importGenerateTimestamp");
        expandVariables.setId("importExpandVariables");
        expandVariables.setSelected(true);

        GridPane options = new GridPane();
        options.getStyleClass().add("form-pane");
        options.setPadding(new Insets(0));
        options.addRow(0, Fields.label("Destination"), destination);
        options.addRow(1, new Label(), new HBox(12, generateControlId, generateTimestamp, expandVariables));

        progress.setId("importProgress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progressLabel.setId("importProgressLabel");
        deliveryLabel.setId("importDeliveryLabel");
        problems.setId("importProblems");
        problems.setPrefHeight(120);
        problems.setPlaceholder(new Label("No problems"));

        importButton.setId("importStartButton");
        importButton.setDefaultButton(true);
        importButton.setOnAction(e -> startImport());
        cancelButton.setId("importCancelButton");
        cancelButton.setOnAction(e -> {
            if (running) {
                cancelled.set(true);
            } else {
                close();
            }
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, spacer, importButton, cancelButton);

        VBox root = new VBox(10, section("1. Messages"), sourceButtons, sources, summary, section("2. Destination"),
                options, section("3. Progress"), progress, progressLabel, deliveryLabel, problems, buttons);
        root.setPadding(new Insets(14));
        Scene scene = new Scene(root, 760, 680);
        scene.getStylesheets().add(Styles.stylesheet());
        setScene(scene);
        setOnHidden(e -> {
            cancelled.set(true);
            if (deliveryTracker != null) {
                deliveryTracker.stop();
            }
        });
        updateSummary(List.of());
    }

    /** Adds files programmatically (used by tests and drag-and-drop callers). */
    void addFiles(List<Path> files) {
        List<String> warnings = new ArrayList<>();
        for (Path f : files) {
            try {
                Charset cs = selectedCharset();
                SplitResult split = MessageFiles.read(f, cs);
                String name = String.valueOf(f.getFileName());
                for (int i = 0; i < split.messages().size(); i++) {
                    items.add(new BulkItem(split.messages().get(i), name + "#" + (i + 1)));
                }
                sources.getItems().add(name + "  -  " + split.messages().size() + " message(s)"
                        + (split.isBatch() ? " (batch file)" : ""));
                split.warnings().forEach(w -> warnings.add(name + ": " + w));
            } catch (IOException | RuntimeException e) {
                warnings.add(String.valueOf(f.getFileName()) + ": " + e.getMessage());
            }
        }
        updateSummary(warnings);
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose HL7 files");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("HL7 messages", "*.hl7", "*.txt", "*.msg", "*.dat", "*.er7"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        List<File> chosen = chooser.showOpenMultipleDialog(this);
        if (chosen != null) {
            addFiles(chosen.stream().map(File::toPath).toList());
        }
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a folder of HL7 files");
        File dir = chooser.showDialog(this);
        if (dir != null) {
            try {
                addFiles(MessageFiles.list(dir.toPath()));
            } catch (IOException e) {
                updateSummary(List.of(dir.getName() + ": " + e.getMessage()));
            }
        }
    }

    private void addTemplateCopies() {
        int n = copies.getValue();
        for (int i = 0; i < n; i++) {
            items.add(new BulkItem(editorTemplate, "template#" + (i + 1)));
        }
        sources.getItems().add("Editor template  -  " + n + " message(s)");
        updateSummary(List.of());
    }

    private void updateSummary(List<String> warnings) {
        summary.setText(items.isEmpty() ? "Choose files, a folder or the editor template."
                : items.size() + " message(s) ready to import.");
        problems.getItems().setAll(warnings);
        importButton.setDisable(items.isEmpty() || destination.getValue() == null || running);
    }

    private Charset selectedCharset() {
        DestinationConfig d = destination.getValue();
        return Charset.forName(d == null ? "UTF-8" : d.charset());
    }

    private void startImport() {
        DestinationConfig d = destination.getValue();
        if (d == null || items.isEmpty()) {
            return;
        }
        running = true;
        cancelled.set(false);
        importButton.setDisable(true);
        cancelButton.setText("Stop");
        List<BulkItem> batch = List.copyOf(items);
        SendOptions options = new SendOptions(generateControlId.isSelected(), generateTimestamp.isSelected(),
                d.ackMode());
        TemplateEngine templates = expandVariables.isSelected() ? context.templates() : null;
        progressLabel.setText("Validating and queuing...");

        Task<BulkResult> task = new Task<>() {
            @Override
            protected BulkResult call() {
                return engine.enqueueAll(d.id(), batch, options, templates, new BulkProgress() {
                    @Override
                    public void onProgress(int processed, int total, int accepted, int rejected) {
                        updateProgress(processed, total);
                        updateMessage("Processed " + processed + " of " + total + ": " + accepted + " queued, "
                                + rejected + " rejected");
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }
                });
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        progressLabel.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> finished(d, task.getValue()));
        task.setOnFailed(e -> {
            running = false;
            progressLabel.textProperty().unbind();
            progressLabel.setText("Import failed: " + task.getException().getMessage());
            cancelButton.setText("Close");
        });
        context.executor().submit(task);
    }

    private void finished(DestinationConfig d, BulkResult r) {
        running = false;
        progress.progressProperty().unbind();
        progressLabel.textProperty().unbind();
        progress.setProgress(1);
        progressLabel.setText((r.cancelled() ? "Stopped. " : "Done. ") + r.accepted() + " of " + r.total()
                + " message(s) queued to " + d.name() + " as batch " + r.batchId() + "; "
                + r.rejected().size() + " rejected.");
        List<String> lines = new ArrayList<>(r.warnings());
        r.rejected().stream().limit(500).forEach(x -> lines.add(x.source() + ": " + x.reason()));
        if (r.rejected().size() > 500) {
            lines.add("... and " + (r.rejected().size() - 500) + " more");
        }
        problems.getItems().setAll(lines);
        cancelButton.setText("Close");
        status.accept("Imported " + r.accepted() + " message(s) into " + d.name() + " (batch " + r.batchId() + ")");
        trackDelivery(r);
    }

    /** Shows delivery progress of the batch until it completes or the dialog closes. */
    private void trackDelivery(BulkResult r) {
        if (r.accepted() == 0) {
            return;
        }
        MessageQuery batch = MessageQuery.all().withBatch(r.batchId());
        MessageQuery done = batch.withStatuses(EnumSet.of(MessageStatus.ACKNOWLEDGED, MessageStatus.SENT_UNCONFIRMED));
        MessageQuery dead = batch.withStatuses(EnumSet.of(MessageStatus.DEAD_LETTER));
        deliveryTracker = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            int delivered = engine.store().count(done);
            int failed = engine.store().count(dead);
            deliveryLabel.setText("Delivered " + delivered + " of " + r.accepted()
                    + (failed > 0 ? ", " + failed + " dead-lettered" : "")
                    + (delivered + failed >= r.accepted() ? " - complete" : ""));
            if (delivered + failed >= r.accepted()) {
                deliveryTracker.stop();
            }
        }));
        deliveryTracker.setCycleCount(Timeline.INDEFINITE);
        deliveryTracker.play();
        Platform.runLater(() -> deliveryLabel.setText("Delivering..."));
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }
}
