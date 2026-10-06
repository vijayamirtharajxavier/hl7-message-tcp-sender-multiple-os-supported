package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.auth.Role;
import io.hl7sender.core.auth.User;
import io.hl7sender.core.config.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI test for Tools > Users and roles, signing in, and hiding what a role may not do. */
@ExtendWith(ApplicationExtension.class)
class UsersUiTest {

    private Stage stage;
    private AppPaths paths;
    private AppContext context;
    private MainWindow window;

    @Start
    void start(Stage stage) throws IOException {
        this.stage = stage;
        Path home = Files.createTempDirectory("hl7sender-users-ui");
        paths = new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs"));
        context = new AppContext(paths);
        show(new MainWindow(context, null));
    }

    private void show(MainWindow w) {
        window = w;
        Scene scene = new Scene(w, 1280, 860);
        Styles.apply(scene);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        window.shutdown();
        context.close();
    }

    private static void fill(FxRobot robot, String user, String name, Role role, String password) {
        robot.interact(() -> {
            robot.lookup("#usersUsernameField").queryAs(TextField.class).setText(user);
            robot.lookup("#usersDisplayNameField").queryAs(TextField.class).setText(name);
            @SuppressWarnings("unchecked")
            ChoiceBox<Role> box = robot.lookup("#usersRoleBox").queryAs(ChoiceBox.class);
            box.setValue(role);
            robot.lookup("#usersPasswordField").queryAs(PasswordField.class).setText(password);
        });
    }

    private static String state(FxRobot robot) {
        return robot.lookup("#usersStateLabel").queryAs(Label.class).getText();
    }

    private MenuItem menuItem(String id) {
        return ((MenuBar) window.getTop()).getMenus().stream().flatMap(m -> m.getItems().stream())
                .filter(i -> id.equals(i.getId())).findFirst().orElseThrow();
    }

    private boolean shown(FxRobot robot, String id) {
        return robot.lookup("#" + id).queryAll().stream().anyMatch(Node::isVisible);
    }

    @Test
    void usersAreManagedAndRolesHideWhatTheyMayNotDo(FxRobot robot) throws Exception {
        assertThat(context.signInRequired()).isFalse();
        assertThat(menuItem("usersMenuItem").isVisible()).isTrue();
        robot.interact(() -> new UsersDialog(window.getScene().getWindow(), context.engine().orElseThrow().store())
                .show());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#usersIntroLabel").queryAs(Label.class).getText()).contains("no users");

        fill(robot, "olga", "", Role.OPERATOR, "operator-pass");
        robot.clickOn("#usersAddButton");
        assertThat(state(robot)).contains("first user must be an administrator");
        fill(robot, "ada", "Ada Lovelace", Role.ADMIN, "short");
        robot.clickOn("#usersAddButton");
        assertThat(state(robot)).contains("at least 8");
        fill(robot, "ada", "Ada Lovelace", Role.ADMIN, "admin-pass");
        robot.clickOn("#usersAddButton");
        assertThat(state(robot)).contains("Added ada").contains("next start");
        fill(robot, "olga", "Olga", Role.OPERATOR, "operator-pass");
        robot.clickOn("#usersAddButton");
        fill(robot, "vic", "", Role.VIEWER, "viewer-pass");
        robot.clickOn("#usersAddButton");
        @SuppressWarnings("unchecked")
        TableView<User> table = robot.lookup("#usersTable").queryAs(TableView.class);
        assertThat(table.getItems()).extracting(User::username).containsExactly("ada", "olga", "vic");
        MainWindowUiTest.screenshot(robot, table.getScene().getRoot(), "41-users");

        // The last administrator is kept.
        robot.interact(() -> table.getSelectionModel().select(0));
        robot.interact(() -> {
            @SuppressWarnings("unchecked")
            ChoiceBox<Role> box = robot.lookup("#usersRoleBox").queryAs(ChoiceBox.class);
            box.setValue(Role.VIEWER);
        });
        robot.clickOn("#usersSaveButton");
        assertThat(state(robot)).contains("last administrator");
        robot.clickOn("#usersRemoveButton");
        assertThat(state(robot)).contains("last administrator");
        // Disable and re-enable a user.
        robot.interact(() -> table.getSelectionModel().select(2));
        robot.clickOn("#usersToggleButton");
        assertThat(state(robot)).contains("vic can no longer sign in");
        robot.clickOn("#usersToggleButton");
        assertThat(state(robot)).contains("vic can sign in again");
        robot.interact(() -> table.getScene().getWindow().hide());

        // Next start: everyone signs in.
        robot.interact(() -> {
            window.shutdown();
            context.close();
            context = new AppContext(paths);
        });
        assertThat(context.signInRequired()).isTrue();
        robot.interact(() -> new SignInDialog(stage, context).show());
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            robot.lookup("#signInUserField").queryAs(TextField.class).setText("vic");
            robot.lookup("#signInPasswordField").queryAs(PasswordField.class).setText("wrong-pass");
        });
        robot.clickOn("#signInOkButton");
        assertThat(robot.lookup("#signInErrorLabel").queryAs(Label.class).getText()).contains("Wrong user name");
        assertThat(context.signInRequired()).isTrue();
        robot.interact(() -> robot.lookup("#signInPasswordField").queryAs(PasswordField.class).setText("viewer-pass"));
        robot.clickOn("#signInOkButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(context.signInRequired()).isFalse();
        assertThat(context.access().actor()).contains("vic");

        robot.interact(() -> show(new MainWindow(context, null)));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#signedInLabel").queryAs(Label.class).getText())
                .isEqualTo("Signed in as vic (Viewer)");
        // A viewer sees the queue, history and logs, but cannot send, change the queue or configure.
        assertThat(shown(robot, "sendButton")).isFalse();
        assertThat(shown(robot, "enqueueButton")).isFalse();
        assertThat(shown(robot, "validateButton")).isTrue();
        assertThat(menuItem("usersMenuItem").isVisible()).isFalse();
        assertThat(menuItem("schedulesMenuItem").isVisible()).isFalse();
        assertThat(menuItem("importMenuItem").isVisible()).isFalse();
        robot.clickOn("#queueTab");
        assertThat(shown(robot, "addDestinationButton")).isFalse();
        assertThat(shown(robot, "pauseButton")).isFalse();
        assertThat(robot.lookup("#destinationList").query().isVisible()).isTrue();
        MainWindowUiTest.screenshot(robot, window, "42-viewer");
    }
}
