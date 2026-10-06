package io.hl7sender.app;

import io.hl7sender.core.alert.AlertMonitor;
import io.hl7sender.core.alert.AlertSettings;
import io.hl7sender.core.alert.WebhookSink;
import io.hl7sender.core.secrets.SecretStoreException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** What to alert on, and the desktop, webhook and e-mail channels, with a button to send a test alert. */
final class AlertSettingsDialog extends Dialog<ButtonType> {

    private final AppContext context;
    private final CheckBox desktop = new CheckBox(Messages.get("alerts.desktop"));
    private final CheckBox deadLetter = new CheckBox(Messages.get("alerts.deadLetter"));
    private final TextField threshold;
    private final CheckBox circuit = new CheckBox(Messages.get("alerts.circuit"));
    private final CheckBox certificates = new CheckBox(Messages.get("alerts.certificates"));
    private final TextField webhook = new TextField();
    private final CheckBox emailEnabled = new CheckBox(Messages.get("alerts.email.enabled"));
    private final TextField smtpHost = new TextField();
    private final TextField smtpPort;
    private final ComboBox<AlertSettings.EmailSettings.Security> security =
            new ComboBox<>(FXCollections.observableArrayList(AlertSettings.EmailSettings.Security.values()));
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField from = new TextField();
    private final TextField to = new TextField();
    private final Label result = new Label();

    AlertSettingsDialog(Window owner, AppContext context) {
        this.context = context;
        initOwner(owner);
        setTitle(Messages.get("alerts.title"));
        setHeaderText(Messages.get("alerts.header"));
        AlertSettings s = context.settings().alerts();
        AlertSettings.EmailSettings e = s.email();

        desktop.setId("alertDesktopBox");
        desktop.setSelected(s.desktop());
        deadLetter.setId("alertDeadLetterBox");
        deadLetter.setSelected(s.deadLetter());
        threshold = Fields.integer("alertThresholdField", s.deadLetterThreshold(), 5, 4);
        threshold.setAccessibleText(Messages.get("alerts.threshold"));
        circuit.setId("alertCircuitBox");
        circuit.setSelected(s.circuitOpen());
        certificates.setId("alertCertificatesBox");
        certificates.setSelected(s.certificateExpiry());
        webhook.setId("alertWebhookField");
        webhook.setText(s.webhookUrl());
        webhook.setPromptText("https://hooks.slack.com/services/...");
        webhook.setPrefColumnCount(36);
        emailEnabled.setId("alertEmailBox");
        emailEnabled.setSelected(e.enabled());
        smtpHost.setId("alertSmtpHostField");
        smtpHost.setText(e.host());
        smtpPort = Fields.integer("alertSmtpPortField", e.port(), 5, 5);
        security.setId("alertSmtpSecurityBox");
        security.setValue(e.security());
        username.setId("alertSmtpUserField");
        username.setText(e.username());
        password.setId("alertSmtpPasswordField");
        boolean stored = context.secrets().get(AlertSettings.SMTP_PASSWORD_KEY).isPresent();
        password.setPromptText(stored ? Messages.get("alerts.password.stored") : "");
        from.setId("alertFromField");
        from.setText(e.from());
        to.setId("alertToField");
        to.setText(e.to());
        to.setPromptText("ops@example.org, oncall@example.org");
        result.setId("alertResultLabel");
        result.setWrapText(true);

        GridPane rules = new GridPane();
        rules.getStyleClass().add("form-pane");
        rules.addRow(0, deadLetter, Fields.label(Messages.get("alerts.threshold")), threshold);
        rules.addRow(1, circuit);
        rules.addRow(2, certificates);

        GridPane mail = new GridPane();
        mail.getStyleClass().add("form-pane");
        int r = 0;
        mail.addRow(r++, emailEnabled);
        mail.addRow(r++, Fields.label(Messages.get("alerts.smtp.host")), smtpHost,
                Fields.label(Messages.get("alerts.smtp.port")), smtpPort);
        mail.addRow(r++, Fields.label(Messages.get("alerts.smtp.security")), security);
        mail.addRow(r++, Fields.label(Messages.get("alerts.smtp.user")), username,
                Fields.label(Messages.get("alerts.smtp.password")), password);
        mail.addRow(r++, Fields.label(Messages.get("alerts.email.from")), from);
        mail.addRow(r++, Fields.label(Messages.get("alerts.email.to")), to);
        GridPane.setColumnSpan(to, 3);
        for (javafx.scene.Node n : List.of(smtpHost, smtpPort, security, username, password, from, to)) {
            n.disableProperty().bind(emailEnabled.selectedProperty().not());
        }

        Button test = new Button(Messages.get("alerts.test"));
        test.setId("alertTestButton");
        test.setOnAction(ev -> sendTest(test));
        Label phi = new Label(Messages.get("alerts.phi"));
        phi.getStyleClass().add("field-label");
        phi.setWrapText(true);

        VBox content = new VBox(8,
                Styles.sectionTitle(Messages.get("alerts.section.when")), rules,
                Styles.sectionTitle(Messages.get("alerts.section.desktop")), desktop,
                Styles.sectionTitle(Messages.get("alerts.section.webhook")), webhook,
                Styles.sectionTitle(Messages.get("alerts.section.email")), mail,
                phi, new HBox(8, test), result);
        content.setPadding(new Insets(4));
        content.setPrefWidth(640);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("alertOkButton");
        ok.addEventFilter(ActionEvent.ACTION, ev -> {
            Optional<AlertSettings> built = build();
            if (built.isEmpty()) {
                ev.consume();
                return;
            }
            try {
                savePassword();
            } catch (SecretStoreException ex) {
                result.setText(ex.getMessage());
                ev.consume();
                return;
            }
            context.updateSettings(x -> x.withAlerts(built.get()));
        });
    }

