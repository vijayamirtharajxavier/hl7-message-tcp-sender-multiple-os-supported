package io.hl7sender.app;

import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.secrets.SecretStore;
import io.hl7sender.core.secrets.SecretStoreException;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Where secrets are kept, whether the queue database is encrypted at rest, and the recovery key
 * that opens it if the keychain entry is ever lost.
 */
final class SecurityDialog extends Dialog<ButtonType> {

    private static final String HEX_KEY = "[0-9a-fA-F]{64}";

    private final AppContext context;
    private final CheckBox encrypt = new CheckBox("Encrypt the queue database (AES-256, applied at next start)");
    private final TextField recoveryKey = new TextField();
    private final TextField restoreKey = new TextField();
    private final Label message = new Label();

    SecurityDialog(Window owner, AppContext context) {
        this.context = context;
        initOwner(owner);
        setTitle("Security");
        setHeaderText("Passwords, queue encryption and the recovery key");
        SecretStore secrets = context.secrets();

        Label store = new Label("Passwords and keys are stored in the " + secrets.description() + ".");
        store.setId("securitySecretStoreLabel");
        store.setWrapText(true);
        Label storeWarning = new Label("No operating-system keychain is available, so secrets are encrypted in a "
                + "file in the settings folder with a key kept next to it. This hides them from casual reading, "
                + "but not from anyone who can read your user account's files.");
        storeWarning.getStyleClass().add("issue-warning");
        storeWarning.setWrapText(true);
        storeWarning.setVisible(!secrets.isOsKeychain());
        storeWarning.setManaged(!secrets.isOsKeychain());

        boolean wanted = context.settings().security().encryptQueue();
        encrypt.setId("securityEncryptBox");
        encrypt.setSelected(wanted);
        Label queueState = new Label(queueState(wanted));
        queueState.setId("securityQueueStateLabel");
        queueState.setWrapText(true);
        encrypt.selectedProperty().addListener((o, a, b) -> queueState.setText(queueState(b)));

        recoveryKey.setId("securityRecoveryKeyField");
        recoveryKey.setEditable(false);
        recoveryKey.setPromptText("hidden");
        recoveryKey.setPrefColumnCount(40);
        Button show = new Button("Show");
        show.setId("securityShowKeyButton");
        show.setOnAction(e -> recoveryKey.setText(context.databaseKey().orElse("")));
        Button copy = new Button("Copy");
        copy.setId("securityCopyKeyButton");
        copy.setOnAction(e -> context.databaseKey().ifPresent(k -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(k);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
            message.setText("Recovery key copied. Paste it into a password manager, then clear the clipboard.");
        }));
        boolean hasKey = context.databaseKey().isPresent();
        show.setDisable(!hasKey);
        copy.setDisable(!hasKey);
        for (Button b : new Button[] {show, copy}) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox keyRow = new HBox(6, recoveryKey, show, copy);
        HBox.setHgrow(recoveryKey, Priority.ALWAYS);
        Label keyNote = new Label(hasKey
                ? "Anyone with this key and queue.db can read the queued messages. Keep it in a password manager: "
                        + "if the keychain entry is lost, it is the only way to open the encrypted queue."
                : "No key yet. One is created when encryption is first applied.");
        keyNote.setWrapText(true);
        keyNote.getStyleClass().add("field-label");

        restoreKey.setId("securityRestoreKeyField");
        restoreKey.setPromptText("64 hexadecimal characters");
        restoreKey.setPrefColumnCount(40);
        Button restore = new Button("Restore key");
        restore.setId("securityRestoreKeyButton");
        restore.setOnAction(e -> restore());
        restore.setMinWidth(Region.USE_PREF_SIZE);
        HBox restoreRow = new HBox(6, restoreKey, restore);
        HBox.setHgrow(restoreKey, Priority.ALWAYS);

        message.setId("securityMessageLabel");
        message.setWrapText(true);

        VBox content = new VBox(8,
                Styles.sectionTitle("Secret storage"), store, storeWarning,
                Styles.sectionTitle("Queue database"), encrypt, queueState,
                Styles.sectionTitle("Recovery key"), keyRow, keyNote,
                Styles.sectionTitle("Restore a recovery key"), restoreRow, message);
        content.setPadding(new Insets(4));
        content.setPrefWidth(620);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("securityOkButton");
        ok.addEventFilter(ActionEvent.ACTION, e -> apply());
    }

    private String queueState(boolean wanted) {
        boolean now = context.queueEncrypted();
        String state = now ? "The queue database is encrypted." : "The queue database is not encrypted.";
        if (context.engine().isEmpty()) {
            state = "The queue is not open in this window.";
        }
        if (wanted != now && context.engine().isPresent()) {
            state += wanted ? " It will be encrypted the next time the app starts."
                    : " It will be decrypted the next time the app starts.";
        }
        return state;
    }

    private void apply() {
        boolean wanted = encrypt.isSelected();
        context.updateSettings(s -> s.withSecurity(new AppSettings.Security(wanted)));
    }

    private void restore() {
        String key = restoreKey.getText() == null ? "" : restoreKey.getText().trim();
        if (!key.matches(HEX_KEY)) {
            message.setText("A recovery key is 64 hexadecimal characters (0-9, a-f).");
            return;
        }
        if (context.engine().isPresent() && context.databaseKey().isPresent()) {
            message.setText("The queue is open and already has its key; there is nothing to restore.");
            return;
        }
        try {
            context.secrets().put(AppContext.DB_KEY, key.toLowerCase(java.util.Locale.ROOT));
            restoreKey.clear();
            message.setText("Key saved in the " + context.secrets().description()
                    + ". Restart the app to open the encrypted queue.");
        } catch (SecretStoreException e) {
            message.setText("Could not save the key: " + e.getMessage());
        }
    }
}
