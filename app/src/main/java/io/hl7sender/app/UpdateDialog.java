package io.hl7sender.app;

import io.hl7sender.core.AppInfo;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.update.UpdateChecker;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Checks GitHub Releases for a newer version, on request. Also holds the "check automatically" preference. Nothing
 * is downloaded: a newer version is shown with its notes and a link to its download page.
 */
final class UpdateDialog extends Dialog<ButtonType> {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*|__)(.+?)\\1");
    private static final Pattern TABLE_RULE = Pattern.compile("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?");

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
        result.setMinHeight(Region.USE_PREF_SIZE);
        notes.setId("updateNotes");
        notes.setEditable(false);
        notes.setWrapText(true);
        notes.setPrefRowCount(8);
        notes.setVisible(false);
        notes.managedProperty().bind(notes.visibleProperty());
        VBox.setVgrow(notes, Priority.ALWAYS);
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
        privacy.setMinHeight(Region.USE_PREF_SIZE);
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
        task.setOnFailed(e -> {
            result.setText(Messages.get("update.failed", task.getException() == null ? "?"
                    : task.getException().getMessage()));
            fitWindow();
        });
        context.executor().submit(task);
    }

    private void show(UpdateChecker.Result r) {
        last = r;
        if (!r.newer()) {
            result.setText(Messages.get("update.latest", r.current()));
            fitWindow();
            return;
        }
        result.setText(Messages.get("update.available", r.latest().version(), r.current()));
        String text = plainNotes(r.latest().notes());
        notes.setText(text);
        notes.setVisible(!text.isBlank());
        open.setVisible(hostServices != null);
        skip.setVisible(true);
        fitWindow();
    }

    /**
     * Grows the window to fit what the check added. The dialog is sized when it opens, while it only says
     * "Checking...", so without this the result, notes and buttons are cut off. (The dialog is deliberately not
     * resizable: under Linux window managers a resizable dialog opens at its minimum size.)
     */
    private void fitWindow() {
        Platform.runLater(() -> {
            DialogPane pane = getDialogPane();
            if (pane.getScene() == null || !(pane.getScene().getWindow() instanceof Stage stage)) {
                return;
            }
            // Wrapped text needs the height for the window's actual width, which sizeToScene() does not use.
            pane.applyCss();
            pane.layout();
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            double frameW = stage.getWidth() - pane.getScene().getWidth();
            double frameH = stage.getHeight() - pane.getScene().getHeight();
            double width = Math.max(pane.getScene().getWidth(), pane.prefWidth(-1));
            if (width + frameW > stage.getWidth()) {
                stage.setWidth(Math.min(width + frameW, screen.getWidth() * 0.9));
            }
            double wanted = pane.prefHeight(width) + frameH;
            if (wanted > stage.getHeight()) {
                stage.setHeight(Math.min(wanted, screen.getHeight() * 0.9));
            }
        });
    }

    /**
     * Release notes are Markdown; shows them as readable plain text: no image or HTML tags, links as their text,
     * no heading or emphasis marks, table rows as "cell: cell", and list items as bullets.
     */
    static String plainNotes(String markdown) {
        List<String> out = new ArrayList<>();
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (TABLE_RULE.matcher(line).matches()) {
                continue;
            }
            if (line.startsWith("|") && i + 1 < lines.length && TABLE_RULE.matcher(lines[i + 1].strip()).matches()) {
                continue; // a table's header row
            }
            line = HTML_TAG.matcher(line).replaceAll("");
            line = IMAGE.matcher(line).replaceAll("");
            line = LINK.matcher(line).replaceAll("$1");
            line = EMPHASIS.matcher(line).replaceAll("$2");
            line = line.replace("`", "");
            line = line.replaceFirst("^#{1,6}\\s+", "").replaceFirst("^>\\s?", "");
            if (line.startsWith("|")) {
                String[] cells = line.replaceAll("^\\||\\|$", "").split("\\|");
                List<String> parts = new ArrayList<>();
                for (String c : cells) {
                    if (!c.isBlank()) {
                        parts.add(c.strip());
                    }
                }
                line = String.join(": ", parts);
            }
            line = line.replaceFirst("^[-*+]\\s+", "\u2022 ");
            out.add(line.strip());
        }
        return String.join("\n", out).replaceAll("\n{3,}", "\n\n").strip();
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
