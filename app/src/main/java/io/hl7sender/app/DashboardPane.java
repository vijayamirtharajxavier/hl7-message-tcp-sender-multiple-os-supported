package io.hl7sender.app;

import io.hl7sender.core.alert.AlertMonitor;
import io.hl7sender.core.monitor.DestinationStats;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.DestinationState;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueListener;
import io.hl7sender.core.send.SendOutcome;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.geometry.Orientation;
import javafx.util.Duration;

/**
 * Live overview of every destination: connection state, queue depth, in-flight and dead-letter counts,
 * throughput, ACK/NACK rates and latency over a chosen window, plus recent alerts.
 */
final class DashboardPane extends BorderPane {

    /** Time windows for the statistics. */
    enum Window {
        MINUTES_15("dashboard.window.15m", java.time.Duration.ofMinutes(15)),
        HOUR_1("dashboard.window.1h", java.time.Duration.ofHours(1)),
        HOURS_24("dashboard.window.24h", java.time.Duration.ofHours(24));

        private final String key;
        private final java.time.Duration duration;

        Window(String key, java.time.Duration duration) {
            this.key = key;
            this.duration = duration;
        }

        @Override
        public String toString() {
            return Messages.get(key);
        }
    }

    /** Everything one card shows, gathered off the FX thread. */
    private record Row(DestinationConfig destination, DestinationState state, Map<MessageStatus, Integer> counts,
                       DestinationStats stats) {
    }

    private static final int MAX_BARS = 60;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AppContext context;
    private final DeliveryEngine engine;
    private final ComboBox<Window> windowBox = new ComboBox<>(FXCollections.observableArrayList(Window.values()));
    private final Label summary = new Label();
    private final FlowPane cards = new FlowPane(12, 12);
    private final ListView<String> alertList = new ListView<>();
    private final Timeline ticker = new Timeline(new KeyFrame(Duration.seconds(5), e -> refresh()));
    private final PauseTransition debounce = new PauseTransition(Duration.millis(400));
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final QueueListener listener;
    private final Consumer<AlertMonitor.Raised> alertListener;
    private volatile boolean closed;

    DashboardPane(AppContext context) {
        this.context = context;
        this.engine = context.engine().orElse(null);
        if (engine == null) {
            Label unavailable = new Label(context.queueUnavailableReason());
            unavailable.setWrapText(true);
            unavailable.setPadding(new Insets(20));
            setCenter(unavailable);
            listener = null;
            alertListener = null;
            return;
        }
        windowBox.setId("dashWindowBox");
        windowBox.setValue(Window.HOUR_1);
        windowBox.setAccessibleText(Messages.get("dashboard.window.accessible"));
        windowBox.valueProperty().addListener((o, a, b) -> refresh());
        summary.setId("dashSummary");
        summary.getStyleClass().add("section-title");
        Label windowLabel = Fields.label(Messages.get("dashboard.window"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, summary, spacer, windowLabel, windowBox);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10, 10, 6, 10));

        cards.setId("dashCards");
        cards.setPadding(new Insets(4, 10, 10, 10));
        ScrollPane scroll = new ScrollPane(cards);
        scroll.setFitToWidth(true);

        alertList.setId("dashAlerts");
        alertList.setPlaceholder(new Label(Messages.get("dashboard.alerts.none")));
        alertList.setAccessibleText(Messages.get("dashboard.alerts"));
        VBox alertsBox = new VBox(4, Styles.sectionTitle(Messages.get("dashboard.alerts")), alertList);
        VBox.setVgrow(alertList, Priority.ALWAYS);
        alertsBox.setPadding(new Insets(6, 10, 10, 10));

        SplitPane split = new SplitPane(scroll, alertsBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.72);
        setTop(top);
        setCenter(split);

