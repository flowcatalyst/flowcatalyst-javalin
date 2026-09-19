package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionHost;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionStatus;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// Builds the `/control/functions/desired-state` document (spec
/// `function-api.md` §6.1). NOT a use case — reads never go through
/// `Operation` (`CONVENTIONS.md` §2: "reads do not go through use cases").
/// Deterministic byte output for one database state IS the point (spec §8
/// P14): [#build] always sorts `functions` and `unload`; nothing here
/// depends on row insertion order or `HashMap`/`HashSet` iteration order.
public final class DesiredState {

    private final FunctionRepository functions;
    private final FunctionVersionRepository versions;
    private final FunctionHostRepository hosts;

    public DesiredState(FunctionRepository functions, FunctionVersionRepository versions, FunctionHostRepository hosts) {
        this.functions = Objects.requireNonNull(functions, "functions");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.hosts = Objects.requireNonNull(hosts, "hosts");
    }

    /// One consistent read: every `ACTIVE` function's `live` + `candidate`
    /// version whose OWN manifest names `pool` (spec §6.1), plus every
    /// `(address, version)` a live host of `pool` still reports that is not
    /// among them (`unload`). `now` drives [FunctionHost#LIVE_WINDOW] —
    /// never `Instant.now()` here, so a test can move it without sleeping.
    public Document build(DnsLabel pool, Instant now) {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(now, "now");

        List<Function> active = functions.list(new FunctionRepository.ListFilter(null, null, FunctionStatus.ACTIVE));

        List<String> liveIds = active.stream().map(Function::liveVersionId).flatMap(Optional::stream).toList();
        Map<String, FunctionVersion> liveVersions = versions.findByIds(liveIds);
        Map<String, FunctionVersion> candidates =
                versions.newestPublishedByFunctions(active.stream().map(Function::id).toList());

        List<FunctionEntry> entries = new ArrayList<>();
        for (Function f : active) {
            FunctionVersion live = f.liveVersionId().map(liveVersions::get).orElse(null);
            if (live != null && live.manifest().pool().equals(pool)) {
                entries.add(FunctionEntry.of(f, live, "live"));
            }
            FunctionVersion candidate = candidates.get(f.id());
            if (candidate != null
                    && (live == null || candidate.version() > live.version())
                    && candidate.manifest().pool().equals(pool)) {
                entries.add(FunctionEntry.of(f, candidate, "candidate"));
            }
        }
        entries.sort(Comparator.comparing(FunctionEntry::address).thenComparingInt(FunctionEntry::version));

        Set<UnloadEntry> desired = new LinkedHashSet<>();
        for (FunctionEntry e : entries) {
            desired.add(new UnloadEntry(e.address(), e.version()));
        }

        Set<UnloadEntry> reported = new LinkedHashSet<>();
        for (FunctionHost h : hosts.listLive(pool, now.minus(FunctionHost.LIVE_WINDOW))) {
            for (FunctionHost.LoadedVersion lv : h.loaded()) {
                UnloadEntry key = new UnloadEntry(lv.address().render(), lv.version());
                if (!desired.contains(key)) {
                    reported.add(key);
                }
            }
        }
        List<UnloadEntry> unload = reported.stream()
                .sorted(Comparator.comparing(UnloadEntry::address).thenComparingInt(UnloadEntry::version))
                .toList();

        return new Document(pool.value(), List.copyOf(entries), unload);
    }

    // ── The wire document (spec §6.1) ────────────────────────────────────

    public record Document(String pool, List<FunctionEntry> functions, List<UnloadEntry> unload) {
        public Document {
            Objects.requireNonNull(pool, "pool");
            functions = List.copyOf(functions);
            unload = List.copyOf(unload);
        }
    }

    /// `role` is `"live"` or `"candidate"`; `mode` is `"warm"` when the
    /// manifest says so, else `"lazy"` — a candidate is ALWAYS `"lazy"`
    /// regardless of its own manifest's `warm` (spec §6.1: "a host fetches
    /// and verifies a candidate and reports it REGISTERED, never serves it").
    public record FunctionEntry(String address, String functionId, String versionId, int version, String role,
                                String mode, String digest, String artifactRef, String signatureBundle,
                                JsonNode manifest) {

        static FunctionEntry of(Function f, FunctionVersion v, String role) {
            String mode = "candidate".equals(role) ? "lazy" : (v.manifest().warm() ? "warm" : "lazy");
            return new FunctionEntry(f.address().render(), f.id(), v.id(), v.version(), role, mode,
                    v.digest().value(), v.artifactRef(), v.signatureBundle(), v.manifest().toJson());
        }
    }

    public record UnloadEntry(String address, int version) {
    }
}
