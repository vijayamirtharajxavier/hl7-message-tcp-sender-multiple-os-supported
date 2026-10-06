package io.hl7sender.app;

import io.hl7sender.core.AppInfo;
import io.hl7sender.core.alert.AlertMonitor;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.diagnostics.DiagnosticsBundle;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationState;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.update.UpdateChecker;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Top-level layout: menu bar, the Sender, Queue, Dashboard, History, Test Listener and Logs tabs, status bar. */
final class MainWindow extends BorderPane {

    private static final Logger LOG = LoggerFactory.getLogger(MainWindow.class);

    /** Keyboard shortcuts, as shown in Help > Keyboard shortcuts (key text, message key of the action). */
    static final List<String[]> SHORTCUTS = List.of(
            new String[] {"Ctrl+Enter", "shortcut.send"},
            new String[] {"Ctrl+O", "shortcut.open"},
            new String[] {"Ctrl+I", "shortcut.import"},
            new String[] {"Ctrl+1 ... Ctrl+7", "shortcut.tabs"},
            new String[] {"F5", "shortcut.refresh"},
            new String[] {"F1", "shortcut.help"},
            new String[] {"Ctrl+Q", "shortcut.exit"},
            new String[] {"Alt+F / Alt+V / Alt+T / Alt+H", "shortcut.menus"},
            new String[] {"Tab / Shift+Tab", "shortcut.focus"},
            new String[] {"Ctrl+Tab", "shortcut.nextTab"});

    private final AppContext context;
    private final HostServices hostServices;
    private final Label statusLabel = new Label(Messages.get("status.ready"));
    private final SenderPane senderPane;
    private final ListenerPane listenerPane;
    private final QueuePane queuePane;
    private final HistoryPane historyPane;
    private final DashboardPane dashboardPane;
    private final LogsPane logsPane;
    private final LoadTestPane loadTestPane;
    private final TabPane tabs;
    private final Consumer<AlertMonitor.Raised> alertListener;
    private Notifier notifier;

