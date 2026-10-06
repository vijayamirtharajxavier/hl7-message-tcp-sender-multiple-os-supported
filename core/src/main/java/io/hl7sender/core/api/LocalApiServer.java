package io.hl7sender.core.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.hl7sender.core.AppInfo;
import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.batch.SplitResult;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.queue.BulkItem;
import io.hl7sender.core.queue.BulkProgress;
import io.hl7sender.core.queue.BulkResult;
import io.hl7sender.core.queue.DeliveryEngine;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.EnqueueResult;
import io.hl7sender.core.queue.MessageQuery;
import io.hl7sender.core.queue.MessageStatus;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.queue.QueuedMessage;
import io.hl7sender.core.schedule.Schedule;
import io.hl7sender.core.schedule.Scheduler;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.template.TemplateEngine;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A small REST API for test harnesses and scripts, served by the process that delivers from the queue (the app
 * or {@code hl7send serve}). It listens on the loopback interface only and requires the bearer token from
 * {@link ApiToken} on every request except {@code GET /api/v1/health}.
 *
 * <p>Browsers cannot use it from a web page: requests must carry the token header (which forces a CORS
 * pre-flight that is never approved), and a {@code Host} header other than localhost is refused, which stops
 * DNS-rebinding attacks.
 *
 * <pre>
 * GET    /api/v1/health                               (no token) {"status":"ok","version":...}
 * GET    /api/v1/destinations                         destinations with state and message counts
 * GET    /api/v1/destinations/{id|name}               one destination
 * GET    /api/v1/destinations/{id|name}/stats?minutes=60
 * POST   /api/v1/destinations/{id|name}/messages      body: HL7 text; ?split=true for batch files;
 *                                                     ?controlId=keep, ?timestamp=keep to send MSH-10/MSH-7 as is
 * POST   /api/v1/destinations/{id|name}/pause | resume
 * GET    /api/v1/messages?destination=&status=&batch=&controlId=&limit=
 * GET    /api/v1/messages/{id}?payload=true           status, attempts and ACK codes (payload only on request)
 * POST   /api/v1/messages/{id}/requeue | retry
 * POST   /api/v1/messages/{id}/replay?destination=&amp;newControlIds=true   queue a copy (202)
 * DELETE /api/v1/messages/{id}
 * GET    /api/v1/schedules                            schedules with their next run
 * POST   /api/v1/schedules/{id|name}/run              run a schedule now
 * POST   /api/v1/reload                               pick up changes made by another process
 * </pre>
 */
public final class LocalApiServer implements AutoCloseable {

    /** Default port; any free port can be configured. */
    public static final int DEFAULT_PORT = 8742;
    static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    private static final String PREFIX = "/api/v1/";
    private static final Logger LOG = LoggerFactory.getLogger(LocalApiServer.class);

    private final DeliveryEngine engine;
    private final TemplateEngine templates = new TemplateEngine();
    private final Supplier<String> token;
    private final HttpServer server;
    private final ExecutorService executor;
    private final AppPaths paths;
    private final ApiEndpoint endpoint;

    private LocalApiServer(DeliveryEngine engine, Supplier<String> token, HttpServer server, ExecutorService executor,
                           AppPaths paths) {
        this.engine = engine;
        this.token = token;
        this.server = server;
        this.executor = executor;
        this.paths = paths;
        this.endpoint = new ApiEndpoint(server.getAddress().getPort(), ProcessHandle.current().pid());
    }

