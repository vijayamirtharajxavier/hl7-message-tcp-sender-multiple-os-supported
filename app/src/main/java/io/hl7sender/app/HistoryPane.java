package io.hl7sender.app;

import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageQuery;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueExport;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.queue.QueuedMessage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/** Searches every queued message, across destinations, with export to CSV (metadata) or JSON (with content). */
final class HistoryPane extends BorderPane {

    /** Status filter choices. */
    enum StatusFilter {
        ALL("All statuses", EnumSet.noneOf(MessageStatus.class)),
        PENDING("Pending", EnumSet.of(MessageStatus.QUEUED, MessageStatus.IN_FLIGHT, MessageStatus.RETRY_PENDING)),
        DELIVERED("Delivered", EnumSet.of(MessageStatus.ACKNOWLEDGED, MessageStatus.SENT_UNCONFIRMED)),
        DEAD_LETTER("Dead letter", EnumSet.of(MessageStatus.DEAD_LETTER));

        private final String label;
        private final Set<MessageStatus> statuses;

        StatusFilter(String label, Set<MessageStatus> statuses) {
            this.label = label;
            this.statuses = statuses;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final String ALL_DESTINATIONS = "All destinations";

    private final Consumer<String> status;
    private final DeliveryEngine engine;
    private final QueueStore store;
    private final MessageDetailPane detail;
    private final ComboBox<String> destinationBox = new ComboBox<>();
    private final ComboBox<StatusFilter> statusBox =
            new ComboBox<>(FXCollections.observableArrayList(StatusFilter.values()));
    private final TextField typeField = new TextField();
    private final TextField controlIdField = new TextField();
    private final TextField containsField = new TextField();
    private final TextField batchField = new TextField();
    private final DatePicker fromDate = new DatePicker();
    private final DatePicker toDate = new DatePicker();
    private final Label resultLabel = new Label();
    private final TableView<QueuedMessage> table = new TableView<>();
    private Map<Long, String> destinationNames = Map.of();

    HistoryPane(AppContext context, Consumer<String> status) {
        this.status = status;
        this.engine = context.engine().orElse(null);
        if (engine == null) {
            this.store = null;
            this.detail = null;
            Label unavailable = new Label(context.queueUnavailableReason());
            unavailable.setWrapText(true);
            unavailable.setPadding(new Insets(20));
            setCenter(unavailable);
            return;
        }
        this.store = engine.store();
        this.detail = new MessageDetailPane(store, "history");

        setTop(buildFilters());
        buildTable();
        SplitPane split = new SplitPane(table, detail);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        setCenter(split);
        statusBox.setValue(StatusFilter.ALL);
    }

    /** Sets the batch filter and searches (used after an import). */
    void showBatch(String batchId) {
        clearFilters();
        batchField.setText(batchId);
        search();
    }

    void refreshDestinations() {
        if (engine == null) {
            return;
        }
        List<DestinationConfig> list = engine.destinations();
        destinationNames = list.stream().collect(Collectors.toMap(DestinationConfig::id, DestinationConfig::name));
        String selected = destinationBox.getValue();
        destinationBox.getItems().setAll(ALL_DESTINATIONS);
        list.forEach(d -> destinationBox.getItems().add(d.name()));
        destinationBox.setValue(selected != null && destinationBox.getItems().contains(selected) ? selected
                : ALL_DESTINATIONS);
    }

    private VBox buildFilters() {
        destinationBox.setId("historyDestination");
        statusBox.setId("historyStatus");
        typeField.setId("historyType");
        typeField.setPromptText("type, e.g. A01");
        typeField.setPrefColumnCount(8);
        controlIdField.setId("historyControlId");
        controlIdField.setPromptText("MSH-10");
        controlIdField.setPrefColumnCount(12);
        containsField.setId("historyContains");
        containsField.setPromptText("text in message, e.g. MRN");
        containsField.setPrefColumnCount(14);
        batchField.setId("historyBatch");
        batchField.setPromptText("import batch ID");
        batchField.setPrefColumnCount(16);
        fromDate.setId("historyFrom");
        fromDate.setPromptText("from");
        fromDate.setPrefWidth(130);
        toDate.setId("historyTo");
        toDate.setPromptText("to (inclusive)");
        toDate.setPrefWidth(130);
        for (TextField f : List.of(typeField, controlIdField, containsField, batchField)) {
            f.setOnAction(e -> search());
        }
        destinationBox.setOnShowing(e -> refreshDestinations());
        refreshDestinations();

        Button search = new Button("Search");
        search.setId("historySearchButton");
        search.setDefaultButton(false);
        search.setOnAction(e -> search());
        Button clear = new Button("Clear");
        clear.setOnAction(e -> {
            clearFilters();
            search();
        });
        Button csv = new Button("Export CSV...");
        csv.setId("historyExportCsv");
        csv.setOnAction(e -> exportCsv());
        Button json = new Button("Export JSON...");
        json.setId("historyExportJson");
        json.setOnAction(e -> exportJson());
        Button replay = new Button(Messages.get("replay.button"));
        replay.setId("historyReplay");
        replay.setOnAction(e -> chooseReplay());
        replay.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());

        FlowPane filters = new FlowPane(8, 8, destinationBox, statusBox, typeField, controlIdField, containsField,
                batchField, fromDate, toDate, search, clear);
        filters.setAlignment(Pos.CENTER_LEFT);
        resultLabel.setId("historyResultLabel");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, resultLabel, spacer, replay, csv, json);
        bar.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, filters, bar);
        box.setPadding(new Insets(10));
        return box;
    }

    private void buildTable() {
        table.setId("historyTable");
        table.setAccessibleText("Message history");
        table.setPlaceholder(new Label("Click Search to list messages"));
        table.getColumns().add(column("ID", 60, m -> String.valueOf(m.id())));
        table.getColumns().add(column("Destination", 120, m -> destinationNames.getOrDefault(m.destinationId(), "")));
        table.getColumns().add(column("Enqueued", 130, m -> QueuePane.formatTime(m.createdAt())));
        table.getColumns().add(column("Type", 90, QueuedMessage::messageType));
        table.getColumns().add(column("Control ID", 175, QueuedMessage::controlId));
        table.getColumns().add(column("Status", 125, m -> m.status().name()));
        table.getColumns().add(column("Tries", 45, m -> String.valueOf(m.attempts())));
        table.getColumns().add(column("Dup?", 45, m -> m.possibleDuplicate() ? "maybe" : ""));
        table.getColumns().add(column("Last outcome", 150, m -> m.lastOutcome().orElse("")));
        table.getColumns().add(column("Source", 130, m -> m.source().orElse("")));
        table.getColumns().add(column("Batch", 170, m -> m.batchId().orElse("")));
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> detail.show(Optional.ofNullable(b)));
    }

    /** A replay target: the original destination (null) or a named one. */
    private record Target(String label, Long id) {
        @Override
        public String toString() {
            return label;
        }
    }

    private void chooseReplay() {
        List<QueuedMessage> selected = List.copyOf(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        javafx.scene.control.Dialog<ButtonType> d = new javafx.scene.control.Dialog<>();
        d.initOwner(getScene() == null ? null : getScene().getWindow());
        d.setTitle(Messages.get("replay.title"));
        d.setHeaderText(Messages.get("replay.header", selected.size()));
        ComboBox<Target> target = new ComboBox<>();
        target.setId("replayTarget");
        target.getItems().add(new Target(Messages.get("replay.original"), null));
        engine.destinations().forEach(x -> target.getItems().add(new Target(x.name(), x.id())));
        target.setValue(target.getItems().get(0));
        javafx.scene.control.CheckBox newIds = new javafx.scene.control.CheckBox(Messages.get("replay.newIds"));
        newIds.setId("replayNewIds");
        newIds.setSelected(true);
        Label note = new Label(Messages.get("replay.note"));
        note.setWrapText(true);
        note.getStyleClass().add("field-label");
        VBox content = new VBox(8, new HBox(8, Fields.label(Messages.get("replay.to")), target), newIds, note);
        content.setPrefWidth(460);
        d.getDialogPane().setContent(content);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) d.getDialogPane().lookupButton(ButtonType.OK)).setId("replayOk");
        d.getDialogPane().getStylesheets().add(Styles.stylesheet());
        if (d.showAndWait().filter(b -> b == ButtonType.OK).isPresent()) {
            replay(selected.stream().map(QueuedMessage::id).toList(), target.getValue().id(), newIds.isSelected());
        }
    }

    /** Queues copies of {@code ids} and shows the new batch. */
    void replay(List<Long> ids, Long targetDestination, boolean newControlIds) {
        List<io.hl7sender.core.queue.DeliveryEngine.Replayed> result = engine.replay(ids, targetDestination,
                newControlIds);
        long queued = result.stream().filter(r -> r.copy().isPresent()).count();
        String problems = result.stream().filter(r -> r.copy().isEmpty())
                .map(r -> r.sourceId() + ": " + r.problem()).collect(Collectors.joining("; "));
        Optional<String> batch = result.stream().flatMap(r -> r.copy().stream()).findFirst()
                .flatMap(QueuedMessage::batchId);
        status.accept(Messages.get("replay.done", queued, batch.orElse("-"))
                + (problems.isEmpty() ? "" : " " + Messages.get("replay.problems", problems)));
        batch.ifPresent(this::showBatch);
    }

    MessageQuery currentQuery() {
        MessageQuery q = MessageQuery.all();
        String dest = destinationBox.getValue();
        if (dest != null && !ALL_DESTINATIONS.equals(dest)) {
            Optional<Long> id = destinationNames.entrySet().stream().filter(e -> e.getValue().equals(dest))
                    .map(Map.Entry::getKey).findFirst();
            if (id.isPresent()) {
                q = q.withDestination(id.get());
            }
        }
        StatusFilter sf = statusBox.getValue() == null ? StatusFilter.ALL : statusBox.getValue();
        ZoneId zone = ZoneId.systemDefault();
        LocalDate from = fromDate.getValue();
        LocalDate to = toDate.getValue();
        return q.withStatuses(sf.statuses)
                .withText(typeField.getText(), controlIdField.getText(), containsField.getText())
                .withBatch(batchField.getText())
                .withRange(from == null ? null : from.atStartOfDay(zone).toInstant(),
                        to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant());
    }

    void search() {
        if (engine == null) {
            return;
        }
        refreshDestinations();
        MessageQuery q = currentQuery();
        List<QueuedMessage> rows = store.search(q);
        int total = store.count(q);
        table.getItems().setAll(rows);
        resultLabel.setText(total == rows.size() ? total + " message(s)"
                : "Showing newest " + rows.size() + " of " + total + " message(s)");
        detail.show(Optional.empty());
    }

    private void clearFilters() {
        destinationBox.setValue(ALL_DESTINATIONS);
        statusBox.setValue(StatusFilter.ALL);
        typeField.clear();
        controlIdField.clear();
        containsField.clear();
        batchField.clear();
        fromDate.setValue(null);
        toDate.setValue(null);
    }

    private List<QueuedMessage> allMatching() {
        MessageQuery q = currentQuery();
        return store.search(q.withLimit(Math.max(1, store.count(q))));
    }

    private void exportCsv() {
        File file = saveAs("history.csv", "CSV", "*.csv");
        if (file == null) {
            return;
        }
        try {
            int n = QueueExport.writeCsv(allMatching(), destinationNames, file.toPath());
            status.accept("Exported " + n + " message(s) (metadata only) to " + file.getName());
        } catch (IOException e) {
            status.accept("Export failed: " + e.getMessage());
        }
    }

    private void exportJson() {
        Alert warn = new Alert(Alert.AlertType.CONFIRMATION, "The JSON export includes full message content, which "
                + "may contain patient information. Store and share it accordingly.", ButtonType.OK, ButtonType.CANCEL);
        warn.initOwner(getScene() == null ? null : getScene().getWindow());
        warn.setHeaderText("Export message content?");
        if (warn.showAndWait().filter(b -> b == ButtonType.OK).isEmpty()) {
            return;
        }
        File file = saveAs("history.json", "JSON", "*.json");
        if (file == null) {
            return;
        }
        try {
            List<QueuedMessage> rows = allMatching();
            DestinationConfig label = DestinationConfig.of("History export", "localhost", 1);
            int n = QueueExport.writeJson(store, label, rows, file.toPath());
            status.accept("Exported " + n + " message(s) with content to " + file.getName());
        } catch (IOException e) {
            status.accept("Export failed: " + e.getMessage());
        }
    }

    private File saveAs(String name, String type, String pattern) {
        FileChooser chooser = new FileChooser();
        chooser.setInitialFileName(name);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(type, pattern));
        return chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
    }

    private static <T> TableColumn<T, String> column(String title, double width, Function<T, String> value) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(width);
        col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return col;
    }
}
