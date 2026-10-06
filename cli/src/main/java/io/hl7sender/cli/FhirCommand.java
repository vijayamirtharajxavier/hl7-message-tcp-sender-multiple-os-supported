package io.hl7sender.cli;

import io.hl7sender.core.fhir.V2ToFhir;
import io.hl7sender.core.hl7.Hl7FormatException;
import io.hl7sender.core.queue.DestinationConfig;
import io.hl7sender.core.send.AckMode;
import io.hl7sender.core.send.Hl7Sender;
import io.hl7sender.core.send.PreparedMessage;
import io.hl7sender.core.send.SendOptions;
import io.hl7sender.core.send.SendResult;
import io.hl7sender.core.transport.Transport;
import io.hl7sender.core.transport.TransportContext;
import io.hl7sender.core.transport.Transports;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** {@code hl7send fhir ...}: HL7 v2 to FHIR R4. */
@Command(name = "fhir", mixinStandardHelpOptions = true,
        description = "Convert HL7 v2 messages to FHIR R4 transaction Bundles, or send them to a FHIR server.",
        subcommands = {FhirCommand.Convert.class, FhirCommand.Send.class})
final class FhirCommand {

    private FhirCommand() {
    }

    @Command(name = "convert", mixinStandardHelpOptions = true,
            description = "Print the FHIR R4 Bundle for a message (Patient, Encounter, Observations, ...). Notes about "
                    + "segments that were not converted go to standard error.")
    static final class Convert implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Parameters(paramLabel = "FILE", description = "Message file, or '-' for standard input.")
        Path file;
        @Option(names = {"-o", "--output"}, paramLabel = "FILE", description = "Write the Bundle to FILE.")
        Path output;

        @Override
        public Integer call() throws IOException {
            PrintWriter out = spec.commandLine().getOut();
            PrintWriter err = spec.commandLine().getErr();
            V2ToFhir.Result r;
            try {
                r = V2ToFhir.convert(MessageInput.read(file, StandardCharsets.UTF_8));
            } catch (Hl7FormatException | IllegalArgumentException e) {
                err.println("Cannot convert: " + e.getMessage());
                return ExitCodes.INVALID_INPUT;
            }
            if (output != null) {
                Files.writeString(output, r.json(), StandardCharsets.UTF_8);
                out.println("Wrote " + output + ": " + r.resources());
            } else {
                out.println(r.json());
            }
            r.notes().forEach(n -> err.println("Note: " + n));
            out.flush();
            err.flush();
            return ExitCodes.OK;
        }
    }

    @Command(name = "send", mixinStandardHelpOptions = true,
            description = {"Convert a message and POST the Bundle to a FHIR server now (not queued, no retries).",
                "To queue with retries, create a destination with the FHIR transport and use 'queue send'."})
    static final class Send implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;
        @Option(names = "--url", required = true, paramLabel = "BASE", description = "FHIR base URL.")
        String url;
        @Option(names = "--header", paramLabel = "NAME: VALUE", description = "Extra header, e.g. "
                + "'Authorization: Bearer ...' (repeatable).")
        java.util.List<String> headers = new java.util.ArrayList<>();
        @Option(names = "--timeout", paramLabel = "MS", defaultValue = "30000", description = "Response timeout.")
        int timeoutMs;
        @Parameters(paramLabel = "FILE", description = "Message file, or '-' for standard input.")
        Path file;

        @Override
        public Integer call() throws Exception {
            PrintWriter out = spec.commandLine().getOut();
            Map<String, String> options = Map.of("baseUrl", url, "headers", String.join("\n", headers));
            Transports transports = Transports.builtIn();
            try {
                transports.validate("fhir", options);
            } catch (IllegalArgumentException e) {
                throw new Workspace.UsageException(e.getMessage());
            }
            DestinationConfig d = DestinationConfig.of("FHIR", "localhost", 1).withTimeouts(10_000, timeoutMs)
                    .withTransport("fhir", options);
            PreparedMessage message = new Hl7Sender().prepare(MessageInput.read(file, StandardCharsets.UTF_8),
                    new SendOptions(false, false, AckMode.EXPECT_ACK));
            if (message.validation().hasErrors()) {
                spec.commandLine().getErr().println(message.validation().errors().get(0).message());
                return ExitCodes.INVALID_INPUT;
            }
            SendResult r;
            try (Transport t = transports.get("fhir").orElseThrow().open(d, new TransportContext(Optional.empty()))) {
                r = t.send(message, AckMode.EXPECT_ACK);
            }
            int code = ExitCodes.of(r.outcome());
            if (output.json) {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("outcome", r.outcome().name());
                doc.put("detail", r.detail());
                doc.put("response", r.rawResponse().orElse(null));
                doc.put("exitCode", code);
                Output.printJson(out, doc);
            } else {
                out.println(r.outcome().name() + ": " + r.detail());
            }
            out.flush();
            return code;
        }
    }
}
