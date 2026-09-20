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

/// `fn status <address>` (spec §2, `function-api.md` §6.3): versions, live,
/// hosts (with their loaded state/error for each), wiring. A bare literal
/// address only — the spec's `<address|pattern>` multi-function listing
/// (`GET /api/functions?address=<pattern>`) is not implemented in this
/// slice (see the final report's ambiguity note).
@Command(name = "status", description = "Show a function's versions, live alias, hosts and wiring", sortOptions = false)
public final class StatusCommand implements Callable<Integer> {

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
            JsonNode node = root.client().get("/api/functions/" + addr + "/status");
            FunctionApi.StatusResponse status = Json.MAPPER.convertValue(node, FunctionApi.StatusResponse.class);
            print(root, status);
            return 0;
        });
    }

    private void print(FnCommand root, FunctionApi.StatusResponse status) {
        var out = spec.commandLine().getOut();
        if (root.output() == OutputMode.JSON) {
            out.println(Json.write(status));
            return;
        }
        out.printf("%s  %s%s%n", status.address(), status.status(),
                status.live() == null ? "" : " (live: v" + status.live().version() + ")");
        out.println("versions:");
        for (var v : status.versions()) {
            out.printf("  v%d  %s%n", v.version(), v.state());
        }
        out.println("hosts:");
        if (status.hosts().isEmpty()) {
            out.println("  (none)");
        }
        for (var h : status.hosts()) {
            out.printf("  %s  pool=%s  %s%s%n", h.hostId(), h.pool(), h.state(), h.stale() ? " (stale)" : "");
            for (var l : h.loaded()) {
                out.printf("    v%d  %s%s%n", l.version(), l.state(), l.error() == null ? "" : " (" + l.error() + ")");
            }
        }
        out.println("wiring:");
        if (status.wiring().isEmpty()) {
            out.println("  (none)");
        }
        for (var w : status.wiring()) {
            out.printf("  %s  %s%s%n", w.kind(), w.code(), w.present() ? "" : "  (MISSING)");
        }
    }
}
