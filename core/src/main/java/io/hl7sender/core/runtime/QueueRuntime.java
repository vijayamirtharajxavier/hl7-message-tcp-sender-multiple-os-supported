package io.hl7sender.core.runtime;

import io.hl7sender.core.alert.AlertMonitor;
import io.hl7sender.core.api.ApiToken;
import io.hl7sender.core.api.LocalApiServer;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.schedule.Scheduler;
import io.hl7sender.core.secrets.SecretStore;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.template.TemplateEngine;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the process that delivers from the queue runs: the delivery engine (with folder watchers), the
 * scheduler, alerting, and optionally the local REST API. Used by the desktop app and by {@code hl7send serve}.
 */
public final class QueueRuntime implements AutoCloseable {

    /** Certificates and queues are re-checked this often for alerts, in addition to engine events. */
    public static final Duration ALERT_CHECK_EVERY = Duration.ofMinutes(5);
    /** How often the delivering process re-reads a shared PostgreSQL queue for changes made on other computers. */
    public static final Duration SHARED_RELOAD_EVERY = Duration.ofSeconds(5);
    private static final Logger LOG = LoggerFactory.getLogger(QueueRuntime.class);

    private final AppPaths paths;
    private final QueueOpener.Opened opened;
    private final DeliveryEngine engine;
    private final AlertMonitor alerts;
    private final Scheduler scheduler;
    private LocalApiServer api;
    private java.util.concurrent.ScheduledExecutorService reloader;
    private AppSettings.Api apiSettings = AppSettings.Api.defaults();

    private QueueRuntime(AppPaths paths, QueueOpener.Opened opened, DeliveryEngine engine, AlertMonitor alerts,
                         Scheduler scheduler) {
        this.paths = paths;
        this.opened = opened;
        this.engine = engine;
        this.alerts = alerts;
        this.scheduler = scheduler;
    }

    /**
     * Takes the queue lock, opens the queue (applying the encryption setting), and starts delivery and alerting.
     * The API is started too if the settings enable it. Returns empty if another process already delivers from
     * this queue.
     */
    public static Optional<QueueRuntime> start(AppPaths paths, Supplier<AppSettings> settings, SecretStore secrets,
                                               Hl7Sender sender, Clock clock) throws IOException {
        Optional<QueueOpener.Opened> opened = QueueOpener.own(paths, settings.get().database(),
                settings.get().security().encryptQueue(), secrets, clock);
        if (opened.isEmpty()) {
            return Optional.empty();
        }
        QueueRuntime runtime = null;
        try {
            DeliveryEngine engine = new DeliveryEngine(opened.get().store(), sender, clock,
                    java.util.random.RandomGenerator.getDefault(), secrets);
            engine.setTransports(io.hl7sender.core.transport.Transports.load(paths.pluginsDir()));
            engine.start();
            AlertMonitor alerts = new AlertMonitor(engine, () -> settings.get().alerts(), secrets, clock);
            alerts.start(ALERT_CHECK_EVERY);
            Scheduler scheduler = new Scheduler(engine, new TemplateEngine(), clock);
            runtime = new QueueRuntime(paths, opened.get(), engine, alerts, scheduler);
            scheduler.start();
            if (opened.get().store().isPostgres()) {
                runtime.startSharedReload();
            }
            try {
                runtime.applyApiSettings(settings.get().api());
            } catch (IOException e) {
                // Delivery matters more than the API: keep running without it (callers can check api()).
                LOG.warn("Local API could not start on port {}: {}", settings.get().api().port(), e.getMessage());
            }
            return Optional.of(runtime);
        } catch (RuntimeException e) {
            if (runtime != null) {
                runtime.close();
            } else {
                opened.get().close();
            }
            throw e;
        }
    }

    /**
     * Opens a shared PostgreSQL queue without delivering, for when another computer (or service) already delivers
     * from it: destinations, messages and history can be viewed and changed, and the delivering process sends the
     * messages. Alerts and schedules run in the delivering process only.
     */
    public static QueueRuntime share(AppPaths paths, Supplier<AppSettings> settings, SecretStore secrets,
                                     Hl7Sender sender, Clock clock) {
        QueueOpener.Opened opened = QueueOpener.share(paths, settings.get().database(), secrets, clock);
        try {
            DeliveryEngine engine = new DeliveryEngine(opened.store(), sender, clock,
                    java.util.random.RandomGenerator.getDefault(), secrets);
            engine.setTransports(io.hl7sender.core.transport.Transports.load(paths.pluginsDir()));
            AlertMonitor alerts = new AlertMonitor(engine, () -> settings.get().alerts(), secrets, clock);
            return new QueueRuntime(paths, opened, engine, alerts, new Scheduler(engine, new TemplateEngine(), clock));
        } catch (RuntimeException e) {
            opened.close();
            throw e;
        }
    }

    /** True if this process delivers from the queue; false for {@link #share}. */
    public boolean delivering() {
        return opened.owner();
    }

    public DeliveryEngine engine() {
        return engine;
    }

    public QueueStore store() {
        return opened.store();
    }

    public AlertMonitor alerts() {
        return alerts;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    public boolean encrypted() {
        return opened.encrypted();
    }

    public synchronized Optional<LocalApiServer> api() {
        return Optional.ofNullable(api);
    }

    /** Starts, restarts or stops the local API to match {@code settings}. */
    public synchronized void applyApiSettings(AppSettings.Api settings) throws IOException {
        boolean running = api != null;
        if (running && settings.enabled() && settings.port() == apiSettings.port()) {
            return;
        }
        if (running) {
            api.close();
            api = null;
        }
        apiSettings = settings;
        if (settings.enabled()) {
            ApiToken.loadOrCreate(paths.configDir());
            api = LocalApiServer.start(engine, settings.port(), ApiToken.watching(paths.configDir()), paths);
        }
    }

    /**
     * Other computers change a shared queue without telling this process (the local API reload only reaches this
     * computer), so new destinations and messages are picked up by re-reading the queue regularly.
     */
    private synchronized void startSharedReload() {
        reloader = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "shared-queue-reload");
            t.setDaemon(true);
            return t;
        });
        long every = SHARED_RELOAD_EVERY.toMillis();
        reloader.scheduleWithFixedDelay(() -> {
            try {
                engine.reload();
            } catch (RuntimeException e) {
                LOG.warn("Could not re-read the shared queue: {}", e.getMessage());
            }
        }, every, every, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (reloader != null) {
            reloader.shutdownNow();
            reloader = null;
        }
        if (api != null) {
            api.close();
            api = null;
        }
        scheduler.close();
        alerts.close();
        engine.close();
        opened.close();
        LOG.debug("Queue runtime closed");
    }
}
