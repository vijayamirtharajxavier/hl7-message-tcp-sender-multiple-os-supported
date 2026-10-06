package io.hl7sender.cli;

import io.hl7sender.core.api.ApiClient;
import io.hl7sender.core.api.ApiEndpoint;
import io.hl7sender.core.api.ApiToken;
import io.hl7sender.core.config.AppPaths;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** {@code hl7send api ...}: the local REST API's token and status. */
@Command(name = "api", mixinStandardHelpOptions = true,
        description = {"The local REST API for test harnesses and scripts (http://127.0.0.1:PORT/api/v1/).",
            "It is served by the app (Tools > Local API) or by 'hl7send serve --api'. Every request needs the header "
                + "'Authorization: Bearer TOKEN'."},
        subcommands = {ApiCommand.Token.class, ApiCommand.Status.class})
final class ApiCommand {

    private ApiCommand() {
    }

    @Command(name = "token", mixinStandardHelpOptions = true,
            description = "Print the API token (creating one if needed), or replace it with --regenerate.")
    static final class Token implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Option(names = "--regenerate", description = "Replace the token; clients using the old one are refused "
                + "from then on, also by a server that is already running.")
        boolean regenerate;

        @Override
        public Integer call() throws IOException {
            AppPaths paths = AppPaths.detect();
            String token = regenerate ? ApiToken.regenerate(paths.configDir())
                    : ApiToken.loadOrCreate(paths.configDir());
            spec.commandLine().getOut().println(token);
            spec.commandLine().getOut().flush();
            return ExitCodes.OK;
        }
    }

    @Command(name = "status", mixinStandardHelpOptions = true, description = "Check whether the API is being served.")
    static final class Status implements Callable<Integer> {
        @Spec
        CommandSpec spec;
        @Mixin
        Output output;

        @Override
        public Integer call() {
            PrintWriter out = spec.commandLine().getOut();
            AppPaths paths = AppPaths.detect();
            Optional<ApiEndpoint> endpoint = ApiEndpoint.read(paths);
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("running", false);
            doc.put("tokenFile", ApiToken.file(paths.configDir()).toString());
            if (endpoint.isPresent()) {
                doc.put("url", "http://127.0.0.1:" + endpoint.get().port() + "/api/v1/");
                Optional<ApiClient> client = ApiClient.discover(paths);
                try {
                    boolean up = client.isPresent() && client.get().get("destinations").ok();
                    doc.put("running", up);
                } catch (IOException e) {
                    doc.put("error", e.getMessage());
                }
            }
            boolean running = (Boolean) doc.get("running");
            if (output.json) {
                Output.printJson(out, doc);
            } else if (running) {
                out.println("The local API is running at " + doc.get("url"));
                out.println("Token: " + doc.get("tokenFile"));
            } else {
                out.println("The local API is not running. Enable it in the app (Tools > Local API) or run: hl7send "
                        + "serve --api");
            }
            out.flush();
            return running ? ExitCodes.OK : ExitCodes.QUEUE_UNAVAILABLE;
        }
    }
}
