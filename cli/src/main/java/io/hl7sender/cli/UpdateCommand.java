package io.hl7sender.cli;

import io.hl7sender.core.update.UpdateChecker;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** {@code hl7send update}: is a newer version available? Nothing is downloaded or installed. */
@Command(name = "update", mixinStandardHelpOptions = true,
        description = {"Check GitHub Releases for a newer version and print where to download it.",
            "Nothing is downloaded or installed, and no information about you or your messages is sent."})
final class UpdateCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;
    @Mixin
    Output output;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        UpdateChecker.Result r;
        try {
            r = UpdateChecker.forThisBuild().check();
        } catch (IOException e) {
            spec.commandLine().getErr().println("Could not check for updates: " + e.getMessage());
            return ExitCodes.CONNECTION_FAILURE;
        }
        if (output.json) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("current", r.current());
            m.put("latest", r.latest().version());
            m.put("updateAvailable", r.newer());
            m.put("url", r.latest().pageUrl());
            Output.printJson(out, m);
        } else if (r.newer()) {
            out.println("Version " + r.latest().version() + " is available (you have " + r.current() + ").");
            out.println("Download: " + r.latest().pageUrl());
        } else {
            out.println("You have the latest version (" + r.current() + ").");
        }
        out.flush();
        return ExitCodes.OK;
    }
}
