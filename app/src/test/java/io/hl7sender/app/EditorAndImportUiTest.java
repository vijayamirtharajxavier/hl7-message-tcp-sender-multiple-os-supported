package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.MessageQuery;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.samples.SampleMessages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI tests for the editor, templates, bulk import and history. */
@ExtendWith(ApplicationExtension.class)
class EditorAndImportUiTest {

    private Path home;
    private AppContext context;
    private MainWindow window;
    private TestListener listener;
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();

    @Start
    void start(Stage stage) throws IOException {
        home = Files.createTempDirectory("hl7sender-p3-ui");
        context = new AppContext(new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs")));
        window = new MainWindow(context, null);
        Scene scene = new Scene(window, 1280, 860);
        scene.getStylesheets().add(Styles.stylesheet());
        stage.setScene(scene);
        stage.show();
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
    }

    @AfterEach
    void tearDown() {
        window.shutdown();
        context.close();
        listener.close();
    }

    private DeliveryEngine engine() {
        return context.engine().orElseThrow();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, condition::getAsBoolean);
        WaitForAsyncUtils.waitForFxEvents();
    }

    private void selectMainTab(FxRobot robot, String id) {
        robot.interact(() -> {
            TabPane tabs = robot.lookup("#mainTabs").queryAs(TabPane.class);
            for (Tab t : tabs.getTabs()) {
                if (id.equals(t.getId())) {
                    tabs.getSelectionModel().select(t);
                }
            }
        });
    }

