package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.platform.function.api.FunctionApi;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/// `fn validate <address> --manifest <file> [--alias <name>]` (spec
/// `function-manifest-authoring.md` M2.3): calls `POST
/// /api/functions/{address}/manifest/check` and prints the errors, or the
/// plan as a readable list. `--output json` prints the response body
/// verbatim instead. Never publishes or promotes anything.
///
/// Exit 0 when `valid`, 1 when not — `settingsMissing` alone (a PROMOTE
/// precondition, spec M2.2, not a publish one) never fails this command by
/// itself; it is printed as a warning line.
@Command(name = "validate", description = "Validate a manifest and preview its promote plan, without publishing",
        sortOptions = false)
public final class ValidateCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
    String address;

    @Option(names = "--manifest", required = true, paramLabel = "<file>", description = "the manifest JSON file")
    String manifestFile;

    @Option(names = "--alias", paramLabel = "<name>", defaultValue = "live",
            description = "the alias to preview promoting to (default: ${DEFAULT-VALUE})")
    String alias;

    @Mixin
    AddressOptions addressOpts;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        FnCommand root = FnCommand.of(spec);
        return FnCommand.runSafely(spec, () -> {
            String addr = addressOpts.resolve(address);
            JsonNode manifest = Publisher.readManifest(spec, manifestFile);
            var body = new FunctionApi.CheckManifestRequest(manifest, alias);
            JsonNode response =
                    root.client().post("/api/functions/" + addr + "/manifest/check", Json.MAPPER.valueToTree(body));
            boolean valid = response.path("valid").asBoolean(false);

            if (root.output() == OutputMode.JSON) {
                spec.commandLine().getOut().println(Json.write(response));
                return valid ? 0 : 1;
            }
            printText(response, valid);
            return valid ? 0 : 1;
        });
    }

    private void printText(JsonNode response, boolean valid) {
        PrintWriter out = spec.commandLine().getOut();
        if (!valid) {
            // spec `manifest-all-errors.md` §3: every problem, one per line, "code pointer: message".
            for (JsonNode error : response.path("errors")) {
                String pointer = error.path("details").path("pointer").asString("");
                out.printf("%s %s: %s%n", error.path("code").asString(), pointer, error.path("message").asString());
            }
            return;
        }

        JsonNode plan = response.path("plan");
        boolean httpOnly = plan.path("httpOnly").asBoolean(false);
        boolean anyWiringLine = false;
        if (httpOnly) {
            out.println("! named alias — no wiring change");
        } else {
            anyWiringLine |= printPoolLine(out, plan.path("pool"));
            for (JsonNode a : plan.path("subscriptions")) anyWiringLine |= printSubscriptionLine(out, a);
            for (JsonNode a : plan.path("schedules")) anyWiringLine |= printScheduleLine(out, a);
            anyWiringLine |= printPublicRoutesLines(out, plan.path("publicRoutes"));
        }

        boolean anyConflict = false;
        for (JsonNode c : plan.path("conflicts")) {
            anyConflict = true;
            out.printf("! conflict: %s: %s%n", c.path("code").asString(), c.path("message").asString());
        }

        List<String> missing = new ArrayList<>();
        for (JsonNode key : plan.path("settingsMissing")) missing.add(key.asString());
        if (!missing.isEmpty()) {
            out.printf("! settings missing: %s%n", String.join(", ", missing));
        }

        if (!httpOnly && !anyWiringLine && !anyConflict && missing.isEmpty()) {
            out.println("no changes");
        }
    }

    private static boolean printPoolLine(PrintWriter out, JsonNode pool) {
        String action = pool.path("action").asString();
        if ("unchanged".equals(action) || action == null) return false;
        if ("create".equals(action)) {
            out.println("+ pool (create)");
        } else {
            out.printf("~ pool (update: %s)%n", joinFields(pool));
        }
        return true;
    }

    private static boolean printSubscriptionLine(PrintWriter out, JsonNode a) {
        String action = a.path("action").asString();
        String eventType = a.path("eventType").asString();
        switch (action) {
            case "create" -> out.printf("+ subscription %s (create)%n", eventType);
            case "update" -> out.printf("~ subscription %s (update: %s)%n", eventType, joinFields(a));
            case "delete" -> out.printf("- subscription %s (delete)%n", eventType);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean printScheduleLine(PrintWriter out, JsonNode a) {
        String action = a.path("action").asString();
        String cron = a.path("cron").asString();
        switch (action) {
            case "create" -> out.printf("+ schedule \"%s\" (create)%n", cron);
            case "update" -> out.printf("~ schedule \"%s\" (update: %s)%n", cron, joinFields(a));
            case "delete" -> out.printf("- schedule \"%s\" (delete)%n", cron);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean printPublicRoutesLines(PrintWriter out, JsonNode publicRoutes) {
        if (!"replace".equals(publicRoutes.path("action").asString())) return false;
        boolean any = false;
        for (JsonNode r : publicRoutes.path("added")) {
            out.printf("+ route %s%s%n", r.path("hostname").asString(), r.path("pathPrefix").asString());
            any = true;
        }
        for (JsonNode r : publicRoutes.path("removed")) {
            out.printf("- route %s%s%n", r.path("hostname").asString(), r.path("pathPrefix").asString());
            any = true;
        }
        return any;
    }

    private static String joinFields(JsonNode a) {
        List<String> fields = new ArrayList<>();
        for (JsonNode f : a.path("changedFields")) fields.add(f.asString());
        return String.join(", ", fields);
    }
}