        ticker.setCycleCount(Timeline.INDEFINITE);
        debounce.setOnFinished(e -> refresh());
        listener = new QueueListener() {
            @Override
            public void onQueueChanged(long destinationId) {
                Platform.runLater(DashboardPane.this::scheduleRefresh);
            }

            @Override
            public void onStateChanged(DestinationState state) {
                Platform.runLater(DashboardPane.this::scheduleRefresh);
            }

            @Override
            public void onDestinationsChanged() {
                Platform.runLater(DashboardPane.this::scheduleRefresh);
            }
        };
        engine.addListener(listener);
        alertListener = r -> Platform.runLater(this::showAlerts);
        context.alerts().ifPresent(a -> a.addListener(alertListener));
        showAlerts();
    }

    /** Starts periodic refreshes (while the tab is visible). */
    void activate() {
        if (engine != null && !closed) {
            refresh();
            ticker.play();
        }
    }

    void deactivate() {
        ticker.stop();
    }

    void shutdown() {
        closed = true;
        ticker.stop();
        debounce.stop();
        if (engine != null) {
            engine.removeListener(listener);
        }
    }

    private void scheduleRefresh() {
        if (!closed && ticker.getStatus() == javafx.animation.Animation.Status.RUNNING) {
            debounce.playFromStart();
        }
    }

    /** Gathers statistics in the background and redraws the cards. */
    void refresh() {
        if (engine == null || closed || !refreshing.compareAndSet(false, true)) {
            return;
        }
        java.time.Duration window = windowBox.getValue().duration;
        context.executor().submit(() -> {
            try {
                List<Row> rows = new ArrayList<>();
                for (DestinationConfig d : engine.destinations()) {
                    rows.add(new Row(d, engine.state(d.id()), engine.store().counts(d.id()),
                            engine.stats(d.id(), window)));
                }
                Platform.runLater(() -> show(rows));
            } catch (QueueException e) {
                // Closed or unavailable; the next refresh tries again.
            } finally {
                refreshing.set(false);
            }
        });
    }

    private void show(List<Row> rows) {
        if (closed) {
            return;
        }
        int pending = 0;
        int inFlight = 0;
        int dead = 0;
        List<VBox> built = new ArrayList<>();
        for (Row r : rows) {
            pending += pending(r.counts());
            inFlight += r.counts().getOrDefault(MessageStatus.IN_FLIGHT, 0);
            dead += r.counts().getOrDefault(MessageStatus.DEAD_LETTER, 0);
            built.add(card(r));
        }
        summary.setText(rows.isEmpty() ? Messages.get("dashboard.empty")
                : Messages.get("dashboard.summary", rows.size(), pending, inFlight, dead));
        cards.getChildren().setAll(built);
    }

    private VBox card(Row r) {
        DestinationConfig d = r.destination();
        DestinationStats s = r.stats();
        long id = d.id();
        Label name = new Label(d.name());
        name.getStyleClass().add("section-title");
        Label badge = new Label(stateLabel(r.state().status()));
        badge.setId("dash-" + id + "-state");
        Styles.badge(badge, severity(r.state().status()));
        badge.setStyle("-fx-font-size: 0.8em; -fx-padding: 1 6 1 6;");
        badge.setTooltip(new Tooltip(r.state().detail()));
        HBox header = new HBox(8, name, badge);
        header.setAlignment(Pos.CENTER_LEFT);
        if (d.tls().enabled()) {
            Label tls = new Label("TLS");
            Styles.badge(tls, SendOutcome.Severity.SUCCESS);
            tls.setStyle("-fx-font-size: 0.8em; -fx-padding: 1 6 1 6;");
            header.getChildren().add(tls);
        }
        Label address = new Label(d.displayAddress()
                + (r.state().connected() ? "  " + Messages.get("dashboard.connected") : ""));
        address.setId("dash-" + id + "-address");
        address.getStyleClass().add("field-label");

        int depth = pending(r.counts());
        int inFlight = r.counts().getOrDefault(MessageStatus.IN_FLIGHT, 0);
        int dead = r.counts().getOrDefault(MessageStatus.DEAD_LETTER, 0);
        String throughput = String.format(Messages.locale(), "%.1f", s.throughputPerMinute());
        String accept = percent(s.acceptRate());
        String nack = percent(s.nackRate());
        String latency = s.avgLatencyMs() < 0 ? "-" : Messages.get("dashboard.latency.value", s.avgLatencyMs(),
                s.maxLatencyMs());

        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(2);
        metric(grid, 0, 0, id, "depth", Messages.get("dashboard.depth"), String.valueOf(depth));
        metric(grid, 1, 0, id, "inflight", Messages.get("dashboard.inflight"), String.valueOf(inFlight));
        metric(grid, 2, 0, id, "dead", Messages.get("dashboard.dead"), String.valueOf(dead));
        metric(grid, 0, 2, id, "throughput", Messages.get("dashboard.throughput"), throughput);
        metric(grid, 1, 2, id, "accepted", Messages.get("dashboard.accepted"), accept);
        metric(grid, 2, 2, id, "nack", Messages.get("dashboard.nack"), nack);
        metric(grid, 0, 4, id, "failures", Messages.get("dashboard.failures"), String.valueOf(s.failures()));
        metric(grid, 1, 4, id, "latency", Messages.get("dashboard.latency"), latency);
        GridPane.setColumnSpan(grid.lookup("#dash-" + id + "-latency"), 2);

        HBox spark = sparkline(s.acceptedPerMinute());
        spark.setId("dash-" + id + "-sparkline");
        Label sparkLabel = Fields.label(Messages.get("dashboard.sparkline", s.attempts()));

        VBox card = new VBox(6, header, address, grid, sparkLabel, spark);
        card.setId("dashCard-" + id);
        card.getStyleClass().add("dashboard-card");
        card.setPrefWidth(340);
        card.setFocusTraversable(true);
        card.setAccessibleRole(AccessibleRole.TEXT);
        card.setAccessibleText(Messages.get("dashboard.card.accessible", d.name(), stateLabel(r.state().status()),
                depth, inFlight, dead, throughput, accept, nack, latency));
        return card;
    }

    private static void metric(GridPane grid, int col, int row, long id, String key, String title, String value) {
        Label t = Fields.label(title);
        Label v = new Label(value);
        v.setId("dash-" + id + "-" + key);
        v.getStyleClass().add("metric-value");
        t.setLabelFor(v);
        grid.add(t, col, row);
        grid.add(v, col, row + 1);
    }

    /** Bars for accepted messages per minute, merged into at most {@value #MAX_BARS} bars. */
    private static HBox sparkline(List<Integer> perMinute) {
        int n = perMinute.size();
        int group = Math.max(1, (n + MAX_BARS - 1) / MAX_BARS);
        List<Integer> bars = new ArrayList<>();
        for (int i = 0; i < n; i += group) {
            int sum = 0;
            for (int j = i; j < Math.min(n, i + group); j++) {
                sum += perMinute.get(j);
            }
            bars.add(sum);
        }
        int max = Math.max(1, bars.stream().mapToInt(Integer::intValue).max().orElse(1));
        HBox box = new HBox(1);
        box.setAlignment(Pos.BOTTOM_LEFT);
        box.setMinHeight(32);
        box.setPrefHeight(32);
        double width = Math.max(2, 300.0 / Math.max(1, bars.size()) - 1);
        for (int b : bars) {
            Rectangle r = new Rectangle(width, Math.max(1, 30.0 * b / max));
            r.getStyleClass().add("sparkline-bar");
            box.getChildren().add(r);
        }
        return box;
    }

    private void showAlerts() {
        List<String> lines = new ArrayList<>();
        context.alerts().ifPresent(a -> {
            for (AlertMonitor.Raised r : a.recent()) {
                lines.add(TIME.format(r.alert().at().atZone(ZoneId.systemDefault())) + "  [" + r.alert().severity()
                        + "] " + r.alert().title()
                        + (r.errors().isEmpty() ? "" : "  (" + String.join("; ", r.errors()) + ")"));
            }
        });
        alertList.getItems().setAll(lines);
    }

    private static int pending(Map<MessageStatus, Integer> c) {
        return c.getOrDefault(MessageStatus.QUEUED, 0) + c.getOrDefault(MessageStatus.IN_FLIGHT, 0)
                + c.getOrDefault(MessageStatus.RETRY_PENDING, 0);
    }

    private static String percent(double rate) {
        return rate < 0 ? "-" : String.format(Messages.locale(), "%.1f%%", rate * 100);
    }

    static String stateLabel(DestinationState.Status s) {
        return Messages.get("state." + s.name());
    }

    static SendOutcome.Severity severity(DestinationState.Status s) {
        return switch (s) {
            case IDLE, SENDING -> SendOutcome.Severity.SUCCESS;
            case WAITING_RETRY, PAUSED -> SendOutcome.Severity.WARNING;
            case CIRCUIT_OPEN, ERROR -> SendOutcome.Severity.FAILURE;
            case STOPPED -> null;
        };
    }
}
