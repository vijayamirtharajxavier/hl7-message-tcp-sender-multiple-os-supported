package io.hl7sender.cli;

import io.hl7sender.core.api.ApiClient;
import io.hl7sender.core.auth.Access;
import io.hl7sender.core.auth.AccessDeniedException;
import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.config.SettingsStore;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.runtime.QueueOpener;
import io.hl7sender.core.secrets.SecretStore;
import io.hl7sender.core.secrets.SecretStores;
import io.hl7sender.core.send.Hl7Sender;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;

/**
 * The app's settings, secrets and queue, opened for one CLI command.
 *
 * <p>If no app window or service is running, this process owns the queue and may deliver (for {@code --wait}).
 * Otherwise it shares the queue: it reads and changes the database, and asks the running process (through its
 * local API, if enabled) to act on the changes at once. Without the API, the running process notices within
 * 30 seconds.
 */
final class Workspace implements AutoCloseable {

    private final AppPaths paths;
    private final AppSettings settings;
    private final SecretStore secrets;
    private final QueueOpener.Opened queue;
    private final DeliveryEngine engine;

    private Workspace(AppPaths paths, AppSettings settings, SecretStore secrets, QueueOpener.Opened queue) {
        this.paths = paths;
        this.settings = settings;
        this.secrets = secrets;
        this.queue = queue;
        this.engine = new DeliveryEngine(queue.store(), new Hl7Sender(), secrets);
        this.engine.setTransports(io.hl7sender.core.transport.Transports.load(paths.pluginsDir()));
    }

    /** System property set by {@code --user}; {@code HL7SENDER_USER} is used when it is not set. */
    static final String USER_PROPERTY = "hl7sender.user";
    /** Environment variable holding the password for {@code --user}. */
    static final String PASSWORD_ENV = "HL7SENDER_PASSWORD";
    /** Reads environment variables; tests replace it, as they cannot set real ones. */
    static final java.util.concurrent.atomic.AtomicReference<java.util.function.UnaryOperator<String>> ENV =
            new java.util.concurrent.atomic.AtomicReference<>(System::getenv);

    static String env(String name) {
        return ENV.get().apply(name);
    }

    /**
     * Opens the queue for a command that needs {@code permission}. If users have been added, the user named by
     * {@code --user} (or {@code HL7SENDER_USER}) signs in with the password from {@code HL7SENDER_PASSWORD} (or
     * typed at the console), and changes are recorded in the audit trail under their name.
     *
     * @param what what the command does, for the refusal message ("send messages")
     * @throws AccessDeniedException if sign-in fails or the user may not do this
     */
    static Workspace open(Permission permission, String what) throws IOException {
        AppPaths paths = AppPaths.detect();
        AppSettings settings = new SettingsStore(paths.settingsFile()).load();
        SecretStore secrets = SecretStores.detect(paths.configDir());
        QueueOpener.Opened queue = QueueOpener.ownOrShare(paths, settings.database(),
                settings.security().encryptQueue(), secrets, Clock.systemUTC());
        try {
            Access access = signIn(queue.store());
            access.require(permission, what);
            access.actor().ifPresent(queue.store()::setUserActor);
        } catch (RuntimeException e) {
            queue.close();
            throw e;
        }
        return new Workspace(paths, settings, secrets, queue);
    }

    private static Access signIn(QueueStore store) {
        if (!store.accessControlEnabled()) {
            return Access.OPEN;
        }
        String fromEnv = env("HL7SENDER_USER");
        String user = System.getProperty(USER_PROPERTY, fromEnv == null ? "" : fromEnv).trim();
        if (user.isEmpty()) {
            throw new AccessDeniedException("This queue has users: sign in with --user NAME (or HL7SENDER_USER) and "
                    + "the password in " + PASSWORD_ENV + ".");
        }
        String password = env(PASSWORD_ENV);
        if (password == null && System.console() != null) {
            char[] typed = System.console().readPassword("Password for %s: ", user);
            password = typed == null ? null : new String(typed);
        }
        if (password == null) {
            throw new AccessDeniedException("No password for " + user + ": set " + PASSWORD_ENV + ".");
        }
        return store.authenticate(user, password).map(Access::signedIn)
                .orElseThrow(() -> new AccessDeniedException("Sign-in failed: wrong user name or password, or the "
                        + "user is disabled."));
    }

    AppPaths paths() {
        return paths;
    }

    AppSettings settings() {
        return settings;
    }

    SecretStore secrets() {
        return secrets;
    }

    QueueStore store() {
        return queue.store();
    }

    /** Engine for validation, queuing and TLS options; it only delivers if {@link #startDelivery} is called. */
    DeliveryEngine engine() {
        return engine;
    }

    /** True if no other process delivers from this queue. */
    boolean owner() {
        return queue.owner();
    }

    /** Starts delivering from this process; only allowed when it owns the queue. */
    void startDelivery() {
        if (!owner()) {
            throw new IllegalStateException("Another process delivers from this queue");
        }
        engine.start();
    }

    /**
     * Tells the process that delivers from the queue about changes made here. Returns a note for the user if it
     * could not be told, or empty if it was told (or this process delivers itself).
     */
    Optional<String> notifyOwner() {
        if (owner()) {
            return engine.isStarted() ? Optional.empty()
                    : Optional.of("No app or service is running, so nothing is sent until one starts (or use --wait).");
        }
        Optional<ApiClient> api = ApiClient.discover(paths);
        if (api.isPresent() && api.get().reload()) {
            return Optional.empty();
        }
        return Optional.of("The running app or service will pick this up within 30 seconds (enable its local API "
                + "for immediate delivery).");
    }

    /** A destination by ID or (case-insensitive) name. */
    DestinationConfig destination(String idOrName) {
        Optional<DestinationConfig> d = idOrName.matches("\\d{1,18}")
                ? store().destination(Long.parseLong(idOrName))
                : store().destinations().stream().filter(x -> x.name().equalsIgnoreCase(idOrName)).findFirst();
        return d.orElseThrow(() -> new UsageException("No destination named '" + idOrName
                + "'. List them with: hl7send destination list"));
    }

    @Override
    public void close() {
        engine.close();
        queue.close();
    }

    /** A problem with what the user asked for (exit code 2). */
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }
}
