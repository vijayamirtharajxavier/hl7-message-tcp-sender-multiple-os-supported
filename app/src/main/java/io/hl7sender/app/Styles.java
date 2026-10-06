package io.hl7sender.app;

import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.send.SendOutcome;
import java.net.URL;
import java.util.List;
import java.util.Objects;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.text.Font;
import javafx.stage.Window;

/** CSS helpers. */
final class Styles {

    static final String BADGE = "badge";
    static final String SUCCESS = "badge-success";
    static final String WARNING = "badge-warning";
    static final String FAILURE = "badge-failure";
    static final String NEUTRAL = "badge-neutral";

    /** Preferred monospace fonts, per platform; JavaFX CSS has no font fallback list, so pick one in code. */
    private static final List<String> MONO_CANDIDATES =
            List.of("Consolas", "Menlo", "SF Mono", "DejaVu Sans Mono", "Liberation Mono", "Courier New");

    private static String monoFamily;
    private static volatile AppSettings.Ui.Theme theme = AppSettings.Ui.Theme.LIGHT;
    private static boolean watching;

    private Styles() {
    }

    /** Uses the best available monospace font for {@code node}. */
    static void mono(Node node) {
        node.setStyle("-fx-font-family: '" + monoFamily() + "';");
    }

    private static synchronized String monoFamily() {
        if (monoFamily == null) {
            List<String> installed = Font.getFamilies();
            monoFamily = MONO_CANDIDATES.stream().filter(installed::contains).findFirst().orElse("Monospaced");
        }
        return monoFamily;
    }

    static String stylesheet() {
        return resource("app.css");
    }

    static String darkStylesheet() {
        return resource("dark.css");
    }

    private static String resource(String name) {
        URL url = Objects.requireNonNull(Styles.class.getResource(name), name);
        return url.toExternalForm();
    }

    static AppSettings.Ui.Theme theme() {
        return theme;
    }

    /** Switches every open window (and every window opened later) to {@code newTheme}. */
    static void setTheme(AppSettings.Ui.Theme newTheme) {
        theme = newTheme == null ? AppSettings.Ui.Theme.LIGHT : newTheme;
        for (Window w : Window.getWindows()) {
            if (w.getScene() != null) {
                apply(w.getScene());
            }
        }
    }

    /** Applies the current theme to windows as they open, including dialogs and alerts. Call once at startup. */
    static synchronized void watchWindows() {
        if (watching) {
            return;
        }
        watching = true;
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window w : change.getAddedSubList()) {
                    if (w.getScene() != null) {
                        apply(w.getScene());
                    }
                    w.sceneProperty().addListener((o, a, scene) -> {
                        if (scene != null) {
                            apply(scene);
                        }
                    });
                }
            }
        });
    }

    /** Sets the scene's stylesheets to the base sheet plus the current theme's. */
    static void apply(Scene scene) {
        String base = stylesheet();
        String dark = darkStylesheet();
        scene.getStylesheets().removeAll(base, dark);
        scene.getStylesheets().add(base);
        if (theme == AppSettings.Ui.Theme.DARK) {
            scene.getStylesheets().add(dark);
        }
    }

    /** Sets a badge label's colour to match {@code severity}, or neutral when {@code null}. */
    static void badge(Label label, SendOutcome.Severity severity) {
        label.getStyleClass().removeAll(SUCCESS, WARNING, FAILURE, NEUTRAL);
        if (!label.getStyleClass().contains(BADGE)) {
            label.getStyleClass().add(BADGE);
        }
        String style = severity == null ? NEUTRAL : switch (severity) {
            case SUCCESS -> SUCCESS;
            case WARNING -> WARNING;
            case FAILURE -> FAILURE;
        };
        label.getStyleClass().add(style);
    }

    static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }
}
