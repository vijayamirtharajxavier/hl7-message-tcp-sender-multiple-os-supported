package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.queue.DestinationConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI test for choosing a transport in the destination editor. */
@ExtendWith(ApplicationExtension.class)
class TransportUiTest {

    private AppContext context;
    private MainWindow window;

    @Start
    void start(Stage stage) throws IOException {
        Path home = Files.createTempDirectory("hl7sender-transport-ui");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
        window = new MainWindow(context, null);
        stage.setScene(new Scene(window, 1280, 860));
        stage.show();
    }

    @AfterEach
    void tearDown() {
        window.shutdown();
        context.close();
    }

    @Test
    void httpDestinationIsConfiguredInTheTransportTab(FxRobot robot) throws Exception {
        DestinationDialog[] dialog = new DestinationDialog[1];
        DestinationConfig[] saved = new DestinationConfig[1];
        robot.interact(() -> {
            dialog[0] = new DestinationDialog(window.getScene().getWindow(), null, context.engine().orElseThrow());
            dialog[0].show();
        });
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            robot.lookup("#destNameField").queryAs(TextField.class).setText("Web");
            TabPane tabs = robot.lookup("#destTabs").queryAs(TabPane.class);
            tabs.getSelectionModel().select(tabs.getTabs().stream()
                    .filter(t -> "destTransportTab".equals(t.getId())).findFirst().orElseThrow());
            @SuppressWarnings("unchecked")
            ComboBox<DestinationDialog.TransportChoice> box = robot.lookup("#destTransportBox")
                    .queryAs(ComboBox.class);
            box.setValue(box.getItems().stream().filter(c -> c.id().equals("http")).findFirst().orElseThrow());
        });
        assertThat(robot.lookup("#destHostField").queryAs(TextField.class).isDisabled()).isTrue();
        MainWindowUiTest.screenshot(robot, robot.lookup("#destTransportBox").query().getScene().getRoot(),
                "33-transport");
        // Without the required URL the dialog explains and stays open.
        robot.interact(() -> ((javafx.scene.control.Button) dialog[0].getDialogPane().lookupButton(ButtonType.OK))
                .fire());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#destErrorLabel").queryAs(Label.class).getText()).contains("URL is required");

        robot.interact(() -> robot.lookup("#destTransport_url").queryAs(TextField.class)
                .setText("https://mirth.example.org/hl7"));
        dialog[0].resultProperty().addListener((o, a, b) -> saved[0] = b == null ? null : b.config());
        robot.interact(() -> ((javafx.scene.control.Button) dialog[0].getDialogPane().lookupButton(ButtonType.OK))
                .fire());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(saved[0]).isNotNull();
        assertThat(saved[0].transport()).isEqualTo("http");
        assertThat(saved[0].transportOptions()).containsEntry("url", "https://mirth.example.org/hl7")
                .containsEntry("method", "POST");
        assertThat(saved[0].address()).isEqualTo("https://mirth.example.org/hl7");
    }
}
