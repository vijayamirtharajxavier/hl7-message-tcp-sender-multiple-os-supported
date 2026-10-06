package io.hl7sender.app;

import io.hl7sender.core.hl7.Delimiters;
import io.hl7sender.core.hl7.FieldDictionary;
import io.hl7sender.core.hl7.Hl7Location;
import io.hl7sender.core.hl7.ParsedMessage;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.Popup;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * HL7 message editor. It colours segment names, delimiters, escape sequences and {@code ${variables}},
 * shows the field name (from HAPI, for the message's version) when hovering over any value, and reports
 * the cursor's HL7 location ({@code PID-5.1 Patient Name > Family Name (XPN)}) in a status line.
 */
final class Hl7Editor extends BorderPane {

    private static final Pattern VARIABLE = Pattern.compile("\\$\\{[^}]*}");

    private final CodeArea area = new CodeArea();
    private final Label location = new Label(" ");
    private final Popup tooltip = new Popup();
    private final Label tooltipLabel = new Label();

    Hl7Editor(String id) {
        area.setId(id);
        area.setAccessibleText("HL7 message editor");
        location.setAccessibleHelp("Field at the cursor");
        area.getStyleClass().add("hl7-editor");
        Styles.mono(area);
        area.setWrapText(false);
        area.textProperty().addListener((obs, old, text) -> area.setStyleSpans(0, highlight(text)));
        area.caretPositionProperty().addListener((obs, old, pos) -> updateLocation());
        area.setContextMenu(contextMenu());

        tooltipLabel.getStyleClass().add("hl7-tooltip");
        tooltip.getContent().add(tooltipLabel);
        area.setMouseOverTextDelay(Duration.ofMillis(350));
        area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, e -> {
            describe(e.getCharacterIndex()).ifPresent(text -> {
                tooltipLabel.setText(text);
                tooltip.show(area, e.getScreenPosition().getX() + 12, e.getScreenPosition().getY() + 16);
            });
        });
        area.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, e -> tooltip.hide());

        location.setId(id + "Location");
        location.getStyleClass().add("field-label");
        location.setPadding(new Insets(2, 4, 0, 4));
        setCenter(new VirtualizedScrollPane<>(area));
        setBottom(location);
    }

    String getText() {
        return area.getText();
    }

    void setText(String text) {
        area.replaceText(text == null ? "" : text);
        area.moveTo(0);
        area.requestFollowCaret();
    }

    ObservableValue<String> textProperty() {
        return area.textProperty();
    }

    CodeArea area() {
        return area;
    }

    void setEditable(boolean editable) {
        area.setEditable(editable);
    }

    /**
     * Text for the status line and tooltip at {@code offset}, for example
     * {@code PID-5.1 Patient Name > Family Name (XPN) = DOE}.
     */
    Optional<String> describe(int offset) {
        String text = area.getText();
        return Hl7Location.at(text, offset).map(loc -> {
            if (loc.field() == 0) {
                return loc.segment() + " segment " + loc.segmentOrdinal();
            }
            String version = versionOf(text);
            String label = FieldDictionary.shared().describe(version, loc.segment(), loc.field(),
                    loc.path().contains(".") ? loc.component() : 0);
            if (loc.repetition() > 1) {
                label = label.replaceFirst("^(\\S+)", loc.path());
            }
            String value = loc.path().contains(".") ? loc.componentValue() : loc.fieldValue();
            return value.isEmpty() ? label : label + " = " + abbreviate(value);
        });
    }

    private void updateLocation() {
        location.setText(describe(area.getCaretPosition()).orElse(" "));
    }

    private static String versionOf(String text) {
        try {
            return ParsedMessage.parse(text).header().version();
        } catch (RuntimeException e) {
            return FieldDictionary.FALLBACK_VERSION;
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    /** Computes style spans: segment names, the five delimiters, escape sequences and template variables. */
    static StyleSpans<Collection<String>> highlight(String text) {
        Delimiters d = delimitersOf(text);
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        int i = 0;
        int n = text.length();
        boolean lineStart = true;
        int plainStart = 0;
        while (i < n) {
            char c = text.charAt(i);
            String style = null;
            int len = 1;
            if (lineStart && i + 3 <= n && Character.isLetterOrDigit(c)) {
                style = "hl7-segment";
                len = 3;
            } else if (c == '$' && i + 1 < n && text.charAt(i + 1) == '{') {
                Matcher m = VARIABLE.matcher(text).region(i, n);
                if (m.lookingAt()) {
                    style = "hl7-variable";
                    len = m.end() - i;
                }
            } else if (c == d.escape() && !isMshEncoding(text, i)) {
                int close = text.indexOf(d.escape(), i + 1);
                int eol = nextBreak(text, i);
                if (close > i && close < eol && close - i <= 12) {
                    style = "hl7-escape";
                    len = close - i + 1;
                }
            } else if (c == d.field()) {
                style = "hl7-field-sep";
            } else if (c == d.component()) {
                style = "hl7-component-sep";
            } else if (c == d.repetition()) {
                style = "hl7-repetition-sep";
            } else if (c == d.subcomponent()) {
                style = "hl7-subcomponent-sep";
            }
            if (style != null) {
                if (i > plainStart) {
                    spans.add(List.of(), i - plainStart);
                }
                spans.add(List.of(style), len);
                i += len;
                plainStart = i;
                lineStart = false;
                continue;
            }
            lineStart = c == '\r' || c == '\n';
            i++;
        }
        spans.add(List.of(), Math.max(0, n - plainStart));
        return spans.create();
    }

    /** The escape character inside MSH-2 is a delimiter declaration, not an escape sequence. */
    private static boolean isMshEncoding(String text, int i) {
        int lineStart = i;
        while (lineStart > 0 && text.charAt(lineStart - 1) != '\r' && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        return text.startsWith("MSH", lineStart) && i - lineStart >= 4 && i - lineStart <= 7;
    }

    private static int nextBreak(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                return i;
            }
        }
        return text.length();
    }

    private static Delimiters delimitersOf(String text) {
        if (text.startsWith("MSH") && text.length() >= 8) {
            int end = nextBreak(text, 0);
            try {
                return Delimiters.fromHeaderSegment(text.substring(0, end));
            } catch (IllegalArgumentException e) {
                return Delimiters.DEFAULT;
            }
        }
        return Delimiters.DEFAULT;
    }

    private ContextMenu contextMenu() {
        MenuItem undo = new MenuItem("Undo");
        undo.setOnAction(e -> area.undo());
        MenuItem redo = new MenuItem("Redo");
        redo.setOnAction(e -> area.redo());
        MenuItem cut = new MenuItem("Cut");
        cut.setOnAction(e -> area.cut());
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> area.copy());
        MenuItem paste = new MenuItem("Paste");
        paste.setOnAction(e -> area.paste());
        MenuItem all = new MenuItem("Select all");
        all.setOnAction(e -> area.selectAll());
        return new ContextMenu(undo, redo, new SeparatorMenuItem(), cut, copy, paste, new SeparatorMenuItem(), all);
    }
}
