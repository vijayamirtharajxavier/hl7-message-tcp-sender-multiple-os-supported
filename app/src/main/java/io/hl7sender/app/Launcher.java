package io.hl7sender.app;

import io.hl7sender.core.config.AppPaths;
import java.io.IOException;
import javafx.application.Application;

/**
 * Main class. It is kept separate from the {@link javafx.application.Application} subclass so the
 * app can start from a plain classpath (fat jar, jpackage), and so that log locations are set
 * before any logger is created.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        AppPaths paths = AppPaths.detect();
        try {
            paths.ensureExist();
        } catch (IOException e) {
            System.err.println("Warning: cannot create application directories: " + e.getMessage());
        }
        paths.publishLogDir();
        Application.launch(Hl7SenderApp.class, args);
    }
}
