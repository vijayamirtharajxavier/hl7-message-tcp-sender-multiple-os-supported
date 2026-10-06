package io.hl7sender.app;

import io.hl7sender.core.alert.Alert;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shows alerts to the person at the desk: a notification in the corner of the main window, and an operating
 * system notification (Windows action center, macOS Notification Center, Linux notification daemon) where the
 * platform supports it. OS notifications are best effort: any failure falls back to the in-app one.
 */
final class Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(Notifier.class);
    private static final Duration SHOW_FOR = Duration.seconds(8);

    private final Window owner;
    private Popup current;
    // Used on the AWT event thread; read on the FX thread.
    private volatile TrayIcon trayIcon;
    private volatile boolean trayFailed;
    private String lastShown = "";

    Notifier(Window owner) {
        this.owner = owner;
    }

    /** The title and text of the last notification, for tests. */
    String lastShown() {
        return lastShown;
    }

    /** Shows {@code alert}; must be called on the FX thread. */
    void show(Alert alert) {
        lastShown = alert.title() + "\n" + alert.message();
        showToast(alert);
        if (!Boolean.getBoolean("hl7sender.noOsNotifications")) {
            showOsNotification(alert);
        }
    }

    private void showToast(Alert alert) {
        if (owner == null || !owner.isShowing()) {
            return;
        }
        if (current != null) {
            current.hide();
        }
        Label title = new Label(alert.title());
        title.getStyleClass().add("toast-title");
        title.setWrapText(true);
        Label text = new Label(alert.message());
        text.setWrapText(true);
        VBox box = new VBox(4, title, text);
        box.setId("toast");
        box.getStyleClass().add("toast");
        box.setMaxWidth(420);
        box.setPrefWidth(420);
        box.setAccessibleRole(AccessibleRole.TEXT);
        box.setAccessibleText(alert.title() + ". " + alert.message());
        box.getStylesheets().add(Styles.stylesheet());
        if (Styles.theme() == io.hl7sender.core.config.AppSettings.Ui.Theme.DARK) {
            box.getStylesheets().add(Styles.darkStylesheet());
        }
        Popup popup = new Popup();
        popup.getContent().add(box);
        popup.setAutoHide(false);
        box.setOnMouseClicked(e -> popup.hide());
        Bounds b = owner.getScene().getRoot().localToScreen(owner.getScene().getRoot().getBoundsInLocal());
        double x = b == null ? owner.getX() : b.getMaxX() - 440;
        double y = b == null ? owner.getY() : b.getMaxY() - 140;
        popup.show(owner, x, y);
        current = popup;
        PauseTransition hide = new PauseTransition(SHOW_FOR);
        hide.setOnFinished(e -> popup.hide());
        hide.play();
    }

    private void showOsNotification(Alert alert) {
        if (trayFailed || GraphicsEnvironment.isHeadless()) {
            return;
        }
        // AWT must not run on the JavaFX thread (it can deadlock on macOS).
        EventQueue.invokeLater(() -> {
            try {
                if (trayIcon == null) {
                    if (!SystemTray.isSupported()) {
                        trayFailed = true;
                        return;
                    }
                    BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = image.createGraphics();
                    g.setColor(new java.awt.Color(0x1565c0));
                    g.fillOval(1, 1, 14, 14);
                    g.dispose();
                    trayIcon = new TrayIcon(image, "HL7 Sender");
                    trayIcon.setImageAutoSize(true);
                    SystemTray.getSystemTray().add(trayIcon);
                }
                TrayIcon.MessageType type = switch (alert.severity()) {
                    case INFO -> TrayIcon.MessageType.INFO;
                    case WARNING -> TrayIcon.MessageType.WARNING;
                    case CRITICAL -> TrayIcon.MessageType.ERROR;
                };
                trayIcon.displayMessage(alert.title(), alert.message(), type);
            } catch (Exception | LinkageError e) {
                trayFailed = true;
                LOG.info("Operating-system notifications are unavailable ({}); using in-app notifications",
                        e.toString());
            }
        });
    }

    /** Removes the tray icon, if one was added. */
    void close() {
        Popup popup = current;
        if (popup != null) {
            if (javafx.application.Platform.isFxApplicationThread()) {
                popup.hide();
            } else {
                javafx.application.Platform.runLater(popup::hide);
            }
        }
        if (trayIcon != null) {
            TrayIcon icon = trayIcon;
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(icon));
        }
    }
}
