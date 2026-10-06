package io.hl7sender.app;

import io.hl7sender.core.api.ApiToken;
import io.hl7sender.core.config.AppSettings;
import java.io.IOException;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Turns the local REST API on or off, and shows its address, token and an example request. */
final class ApiDialog extends Dialog<ButtonType> {

    private final AppContext context;
    private final CheckBox enabled = new CheckBox(Messages.get("api.enabled"));
    private final TextField port;
    private final TextField token = new TextField();
    private final Label state = new Label();
    private final TextArea example = new TextArea();

    ApiDialog(Window owner, AppContext context) {
        this.context = context;
        initOwner(owner);
        setTitle(Messages.get("api.title"));
        setHeaderText(Messages.get("api.header"));
        AppSettings.Api s = context.settings().api();
        enabled.setId("apiEnabledBox");
        enabled.setSelected(s.enabled());
        port = Fields.integer("apiPortField", s.port(), 5, 5);
        port.setAccessibleText(Messages.get("api.port"));
        state.setId("apiStateLabel");
        state.setWrapText(true);
        token.setId("apiTokenField");
        token.setEditable(false);
        token.setPromptText(Messages.get("api.token.hidden"));
        token.setPrefColumnCount(40);
        Button show = new Button(Messages.get("api.token.show"));
        show.setId("apiShowTokenButton");
        show.setOnAction(e -> token.setText(loadToken()));
        Button copy = new Button(Messages.get("api.token.copy"));
        copy.setId("apiCopyTokenButton");
        copy.setOnAction(e -> {
            ClipboardContent c = new ClipboardContent();
            c.putString(loadToken());
            Clipboard.getSystemClipboard().setContent(c);
        });
        Button regenerate = new Button(Messages.get("api.token.regenerate"));
        regenerate.setId("apiRegenerateButton");
        regenerate.setOnAction(e -> {
            try {
                token.setText(ApiToken.regenerate(context.paths().configDir()));
                state.setText(Messages.get("api.token.regenerated"));
            } catch (IOException ex) {
                state.setText(ex.getMessage());
            }
        });
        for (Button b : new Button[] {show, copy, regenerate}) {
            b.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox tokenRow = new HBox(6, token, show, copy, regenerate);
        HBox.setHgrow(token, Priority.ALWAYS);
        example.setId("apiExample");
        example.setEditable(false);
        example.setPrefRowCount(5);
        example.setWrapText(true);
        Styles.mono(example);
        example.setText(example(s.port()));
        port.textProperty().addListener((o, a, b) -> example.setText(example(parsePort())));
        showState();

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-pane");
        grid.addRow(0, enabled);
        grid.addRow(1, Fields.label(Messages.get("api.port")), port);
        Label note = new Label(Messages.get("api.note", ApiToken.file(context.paths().configDir())));
        note.setWrapText(true);
        note.getStyleClass().add("field-label");
        VBox content = new VBox(8, grid, state, Styles.sectionTitle(Messages.get("api.token")), tokenRow, note,
                Styles.sectionTitle(Messages.get("api.example")), example);
        content.setPadding(new Insets(4));
        content.setPrefWidth(680);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("apiOkButton");
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            int p;
            try {
                p = Fields.parse(port, Messages.get("api.port"), 1, 65_535);
            } catch (IllegalArgumentException ex) {
                state.setText(ex.getMessage());
                e.consume();
                return;
            }
            context.updateSettings(x -> x.withApi(new AppSettings.Api(enabled.isSelected(), p)));
            try {
                context.applyApiSettings();
            } catch (IOException ex) {
                state.setText(Messages.get("api.failed", p, ex.getMessage()));
                e.consume();
            }
        });
    }

    private void showState() {
        if (context.engine().isEmpty()) {
            state.setText(Messages.get("api.noQueue"));
            return;
        }
        state.setText(context.apiPort().map(p -> Messages.get("api.running", "http://127.0.0.1:" + p + "/api/v1/"))
                .orElse(Messages.get("api.stopped")));
    }

    private String loadToken() {
        try {
            return ApiToken.loadOrCreate(context.paths().configDir());
        } catch (IOException e) {
            state.setText(e.getMessage());
            return "";
        }
    }

    private int parsePort() {
        try {
            return Integer.parseInt(port.getText().trim());
        } catch (NumberFormatException e) {
            return AppSettings.Api.DEFAULT_PORT;
        }
    }

    private static String example(int port) {
        String base = "http://127.0.0.1:" + port + "/api/v1";
        return "curl -H \"Authorization: Bearer $TOKEN\" " + base + "/destinations\n"
                + "curl -H \"Authorization: Bearer $TOKEN\" --data-binary @message.hl7 " + base
                + "/destinations/NAME/messages\n"
                + "curl -H \"Authorization: Bearer $TOKEN\" " + base + "/messages/ID";
    }
}
