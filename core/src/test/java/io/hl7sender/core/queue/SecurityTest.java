package io.hl7sender.core.queue;

import static io.hl7sender.core.queue.DeliveryEngineTest.MESSAGE;
import static io.hl7sender.core.queue.DeliveryEngineTest.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hl7sender.core.hl7.validation.ValidationLevel;
import io.hl7sender.core.listener.ListenerSettings;
import io.hl7sender.core.listener.ReceivedMessage;
import io.hl7sender.core.listener.TestListener;
import io.hl7sender.core.secrets.InMemorySecretStore;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.tls.TestCertificates;
import io.hl7sender.core.tls.TlsContexts;
import io.hl7sender.core.tls.TlsSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Encryption at rest, TLS destinations with keychain passwords, fan-out and profiles. */
@Timeout(60)
class SecurityTest {

    @TempDir
    static Path certDir;
    static TestCertificates certs;

    @TempDir
    Path dir;

    @BeforeAll
    static void generateCertificates() throws Exception {
        certs = new TestCertificates(certDir);
    }

    // ---------------------------------------------------------------------------------------------
    // Encryption at rest

    @Test
    void encryptedDatabaseHidesContentAndNeedsTheKey() {
        Path db = dir.resolve("queue.db");
        String key = QueueStore.newKey();
        try (QueueStore store = QueueStore.open(db, java.time.Clock.systemUTC(), key)) {
            DestinationConfig d = store.saveDestination(DestinationConfig.of("D", "h", 1));
            store.enqueue(d.id(), "C1", "ADT^A01", "MSH|^~\\&|PATIENT-SECRET-NAME");
        }
        assertThat(readAll(db)).doesNotContain("PATIENT-SECRET-NAME");
        assertThat(QueueStore.isEncrypted(db)).isTrue();
        assertThatThrownBy(() -> QueueStore.open(db)).isInstanceOf(QueueException.class)
                .hasMessageContaining("encrypted and no key");
        assertThatThrownBy(() -> QueueStore.open(db, java.time.Clock.systemUTC(), QueueStore.newKey()))
                .isInstanceOf(QueueException.class).hasMessageContaining("key is wrong");
        try (QueueStore store = QueueStore.open(db, java.time.Clock.systemUTC(), key)) {
            assertThat(store.search(MessageQuery.all())).singleElement()
                    .satisfies(m -> assertThat(m.payload()).contains("PATIENT-SECRET-NAME"));
        }
    }

