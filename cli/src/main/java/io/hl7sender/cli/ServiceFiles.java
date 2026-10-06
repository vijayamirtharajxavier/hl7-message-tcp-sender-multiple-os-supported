package io.hl7sender.cli;

import io.hl7sender.core.config.AppPaths;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service definitions that start {@code hl7send serve} with the computer or at login: a systemd user unit
 * (Linux), a launchd agent (macOS) and a WinSW service configuration (Windows).
 *
 * <p>They run Java directly with an explicit classpath rather than the {@code hl7send} script, so they do not
 * depend on a shell, and they pass on {@code hl7sender.home} so the service uses the same settings, queue and
 * secrets as the app.
 */
final class ServiceFiles {

    static final String NAME = "hl7send";
    static final String LAUNCHD_LABEL = "io.hl7sender.hl7send";

    enum Platform {
        LINUX, MACOS, WINDOWS;

        static Platform current() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            return os.contains("win") ? WINDOWS : os.contains("mac") || os.contains("darwin") ? MACOS : LINUX;
        }
    }

    /**
     * The command line of the service.
     *
     * @param java      Java executable
     * @param arguments everything after it
     */
    record Command(String java, List<String> arguments) {

        List<String> all() {
            List<String> out = new ArrayList<>();
            out.add(java);
            out.addAll(arguments);
            return out;
        }
    }

    private ServiceFiles() {
    }

    /** The command that starts {@code serve} with this JVM, this classpath and these data folders. */
    static Command current() {
        String exe = Platform.current() == Platform.WINDOWS ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", exe);
        List<String> args = new ArrayList<>();
        args.add("-cp");
        List<String> cp = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                cp.add(Path.of(entry).toAbsolutePath().toString());
            }
        }
        args.add(String.join(File.pathSeparator, cp));
        String home = System.getProperty("hl7sender.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("HL7SENDER_HOME");
        }
        if (home != null && !home.isBlank()) {
            args.add("-Dhl7sender.home=" + Path.of(home).toAbsolutePath());
        }
        String secrets = System.getProperty("hl7sender.secrets");
        if (secrets != null && !secrets.isBlank()) {
            args.add("-Dhl7sender.secrets=" + secrets);
        }
        args.add(Hl7SendCli.class.getName());
        args.add("serve");
        return new Command(java.toString(), args);
    }

    static String fileName(Platform p) {
        return switch (p) {
            case LINUX -> NAME + ".service";
            case MACOS -> LAUNCHD_LABEL + ".plist";
            case WINDOWS -> NAME + ".xml";
        };
    }

    /** Where {@code install} writes the definition by default. */
    static Path defaultLocation(Platform p, AppPaths paths) {
        String userHome = System.getProperty("user.home", ".");
        return switch (p) {
            case LINUX -> {
                String xdg = System.getenv("XDG_CONFIG_HOME");
                Path config = xdg == null || xdg.isBlank() ? Path.of(userHome, ".config") : Path.of(xdg);
                yield config.resolve("systemd").resolve("user").resolve(fileName(p));
            }
            case MACOS -> Path.of(userHome, "Library", "LaunchAgents", fileName(p));
            case WINDOWS -> paths.configDir().resolve("service").resolve(fileName(p));
        };
    }

    static String render(Platform p, Command c, AppPaths paths) {
        return switch (p) {
            case LINUX -> systemd(c);
            case MACOS -> launchd(c, paths.logDir());
            case WINDOWS -> winsw(c, paths.logDir());
        };
    }

    static String systemd(Command c) {
        StringBuilder exec = new StringBuilder();
        for (String a : c.all()) {
            if (!exec.isEmpty()) {
                exec.append(' ');
            }
            exec.append('"').append(a.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%")
                    .replace("$", "$$")).append('"');
        }
        return String.join("\n",
                "# HL7 Sender delivery service (systemd user unit), written by 'hl7send service install'.",
                "[Unit]",
                "Description=HL7 Sender delivery service",
                "Documentation=https://github.com/vijayamirtharajxavier/hl7-message-tcp-sender-multiple-os-supported",
                "After=network-online.target",
                "Wants=network-online.target",
                "",
                "[Service]",
                "Type=simple",
                "ExecStart=" + exec,
                "Restart=on-failure",
                "RestartSec=10",
                "TimeoutStopSec=30",
                "",
                "[Install]",
                "WantedBy=default.target",
                "");
    }

    static String launchd(Command c, Path logDir) {
        StringBuilder args = new StringBuilder();
        for (String a : c.all()) {
            args.append("        <string>").append(xml(a)).append("</string>\n");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
                + "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
                + "<!-- HL7 Sender delivery service (launchd agent), written by 'hl7send service install'. -->\n"
                + "<plist version=\"1.0\">\n"
                + "<dict>\n"
                + "    <key>Label</key>\n"
                + "    <string>" + LAUNCHD_LABEL + "</string>\n"
                + "    <key>ProgramArguments</key>\n"
                + "    <array>\n"
                + args
                + "    </array>\n"
                + "    <key>RunAtLoad</key>\n"
                + "    <true/>\n"
                + "    <key>KeepAlive</key>\n"
                + "    <dict>\n"
                + "        <key>SuccessfulExit</key>\n"
                + "        <false/>\n"
                + "    </dict>\n"
                + "    <key>ThrottleInterval</key>\n"
                + "    <integer>10</integer>\n"
                + "    <key>StandardOutPath</key>\n"
                + "    <string>" + xml(logDir.resolve("service.out.log").toString()) + "</string>\n"
                + "    <key>StandardErrorPath</key>\n"
                + "    <string>" + xml(logDir.resolve("service.err.log").toString()) + "</string>\n"
                + "</dict>\n"
                + "</plist>\n";
    }

    static String winsw(Command c, Path logDir) {
        StringBuilder args = new StringBuilder();
        for (String a : c.arguments()) {
            if (!args.isEmpty()) {
                args.append(' ');
            }
            args.append(a.contains(" ") || a.contains(";") ? "\"" + a + "\"" : a);
        }
        // XML comments must not contain two hyphens in a row, so none of the text below uses them.
        return "<!-- HL7 Sender delivery service for WinSW (https://github.com/winsw/winsw), written by\n"
                + "     'hl7send service install'. 'hl7send service install -h' shows the steps. -->\n"
                + "<service>\n"
                + "  <id>" + NAME + "</id>\n"
                + "  <name>HL7 Sender</name>\n"
                + "  <description>Delivers queued HL7 messages (hl7send serve).</description>\n"
                + "  <executable>" + xml(c.java()) + "</executable>\n"
                + "  <arguments>" + xml(args.toString()) + "</arguments>\n"
                + "  <startmode>Automatic</startmode>\n"
                + "  <delayedAutoStart>true</delayedAutoStart>\n"
                + "  <stoptimeout>30 sec</stoptimeout>\n"
                + "  <onfailure action=\"restart\" delay=\"10 sec\"/>\n"
                + "  <logpath>" + xml(logDir.toString()) + "</logpath>\n"
                + "  <log mode=\"roll-by-size\">\n"
                + "    <sizeThreshold>10240</sizeThreshold>\n"
                + "    <keepFiles>8</keepFiles>\n"
                + "  </log>\n"
                + "  <!-- Run as your own account so the service shares the app's settings, queue and saved\n"
                + "       passwords:\n"
                + "  <serviceaccount>\n"
                + "    <username>.\\YOUR-USER</username>\n"
                + "    <allowservicelogon>true</allowservicelogon>\n"
                + "  </serviceaccount>\n"
                + "  -->\n"
                + "</service>\n";
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
