package io.hl7sender.app;

import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.schedule.Schedule;
import io.hl7sender.core.schedule.Scheduler;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Window;

/** Tools > Scheduled sends: lists schedules with their next and last runs, and adds, edits, runs and deletes them. */
final class SchedulesDialog extends Dialog<Void> {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AppContext context;
    private final DeliveryEngine engine;
    private final Supplier<String> editorText;
    private final Consumer<String> status;
    private final TableView<Schedule> table = new TableView<>();
    private final Label summary = new Label();
    private Map<Long, String> names = Map.of();

    SchedulesDialog(Window owner, AppContext context, Supplier<String> editorText, Consumer<String> status) {
        this.context = context;
        this.engine = context.engine().orElseThrow();
        this.editorText = editorText;
        this.status = status;
        initOwner(owner);
        setTitle(Messages.get("schedules.title"));
        setHeaderText(Messages.get("schedules.header"));
        setResizable(true);

        table.setId("schedulesTable");
        table.setPlaceholder(new Label(Messages.get("schedules.empty")));
        table.setPrefSize(900, 320);
        table.getColumns().add(column("schedules.col.name", 150, Schedule::name));
        table.getColumns().add(column("schedules.col.cron", 110, Schedule::cron));
        table.getColumns().add(column("schedules.col.destination", 110,
                s -> names.getOrDefault(s.destinationId(), "#" + s.destinationId())));
        table.getColumns().add(column("schedules.col.count", 50, s -> "x" + s.count()));
        table.getColumns().add(column("schedules.col.next", 130, s -> s.nextRunAfter(Instant.now())
                .map(t -> WHEN.format(t.atZone(ZoneId.of(s.zone())))).orElse(Messages.get("schedules.disabled"))));
        table.getColumns().add(column("schedules.col.last", 130, s -> s.lastRunAt()
                .map(t -> WHEN.format(t.atZone(ZoneId.of(s.zone())))).orElse("-")));
        table.getColumns().add(column("schedules.col.result", 220, Schedule::lastResult));
        table.setRowFactory(t -> {
            javafx.scene.control.TableRow<Schedule> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    edit();
                }
            });
            return row;
        });

        Button add = button("schedulesAdd", "schedules.add", this::add);
        Button edit = button("schedulesEdit", "schedules.edit", this::edit);
        Button toggle = button("schedulesToggle", "schedules.toggle", this::toggle);
        Button run = button("schedulesRun", "schedules.run", this::runNow);
        Button delete = button("schedulesDelete", "schedules.delete", this::delete);
        for (Button b : List.of(edit, toggle, run, delete)) {
            b.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        }
        HBox buttons = new HBox(8, add, edit, toggle, run, delete);
        summary.setId("schedulesSummary");
        summary.setWrapText(true);
        BorderPane content = new BorderPane(table);
        content.setTop(buttons);
        content.setBottom(summary);
        BorderPane.setMargin(buttons, new Insets(0, 0, 8, 0));
        BorderPane.setMargin(summary, new Insets(8, 0, 0, 0));
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        refresh();
    }

    private static Button button(String id, String key, Runnable action) {
        Button b = new Button(Messages.get(key));
        b.setId(id);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static TableColumn<Schedule, String> column(String key, double width, Function<Schedule, String> value) {
        TableColumn<Schedule, String> c = new TableColumn<>(Messages.get(key));
        c.setPrefWidth(width);
        c.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        return c;
    }

    void refresh() {
        names = engine.destinations().stream().collect(Collectors.toMap(DestinationConfig::id,
                DestinationConfig::name));
        Schedule selected = table.getSelectionModel().getSelectedItem();
        table.getItems().setAll(engine.store().schedules());
        if (selected != null) {
            table.getItems().stream().filter(s -> s.id() == selected.id()).findFirst()
                    .ifPresent(s -> table.getSelectionModel().select(s));
        }
    }

    /** Saves and tells the scheduler, which then uses the new definition. */
    private void save(Schedule s) {
        try {
            engine.store().saveSchedule(s);
            context.scheduler().ifPresent(Scheduler::wake);
            summary.setText("");
        } catch (QueueException e) {
            summary.setText(e.getMessage());
        }
        refresh();
    }

    private void add() {
        if (engine.destinations().isEmpty()) {
            summary.setText(Messages.get("schedule.noDestination"));
            return;
        }
        new ScheduleDialog(getDialogPane().getScene().getWindow(), null, engine.destinations(), editorText)
                .showAndWait().ifPresent(this::save);
    }

    private void edit() {
        Schedule s = table.getSelectionModel().getSelectedItem();
        if (s != null) {
            new ScheduleDialog(getDialogPane().getScene().getWindow(), s, engine.destinations(), editorText)
                    .showAndWait().ifPresent(this::save);
        }
    }

    private void toggle() {
        Schedule s = table.getSelectionModel().getSelectedItem();
        if (s != null) {
            save(s.withEnabled(!s.enabled()));
        }
    }

    private void delete() {
        Schedule s = table.getSelectionModel().getSelectedItem();
        if (s != null) {
            engine.store().deleteSchedule(s.id());
            context.scheduler().ifPresent(Scheduler::wake);
            refresh();
        }
    }

    /** Runs the selected schedule now, in the background. */
    void runNow() {
        Schedule s = table.getSelectionModel().getSelectedItem();
        Scheduler scheduler = context.scheduler().orElse(null);
        if (s == null || scheduler == null) {
            return;
        }
        summary.setText(Messages.get("schedules.running", s.name()));
        context.executor().submit(() -> {
            String text;
            try {
                Scheduler.Result r = scheduler.runNow(s.id());
                text = Messages.get("schedules.ran", s.name(), r.summary());
            } catch (RuntimeException e) {
                text = Messages.get("schedules.ran", s.name(), e.getMessage());
            }
            String done = text;
            Platform.runLater(() -> {
                summary.setText(done);
                status.accept(done);
                refresh();
            });
        });
    }
}
