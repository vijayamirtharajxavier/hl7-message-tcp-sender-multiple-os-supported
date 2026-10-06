package io.hl7sender.cli;

import io.hl7sender.core.auth.Permission;
import io.hl7sender.core.hl7.Hl7Text;
import io.hl7sender.core.script.MessageScript;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code hl7send script test}: run a transform script on a message without queuing anything. */
@Command(name = "script", mixinStandardHelpOptions = true,
        description = "Try destination transform scripts (JavaScript run on each message as it is queued).",
        subcommands = ScriptCommand.Test.class)
final class ScriptCommand {

    private ScriptCommand() {
    }

    /** The script to run: from a file, or the one saved on a destination. */
    static final class Source {
        @Option(names = "--script", required = true, paramLabel = "FILE", description = "Script file.")
        Path file;
        @Option(names = {"-d", "--destination"}, required = true,
                description = "Use this destination's saved script.")
        String destination;
    }

    @Command(name = "test", mixinStandardHelpOptions = true,
            description = "Run a script on a message and print the changed message (or why it was filtered out). "
                    + "Exit code 1 if the script fails or filters the message.")
    static final class Test implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @ArgGroup(multiplicity = "1")
        Source source;
        @Parameters(paramLabel = "FILE", description = "Message file, or '-' for standard input.")
        Path message;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            String script;
            if (source.file != null) {
                script = Files.readString(source.file, StandardCharsets.UTF_8);
            } else {
                try (Workspace ws = Workspace.open(Permission.VIEW, "read destination scripts")) {
                    script = ws.destination(source.destination).script();
                }
                if (script.isBlank()) {
                    err.println("Destination '" + source.destination + "' has no script");
                    return ExitCodes.USAGE;
                }
            }
            String text = MessageInput.read(message, StandardCharsets.UTF_8);
            Map<String, Object> doc = new LinkedHashMap<>();
            int code;
            try {
                MessageScript.Result r = MessageScript.compile(script).apply(text);
                code = r.filtered() ? ExitCodes.INVALID_INPUT : ExitCodes.OK;
                doc.put("filtered", r.filtered());
                doc.put("filterReason", r.filtered() ? r.filterReason() : null);
                doc.put("message", r.filtered() ? null : r.message());
                doc.put("log", r.log());
                if (!output.json) {
                    r.log().forEach(l -> err.println("log: " + l));
                    if (r.filtered()) {
                        out.println("Filtered out (not queued)" + (r.filterReason().isEmpty() ? ""
                                : ": " + r.filterReason()));
                    } else {
                        out.println(Hl7Text.toDisplay(r.message()));
                    }
                }
            } catch (MessageScript.ScriptFailure e) {
                code = ExitCodes.INVALID_INPUT;
                doc.put("error", e.getMessage());
                if (!output.json) {
                    err.println(e.getMessage());
                }
            }
            if (output.json) {
                doc.put("exitCode", code);
                Output.printJson(out, doc);
            }
            out.flush();
            err.flush();
            return code;
        }
    }
}