    MainWindow(AppContext context, HostServices hostServices) {
        this.context = context;
        this.hostServices = hostServices;
        this.senderPane = new SenderPane(context, this::setStatus);
        this.listenerPane = new ListenerPane(context, this::setStatus);
        this.queuePane = new QueuePane(context, this::setStatus);
        this.historyPane = new HistoryPane(context, this::setStatus);
        this.dashboardPane = new DashboardPane(context);
        this.logsPane = new LogsPane(context, this::openLogFolder, this::chooseDiagnosticsFile);
        this.loadTestPane = new LoadTestPane(context, this::setStatus, senderPane::editorText);

        Tab senderTab = tab("senderTab", "tab.sender", senderPane);
        Tab queueTab = tab("queueTab", "tab.queue", queuePane);
        Tab dashboardTab = tab("dashboardTab", "tab.dashboard", dashboardPane);
        dashboardTab.setOnSelectionChanged(e -> {
            if (dashboardTab.isSelected()) {
                dashboardPane.activate();
            } else {
                dashboardPane.deactivate();
            }
        });
        Tab historyTab = tab("historyTab", "tab.history", historyPane);
        historyTab.setOnSelectionChanged(e -> {
            if (historyTab.isSelected()) {
                historyPane.search();
            }
        });
        Tab listenerTab = tab("listenerTab", "tab.listener", listenerPane);
        Tab logsTab = tab("logsTab", "tab.logs", logsPane);
        Tab loadTab = tab("loadTab", "tab.load", loadTestPane);
        loadTab.setOnSelectionChanged(e -> {
            if (loadTab.isSelected() && !loadTestPane.isRunning()) {
                loadTestPane.refreshTargets();
            }
        });
        tabs = new TabPane(senderTab, queueTab, dashboardTab, historyTab, listenerTab, logsTab, loadTab);
        tabs.setId("mainTabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        statusLabel.setId("statusLabel");
        statusLabel.setAccessibleHelp(Messages.get("status.accessible"));
        Label userLabel = new Label(context.access().user().map(u -> Messages.get("signin.as", u.label(),
                Messages.get("users.role." + u.role().name().toLowerCase(java.util.Locale.ROOT)))).orElse(""));
        userLabel.setId("signedInLabel");
        Region statusSpacer = new Region();
        HBox.setHgrow(statusSpacer, Priority.ALWAYS);
        HBox statusBar = new HBox(statusLabel, statusSpacer, userLabel);
        statusBar.getStyleClass().add("status-bar");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        setTop(buildMenu());
        setCenter(tabs);
        setBottom(statusBar);
        applyAccess();
        alertListener = r -> Platform.runLater(() -> onAlert(r));
        context.alerts().ifPresent(a -> a.addListener(alertListener));
        checkCertificatesInBackground();
    }

    /** Controls hidden from users whose role lacks the permission, by node or menu item ID. */
    private static final Map<String, Permission> GATED = Map.ofEntries(
            Map.entry("sendButton", Permission.SEND), Map.entry("enqueueButton", Permission.SEND),
            Map.entry("importButton", Permission.SEND), Map.entry("importMenuItem", Permission.SEND),
            Map.entry("historyReplay", Permission.SEND), Map.entry("loadStartButton", Permission.SEND),
            Map.entry("pauseButton", Permission.MANAGE_QUEUE), Map.entry("retryNowButton", Permission.MANAGE_QUEUE),
            Map.entry("moveToDeadLetterButton", Permission.MANAGE_QUEUE),
            Map.entry("deletePendingButton", Permission.MANAGE_QUEUE),
            Map.entry("resendButton", Permission.MANAGE_QUEUE),
            Map.entry("requeueButton", Permission.MANAGE_QUEUE),
            Map.entry("editDeadLetterButton", Permission.MANAGE_QUEUE),
            Map.entry("deleteDeadLetterButton", Permission.MANAGE_QUEUE),
            Map.entry("addDestinationButton", Permission.CONFIGURE),
            Map.entry("editDestinationButton", Permission.CONFIGURE),
            Map.entry("deleteDestinationButton", Permission.CONFIGURE),
            Map.entry("importDestinationsItem", Permission.CONFIGURE),
            Map.entry("schedulesMenuItem", Permission.CONFIGURE), Map.entry("alertsMenuItem", Permission.CONFIGURE),
            Map.entry("apiMenuItem", Permission.CONFIGURE), Map.entry("databaseMenuItem", Permission.CONFIGURE),
            Map.entry("wizardMenuItem", Permission.CONFIGURE), Map.entry("securityMenuItem", Permission.CONFIGURE),
            Map.entry("usersMenuItem", Permission.ADMIN_USERS));

    /** Hides what the signed-in user may not do. Nothing is hidden while the queue has no users. */
    private void applyAccess() {
        java.util.function.Predicate<String> hidden = id -> id != null && GATED.containsKey(id)
                && !context.can(GATED.get(id));
        for (javafx.scene.Node pane : List.of(senderPane, queuePane, historyPane, loadTestPane)) {
            walk(pane, n -> {
                if (hidden.test(n.getId())) {
                    n.setVisible(false);
                    n.setManaged(false);
                }
                if (n instanceof javafx.scene.control.MenuButton mb) {
                    mb.getItems().forEach(i -> i.setVisible(!hidden.test(i.getId())));
                }
            });
        }
        for (Menu m : ((MenuBar) getTop()).getMenus()) {
            m.getItems().forEach(i -> {
                if (hidden.test(i.getId())) {
                    i.setVisible(false);
                }
            });
        }
    }

    /** Visits every node, including the contents of tabs, split panes and scroll panes before they are shown. */
    private static void walk(javafx.scene.Node n, java.util.function.Consumer<javafx.scene.Node> visit) {
        visit.accept(n);
        if (n instanceof TabPane t) {
            t.getTabs().stream().map(Tab::getContent).filter(java.util.Objects::nonNull).forEach(c -> walk(c, visit));
        } else if (n instanceof javafx.scene.control.SplitPane s) {
            s.getItems().forEach(c -> walk(c, visit));
        } else if (n instanceof javafx.scene.control.ScrollPane s && s.getContent() != null) {
            walk(s.getContent(), visit);
        } else if (n instanceof javafx.scene.control.TitledPane t && t.getContent() != null) {
            walk(t.getContent(), visit);
        } else if (n instanceof javafx.scene.Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> walk(c, visit));
        }
    }

    private static Tab tab(String id, String key, javafx.scene.Node content) {
        Tab t = new Tab(Messages.get(key), content);
        t.setId(id);
        return t;
    }

