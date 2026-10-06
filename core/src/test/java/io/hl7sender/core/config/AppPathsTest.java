package io.hl7sender.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppPathsTest {

    @Test
    void windowsUsesAppData() {
        AppPaths p = AppPaths.detect("Windows 11", "C:/Users/u",
                Map.of("APPDATA", "C:/Users/u/AppData/Roaming", "LOCALAPPDATA", "C:/Users/u/AppData/Local"), null);
        assertThat(p.configDir()).isEqualTo(Path.of("C:/Users/u/AppData/Roaming/HL7Sender"));
        assertThat(p.logDir()).isEqualTo(Path.of("C:/Users/u/AppData/Local/HL7Sender/logs"));
    }

    @Test
    void macUsesLibrary() {
        AppPaths p = AppPaths.detect("Mac OS X", "/Users/u", Map.of(), null);
        assertThat(p.configDir()).isEqualTo(Path.of("/Users/u/Library/Application Support/HL7Sender"));
        assertThat(p.logDir()).isEqualTo(Path.of("/Users/u/Library/Logs/HL7Sender"));
    }

    @Test
    void linuxFollowsXdg() {
        assertThat(AppPaths.detect("Linux", "/home/u", Map.of(), null).configDir())
                .isEqualTo(Path.of("/home/u/.config/hl7sender"));
        AppPaths custom = AppPaths.detect("Linux", "/home/u",
                Map.of("XDG_CONFIG_HOME", "/cfg", "XDG_DATA_HOME", "/data", "XDG_STATE_HOME", "/state"), null);
        assertThat(custom.configDir()).isEqualTo(Path.of("/cfg/hl7sender"));
        assertThat(custom.dataDir()).isEqualTo(Path.of("/data/hl7sender"));
        assertThat(custom.logDir()).isEqualTo(Path.of("/state/hl7sender/logs"));
    }

    @Test
    void overrideWins() {
        AppPaths p = AppPaths.detect("Linux", "/home/u", Map.of(AppPaths.HOME_ENV, "/env"), "/portable");
        assertThat(p.configDir()).isEqualTo(Path.of("/portable/config"));
        assertThat(AppPaths.detect("Linux", "/home/u", Map.of(AppPaths.HOME_ENV, "/env"), null).logDir())
                .isEqualTo(Path.of("/env/logs"));
    }
}
