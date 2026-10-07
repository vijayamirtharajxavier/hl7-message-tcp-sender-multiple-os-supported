package io.hl7sender.app;

import javafx.application.Platform;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Puts a maximized window back to maximized when a modal dialog it owns closes. On Linux, JavaFX locks the owner's
 * size while a modal dialog is open, and window managers then un-maximize it.
 */
final class KeepMaximized {

    private KeepMaximized() {
    }

    /** Called for every window as it opens. */
    static void watch(Window window) {
        if (!(window instanceof Stage dialog) || dialog.getModality() == Modality.NONE
                || !(dialog.getOwner() instanceof Stage owner) || !owner.isMaximized()) {
            return;
        }
        dialog.addEventHandler(WindowEvent.WINDOW_HIDDEN, e -> Platform.runLater(() -> {
            if (owner.isShowing() && !owner.isMaximized()) {
                owner.setMaximized(true);
            }
        }));
    }
}
