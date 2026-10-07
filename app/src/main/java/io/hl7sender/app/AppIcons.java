package io.hl7sender.app;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import javafx.stage.Window;

/** The app icon at several sizes, set on every window so taskbars, docks and window switchers show it. */
final class AppIcons {

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};
    private static List<Image> images;

    private AppIcons() {
    }

    /** The icon images, loaded once from the app's resources (written by IconGen). */
    static synchronized List<Image> images() {
        if (images == null) {
            List<Image> list = new ArrayList<>();
            for (int size : SIZES) {
                try (InputStream in = AppIcons.class.getResourceAsStream("icon/hl7-sender-" + size + ".png")) {
                    if (in != null) {
                        list.add(new Image(in));
                    }
                } catch (java.io.IOException e) {
                    // A missing size only means the platform scales another one.
                }
            }
            images = List.copyOf(list);
        }
        return images;
    }

    /** Sets the icon on a stage that has none yet. */
    static void apply(Window window) {
        if (window instanceof Stage stage && stage.getIcons().isEmpty()) {
            stage.getIcons().setAll(images());
        }
    }
}
