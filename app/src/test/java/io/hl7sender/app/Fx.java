package io.hl7sender.app;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.testfx.util.WaitForAsyncUtils;

/** Test helper: reads JavaFX state on the FX thread, where lists that the UI rebuilds cannot change mid-read. */
final class Fx {

    private Fx() {
    }

    static <T> T call(Callable<T> read) {
        try {
            return WaitForAsyncUtils.asyncFx(read).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException(e);
        }
    }
}
