package io.hl7sender.core.runtime;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.queue.InstanceLock;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueueStore;
import io.hl7sender.core.secrets.SecretStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens the queue database the same way for the desktop app, the {@code hl7send} CLI and the background
 * service.
 *
 * <p>Only one process at a time may <em>deliver</em> from a queue: it holds the queue lock ({@link #own}).
 * Other processes may still read and change the queue ({@link #share}). SQLite serialises their writes, and
 * the delivering process picks up new messages within 30 seconds, or at once when asked through the local
 * API. A process that shares the queue must never start a delivery engine on it.
 */
public final class QueueOpener {

    /** Secret-store entry holding the queue database key. */
    public static final String DB_KEY = "queue/database-key";
    /** Secret-store entry holding the PostgreSQL password. */
    public static final String POSTGRES_PASSWORD = "queue/postgres-password";

    private static final Logger LOG = LoggerFactory.getLogger(QueueOpener.class);

    /**
     * An open queue database.
     *
     * @param store        the database
     * @param lock         the delivery lock file, if this process owns a SQLite queue
     * @param encrypted    whether the database is encrypted at rest
     * @param lockedOnServer whether this process holds the delivery lock of a PostgreSQL queue
     */
    public record Opened(QueueStore store, Optional<InstanceLock> lock, boolean encrypted, boolean lockedOnServer)
            implements AutoCloseable {

        public Opened(QueueStore store, Optional<InstanceLock> lock, boolean encrypted) {
            this(store, lock, encrypted, false);
        }

        public boolean owner() {
            return lock.isPresent() || lockedOnServer;
        }

        @Override
        public void close() {
            store.close();
            lock.ifPresent(l -> {
                try {
                    l.close();
                } catch (IOException e) {
                    LOG.warn("Could not release queue lock: {}", e.getMessage());
                }
            });
        }
    }

    private QueueOpener() {
    }

    public static Path database(AppPaths paths) {
        return paths.dataDir().resolve("queue.db");
    }

    public static Path lockFile(AppPaths paths) {
        return paths.dataDir().resolve("queue.lock");
    }

    /**
     * Takes the delivery lock and opens the queue, first applying the "encrypt queue database" setting (encrypting
     * or decrypting the file in place if it changed). Returns empty if another process holds the lock.
     */
    public static Optional<Opened> own(AppPaths paths, boolean encrypt, SecretStore secrets, Clock clock)
            throws IOException {
        Optional<InstanceLock> lock = InstanceLock.tryAcquire(lockFile(paths));
        if (lock.isEmpty()) {
            return Optional.empty();
        }
        try {
            String key = prepareEncryption(database(paths), encrypt, secrets);
            return Optional.of(new Opened(QueueStore.open(database(paths), clock, key), lock, key != null));
        } catch (RuntimeException e) {
            lock.get().close();
            throw e;
        }
    }

    /**
     * As {@link #own(AppPaths, boolean, SecretStore, Clock)}, for the database chosen in the settings. For a
     * PostgreSQL queue the delivery lock is an advisory lock on the server, so it works across computers.
     */
    public static Optional<Opened> own(AppPaths paths, io.hl7sender.core.config.AppSettings.Database db,
                                       boolean encrypt, SecretStore secrets, Clock clock) throws IOException {
        if (db == null || !db.postgres()) {
            return own(paths, encrypt, secrets, clock);
        }
        QueueStore store = openPostgres(db, secrets, clock);
        if (!store.tryLockDelivery()) {
            store.close();
            return Optional.empty();
        }
        return Optional.of(new Opened(store, Optional.empty(), false, true));
    }

    /** As {@link #share(AppPaths, SecretStore, Clock)}, for the database chosen in the settings. */
    public static Opened share(AppPaths paths, io.hl7sender.core.config.AppSettings.Database db, SecretStore secrets,
                               Clock clock) {
        if (db == null || !db.postgres()) {
            return share(paths, secrets, clock);
        }
        return new Opened(openPostgres(db, secrets, clock), Optional.empty(), false, false);
    }

    /** {@link #own} if the delivery lock is free, otherwise {@link #share}, for the database in the settings. */
    public static Opened ownOrShare(AppPaths paths, io.hl7sender.core.config.AppSettings.Database db, boolean encrypt,
                                    SecretStore secrets, Clock clock) throws IOException {
        Optional<Opened> owned = own(paths, db, encrypt, secrets, clock);
        return owned.isPresent() ? owned.get() : share(paths, db, secrets, clock);
    }

    /** Opens a PostgreSQL queue with the password from the secret store. */
    public static QueueStore openPostgres(io.hl7sender.core.config.AppSettings.Database db, SecretStore secrets,
                                          Clock clock) {
        return QueueStore.openPostgres(db.url(), db.username(), secrets.get(POSTGRES_PASSWORD).orElse(null), clock);
    }

    /**
     * Opens the queue without the delivery lock, to read it or change it while another process delivers. The
     * database is used as it is: encryption is never changed here.
     */
    public static Opened share(AppPaths paths, SecretStore secrets, Clock clock) {
        Path db = database(paths);
        boolean encrypted = QueueStore.isEncrypted(db);
        String key = encrypted ? secrets.get(DB_KEY).orElseThrow(() -> missingKey(secrets)) : null;
        return new Opened(QueueStore.open(db, clock, key), Optional.empty(), encrypted);
    }

    /** {@link #own} if the lock is free, otherwise {@link #share}. */
    public static Opened ownOrShare(AppPaths paths, boolean encrypt, SecretStore secrets, Clock clock)
            throws IOException {
        Optional<Opened> owned = own(paths, encrypt, secrets, clock);
        return owned.isPresent() ? owned.get() : share(paths, secrets, clock);
    }

    /** Whether another process currently holds the delivery lock. */
    public static boolean lockedElsewhere(AppPaths paths) throws IOException {
        Optional<InstanceLock> lock = InstanceLock.tryAcquire(lockFile(paths));
        if (lock.isEmpty()) {
            return true;
        }
        lock.get().close();
        return false;
    }

    /** Returns the key to open the database with (null if unencrypted), encrypting or decrypting it first. */
    static String prepareEncryption(Path db, boolean wanted, SecretStore secrets) {
        boolean encrypted = QueueStore.isEncrypted(db);
        String key = secrets.get(DB_KEY).orElse(null);
        if (encrypted && key == null) {
            throw missingKey(secrets);
        }
        if (wanted && !encrypted) {
            if (key == null) {
                key = QueueStore.newKey();
                secrets.put(DB_KEY, key);
            }
            if (Files.exists(db)) {
                QueueStore.encrypt(db, key);
            }
            LOG.info("Queue database is encrypted at rest; key stored in the {}", secrets.description());
            return key;
        }
        if (!wanted && encrypted) {
            QueueStore.decrypt(db, key);
            LOG.info("Queue database decrypted as requested");
            return null;
        }
        return encrypted ? key : null;
    }

    private static QueueException missingKey(SecretStore secrets) {
        return new QueueException("The queue database is encrypted, but its key is not in the "
                + secrets.description() + ". Restore the key (in the app: Help > Security), or move queue.db aside "
                + "to start a new queue.");
    }
}
