package io.hl7sender.app;

import io.hl7sender.core.AppInfo;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.update.UpdateChecker;
import io.hl7sender.core.update.UpdateInstaller;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.application.HostServices;
import javafx.concurrent.Task;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Checks GitHub Releases for a newer version, on request, and shows it with its notes. Install now downloads it,
 * checks it against the release's checksums, and installs it when the app closes (restarting it, or the next time
 * the user closes it). Also holds the "check automatically" and "install automatically" preferences.
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
    private final CheckBox installAutomatic = new CheckBox(Messages.get("update.installAutomatic"));
    private final Button install = new Button(Messages.get("update.install"));
    private final ProgressBar progress = new ProgressBar(0);
    private final Label installNote = new Label();
    private UpdateChecker.Result last;
    private Optional<UpdateInstaller> installer = Optional.empty();
    /** Closes the app; replaced in tests. */
    private Runnable exitApp = Platform::exit;

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
                context.updateSettings(s -> s.withUpdates(s.updates().withSkippedVersion(v)));
                result.setText(Messages.get("update.skipped", v));
            }
        });
        install.setId("updateInstallButton");
        install.setVisible(false);
        install.managedProperty().bind(install.visibleProperty());
        install.setDefaultButton(true);
        install.setOnAction(e -> download());
        progress.setId("updateProgress");
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        installNote.setId("updateInstallNote");
        installNote.setWrapText(true);
        installNote.setMinHeight(Region.USE_PREF_SIZE);
        installNote.setVisible(false);
        installNote.managedProperty().bind(installNote.visibleProperty());
        automatic.setId("updateAutomaticBox");
        automatic.setSelected(context.settings().updates().checkAutomatically());
        automatic.selectedProperty().addListener((o, a, on) -> {
            context.updateSettings(s -> s.withUpdates(s.updates().withCheckAutomatically(on)));
            if (!on) {
                installAutomatic.setSelected(false);
            }
        });
        installAutomatic.setId("updateInstallAutomaticBox");
        installAutomatic.setSelected(context.settings().updates().installAutomatically());
        installAutomatic.setDisable(true);
        installAutomatic.selectedProperty().addListener((o, a, on) -> {
            context.updateSettings(s -> s.withUpdates(s.updates().withInstallAutomatically(on)));
            if (on) {
                automatic.setSelected(true);
            } else if (context.pendingUpdate().filter(p -> !p.relaunch()).isPresent()) {
                context.cancelInstallOnExit();
            }
        });
        Label privacy = new Label(Messages.get("update.privacy"));
        privacy.setWrapText(true);
        privacy.setMinHeight(Region.USE_PREF_SIZE);
        privacy.getStyleClass().add("field-label");

        VBox content = new VBox(8, result, notes, progress, installNote, new HBox(8, install, open, skip), automatic,
                installAutomatic, privacy);
        content.setPadding(new Insets(4));
        content.setPrefWidth(560);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        check(checker);
    }

    /** For tests: what closes the app when the user chooses Install and restart. */
    void setExitApp(Runnable exitApp) {
        this.exitApp = exitApp;
    }

    private void check(UpdateChecker checker) {
        result.setText(Messages.get("update.checking"));
        Task<UpdateChecker.Result> task = new Task<>() {
            @Override
            protected UpdateChecker.Result call() throws Exception {
                // Works out how this copy was installed off the UI thread: it may run dpkg-query or rpm.
                Optional<UpdateInstaller> i = context.updateInstaller();
                Platform.runLater(() -> {
                    installer = i;
                    installAutomatic.setDisable(i.isEmpty());
                });
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
        if (installer.isPresent() && installer.get().canInstall(r.latest())) {
            install.setVisible(true);
        } else {
            String why = installer.isPresent() ? Messages.get("update.unsupported.NO_ASSET")
                    : context.updateDetection().unsupported()
                            .map(u -> Messages.get("update.unsupported." + u.name())).orElse("");
            installNote.setText(Messages.get("update.install.unavailable", why));
            installNote.setVisible(true);
        }
        fitWindow();
    }

    /** Install now: downloads and verifies the update, then asks when to install it. */
    private void download() {
        UpdateChecker.Release release = last.latest();
        UpdateInstaller i = installer.orElseThrow();
        install.setDisable(true);
        progress.setVisible(true);
        installNote.setVisible(false);
        result.setText(Messages.get("update.installing", release.version()));
        Task<UpdateInstaller.Prepared> task = new Task<>() {
            @Override
            protected UpdateInstaller.Prepared call() throws Exception {
                return i.download(release, f -> updateProgress(f, 1));
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(e -> {
            progress.progressProperty().unbind();
            progress.setVisible(false);
            confirm(task.getValue());
        });
        task.setOnFailed(e -> {
            progress.progressProperty().unbind();
            progress.setVisible(false);
            install.setDisable(false);
            result.setText(Messages.get("update.install.failed", task.getException() == null ? "?"
                    : task.getException().getMessage()));
            fitWindow();
        });
        fitWindow();
        context.executor().submit(task);
    }

    /** Asks whether to install {@code update} now (closing and restarting the app) or when the app next closes. */
    private void confirm(UpdateInstaller.Prepared update) {
        ButtonType now = new ButtonType(Messages.get("update.ready.now"), ButtonBar.ButtonData.OK_DONE);
        ButtonType later = new ButtonType(Messages.get("update.ready.later"), ButtonBar.ButtonData.OTHER);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "", now, later, ButtonType.CANCEL);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setTitle(Messages.get("update.ready.title", update.version()));
        alert.setHeaderText(Messages.get("update.ready.header", update.version()));
        UpdateInstaller.Kind kind = update.installation().kind();
        boolean password = kind == UpdateInstaller.Kind.LINUX_DEB || kind == UpdateInstaller.Kind.LINUX_RPM
                || kind == UpdateInstaller.Kind.MAC_PKG;
        alert.setContentText(Messages.get("update.ready.content")
                + (password ? " " + Messages.get("update.ready.password") : ""));
        alert.getDialogPane().setId("updateReadyPane");
        alert.getDialogPane().getStylesheets().add(Styles.stylesheet());
        alert.setOnHidden(e -> {
            ButtonType choice = alert.getResult();
            if (choice == now) {
                context.installOnExit(update, true);
                close();
                exitApp.run();
            } else if (choice == later) {
                context.installOnExit(update, false);
                result.setText(Messages.get("update.ready.scheduled", update.version()));
                install.setVisible(false);
            } else {
                install.setDisable(false);
                result.setText(Messages.get("update.available", update.version(), last.current()));
            }
        });
        alert.show();
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
     * The automatic check: at most once a day, only if the user turned it on. When a version newer than the running
     * one, and not skipped, is available: with "install automatically" on (and an installation this copy can
     * update), downloads and verifies it, arranges to install it when the app closes, and calls {@code onReady};
     * otherwise calls {@code onNewer}. An update downloaded earlier but not installed yet (the app was killed, or
     * the install failed) is picked up again at once. The callbacks run on the context's executor thread.
     */
    static void checkInBackground(AppContext context, UpdateChecker checker, Consumer<UpdateChecker.Result> onNewer,
                                  Consumer<UpdateInstaller.Prepared> onReady) {
        AppSettings.Updates u = context.settings().updates();
        if (!u.checkAutomatically() && !u.installAutomatically()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean due = now - u.lastCheckedMillis() >= 24L * 60 * 60 * 1000;
        org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(UpdateDialog.class);
        context.executor().submit(() -> {
            Optional<UpdateInstaller> installer = u.installAutomatically() ? context.updateInstaller()
                    : Optional.empty();
            installer.ifPresent(i -> i.deleteInstalled(checker.currentVersion()));
            Optional<UpdateInstaller.Prepared> earlier = installer.flatMap(i -> i.pending(checker.currentVersion()))
                    .filter(p -> !p.version().equals(u.skippedVersion()));
            if (earlier.isPresent()) {
                context.installOnExit(earlier.get(), false);
                onReady.accept(earlier.get());
            }
            if (!due) {
                return;
            }
            try {
                UpdateChecker.Result r = checker.check();
                context.updateSettings(s -> s.withUpdates(s.updates().withLastChecked(now)));
                if (!r.newer() || r.latest().version().equals(context.settings().updates().skippedVersion())
                        || earlier.filter(p -> p.version().equals(r.latest().version())).isPresent()) {
                    return;
                }
                if (installer.isPresent() && installer.get().canInstall(r.latest())) {
                    try {
                        UpdateInstaller.Prepared p = installer.get().download(r.latest(), f -> { });
                        if (context.settings().updates().installAutomatically()) {
                            context.installOnExit(p, false);
                            onReady.accept(p);
                            return;
                        }
                    } catch (java.io.IOException e) {
                        log.warn("Could not download version {}: {}", r.latest().version(), e.getMessage());
                    }
                }
                onNewer.accept(r);
            } catch (java.io.IOException | RuntimeException e) {
                log.info("Update check failed: {}", e.getMessage());
            }
        });
    }
}
