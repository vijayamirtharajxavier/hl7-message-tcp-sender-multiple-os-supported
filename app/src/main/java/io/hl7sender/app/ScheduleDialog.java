package io.hl7sender.app;

import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.schedule.CronExpression;
import io.hl7sender.core.schedule.Schedule;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

/** Creates or edits a scheduled send: when (cron), where, and which message(s). */
final class ScheduleDialog extends Dialog<Schedule> {

    private static final DateTimeFormatter PREVIEW = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm");

    private final Schedule existing;
    private final TextField name = new TextField();
    private final ComboBox<DestinationConfig> destination = new ComboBox<>();
    private final TextField cron = new TextField();
    private final ComboBox<String> zone = new ComboBox<>();
    private final Label preview = new Label();
    private final TextField count = Fields.integer("scheduleCountField", 1, 5, 5);
    private final TextArea message = new TextArea();
    private final CheckBox enabled = new CheckBox(Messages.get("schedule.enabled"));
    private final Label error = new Label();

    ScheduleDialog(Window owner, Schedule existing, List<DestinationConfig> destinations, Supplier<String> editorText) {
        this.existing = existing;
        initOwner(owner);
        setTitle(Messages.get(existing == null ? "schedule.add.title" : "schedule.edit.title"));
        setHeaderText(Messages.get("schedule.header"));
        name.setId("scheduleNameField");
        destination.setId("scheduleDestinationBox");
        destination.getItems().setAll(destinations);
        destination.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(DestinationConfig d) {
                return d == null ? "" : d.name();
            }

            @Override
            public DestinationConfig fromString(String s) {
                return null;
            }
        });
        cron.setId("scheduleCronField");
        cron.setPromptText("0 7 * * 1-5");
        zone.setId("scheduleZoneBox");
        zone.setEditable(true);
        zone.getItems().setAll(ZoneId.getAvailableZoneIds().stream().sorted().toList());
        preview.setId("schedulePreviewLabel");
        preview.setWrapText(true);
        message.setId("scheduleMessageArea");
        message.setPrefRowCount(8);
        Styles.mono(message);
        enabled.setId("scheduleEnabledBox");
        error.setId("scheduleErrorLabel");
        error.getStyleClass().add("issue-error");
        error.setWrapText(true);

        MenuButton presets = new MenuButton(Messages.get("schedule.presets"));
        presets.setId("schedulePresets");
        for (String[] p : new String[][] {{"schedule.preset.15min", "*/15 * * * *"},
            {"schedule.preset.hourly", "0 * * * *"}, {"schedule.preset.weekdays", "0 7 * * 1-5"},
            {"schedule.preset.daily", "0 0 * * *"}, {"schedule.preset.weekly", "0 6 * * 1"}}) {
            MenuItem item = new MenuItem(Messages.get(p[0]) + "   (" + p[1] + ")");
            item.setOnAction(e -> cron.setText(p[1]));
            presets.getItems().add(item);
        }
        Button fromEditor = new Button(Messages.get("schedule.fromEditor"));
        fromEditor.setId("scheduleFromEditor");
        fromEditor.setOnAction(e -> message.setText(editorText.get().replace("\r\n", "\n").replace('\r', '\n')));

        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.setPadding(new Insets(4));
        HBox cronRow = new HBox(8, cron, presets);
        HBox.setHgrow(cron, Priority.ALWAYS);
        int row = 0;
        g.addRow(row++, Fields.label(Messages.get("schedule.name")), name);
        g.addRow(row++, Fields.label(Messages.get("schedule.destination")), destination);
        g.addRow(row++, Fields.label(Messages.get("schedule.cron")), cronRow);
        g.addRow(row++, Fields.label(Messages.get("schedule.zone")), zone);
        g.addRow(row++, new Label(), preview);
        g.addRow(row++, Fields.label(Messages.get("schedule.count")), count);
        g.addRow(row++, Fields.label(Messages.get("schedule.message")), message);
        g.addRow(row++, new Label(), new HBox(8, fromEditor, enabled));
        Label help = new Label(Messages.get("schedule.help"));
        help.setWrapText(true);
        help.getStyleClass().add("field-label");
        g.add(help, 0, row++, 2, 1);
        g.add(error, 0, row, 2, 1);
        GridPane.setHgrow(message, Priority.ALWAYS);
        g.setPrefWidth(720);

        cron.textProperty().addListener((o, a, b) -> updatePreview());
        zone.valueProperty().addListener((o, a, b) -> updatePreview());
        zone.getEditor().textProperty().addListener((o, a, b) -> updatePreview());
        load(existing, destinations);
        updatePreview();

        getDialogPane().setContent(g);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("scheduleOkButton");
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            try {
                read();
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                e.consume();
            }
        });
        setResultConverter(b -> b == ButtonType.OK ? read() : null);
    }

    private void load(Schedule s, List<DestinationConfig> destinations) {
        if (s == null) {
            cron.setText("0 7 * * 1-5");
            zone.setValue(ZoneId.systemDefault().getId());
            enabled.setSelected(true);
            if (!destinations.isEmpty()) {
                destination.setValue(destinations.get(0));
            }
            return;
        }
        name.setText(s.name());
        destinations.stream().filter(d -> d.id() == s.destinationId()).findFirst().ifPresent(destination::setValue);
        cron.setText(s.cron());
        zone.setValue(s.zone());
        count.setText(String.valueOf(s.count()));
        message.setText(s.message().replace("\r\n", "\n").replace('\r', '\n'));
        enabled.setSelected(s.enabled());
    }

    private String zoneText() {
        String z = zone.getEditor().getText();
        return z == null || z.isBlank() ? zone.getValue() : z.trim();
    }

    private void updatePreview() {
        try {
            CronExpression c = CronExpression.parse(cron.getText());
            ZoneId z = ZoneId.of(zoneText());
            List<ZonedDateTime> next = c.nextTimes(ZonedDateTime.now(z), 3);
            preview.getStyleClass().remove("issue-error");
            preview.setText(Messages.get("schedule.next", String.join(",  ",
                    next.stream().map(t -> PREVIEW.withLocale(Messages.locale()).format(t)).toList())));
        } catch (RuntimeException e) {
            if (!preview.getStyleClass().contains("issue-error")) {
                preview.getStyleClass().add("issue-error");
            }
            preview.setText(e.getMessage());
        }
    }

    /** The schedule as entered; throws with a readable message if it is not valid. */
    Schedule read() {
        DestinationConfig d = destination.getValue();
        if (d == null) {
            throw new IllegalArgumentException(Messages.get("schedule.noDestination"));
        }
        int n = Fields.parse(count, Messages.get("schedule.count"), 1, Schedule.MAX_COUNT);
        return new Schedule(existing == null ? 0 : existing.id(), name.getText(), cron.getText(), zoneText(), d.id(),
                message.getText(), n, enabled.isSelected(),
                existing == null ? Optional.empty() : existing.lastRunAt(),
                existing == null ? "" : existing.lastResult());
    }
}