    /** At startup, reports certificates that have expired or expire soon for any TLS destination. */
    private void checkCertificatesInBackground() {
        context.engine().ifPresent(engine -> context.executor().submit(() -> {
            List<String> warnings = new ArrayList<>();
            for (DestinationConfig d : engine.destinations()) {
                engine.certificateWarnings(d).forEach(w -> warnings.add(d.name() + ": " + w));
            }
            if (!warnings.isEmpty()) {
                warnings.forEach(w -> LOG.warn("Certificate: {}", w));
                Platform.runLater(() -> {
                    statusLabel.getStyleClass().add("issue-warning");
                    setStatus("Certificate warning - " + warnings.get(0)
                            + (warnings.size() > 1 ? " (+" + (warnings.size() - 1) + " more, see the Queue tab)" : ""));
                });
            }
        }));
    }

    void shutdown() {
        senderPane.saveSettings();
        senderPane.shutdown();
        queuePane.shutdown();
        listenerPane.shutdown();
        dashboardPane.shutdown();
        logsPane.shutdown();
        loadTestPane.shutdown();
        if (notifier != null) {
            notifier.close();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Alerts

    private void onAlert(AlertMonitor.Raised r) {
        setStatus(Messages.get("status.alert", r.alert().title()));
        if (context.settings().alerts().desktop()) {
            notifier().show(r.alert());
        }
    }

    Notifier notifier() {
        if (notifier == null) {
            notifier = new Notifier(getScene() == null ? null : getScene().getWindow());
        }
        return notifier;
    }

    // ---------------------------------------------------------------------------------------------
    // Menus

    private MenuBar buildMenu() {
        MenuItem open = item("menu.file.open", e -> senderPane.openFile());
        open.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        MenuItem importItem = item("menu.file.import", e -> senderPane.openImportFromMenu());
        importItem.setId("importMenuItem");
        importItem.setAccelerator(new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN));
        MenuItem exit = item("menu.file.exit", e -> Platform.exit());
        exit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        Menu file = new Menu(Messages.get("menu.file"), null, open, importItem, new SeparatorMenuItem(), exit);

        Menu view = new Menu(Messages.get("menu.view"));
        view.setId("viewMenu");
        String[] tabKeys = {"tab.sender", "tab.queue", "tab.dashboard", "tab.history", "tab.listener", "tab.logs",
            "tab.load"};
        KeyCode[] digits = {KeyCode.DIGIT1, KeyCode.DIGIT2, KeyCode.DIGIT3, KeyCode.DIGIT4, KeyCode.DIGIT5,
            KeyCode.DIGIT6, KeyCode.DIGIT7};
        for (int i = 0; i < tabKeys.length; i++) {
            int index = i;
            MenuItem go = new MenuItem(Messages.get(tabKeys[i]));
            go.setAccelerator(new KeyCodeCombination(digits[i], KeyCombination.SHORTCUT_DOWN));
            go.setOnAction(e -> tabs.getSelectionModel().select(index));
            view.getItems().add(go);
        }
        MenuItem refresh = item("menu.view.refresh", e -> refreshCurrentTab());
        refresh.setAccelerator(new KeyCodeCombination(KeyCode.F5));
        view.getItems().addAll(refresh, new SeparatorMenuItem(), themeMenu(), languageMenu());

        MenuItem alerts = item("menu.tools.alerts", e -> new AlertSettingsDialog(window(), context).showAndWait());
        alerts.setId("alertsMenuItem");
        MenuItem wizard = item("menu.tools.wizard", e -> showWelcomeWizard());
        wizard.setId("wizardMenuItem");
        wizard.setDisable(context.engine().isEmpty());
        MenuItem diagnostics = item("menu.tools.diagnostics", e -> chooseDiagnosticsFile());
        diagnostics.setId("diagnosticsMenuItem");
        MenuItem api = item("menu.tools.api", e -> new ApiDialog(window(), context).showAndWait());
        api.setId("apiMenuItem");
        MenuItem schedules = item("menu.tools.schedules", e -> new SchedulesDialog(window(), context,
                senderPane::editorText, this::setStatus).showAndWait());
        schedules.setId("schedulesMenuItem");
        schedules.setDisable(context.engine().isEmpty());
        MenuItem database = item("menu.tools.database", e -> {
            DatabaseDialog d = new DatabaseDialog(window(), context);
            d.showAndWait();
            if (d.changed()) {
                setStatus(Messages.get("database.restart"));
            }
        });
        database.setId("databaseMenuItem");
        MenuItem users = item("menu.tools.users", e -> context.engine().ifPresentOrElse(
                engine -> new UsersDialog(window(), engine.store()).showAndWait(),
                () -> setStatus(context.queueUnavailableReason())));
        users.setId("usersMenuItem");
        users.setDisable(context.engine().isEmpty());
        Menu tools = new Menu(Messages.get("menu.tools"), null, schedules, alerts, api, database, users, wizard,
                diagnostics);

        MenuItem shortcuts = item("menu.help.shortcuts", e -> showShortcuts());
        shortcuts.setId("shortcutsMenuItem");
        shortcuts.setAccelerator(new KeyCodeCombination(KeyCode.F1));
        MenuItem logs = item("menu.help.logs", e -> openLogFolder());
        MenuItem security = item("menu.help.security", e -> new SecurityDialog(window(), context).showAndWait());
        security.setId("securityMenuItem");
        MenuItem updates = item("menu.help.updates", e -> new UpdateDialog(window(), context, hostServices,
                UpdateChecker.forThisBuild()).show());
        updates.setId("updatesMenuItem");
        MenuItem about = item("menu.help.about", e -> showAbout());
        Menu help = new Menu(Messages.get("menu.help"), null, shortcuts, security, logs, new SeparatorMenuItem(),
                updates, about);
        return new MenuBar(file, view, tools, help);
    }

    private static MenuItem item(String key, javafx.event.EventHandler<javafx.event.ActionEvent> action) {
        MenuItem m = new MenuItem(Messages.get(key));
        m.setOnAction(action);
        return m;
    }

    private Menu themeMenu() {
        ToggleGroup group = new ToggleGroup();
        Menu menu = new Menu(Messages.get("menu.view.theme"));
        for (AppSettings.Ui.Theme t : AppSettings.Ui.Theme.values()) {
            RadioMenuItem item = new RadioMenuItem(Messages.get("theme." + t.name()));
            item.setId("theme" + t.name());
            item.setToggleGroup(group);
            item.setSelected(context.settings().ui().theme() == t);
            item.setOnAction(e -> setTheme(t));
            menu.getItems().add(item);
        }
        return menu;
    }

    private Menu languageMenu() {
        ToggleGroup group = new ToggleGroup();
        Menu menu = new Menu(Messages.get("menu.view.language"));
        String current = context.settings().ui().language();
        for (String tag : List.of("", "en", "es")) {
            RadioMenuItem item = new RadioMenuItem(tag.isEmpty() ? Messages.get("language.system")
                    : Messages.get("language." + tag));
            item.setToggleGroup(group);
            item.setSelected(current.equals(tag));
            item.setOnAction(e -> {
                context.updateSettings(s -> s.withUi(new AppSettings.Ui(s.ui().theme(), tag, s.ui().firstRunDone())));
                setStatus(Messages.get("status.languageRestart"));
            });
            menu.getItems().add(item);
        }
        return menu;
    }

    /** Switches the colour theme now and remembers it. */
    void setTheme(AppSettings.Ui.Theme theme) {
        Styles.setTheme(theme);
        context.updateSettings(s -> s.withUi(s.ui().withTheme(theme)));
    }

    private void refreshCurrentTab() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        switch (t.getId()) {
            case "queueTab" -> queuePane.refreshNow();
            case "historyTab" -> historyPane.search();
            case "dashboardTab" -> dashboardPane.refresh();
            default -> {
            }
        }
    }

