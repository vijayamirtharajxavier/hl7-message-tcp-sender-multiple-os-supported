package io.hl7sender.app;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Asks for a user name and password when the queue has users. OK stays open until the sign-in succeeds. */
final class SignInDialog extends Dialog<Boolean> {

    private final TextField user = new TextField();
    private final PasswordField password = new PasswordField();
    private final Label error = new Label();

    SignInDialog(Window owner, AppContext context) {
        if (owner != null) {
            initOwner(owner);
        }
        setTitle(Messages.get("signin.title"));
        setHeaderText(Messages.get("signin.header", context.queueLocation()));
        user.setId("signInUserField");
        user.setAccessibleText(Messages.get("signin.user"));
        user.setPrefColumnCount(24);
        password.setId("signInPasswordField");
        password.setAccessibleText(Messages.get("signin.password"));
        error.setId("signInErrorLabel");
        error.setWrapText(true);
        error.getStyleClass().add("error-text");
        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-pane");
        grid.addRow(0, Fields.label(Messages.get("signin.user")), user);
        grid.addRow(1, Fields.label(Messages.get("signin.password")), password);
        VBox content = new VBox(8, grid, error);
        content.setPadding(new Insets(4));
        content.setPrefWidth(420);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setId("signInOkButton");
        ok.setText(Messages.get("signin.ok"));
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            if (!context.signIn(user.getText(), password.getText())) {
                error.setText(Messages.get("signin.failed"));
                password.clear();
                password.requestFocus();
                e.consume();
            }
        });
        setResultConverter(b -> b == ButtonType.OK);
        Platform.runLater(user::requestFocus);
    }
}
