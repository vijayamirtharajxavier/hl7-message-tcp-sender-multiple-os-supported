package io.hl7sender.cli;

import io.hl7sender.core.AppInfo;
import io.hl7sender.core.api.ApiToken;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.config.SettingsStore;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.runtime.QueueRuntime;
import io.hl7sender.core.secrets.SecretStore;
import io.hl7sender.core.secrets.SecretStores;
import io.hl7sender.core.send.Hl7Sender;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Clock;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code hl7send serve}: delivers from the queue without a window, for servers and unattended folder watching. */
@Command(name = "serve", mixinStandardHelpOptions = true,
        description = {"Deliver queued messages in the background without the desktop app: queues, retries, "
            + "folder watching and alerts, with the destinations and settings made in the app.",
            "Only one process delivers from a queue: stop the app first (it can still be used to watch the queue "
                + "through the local API). Stop with Ctrl+C or the service manager.",
            "To start it with the computer, see: hl7send service install"})
final class ServeCommand implements Callable<Integer> {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Lets tests stop a running {@code serve} without a signal. */
    private static volatile CountDownLatch running;

    @Spec
    CommandSpec spec;

    @Option(names = "--api", negatable = true,
            description = "Serve the local REST API (default: as set in the app).")
    Boolean api;

    @Option(names = "--api-port", paramLabel = "PORT", description = "Port for the local API (default: as set in "
            + "the app, else 8742).")
    Integer apiPort;

    @Override
    public Integer call() throws IOException, InterruptedException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        AppPaths paths = AppPaths.detect();
        AppSettings loaded = new SettingsStore(paths.settingsFile()).load();
        AppSettings.Api a = loaded.api();
        AppSettings settings = loaded.withApi(new AppSettings.Api(api != null ? api : a.enabled(),
                apiPort != null ? apiPort : a.port()));
        SecretStore secrets = SecretStores.detect(paths.configDir());
        Optional<QueueRuntime> started = QueueRuntime.start(paths, () -> settings, secrets, new Hl7Sender(),
                Clock.systemUTC());
        if (started.isEmpty()) {
            err.println("Another HL7 Sender app window or service is already delivering from " + paths.dataDir()
                    + ". Stop it first.");
            return ExitCodes.QUEUE_UNAVAILABLE;
        }
        QueueRuntime runtime = started.get();
        if (settings.api().enabled() && runtime.api().isEmpty()) {
            err.println("The local API could not start on port " + settings.api().port() + " (is it in use?).");
            runtime.close();
            return ExitCodes.QUEUE_UNAVAILABLE;
        }
        CountDownLatch stop = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        setRunning(stop);
        // On SIGTERM/Ctrl+C the JVM halts when hooks return, so wait for the clean shutdown below: it records any
        // attempt in progress, which otherwise would be retried as a possible duplicate at the next start.
        Thread hook = new Thread(() -> {
            stop.countDown();
            try {
                closed.await(15, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "hl7send-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        runtime.alerts().addListener(r -> {
            synchronized (out) {
                out.println(TIME.format(LocalTime.now()) + " ALERT " + r.alert().summary());
                out.flush();
            }
        });
        try {
            int n = runtime.engine().destinations().size();
            out.println(AppInfo.NAME + " " + AppInfo.version() + " delivering for " + n + " destination(s) from "
                    + paths.dataDir() + (runtime.encrypted() ? " (encrypted)" : ""));
            for (DestinationConfig d : runtime.engine().destinations()) {
                out.println("  " + d.name() + "  " + d.displayAddress() + (d.paused() ? "  [paused]" : "")
                        + (d.watchFolder().isEmpty() ? "" : "  watching " + d.watchFolder()));
            }
            runtime.api().ifPresent(s -> out.println("Local API: http://127.0.0.1:" + s.port() + "/api/v1/ (token in "
                    + ApiToken.file(paths.configDir()) + ")"));
            out.println("Press Ctrl+C to stop.");
            out.flush();
            stop.await();
        } finally {
            runtime.close();
            out.println("Stopped.");
            out.flush();
            closed.countDown();
            setRunning(null);
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // The JVM is exiting; the hook already ran.
            }
        }
        return ExitCodes.OK;
    }

    private static void setRunning(CountDownLatch latch) {
        running = latch;
    }

    /** Stops a {@code serve} running in this JVM (tests). */
    static void requestStop() {
        CountDownLatch l = running;
        if (l != null) {
            l.countDown();
        }
    }
}
