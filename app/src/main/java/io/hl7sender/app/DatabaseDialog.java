package io.hl7sender.app;

import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.runtime.QueueOpener;
import io.hl7sender.core.secrets.SecretStoreException;
import java.time.Clock;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Chooses the queue database: the local SQLite file, or a PostgreSQL server shared by several computers. The
 * PostgreSQL password goes to the OS keychain. The change applies when the app is restarted.
 */
final class DatabaseDialog extends Dialog<ButtonType> {

    private final AppContext context;
    private final RadioButton local = new RadioButton(Messages.get("database.local"));
    private final RadioButton shared = new RadioButton(Messages.get("database.shared"));
    private final TextField url = new TextField();
    private final TextField user = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label state = new Label();
    private boolean changed;

    DatabaseDialog(Window owner, AppContext context) {
        this.context = context;
        initOwner(owner);
        setTitle(Messages.get("database.title"));
        setHeaderText(Messages.get("database.header"));
        AppSettings.Database db = context.settings().database();
        ToggleGroup type = new ToggleGroup();
        local.setToggleGroup(type);
        local.setId("databaseLocalRadio");
        shared.setToggleGroup(type);
        shared.setId("databaseSharedRadio");
        (db.postgres() ? shared : local).setSelected(true);
        url.setId("databaseUrlField");
        url.setText(db.url());
        url.setPromptText("jdbc:postgresql://db.example.org:5432/hl7sender");
        url.setPrefColumnCount(36);
        url.setAccessibleText(Messages.get("database.url"));
        user.setId("databaseUserField");
        user.setText(db.username());
        user.setAccessibleText(Messages.get("database.user"));
        password.setId("databasePasswordField");
        password.setPromptText(context.secrets().get(QueueOpener.POSTGRES_PASSWORD).isPresent()
                ? Messages.get("database.password.kept") : "");
        password.setAccessibleText(Messages.get("database.password"));
        state.setId("databaseStateLabel");
        state.setWrapText(true);
        Button test = new Button(Messages.get("database.test"));
        test.setId("databaseTestButton");
        test.setOnAction(e -> testConnection());
        for (var c : new javafx.scene.Node[] {url, user, password, test}) {
            c.disableProperty().bind(shared.selectedProperty().not());
        }

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-pane");
        grid.addRow(0, Fields.label(Messages.get("database.url")), url);
        grid.addRow(1, Fields.label(Messages.get("database.user")), user);
        grid.addRow(2, Fields.label(Messages.get("database.password")), password);
        grid.add(test, 1, 3);
        Label current = new Label(Messages.get("database.current", context.queueLocation().isEmpty()
                ? Messages.get("database.current.none") : context.queueLocation()
                + (context.delivering() ? "" : " " + Messages.get("database.current.notDelivering"))));
        current.setId("databaseCurrentLabel");
        current.setWrapText(true);
        Label note = new Label(Messages.get("database.note"));
        note.setWrapText(true);
        note.getStyleClass().add("field-label");
        for (Label l : new Label[] {current, state, note}) {
            // Wrap rather than cut long text short.
            l.setMinHeight(Region.USE_PREF_SIZE);
        }
        VBox content = new VBox(8, current, local, shared, grid, state, note);
        content.setPadding(new Insets(4));
        content.setPrefWidth(620);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("databaseOkButton");
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            if (!save()) {
                e.consume();
            }
        });
    }

    /** The password typed here, or the stored one if the field was left empty. */
    private String effectivePassword() {
        return password.getText().isEmpty() ? context.secrets().get(QueueOpener.POSTGRES_PASSWORD).orElse(null)
                : password.getText();
    }

    private boolean validUrl() {
        if (!url.getText().trim().startsWith("jdbc:postgresql://")) {
            state.setText(Messages.get("database.badUrl"));
            return false;
        }
        return true;
    }

    private void testConnection() {
        if (!validUrl()) {
            return;
        }
        String u = url.getText().trim();
        String name = user.getText().trim();
        String pw = effectivePassword();
        state.setText(Messages.get("database.testing"));
        context.executor().execute(() -> {
            String result;
            try (QueueStore store = QueueStore.openPostgres(u, name, pw, Clock.systemUTC())) {
                result = Messages.get("database.ok", store.destinations().size());
            } catch (QueueException ex) {
                result = ex.getMessage();
            }
            String shown = result;
            Platform.runLater(() -> state.setText(shown));
        });
    }

    private boolean save() {
        AppSettings.Database db;
        try {
            if (shared.isSelected()) {
                if (!validUrl()) {
                    return false;
                }
                if (!password.getText().isEmpty()) {
                    context.secrets().put(QueueOpener.POSTGRES_PASSWORD, password.getText());
                }
                db = AppSettings.Database.postgres(url.getText().trim(), user.getText().trim());
            } else {
                context.secrets().delete(QueueOpener.POSTGRES_PASSWORD);
                db = AppSettings.Database.defaults();
            }
        } catch (SecretStoreException ex) {
            state.setText(ex.getMessage());
            return false;
        }
        changed = !db.equals(context.settings().database()) || !password.getText().isEmpty();
        AppSettings.Database chosen = db;
        context.updateSettings(s -> s.withDatabase(chosen));
        return true;
    }

    /** True if OK saved a different database (or a new password), which applies after a restart. */
    boolean changed() {
        return changed;
    }
}
