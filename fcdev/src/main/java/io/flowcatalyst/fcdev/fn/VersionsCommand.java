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

import java.util.List;
import java.util.concurrent.Callable;

/// `fn versions <address>` (spec §2): `GET /api/functions/{address}/versions`, newest first.
@Command(name = "versions", description = "List a function's versions", sortOptions = false)
public final class VersionsCommand implements Callable<Integer> {

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
            JsonNode node = root.client().get("/api/functions/" + addr + "/versions");
            List<FunctionApi.VersionResponse> versions =
                    Json.MAPPER.convertValue(node, new TypeReference<List<FunctionApi.VersionResponse>>() {
                    });
            print(root, versions);
            return 0;
        });
    }

    private void print(FnCommand root, List<FunctionApi.VersionResponse> versions) {
        var out = spec.commandLine().getOut();
        if (root.output() == OutputMode.JSON) {
            out.println(Json.write(versions));
            return;
        }
        if (versions.isEmpty()) {
            out.println("(no versions published)");
        }
        for (var v : versions) {
            out.printf("v%d  %s  %s%s%n", v.version(), v.state(), v.digest(), v.live() ? "  (live)" : "");
        }
    }
}
