package io.hl7sender.app;

import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.load.LatencyStats;
import io.hl7sender.core.load.LoadTest;
import io.hl7sender.core.load.LoadTestPlan;
import io.hl7sender.core.load.LoadTestReport;
import io.hl7sender.core.load.LoadTestSnapshot;
import io.hl7sender.core.load.LoadThresholds;
import io.hl7sender.core.mllp.MllpClientConfig;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOutcome;
import io.hl7sender.core.tls.TlsOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

/**
 * Load-tests a receiver: several connections sending at a target rate, with live throughput and latency charts,
 * percentiles, outcomes and optional pass/fail thresholds. Messages are sent directly, not queued.
 */
final class LoadTestPane extends BorderPane {

    /** Where the messages come from. */
    enum Source {
        EDITOR("load.source.editor"),
        FILES("load.source.files");

        private final String key;

        Source(String key) {
            this.key = key;
        }

        @Override
        public String toString() {
            return Messages.get(key);
        }
    }

    /** A saved destination, or the address typed in the form. */
    record Target(String label, DestinationConfig destination) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final int MAX_POINTS = 600;

    private final AppContext context;
    private final Consumer<String> status;
    private final Supplier<String> editorText;

    private final ComboBox<Target> targetBox = new ComboBox<>();
    private final TextField hostField = new TextField("127.0.0.1");
    private final TextField portField = Fields.integer("loadPortField", 2575, 5, 5);
    private final TextField ackTimeoutField = Fields.integer("loadAckTimeoutField", 30_000, 7, 6);
    private final ComboBox<Source> sourceBox = new ComboBox<>(FXCollections.observableArrayList(Source.values()));
    private final Button filesButton = new Button(Messages.get("load.files.choose"));
    private final Label filesLabel = new Label();
    private final TextField connectionsField = Fields.integer("loadConnectionsField", 4, 4, 4);
    private final TextField rateField = Fields.integer("loadRateField", 100, 6, 6);
    private final TextField countField = Fields.integer("loadCountField", 1000, 9, 8);
    private final TextField durationField = Fields.integer("loadDurationField", 0, 6, 6);
    private final CheckBox waitForAckBox = new CheckBox(Messages.get("load.waitForAck"));
    private final TextField maxErrorField = new TextField();
    private final TextField maxP95Field = new TextField();
    private final Button startButton = new Button(Messages.get("load.start"));
    private final Button exportButton = new Button(Messages.get("load.export"));
    private final Label stateBadge = new Label(Messages.get("load.state.idle"));

    private final Label elapsedValue = value("loadElapsed");
    private final Label sentValue = value("loadSent");
    private final Label acceptedValue = value("loadAccepted");
    private final Label failedValue = value("loadFailed");
    private final Label throughputValue = value("loadThroughput");
    private final Label latencyValue = value("loadLatency");
    private final Label thresholdValue = value("loadThresholds");
    private final XYChart.Series<Number, Number> rateSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> meanSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> maxSeries = new XYChart.Series<>();
    private final ListView<String> outcomesList = new ListView<>();
    private final List<LineChart<Number, Number>> charts = new ArrayList<>();

    private List<Path> files = List.of();
    private volatile LoadTest running;
    private LoadTestReport lastReport;
    private LoadThresholds lastThresholds = LoadThresholds.NONE;

    LoadTestPane(AppContext context, Consumer<String> status, Supplier<String> editorText) {
        this.context = context;
        this.status = status;
        this.editorText = editorText;
        setId("loadTestPane");
        targetBox.setId("loadTargetBox");
        hostField.setId("loadHostField");
        sourceBox.setId("loadSourceBox");
        filesButton.setId("loadFilesButton");
        waitForAckBox.setId("loadWaitForAckBox");
        maxErrorField.setId("loadMaxErrorField");
        maxP95Field.setId("loadMaxP95Field");
        startButton.setId("loadStartButton");
        exportButton.setId("loadExportButton");
        stateBadge.setId("loadStateBadge");
        outcomesList.setId("loadOutcomesList");

        setTop(buildForm());
        setCenter(buildResults());
        Styles.badge(stateBadge, null);
        refreshTargets();
        sourceBox.setValue(Source.EDITOR);
        waitForAckBox.setSelected(true);
        exportButton.setDisable(true);
    }