    /**
     * Starts the API on {@code 127.0.0.1:port} (0 picks a free port).
     *
     * @param paths if non-null, {@code api.json} is written there so the CLI can find the server
     */
    public static LocalApiServer start(DeliveryEngine engine, int port, String token, AppPaths paths)
            throws IOException {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException("API token must be at least 32 characters");
        }
        return start(engine, port, () -> token, paths);
    }

    /**
     * Starts the API with a token that may change while it runs (see {@link ApiToken#watching}); a null or short
     * token refuses every authenticated request.
     */
    public static LocalApiServer start(DeliveryEngine engine, int port, Supplier<String> token, AppPaths paths)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
        ExecutorService executor = Executors.newFixedThreadPool(4,
                Thread.ofPlatform().daemon().name("hl7-api-", 0).factory());
        server.setExecutor(executor);
        LocalApiServer api = new LocalApiServer(engine, token, server, executor, paths);
        server.createContext("/", api::handle);
        server.start();
        if (paths != null) {
            api.endpoint.write(paths);
        }
        LOG.info("Local API listening on http://127.0.0.1:{}{}", api.port(), PREFIX);
        return api;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        if (paths != null) {
            endpoint.delete(paths);
        }
        LOG.info("Local API stopped");
    }

    // ---------------------------------------------------------------------------------------------

    private record Response(int status, Object body) {
    }

    private void handle(HttpExchange ex) throws IOException {
        Response r;
        try {
            r = route(ex);
        } catch (NotFound e) {
            r = error(404, e.getMessage());
        } catch (BadRequest | IllegalArgumentException e) {
            r = error(400, e.getMessage());
        } catch (QueueException e) {
            r = error(503, "Queue unavailable: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("API request {} {} failed", ex.getRequestMethod(), ex.getRequestURI().getPath(), e);
            r = error(500, "Internal error");
        }
        LOG.debug("API {} {} -> {}", ex.getRequestMethod(), ex.getRequestURI().getPath(), r.status());
        byte[] body = JsonViews.COMPACT.writeValueAsBytes(r.body());
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        if (r.status() == 401) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
        }
        ex.sendResponseHeaders(r.status(), body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private Response route(HttpExchange ex) throws IOException {
        if (!localHost(ex.getRequestHeaders().getFirst("Host"))) {
            return error(403, "Only requests addressed to localhost are accepted");
        }
        String path = ex.getRequestURI().getPath();
        if (!path.startsWith(PREFIX)) {
            throw new NotFound("Not found; the API is under " + PREFIX);
        }
        List<String> seg = new ArrayList<>();
        for (String s : path.substring(PREFIX.length()).split("/")) {
            if (!s.isEmpty()) {
                seg.add(URLDecoder.decode(s, StandardCharsets.UTF_8));
            }
        }
        String method = ex.getRequestMethod().toUpperCase(Locale.ROOT);
        if (seg.equals(List.of("health")) && method.equals("GET")) {
            return ok(Map.of("status", "ok", "version", AppInfo.version()));
        }
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String expected = token.get();
        if (expected == null || expected.length() < 32 || auth == null || !auth.startsWith("Bearer ")
                || !ApiToken.matches(expected, auth.substring(7).trim())) {
            return error(401, "Missing or wrong API token");
        }
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        if (seg.isEmpty()) {
            throw new NotFound("Not found");
        }
        return switch (seg.get(0)) {
            case "destinations" -> destinations(ex, method, seg, q);
            case "messages" -> messages(method, seg, q);
            case "schedules" -> schedules(method, seg);
            case "reload" -> {
                requireMethod(method, "POST");
                engine.reload();
                yield ok(Map.of("reloaded", true));
            }
            default -> throw new NotFound("Not found");
        };
    }

    private Response destinations(HttpExchange ex, String method, List<String> seg, Map<String, String> q)
            throws IOException {
        if (seg.size() == 1) {
            requireMethod(method, "GET");
            List<Object> list = new ArrayList<>();
            for (DestinationConfig d : engine.destinations()) {
                list.add(view(d));
            }
            return ok(Map.of("destinations", list));
        }
        DestinationConfig d = destination(seg.get(1));
        if (seg.size() == 2) {
            requireMethod(method, "GET");
            return ok(view(d));
        }
        String action = seg.get(2);
        switch (action) {
            case "stats" -> {
                requireMethod(method, "GET");
                int minutes = intParam(q, "minutes", 60, 1, 60 * 24 * 31);
                return ok(JsonViews.stats(engine.stats(d.id(), Duration.ofMinutes(minutes))));
            }
            case "pause" -> {
                requireMethod(method, "POST");
                engine.pause(d.id());
                return ok(view(engine.store().destination(d.id()).orElse(d)));
            }
            case "resume" -> {
                requireMethod(method, "POST");
                engine.resume(d.id());
                return ok(view(engine.store().destination(d.id()).orElse(d)));
            }
            case "messages" -> {
                requireMethod(method, "POST");
                return enqueue(ex, d, q);
            }
            default -> throw new NotFound("Not found");
        }
    }

    private Response enqueue(HttpExchange ex, DestinationConfig d, Map<String, String> q) throws IOException {
        String text = body(ex);
        if (text.isBlank()) {
            throw new BadRequest("The request body must contain the HL7 message");
        }
        SendOptions options = new SendOptions(!"keep".equals(q.get("controlId")), !"keep".equals(q.get("timestamp")),
                d.ackMode() == AckMode.NO_ACK ? AckMode.NO_ACK : AckMode.EXPECT_ACK);
        String source = q.getOrDefault("source", "api");
        if (Boolean.parseBoolean(q.get("split"))) {
            SplitResult split = MessageSplitter.split(text);
            List<BulkItem> items = new ArrayList<>();
            for (int i = 0; i < split.messages().size(); i++) {
                items.add(new BulkItem(split.messages().get(i), source + "#" + (i + 1)));
            }
            BulkResult r = engine.enqueueAll(d.id(), items, options, null, BulkProgress.NONE);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchId", r.batchId());
            m.put("total", r.total());
            m.put("accepted", r.accepted());
            List<Object> rejected = new ArrayList<>();
            for (BulkResult.Rejection rej : r.rejected()) {
                rejected.add(Map.of("index", rej.index(), "source", rej.source(), "reason", rej.reason()));
            }
            m.put("rejected", rejected);
            m.put("warnings", split.warnings());
            return new Response(r.accepted() == 0 ? 422 : 202, m);
        }
        EnqueueResult r = engine.enqueue(d.id(), text, options, source);
        if (!r.accepted()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "Validation failed");
            m.put("issues", JsonViews.issues(r.validation()));
            return new Response(422, m);
        }
        Map<String, Object> m = new LinkedHashMap<>(JsonViews.message(r.message().orElseThrow(), false));
        m.put("warnings", r.warnings());
        return new Response(202, m);
    }

    private Response messages(String method, List<String> seg, Map<String, String> q) {
        if (seg.size() == 1) {
            requireMethod(method, "GET");
            MessageQuery query = MessageQuery.all().withLimit(intParam(q, "limit", 100, 1, 10_000));
            if (q.containsKey("destination")) {
                query = query.withDestination(destination(q.get("destination")).id());
            }
            if (q.containsKey("status")) {
                Set<MessageStatus> statuses = EnumSet.noneOf(MessageStatus.class);
                for (String s : q.get("status").split(",")) {
                    try {
                        statuses.add(MessageStatus.valueOf(s.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        throw new BadRequest("Unknown status: " + s);
                    }
                }
                query = query.withStatuses(statuses);
            }
            query = query.withBatch(q.get("batch")).withText(q.get("type"), q.get("controlId"), "");
            boolean payload = Boolean.parseBoolean(q.get("payload"));
            List<Object> out = new ArrayList<>();
            for (QueuedMessage m : engine.store().search(query)) {
                out.add(JsonViews.message(m, payload));
            }
            return ok(Map.of("messages", out));
        }
        long id = parseId(seg.get(1));
        QueuedMessage m = engine.store().message(id).orElseThrow(() -> new NotFound("No message " + id));
        if (seg.size() == 2) {
            if (method.equals("DELETE")) {
                return engine.delete(id) ? ok(Map.of("deleted", true))
                        : error(409, "A message that is being sent cannot be deleted");
            }
            requireMethod(method, "GET");
            boolean payload = Boolean.parseBoolean(q.get("payload"));
            Map<String, Object> view = new LinkedHashMap<>(JsonViews.message(m, payload));
            view.put("history", engine.store().attempts(id).stream().map(JsonViews::attempt).toList());
            return ok(view);
        }
        requireMethod(method, "POST");
        return switch (seg.get(2)) {
            case "requeue" -> engine.requeue(id) ? ok(JsonViews.message(engine.store().message(id).orElse(m), false))
                    : error(409, "Only dead-lettered or delivered messages can be requeued (status is "
                    + m.status() + ")");
            case "retry" -> engine.retryNow(id) ? ok(JsonViews.message(engine.store().message(id).orElse(m), false))
                    : error(409, "Only messages waiting to retry can be retried now (status is " + m.status() + ")");
            case "replay" -> {
                Long target = q.containsKey("destination") ? destination(q.get("destination")).id() : null;
                DeliveryEngine.Replayed r = engine.replay(List.of(id), target,
                        Boolean.parseBoolean(q.get("newControlIds"))).get(0);
                yield r.copy().map(c -> new Response(202, JsonViews.message(c, false)))
                        .orElseGet(() -> error(422, r.problem()));
            }
            default -> throw new NotFound("Not found");
        };
    }

    private Response schedules(String method, List<String> seg) {
        Instant now = Instant.now();
        if (seg.size() == 1) {
            requireMethod(method, "GET");
            List<Object> out = new ArrayList<>();
            for (Schedule s : engine.store().schedules()) {
                String name = engine.store().destination(s.destinationId()).map(DestinationConfig::name).orElse(null);
                out.add(JsonViews.schedule(s, name, s.nextRunAfter(now).orElse(null)));
            }
            return ok(Map.of("schedules", out));
        }
        String key = seg.get(1);
        Schedule s = engine.store().schedules().stream()
                .filter(x -> String.valueOf(x.id()).equals(key) || x.name().equalsIgnoreCase(key)).findFirst()
                .orElseThrow(() -> new NotFound("No schedule " + key));
        if (seg.size() == 3 && seg.get(2).equals("run")) {
            requireMethod(method, "POST");
            Scheduler.Result r = Scheduler.run(engine, templates, s, now);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("schedule", s.name());
            m.put("queued", r.queued());
            m.put("rejected", r.rejected());
            m.put("batchId", r.batchId().orElse(null));
            m.put("result", r.summary());
            return new Response(r.queued() > 0 ? 202 : 422, m);
        }
        throw new NotFound("Not found");
    }

    // ---------------------------------------------------------------------------------------------

    private Map<String, Object> view(DestinationConfig d) {
        return JsonViews.destination(d, engine.state(d.id()), engine.store().counts(d.id()));
    }

    private DestinationConfig destination(String idOrName) {
        Optional<DestinationConfig> d = idOrName.matches("\\d{1,18}")
                ? engine.store().destination(Long.parseLong(idOrName))
                : engine.destinations().stream().filter(x -> x.name().equalsIgnoreCase(idOrName)).findFirst();
        return d.orElseThrow(() -> new NotFound("No destination " + idOrName));
    }

    private static long parseId(String s) {
        if (!s.matches("\\d{1,18}")) {
            throw new BadRequest("Not a message ID: " + s);
        }
        return Long.parseLong(s);
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new BadRequest("Use " + expected);
        }
    }

    private static int intParam(Map<String, String> q, String name, int def, int min, int max) {
        String v = q.get(name);
        if (v == null) {
            return def;
        }
        try {
            int n = Integer.parseInt(v);
            if (n < min || n > max) {
                throw new BadRequest(name + " must be between " + min + " and " + max);
            }
            return n;
        } catch (NumberFormatException e) {
            throw new BadRequest(name + " must be a number");
        }
    }

    private static String body(HttpExchange ex) throws IOException {
        Charset charset = StandardCharsets.UTF_8;
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type != null) {
            for (String part : type.split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    try {
                        charset = Charset.forName(p.substring(8).replace("\"", ""));
                    } catch (IllegalArgumentException e) {
                        throw new BadRequest("Unsupported charset: " + p.substring(8));
                    }
                }
            }
        }
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
            if (bytes.length > MAX_BODY_BYTES) {
                throw new BadRequest("Request body is larger than " + MAX_BODY_BYTES / 1_048_576 + " MB");
            }
            return new String(bytes, charset);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) {
            return q;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            q.put(k, v);
        }
        return q;
    }

    /** Accepts {@code localhost}, {@code 127.0.0.1} and {@code [::1]}, with or without a port. */
    static boolean localHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 && h.substring(1, end).equals("::1");
        }
        int colon = h.indexOf(':');
        String name = colon < 0 ? h : h.substring(0, colon);
        return name.equals("localhost") || name.equals("127.0.0.1");
    }

    private static Response ok(Object body) {
        return new Response(200, body);
    }

    private static Response error(int status, String message) {
        return new Response(status, Map.of("error", message == null ? "Error" : message));
    }

    private static final class NotFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotFound(String message) {
            super(message);
        }
    }

    private static final class BadRequest extends RuntimeException {
        private static final long serialVersionUID = 1L;

        BadRequest(String message) {
            super(message);
        }
    }
}
