package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.platform.function.api.FunctionDomainApi;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.concurrent.Callable;

/// `fn domain claim|list|release` (`docs/spec/function-public-routes.md`
/// §1, §5, amended `docs/spec/function-domains-no-dns.md`): the
/// `/api/function-domains` surface, thin over [FnClient] — same convention
/// as [ConfigCommand]/[VersionsCommand]. A claim is verified by being made —
/// there is no `verify` subcommand.
@Command(name = "domain", description = "Manage public hostnames: claim, list, release",
        subcommands = {DomainCommand.Claim.class, DomainCommand.List.class, DomainCommand.Release.class})
public final class DomainCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    /// `fn domain claim <hostname> [--client <id>]`: a claim is immediately
    /// usable (spec `function-domains-no-dns.md`: "a claim is verified by
    /// being made") — no TXT record to create. A claim is a ZONE (spec
    /// `function-zones-and-aliases.md` §1): it covers every hostname under
    /// it, not just the exact hostname given.
    @Command(name = "claim", description = "Claim a domain — covers every hostname under it", sortOptions = false)
    public static final class Claim implements Callable<Integer> {
        @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
        boolean help;

        @Parameters(index = "0", paramLabel = "<hostname>",
                description = "the domain to claim — covers every hostname under it")
        String hostname;

        @Option(names = "--client", paramLabel = "<id>", description = "claim for this client (default: platform-owned)")
        String client;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            FnCommand root = FnCommand.of(spec);
            return FnCommand.runSafely(spec, () -> {
                ObjectNode body = Json.MAPPER.createObjectNode();
                body.put("hostname", hostname);
                if (client != null && !client.isBlank()) {
                    body.put("clientId", client);
                }
                JsonNode node = root.client().post("/api/function-domains", body);
                print(root, Json.MAPPER.convertValue(node, FunctionDomainApi.DomainResponse.class));
                return 0;
            });
        }

        private void print(FnCommand root, FunctionDomainApi.DomainResponse d) {
            var out = spec.commandLine().getOut();
            if (root.output() == OutputMode.JSON) {
                out.println(Json.write(d));
                return;
            }
            out.printf("%s  owner=%s%n", d.hostname(), d.owner());
        }
    }

    /// `fn domain list [--client <id>]` — `--client` absent lists
    /// platform-owned domains (the wire literal `platform`,
    /// `FunctionOwner#fromWire`'s own default reading) — the common case for
    /// a local dev loop, where functions are typically platform-owned.
    @Command(name = "list", description = "List claimed hostnames", sortOptions = false)
    public static final class List implements Callable<Integer> {
        @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
        boolean help;

        @Option(names = "--client", paramLabel = "<id>", description = "list this client's domains (default: platform-owned)")
        String client;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            FnCommand root = FnCommand.of(spec);
            return FnCommand.runSafely(spec, () -> {
                String clientParam = (client == null || client.isBlank()) ? "platform" : client;
                JsonNode node = root.client().get("/api/function-domains?clientId="
                        + java.net.URLEncoder.encode(clientParam, java.nio.charset.StandardCharsets.UTF_8));
                var domains = Json.MAPPER.convertValue(node,
                        new TypeReference<java.util.List<FunctionDomainApi.DomainResponse>>() {
                        });
                print(root, domains);
                return 0;
            });
        }

        private void print(FnCommand root, java.util.List<FunctionDomainApi.DomainResponse> domains) {
            var out = spec.commandLine().getOut();
            if (root.output() == OutputMode.JSON) {
                out.println(Json.write(domains));
                return;
            }
            if (domains.isEmpty()) {
                out.println("(no domains claimed)");
                return;
            }
            for (var d : domains) {
                out.printf("%s  owner=%s%n", d.hostname(), d.owner());
            }
        }
    }

    @Command(name = "release", description = "Release a claimed hostname", sortOptions = false)
    public static final class Release implements Callable<Integer> {
        @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
        boolean help;

        @Parameters(index = "0", paramLabel = "<hostname>", description = "the hostname to release")
        String hostname;

        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            FnCommand root = FnCommand.of(spec);
            return FnCommand.runSafely(spec, () -> {
                root.client().delete("/api/function-domains/" + hostname);
                spec.commandLine().getOut().printf("%s released%n", hostname);
                return 0;
            });
        }
    }
}
