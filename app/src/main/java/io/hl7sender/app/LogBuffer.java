package io.hl7sender.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

/**
 * Keeps the most recent log events in memory for the Logs tab. Attached to the root logger at startup, so it
 * sees exactly what the log files see (which never includes message content).
 */
final class LogBuffer extends AppenderBase<ILoggingEvent> {

    /**
     * One log line.
     *
     * @param time    when it was logged
     * @param level   TRACE, DEBUG, INFO, WARN or ERROR
     * @param logger  short logger name
     * @param thread  thread name
     * @param message formatted message, plus the exception summary if any
     */
    record Entry(Instant time, Level level, String logger, String thread, String message) {
    }

    static final int CAPACITY = 5_000;
    private static final String NAME = "HL7_SENDER_MEMORY";
    private static final LogBuffer INSTANCE = new LogBuffer();

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<Consumer<Entry>> listeners = new CopyOnWriteArrayList<>();

    private LogBuffer() {
        setName(NAME);
    }

    /** The shared buffer, attached to the root logger on first use (if Logback is the SLF4J backend). */
    static synchronized LogBuffer install() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) {
            Logger root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            if (root.getAppender(NAME) == null) {
                INSTANCE.setContext(ctx);
                INSTANCE.start();
                root.addAppender(INSTANCE);
            }
        }
        return INSTANCE;
    }

    List<Entry> snapshot() {
        synchronized (entries) {
            return new ArrayList<>(entries);
        }
    }

    void addListener(Consumer<Entry> listener) {
        listeners.add(listener);
    }

    void removeListener(Consumer<Entry> listener) {
        listeners.remove(listener);
    }

    @Override
    protected void append(ILoggingEvent e) {
        String name = e.getLoggerName();
        String shortName = name.substring(name.lastIndexOf('.') + 1);
        String message = e.getFormattedMessage();
        IThrowableProxy t = e.getThrowableProxy();
        if (t != null) {
            message += " [" + t.getClassName() + (t.getMessage() == null ? "" : ": " + t.getMessage()) + "]";
        }
        Entry entry = new Entry(Instant.ofEpochMilli(e.getTimeStamp()), e.getLevel(), shortName, e.getThreadName(),
                message);
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > CAPACITY) {
                entries.removeFirst();
            }
        }
        for (Consumer<Entry> l : listeners) {
            l.accept(entry);
        }
    }
}