    @Test
    void editorShowsFieldNamesForTheCaretPosition(FxRobot robot) throws Exception {
        CodeArea area = robot.lookup("#editor").queryAs(CodeArea.class);
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> area.moveTo(area.getText().indexOf("DOE^JANE") + 1));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#editorLocation").queryAs(Label.class).getText())
                .isEqualTo("PID-5.1 Patient Name > Family Name (XPN) = DOE");
        robot.interact(() -> area.moveTo(area.getText().indexOf("ADT^A01") + 1));
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#editorLocation").queryAs(Label.class).getText())
                .startsWith("MSH-9.1 Message Type > Message Code (MSG)");
        // Delimiters and segment names are styled.
        assertThat(area.getStyleOfChar(0)).contains("hl7-segment");
        assertThat(area.getStyleOfChar(3)).contains("hl7-field-sep");
        MainWindowUiTest.screenshot(robot, window, "08-editor-highlighting");
    }

    @Test
    void templateVariablesAreExpandedWhenSending(FxRobot robot) throws Exception {
        robot.interact(() -> {
            robot.lookup("#hostField").queryAs(TextField.class).setText("127.0.0.1");
            robot.lookup("#portField").queryAs(TextField.class).setText(String.valueOf(listener.port()));
            robot.lookup("#editor").queryAs(CodeArea.class).replaceText(SampleMessages.templates().get(0).text());
        });
        robot.clickOn("#sendButton");
        await(() -> "ACCEPTED".equals(robot.lookup("#outcomeBadge").queryAs(Label.class).getText()));
        assertThat(received).singleElement().satisfies(m -> {
            assertThat(m.payload()).doesNotContain("${");
            assertThat(m.messageType()).isEqualTo("ADT^A01");
        });
        // The editor keeps the template for the next send.
        assertThat(robot.lookup("#editor").queryAs(CodeArea.class).getText()).contains("${RANDOM_MRN}");
    }

    @Test
    void bulkImportDialogQueuesABatchFileAndTracksDelivery(FxRobot robot) throws Exception {
        engine().saveDestination(DestinationConfig.of("Bulk target", "127.0.0.1", listener.port()));
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            messages.add(SampleMessages.all().get(0).text().replace("MSG00001", "BULK" + i));
        }
        Path file = Files.writeString(home.resolve("batch.hl7"), MessageSplitter.toBatchFile(messages));

        BulkImportDialog[] dialog = new BulkImportDialog[1];
        robot.interact(() -> {
            dialog[0] = new BulkImportDialog(window.getScene().getWindow(), context, s -> { }, null);
            dialog[0].addFiles(List.of(file));
            dialog[0].show();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#importSummary").queryAs(Label.class).getText()).startsWith("50 message(s)");
        robot.clickOn("#importStartButton");
        await(() -> robot.lookup("#importDeliveryLabel").queryAs(Label.class).getText().endsWith("complete"));
        assertThat(robot.lookup("#importDeliveryLabel").queryAs(Label.class).getText())
                .isEqualTo("Delivered 50 of 50 - complete");
        MainWindowUiTest.screenshot(robot, dialog[0].getScene().getRoot(), "09-bulk-import");
        robot.interact(dialog[0]::close);

        assertThat(received).hasSize(50);
        assertThat(received.get(0).controlId()).isEqualTo("BULK0");
        assertThat(received.get(49).controlId()).isEqualTo("BULK49");
    }

    @Test
    void historySearchesAcrossDestinationsAndBatches(FxRobot robot) throws Exception {
        DestinationConfig d = engine().saveDestination(DestinationConfig.of("Hist", "127.0.0.1", listener.port()));
        List<io.hl7sender.core.queue.BulkItem> items = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            items.add(new io.hl7sender.core.queue.BulkItem(SampleMessages.all().get(i % 3).text(), "t#" + i));
        }
        var result = engine().enqueueAll(d.id(), items, io.hl7sender.core.send.SendOptions.DEFAULTS, null,
                io.hl7sender.core.queue.BulkProgress.NONE);
        await(() -> engine().store().count(MessageQuery.all().withStatuses(
                java.util.EnumSet.of(MessageStatus.ACKNOWLEDGED))) == 12);

        selectMainTab(robot, "historyTab");
        TableView<?> table = robot.lookup("#historyTable").queryAs(TableView.class);
        await(() -> table.getItems().size() == 12);
        robot.interact(() -> robot.lookup("#historyType").queryAs(TextField.class).setText("A03"));
        robot.clickOn("#historySearchButton");
        await(() -> table.getItems().size() == 4);
        assertThat(robot.lookup("#historyResultLabel").queryAs(Label.class).getText()).isEqualTo("4 message(s)");

        robot.interact(() -> {
            robot.lookup("#historyType").queryAs(TextField.class).clear();
            robot.lookup("#historyBatch").queryAs(TextField.class).setText(result.batchId());
        });
        robot.clickOn("#historySearchButton");
        await(() -> table.getItems().size() == 12);
        robot.interact(() -> table.getSelectionModel().selectFirst());
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#historyMessageText").queryAs(CodeArea.class).getText()).startsWith("MSH|");
        MainWindowUiTest.screenshot(robot, window, "10-history");
    }

    @Test
    void destinationDialogSavesRateLimitAndWatchFolder(FxRobot robot) throws Exception {
        Path inbox = Files.createDirectories(home.resolve("inbox"));
        selectMainTab(robot, "queueTab");
        robot.clickOn("#addDestinationButton");
        WaitForAsyncUtils.waitForFxEvents();
        robot.interact(() -> {
            robot.lookup("#destNameField").queryAs(TextField.class).setText("Throttled");
            robot.lookup("#destMaxPerSecondField").queryAs(TextField.class).setText("50");
            robot.lookup("#destWatchFolderField").queryAs(TextField.class).setText(inbox.toString());
        });
        MainWindowUiTest.screenshot(robot, robot.lookup("#destOkButton").query().getScene().getRoot(),
                "11-destination-dialog-rate-limit");
        robot.clickOn("#destOkButton");
        await(() -> engine().destinations().size() == 1);
        DestinationConfig saved = engine().destinations().get(0);
        assertThat(saved.maxPerSecond()).isEqualTo(50);
        assertThat(saved.watchFolder()).isEqualTo(inbox.toString());
    }
}
