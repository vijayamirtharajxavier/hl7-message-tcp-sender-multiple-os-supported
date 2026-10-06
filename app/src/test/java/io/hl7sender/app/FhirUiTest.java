package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI test for the Sender tab's FHIR preview. */
@ExtendWith(ApplicationExtension.class)
class FhirUiTest {

    private AppContext context;
    private MainWindow window;

    @Start
    void start(Stage stage) throws IOException {
        Path home = Files.createTempDirectory("hl7sender-fhir-ui");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
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

    @Test
    void fhirTabShowsTheBundleForTheEditorMessage(FxRobot robot) throws Exception {
        robot.interact(() -> {
            Tab tab = robot.lookup(".tab-pane").queryAll().stream()
                    .map(n -> (javafx.scene.control.TabPane) n).flatMap(p -> p.getTabs().stream())
                    .filter(t -> "fhirTab".equals(t.getId())).findFirst().orElseThrow();
            tab.getTabPane().getSelectionModel().select(tab);
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#fhirArea").queryAs(TextArea.class).getText())
                .contains("\"resourceType\" : \"Patient\"");
        assertThat(robot.lookup("#fhirSummary").queryAs(Label.class).getText())
                .startsWith("As a FHIR R4 transaction Bundle: 1 Patient, 1 Encounter");
        MainWindowUiTest.screenshot(robot, window, "34-fhir");
    }
}
