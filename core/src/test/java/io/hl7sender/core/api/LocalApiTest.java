package io.hl7sender.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import io.hl7sender.core.batch.MessageSplitter;
import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.config.AppSettings;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.queue.QueueException;
import io.hl7sender.core.runtime.QueueOpener;
import io.hl7sender.core.runtime.QueueRuntime;
import io.hl7sender.core.secrets.InMemorySecretStore;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The local REST API, the shared queue opener and cross-process reload. */
@Timeout(60)
class LocalApiTest {

    static final String MESSAGE = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||ADT^A01^ADT_A01|ORIG|P|2.5.1\r"
            + "EVN|A01|20260101\rPID|1||MRN1^^^H^MR||DOE^JANE\rPV1|1|I";

    @TempDir
    Path home;

    private AppPaths paths;
    private final InMemorySecretStore secrets = new InMemorySecretStore();
    private AppSettings settings;
    private QueueRuntime runtime;
    private TestListener listener;
    private final List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
    private ApiClient client;

    @BeforeEach
    void setUp() throws IOException {
        paths = new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs"));
        settings = AppSettings.defaults().withApi(new AppSettings.Api(true, 0));
        runtime = QueueRuntime.start(paths, () -> settings, secrets, new Hl7Sender(), Clock.systemUTC())
                .orElseThrow();
        listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add);
        listener.start();
        client = ApiClient.discover(paths).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        runtime.close();
        listener.close();
    }

    private DestinationConfig destination(String name) {
        return runtime.engine().saveDestination(DestinationConfig.of(name, "127.0.0.1", listener.port())
                .withTimeouts(1_000, 1_000));
    }

    private static JsonNode json(ApiClient.Response r) throws IOException {
        return JsonViews.COMPACT.readTree(r.body());
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
    }

    @Test
    void schedulesCanBeListedAndRunAndMessagesReplayed() throws Exception {
        DestinationConfig d = destination("Lab");
        io.hl7sender.core.schedule.Schedule s = runtime.store().saveSchedule(
                io.hl7sender.core.schedule.Schedule.of("Hourly A01", "@hourly", d.id(), MESSAGE).withCount(2));

        JsonNode list = json(client.get("schedules"));
        assertThat(list.path("schedules").get(0).path("name").asText()).isEqualTo("Hourly A01");
        assertThat(list.path("schedules").get(0).path("destination").asText()).isEqualTo("Lab");
        assertThat(list.path("schedules").get(0).path("nextRunAt").asText()).endsWith(":00:00Z");

        ApiClient.Response run = client.post("schedules/hourly%20a01/run", "");
        assertThat(run.status()).isEqualTo(202);
        assertThat(json(run).path("queued").asInt()).isEqualTo(2);
        await("scheduled messages delivered", () -> received.size() == 2);
        assertThat(client.post("schedules/" + s.id() + "/nothing", "").status()).isEqualTo(404);
        assertThat(client.post("schedules/Unknown/run", "").status()).isEqualTo(404);

        long first = json(client.get("messages?destination=Lab")).path("messages").get(0).path("id").asLong();
        ApiClient.Response replay = client.post("messages/" + first + "/replay?newControlIds=true", "");
        assertThat(replay.status()).isEqualTo(202);
        assertThat(json(replay).path("source").asText()).isEqualTo("Replay of message " + first);
        await("replayed message delivered", () -> received.size() == 3);
        assertThat(client.post("messages/" + first + "/replay?destination=Nowhere", "").status()).isEqualTo(404);
    }

    @Test
    void endpointFileAndTokenAreWrittenAndTokenIsRequired() throws Exception {
        int port = runtime.api().orElseThrow().port();
        assertThat(ApiEndpoint.read(paths)).hasValueSatisfying(e -> {
            assertThat(e.port()).isEqualTo(port);
            assertThat(e.pid()).isEqualTo(ProcessHandle.current().pid());
        });
        Path tokenFile = ApiToken.file(paths.configDir());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile))).isEqualTo("rw-------");
        }
        assertThat(Files.readString(ApiEndpoint.file(paths))).doesNotContain(ApiToken.load(paths.configDir())
                .orElseThrow());

        ApiClient anonymous = new ApiClient(port, "0".repeat(64));
        assertThat(anonymous.get("health").status()).isEqualTo(200);
        assertThat(json(anonymous.get("health")).path("status").asText()).isEqualTo("ok");
        ApiClient.Response denied = anonymous.get("destinations");
        assertThat(denied.status()).isEqualTo(401);
        assertThat(json(denied).path("error").asText()).isEqualTo("Missing or wrong API token");
        assertThat(client.get("destinations").status()).isEqualTo(200);
        assertThat(client.get("nothing-here").status()).isEqualTo(404);

        // A regenerated token applies at once: the old one is refused, the new one accepted.
        String fresh = ApiToken.regenerate(paths.configDir());
        assertThat(client.get("destinations").status()).isEqualTo(401);
        client = new ApiClient(port, fresh);
        assertThat(client.get("destinations").status()).isEqualTo(200);

        // A request addressed to another host name (DNS rebinding) is refused even with the token.
        String token = ApiToken.load(paths.configDir()).orElseThrow();
        assertThat(rawStatus(port, "GET /api/v1/destinations HTTP/1.1\r\nHost: evil.example:" + port
                + "\r\nAuthorization: Bearer " + token + "\r\nConnection: close\r\n\r\n")).isEqualTo(403);
        assertThat(LocalApiServer.localHost("localhost:8742")).isTrue();
        assertThat(LocalApiServer.localHost("[::1]:8742")).isTrue();
        assertThat(LocalApiServer.localHost("127.0.0.1")).isTrue();
        assertThat(LocalApiServer.localHost("127.0.0.1.evil.example")).isFalse();

        runtime.close();
        assertThat(ApiEndpoint.read(paths)).isEmpty();
        runtime = QueueRuntime.start(paths, () -> settings, secrets, new Hl7Sender(), Clock.systemUTC())
                .orElseThrow();
    }

    @Test
    void enqueueTrackAndInspectMessagesOverTheApi() throws Exception {
        DestinationConfig d = destination("Mirth");
        ApiClient.Response posted = client.post("destinations/mirth/messages", MESSAGE);
        assertThat(posted.status()).isEqualTo(202);
        long id = json(posted).path("id").asLong();
        assertThat(json(posted).path("status").asText()).isEqualTo("QUEUED");

        await("acknowledged", () -> {
            try {
                return json(client.get("messages/" + id)).path("status").asText().equals("ACKNOWLEDGED");
            } catch (IOException e) {
                return false;
            }
        });
        JsonNode m = json(client.get("messages/" + id));
        assertThat(m.path("history").get(0).path("ackCode").asText()).isEqualTo("AA");
        assertThat(m.has("payload")).isFalse();
        assertThat(json(client.get("messages/" + id + "?payload=true")).path("payload").asText()).contains("DOE^JANE");

        JsonNode list = json(client.get("messages?destination=" + d.id() + "&status=acknowledged"));
        assertThat(list.path("messages")).hasSize(1);
        JsonNode dest = json(client.get("destinations/" + d.id()));
        assertThat(dest.path("counts").path("ACKNOWLEDGED").asInt()).isEqualTo(1);
        assertThat(dest.path("state").asText()).isIn("IDLE", "SENDING");
        JsonNode stats = json(client.get("destinations/Mirth/stats?minutes=5"));
        assertThat(stats.path("accepted").asInt()).isEqualTo(1);
        assertThat(stats.path("acceptedPerMinute")).hasSize(5);

        ApiClient.Response invalid = client.post("destinations/Mirth/messages", "PID|1||X");
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(json(invalid).path("issues").get(0).path("severity").asText()).isEqualTo("ERROR");
        assertThat(client.post("destinations/nope/messages", MESSAGE).status()).isEqualTo(404);
        assertThat(client.post("messages/" + id + "/retry", "").status()).isEqualTo(409);
        assertThat(client.post("messages/" + id + "/requeue", "").status()).isEqualTo(200);
        await("received twice", () -> received.size() == 2);

        String noType = "MSH|^~\\&|APP|FAC|RAPP|RFAC|20260101||||P|2.5.1";
        String batch = MessageSplitter.toBatchFile(List.of(MESSAGE, MESSAGE, noType));
        JsonNode bulk = json(client.post("destinations/Mirth/messages?split=true", batch));
        assertThat(bulk.path("accepted").asInt()).isEqualTo(2);
        assertThat(bulk.path("rejected")).hasSize(1);
        await("batch delivered", () -> received.size() == 4);

        assertThat(json(client.post("destinations/Mirth/pause", "")).path("paused").asBoolean()).isTrue();
        assertThat(json(client.post("destinations/Mirth/resume", "")).path("paused").asBoolean()).isFalse();
    }

    @Test
    void anotherProcessCanQueueWhileThisOneDelivers() throws Exception {
        assertThat(QueueOpener.lockedElsewhere(paths)).isTrue();
        assertThat(QueueOpener.own(paths, false, secrets, Clock.systemUTC())).isEmpty();
        // A "CLI" opens the same database without the lock, adds a destination and a message...
        DestinationConfig d;
        try (QueueOpener.Opened shared = QueueOpener.share(paths, secrets, Clock.systemUTC())) {
            assertThat(shared.owner()).isFalse();
            io.hl7sender.core.queue.DeliveryEngine offline = new io.hl7sender.core.queue.DeliveryEngine(
                    shared.store(), new Hl7Sender(), secrets);
            d = offline.saveDestination(DestinationConfig.of("From CLI", "127.0.0.1", listener.port()));
            assertThat(offline.enqueue(d.id(), MESSAGE, SendOptions.DEFAULTS).accepted()).isTrue();
        }
        // ...and asks the delivering process to pick it up now rather than at its next periodic check.
        assertThat(client.reload()).isTrue();
        await("delivered by the owner", () -> received.size() == 1);
        assertThat(runtime.engine().destinations()).extracting(DestinationConfig::name).contains("From CLI");
    }

    @Test
    void sharedOpenNeedsTheKeyOfAnEncryptedQueue() throws Exception {
        runtime.close();
        settings = settings.withSecurity(new AppSettings.Security(true));
        runtime = QueueRuntime.start(paths, () -> settings, secrets, new Hl7Sender(), Clock.systemUTC())
                .orElseThrow();
        assertThat(runtime.encrypted()).isTrue();
        try (QueueOpener.Opened shared = QueueOpener.share(paths, secrets, Clock.systemUTC())) {
            assertThat(shared.encrypted()).isTrue();
        }
        assertThatThrownBy(() -> QueueOpener.share(paths, new InMemorySecretStore(), Clock.systemUTC()))
                .isInstanceOf(QueueException.class).hasMessageContaining("key is not in the");
    }

    private static int rawStatus(int port, String request) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            OutputStream out = s.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String status = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))
                    .readLine();
            return Integer.parseInt(status.split(" ")[1]);
        }
    }
}
