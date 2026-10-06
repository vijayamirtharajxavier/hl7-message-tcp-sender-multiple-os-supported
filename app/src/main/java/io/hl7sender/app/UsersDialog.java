package io.hl7sender.app;

import io.hl7sender.core.auth.Role;
import io.hl7sender.core.auth.User;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueStore;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.function.Supplier;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.StringConverter;

/**
 * Adds users, changes their roles and passwords, and disables or removes them. Adding the first user (an
 * administrator) turns on sign-in for everyone.
 */
final class UsersDialog extends Dialog<ButtonType> {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final QueueStore store;
    private final TableView<User> table = new TableView<>();
    private final TextField username = new TextField();
    private final TextField displayName = new TextField();
    private final ChoiceBox<Role> role = new ChoiceBox<>(FXCollections.observableArrayList(Role.values()));
    private final PasswordField password = new PasswordField();
    private final Label intro = new Label();
    private final Label state = new Label();

    UsersDialog(Window owner, QueueStore store) {
        this.store = store;
        initOwner(owner);
        setTitle(Messages.get("users.title"));
        setHeaderText(Messages.get("users.header"));
        table.setId("usersTable");
        table.setPrefHeight(200);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(column("users.col.user", u -> u.username()));
        table.getColumns().add(column("users.col.name", u -> u.displayName()));
        table.getColumns().add(column("users.col.role", u -> roleName(u.role())));
        table.getColumns().add(column("users.col.state", u -> Messages.get(u.enabled() ? "users.enabled"
                : "users.disabled")));
        table.getColumns().add(column("users.col.lastLogin", u -> u.lastLoginAt().map(WHEN::format).orElse("")));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, u) -> {
            if (u != null) {
                username.setText(u.username());
                displayName.setText(u.displayName());
                role.setValue(u.role());
                password.clear();
            }
        });
        username.setId("usersUsernameField");
        username.setAccessibleText(Messages.get("users.col.user"));
        displayName.setId("usersDisplayNameField");
        displayName.setAccessibleText(Messages.get("users.col.name"));
        role.setId("usersRoleBox");
        role.setConverter(new StringConverter<>() {
            @Override
            public String toString(Role r) {
                return r == null ? "" : roleName(r) + " - " + Messages.get("users.role." + r.name().toLowerCase(
                        Locale.ROOT) + ".help");
            }

            @Override
            public Role fromString(String s) {
                return null;
            }
        });
        role.setValue(Role.ADMIN);
        password.setId("usersPasswordField");
        password.setAccessibleText(Messages.get("users.password"));
        intro.setId("usersIntroLabel");
        intro.setWrapText(true);
        state.setId("usersStateLabel");
        state.setWrapText(true);
        intro.setMinHeight(Region.USE_PREF_SIZE);
        state.setMinHeight(Region.USE_PREF_SIZE);

        GridPane form = new GridPane();
        form.getStyleClass().add("form-pane");
        form.addRow(0, Fields.label(Messages.get("users.col.user")), username);
        form.addRow(1, Fields.label(Messages.get("users.col.name")), displayName);
        form.addRow(2, Fields.label(Messages.get("users.col.role")), role);
        form.addRow(3, Fields.label(Messages.get("users.password")), password);
        FlowPane actions = new FlowPane(6, 6,
                button("users.add", "usersAddButton", this::add),
                button("users.save", "usersSaveButton", this::save),
                button("users.setPassword", "usersPasswordButton", this::setPassword),
                button("users.toggle", "usersToggleButton", this::toggle),
                button("users.remove", "usersRemoveButton", this::remove));
        VBox content = new VBox(8, intro, table, form, actions, state);
        content.setPadding(new Insets(4));
        content.setPrefWidth(640);
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().getStylesheets().add(Styles.stylesheet());
        refresh();
    }

    private static String roleName(Role r) {
        return Messages.get("users.role." + r.name().toLowerCase(Locale.ROOT));
    }

    private static TableColumn<User, String> column(String key, java.util.function.Function<User, String> value) {
        TableColumn<User, String> c = new TableColumn<>(Messages.get(key));
        c.setCellValueFactory(d -> new SimpleStringProperty(value.apply(d.getValue())));
        return c;
    }

    private static Button button(String key, String id, Runnable action) {
        Button b = new Button(Messages.get(key));
        b.setId(id);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void refresh() {
        User selected = table.getSelectionModel().getSelectedItem();
        table.getItems().setAll(store.users());
        if (selected != null) {
            table.getItems().stream().filter(u -> u.id() == selected.id()).findFirst()
                    .ifPresent(u -> table.getSelectionModel().select(u));
        }
        intro.setText(Messages.get(table.getItems().isEmpty() ? "users.intro.none" : "users.intro.some"));
    }

    /** Runs a change and shows its result, or the reason it was refused. */
    private void run(Supplier<String> change) {
        try {
            state.setText(change.get());
            refresh();
        } catch (QueueException | IllegalArgumentException e) {
            state.setText(e.getMessage());
        }
    }

    private User selected() {
        User u = table.getSelectionModel().getSelectedItem();
        if (u == null) {
            throw new IllegalArgumentException(Messages.get("users.selectFirst"));
        }
        return u;
    }

    private void add() {
        run(() -> {
            boolean first = table.getItems().isEmpty();
            User u = store.createUser(User.of(username.getText(), displayName.getText(), role.getValue()),
                    password.getText());
            password.clear();
            return Messages.get(first ? "users.addedFirst" : "users.added", u.username());
        });
    }

    private void save() {
        run(() -> {
            User u = store.updateUser(selected().withDisplayName(displayName.getText()).withRole(role.getValue()));
            return Messages.get("users.saved", u.username());
        });
    }

    private void setPassword() {
        run(() -> {
            User u = selected();
            store.setPassword(u.username(), password.getText());
            password.clear();
            return Messages.get("users.passwordSet", u.username());
        });
    }

    private void toggle() {
        run(() -> {
            User u = store.updateUser(selected().withEnabled(!selected().enabled()));
            return Messages.get(u.enabled() ? "users.nowEnabled" : "users.nowDisabled", u.username());
        });
    }

    private void remove() {
        run(() -> {
            User u = selected();
            store.deleteUser(u.username());
            table.getSelectionModel().clearSelection();
            return Messages.get(store.accessControlEnabled() ? "users.removed" : "users.removedLast", u.username());
        });
    }
}