    @Test
    void existingDatabaseCanBeEncryptedAndDecryptedInPlace() {
        Path db = dir.resolve("queue.db");
        try (QueueStore store = QueueStore.open(db)) {
            DestinationConfig d = store.saveDestination(DestinationConfig.of("D", "h", 1));
            store.enqueue(d.id(), "C1", "ADT^A01", "MSH|^~\\&|PLAIN-TEXT-NAME");
        }
        assertThat(QueueStore.isEncrypted(db)).isFalse();
        String key = QueueStore.newKey();
        QueueStore.encrypt(db, key);
        assertThat(QueueStore.isEncrypted(db)).isTrue();
        assertThat(readAll(db)).doesNotContain("PLAIN-TEXT-NAME");
        try (QueueStore store = QueueStore.open(db, java.time.Clock.systemUTC(), key)) {
            assertThat(store.search(MessageQuery.all())).hasSize(1);
        }
        QueueStore.decrypt(db, key);
        assertThat(QueueStore.isEncrypted(db)).isFalse();
        try (QueueStore store = QueueStore.open(db)) {
            assertThat(store.destinations()).hasSize(1);
        }
        assertThatThrownBy(() -> QueueStore.encrypt(db, "x'; DROP TABLE message; --"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String readAll(Path db) {
        try {
            StringBuilder sb = new StringBuilder(new String(Files.readAllBytes(db), StandardCharsets.ISO_8859_1));
            for (String suffix : List.of("-wal", "-shm")) {
                Path p = db.resolveSibling(db.getFileName() + suffix);
                if (Files.exists(p)) {
                    sb.append(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));
                }
            }
            return sb.toString();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // TLS destinations

    @Test
    void mutualTlsDestinationUsesPasswordsFromTheSecretStore() throws Exception {
        List<ReceivedMessage> received = new CopyOnWriteArrayList<>();
        InMemorySecretStore secrets = new InMemorySecretStore();
        try (TestListener listener = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, received::add)
                .useTls(TlsContexts.server(certs.serverKeyStore, TestCertificates.PASSWORD.toCharArray(),
                        certs.clientCert.toString(), null), true);
             QueueStore store = QueueStore.open(dir.resolve("queue.db"))) {
            listener.start();
            DeliveryEngine engine = new DeliveryEngine(store, new Hl7Sender(), secrets);
            engine.start();
            try {
                DestinationConfig d = engine.saveDestination(DestinationConfig.of("Secure", "127.0.0.1",
                        listener.port()).withTimeouts(3000, 3000)
                        .withRetry(new RetryPolicy(0, 50, 100, 0))
                        .withTls(new TlsSettings(true, certs.serverCert.toString(),
                                certs.clientKeyStore.toString(), true, TlsSettings.DEFAULT_PROTOCOLS)));
                assertThat(d.secretRef()).hasSize(16);
                assertThat(d.displayAddress()).startsWith("tls://");

                // No key-store password yet: TLS is misconfigured, nothing is attempted.
                QueuedMessage m = engine.enqueue(d.id(), MESSAGE, SendOptions.DEFAULTS).message().orElseThrow();
                await("TLS error state", () -> engine.state(d.id()).status() == DestinationState.Status.ERROR);
                assertThat(engine.state(d.id()).detail()).startsWith("TLS configuration");
                assertThat(store.message(m.id()).orElseThrow().attempts()).isZero();

                engine.saveTlsPasswords(d.id(), null, TestCertificates.PASSWORD);
                assertThat(engine.hasKeyStorePassword(d)).isTrue();
                assertThat(engine.hasTrustStorePassword(d)).isFalse();
                await("delivered over mTLS",
                        () -> store.message(m.id()).orElseThrow().status() == MessageStatus.ACKNOWLEDGED);
                assertThat(received).hasSize(1);
                assertThat(engine.certificates(d)).extracting(c -> c.source())
                        .contains("Trusted", "Client", "Server");
                assertThat(engine.certificateWarnings(d)).isEmpty();

                engine.deleteDestination(d.id());
                assertThat(secrets.get(DeliveryEngine.keyKey(d))).isEmpty();
            } finally {
                engine.close();
            }
        }
    }

    @Test
    void expiringCertificatesProduceWarnings() {
        try (QueueStore store = QueueStore.open(dir.resolve("queue.db"))) {
            DeliveryEngine engine = new DeliveryEngine(store, new Hl7Sender());
            DestinationConfig d = engine.saveDestination(DestinationConfig.of("Soon", "127.0.0.1", 1)
                    .withTls(new TlsSettings(true, certs.expiringCert.toString(), "", true, "TLSv1.3")));
            assertThat(engine.certificateWarnings(d)).singleElement().asString()
                    .contains("CN=soon-expiring").contains("expires in");
            DestinationConfig broken = engine.saveDestination(d.withTls(new TlsSettings(true,
                    dir.resolve("missing.pem").toString(), "", true, "TLSv1.3")));
            assertThat(engine.certificateWarnings(broken)).singleElement().asString()
                    .startsWith("Cannot read certificates");
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Fan-out

    @Test
    void fanOutQueuesTheSameMessageToEachDestinationIndependently() throws Exception {
        List<ReceivedMessage> a = new CopyOnWriteArrayList<>();
        List<ReceivedMessage> b = new CopyOnWriteArrayList<>();
        try (TestListener la = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, a::add);
             TestListener lb = new TestListener("127.0.0.1", 0, ListenerSettings.DEFAULTS, b::add);
             QueueStore store = QueueStore.open(dir.resolve("queue.db"))) {
            la.start();
            lb.start();
            DeliveryEngine engine = new DeliveryEngine(store, new Hl7Sender());
            engine.start();
            try {
                DestinationConfig da = engine.saveDestination(DestinationConfig.of("A", "127.0.0.1", la.port()));
                DestinationConfig db = engine.saveDestination(DestinationConfig.of("B", "127.0.0.1", lb.port()));
                DestinationConfig strict = engine.saveDestination(DestinationConfig.of("Strict", "127.0.0.1", 1)
                        .withValidation(ValidationLevel.STRICT, ""));
                String badDate = MESSAGE.replace("EVN|A01|20260101", "EVN|A01|NOTADATE");

                FanOutResult r = engine.enqueueFanOut(List.of(da.id(), db.id(), strict.id()), badDate,
                        SendOptions.DEFAULTS, "editor");
                assertThat(r.accepted()).isEqualTo(2);
                assertThat(r.results().get(strict.id()).accepted()).isFalse();
                await("both delivered", () -> a.size() == 1 && b.size() == 1);
                assertThat(a.get(0).controlId()).isEqualTo(b.get(0).controlId()).isNotEqualTo("ORIG");
                assertThat(store.search(MessageQuery.all().withBatch(r.batchId()))).hasSize(2);
            } finally {
                engine.close();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Profiles and presets

    @Test
    void profilesRoundTripWithoutSecrets() throws Exception {
        InMemorySecretStore secrets = new InMemorySecretStore();
        try (QueueStore store = QueueStore.open(dir.resolve("queue.db"))) {
            DeliveryEngine engine = new DeliveryEngine(store, new Hl7Sender(), secrets);
            DestinationConfig d = engine.saveDestination(DestinationPresets.all().get(2).config());
            engine.saveTlsPasswords(d.id(), "trust-secret", "key-secret");
            Path file = dir.resolve("profiles.json");
            DestinationProfiles.write(engine.destinations(), file);
            String json = Files.readString(file);
            assertThat(json).contains("EHR production").contains("\"validationLevel\" : \"STRICT\"")
                    .doesNotContain("trust-secret").doesNotContain("key-secret").doesNotContain(d.secretRef());

            List<DestinationConfig> imported = DestinationProfiles.read(file);
            assertThat(imported).singleElement().satisfies(x -> {
                assertThat(x.id()).isZero();
                assertThat(x.secretRef()).isEmpty();
                assertThat(x.with(bld -> bld.id = d.id()).withSecretRef(d.secretRef())).isEqualTo(d);
            });
            assertThat(DestinationProfiles.uniqueName("EHR production", Set.of("EHR production")))
                    .isEqualTo("EHR production (2)");
            Files.writeString(file, "{\"format\": 99, \"destinations\": []}");
            assertThatThrownBy(() -> DestinationProfiles.read(file)).hasMessageContaining("newer version");
        }
    }

    @Test
    void presetsAreValidAndDocumented() {
        try (QueueStore store = QueueStore.open(dir.resolve("queue.db"))) {
            for (DestinationPresets.Preset p : DestinationPresets.all()) {
                assertThat(p.config().notes()).as(p.name()).isNotBlank();
                assertThat(store.saveDestination(p.config()).id()).isPositive();
            }
        }
    }
}
