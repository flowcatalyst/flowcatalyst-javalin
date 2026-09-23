package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.platform.function.api.FunctionApi;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine.Model.CommandSpec;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/// The shared promote logic behind `fn promote` and `fn deploy` (spec §2,
/// §4 E5, `function-zones-and-aliases.md` §6): poll `GET …/status` once a
/// second until `version` is `READY`, then `PUT …/aliases/{alias}`; `wait`
/// zero skips the wait entirely (the platform's own `VERSION_NOT_READY` is
/// then just an ordinary error); a timeout never calls promote. `fn deploy`
/// and `fn watch` always promote `live` — only `fn promote` exposes
/// `--alias`.
final class Promoter {

    static final String LIVE = "live";

    private Promoter() {
    }

    /// @return the promote response on success, or `null` on a timeout — the
    ///         timeout message was already printed to `spec`'s err writer,
    ///         so the caller just needs to exit 1
    static FunctionApi.PromoteResponse promote(CommandSpec spec, FnCommand root, FnClient platform, String address,
                                                int version, Duration wait, LongSupplier clockMillis,
                                                LongConsumer sleepMillis) {
        return promote(spec, root, platform, address, LIVE, version, wait, clockMillis, sleepMillis);
    }

    /// @return the promote response on success, or `null` on a timeout — the
    ///         timeout message was already printed to `spec`'s err writer,
    ///         so the caller just needs to exit 1
    static FunctionApi.PromoteResponse promote(CommandSpec spec, FnCommand root, FnClient platform, String address,
                                                String alias, int version, Duration wait, LongSupplier clockMillis,
                                                LongConsumer sleepMillis) {
        if (!wait.isZero()) {
            long deadline = clockMillis.getAsLong() + wait.toMillis();
            FunctionApi.StatusResponse status = status(platform, address);
            while (!isReady(status, version)) {
                if (clockMillis.getAsLong() >= deadline) {
                    printTimeout(spec, root, address, version, status);
                    return null;
                }
                sleepMillis.accept(1000);
                status = status(platform, address);
            }
        }
        var body = new FunctionApi.PromoteRequest(version);
        JsonNode response = platform.put("/api/functions/" + address + "/aliases/" + alias, Json.MAPPER.valueToTree(body));
        return Json.MAPPER.convertValue(response, FunctionApi.PromoteResponse.class);
    }

    private static FunctionApi.StatusResponse status(FnClient platform, String address) {
        JsonNode node = platform.get("/api/functions/" + address + "/status");
        return Json.MAPPER.convertValue(node, FunctionApi.StatusResponse.class);
    }

    private static boolean isReady(FunctionApi.StatusResponse status, int version) {
        return status.versions().stream().anyMatch(v -> v.version() == version && "READY".equals(v.state()));
    }

    private static void printTimeout(CommandSpec spec, FnCommand root, String address, int version,
                                      FunctionApi.StatusResponse status) {
        List<Map<String, Object>> hosts = new ArrayList<>();
        for (var h : status.hosts()) {
            for (var loaded : h.loaded()) {
                if (loaded.version() == version) {
                    var row = new LinkedHashMap<String, Object>();
                    row.put("hostId", h.hostId());
                    row.put("state", loaded.state());
                    row.put("error", loaded.error());
                    hosts.add(row);
                }
            }
        }
        var err = spec.commandLine().getErr();
        if (root.output() == OutputMode.JSON) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("error", "TIMEOUT");
            payload.put("address", address);
            payload.put("version", version);
            payload.put("hosts", hosts);
            err.println(Json.write(payload));
        } else {
            err.println("timed out waiting for " + address + " version " + version + " to become READY:");
            if (hosts.isEmpty()) {
                err.println("  (no host reports this version)");
            }
            for (var h : hosts) {
                err.println("  " + h.get("hostId") + ": " + h.get("state")
                        + (h.get("error") != null ? " (" + h.get("error") + ")" : ""));
            }
        }
    }
}
