package io.hl7sender.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.hl7sender.core.listener.ResponseMode;
import io.hl7sender.core.send.AckMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void missingFileGivesDefaults() {
        assertThat(new SettingsStore(dir.resolve("settings.json")).load()).isEqualTo(AppSettings.defaults());
    }

    @Test
    void roundTrips() throws IOException {
        SettingsStore store = new SettingsStore(dir.resolve("nested/settings.json"));
        AppSettings s = AppSettings.defaults()
                .withDestination(new AppSettings.Destination("mirth.local", 6661, 1000, 9000, "ISO-8859-1",
                        AckMode.NO_ACK))
                .withSender(new AppSettings.Sender(false, true))
                .withListener(new AppSettings.Listener("127.0.0.1", 7777, ResponseMode.REJECT, 250, true));
        store.save(s);
        assertThat(store.load()).isEqualTo(s);
        assertThat(Files.list(store.file().getParent())).containsExactly(store.file());
    }

    @Test
    void partialAndInvalidValuesFallBackToDefaults() throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{\"destination\":{\"host\":\"ehr\",\"port\":99999,\"charset\":\"NOPE\"},\"extra\":1}");
        AppSettings s = new SettingsStore(file).load();
        assertThat(s.destination().host()).isEqualTo("ehr");
        assertThat(s.destination().port()).isEqualTo(2575);
        assertThat(s.destination().charset()).isEqualTo("UTF-8");
        assertThat(s.destination().ackMode()).isEqualTo(AckMode.EXPECT_ACK);
        assertThat(s.sender()).isEqualTo(AppSettings.Sender.defaults());
    }

    @Test
    void corruptFileIsBackedUp() throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{not json");
        assertThat(new SettingsStore(file).load()).isEqualTo(AppSettings.defaults());
        assertThat(dir.resolve("settings.json.corrupt")).exists();
        assertThat(file).doesNotExist();
    }
}
