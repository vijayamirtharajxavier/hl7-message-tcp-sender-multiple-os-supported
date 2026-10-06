package io.hl7sender.app;

import io.hl7sender.core.alert.AlertMonitor;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.config.SettingsStore;
import io.hl7sender.core.hl7.ControlIdGenerator;
import io.hl7sender.core.hl7.validation.MessageValidator;
import io.hl7sender.core.api.LocalApiServer;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.auth.Access;
import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.auth.User;
import io.hl7sender.core.runtime.QueueOpener;
import io.hl7sender.core.runtime.QueueRuntime;
import io.hl7sender.core.secrets.SecretStore;
import io.hl7sender.core.secrets.SecretStoreException;
import io.hl7sender.core.secrets.SecretStores;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.template.TemplateEngine;
import io.hl7sender.core.template.TemplateStore;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Services shared by the UI: settings, the sender, the validator, a background executor and the
 * durable delivery queue.
 *
 * <p>The queue is only available to the first running instance of the app. Two engines on one
 * database would send every message twice, so later instances run with the queue disabled.
 */
final class AppContext implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    /** Secret-store entry holding the queue database key. */
    static final String DB_KEY = QueueOpener.DB_KEY;

    private final AppPaths paths;
    private final SettingsStore store;
    private final MessageValidator validator = new MessageValidator();
    private final Hl7Sender sender = new Hl7Sender(validator, new ControlIdGenerator(), Clock.systemDefaultZone());
    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().daemon().name("hl7-worker-", 0).factory());
    private final TemplateEngine templates = new TemplateEngine();
    private final TemplateStore templateStore;
    private volatile AppSettings settings;
    private final SecretStore secrets;
    private QueueRuntime runtime;
    private final LogBuffer logs = LogBuffer.install();
    private String queueUnavailableReason = "";
    private volatile Access access = Access.OPEN;

    AppContext(AppPaths paths) {
        this.paths = paths;
        this.store = new SettingsStore(paths.settingsFile());
        this.settings = store.load();
        this.templateStore = new TemplateStore(paths.configDir().resolve("templates"));
        this.secrets = SecretStores.detect(paths.configDir());
        openQueue();
    }

    private void openQueue() {
        try {
            Optional<QueueRuntime> started = QueueRuntime.start(paths, () -> settings, secrets, sender,
                    Clock.systemUTC());
            if (started.isEmpty() && settings.database().postgres()) {
                // A shared queue that another computer delivers from: use it without delivering.
                runtime = QueueRuntime.share(paths, () -> settings, secrets, sender, Clock.systemUTC());
                LOG.info("Shared queue opened; another computer or service delivers the messages");
                return;
            }
            if (started.isEmpty()) {
                queueUnavailableReason = "Another HL7 Sender window or the hl7send service is already delivering "
                        + "from this queue. Close it to use the queue here.";
                LOG.warn("Queue disabled: {}", queueUnavailableReason);
                return;
            }
            runtime = started.get();
        } catch (IOException | QueueException | SecretStoreException e) {
            queueUnavailableReason = "The delivery queue could not be opened: " + e.getMessage();
            LOG.error("Queue disabled", e);
        }
    }

    /** Starts, restarts or stops the local API to match the current settings. */
    void applyApiSettings() throws IOException {
        if (runtime != null) {
            runtime.applyApiSettings(settings.api());
        }
    }

    /** The port the local API is listening on, if it is running. */
    Optional<Integer> apiPort() {
        return runtime == null ? Optional.empty() : runtime.api().map(LocalApiServer::port);
    }

    /** True if the queue has users and nobody has signed in to this window yet. */
    boolean signInRequired() {
        return runtime != null && access.user().isEmpty() && runtime.store().accessControlEnabled();
    }

    /** Checks the user name and password; on success, this window acts as that user from now on. */
    boolean signIn(String username, String password) {
        if (runtime == null) {
            return false;
        }
        Optional<User> user = runtime.store().authenticate(username, password);
        user.ifPresent(u -> {
            access = Access.signedIn(u);
            runtime.store().setUserActor(u.username());
            LOG.info("Signed in as {} ({})", u.username(), u.role());
        });
        return user.isPresent();
    }

    /** Who is signed in, and what they may do. Everything is allowed while the queue has no users. */
    Access access() {
        return access;
    }

    boolean can(Permission p) {
        return access.can(p);
    }

    /** False when this window uses a shared queue that another computer or service delivers from. */
    boolean delivering() {
        return runtime != null && runtime.delivering();
    }

    /** Where the queue database is, for the status bar and dialogs. */
    String queueLocation() {
        return runtime == null ? "" : runtime.store().isPostgres() ? runtime.store().location()
                : String.valueOf(runtime.store().file());
    }

    SecretStore secrets() {
        return secrets;
    }

    boolean queueEncrypted() {
        return runtime != null && runtime.encrypted();
    }

    /** The queue database key, for the user to keep as a recovery key. */
    Optional<String> databaseKey() {
        return secrets.get(DB_KEY);
    }

    /** Alerting on dead letters, open circuits and certificates, or empty if the queue is unavailable. */
    Optional<AlertMonitor> alerts() {
        return runtime == null ? Optional.empty() : Optional.of(runtime.alerts());
    }

    /** Recent log events, for the Logs tab. */
    LogBuffer logs() {
        return logs;
    }

    /** The delivery engine, or empty if the queue is unavailable (see {@link #queueUnavailableReason()}). */
    Optional<io.hl7sender.core.schedule.Scheduler> scheduler() {
        return runtime == null ? Optional.empty() : Optional.of(runtime.scheduler());
    }

    Optional<DeliveryEngine> engine() {
        return runtime == null ? Optional.empty() : Optional.of(runtime.engine());
    }

    String queueUnavailableReason() {
        return queueUnavailableReason;
    }

    AppPaths paths() {
        return paths;
    }

    MessageValidator validator() {
        return validator;
    }

    Hl7Sender sender() {
        return sender;
    }

    /** Shared template engine, so ${SEQ} keeps counting across sends in a session. */
    TemplateEngine templates() {
        return templates;
    }

    TemplateStore templateStore() {
        return templateStore;
    }

    ExecutorService executor() {
        return executor;
    }

    AppSettings settings() {
        return settings;
    }

    /** Applies {@code change} to the current settings and saves them. A failed save is logged, not thrown. */
    synchronized void updateSettings(UnaryOperator<AppSettings> change) {
        settings = change.apply(settings).normalized();
        try {
            store.save(settings);
        } catch (IOException e) {
            LOG.warn("Could not save settings to {}: {}", store.file(), e.getMessage());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        closeQueue();
    }

    private void closeQueue() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
    }
}
