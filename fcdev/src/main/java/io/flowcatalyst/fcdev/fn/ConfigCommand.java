package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.platform.function.api.FunctionApi;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

/// `fn config get|set <address> [KEY=VALUE…]` (`docs/spec/function-context.md`
/// §1): non-secret, per-function config, read-modify-write on `set`.
@Command(name = "config", description = "Get or set a function's config values",
        subcommands = {ConfigCommand.Get.class, ConfigCommand.Set.class})
public final class ConfigCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    @Command(name = "get", description = "Show a function's config values", sortOptions = false)
    public static final class Get implements Callable<Integer> {
        @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
        boolean help;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
        String address;

        @Mixin
        AddressOptions addressOpts;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            FnCommand root = FnCommand.of(spec);
            return FnCommand.runSafely(spec, () -> {
                String addr = addressOpts.resolve(address);
                JsonNode node = root.client().get("/api/functions/" + addr + "/config");
                var config = Json.MAPPER.convertValue(node, FunctionApi.ConfigResponse.class);
                print(root, config);
                return 0;
            });
        }

        private void print(FnCommand root, FunctionApi.ConfigResponse config) {
            var out = spec.commandLine().getOut();
            if (root.output() == OutputMode.JSON) {
                out.println(Json.write(config));
                return;
            }
            for (var e : new TreeMap<>(config.values()).entrySet()) {
                out.printf("%s=%s%n", e.getKey(), e.getValue());
            }
            if (!config.missing().isEmpty()) {
                out.println("missing (declared, unset): " + String.join(", ", config.missing()));
            }
        }
    }

    @Command(name = "set", description = "Set config values (read-modify-write of the full map)", sortOptions = false)
    public static final class Set implements Callable<Integer> {
        @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
        boolean help;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
        String address;

        @Parameters(index = "1..*", paramLabel = "<KEY=VALUE>", description = "one or more key=value pairs to set")
        List<String> assignments;

        @Mixin
        AddressOptions addressOpts;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            FnCommand root = FnCommand.of(spec);
            return FnCommand.runSafely(spec, () -> {
                String addr = addressOpts.resolve(address);
                Map<String, String> updates = parseAssignments();
                FnClient platform = root.client();
                JsonNode current = platform.get("/api/functions/" + addr + "/config");
                var existing = Json.MAPPER.convertValue(current, FunctionApi.ConfigResponse.class);
                var merged = new LinkedHashMap<>(existing.values());
                merged.putAll(updates);
                var body = new FunctionApi.SetConfigRequest(merged);
                JsonNode node = platform.put("/api/functions/" + addr + "/config", Json.MAPPER.valueToTree(body));
                var result = Json.MAPPER.convertValue(node, FunctionApi.ConfigResponse.class);
                print(root, result);
                return 0;
            });
        }

        private Map<String, String> parseAssignments() {
            Map<String, String> out = new LinkedHashMap<>();
            for (String a : assignments == null ? List.<String>of() : assignments) {
                int i = a.indexOf('=');
                if (i <= 0) {
                    throw new CommandLine.ParameterException(spec.commandLine(),
                            "expected KEY=VALUE, got \"" + a + "\"");
                }
                out.put(a.substring(0, i), a.substring(i + 1));
            }
            return out;
        }

        private void print(FnCommand root, FunctionApi.ConfigResponse config) {
            var out = spec.commandLine().getOut();
            if (root.output() == OutputMode.JSON) {
                out.println(Json.write(config));
                return;
            }
            for (var e : new TreeMap<>(config.values()).entrySet()) {
                out.printf("%s=%s%n", e.getKey(), e.getValue());
            }
        }
    }
}
