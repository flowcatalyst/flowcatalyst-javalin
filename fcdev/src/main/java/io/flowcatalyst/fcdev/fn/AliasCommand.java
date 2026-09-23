package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.platform.function.api.FunctionApi;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

import java.util.concurrent.Callable;

/// `fn alias list|delete` (`docs/spec/function-zones-and-aliases.md` §6):
/// aliases are HTTP-only (never wiring) — `fn promote --alias <name>` is
/// where one is CREATED or moved; this command only lists and removes them.
@Command(name = "alias", description = "List or delete a function's aliases",
        subcommands = {AliasCommand.List.class, AliasCommand.Delete.class})
public final class AliasCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    @Command(name = "list", description = "List a function's aliases", sortOptions = false)
    public static final class List implements Callable<Integer> {
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
                JsonNode node = root.client().get("/api/functions/" + addr + "/aliases");
                java.util.List<FunctionApi.AliasResponse> aliases =
                        Json.MAPPER.convertValue(node, new TypeReference<java.util.List<FunctionApi.AliasResponse>>() {
                        });
                print(root, aliases);
                return 0;
            });
        }

        private void print(FnCommand root, java.util.List<FunctionApi.AliasResponse> aliases) {
            var out = spec.commandLine().getOut();
            if (root.output() == OutputMode.JSON) {
                out.println(Json.write(aliases));
                return;
            }
            if (aliases.isEmpty()) {
                out.println("(no aliases)");
            }
            for (var a : aliases) {
                out.printf("%s -> v%d  updated %s by %s%n", a.alias(), a.version(), a.updatedAt(), a.updatedBy());
            }
        }
    }

    @Command(name = "delete", description = "Delete a named alias (live cannot be removed)", sortOptions = false)
    public static final class Delete implements Callable<Integer> {
        @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
        boolean help;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
        String address;

        @Parameters(index = "1", paramLabel = "<alias>", description = "the alias to remove")
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
                root.client().delete("/api/functions/" + addr + "/aliases/" + alias);
                var out = spec.commandLine().getOut();
                switch (root.output()) {
                    case TEXT -> out.printf("%s: alias %s deleted%n", addr, alias);
                    case JSON -> out.println(Json.write(new java.util.LinkedHashMap<>(java.util.Map.of(
                            "address", addr, "alias", alias, "status", "deleted"))));
                }
                return 0;
            });
        }
    }
}
