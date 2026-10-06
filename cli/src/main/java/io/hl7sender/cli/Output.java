package io.hl7sender.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.hl7sender.core.api.JsonViews;
import java.io.PrintWriter;
import picocli.CommandLine.Option;

/** The {@code --json} option: one JSON document on standard output instead of text, for scripts and CI. */
final class Output {

    @Option(names = "--json", description = "Print the result as JSON (for scripts and CI pipelines).")
    boolean json;

    static void printJson(PrintWriter out, Object value) {
        try {
            out.println(JsonViews.PRETTY.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        out.flush();
    }
}