    private void showShortcuts() {
        TableView<String[]> table = new TableView<>(FXCollections.observableArrayList(SHORTCUTS));
        table.setId("shortcutsTable");
        TableColumn<String[], String> keys = new TableColumn<>(Messages.get("shortcut.keys"));
        keys.setCellValueFactory(d -> new ReadOnlyStringWrapper(d.getValue()[0]));
        keys.setPrefWidth(230);
        TableColumn<String[], String> action = new TableColumn<>(Messages.get("shortcut.action"));
        action.setCellValueFactory(d -> new ReadOnlyStringWrapper(Messages.get(d.getValue()[1])));
        action.setPrefWidth(330);
        table.getColumns().add(keys);
        table.getColumns().add(action);
        table.setPrefHeight(320);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(window());
        dialog.setTitle(Messages.get("menu.help.shortcuts"));
        dialog.getDialogPane().setContent(table);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(window());
        alert.setTitle(Messages.get("menu.help.about"));
        alert.setHeaderText(AppInfo.NAME + " " + AppInfo.version());
        alert.setContentText(Messages.get("about.text") + "\n" + Messages.get("about.license") + "\n\n"
                + "Java " + System.getProperty("java.version") + ", JavaFX "
                + System.getProperty("javafx.runtime.version", "?") + "\n"
                + "Settings: " + context.paths().settingsFile() + "\n"
                + "Logs: " + context.paths().logDir());
        alert.showAndWait();
    }

