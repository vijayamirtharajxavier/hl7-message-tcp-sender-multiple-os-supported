package io.hl7sender.cli;

import io.hl7sender.core.config.AppPaths;
import io.hl7sender.core.update.UpdateChecker;
import io.hl7sender.core.update.UpdateInstaller;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * {@code hl7send update}: is a newer version available? With {@code --install}, downloads it, checks it against the
 * release's SHA-256 checksums and installs it over this copy.
 */
@Command(name = "update", mixinStandardHelpOptions = true,
        description = {"Check GitHub Releases for a newer version and print where to download it.",
            "With --install, download it, check it against the release's SHA-256 checksums and install it over this "
                    + "copy. Settings, destinations and the queue are kept. No information about you or your "
                    + "messages is sent."})
final class UpdateCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;
    @Mixin
    Output output;

    @Option(names = "--install", description = "Download and install the newer version, if there is one.")
    boolean install;

    @Option(names = {"-y", "--yes"}, description = "With --install: do not ask for confirmation.")
    boolean yes;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        UpdateChecker.Result r;
        try {
            r = UpdateChecker.forThisBuild().check();
        } catch (IOException e) {
            err.println("Could not check for updates: " + e.getMessage());
            return ExitCodes.CONNECTION_FAILURE;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("current", r.current());
        json.put("latest", r.latest().version());
        json.put("updateAvailable", r.newer());
        json.put("url", r.latest().pageUrl());
        if (!install || !r.newer()) {
            if (output.json) {
                Output.printJson(out, json);
            } else if (r.newer()) {
                out.println("Version " + r.latest().version() + " is available (you have " + r.current() + ").");
                out.println("Download: " + r.latest().pageUrl());
                out.println("Or install it with: hl7send update --install");
            } else {
                out.println("You have the latest version (" + r.current() + ").");
            }
            out.flush();
            return ExitCodes.OK;
        }
        return install(r, json, out, err);
    }

    private int install(UpdateChecker.Result r, Map<String, Object> json, PrintWriter out, PrintWriter err) {
        String version = r.latest().version();
        UpdateInstaller.Detection detection = UpdateInstaller.detect();
        if (detection.installation().isEmpty()) {
            err.println("Cannot install updates from here: " + switch (detection.unsupported().orElseThrow()) {
                case DISABLED -> "installing updates is turned off (-D" + UpdateInstaller.ENABLED_PROPERTY + "=false).";
                case DEVELOPMENT_BUILD -> "this is not an installed copy of HL7 Sender.";
                case PLATFORM -> "no installer is published for this system.";
                case INSTALLATION -> "HL7 Sender cannot replace this installation (see the administrator guide).";
            });
            err.println("Download version " + version + " from " + r.latest().pageUrl());
            return ExitCodes.USAGE;
        }
        UpdateInstaller installer = new UpdateInstaller(detection.installation().get(),
                AppPaths.detect().dataDir().resolve("updates"));
        if (!installer.canInstall(r.latest())) {
            err.println("Version " + version + " has no installer for this system. Download it from "
                    + r.latest().pageUrl());
            return ExitCodes.USAGE;
        }
        if (!yes) {
            java.io.Console console = System.console();
            if (console == null) {
                err.println("Add --yes to install version " + version + " without asking.");
                return ExitCodes.USAGE;
            }
            String answer = console.readLine("Install version %s over %s? [y/N] ", version, r.current());
            if (answer == null || !answer.trim().toLowerCase(java.util.Locale.ROOT).startsWith("y")) {
                err.println("Not installed.");
                return ExitCodes.OK;
            }
        }
        UpdateInstaller.Prepared update;
        try {
            int[] shown = {-1};
            update = installer.download(r.latest(), f -> {
                int percent = (int) (f * 100);
                if (!output.json && percent / 10 != shown[0]) {
                    shown[0] = percent / 10;
                    err.print("\rDownloading version " + version + ": " + percent + "%");
                    err.flush();
                }
            });
            if (!output.json) {
                err.println();
            }
        } catch (IOException e) {
            err.println();
            err.println("Could not download version " + version + ": " + e.getMessage());
            return ExitCodes.CONNECTION_FAILURE;
        }
        json.put("installer", update.file().toString());
        try {
            if (update.installation().kind() == UpdateInstaller.Kind.WINDOWS_MSI) {
                // Windows cannot replace files in use: install once this program has exited.
                UpdateInstaller.launch(update, ProcessHandle.current().pid(), false);
                json.put("installing", true);
                if (output.json) {
                    Output.printJson(out, json);
                } else {
                    out.println("Version " + version + " is verified and will be installed as soon as hl7send exits.");
                    out.println("Close HL7 Sender and stop the hl7send service first if they are running. "
                            + "Installer log: " + update.file().resolveSibling("msiexec.log"));
                }
                out.flush();
                return ExitCodes.OK;
            }
            int code = UpdateInstaller.installNow(update);
            json.put("installed", code == 0);
            if (output.json) {
                Output.printJson(out, json);
            } else if (code == 0) {
                out.println("Version " + version + " is installed. Restart HL7 Sender if it is open.");
            } else {
                err.println("Installing version " + version + " failed (exit code " + code + "). The installer is "
                        + update.file());
            }
            out.flush();
            return code == 0 ? ExitCodes.OK : ExitCodes.APPLICATION_ERROR;
        } catch (IOException e) {
            err.println("Could not install version " + version + ": " + e.getMessage());
            return ExitCodes.APPLICATION_ERROR;
        }
    }
}
