package io.hl7sender.app;

import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.listener.ResponseRule;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

/**
 * Creates or edits one responder rule: which messages it matches, how it answers, and an optional follow-up
 * message queued back to a destination.
 */
final class ResponseRuleDialog extends Dialog<ResponseRule> {

    /** A starting point for a follow-up result message, using values from the order. */
    static final String SAMPLE_FOLLOW_UP = "MSH|^~\\&|${IN:MSH-5}|${IN:MSH-6}|${IN:MSH-3}|${IN:MSH-4}|${NOW}||"
            + "ORU^R01^ORU_R01|${CONTROL_ID}|P|2.5.1\n"
            + "PID|1||${IN:PID-3}||${IN:PID-5}||${IN:PID-7}|${IN:PID-8}\n"
            + "OBR|1|${IN:ORC-2}|${RANDOM:8}|${IN:OBR-4}|||${NOW}|||||||||||||||${NOW}|||F\n"
            + "OBX|1|NM|6690-2^WBC^LN||7.5|10*9/L|4.0-11.0|N|||F\n";
    /** A starting point for a custom acknowledgment. */
    static final String SAMPLE_CUSTOM = "MSH|^~\\&|${IN:MSH-5}|${IN:MSH-6}|${IN:MSH-3}|${IN:MSH-4}|${NOW}||"
            + "ACK^${IN:MSH-9.2}^ACK|${CONTROL_ID}|P|${IN:MSH-12}\n"
            + "MSA|AA|${IN:MSH-10}|Received ${IN:MSH-9.1} for ${IN:PID-3.1}\n";

    private final TextField name = new TextField();
    private final TextField messageType = new TextField();
    private final TextField field = new TextField();
    private final TextField pattern = new TextField();
    private final ComboBox<ResponseMode> mode = new ComboBox<>(FXCollections.observableArrayList(
            ResponseMode.values()));
    private final TextField delay = Fields.integer("ruleDelayField", 0, 7, 6);
    private final TextField responseText = new TextField();
    private final TextArea customResponse = new TextArea();
    private final CheckBox followUpBox = new CheckBox(Messages.get("rules.followUp"));
    private final ComboBox<String> destination = new ComboBox<>();
    private final TextField followUpDelay = Fields.integer("ruleFollowUpDelayField", 0, 7, 6);
    private final TextArea followUpTemplate = new TextArea();
    private final Label error = new Label();

    ResponseRuleDialog(Window owner, ResponseRule existing, List<String> destinations) {
        initOwner(owner);
        setTitle(Messages.get(existing == null ? "rules.add.title" : "rules.edit.title"));
        setHeaderText(Messages.get("rules.header"));
        name.setId("ruleNameField");
        messageType.setId("ruleTypeField");
        messageType.setPromptText("ORM^O01, ADT^A0*, ADT");
        field.setId("ruleFieldField");
        field.setPromptText("PID-3.1");
        pattern.setId("rulePatternField");
        pattern.setPromptText(Messages.get("rules.pattern.prompt"));
        mode.setId("ruleModeBox");
        responseText.setId("ruleResponseTextField");
        customResponse.setId("ruleCustomResponse");
        customResponse.setPrefRowCount(4);
        Styles.mono(customResponse);
        followUpBox.setId("ruleFollowUpBox");
        destination.setId("ruleDestinationBox");
        destination.getItems().setAll(destinations);
        destination.setEditable(true);
        followUpTemplate.setId("ruleFollowUpTemplate");
        followUpTemplate.setPrefRowCount(6);
        Styles.mono(followUpTemplate);
        error.setId("ruleErrorLabel");
        error.getStyleClass().add("issue-error");
        error.setWrapText(true);

        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.setPadding(new Insets(4));
        int row = 0;
        g.addRow(row++, Fields.label(Messages.get("rules.name")), name);
        g.addRow(row++, Fields.label(Messages.get("rules.type")), messageType);
        g.addRow(row++, Fields.label(Messages.get("rules.field")), field);
        g.addRow(row++, Fields.label(Messages.get("rules.pattern")), pattern);
        g.addRow(row++, Fields.label(Messages.get("rules.mode")), mode);
        g.addRow(row++, Fields.label(Messages.get("rules.delay")), delay);
        g.addRow(row++, Fields.label(Messages.get("rules.text")), responseText);
        g.addRow(row++, Fields.label(Messages.get("rules.custom")), customResponse);
        g.add(followUpBox, 0, row++, 2, 1);
        g.addRow(row++, Fields.label(Messages.get("rules.destination")), destination);
        g.addRow(row++, Fields.label(Messages.get("rules.followUpDelay")), followUpDelay);
        g.addRow(row++, Fields.label(Messages.get("rules.template")), followUpTemplate);
        Label help = new Label(Messages.get("rules.help"));
        help.setWrapText(true);
        help.getStyleClass().add("field-label");
        g.add(help, 0, row++, 2, 1);
        g.add(error, 0, row, 2, 1);
        GridPane.setHgrow(customResponse, Priority.ALWAYS);
        GridPane.setHgrow(followUpTemplate, Priority.ALWAYS);
        g.setPrefWidth(720);

        customResponse.disableProperty().bind(mode.valueProperty().isNotEqualTo(ResponseMode.CUSTOM));
        responseText.disableProperty().bind(mode.valueProperty().isEqualTo(ResponseMode.CUSTOM));
        destination.disableProperty().bind(followUpBox.selectedProperty().not());
        followUpDelay.disableProperty().bind(followUpBox.selectedProperty().not());
        followUpTemplate.disableProperty().bind(followUpBox.selectedProperty().not());
        mode.valueProperty().addListener((o, a, m) -> {
            if (m == ResponseMode.CUSTOM && customResponse.getText().isBlank()) {
                customResponse.setText(SAMPLE_CUSTOM);
            }
        });
        followUpBox.selectedProperty().addListener((o, a, on) -> {
            if (on && followUpTemplate.getText().isBlank()) {
                followUpTemplate.setText(SAMPLE_FOLLOW_UP);
            }
        });

        load(existing);
        getDialogPane().setContent(g);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("ruleOkButton");
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

    private void load(ResponseRule r) {
        if (r == null) {
            mode.setValue(ResponseMode.ACCEPT);
            return;
        }
        name.setText(r.name());
        messageType.setText(r.messageType());
        field.setText(r.field());
        pattern.setText(r.pattern());
        mode.setValue(r.mode());
        delay.setText(String.valueOf(r.delayMs()));
        responseText.setText(r.responseText());
        customResponse.setText(r.customResponse().replace("\r", "\n"));
        if (r.followUp() != null) {
            followUpBox.setSelected(true);
            destination.setValue(r.followUp().destination());
            followUpDelay.setText(String.valueOf(r.followUp().delayMs()));
            followUpTemplate.setText(r.followUp().template().replace("\r", "\n"));
        }
    }

    /** The rule as entered; throws with a readable message if it is not valid. */
    ResponseRule read() {
        ResponseRule.FollowUp followUp = null;
        if (followUpBox.isSelected()) {
            String dest = destination.getEditor().getText();
            followUp = new ResponseRule.FollowUp(dest == null || dest.isBlank() ? destination.getValue() : dest,
                    delay(followUpDelay), followUpTemplate.getText());
        }
        return new ResponseRule(name.getText(), messageType.getText(), field.getText(), pattern.getText(),
                mode.getValue(), delay(delay), responseText.getText(), customResponse.getText(), followUp);
    }

    private static int delay(TextField f) {
        return f.getText().isBlank() ? 0 : Fields.parse(f, Messages.get("rules.delay"), 0, 3_600_000);
    }
}