    private Optional<AlertSettings> build() {
        try {
            String url = webhook.getText() == null ? "" : webhook.getText().trim();
            if (!url.isEmpty()) {
                WebhookSink.validate(url);
            }
            AlertSettings.EmailSettings e = new AlertSettings.EmailSettings(emailEnabled.isSelected(),
                    smtpHost.getText(), Fields.parse(smtpPort, Messages.get("alerts.smtp.port"), 1, 65_535),
                    security.getValue(), username.getText(), from.getText(), to.getText());
            if (e.enabled() && (e.host().isEmpty() || e.from().isEmpty() || e.to().isEmpty())) {
                throw new IllegalArgumentException(Messages.get("alerts.email.incomplete"));
            }
            return Optional.of(new AlertSettings(desktop.isSelected(), deadLetter.isSelected(),
                    Fields.parse(threshold, Messages.get("alerts.threshold"), 1, 100_000), circuit.isSelected(),
                    certificates.isSelected(), url, e));
        } catch (IllegalArgumentException ex) {
            result.setText(ex.getMessage());
            return Optional.empty();
        }
    }

    private void savePassword() {
        String typed = password.getText();
        if (typed != null && !typed.isEmpty()) {
            context.secrets().put(AlertSettings.SMTP_PASSWORD_KEY, typed);
        }
    }

    private void sendTest(Button button) {
        Optional<AlertSettings> s = build();
        if (s.isEmpty()) {
            return;
        }
        String typed = password.getText() == null || password.getText().isEmpty() ? null : password.getText();
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() {
                return AlertMonitor.test(s.get(), typed, context.secrets(), Clock.systemUTC());
            }
        };
        task.setOnSucceeded(e -> {
            button.setDisable(false);
            result.setText(String.join("\n", task.getValue()));
        });
        task.setOnFailed(e -> {
            button.setDisable(false);
            result.setText(String.valueOf(task.getException()));
        });
        button.setDisable(true);
        result.setText(Messages.get("alerts.testing"));
        context.executor().submit(task);
    }
}
