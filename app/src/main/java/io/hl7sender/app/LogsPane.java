package io.hl7sender.app;

import ch.qos.logback.classic.Level;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * The live application log: the most recent {@value LogBuffer#CAPACITY} events, filtered by minimum level
 * and text, following new events as they arrive. The log never contains message content.
 */
final class LogsPane extends BorderPane {

    /** Minimum level shown. */
    enum MinLevel {
        ALL(Level.TRACE), INFO(Level.INFO), WARN(Level.WARN), ERROR(Level.ERROR);

        private final Level level;

        MinLevel(Level level) {
            this.level = level;
        }

        @Override
        public String toString() {
            return Messages.get("logs.level." + name());
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final LogBuffer buffer;
    private final ObservableList<LogBuffer.Entry> all = FXCollections.observableArrayList();
    private final FilteredList<LogBuffer.Entry> shown = new FilteredList<>(all, e -> true);
    private final TableView<LogBuffer.Entry> table = new TableView<>(shown);
    private final ComboBox<MinLevel> levelBox = new ComboBox<>(FXCollections.observableArrayList(MinLevel.values()));
    private final TextField filter = new TextField();
    private final CheckBox follow = new CheckBox(Messages.get("logs.follow"));
    private final Label count = new Label();
    private final ConcurrentLinkedQueue<LogBuffer.Entry> incoming = new ConcurrentLinkedQueue<>();
    private final Timeline flusher = new Timeline(new KeyFrame(Duration.millis(250), e -> flush()));
    private final Consumer<LogBuffer.Entry> listener = incoming::add;

    LogsPane(AppContext context, Runnable openLogFolder, Runnable exportDiagnostics) {
        this.buffer = context.logs();
        levelBox.setId("logLevelBox");
        levelBox.setValue(MinLevel.ALL);
        levelBox.setAccessibleText(Messages.get("logs.level"));
        levelBox.valueProperty().addListener((o, a, b) -> applyFilter());
        filter.setId("logFilterField");
        filter.setPromptText(Messages.get("logs.filter.prompt"));
        filter.setAccessibleText(Messages.get("logs.filter"));
        filter.setPrefColumnCount(24);
        filter.textProperty().addListener((o, a, b) -> applyFilter());
        follow.setId("logFollowBox");
        follow.setSelected(true);
        count.setId("logCountLabel");
        count.getStyleClass().add("field-label");

        Button clear = new Button(Messages.get("logs.clear"));
        clear.setId("logClearButton");
        clear.setOnAction(e -> all.clear());
        Button folder = new Button(Messages.get("logs.openFolder"));
        folder.setId("logOpenFolderButton");
        folder.setOnAction(e -> openLogFolder.run());
        Button diagnostics = new Button(Messages.get("logs.diagnostics"));
        diagnostics.setId("logDiagnosticsButton");
        diagnostics.setOnAction(e -> exportDiagnostics.run());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, Fields.label(Messages.get("logs.level")), levelBox, filter, follow, count, spacer,
                clear, folder, diagnostics);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10));
        bar.getChildren().forEach(n -> ((Region) n).setMinWidth(Region.USE_PREF_SIZE));
        filter.setMinWidth(120);

        table.setId("logTable");
        table.setPlaceholder(new Label(Messages.get("logs.empty")));
        table.setAccessibleText(Messages.get("logs.table"));
        table.getColumns().add(column(Messages.get("logs.col.time"), 100,
                e -> TIME.format(e.time().atZone(ZoneId.systemDefault()))));
        TableColumn<LogBuffer.Entry, String> level = column(Messages.get("logs.col.level"), 60,
                e -> e.level().toString());
        level.setCellFactory(c -> new LevelCell());
        table.getColumns().add(level);
        table.getColumns().add(column(Messages.get("logs.col.logger"), 150, LogBuffer.Entry::logger));
        TableColumn<LogBuffer.Entry, String> message = column(Messages.get("logs.col.message"), 700,
                LogBuffer.Entry::message);
        table.getColumns().add(message);
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        setTop(bar);
        setCenter(table);
        BorderPane.setMargin(table, new Insets(0, 10, 10, 10));

        all.setAll(buffer.snapshot());
        buffer.addListener(listener);
        flusher.setCycleCount(Timeline.INDEFINITE);
        flusher.play();
        applyFilter();
    }

    void shutdown() {
        flusher.stop();
        buffer.removeListener(listener);
    }

    /** Moves events logged since the last tick into the table, trimming to the buffer's capacity. */
    void flush() {
        if (incoming.isEmpty()) {
            return;
        }
        List<LogBuffer.Entry> batch = new ArrayList<>();
        for (LogBuffer.Entry e; (e = incoming.poll()) != null; ) {
            batch.add(e);
        }
        all.addAll(batch);
        if (all.size() > LogBuffer.CAPACITY) {
            all.remove(0, all.size() - LogBuffer.CAPACITY);
        }
        updateCount();
        if (follow.isSelected() && !shown.isEmpty()) {
            table.scrollTo(shown.size() - 1);
        }
    }

    private void applyFilter() {
        Level min = levelBox.getValue() == null ? Level.TRACE : levelBox.getValue().level;
        String text = filter.getText() == null ? "" : filter.getText().trim().toLowerCase(Locale.ROOT);
        shown.setPredicate(e -> e.level().isGreaterOrEqual(min) && (text.isEmpty()
                || e.message().toLowerCase(Locale.ROOT).contains(text)
                || e.logger().toLowerCase(Locale.ROOT).contains(text)));
        updateCount();
    }

    private void updateCount() {
        count.setText(Messages.get("logs.count", shown.size(), all.size()));
    }

    private static TableColumn<LogBuffer.Entry, String> column(String title, double width,
                                                              Function<LogBuffer.Entry, String> value) {
        TableColumn<LogBuffer.Entry, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(d -> new ReadOnlyStringWrapper(value.apply(d.getValue())));
        c.setSortable(false);
        return c;
    }

    /** Colours WARN and ERROR levels. */
    private static final class LevelCell extends TableCell<LogBuffer.Entry, String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("issue-error", "issue-warning");
            setText(empty ? null : item);
            if (!empty && "ERROR".equals(item)) {
                getStyleClass().add("issue-error");
            } else if (!empty && "WARN".equals(item)) {
                getStyleClass().add("issue-warning");
            }
        }
    }
}
