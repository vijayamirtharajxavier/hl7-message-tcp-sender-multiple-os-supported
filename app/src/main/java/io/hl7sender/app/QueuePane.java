package io.hl7sender.app;

import io.hl7sender.core.hl7.validation.ValidationIssue;
import io.hl7sender.core.hl7.validation.ValidationReport;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationPresets;
import io.hl7sender.core.queue.DestinationProfiles;
import io.hl7sender.core.queue.DestinationState;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueExport;
import io.hl7sender.core.queue.QueueListener;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.secrets.SecretStoreException;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.tls.TlsSettings;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * The durable delivery queue: destinations on the left, and pending, delivered and dead-letter
 * messages for the selected destination on the right, with each message's attempts, ACKs and
 * audit trail.
 */
final class QueuePane extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Set<MessageStatus> PENDING =
            EnumSet.of(MessageStatus.QUEUED, MessageStatus.IN_FLIGHT, MessageStatus.RETRY_PENDING);
    private static final Set<MessageStatus> DELIVERED =
            EnumSet.of(MessageStatus.ACKNOWLEDGED, MessageStatus.SENT_UNCONFIRMED);
    private static final Set<MessageStatus> DEAD = EnumSet.of(MessageStatus.DEAD_LETTER);
    private static final int PURGE_AFTER_DAYS = 30;

    private final Consumer<String> status;
    private final DeliveryEngine engine;
    private final QueueStore store;
    private final QueueListener listener;
    private final PauseTransition refreshDelay = new PauseTransition(Duration.millis(150));

    private final ListView<DestinationConfig> destinationList = new ListView<>();
    private final Map<Long, DestinationState> states = new HashMap<>();
    private final Map<Long, Map<MessageStatus, Integer>> counts = new HashMap<>();
    private final Map<Long, CertCheck> certChecks = new HashMap<>();
    private final Label certWarning = new Label();
    /** Set on shutdown; refreshes queued before then must not touch the closed store. */
    private volatile boolean closed;
    private final Button pauseButton = new Button("Pause");
    private final Label header = new Label("Select a destination");
    private final Label stateBadge = new Label();
    private final Label stateDetail = new Label();
    private final TabPane views = new TabPane();
    private final Tab pendingTab = new Tab("Pending");
    private final Tab deliveredTab = new Tab("Delivered");
    private final Tab deadTab = new Tab("Dead letter");
    private final TableView<QueuedMessage> pendingTable = messageTable("pendingTable");
    private final TableView<QueuedMessage> deliveredTable = messageTable("deliveredTable");
    private final TableView<QueuedMessage> deadTable = messageTable("deadLetterTable");
    private final MessageDetailPane detail;

    QueuePane(AppContext context, Consumer<String> status) {
        this.status = status;
        Optional<DeliveryEngine> maybeEngine = context.engine();
        if (maybeEngine.isEmpty()) {
            this.engine = null;
            this.store = null;
            this.listener = null;
            this.detail = null;
            Label unavailable = new Label(context.queueUnavailableReason());
            unavailable.setId("queueUnavailableLabel");
            unavailable.setWrapText(true);
            unavailable.setPadding(new Insets(20));
            setCenter(unavailable);
            return;
        }
        this.engine = maybeEngine.get();
        this.store = engine.store();
        this.detail = new MessageDetailPane(store, "");

        SplitPane split = new SplitPane(buildDestinations(), buildMessages());
        split.setDividerPositions(0.26);
        setCenter(split);

        refreshDelay.setOnFinished(e -> refreshNow());
        this.listener = new QueueListener() {
            @Override
            public void onQueueChanged(long destinationId) {
                Platform.runLater(QueuePane.this::scheduleRefresh);
            }

            @Override
            public void onStateChanged(DestinationState state) {
                Platform.runLater(QueuePane.this::scheduleRefresh);
            }

            @Override
            public void onDestinationsChanged() {
                Platform.runLater(QueuePane.this::scheduleRefresh);
            }
        };
        engine.addListener(listener);
        refreshNow();
    }

    void shutdown() {
        closed = true;
        if (engine != null) {
            engine.removeListener(listener);
        }
        refreshDelay.stop();
    }

    // ---------------------------------------------------------------------------------------------
    // Layout

    private Region buildDestinations() {
        destinationList.setId("destinationList");
        destinationList.setAccessibleText("Destinations");
        destinationList.setPlaceholder(new Label("No destinations yet.\nClick Add to create one."));
        destinationList.setCellFactory(list -> new DestinationCell());
        destinationList.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> refreshMessages());

        Button add = new Button("Add...");
        add.setId("addDestinationButton");
        add.setOnAction(e -> editDestination(null));
        MenuButton more = new MenuButton("More");
        more.setId("destinationMoreMenu");
        for (DestinationPresets.Preset p : DestinationPresets.all()) {
            MenuItem item = new MenuItem("New from preset: " + p.name());
            item.setId("preset-" + p.name().replaceAll("[^A-Za-z0-9]+", "-"));
            item.setOnAction(e -> editDestination(p.config().withName(uniqueName(p.config().name()))));
            more.getItems().add(item);
        }
        MenuItem importItem = new MenuItem("Import destinations...");
        importItem.setId("importDestinationsItem");
        importItem.setOnAction(e -> chooseProfilesToImport());
        MenuItem exportItem = new MenuItem("Export destinations...");
        exportItem.setId("exportDestinationsItem");
        exportItem.setOnAction(e -> chooseProfilesExport());
        more.getItems().addAll(new SeparatorMenuItem(), importItem, exportItem);
        Button edit = new Button("Edit...");
        edit.setId("editDestinationButton");
        edit.setOnAction(e -> selectedDestination().ifPresent(this::editDestination));
        Button delete = new Button("Delete");
        delete.setId("deleteDestinationButton");
        delete.setOnAction(e -> selectedDestination().ifPresent(this::deleteDestination));
        pauseButton.setId("pauseButton");
        pauseButton.setOnAction(e -> selectedDestination().ifPresent(d -> {
            if (d.paused()) {
                engine.resume(d.id());
                status.accept("Resumed delivery to " + d.name());
            } else {
                engine.pause(d.id());
                status.accept("Paused delivery to " + d.name());
            }
        }));
        FlowPane buttons = new FlowPane(6, 6, add, edit, delete, pauseButton, more);
        buttons.getChildren().forEach(n -> ((Region) n).setMinWidth(Region.USE_PREF_SIZE));
        buttons.setPadding(new Insets(6, 0, 0, 0));

        Label title = new Label("Destinations");
        title.getStyleClass().add("section-title");
        VBox box = new VBox(4, title, destinationList, buttons);
        VBox.setVgrow(destinationList, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    private Region buildMessages() {
        header.setId("queueHeader");
        header.getStyleClass().add("section-title");
        stateBadge.setId("queueStateBadge");
        stateDetail.setId("queueStateDetail");
        stateDetail.setWrapText(true);
        HBox headerRow = new HBox(10, header, stateBadge, stateDetail);
        headerRow.setAlignment(Pos.CENTER_LEFT);
        certWarning.setId("certificateWarningLabel");
        certWarning.getStyleClass().add("issue-warning");
        certWarning.setWrapText(true);
        certWarning.managedProperty().bind(certWarning.visibleProperty());
        certWarning.setVisible(false);

        pendingTab.setContent(withActions(pendingTable,
                button("Retry now", "retryNowButton", this::retryNow),
                button("Move to dead letter", "moveToDeadLetterButton", this::moveToDeadLetter),
                button("Delete", "deletePendingButton", () -> deleteSelected(pendingTable))));
        deliveredTab.setContent(withActions(deliveredTable,
                button("Send again", "resendButton", () -> requeue(deliveredTable)),
                button("Purge older than " + PURGE_AFTER_DAYS + " days", "purgeButton", this::purge)));
        deadTab.setContent(withActions(deadTable,
                button("Requeue", "requeueButton", () -> requeue(deadTable)),
                button("Edit...", "editDeadLetterButton", this::editDeadLetter),
                button("Delete", "deleteDeadLetterButton", () -> deleteSelected(deadTable)),
                button("Export...", "exportButton", this::exportDeadLetters)));
        views.getTabs().addAll(pendingTab, deliveredTab, deadTab);
        views.setId("queueViews");
        views.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        views.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showDetail());
        for (TableView<QueuedMessage> t : List.of(pendingTable, deliveredTable, deadTable)) {
            t.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> showDetail());
        }

        VBox top = new VBox(8, headerRow, certWarning, views);
        VBox.setVgrow(views, Priority.ALWAYS);
        top.setPadding(new Insets(10, 10, 0, 0));

        SplitPane split = new SplitPane(top, detail);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        return split;
    }

    private static VBox withActions(TableView<QueuedMessage> table, Button... actions) {
        HBox bar = new HBox(6, actions);
        bar.setPadding(new Insets(6, 0, 0, 0));
        VBox box = new VBox(table, bar);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(6, 0, 6, 0));
        return box;
    }

    private static Button button(String text, String id, Runnable action) {
        Button b = new Button(text);
        b.setId(id);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static TableView<QueuedMessage> messageTable(String id) {
        TableView<QueuedMessage> t = new TableView<>();
        t.setId(id);
        t.setAccessibleText("Messages");
        t.setPlaceholder(new Label("No messages"));
        t.getColumns().add(column("ID", 55, m -> String.valueOf(m.id())));
        t.getColumns().add(column("Enqueued", 130, m -> formatTime(m.createdAt())));
        t.getColumns().add(column("Type", 90, QueuedMessage::messageType));
        t.getColumns().add(column("Control ID", 175, QueuedMessage::controlId));
        t.getColumns().add(column("Status", 125, m -> m.status().name()));
        t.getColumns().add(column("Tries", 45, m -> String.valueOf(m.attempts())));
        t.getColumns().add(column("Next attempt", 90, m -> m.status() == MessageStatus.RETRY_PENDING
                ? formatTime(m.nextAttemptAt()) : ""));
        t.getColumns().add(column("Dup?", 45, m -> m.possibleDuplicate() ? "maybe" : ""));
        t.getColumns().add(column("Last outcome", 160, m -> m.lastOutcome().orElse("")));
        t.getColumns().add(column("Last error", 320, m -> m.lastError().orElse("")));
        return t;
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> value) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return col;
    }

    // ---------------------------------------------------------------------------------------------
    // Refresh

    /** Cached certificate warnings; key stores are re-read when the TLS settings change or the entry is stale. */
    private record CertCheck(TlsSettings tls, Instant at, List<String> warnings) {
    }

    private static final java.time.Duration CERT_CHECK_EVERY = java.time.Duration.ofMinutes(1);

    /** Certificate warnings for a destination, from a cache refreshed at most once a minute. */
    List<String> certificateWarnings(DestinationConfig d) {
        if (!d.tls().enabled()) {
            return List.of();
        }
        CertCheck c = certChecks.get(d.id());
        Instant now = Instant.now();
        if (c == null || !c.tls().equals(d.tls()) || c.at().plus(CERT_CHECK_EVERY).isBefore(now)) {
            c = new CertCheck(d.tls(), now, engine.certificateWarnings(d));
            certChecks.put(d.id(), c);
        }
        return c.warnings();
    }

    private void scheduleRefresh() {
        if (closed) {
            return;
        }
        refreshDelay.playFromStart();
    }

    /** Reloads destinations, counts, states and the visible tables, keeping selections. */
    void refreshNow() {
        if (engine == null || closed) {
            return;
        }
        try {
            Long selectedId = selectedDestination().map(DestinationConfig::id).orElse(null);
            List<DestinationConfig> list = engine.destinations();
            states.clear();
            counts.clear();
            for (DestinationConfig d : list) {
                states.put(d.id(), engine.state(d.id()));
                counts.put(d.id(), store.counts(d.id()));
            }
            destinationList.getItems().setAll(list);
            Optional<DestinationConfig> reselect = list.stream()
                    .filter(d -> selectedId != null && d.id() == selectedId).findFirst();
            // Keep the user's selection; otherwise show the first destination rather than an empty view.
            reselect.or(() -> list.stream().findFirst())
                    .ifPresent(d -> destinationList.getSelectionModel().select(d));
            refreshMessages();
        } catch (QueueException e) {
            status.accept("Queue error: " + e.getMessage());
        }
    }

    private void refreshMessages() {
        if (closed) {
            return;
        }
        try {
            showSelectedDestination();
        } catch (QueueException e) {
            // Shutting down (the store was closed while this refresh ran) or the database is unavailable.
            if (!closed) {
                status.accept("Queue error: " + e.getMessage());
            }
        }
    }

    private void showSelectedDestination() {
        Optional<DestinationConfig> dest = selectedDestination();
        if (dest.isEmpty()) {
            header.setText("Select a destination");
            stateBadge.setText("");
            stateBadge.setVisible(false);
            stateDetail.setText("");
            certWarning.setVisible(false);
            for (TableView<QueuedMessage> t : List.of(pendingTable, deliveredTable, deadTable)) {
                t.getItems().clear();
            }
            showDetail();
            return;
        }
        DestinationConfig d = dest.get();
        header.setText(d.name() + "  (" + d.displayAddress() + ")");
        List<String> warnings = certificateWarnings(d);
        certWarning.setText(String.join("\n", warnings));
        certWarning.setVisible(!warnings.isEmpty());
        DestinationState state = states.getOrDefault(d.id(), engine.state(d.id()));
        stateBadge.setVisible(true);
        stateBadge.setText(label(state.status()));
        Styles.badge(stateBadge, severity(state.status()));
        stateDetail.setText(state.detail() + state.nextAttemptAt().map(t -> " (until " + formatTime(t) + ")")
                .orElse(""));
        pauseButton.setText(d.paused() ? "Resume" : "Pause");

        reload(pendingTable, store.messages(d.id(), PENDING, 1_000, false));
        reload(deliveredTable, store.messages(d.id(), DELIVERED, 500, true));
        reload(deadTable, store.messages(d.id(), DEAD, 1_000, true));
        Map<MessageStatus, Integer> c = counts.getOrDefault(d.id(), store.counts(d.id()));
        pendingTab.setText("Pending (" + sum(c, PENDING) + ")");
        deliveredTab.setText("Delivered (" + sum(c, DELIVERED) + ")");
        deadTab.setText("Dead letter (" + sum(c, DEAD) + ")");
        showDetail();
    }

    private static void reload(TableView<QueuedMessage> table, List<QueuedMessage> rows) {
        Long selected = Optional.ofNullable(table.getSelectionModel().getSelectedItem())
                .map(QueuedMessage::id).orElse(null);
        table.getItems().setAll(rows);
        if (selected != null) {
            rows.stream().filter(m -> m.id() == selected).findFirst()
                    .ifPresent(m -> table.getSelectionModel().select(m));
        }
    }

    private void showDetail() {
        detail.show(selectedMessage());
    }

    // ---------------------------------------------------------------------------------------------
    // Actions

    private void editDestination(DestinationConfig existing) {
        DestinationDialog dialog = new DestinationDialog(window(), existing, engine);
        dialog.showAndWait().ifPresent(r -> {
            try {
                DestinationConfig saved = engine.saveDestination(r.config(), r.trustStorePassword(),
                        r.keyStorePassword());
                refreshNow();
                destinationList.getItems().stream().filter(x -> x.id() == saved.id()).findFirst()
                        .ifPresent(x -> destinationList.getSelectionModel().select(x));
                status.accept((existing == null || existing.id() == 0 ? "Added" : "Updated") + " destination "
                        + saved.name());
            } catch (QueueException | SecretStoreException e) {
                error("Could not save destination", e.getMessage());
            }
        });
    }

    private String uniqueName(String name) {
        Set<String> taken = new HashSet<>();
        engine.destinations().forEach(d -> taken.add(d.name()));
        return DestinationProfiles.uniqueName(name, taken);
    }

    private void chooseProfilesToImport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import destinations");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Destination profiles", "*.json"));
        File file = chooser.showOpenDialog(window());
        if (file != null) {
            try {
                importProfiles(file.toPath());
            } catch (IOException | QueueException | IllegalArgumentException e) {
                error("Import failed", e.getMessage());
            }
        }
    }

    /** Adds every destination in a profile file, renaming any whose name is already taken. */
    int importProfiles(Path file) throws IOException {
        List<DestinationConfig> imported = DestinationProfiles.read(file);
        for (DestinationConfig d : imported) {
            engine.saveDestination(d.withName(uniqueName(d.name())));
        }
        refreshNow();
        long tls = imported.stream().filter(d -> d.tls().enabled()).count();
        status.accept("Imported " + imported.size() + " destination(s)"
                + (tls > 0 ? "; enter the TLS passwords for " + tls + " of them in Edit" : ""));
        return imported.size();
    }

    private void chooseProfilesExport() {
        if (engine.destinations().isEmpty()) {
            status.accept("There are no destinations to export");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export destinations");
        chooser.setInitialFileName("hl7-destinations.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Destination profiles", "*.json"));
        File file = chooser.showSaveDialog(window());
        if (file != null) {
            try {
                exportProfiles(file.toPath());
            } catch (IOException e) {
                error("Export failed", e.getMessage());
            }
        }
    }

    /** Writes all destinations (settings and notes, no passwords or messages) to a profile file. */
    int exportProfiles(Path file) throws IOException {
        List<DestinationConfig> all = engine.destinations();
        DestinationProfiles.write(all, file);
        status.accept("Exported " + all.size() + " destination(s) to " + file.getFileName()
                + " (no passwords or messages)");
        return all.size();
    }

    private void deleteDestination(DestinationConfig d) {
        int pending = sum(store.counts(d.id()), PENDING);
        if (confirm("Delete destination '" + d.name() + "'?", "This permanently deletes the destination and all of "
                + "its messages and history" + (pending > 0 ? ", including " + pending + " undelivered message(s)"
                : "") + ".")) {
            engine.deleteDestination(d.id());
            refreshNow();
            status.accept("Deleted destination " + d.name());
        }
    }

    private void retryNow() {
        selected(pendingTable).ifPresent(m -> report(engine.retryNow(m.id()), "Retrying " + m.controlId() + " now",
                "Only messages waiting to retry can be retried now"));
    }

    private void moveToDeadLetter() {
        selected(pendingTable).ifPresent(m -> report(engine.moveToDeadLetter(m.id(), "Moved to dead letter by user"),
                "Moved " + m.controlId() + " to the dead-letter queue",
                "A message that is being sent cannot be moved"));
    }

    private void requeue(TableView<QueuedMessage> table) {
        selected(table).ifPresent(m -> report(engine.requeue(m.id()), "Re-queued " + m.controlId(),
                "The message could not be re-queued"));
    }

    private void deleteSelected(TableView<QueuedMessage> table) {
        selected(table).ifPresent(m -> {
            if (confirm("Delete message " + m.controlId() + "?", "The message and its history will be removed.")) {
                report(engine.delete(m.id()), "Deleted " + m.controlId(), "A message in flight cannot be deleted");
            }
        });
    }

    private void editDeadLetter() {
        selected(deadTable).ifPresent(m -> {
            Dialog<ButtonType> dialog = new Dialog<>();
            dialog.initOwner(window());
            dialog.setTitle("Edit dead-lettered message");
            dialog.setHeaderText("Fix the message, then Requeue it. It will be sent exactly as written.");
            TextArea editor = new TextArea(display(m.payload()));
            editor.setId("deadLetterEditor");
            editor.setPrefSize(820, 360);
            Styles.mono(editor);
            Label problems = new Label();
            problems.getStyleClass().add("issue-error");
            problems.setWrapText(true);
            dialog.getDialogPane().setContent(new VBox(8, editor, problems));
            dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            dialog.getDialogPane().getStylesheets().add(Styles.stylesheet());
            Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            ok.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                ValidationReport report = engine.editDeadLetter(m.id(), editor.getText());
                if (report.hasErrors()) {
                    problems.setText(report.errors().stream().map(ValidationIssue::message)
                            .collect(Collectors.joining("\n")));
                    e.consume();
                }
            });
            if (dialog.showAndWait().filter(b -> b == ButtonType.OK).isPresent()) {
                status.accept("Saved changes to message " + m.id() + "; click Requeue to send it");
                refreshNow();
            }
        });
    }

    private void exportDeadLetters() {
        selectedDestination().ifPresent(d -> {
            List<QueuedMessage> dead = store.messages(d.id(), DEAD, 100_000, true);
            if (dead.isEmpty()) {
                status.accept("The dead-letter queue is empty");
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export dead-letter queue");
            chooser.setInitialFileName(d.name().replaceAll("[^A-Za-z0-9_-]+", "_") + "-dead-letter.json");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
            File file = chooser.showSaveDialog(window());
            if (file == null) {
                return;
            }
            try {
                int n = QueueExport.writeJson(store, d, dead, file.toPath());
                status.accept("Exported " + n + " message(s) to " + file.getName() + " (contains message content)");
            } catch (IOException e) {
                error("Export failed", e.getMessage());
            }
        });
    }

    private void purge() {
        Instant cutoff = Instant.now().minus(PURGE_AFTER_DAYS, ChronoUnit.DAYS);
        if (confirm("Purge delivered messages?", "Delivered messages completed more than " + PURGE_AFTER_DAYS
                + " days ago, for all destinations, will be permanently deleted with their history.")) {
            int n = store.purgeDelivered(cutoff);
            refreshNow();
            status.accept("Purged " + n + " delivered message(s)");
        }
    }

    private void report(boolean ok, String success, String failure) {
        status.accept(ok ? success : failure);
        refreshNow();
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers

    private Optional<DestinationConfig> selectedDestination() {
        return Optional.ofNullable(destinationList.getSelectionModel().getSelectedItem());
    }

    private Optional<QueuedMessage> selectedMessage() {
        Tab tab = views.getSelectionModel().getSelectedItem();
        TableView<QueuedMessage> table = tab == deliveredTab ? deliveredTable : tab == deadTab ? deadTable
                : pendingTable;
        return Optional.ofNullable(table.getSelectionModel().getSelectedItem());
    }

    /** The selected row of {@code table}; asks the user to select one if there is none. */
    private Optional<QueuedMessage> selected(TableView<QueuedMessage> table) {
        QueuedMessage m = table.getSelectionModel().getSelectedItem();
        if (m == null) {
            status.accept("Select a message first");
        }
        return Optional.ofNullable(m);
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private boolean confirm(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(window());
        alert.setHeaderText(title);
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private void error(String title, String text) {
        Alert alert = new Alert(Alert.AlertType.ERROR, text, ButtonType.OK);
        alert.initOwner(window());
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    private static int sum(Map<MessageStatus, Integer> counts, Set<MessageStatus> statuses) {
        return statuses.stream().mapToInt(s -> counts.getOrDefault(s, 0)).sum();
    }

    private static String display(String wire) {
        return MessageDetailPane.display(wire);
    }

    static String formatTime(Instant instant) {
        var local = instant.atZone(ZoneId.systemDefault());
        return local.toLocalDate().equals(LocalDate.now()) ? TIME.format(local) : DATE_TIME.format(local);
    }

    static String label(DestinationState.Status s) {
        return switch (s) {
            case STOPPED -> "Stopped";
            case IDLE -> "Idle";
            case PAUSED -> "Paused";
            case SENDING -> "Sending";
            case WAITING_RETRY -> "Waiting to retry";
            case CIRCUIT_OPEN -> "Circuit open";
            case ERROR -> "Error";
        };
    }

    static SendOutcome.Severity severity(DestinationState.Status s) {
        return switch (s) {
            case IDLE, SENDING -> SendOutcome.Severity.SUCCESS;
            case WAITING_RETRY, PAUSED -> SendOutcome.Severity.WARNING;
            case CIRCUIT_OPEN, ERROR -> SendOutcome.Severity.FAILURE;
            case STOPPED -> null;
        };
    }

    /** Destination row: name, address, state and counts. */
    private final class DestinationCell extends ListCell<DestinationConfig> {
        @Override
        protected void updateItem(DestinationConfig d, boolean empty) {
            super.updateItem(d, empty);
            if (empty || d == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Map<MessageStatus, Integer> c = counts.getOrDefault(d.id(), Map.of());
            DestinationState s = states.get(d.id());
            Label name = new Label(d.name());
            name.getStyleClass().add("section-title");
            Label badge = new Label(s == null ? "" : label(s.status()));
            Styles.badge(badge, s == null ? null : severity(s.status()));
            badge.setStyle("-fx-font-size: 0.8em; -fx-padding: 1 6 1 6;");
            HBox top = new HBox(8, name, badge);
            top.setAlignment(Pos.CENTER_LEFT);
            if (d.tls().enabled()) {
                List<String> warnings = certificateWarnings(d);
                Label tls = new Label(warnings.isEmpty() ? "TLS" : "TLS \u26A0");
                Styles.badge(tls, warnings.isEmpty() ? SendOutcome.Severity.SUCCESS : SendOutcome.Severity.WARNING);
                tls.setStyle("-fx-font-size: 0.8em; -fx-padding: 1 6 1 6;");
                tls.setTooltip(new Tooltip(warnings.isEmpty() ? "MLLP over TLS" + (d.tls().mutual()
                        ? " with a client certificate" : "") : String.join("\n", warnings)));
                top.getChildren().add(tls);
            }
            Label info = new Label(d.address() + "   " + sum(c, PENDING) + " pending, " + sum(c, DEAD) + " dead");
            info.getStyleClass().add("field-label");
            setText(null);
            setGraphic(new VBox(2, top, info));
        }
    }
}