    private static Label value(String id) {
        Label l = new Label("-");
        l.setId(id);
        l.getStyleClass().add("stat-value");
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    private GridPane buildForm() {
        GridPane g = new GridPane();
        g.setHgap(8);
        g.setVgap(6);
        g.setPadding(new Insets(10));
        for (TextField f : List.of(portField, ackTimeoutField, connectionsField, rateField, countField,
                durationField)) {
            f.setMaxWidth(Region.USE_PREF_SIZE);
        }

        targetBox.valueProperty().addListener((o, a, t) -> {
            boolean saved = t != null && t.destination() != null;
            hostField.setDisable(saved);
            portField.setDisable(saved);
            ackTimeoutField.setDisable(saved);
            if (saved) {
                hostField.setText(t.destination().host());
                portField.setText(String.valueOf(t.destination().port()));
                ackTimeoutField.setText(String.valueOf(t.destination().ackTimeoutMs()));
            }
        });
        targetBox.setOnShowing(e -> refreshTargets());
        hostField.setPrefColumnCount(16);
        g.addRow(0, Fields.label(Messages.get("load.target")), targetBox, Fields.label(Messages.get("load.host")),
                hostField, Fields.label(Messages.get("load.port")), portField,
                Fields.label(Messages.get("load.ackTimeout")), ackTimeoutField);

        filesButton.setOnAction(e -> chooseFiles());
        filesButton.disableProperty().bind(sourceBox.valueProperty().isNotEqualTo(Source.FILES));
        HBox source = new HBox(8, sourceBox, filesButton, filesLabel);
        source.setAlignment(Pos.CENTER_LEFT);
        g.add(Fields.label(Messages.get("load.messages")), 0, 1);
        g.add(source, 1, 1, 7, 1);

        rateField.setTooltip(new Tooltip(Messages.get("load.rate.tip")));
        durationField.setTooltip(new Tooltip(Messages.get("load.duration.tip")));
        countField.setTooltip(new Tooltip(Messages.get("load.count.tip")));
        g.addRow(2, Fields.label(Messages.get("load.connections")), connectionsField,
                Fields.label(Messages.get("load.rate")), rateField, Fields.label(Messages.get("load.count")),
                countField, Fields.label(Messages.get("load.duration")), durationField);

        maxErrorField.setPrefColumnCount(5);
        maxErrorField.setPromptText(Messages.get("load.threshold.none"));
        maxP95Field.setPrefColumnCount(5);
        maxP95Field.setPromptText(Messages.get("load.threshold.none"));
        HBox thresholds = new HBox(8, Fields.label(Messages.get("load.maxError")), maxErrorField,
                Fields.label(Messages.get("load.maxP95")), maxP95Field);
        thresholds.setAlignment(Pos.CENTER_LEFT);
        g.add(waitForAckBox, 0, 3, 2, 1);
        g.add(thresholds, 2, 3, 6, 1);

        startButton.setDefaultButton(false);
        startButton.setOnAction(e -> {
            if (running == null) {
                start();
            } else {
                stop();
            }
        });
        exportButton.setOnAction(e -> chooseExport());
        HBox buttons = new HBox(8, startButton, exportButton, stateBadge);
        buttons.setAlignment(Pos.CENTER_LEFT);
        g.add(buttons, 0, 4, 8, 1);
        return g;
    }

    private BorderPane buildResults() {
        GridPane stats = new GridPane();
        stats.setHgap(16);
        stats.setVgap(4);
        stats.setPadding(new Insets(0, 10, 6, 10));
        stats.addRow(0, Fields.label(Messages.get("load.stat.elapsed")), elapsedValue,
                Fields.label(Messages.get("load.stat.sent")), sentValue,
                Fields.label(Messages.get("load.stat.accepted")), acceptedValue,
                Fields.label(Messages.get("load.stat.failed")), failedValue);
        stats.addRow(1, Fields.label(Messages.get("load.stat.throughput")), throughputValue,
                Fields.label(Messages.get("load.stat.latency")), latencyValue);
        GridPane.setColumnSpan(latencyValue, 5);
        stats.addRow(2, Fields.label(Messages.get("load.stat.thresholds")), thresholdValue);
        GridPane.setColumnSpan(thresholdValue, 7);

        rateSeries.setName(Messages.get("load.chart.rate"));
        meanSeries.setName(Messages.get("load.chart.mean"));
        maxSeries.setName(Messages.get("load.chart.max"));
        LineChart<Number, Number> rateChart = chart("loadRateChart", Messages.get("load.chart.rateAxis"));
        rateChart.getData().add(rateSeries);
        LineChart<Number, Number> latencyChart = chart("loadLatencyChart", Messages.get("load.chart.latencyAxis"));
        latencyChart.getData().add(meanSeries);
        latencyChart.getData().add(maxSeries);
        charts.add(rateChart);
        charts.add(latencyChart);
        HBox charts = new HBox(8, rateChart, latencyChart);
        HBox.setHgrow(rateChart, Priority.ALWAYS);
        HBox.setHgrow(latencyChart, Priority.ALWAYS);

        outcomesList.setPrefHeight(140);
        outcomesList.setPlaceholder(new Label(Messages.get("load.outcomes.empty")));
        VBox bottom = new VBox(4, Styles.sectionTitle(Messages.get("load.outcomes")), outcomesList);
        bottom.setPadding(new Insets(0, 10, 10, 10));

        BorderPane results = new BorderPane(charts);
        results.setTop(stats);
        results.setBottom(bottom);
        return results;
    }

    private static LineChart<Number, Number> chart(String id, String yLabel) {
        NumberAxis x = new NumberAxis(0, 10, 1);
        x.setLabel(Messages.get("load.chart.seconds"));
        x.setAutoRanging(false);
        NumberAxis y = new NumberAxis();
        y.setLabel(yLabel);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setId(id);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setMinHeight(180);
        return chart;
    }

    void refreshTargets() {
        Target current = targetBox.getValue();
        List<Target> targets = new ArrayList<>();
        targets.add(new Target(Messages.get("load.target.address"), null));
        context.engine().map(DeliveryEngine::destinations).orElse(List.of())
                .stream().filter(DestinationConfig::isMllp).forEach(d -> targets.add(new Target(d.name(), d)));
        targetBox.getItems().setAll(targets);
        Target keep = targets.get(0);
        if (current != null) {
            for (Target t : targets) {
                if (t.label().equals(current.label())) {
                    keep = t;
                }
            }
        }
        targetBox.setValue(keep);
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("load.files.choose"));
        chooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("HL7", "*.hl7", "*.txt", "*.dat"),
                new FileChooser.ExtensionFilter("*", "*.*"));
        List<java.io.File> chosen = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (chosen != null && !chosen.isEmpty()) {
            setFiles(chosen.stream().map(java.io.File::toPath).toList());
        }
    }

    /** Uses these message files (for tests and the file chooser). */
    void setFiles(List<Path> paths) {
        files = List.copyOf(paths);
        sourceBox.setValue(Source.FILES);
        filesLabel.setText(files.size() == 1 ? String.valueOf(files.get(0).getFileName())
                : Messages.get("load.files.count", files.size()));
    }

    boolean isRunning() {
        return running != null;
    }

    private LoadTestPlan buildPlan() throws IOException {
        Target target = targetBox.getValue();
        MllpClientConfig config;
        TlsOptions tls = null;
        AckMode ackMode = waitForAckBox.isSelected() ? AckMode.EXPECT_ACK : AckMode.NO_ACK;
        if (target != null && target.destination() != null) {
            DestinationConfig d = target.destination();
            config = d.clientConfig();
            DeliveryEngine engine = context.engine().orElseThrow();
            tls = engine.tlsOptions(d).orElse(null);
        } else {
            config = MllpClientConfig.of(hostField.getText(), Fields.parse(portField, Messages.get("load.port"), 1,
                    65_535));
            config = config.withTimeouts(config.connectTimeoutMs(),
                    Fields.parse(ackTimeoutField, Messages.get("load.ackTimeout"), 1, 3_600_000));
        }
        List<String> messages = new ArrayList<>();
        if (sourceBox.getValue() == Source.FILES) {
            if (files.isEmpty()) {
                throw new IllegalArgumentException(Messages.get("load.files.none"));
            }
            for (Path f : files) {
                messages.addAll(MessageSplitter.split(Files.readString(f, config.charset())).messages());
            }
        } else {
            messages.addAll(MessageSplitter.split(editorText.get()).messages());
        }
        if (messages.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("load.messages.none"));
        }
        int connections = Fields.parse(connectionsField, Messages.get("load.connections"), 1,
                LoadTestPlan.MAX_CONNECTIONS);
        int rate = Fields.parse(rateField, Messages.get("load.rate"), 0, 1_000_000);
        int count = Fields.parse(countField, Messages.get("load.count"), 0, Integer.MAX_VALUE);
        int seconds = Fields.parse(durationField, Messages.get("load.duration"), 0, 7 * 24 * 3600);
        return new LoadTestPlan(config, tls, messages, connections, rate, count, Duration.ofSeconds(seconds),
                ackMode, true);
    }

    private static double threshold(TextField field, String name) {
        String t = field.getText().trim().replace(',', '.');
        if (t.isEmpty()) {
            return -1;
        }
        try {
            double d = Double.parseDouble(t);
            if (d < 0) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(Messages.get("load.threshold.invalid", name));
        }
    }

    /** Starts a test with the form's settings. */
    void start() {
        LoadTestPlan plan;
        try {
            plan = buildPlan();
            lastThresholds = new LoadThresholds(threshold(maxErrorField, Messages.get("load.maxError")),
                    threshold(maxP95Field, Messages.get("load.maxP95")), -1, -1);
        } catch (IOException | RuntimeException e) {
            Styles.badge(stateBadge, SendOutcome.Severity.FAILURE);
            stateBadge.setText(Messages.get("load.state.invalid"));
            status.accept(e.getMessage());
            return;
        }
        LoadTest test = new LoadTest(plan);
        running = test;
        lastReport = null;
        exportButton.setDisable(true);
        rateSeries.getData().clear();
        meanSeries.getData().clear();
        maxSeries.getData().clear();
        outcomesList.getItems().clear();
        thresholdValue.setText("-");
        startButton.setText(Messages.get("load.stop"));
        Styles.badge(stateBadge, SendOutcome.Severity.WARNING);
        stateBadge.setText(Messages.get("load.state.running"));
        status.accept(Messages.get("load.started", plan.describe()));
        Thread.ofPlatform().daemon().name("load-test").start(() -> {
            try {
                LoadTestReport report = test.run(s -> Platform.runLater(() -> show(s)));
                Platform.runLater(() -> finished(report));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    running = null;
                    startButton.setText(Messages.get("load.start"));
                    Styles.badge(stateBadge, SendOutcome.Severity.FAILURE);
                    stateBadge.setText(Messages.get("load.state.invalid"));
                    status.accept(e.getMessage());
                });
            }
        });
    }

    /** Stops the running test; the results so far are kept. */
    void stop() {
        LoadTest test = running;
        if (test != null) {
            test.cancel();
            status.accept(Messages.get("load.stopping"));
        }
    }

    private void show(LoadTestSnapshot s) {
        elapsedValue.setText(String.format(Locale.ROOT, "%.1f s", s.elapsedMillis() / 1000.0));
        sentValue.setText(String.format(Messages.locale(), "%,d", s.sent()));
        acceptedValue.setText(String.format(Messages.locale(), "%,d", s.accepted()));
        failedValue.setText(String.format(Messages.locale(), "%,d (%.2f%%)", s.failed(), s.errorPercent()));
        throughputValue.setText(String.format(Messages.locale(), "%,.1f msg/s", s.throughput()));
        LatencyStats l = s.latency();
        latencyValue.setText(String.format(Locale.ROOT, "p50 %.2f · p90 %.2f · p95 %.2f · p99 %.2f · max %.2f ms",
                l.p50(), l.p90(), l.p95(), l.p99(), l.max()));
        List<LoadTestSnapshot.Second> timeline = s.timeline();
        int from = Math.max(0, timeline.size() - MAX_POINTS);
        // Points are only drawn for short tests, where a line of one or two points would be invisible.
        boolean symbols = timeline.size() - from <= 20;
        long first = timeline.isEmpty() ? 0 : timeline.get(from).second();
        long last = timeline.isEmpty() ? 0 : timeline.get(timeline.size() - 1).second();
        double upper = Math.max(first + 10, last + 1);
        for (LineChart<Number, Number> c : charts) {
            c.setCreateSymbols(symbols);
            NumberAxis x = (NumberAxis) c.getXAxis();
            x.setLowerBound(first);
            x.setUpperBound(upper);
            x.setTickUnit(Math.max(1, Math.ceil((upper - first) / 10)));
        }
        rateSeries.getData().setAll(timeline.subList(from, timeline.size()).stream()
                .map(t -> new XYChart.Data<Number, Number>(t.second(), t.completed())).toList());
        meanSeries.getData().setAll(timeline.subList(from, timeline.size()).stream()
                .map(t -> new XYChart.Data<Number, Number>(t.second(), t.meanLatencyMs())).toList());
        maxSeries.getData().setAll(timeline.subList(from, timeline.size()).stream()
                .map(t -> new XYChart.Data<Number, Number>(t.second(), t.maxLatencyMs())).toList());
        List<String> outcomes = new ArrayList<>();
        for (SendOutcome o : SendOutcome.values()) {
            long n = s.outcomes().getOrDefault(o, 0L);
            if (n > 0) {
                outcomes.add(String.format(Messages.locale(), "%s: %,d", o.description(), n));
            }
        }
        outcomesList.getItems().setAll(outcomes);
    }

    private void finished(LoadTestReport report) {
        running = null;
        lastReport = report;
        show(report.result());
        report.errors().forEach(e -> outcomesList.getItems().add("  " + e));
        startButton.setText(Messages.get("load.start"));
        exportButton.setDisable(false);
        List<String> failures = lastThresholds.failures(report.result());
        if (lastThresholds.any()) {
            thresholdValue.setText(failures.isEmpty() ? Messages.get("load.threshold.passed")
                    : Messages.get("load.threshold.failed", String.join("; ", failures)));
        }
        boolean ok = report.result().failed() == 0 && failures.isEmpty();
        Styles.badge(stateBadge, ok ? SendOutcome.Severity.SUCCESS : SendOutcome.Severity.FAILURE);
        stateBadge.setText(Messages.get(report.cancelled() ? "load.state.stopped" : "load.state.finished"));
        status.accept(Messages.get("load.done", report.result().completed(), report.result().throughput(),
                report.result().latency().p95()));
    }

    /** The last finished test, or null. */
    LoadTestReport lastReport() {
        return lastReport;
    }

    private void chooseExport() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(Messages.get("load.export"));
        chooser.setInitialFileName("load-test.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File f = chooser.showSaveDialog(getScene().getWindow());
        if (f != null) {
            exportTo(f.toPath());
        }
    }

    /** Writes the last report as JSON, in the same shape as {@code hl7send load --json}. */
    void exportTo(Path file) {
        if (lastReport == null) {
            return;
        }
        try {
            Files.writeString(file, JsonViews.pretty(JsonViews.loadReport(lastReport,
                    lastThresholds.any() ? lastThresholds.failures(lastReport.result()) : null)),
                    StandardCharsets.UTF_8);
            status.accept(Messages.get("load.exported", file));
        } catch (IOException e) {
            status.accept(Messages.get("load.exportFailed", e.getMessage()));
        }
    }

    void shutdown() {
        stop();
    }
}
