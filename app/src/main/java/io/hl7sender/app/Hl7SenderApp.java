package io.hl7sender.app;

import io.hl7sender.core.AppInfo;
import io.hl7sender.core.config.AppPaths;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.stage.Screen;
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
        // Opens maximized, filling the screen's usable area at any resolution. The restored (un-maximized) size
        // is 1280 x 860, or 90% of the screen if that is smaller, so it never extends past a small display.
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(window, Math.min(1280, screen.getWidth() * 0.9),
                Math.min(860, screen.getHeight() * 0.9));
        Styles.apply(scene);
        stage.setTitle(AppInfo.NAME + " " + AppInfo.version());
        AppIcons.apply(stage);
        stage.setMinWidth(Math.min(900, screen.getWidth()));
        stage.setMinHeight(Math.min(600, screen.getHeight()));
        stage.setScene(scene);
        stage.show();
        // Maximize once the window is on screen: Linux window managers ignore a maximize requested before the
        // window is mapped, and JavaFX does not resend it because the property is then already true.
        Platform.runLater(() -> stage.setMaximized(true));
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
            // The wizard is modal; opening it before the window manager has maximized the main window leaves the
            // main window at its normal size.
            whenMaximized(stage, screen, window::showWelcomeWizardIfFirstRun);
            window.checkForUpdatesInBackground(io.hl7sender.core.update.UpdateChecker.forThisBuild());
        }
    }

    /** Runs {@code action} once the stage fills the screen's width, or after a second at the latest. */
    private static void whenMaximized(Stage stage, Rectangle2D screen, Runnable action) {
        AtomicBoolean done = new AtomicBoolean();
        Runnable once = () -> {
            if (done.compareAndSet(false, true)) {
                Platform.runLater(action);
            }
        };
        ChangeListener<Number> listener = (o, a, width) -> {
            if (width.doubleValue() >= screen.getWidth() - 1) {
                once.run();
            }
        };
        stage.widthProperty().addListener(listener);
        PauseTransition fallback = new PauseTransition(Duration.seconds(1));
        fallback.setOnFinished(e -> once.run());
        fallback.play();
        if (stage.getWidth() >= screen.getWidth() - 1) {
            once.run();
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
