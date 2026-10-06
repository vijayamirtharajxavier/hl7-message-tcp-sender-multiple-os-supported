package io.hl7sender.cli;

import io.hl7sender.core.api.JsonViews;
import io.hl7sender.core.hl7.validation.MessageValidator;
import io.hl7sender.core.hl7.validation.ValidationIssue;
import io.hl7sender.core.hl7.validation.ValidationReport;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Check a message's structure and data types without sending it.")
final class ValidateCommand implements Callable<Integer> {

    @Spec
    private CommandSpec spec;

    @Parameters(index = "0", paramLabel = "FILE", description = "Message file, or '-' to read standard input.")
    private Path file;

    @Option(names = "--charset", defaultValue = "UTF-8",
            description = "File character set (default: ${DEFAULT-VALUE}).")
    private Charset charset;

    @Mixin
    private Output output;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        String text;
        try {
            text = MessageInput.read(file, charset);
        } catch (IOException e) {
            spec.commandLine().getErr().println("Cannot read " + file + ": " + e.getMessage());
            return ExitCodes.INVALID_INPUT;
        }
        ValidationReport report = new MessageValidator().validate(text);
        int code = report.hasErrors() ? ExitCodes.INVALID_INPUT : ExitCodes.OK;
        if (output.json) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("valid", !report.hasErrors());
            m.put("summary", report.summary());
            m.put("issues", JsonViews.issues(report));
            m.put("exitCode", code);
            Output.printJson(out, m);
            return code;
        }
        for (ValidationIssue issue : report.issues()) {
            out.println(issue);
        }
        out.println(report.summary());
        out.flush();
        return code;
    }
}
