package io.hl7sender.core.script;

import io.hl7sender.core.hl7.Hl7FormatException;
import io.hl7sender.core.hl7.MutableMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.LambdaFunction;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.SerializableCallable;
import org.mozilla.javascript.Undefined;

/**
 * A JavaScript transform run on each message before it is queued, like a Mirth Connect transformer: it can change
 * fields, add or remove segments, or filter the message out.
 *
 * <pre>
 * // Every message to this destination goes to the test facility, without next-of-kin details.
 * msg.set('MSH-6', 'TESTFAC');
 * msg.remove('NK1');
 * if (msg.type() == 'ADT^A08' &amp;&amp; msg.get('PV1-2') == 'O') filter('outpatient updates are not needed');
 * msg.add('ZHS|1|' + msg.get('PID-3.1'));
 * </pre>
 *
 * <p>Available to scripts: {@code msg.get(path)}, {@code msg.set(path, value)}, {@code msg.type()},
 * {@code msg.count(segment)}, {@code msg.remove(segment)}, {@code msg.add(segmentText)}, {@code msg.text()},
 * {@code filter(reason)} and {@code log(text)}, plus standard JavaScript (ES6). Paths are {@link
 * io.hl7sender.core.hl7.FieldPath}s such as {@code PID-5.1} or {@code OBX(2)-5}.
 *
 * <p>Scripts run in a sandbox: no Java classes, files, network or {@code eval} of host objects, and at most one
 * second of work per message, so an endless loop fails that message instead of stopping delivery. Thread-safe;
 * compiled once and reused.
 */
public final class MessageScript {

    /** Longest a script may run for one message. */
    public static final long TIME_LIMIT_MS = 1_000;
    private static final int OBSERVE_EVERY = 10_000;
    private static final Map<String, MessageScript> CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private static final SandboxFactory FACTORY = new SandboxFactory();
    private static final ScriptableObject SHARED_SCOPE = sharedScope();

    private final String source;
    private final Script compiled;

    private MessageScript(String source, Script compiled) {
        this.source = source;
        this.compiled = compiled;
    }

    /**
     * Compiles a script.
     *
     * @throws ScriptFailure if it has a syntax error (with the line number)
     */
    public static MessageScript compile(String source) {
        Context cx = FACTORY.enterContext();
        try {
            return new MessageScript(source, cx.compileString(source, "script", 1, null));
        } catch (EvaluatorException e) {
            throw new ScriptFailure("Syntax error on line " + e.lineNumber() + ": " + e.details(), e);
        } finally {
            Context.exit();
        }
    }

    /** A compiled script for {@code source}, reused for the same text. */
    public static MessageScript cached(String source) {
        MessageScript s = CACHE.get(source);
        if (s == null) {
            s = compile(source);
            if (CACHE.size() > 100) {
                CACHE.clear();
            }
            CACHE.put(source, s);
        }
        return s;
    }

    public String source() {
        return source;
    }

    /**
     * The outcome for one message.
     *
     * @param message      the changed message (CR-separated), or the original if it was filtered
     * @param filtered     true if the script called {@code filter()}: the message should not be queued
     * @param filterReason what the script gave to {@code filter()}, or empty
     * @param log          what the script passed to {@code log()}, in order
     */
    public record Result(String message, boolean filtered, String filterReason, List<String> log) {
    }

