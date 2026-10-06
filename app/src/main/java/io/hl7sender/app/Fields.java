package io.hl7sender.app;

import java.util.function.UnaryOperator;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;

/** Small helpers for building forms. */
final class Fields {

    private Fields() {
    }

    /** A text field that only accepts digits, up to {@code maxDigits}. */
    static TextField integer(String id, int value, int maxDigits, int prefColumns) {
        TextField field = new TextField(String.valueOf(value));
        field.setId(id);
        field.setPrefColumnCount(prefColumns);
        UnaryOperator<TextFormatter.Change> filter = change ->
                change.getControlNewText().matches("\\d{0," + maxDigits + "}") ? change : null;
        field.setTextFormatter(new TextFormatter<>(filter));
        return field;
    }

    /** Parses an integer field, or throws with a message that names the field. */
    static int parse(TextField field, String name, int min, int max) {
        String text = field.getText().trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    static Label label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        label.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        return label;
    }
}
