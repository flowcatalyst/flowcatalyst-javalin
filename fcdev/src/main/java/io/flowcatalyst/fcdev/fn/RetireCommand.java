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

import java.util.concurrent.Callable;

/// `fn retire <address> --version <n>` (spec §2): `POST
/// /api/functions/{address}/versions/{v}/retire`. The platform refuses to
/// retire the live version (`VERSION_IS_LIVE`) — an ordinary error here.
@Command(name = "retire", description = "Retire a version", sortOptions = false)
public final class RetireCommand implements Callable<Integer> {

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Parameters(index = "0", arity = "0..1", paramLabel = "<address>", description = "full function address app.service.name")
    String address;

    @Option(names = "--version", required = true, paramLabel = "<n>", description = "the version to retire")
    int version;

    @Mixin
    AddressOptions addressOpts;

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        FnCommand root = FnCommand.of(spec);
        return FnCommand.runSafely(spec, () -> {
            String addr = addressOpts.resolve(address);
            JsonNode node = root.client().post("/api/functions/" + addr + "/versions/" + version + "/retire", null);
            FunctionApi.VersionResponse retired = Json.MAPPER.convertValue(node, FunctionApi.VersionResponse.class);
            print(root, addr, retired);
            return 0;
        });
    }

    private void print(FnCommand root, String address, FunctionApi.VersionResponse retired) {
        var out = spec.commandLine().getOut();
        switch (root.output()) {
            case TEXT -> out.printf("%s: version %d retired%n", address, retired.version());
            case JSON -> out.println(Json.write(retired));
        }
    }
}
