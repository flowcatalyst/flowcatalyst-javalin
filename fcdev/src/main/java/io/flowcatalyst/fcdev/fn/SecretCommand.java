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

import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/// `fn secret set|list|delete` (`docs/spec/function-context.md` §1, spec §4
/// E6): a secret's VALUE is never a CLI argument or option — only stdin (no
/// echo when a console is attached) or `--from-file` — and never printed,
/// logged, or echoed anywhere this process writes.
@Command(name = "secret", description = "Set, list, or delete a function's secrets",
        subcommands = {SecretCommand.Set.class, SecretCommand.List.class, SecretCommand.Delete.class})
public final class SecretCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    @Command(name = "set", description = "Set a secret value (from stdin, or --from-file — never an argument)",
            sortOptions = false)
    public static final class Set implements Callable<Integer> {
        @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
        boolean help;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
        String address;

        @Parameters(index = "1", paramLabel = "<KEY>", description = "the secret's key")
        String key;

        @Option(names = "--from-file", paramLabel = "<file>", description = "read the value from this file instead of stdin")
        String fromFile;

        @Option(names = "--manifest", paramLabel = "<file>",
                description = "the manifest JSON file, to create the function from if it does not exist yet "
                        + "(default: manifest.json in the working directory, when present)")
        String manifestFile;

        @Option(names = "--client", paramLabel = "<id>", description = "owning client id, when creating a client-owned function")
        String client;

        @Option(names = "--no-create", description = "fail (exit 1) instead of creating the function when its address is unknown")
        boolean noCreate;

        @Mixin
        AddressOptions addressOpts;

        @Spec
        CommandSpec spec;

        /// Test seams: piped stdin, and a fake "console" so a test never has
        /// to attach a real TTY. `docs/fcdev.md`'s convention (`DbCommand.Upgrade`'s
        /// own `in` field) extended with a console hook.
        InputStream stdin = System.in;
        Console console = System.console();

        @Override
        public Integer call() {
            FnCommand root = FnCommand.of(spec);
            return FnCommand.runSafely(spec, () -> {
                String addr = addressOpts.resolve(address);
                String value = resolveValue();
                if (value.isEmpty()) {
                    throw new CommandLine.ParameterException(spec.commandLine(), "secret value must not be empty");
                }
                FnClient platform = root.client();
                JsonNode manifest = Publisher.resolveOptionalManifest(spec, manifestFile);
                // `secret set` PUTs one key directly, with no GET of its own — the
                // existence check below is what catches a 404 and creates.
                Publisher.ensureFunctionExists(platform, addr, manifest, client, noCreate);
                var body = new FunctionApi.SetSecretRequest(value);
                platform.put("/api/functions/" + addr + "/secrets/" + key, Json.MAPPER.valueToTree(body));
                print(root, addr);
                return 0;
            });
        }

        private String resolveValue() throws IOException {
            if (fromFile != null && !fromFile.isBlank()) {
                return stripTrailingNewline(Files.readString(Path.of(fromFile)));
            }
            if (console != null) {
                char[] chars = console.readPassword("value: ");
                return chars == null ? "" : new String(chars);
            }
            return stripTrailingNewline(new String(stdin.readAllBytes(), StandardCharsets.UTF_8));
        }

        private static String stripTrailingNewline(String s) {
            if (s.endsWith("\r\n")) return s.substring(0, s.length() - 2);
            if (s.endsWith("\n")) return s.substring(0, s.length() - 1);
            return s;
        }

        private void print(FnCommand root, String address) {
            var out = spec.commandLine().getOut();
            switch (root.output()) {
                case TEXT -> out.printf("%s: secret %s set%n", address, key);
                case JSON -> out.println(Json.write(new java.util.LinkedHashMap<>(java.util.Map.of(
                        "address", address, "key", key, "status", "set"))));
            }
        }
    }

    @Command(name = "list", description = "List a function's secret keys (never their values)", sortOptions = false)
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
                JsonNode node = root.client().get("/api/functions/" + addr + "/secrets");
                var list = Json.MAPPER.convertValue(node, FunctionApi.SecretListResponse.class);
                print(root, list);
                return 0;
            });
        }

        private void print(FnCommand root, FunctionApi.SecretListResponse list) {
            var out = spec.commandLine().getOut();
            if (root.output() == OutputMode.JSON) {
                out.println(Json.write(list));
                return;
            }
            for (var k : list.keys()) {
                out.printf("%s  updated %s by %s%n", k.key(), k.updatedAt(), k.updatedBy());
            }
            if (!list.missing().isEmpty()) {
                out.println("missing (declared, unset): " + String.join(", ", list.missing()));
            }
        }
    }

    @Command(name = "delete", description = "Delete a secret", sortOptions = false)
    public static final class Delete implements Callable<Integer> {
        @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
        boolean help;

        @Parameters(index = "0", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
        String address;

        @Parameters(index = "1", paramLabel = "<KEY>", description = "the secret's key")
        String key;

        @Mixin
        AddressOptions addressOpts;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            FnCommand root = FnCommand.of(spec);
            return FnCommand.runSafely(spec, () -> {
                String addr = addressOpts.resolve(address);
                root.client().delete("/api/functions/" + addr + "/secrets/" + key);
                var out = spec.commandLine().getOut();
                switch (root.output()) {
                    case TEXT -> out.printf("%s: secret %s deleted%n", addr, key);
                    case JSON -> out.println(Json.write(new java.util.LinkedHashMap<>(java.util.Map.of(
                            "address", addr, "key", key, "status", "deleted"))));
                }
                return 0;
            });
        }
    }
}
