package io.hl7sender.core.queue;

import io.hl7sender.core.send.Hl7Sender;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Separate-process delivery engine for {@link CrashRecoveryTest}. It takes the instance lock, runs
 * the engine on the given database and waits to be killed.
 */
public final class ChaosChild {

    private ChaosChild() {
    }

    public static void main(String[] args) throws Exception {
        Path db = Path.of(args[0]);
        Optional<InstanceLock> lock = InstanceLock.tryAcquire(db.resolveSibling("queue.lock"));
        if (lock.isEmpty()) {
            System.out.println("LOCKED");
            System.exit(3);
        }
        QueueStore store = QueueStore.open(db);
        DeliveryEngine engine = new DeliveryEngine(store, new Hl7Sender());
        engine.start();
        System.out.println("READY");
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
