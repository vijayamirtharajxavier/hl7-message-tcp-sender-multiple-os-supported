package io.hl7sender.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.config.SettingsStore;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.runtime.QueueOpener;
import io.hl7sender.core.runtime.QueueRuntime;
import io.hl7sender.core.secrets.SecretStores;
import io.hl7sender.core.send.Hl7Sender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.util.concurrent.TimeUnit;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Headless UI test for Tools > Queue database and for a window on a shared queue that another process delivers. */
@ExtendWith(ApplicationExtension.class)
class DatabaseUiTest {

    private Path home;
    private AppPaths paths;
    private AppContext context;
    private MainWindow window;
    private final DatabaseDialog[] dialog = new DatabaseDialog[1];

    @Start
    void start(Stage stage) throws IOException {
        home = Files.createTempDirectory("hl7sender-database-ui");
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

    @Test
    void sharedDatabaseIsChosenAndSaved(FxRobot robot) throws Exception {
        robot.interact(() -> {
            dialog[0] = new DatabaseDialog(window.getScene().getWindow(), context);
            dialog[0].show();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#databaseLocalRadio").queryAs(RadioButton.class).isSelected()).isTrue();
        assertThat(robot.lookup("#databaseCurrentLabel").queryAs(Label.class).getText()).contains("queue.db");
        assertThat(robot.lookup("#databaseUrlField").queryAs(TextField.class).isDisabled()).isTrue();

        robot.clickOn("#databaseSharedRadio");
        robot.interact(() -> robot.lookup("#databaseUrlField").queryAs(TextField.class).setText("postgres://x"));
        robot.clickOn("#databaseOkButton");
        assertThat(robot.lookup("#databaseStateLabel").queryAs(Label.class).getText())
                .contains("jdbc:postgresql://");
        robot.interact(() -> {
            robot.lookup("#databaseUrlField").queryAs(TextField.class).setText("jdbc:postgresql://127.0.0.1:1/q");
            robot.lookup("#databaseUserField").queryAs(TextField.class).setText("sender");
            robot.lookup("#databasePasswordField").queryAs(PasswordField.class).setText("s3cret");
        });
        robot.clickOn("#databaseTestButton");
        WaitForAsyncUtils.waitFor(20, TimeUnit.SECONDS, () -> Fx.call(() -> robot.lookup("#databaseStateLabel")
                .queryAs(Label.class).getText()).contains("Cannot open the PostgreSQL queue"));
        MainWindowUiTest.screenshot(robot, robot.lookup("#databaseUrlField").queryAs(TextField.class).getScene()
                .getRoot(), "40-queue-database");
        robot.clickOn("#databaseOkButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(dialog[0].isShowing()).isFalse();
        assertThat(dialog[0].changed()).isTrue();
        AppSettings.Database saved = new SettingsStore(paths.settingsFile()).load().database();
        assertThat(saved).isEqualTo(AppSettings.Database.postgres("jdbc:postgresql://127.0.0.1:1/q", "sender"));
        assertThat(context.secrets().get(QueueOpener.POSTGRES_PASSWORD)).contains("s3cret");

        // Back to the local database: the password is removed.
        robot.interact(() -> {
            dialog[0] = new DatabaseDialog(window.getScene().getWindow(), context);
            dialog[0].show();
        });
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(robot.lookup("#databaseSharedRadio").queryAs(RadioButton.class).isSelected()).isTrue();
        assertThat(robot.lookup("#databasePasswordField").queryAs(PasswordField.class).getPromptText())
                .contains("keychain");
        robot.clickOn("#databaseLocalRadio");
        robot.clickOn("#databaseOkButton");
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(context.settings().database().postgres()).isFalse();
        assertThat(context.secrets().get(QueueOpener.POSTGRES_PASSWORD)).isEmpty();
    }

    @Test
    void windowOnASharedQueueWorksWhileAnotherComputerDelivers() throws Exception {
        String baseUrl = System.getenv("HL7SENDER_TEST_POSTGRES_URL");
        Assumptions.assumeTrue(baseUrl != null && baseUrl.startsWith("jdbc:postgresql:"), "no PostgreSQL test server");
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + "currentSchema=hl7s_app";
        String user = System.getenv().getOrDefault("HL7SENDER_TEST_POSTGRES_USER", "");
        String password = System.getenv().getOrDefault("HL7SENDER_TEST_POSTGRES_PASSWORD", "");
        // Each test class has its own schema, so the modules' tests can run at the same time.
        try (Connection c = DriverManager.getConnection(baseUrl, user, password);
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS hl7s_app CASCADE");
            st.execute("CREATE SCHEMA hl7s_app");
        }
        AppSettings settings = AppSettings.defaults().withDatabase(AppSettings.Database.postgres(url, user));
        Path other = home.resolve("other");
        AppPaths otherPaths = new AppPaths(other.resolve("config"), other.resolve("data"), other.resolve("logs"));
        var otherSecrets = SecretStores.localFile(otherPaths.configDir());
        otherSecrets.put(QueueOpener.POSTGRES_PASSWORD, password);
        try (QueueRuntime delivering = QueueRuntime.start(otherPaths, () -> settings, otherSecrets, new Hl7Sender(),
                Clock.systemUTC()).orElseThrow()) {
            delivering.engine().saveDestination(DestinationConfig.of("Shared", "127.0.0.1", 2575));
            Path mine = home.resolve("mine");
            AppPaths myPaths = new AppPaths(mine.resolve("config"), mine.resolve("data"), mine.resolve("logs"));
            new SettingsStore(myPaths.settingsFile()).save(settings);
            SecretStores.localFile(myPaths.configDir()).put(QueueOpener.POSTGRES_PASSWORD, password);
            try (AppContext shared = new AppContext(myPaths)) {
                assertThat(shared.engine()).isPresent();
                assertThat(shared.delivering()).isFalse();
                assertThat(shared.queueLocation()).startsWith("jdbc:postgresql:");
                assertThat(shared.engine().get().destinations()).extracting(DestinationConfig::name)
                        .containsExactly("Shared");
            }
        }
    }
}
