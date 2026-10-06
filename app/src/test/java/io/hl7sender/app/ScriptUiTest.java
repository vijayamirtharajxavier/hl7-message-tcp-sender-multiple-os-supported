package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.queue.DestinationConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI test for the destination editor's Script tab. */
@ExtendWith(ApplicationExtension.class)
class ScriptUiTest {

    private AppContext context;
    private MainWindow window;

    @Start
    void start(Stage stage) throws IOException {
        Path home = Files.createTempDirectory("hl7sender-script-ui");
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
    void scriptTabTestsTheScriptOnASampleMessage(FxRobot robot) throws Exception {
        robot.interact(() -> new DestinationDialog(window.getScene().getWindow(),
                DestinationConfig.of("Lab", "127.0.0.1", 2575), context.engine().orElseThrow()).show());
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            TabPane tabs = robot.lookup("#destTabs").queryAs(TabPane.class);
            tabs.getSelectionModel().select(tabs.getTabs().stream().filter(t -> "destScriptTab".equals(t.getId()))
                    .findFirst().orElseThrow());
        });
        robot.clickOn("#destScriptExample");
        assertThat(robot.lookup("#destScriptArea").queryAs(TextArea.class).getText()).contains("TESTFAC");
        robot.clickOn("#destScriptTest");
        String result = robot.lookup("#destScriptOutput").queryAs(TextArea.class).getText();
        assertThat(result).contains("|TESTFAC|").contains("log: MRN");
        MainWindowUiTest.screenshot(robot, robot.lookup("#destScriptArea").queryAs(TextArea.class).getScene()
                .getRoot(), "32-script");

        robot.interact(() -> {
            robot.lookup("#destScriptArea").queryAs(TextArea.class).setText("msg.set('PID3','x')");
            robot.lookup("#destScriptTest").queryButton().fire();
        });
        assertThat(robot.lookup("#destScriptOutput").queryAs(TextArea.class).getText()).contains("not a field");
        assertThat(DestinationDialog.testScript("filter('no')", "MSH|^~\\&|A|B|C|D|1||ADT^A01|1|P|2.5.1"))
                .contains("Filtered out (not queued): no");
        assertThat(DestinationDialog.testScript(" ", "x")).contains("No script");
    }
}
