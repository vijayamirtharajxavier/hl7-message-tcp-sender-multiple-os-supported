package io.hl7sender.app;

import io.hl7sender.core.AppInfo;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.update.UpdateChecker;
import java.util.function.Consumer;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Checks GitHub Releases for a newer version, on request. Also holds the "check automatically" preference. Nothing
 * is downloaded: a newer version is shown with its notes and a link to its download page.
 */
final class UpdateDialog extends Dialog<ButtonType> {

    private final AppContext context;
    private final HostServices hostServices;
    private final Label result = new Label();
    private final TextArea notes = new TextArea();
    private final Button open = new Button(Messages.get("update.open"));
    private final Button skip = new Button(Messages.get("update.skip"));
    private final CheckBox automatic = new CheckBox(Messages.get("update.automatic"));
    private UpdateChecker.Result last;

    UpdateDialog(Window owner, AppContext context, HostServices hostServices, UpdateChecker checker) {
        this.context = context;
        this.hostServices = hostServices;
        initOwner(owner);
        setTitle(Messages.get("update.title"));
        setHeaderText(Messages.get("update.current", AppInfo.version()));
        result.setId("updateResultLabel");
        result.setWrapText(true);
        notes.setId("updateNotes");
        notes.setEditable(false);
        notes.setWrapText(true);
        notes.setPrefRowCount(8);
        notes.setVisible(false);
        notes.managedProperty().bind(notes.visibleProperty());
        open.setId("updateOpenButton");
        open.setVisible(false);
        open.setOnAction(e -> {
            if (last != null && hostServices != null) {
                hostServices.showDocument(last.latest().pageUrl());
            }
        });
        skip.setId("updateSkipButton");
        skip.setVisible(false);
        skip.setOnAction(e -> {
            if (last != null) {
                String v = last.latest().version();
                context.updateSettings(s -> s.withUpdates(new AppSettings.Updates(
                        s.updates().checkAutomatically(), s.updates().lastCheckedMillis(), v)));
                result.setText(Messages.get("update.skipped", v));
            }
        });
        automatic.setId("updateAutomaticBox");
        automatic.setSelected(context.settings().updates().checkAutomatically());
        automatic.selectedProperty().addListener((o, a, on) -> context.updateSettings(s -> s.withUpdates(
                new AppSettings.Updates(on, s.updates().lastCheckedMillis(), s.updates().skippedVersion()))));
        Label privacy = new Label(Messages.get("update.privacy"));
        privacy.setWrapText(true);
        privacy.getStyleClass().add("field-label");

        VBox content = new VBox(8, result, notes, new HBox(8, open, skip), automatic, privacy);
        content.setPadding(new Insets(4));
        content.setPrefWidth(560);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        check(checker);
    }

    private void check(UpdateChecker checker) {
        result.setText(Messages.get("update.checking"));
        Task<UpdateChecker.Result> task = new Task<>() {
            @Override
            protected UpdateChecker.Result call() throws Exception {
                return checker.check();
            }
        };
        task.setOnSucceeded(e -> show(task.getValue()));
        task.setOnFailed(e -> result.setText(Messages.get("update.failed", task.getException() == null ? "?"
                : task.getException().getMessage())));
        context.executor().submit(task);
    }

    private void show(UpdateChecker.Result r) {
        last = r;
        if (!r.newer()) {
            result.setText(Messages.get("update.latest", r.current()));
            return;
        }
        result.setText(Messages.get("update.available", r.latest().version(), r.current()));
        notes.setText(r.latest().notes());
        notes.setVisible(!r.latest().notes().isBlank());
        open.setVisible(hostServices != null);
        skip.setVisible(true);
    }

    /**
     * The automatic check: at most once a day, only if the user turned it on. Calls {@code onNewer} (on the
     * caller's executor thread) when a version newer than the running one, and not skipped, is available.
     */
    static void checkInBackground(AppContext context, UpdateChecker checker, Consumer<UpdateChecker.Result> onNewer) {
        AppSettings.Updates u = context.settings().updates();
        long now = System.currentTimeMillis();
        if (!u.checkAutomatically() || now - u.lastCheckedMillis() < 24L * 60 * 60 * 1000) {
            return;
        }
        context.executor().submit(() -> {
            try {
                UpdateChecker.Result r = checker.check();
                context.updateSettings(s -> s.withUpdates(new AppSettings.Updates(
                        s.updates().checkAutomatically(), now, s.updates().skippedVersion())));
                if (r.newer() && !r.latest().version().equals(context.settings().updates().skippedVersion())) {
                    onNewer.accept(r);
                }
            } catch (java.io.IOException | RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(UpdateDialog.class).info("Update check failed: {}", e.getMessage());
            }
        });
    }
}
