package io.hl7sender.app;

import io.hl7sender.core.AppInfo;
import io.hl7sender.core.config.AppPaths;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JavaFX application: one main window with the sender and the test listener. */
public final class Hl7SenderApp extends Application {

    /** Start, show the window, then exit with status 0. Used by CI to prove the UI starts on each OS. */
    public static final String SMOKE_TEST_ARG = "--smoke-test";

    private static final Logger LOG = LoggerFactory.getLogger(Hl7SenderApp.class);

    private AppContext context;
    private MainWindow window;

    @Override
    public void start(Stage stage) {
        context = new AppContext(AppPaths.detect());
        Messages.setLanguage(context.settings().ui().language());
        Styles.watchWindows();
        Styles.setTheme(context.settings().ui().theme());
        if (context.signInRequired() && !new SignInDialog(null, context).showAndWait().orElse(false)) {
            LOG.info("Sign-in cancelled");
            Platform.exit();
            return;
        }
        window = new MainWindow(context, getHostServices());
        Scene scene = new Scene(window, 1280, 860);
        Styles.apply(scene);
        stage.setTitle(AppInfo.NAME + " " + AppInfo.version());
        AppIcons.apply(stage);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.show();
        LOG.info("{} {} started (Java {}, {}), logs in {}", AppInfo.NAME, AppInfo.version(),
                System.getProperty("java.version"), System.getProperty("os.name"), context.paths().logDir());

        if (getParameters().getRaw().contains(SMOKE_TEST_ARG)) {
            PauseTransition pause = new PauseTransition(Duration.seconds(1));
            pause.setOnFinished(e -> {
                System.out.println("SMOKE TEST OK: window shown");
                Platform.exit();
            });
            pause.play();
        } else {
            Platform.runLater(window::showWelcomeWizardIfFirstRun);
            window.checkForUpdatesInBackground(io.hl7sender.core.update.UpdateChecker.forThisBuild());
        }
    }

    @Override
    public void stop() {
        if (window != null) {
            window.shutdown();
        }
        if (context != null) {
            context.close();
        }
        LOG.info("{} stopped", AppInfo.NAME);
    }
}
