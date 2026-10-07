package io.hl7sender.core.queue;

import io.hl7sender.core.hl7.validation.ConformanceProfile;
import io.hl7sender.core.hl7.validation.ValidationIssue;
import io.hl7sender.core.hl7.validation.ValidationReport;
import io.hl7sender.core.script.MessageScript;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.secrets.InMemorySecretStore;
import io.hl7sender.core.secrets.SecretStore;
import io.hl7sender.core.template.TemplateEngine;
import io.hl7sender.core.tls.CertificateInfo;
import io.hl7sender.core.tls.TlsContexts;
import io.hl7sender.core.tls.TlsOptions;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Durable store-and-forward delivery: one {@link DestinationWorker} per destination, backed by a
 * {@link QueueStore}. This is the entry point for UI and CLI actions on queues. Every change goes
 * through here, so workers are woken and listeners notified consistently.
 *
 * <p>Guarantees: at-least-once delivery, no loss across crashes (state is committed before each
 * send), strict FIFO per destination, and every transition recorded in the audit trail.
 *
 * <p>Only one engine may use a database at a time. Use {@link InstanceLock} to enforce this.
 */
public final class DeliveryEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryEngine.class);
    private static final long STOP_TIMEOUT_MS = 10_000;
    /** Messages stored per transaction during bulk imports. */
    private static final int BULK_CHUNK = 250;
    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final QueueStore store;
    private final Hl7Sender sender;
    private final SecretStore secrets;
    /** Bumped whenever TLS passwords change, so workers rebuild their TLS contexts. */
    private final AtomicLong tlsGeneration = new AtomicLong();
    private final Clock clock;
    private final RandomGenerator random;
    private final Map<Long, DestinationWorker> workers = new ConcurrentHashMap<>();
    private final Map<Long, Thread> threads = new ConcurrentHashMap<>();
    private final Map<Long, FolderWatcher> watchers = new ConcurrentHashMap<>();
    private final Map<Long, Thread> watcherThreads = new ConcurrentHashMap<>();
    private final Map<Long, ApplicationAckListener> appAckListeners = new ConcurrentHashMap<>();
    private volatile long watchPollMillis = 2_000;
    private final List<QueueListener> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> reloadHooks = new CopyOnWriteArrayList<>();
    /** Destinations as last seen by {@link #reload()}, so a reload that finds no change does not notify. */
    private List<DestinationConfig> reloadedDestinations = List.of();
    private volatile io.hl7sender.core.transport.Transports transports =
            io.hl7sender.core.transport.Transports.builtIn();
    private volatile boolean started;

    public DeliveryEngine(QueueStore store, Hl7Sender sender, Clock clock, RandomGenerator random,
                          SecretStore secrets) {
        this.store = store;
        this.sender = sender;
        this.clock = clock;
        this.random = random;
        this.secrets = secrets;
    }

    public DeliveryEngine(QueueStore store, Hl7Sender sender, Clock clock, RandomGenerator random) {
        this(store, sender, clock, random, new InMemorySecretStore());
    }

    public DeliveryEngine(QueueStore store, Hl7Sender sender, SecretStore secrets) {
        this(store, sender, Clock.systemUTC(), RandomGenerator.getDefault(), secrets);
    }

    public DeliveryEngine(QueueStore store, Hl7Sender sender) {
        this(store, sender, new InMemorySecretStore());
    }

    public SecretStore secrets() {
        return secrets;
    }

    public QueueStore store() {
        return store;
    }

    /** How often watch folders are scanned (default 2 s). A file is picked up after two identical scans. */
    public void setWatchPollMillis(long millis) {
        this.watchPollMillis = Math.max(10, millis);
    }

    public void addListener(QueueListener listener) {
        listeners.add(listener);
    }

    public void removeListener(QueueListener listener) {
        listeners.remove(listener);
    }

    /** Recovers messages interrupted by a previous crash, then starts a worker per destination. */
    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        int recovered = store.recoverInFlight(null);
        List<DestinationConfig> destinations = store.destinations();
        for (DestinationConfig d : destinations) {
            startWorker(d);
        }
        LOG.info("Delivery engine started: {} destination(s), {} message(s) recovered", destinations.size(),
                recovered);
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * Picks up changes made to the database by another process (for example {@code hl7send queue send} while
     * the app or service is running): starts workers for new destinations, stops workers for deleted ones,
     * and wakes every worker so newly queued messages are sent now rather than at the next periodic check.
     */
    public synchronized void reload() {
        if (!started) {
            return;
        }
        for (Runnable hook : reloadHooks) {
            hook.run();
        }
        List<DestinationConfig> destinations = store.destinations();
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (DestinationConfig d : destinations) {
            ids.add(d.id());
            if (workers.containsKey(d.id())) {
                wake(d.id());
                ensureWatcher(d);
                ensureAppAckListener(d);
            } else {
                startWorker(d);
            }
        }
        for (Long id : List.copyOf(workers.keySet())) {
            if (!ids.contains(id)) {
                stopWorker(id);
            }
        }
        if (!destinations.equals(reloadedDestinations)) {
            reloadedDestinations = destinations;
            fireDestinationsChanged();
        }
    }

    /** Stops all workers, waiting briefly for in-progress attempts to be recorded. The store is not closed. */
    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        started = false;
        watchers.values().forEach(FolderWatcher::stop);
        appAckListeners.values().forEach(ApplicationAckListener::close);
        appAckListeners.clear();
        workers.values().forEach(DestinationWorker::stop);
        long deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS;
        for (Thread t : threads.values()) {
            try {
                t.join(Math.max(1, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Thread t : watcherThreads.values()) {
            try {
                t.join(Math.max(1, deadline - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        workers.clear();
        threads.clear();
        watchers.clear();
        watcherThreads.clear();
        LOG.info("Delivery engine stopped");
    }

    // ---------------------------------------------------------------------------------------------
    // Destinations

    public List<DestinationConfig> destinations() {
        return store.destinations();
    }

    /** Creates or updates a destination and (re)starts its worker. */
    public DestinationConfig saveDestination(DestinationConfig config) {
        return saveDestination(config, null, null);
    }

    /**
     * Creates or updates a destination together with its TLS passwords. The passwords are stored before the
     * worker starts, so its first connection already uses them. {@code null} leaves a password unchanged; an
     * empty string removes it.
     */
    public DestinationConfig saveDestination(DestinationConfig config, String trustStorePassword,
                                             String keyStorePassword) {
        transports.validate(config.transport(), config.transportOptions());
        DestinationConfig withRef = config.secretRef().isEmpty()
                ? config.withSecretRef(java.util.HexFormat.of().formatHex(randomBytes(8))) : config;
        putOrDelete(trustKey(withRef), trustStorePassword);
        putOrDelete(keyKey(withRef), keyStorePassword);
        DestinationConfig saved = store.saveDestination(withRef);
        tlsGeneration.incrementAndGet();
        synchronized (this) {
            if (started) {
                DestinationWorker w = workers.get(saved.id());
                if (w == null) {
                    startWorker(saved);
                } else {
                    w.wake();
                    ensureWatcher(saved);
                    ensureAppAckListener(saved);
                }
            }
        }
        fireDestinationsChanged();
        return saved;
    }

    /** Stops the worker and deletes the destination with all of its messages and history. */
    public void deleteDestination(long destinationId) {
        Optional<DestinationConfig> d = store.destination(destinationId);
        stopWorker(destinationId);
        store.deleteDestination(destinationId);
        d.filter(x -> !x.secretRef().isEmpty()).ifPresent(x -> {
            secrets.delete(trustKey(x));
            secrets.delete(keyKey(x));
        });
        fireDestinationsChanged();
    }

    // ---------------------------------------------------------------------------------------------
    // TLS

    /**
     * Stores the trust-store and key-store passwords for a destination in the secret store.
     * {@code null} leaves a password unchanged; an empty string removes it.
     */
    public void saveTlsPasswords(long destinationId, String trustStorePassword, String keyStorePassword) {
        DestinationConfig d = store.destination(destinationId)
                .orElseThrow(() -> new QueueException("Destination " + destinationId + " does not exist"));
        putOrDelete(trustKey(d), trustStorePassword);
        putOrDelete(keyKey(d), keyStorePassword);
        tlsGeneration.incrementAndGet();
        wake(destinationId);
    }

    /** True if a password is stored for the destination's trust store / key store. */
    public boolean hasTrustStorePassword(DestinationConfig d) {
        return !d.secretRef().isEmpty() && secrets.get(trustKey(d)).isPresent();
    }

    public boolean hasKeyStorePassword(DestinationConfig d) {
        return !d.secretRef().isEmpty() && secrets.get(keyKey(d)).isPresent();
    }

    /** TLS client options for a destination, or empty if TLS is off. */
    public Optional<TlsOptions> tlsOptions(DestinationConfig d) throws IOException {
        if (!d.tls().enabled()) {
            return Optional.empty();
        }
        return Optional.of(TlsContexts.client(d.tls(), password(trustKey(d)), password(keyKey(d))));
    }

    /** Certificates in the destination's trust store and client key store, plus the last server chain seen. */
    public List<CertificateInfo> certificates(DestinationConfig d) throws IOException {
        return certificates(d, null, null);
    }

    /**
     * Like {@link #certificates(DestinationConfig)}, but opens the stores with the given passwords, e.g. ones
     * typed into a form and not yet saved. {@code null} uses the stored password.
     */
    public List<CertificateInfo> certificates(DestinationConfig d, String trustStorePassword,
                                              String keyStorePassword) throws IOException {
        List<CertificateInfo> out = new ArrayList<>();
        if (!d.tls().trustStorePath().isEmpty()) {
            char[] pw = trustStorePassword != null ? trustStorePassword.toCharArray() : password(trustKey(d));
            out.addAll(TlsContexts.inspect(Path.of(d.tls().trustStorePath()), pw, "Trusted"));
        }
        if (!d.tls().keyStorePath().isEmpty()) {
            char[] pw = keyStorePassword != null ? keyStorePassword.toCharArray() : password(keyKey(d));
            out.addAll(TlsContexts.inspect(Path.of(d.tls().keyStorePath()), pw, "Client"));
        }
        DestinationWorker w = workers.get(d.id());
        if (w != null) {
            out.addAll(w.serverCertificates());
        }
        return out;
    }

    /** Warnings for certificates that are expired or expire within {@link CertificateInfo#WARN_DAYS} days. */
    public List<String> certificateWarnings(DestinationConfig d) {
        if (!d.tls().enabled()) {
            return List.of();
        }
        try {
            return certificates(d).stream().map(c -> c.warning(clock)).filter(w -> !w.isEmpty()).distinct().toList();
        } catch (IOException e) {
            return List.of("Cannot read certificates: " + e.getMessage());
        }
    }

    long tlsGeneration() {
        return tlsGeneration.get();
    }

    private char[] password(String key) {
        return secrets.get(key).map(String::toCharArray).orElse(null);
    }

    private void putOrDelete(String key, String value) {
        if (value == null) {
            return;
        }
        if (value.isEmpty()) {
            secrets.delete(key);
        } else {
            secrets.put(key, value);
        }
    }

    static String trustKey(DestinationConfig d) {
        return "destination/" + d.secretRef() + "/truststore";
    }

    static String keyKey(DestinationConfig d) {
        return "destination/" + d.secretRef() + "/keystore";
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) random.nextInt(256);
        }
        return b;
    }

    public void pause(long destinationId) {
        store.setPaused(destinationId, true);
        wake(destinationId);
        fireDestinationsChanged();
    }

    public void resume(long destinationId) {
        store.setPaused(destinationId, false);
        wake(destinationId);
        fireDestinationsChanged();
    }

    /** Delivery statistics for the last {@code window} (attempt outcomes, latency, throughput per minute). */
    public io.hl7sender.core.monitor.DestinationStats stats(long destinationId, java.time.Duration window) {
        java.time.Instant now = clock.instant();
        // [now - window + 1 ms, now + 1 ms): exactly `window` long, and includes attempts that just finished.
        return store.stats(destinationId, now.minus(window).plusMillis(1), now.plusMillis(1));
    }

    public DestinationState state(long destinationId) {
        DestinationWorker w = workers.get(destinationId);
        return w == null ? DestinationState.stopped(destinationId) : w.state();
    }

    // ---------------------------------------------------------------------------------------------
    // Messages

    /**
     * Validates (per the destination's validation policy), stamps (per {@code options}) and adds a message
     * to the end of a destination's queue. Messages with blocking validation errors are not stored. The
     * MSH-10 assigned here is kept for every retry so the receiver can recognise duplicates.
     */
    public EnqueueResult enqueue(long destinationId, String text, SendOptions options) {
        return enqueue(destinationId, text, options, null);
    }

    /** As {@link #enqueue(long, String, SendOptions)}, recording where the message came from. */
    public EnqueueResult enqueue(long destinationId, String text, SendOptions options, String source) {
        DestinationConfig d = store.destination(destinationId)
                .orElseThrow(() -> new QueueException("Destination " + destinationId + " does not exist"));
        PreparedMessage prepared = prepare(d, text, options);
        ValidationReport report = prepared.validation();
        List<String> warnings = new ArrayList<>();
        if (report.hasErrors()) {
            return new EnqueueResult(Optional.empty(), report, warnings);
        }
        duplicateWarning(destinationId, prepared.controlId()).ifPresent(warnings::add);
        QueuedMessage m = store.enqueue(destinationId, prepared.controlId(), prepared.messageType(), prepared.wire(),
                source, null);
        LOG.info("Enqueued {} [{}] as message {} for destination {}", m.messageType(), m.controlId(), m.id(),
                destinationId);
        wake(destinationId);
        fireQueueChanged(destinationId);
        return new EnqueueResult(Optional.of(m), report, warnings);
    }

    /**
     * Adds many messages to a queue, for example from a batch file or folder. Each item is validated
     * per the destination's policy. Valid items are stored in chunks, each chunk in one transaction, and
     * delivery starts while the import is still running. Invalid items are skipped and reported.
     *
     * @param templates if non-null, items containing {@code ${...}} are expanded with it (one expansion per item)
     */
    public BulkResult enqueueAll(long destinationId, List<BulkItem> items, SendOptions options,
                                 TemplateEngine templates, BulkProgress progress) {
        DestinationConfig d = store.destination(destinationId)
                .orElseThrow(() -> new QueueException("Destination " + destinationId + " does not exist"));
        String batchId = "B" + ID_FORMAT.format(LocalDateTime.now(clock)) + "-"
                + Integer.toHexString(random.nextInt(0x10000));
        List<BulkResult.Rejection> rejected = new ArrayList<>();
        Set<String> seenControlIds = new HashSet<>();
        int duplicates = 0;
        int accepted = 0;
        int processed = 0;
        boolean cancelled = false;
        List<QueueStore.NewMessage> chunk = new ArrayList<>(BULK_CHUNK);
        for (BulkItem item : items) {
            if (progress.isCancelled()) {
                cancelled = true;
                break;
            }
            String text = templates != null && TemplateEngine.hasVariables(item.text())
                    ? templates.expand(item.text()).text() : item.text();
            PreparedMessage prepared = prepare(d, text, options);
            if (prepared.validation().hasErrors()) {
                rejected.add(new BulkResult.Rejection(processed, item.source(),
                        prepared.validation().errors().get(0).message()));
            } else {
                String controlId = prepared.controlId();
                if (!controlId.isEmpty() && (!seenControlIds.add(controlId)
                        || store.controlIdExists(destinationId, controlId))) {
                    duplicates++;
                }
                chunk.add(new QueueStore.NewMessage(controlId, prepared.messageType(), prepared.wire(),
                        item.source()));
            }
            processed++;
            if (chunk.size() >= BULK_CHUNK) {
                accepted += flush(destinationId, chunk, batchId);
                progress.onProgress(processed, items.size(), accepted, rejected.size());
            }
        }
        accepted += flush(destinationId, chunk, batchId);
        progress.onProgress(processed, items.size(), accepted, rejected.size());
        List<String> warnings = new ArrayList<>();
        if (duplicates > 0) {
            warnings.add(duplicates + " message(s) reuse an MSH-10 already queued for this destination; "
                    + "the receiver may treat them as duplicates");
        }
        LOG.info("Bulk import {} to destination {}: {} queued, {} rejected{}", batchId, destinationId, accepted,
                rejected.size(), cancelled ? " (cancelled)" : "");
        return new BulkResult(batchId, items.size(), accepted, rejected, warnings, cancelled);
    }

    /**
     * Queues one message to several destinations. MSH-7/MSH-10 are generated once (per {@code options}), so
     * every destination gets the same message; each copy is validated with that destination's policy and
     * delivered and tracked independently. All copies share a batch ID.
     */
    public FanOutResult enqueueFanOut(List<Long> destinationIds, String text, SendOptions options, String source) {
        PreparedMessage stamped = sender.prepare(text, options,
                io.hl7sender.core.hl7.validation.ValidationLevel.LENIENT, null);
        String batchId = "F" + ID_FORMAT.format(LocalDateTime.now(clock)) + "-"
                + Integer.toHexString(random.nextInt(0x10000));
        SendOptions asIs = new SendOptions(false, false, options.ackMode());
        java.util.Map<Long, EnqueueResult> results = new java.util.LinkedHashMap<>();
        for (long id : destinationIds) {
            Optional<DestinationConfig> d = store.destination(id);
            if (d.isEmpty()) {
                continue;
            }
            PreparedMessage p = stamped.validation().hasErrors() ? stamped : prepare(d.get(), stamped.wire(), asIs);
            if (p.validation().hasErrors()) {
                results.put(id, new EnqueueResult(Optional.empty(), p.validation(), List.of()));
                continue;
            }
            List<String> warnings = new ArrayList<>();
            duplicateWarning(id, p.controlId()).ifPresent(warnings::add);
            QueuedMessage m = store.enqueue(id, p.controlId(), p.messageType(), p.wire(), source, batchId);
            wake(id);
            fireQueueChanged(id);
            results.put(id, new EnqueueResult(Optional.of(m), p.validation(), warnings));
        }
        LOG.info("Fan-out {}: queued to {} of {} destination(s)", batchId,
                results.values().stream().filter(EnqueueResult::accepted).count(), destinationIds.size());
        return new FanOutResult(batchId, results);
    }

    private int flush(long destinationId, List<QueueStore.NewMessage> chunk, String batchId) {
        if (chunk.isEmpty()) {
            return 0;
        }
        int n = store.enqueueAll(destinationId, chunk, batchId).size();
        chunk.clear();
        wake(destinationId);
        fireQueueChanged(destinationId);
        return n;
    }

    private PreparedMessage prepare(DestinationConfig d, String text, SendOptions options) {
        return prepare(d, text, options, true);
    }

    /**
     * Runs the destination's script (unless {@code runScript} is false), then stamps and validates. A filtered
     * message or a script error is reported as a validation error from {@link ValidationIssue.Source#SCRIPT}, so
     * the message is not queued.
     */
    private PreparedMessage prepare(DestinationConfig d, String text, SendOptions options, boolean runScript) {
        if (runScript && !d.script().isBlank()) {
            String problem;
            try {
                MessageScript.Result r = MessageScript.cached(d.script()).apply(text);
                problem = r.filtered() ? "Filtered out by the destination's script"
                        + (r.filterReason().isEmpty() ? "" : ": " + r.filterReason()) : null;
                text = r.message();
            } catch (MessageScript.ScriptFailure e) {
                problem = e.getMessage();
            }
            if (problem != null) {
                return new PreparedMessage(text, "", "", new ValidationReport(Optional.empty(), 0,
                        List.of(ValidationIssue.error(ValidationIssue.Source.SCRIPT, problem))));
            }
        }
        ConformanceProfile profile = null;
        if (d.profile().isPresent()) {
            try {
                profile = ConformanceProfile.cached(d.profile().get());
            } catch (IOException e) {
                ValidationReport failed = new ValidationReport(Optional.empty(), 0, List.of(ValidationIssue.error(
                        ValidationIssue.Source.PROFILE, "Conformance profile " + d.profilePath()
                                + " could not be loaded: " + e.getMessage())));
                return new PreparedMessage(text, "", "", failed);
            }
        }
        return sender.prepare(text, options, d.validationLevel(), profile);
    }

    private Optional<String> duplicateWarning(long destinationId, String controlId) {
        if (controlId.isEmpty()) {
            return Optional.of("MSH-10 is empty, so the ACK cannot be matched to this message");
        }
        if (store.controlIdExists(destinationId, controlId)) {
            return Optional.of("MSH-10 '" + controlId + "' was already queued for this destination; "
                    + "the receiver may treat it as a duplicate");
        }
        return Optional.empty();
    }

    /** The transports destinations can use (built-in ones plus plugins). */
    public io.hl7sender.core.transport.Transports transports() {
        return transports;
    }

    /** Replaces the available transports, e.g. with those loaded from the plugins folder. */
    public void setTransports(io.hl7sender.core.transport.Transports t) {
        this.transports = t;
    }

    /** Runs {@code hook} whenever {@link #reload()} is called, e.g. so the scheduler re-reads its schedules. */
    public void onReload(Runnable hook) {
        reloadHooks.add(hook);
    }

    /**
     * The result of replaying one message.
     *
     * @param sourceId the message that was replayed
     * @param copy     the new message, or empty if it could not be queued
     * @param problem  why it was not queued, or empty
     */
    public record Replayed(long sourceId, Optional<QueuedMessage> copy, String problem) {
    }

    /**
     * Queues copies of earlier messages again, in the order given, in any state. The originals are not changed.
     * All copies share a batch ID, so they can be followed together in History.
     *
     * @param target       destination for the copies, or null to send each one to its original destination
     * @param newControlIds give each copy a new MSH-10 and MSH-7; otherwise they are sent exactly as before, which the
     *                     receiver may treat as duplicates
     */
    public List<Replayed> replay(List<Long> messageIds, Long target, boolean newControlIds) {
        String batchId = "R" + ID_FORMAT.format(LocalDateTime.now(clock)) + "-"
                + Integer.toHexString(random.nextInt(0x10000));
        SendOptions options = newControlIds ? SendOptions.DEFAULTS : SendOptions.AS_IS;
        List<Replayed> out = new ArrayList<>();
        java.util.Set<Long> touched = new java.util.LinkedHashSet<>();
        for (long id : messageIds) {
            Optional<QueuedMessage> original = store.message(id);
            if (original.isEmpty()) {
                out.add(new Replayed(id, Optional.empty(), "message " + id + " does not exist"));
                continue;
            }
            long destinationId = target == null ? original.get().destinationId() : target;
            Optional<DestinationConfig> d = store.destination(destinationId);
            if (d.isEmpty()) {
                out.add(new Replayed(id, Optional.empty(), "destination " + destinationId + " does not exist"));
                continue;
            }
            // The stored payload was already transformed when it was first queued, so scripts do not run again.
            PreparedMessage p = prepare(d.get(), original.get().payload(), options, false);
            if (p.validation().hasErrors()) {
                out.add(new Replayed(id, Optional.empty(), p.validation().errors().get(0).message()));
                continue;
            }
            QueuedMessage copy = store.enqueue(destinationId, p.controlId(), p.messageType(), p.wire(),
                    "Replay of message " + id, batchId);
            touched.add(destinationId);
            out.add(new Replayed(id, Optional.of(copy), ""));
        }
        for (long destinationId : touched) {
            wake(destinationId);
            fireQueueChanged(destinationId);
        }
        LOG.info("Replay {}: {} of {} message(s) queued again", batchId,
                out.stream().filter(r -> r.copy().isPresent()).count(), messageIds.size());
        return out;
    }

    /** Re-queues a dead-lettered (or delivered) message at the end of its queue with a fresh retry budget. */
    public boolean requeue(long messageId) {
        return afterChange(messageId, store.requeue(messageId, store.userActor()));
    }

    /** Moves a waiting message to the dead-letter queue, which unblocks the messages behind it. */
    public boolean moveToDeadLetter(long messageId, String reason) {
        return afterChange(messageId, store.moveToDeadLetter(messageId, store.userActor(),
                reason == null || reason.isBlank() ? "Moved to dead-letter queue by user" : reason));
    }

    /** Makes a message that is waiting to be retried due immediately. */
    public boolean retryNow(long messageId) {
        return afterChange(messageId, store.retryNow(messageId, store.userActor()));
    }

    /**
     * Replaces the content of a dead-lettered message. The text is validated and sent exactly as
     * written (no MSH stamping). Returns the validation report. Nothing is saved if it has errors.
     */
    public ValidationReport editDeadLetter(long messageId, String text) {
        SendOptions asIs = new SendOptions(false, false, io.hl7sender.core.send.AckMode.EXPECT_ACK);
        PreparedMessage prepared = store.message(messageId)
                .flatMap(m -> store.destination(m.destinationId()))
                .map(d -> prepare(d, text, asIs, false))
                .orElseGet(() -> sender.prepare(text, asIs));
        if (!prepared.validation().hasErrors()) {
            afterChange(messageId, store.updatePayload(messageId, prepared.controlId(), prepared.messageType(),
                    prepared.wire(), store.userActor()));
        }
        return prepared.validation();
    }

    /** Deletes a message that is not in flight. */
    public boolean delete(long messageId) {
        Optional<QueuedMessage> m = store.message(messageId);
        boolean deleted = store.delete(messageId);
        if (deleted) {
            m.ifPresent(q -> {
                wake(q.destinationId());
                fireQueueChanged(q.destinationId());
            });
        }
        return deleted;
    }

    private boolean afterChange(long messageId, boolean changed) {
        if (changed) {
            store.message(messageId).ifPresent(m -> {
                wake(m.destinationId());
                fireQueueChanged(m.destinationId());
            });
        }
        return changed;
    }

    // ---------------------------------------------------------------------------------------------

    private void startWorker(DestinationConfig d) {
        DestinationWorker w = new DestinationWorker(d, store, sender, clock, random, this::fireStateChanged,
                () -> fireQueueChanged(d.id()), this);
        workers.put(d.id(), w);
        Thread t = Thread.ofPlatform().daemon().name("delivery-" + d.id() + "-" + d.name()).start(w);
        threads.put(d.id(), t);
        ensureWatcher(d);
        ensureAppAckListener(d);
    }

    /**
     * Starts, restarts or stops the destination's application ACK listener to match its settings. If the port
     * cannot be opened (already in use, say), the problem is logged and audited, and messages that wait for an
     * application ACK time out.
     */
    private synchronized void ensureAppAckListener(DestinationConfig d) {
        ApplicationAckListener existing = appAckListeners.get(d.id());
        boolean wanted = started && d.matchesAppAcks();
        if (existing != null && (!wanted || existing.port() != d.appAckPort())) {
            appAckListeners.remove(d.id());
            existing.close();
            existing = null;
        }
        if (!wanted || existing != null) {
            return;
        }
        ApplicationAckListener listener = new ApplicationAckListener(d.id(), d.appAckPort(), this);
        try {
            listener.start();
            appAckListeners.put(d.id(), listener);
        } catch (IOException e) {
            String detail = "Cannot listen for application ACKs on port " + d.appAckPort() + ": " + e.getMessage();
            LOG.error("Destination '{}': {}", d.name(), detail);
            store.auditDestination(d.id(), QueueStore.ACTOR_ENGINE, detail);
        }
    }

    /** True if the destination's application ACK listener is running. */
    public boolean isListeningForAppAcks(long destinationId) {
        return appAckListeners.containsKey(destinationId);
    }

    /**
     * Applies an application ACK from a destination's receiver to the message it acknowledges (see
     * {@link QueueStore#applyApplicationAck}). Used by the destination's application ACK listener.
     */
    Optional<QueuedMessage> applyApplicationAck(long destinationId, io.hl7sender.core.ack.ParsedAck ack,
                                                String source) {
        Optional<QueuedMessage> m = store.applyApplicationAck(destinationId, ack, source);
        if (m.isPresent()) {
            LOG.info("Application ACK {} from {} for [{}]: message is now {}", ack.code(), source, ack.controlId(),
                    m.get().status());
            fireQueueChanged(destinationId);
        } else {
            LOG.warn("Application ACK {} from {} for [{}] matches no waiting message of destination {}", ack.code(),
                    source, ack.controlId(), destinationId);
        }
        return m;
    }

    /** Starts a folder watcher if the destination has a watch folder and none is running. */
    private synchronized void ensureWatcher(DestinationConfig d) {
        if (!started || d.watchPath().isEmpty()) {
            return;
        }
        Thread existing = watcherThreads.get(d.id());
        if (existing != null && existing.isAlive()) {
            return;
        }
        FolderWatcher w = new FolderWatcher(d.id(), this, store, watchPollMillis);
        watchers.put(d.id(), w);
        watcherThreads.put(d.id(), Thread.ofPlatform().daemon().name("watch-" + d.id()).start(w));
    }

    private void stopWorker(long destinationId) {
        ApplicationAckListener appAcks = appAckListeners.remove(destinationId);
        if (appAcks != null) {
            appAcks.close();
        }
        FolderWatcher watcher = watchers.remove(destinationId);
        Thread watcherThread = watcherThreads.remove(destinationId);
        if (watcher != null) {
            watcher.stop();
        }
        if (watcherThread != null) {
            try {
                watcherThread.join(STOP_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        DestinationWorker w = workers.remove(destinationId);
        Thread t = threads.remove(destinationId);
        if (w != null) {
            w.stop();
        }
        if (t != null) {
            try {
                t.join(STOP_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wake(long destinationId) {
        DestinationWorker w = workers.get(destinationId);
        if (w != null) {
            w.wake();
        }
    }

    private void fireQueueChanged(long destinationId) {
        for (QueueListener l : listeners) {
            try {
                l.onQueueChanged(destinationId);
            } catch (RuntimeException e) {
                LOG.warn("Queue listener failed", e);
            }
        }
    }

    private void fireStateChanged(DestinationState state) {
        for (QueueListener l : listeners) {
            try {
                l.onStateChanged(state);
            } catch (RuntimeException e) {
                LOG.warn("Queue listener failed", e);
            }
        }
    }

    private void fireDestinationsChanged() {
        for (QueueListener l : listeners) {
            try {
                l.onDestinationsChanged();
            } catch (RuntimeException e) {
                LOG.warn("Queue listener failed", e);
            }
        }
    }
}