    private void openLogFolder() {
        if (hostServices == null) {
            setStatus(context.paths().logDir().toString());
            return;
        }
        hostServices.showDocument(context.paths().logDir().toUri().toString());
    }

    // ---------------------------------------------------------------------------------------------
    // Welcome wizard

    /** Starts the once-a-day update check, if the user turned it on (Help > Check for updates). */
    void checkForUpdatesInBackground(UpdateChecker checker) {
        UpdateDialog.checkInBackground(context, checker, r -> Platform.runLater(() -> {
            String text = Messages.get("update.notice", r.latest().version());
            setStatus(text);
            notifier().show(new io.hl7sender.core.alert.Alert(io.hl7sender.core.alert.Alert.Kind.UPDATE,
                    io.hl7sender.core.alert.Alert.Severity.INFO, 0, "", text, Messages.get("update.notice.detail"),
                    java.time.Instant.now()));
        }));
    }

    /** Shows the welcome wizard on a new installation (no destinations yet, wizard never completed). */
    void showWelcomeWizardIfFirstRun() {
        boolean fresh = context.engine().map(e -> e.destinations().isEmpty()).orElse(false);
        if (fresh && !context.settings().ui().firstRunDone() && context.can(Permission.CONFIGURE)) {
            showWelcomeWizard();
        }
    }

    void showWelcomeWizard() {
        if (context.engine().isEmpty()) {
            return;
        }
        FirstRunWizard wizard = new FirstRunWizard(window(), context, listenerPane::startLocal);
        wizard.showAndWait().ifPresent(d -> {
            queuePane.refreshNow();
            tabs.getSelectionModel().select(tabs.getTabs().stream().filter(t -> "dashboardTab".equals(t.getId()))
                    .findFirst().orElseThrow());
            setStatus(Messages.get("wizard.done", d.name()));
        });
        context.updateSettings(s -> s.withUi(s.ui().withFirstRunDone(true)));
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics

    private void chooseDiagnosticsFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("menu.tools.diagnostics"));
        chooser.setInitialFileName("hl7-sender-diagnostics-" + LocalDate.now() + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip", "*.zip"));
        File file = chooser.showSaveDialog(window());
        if (file == null) {
            return;
        }
        setStatus(Messages.get("diagnostics.writing"));
        context.executor().submit(() -> {
            try {
                List<String> entries = writeDiagnostics(file.toPath());
                Platform.runLater(() -> setStatus(Messages.get("diagnostics.done", file.getName(), entries.size())));
            } catch (IOException | RuntimeException e) {
                LOG.warn("Diagnostics export failed: {}", e.getMessage());
                Platform.runLater(() -> setStatus(Messages.get("diagnostics.failed", e.getMessage())));
            }
        });
    }

    /** Writes the diagnostics bundle (no secrets, no message content) and returns its entries. */
    List<String> writeDiagnostics(Path zip) throws IOException {
        List<DestinationConfig> destinations = new ArrayList<>();
        Map<Long, DestinationState> states = new HashMap<>();
        Map<Long, Map<MessageStatus, Integer>> counts = new HashMap<>();
        DeliveryEngine engine = context.engine().orElse(null);
        if (engine != null) {
            for (DestinationConfig d : engine.destinations()) {
                destinations.add(d);
                states.put(d.id(), engine.state(d.id()));
                counts.put(d.id(), engine.store().counts(d.id()));
            }
        }
        List<String> alerts = context.alerts().map(a -> a.recent().stream()
                .map(r -> r.alert().at() + " " + r.alert().summary()
                        + (r.errors().isEmpty() ? "" : " (" + String.join("; ", r.errors()) + ")"))
                .toList()).orElse(List.of());
        return DiagnosticsBundle.write(new DiagnosticsBundle.Input(context.paths(), context.settings(), destinations,
                states, counts, context.secrets().description(), context.queueEncrypted(), alerts), zip,
                Clock.systemUTC());
    }

    // ---------------------------------------------------------------------------------------------

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void setStatus(String text) {
        if (!text.startsWith("Certificate warning")) {
            statusLabel.getStyleClass().remove("issue-warning");
        }
        statusLabel.setText(text);
    }
}
