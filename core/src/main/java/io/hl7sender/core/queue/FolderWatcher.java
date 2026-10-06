package io.hl7sender.core.queue;

import io.hl7sender.core.batch.MessageFiles;
import io.hl7sender.core.batch.SplitResult;
import io.hl7sender.core.send.SendOptions;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a destination's inbox folder and queues every HL7 file that appears in it.
 *
 * <ul>
 *   <li>A file is picked up only when its size and modification time have not changed between two polls,
 *       so files still being written are left alone.</li>
 *   <li>Messages are queued exactly as written (MSH-7 and MSH-10 are not regenerated), validated per the
 *       destination's policy.</li>
 *   <li>When every message is queued, the file moves to {@code processed/}. If any message is rejected or the
 *       file cannot be read as HL7, it moves to {@code error/} with a {@code .error.txt} report. Valid
 *       messages from that file are still queued.</li>
 * </ul>
 *
 * <p>Polling is used instead of OS file events because it behaves the same on every platform and on
 * network shares.
 */
final class FolderWatcher implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(FolderWatcher.class);
    static final String PROCESSED = "processed";
    static final String ERROR = "error";
    private static final DateTimeFormatter SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private record Snapshot(long size, long modified) {
    }

    private final long destinationId;
    private final DeliveryEngine engine;
    private final QueueStore store;
    private final long pollMillis;
    private final Object signal = new Object();
    private final Map<Path, Snapshot> lastSeen = new HashMap<>();
    private volatile boolean running = true;
    private Path warnedMissing;

    FolderWatcher(long destinationId, DeliveryEngine engine, QueueStore store, long pollMillis) {
        this.destinationId = destinationId;
        this.engine = engine;
        this.store = store;
        this.pollMillis = pollMillis;
    }

    void stop() {
        running = false;
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    @Override
    public void run() {
        LOG.info("Folder watcher started for destination {}", destinationId);
        while (running) {
            Optional<DestinationConfig> config;
            try {
                config = store.destination(destinationId);
            } catch (QueueException e) {
                LOG.warn("Folder watcher for destination {}: {}", destinationId, e.getMessage());
                sleep();
                continue;
            }
            if (config.isEmpty() || config.get().watchPath().isEmpty()) {
                break;
            }
            try {
                poll(config.get());
            } catch (IOException | RuntimeException e) {
                LOG.warn("Folder watcher for destination {} failed: {}", destinationId, e.getMessage());
            }
            sleep();
        }
        LOG.info("Folder watcher stopped for destination {}", destinationId);
    }

    /** One scan of the inbox. Package-private for tests. */
    void poll(DestinationConfig d) throws IOException {
        Path inbox = d.watchPath().orElseThrow();
        if (!Files.isDirectory(inbox)) {
            if (!inbox.equals(warnedMissing)) {
                LOG.warn("Watch folder {} for destination '{}' does not exist", inbox, d.name());
                warnedMissing = inbox;
            }
            return;
        }
        warnedMissing = null;
        List<Path> files = MessageFiles.list(inbox);
        lastSeen.keySet().retainAll(files);
        for (Path file : files) {
            if (!running) {
                return;
            }
            Snapshot now;
            try {
                now = new Snapshot(Files.size(file), Files.getLastModifiedTime(file).toMillis());
            } catch (IOException e) {
                continue;
            }
            Snapshot before = lastSeen.put(file, now);
            if (now.equals(before)) {
                process(d, inbox, file);
                lastSeen.remove(file);
            }
        }
    }

    private void process(DestinationConfig d, Path inbox, Path file) {
        String name = String.valueOf(file.getFileName());
        SplitResult split;
        try {
            split = MessageFiles.read(file, Charset.forName(d.charset()));
        } catch (IOException e) {
            // Possibly locked by the writer (Windows); try again on the next poll.
            LOG.debug("Cannot read {} yet: {}", file, e.getMessage());
            return;
        }
        List<BulkItem> items = new ArrayList<>();
        for (int i = 0; i < split.messages().size(); i++) {
            items.add(new BulkItem(split.messages().get(i), name + "#" + (i + 1)));
        }
        List<String> problems = new ArrayList<>(split.warnings());
        BulkResult result = null;
        if (items.isEmpty()) {
            problems.add("No HL7 messages found in the file");
        } else {
            result = engine.enqueueAll(destinationId, items, new SendOptions(false, false, d.ackMode()), null,
                    BulkProgress.NONE);
            for (BulkResult.Rejection r : result.rejected()) {
                problems.add("Message " + (r.index() + 1) + " (" + r.source() + ") not queued: " + r.reason());
            }
        }
        boolean ok = result != null && result.rejected().isEmpty();
        String summary = "Watched file " + name + ": " + (result == null ? 0 : result.accepted()) + " queued"
                + (result == null || result.rejected().isEmpty() ? "" : ", " + result.rejected().size() + " rejected")
                + (result == null ? "" : " (batch " + result.batchId() + ")");
        try {
            Path moved = move(file, inbox.resolve(ok ? PROCESSED : ERROR));
            if (!ok) {
                Files.writeString(moved.resolveSibling(String.valueOf(moved.getFileName()) + ".error.txt"),
                        String.join(System.lineSeparator(), problems) + System.lineSeparator(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOG.error("Could not move {} out of the watch folder; it may be imported again: {}", file, e.getMessage());
        }
        store.auditDestination(destinationId, QueueStore.ACTOR_ENGINE, summary);
        if (ok) {
            LOG.info("{}", summary);
        } else {
            LOG.warn("{}; see {}", summary, ERROR + "/" + name + ".error.txt");
        }
    }

    static Path move(Path file, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        String name = String.valueOf(file.getFileName());
        Path target = targetDir.resolve(name);
        if (Files.exists(target)) {
            int dot = name.lastIndexOf('.');
            String suffix = "-" + SUFFIX.format(LocalDateTime.now());
            target = targetDir.resolve(dot > 0 ? name.substring(0, dot) + suffix + name.substring(dot) : name + suffix);
        }
        try {
            return Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            return Files.move(file, target);
        }
    }

    private void sleep() {
        synchronized (signal) {
            if (running) {
                try {
                    signal.wait(pollMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running = false;
                }
            }
        }
    }
}