    /**
     * Runs the script on {@code message}.
     *
     * @throws ScriptFailure if the message cannot be parsed, or the script throws, times out or breaks the message
     */
    public Result apply(String message) {
        MutableMessage msg;
        try {
            msg = MutableMessage.parse(message);
        } catch (Hl7FormatException | IllegalArgumentException e) {
            throw new ScriptFailure("The message cannot be transformed: " + e.getMessage(), e);
        }
        List<String> log = new ArrayList<>();
        String[] filterReason = {null};
        Context cx = FACTORY.enterContext();
        DEADLINE.set(System.nanoTime() + TIME_LIMIT_MS * 1_000_000);
        try {
            ScriptableObject scope = (ScriptableObject) cx.newObject(SHARED_SCOPE);
            scope.setPrototype(SHARED_SCOPE);
            scope.setParentScope(null);
            ScriptableObject.putProperty(scope, "msg", messageObject(cx, scope, msg));
            ScriptableObject.putProperty(scope, "log", fn(scope, "log", (c, s, t, a) -> {
                if (log.size() < 1_000) {
                    log.add(a.length == 0 ? "" : Context.toString(a[0]));
                }
                return Undefined.instance;
            }));
            ScriptableObject.putProperty(scope, "filter", fn(scope, "filter", (c, s, t, a) -> {
                filterReason[0] = a.length == 0 || a[0] == Undefined.instance ? "" : Context.toString(a[0]);
                return Undefined.instance;
            }));
            compiled.exec(cx, scope, scope);
        } catch (Timeout e) {
            throw new ScriptFailure("The script ran for more than " + TIME_LIMIT_MS + " ms and was stopped", e);
        } catch (RhinoException e) {
            throw new ScriptFailure("Script error on line " + e.lineNumber() + ": " + e.details(), e);
        } finally {
            DEADLINE.remove();
            Context.exit();
        }
        String out = msg.text();
        try {
            MutableMessage.parse(out);
        } catch (Hl7FormatException e) {
            throw new ScriptFailure("The script left an invalid message: " + e.getMessage(), e);
        }
        boolean filtered = filterReason[0] != null;
        return new Result(filtered ? message : out, filtered, filtered ? filterReason[0] : "", List.copyOf(log));
    }

    private static NativeObject messageObject(Context cx, ScriptableObject scope, MutableMessage msg) {
        NativeObject o = (NativeObject) cx.newObject(scope);
        o.put("get", o, fn(scope, "get", (c, s, t, a) -> msg.get(arg(a, 0))));
        o.put("set", o, fn(scope, "set", (c, s, t, a) -> {
            msg.set(arg(a, 0), arg(a, 1));
            return Undefined.instance;
        }));
        o.put("type", o, fn(scope, "type", (c, s, t, a) -> msg.type()));
        o.put("count", o, fn(scope, "count", (c, s, t, a) -> msg.count(arg(a, 0))));
        o.put("remove", o, fn(scope, "remove", (c, s, t, a) -> msg.remove(arg(a, 0))));
        o.put("add", o, fn(scope, "add", (c, s, t, a) -> {
            msg.add(arg(a, 0));
            return Undefined.instance;
        }));
        o.put("text", o, fn(scope, "text", (c, s, t, a) -> msg.text()));
        return o;
    }

    private static String arg(Object[] args, int i) {
        return i < args.length && args[i] != Undefined.instance && args[i] != null ? Context.toString(args[i]) : "";
    }

    /** A function for scripts; a problem such as a bad field path becomes a JavaScript error with a line number. */
    private static LambdaFunction fn(ScriptableObject scope, String name, SerializableCallable body) {
        return new LambdaFunction(scope, name, 0, (cx, s, thisObj, args) -> {
            try {
                return body.call(cx, s, thisObj, args);
            } catch (IllegalArgumentException | io.hl7sender.core.hl7.Hl7FormatException e) {
                throw Context.reportRuntimeError(e.getMessage());
            }
        });
    }

    private static ScriptableObject sharedScope() {
        Context cx = FACTORY.enterContext();
        try {
            // Standard JavaScript objects only: no Packages, java or JavaImporter.
            ScriptableObject scope = cx.initSafeStandardObjects(null, true);
            scope.sealObject();
            return scope;
        } finally {
            Context.exit();
        }
    }

    /** Thrown when a script cannot be compiled or fails on a message. */
    public static final class ScriptFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ScriptFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Stops a script that runs too long. An Error, so scripts cannot catch it. */
    private static final class Timeout extends Error {
        private static final long serialVersionUID = 1L;
    }

    /** Interpreted contexts with no Java access and an instruction budget. */
    private static final class SandboxFactory extends ContextFactory {
        @Override
        protected Context makeContext() {
            Context cx = super.makeContext();
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInterpretedMode(true);
            cx.setInstructionObserverThreshold(OBSERVE_EVERY);
            cx.setMaximumInterpreterStackDepth(1_000);
            cx.setClassShutter(className -> false);
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            Long deadline = DEADLINE.get();
            if (deadline != null && System.nanoTime() - deadline > 0) {
                throw new Timeout();
            }
        }
    }
}
