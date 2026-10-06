package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.api.ApiClient;
import io.hl7sender.core.config.AppPaths;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI test for turning the local REST API on and off from the app. */
@ExtendWith(ApplicationExtension.class)
class LocalApiUiTest {

    private AppPaths paths;
    private AppContext context;
    private MainWindow window;

    @Start
    void start(Stage stage) throws IOException {
        Path home = Files.createTempDirectory("hl7sender-p6-ui");
        paths = new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs"));
        context = new AppContext(paths);
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        Styles.apply(scene);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown() {
        window.shutdown();
        context.close();
    }

    private void openDialog() {
        Platform.runLater(() -> new ApiDialog(window.getScene().getWindow(), context).showAndWait());
        WaitForAsyncUtils.waitForFxEvents();
    }

    @Test
    void localApiCanBeTurnedOnAndOffFromTheApp(FxRobot robot) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        openDialog();
        assertThat(robot.lookup("#apiStateLabel").queryAs(Label.class).getText()).isEqualTo("Not running.");
        int p = port;
        robot.interact(() -> {
            robot.lookup("#apiEnabledBox").queryAs(CheckBox.class).setSelected(true);
            robot.lookup("#apiPortField").queryAs(TextField.class).setText(String.valueOf(p));
        });
        robot.clickOn("#apiShowTokenButton");
        assertThat(robot.lookup("#apiTokenField").queryAs(TextField.class).getText()).hasSize(64);
        MainWindowUiTest.screenshot(robot, robot.lookup("#apiOkButton").query().getScene().getRoot(), "25-local-api");
        robot.clickOn("#apiOkButton");
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(context.settings().api().enabled()).isTrue();
        assertThat(context.apiPort()).contains(port);
        ApiClient client = ApiClient.discover(paths).orElseThrow();
        assertThat(client.get("destinations").status()).isEqualTo(200);

        openDialog();
        assertThat(robot.lookup("#apiStateLabel").queryAs(Label.class).getText())
                .isEqualTo("Running at http://127.0.0.1:" + port + "/api/v1/");
        robot.interact(() -> robot.lookup("#apiEnabledBox").queryAs(CheckBox.class).setSelected(false));
        robot.clickOn("#apiOkButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(context.apiPort()).isEmpty();
        assertThat(ApiClient.discover(paths)).isEmpty();
    }
}
