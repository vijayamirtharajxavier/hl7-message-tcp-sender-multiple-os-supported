package io.hl7sender.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Per-OS locations for configuration, data and logs.
 *
 * <table>
 *   <caption>Default locations</caption>
 *   <tr><th>OS</th><th>Config</th><th>Data</th><th>Logs</th></tr>
 *   <tr><td>Windows</td><td>%APPDATA%\HL7Sender</td><td>%LOCALAPPDATA%\HL7Sender\data</td>
 *       <td>%LOCALAPPDATA%\HL7Sender\logs</td></tr>
 *   <tr><td>macOS</td><td>~/Library/Application Support/HL7Sender</td>
 *       <td>~/Library/Application Support/HL7Sender/data</td><td>~/Library/Logs/HL7Sender</td></tr>
 *   <tr><td>Linux</td><td>$XDG_CONFIG_HOME/hl7sender</td><td>$XDG_DATA_HOME/hl7sender</td>
 *       <td>$XDG_STATE_HOME/hl7sender/logs</td></tr>
 * </table>
 *
 * <p>Setting the system property {@value #HOME_PROPERTY} (or the environment variable {@value #HOME_ENV})
 * puts everything under a single directory instead. This is useful for portable installs and tests.
 */
public record AppPaths(Path configDir, Path dataDir, Path logDir) {

    public static final String HOME_PROPERTY = "hl7sender.home";
    public static final String HOME_ENV = "HL7SENDER_HOME";
    /** System property read by logback.xml to find the log directory. */
    public static final String LOG_DIR_PROPERTY = "hl7sender.logDir";

    public static AppPaths detect() {
        return detect(System.getProperty("os.name", ""), System.getProperty("user.home", "."),
                System.getenv(), System.getProperty(HOME_PROPERTY));
    }

    static AppPaths detect(String osName, String userHome, Map<String, String> env, String homeOverride) {
        String override = homeOverride != null && !homeOverride.isBlank() ? homeOverride : env.get(HOME_ENV);
        if (override != null && !override.isBlank()) {
            Path home = Path.of(override);
            return new AppPaths(home.resolve("config"), home.resolve("data"), home.resolve("logs"));
        }
        Path home = Path.of(userHome);
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            Path roaming = pathOr(env.get("APPDATA"), home.resolve("AppData").resolve("Roaming"));
            Path local = pathOr(env.get("LOCALAPPDATA"), home.resolve("AppData").resolve("Local"));
            return new AppPaths(roaming.resolve("HL7Sender"), local.resolve("HL7Sender").resolve("data"),
                    local.resolve("HL7Sender").resolve("logs"));
        }
        if (os.contains("mac") || os.contains("darwin")) {
            Path support = home.resolve("Library").resolve("Application Support").resolve("HL7Sender");
            return new AppPaths(support, support.resolve("data"),
                    home.resolve("Library").resolve("Logs").resolve("HL7Sender"));
        }
        Path config = pathOr(env.get("XDG_CONFIG_HOME"), home.resolve(".config")).resolve("hl7sender");
        Path data = pathOr(env.get("XDG_DATA_HOME"), home.resolve(".local").resolve("share")).resolve("hl7sender");
        Path state = pathOr(env.get("XDG_STATE_HOME"), home.resolve(".local").resolve("state")).resolve("hl7sender");
        return new AppPaths(config, data, state.resolve("logs"));
    }

    /** Creates the directories if they do not exist yet. */
    public AppPaths ensureExist() throws IOException {
        Files.createDirectories(configDir);
        Files.createDirectories(dataDir);
        Files.createDirectories(logDir);
        return this;
    }

    /** Where transport plugins ({@code .jar} files) are loaded from: {@code plugins} in the settings folder. */
    public Path pluginsDir() {
        return configDir().resolve("plugins");
    }

    public Path settingsFile() {
        return configDir.resolve("settings.json");
    }

    /**
     * Publishes the log directory as a system property for logback.xml. Call this before the first
     * logger is created.
     */
    public void publishLogDir() {
        if (System.getProperty(LOG_DIR_PROPERTY) == null) {
            System.setProperty(LOG_DIR_PROPERTY, logDir.toAbsolutePath().toString());
        }
    }

    private static Path pathOr(String value, Path fallback) {
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }
}
