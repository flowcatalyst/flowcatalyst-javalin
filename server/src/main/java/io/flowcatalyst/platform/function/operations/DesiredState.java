package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.EndpointAuth;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionHost;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionStatus;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
    private final ServiceAccountRepository serviceAccounts;

    /// `serviceAccounts` feeds [OutboundCredentials#resolve] — the SAME
    /// resolver chain the dispatch processor uses (spec §6, R9): "the
    /// application's oldest active service account's signing secret".
    public DesiredState(FunctionRepository functions, FunctionVersionRepository versions, FunctionHostRepository hosts,
            ServiceAccountRepository serviceAccounts) {
        this.functions = Objects.requireNonNull(functions, "functions");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.hosts = Objects.requireNonNull(hosts, "hosts");
        this.serviceAccounts = Objects.requireNonNull(serviceAccounts, "serviceAccounts");
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

        // Spec §6, R9: the application's webhook signing secret, resolved at most
        // once per application per call — several functions of one application
        // share it, and this read runs on every control-plane poll.
        Map<String, Optional<String>> secretByApplication = new HashMap<>();

        List<FunctionEntry> entries = new ArrayList<>();
        for (Function f : active) {
            FunctionVersion live = f.liveVersionId().map(liveVersions::get).orElse(null);
            if (live != null && live.manifest().pool().equals(pool)) {
                entries.add(FunctionEntry.of(f, live, "live", signingSecretFor(f, live, secretByApplication)));
            }
            FunctionVersion candidate = candidates.get(f.id());
            if (candidate != null
                    && (live == null || candidate.version() > live.version())
                    && candidate.manifest().pool().equals(pool)) {
                entries.add(FunctionEntry.of(f, candidate, "candidate", signingSecretFor(f, candidate, secretByApplication)));
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

    /// Spec §6, R9: only for a version whose manifest has at least one
    /// `webhook` endpoint (§4.1's `webhook` — the one auth mode the host
    /// must verify a signature for), and only when the application actually
    /// has one (an absent/blank secret is omitted, never sent as `""`).
    private String signingSecretFor(Function f, FunctionVersion v, Map<String, Optional<String>> cache) {
        if (!hasWebhookEndpoint(v.manifest())) {
            return null;
        }
        // Memoised per application for this one call only (not the process-wide TTL cache the
        // dispatch processor shares) — this read is once per control-plane poll, not per delivery.
        Optional<String> secret = cache.computeIfAbsent(f.applicationId(),
                id -> OutboundCredentials.resolve(serviceAccounts, id).map(OutboundCredentials::signingSecret));
        return secret.filter(s -> s != null && !s.isBlank()).orElse(null);
    }

    private static boolean hasWebhookEndpoint(Manifest manifest) {
        return manifest.endpoints().stream().anyMatch(e -> e.auth() == EndpointAuth.WEBHOOK);
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
    /// `signer` (spec §0, R13) is the identity recorded at publish — omitted
    /// (never `null` on the wire, `Json`'s `NON_ABSENT` default) when the
    /// version was published with signatures off. `webhookSigningSecret`
    /// (spec `function-invocation.md` §6, R9) is present only for a version
    /// whose manifest has a `webhook` endpoint AND whose application has an
    /// active service account with a secret — it changes the document's
    /// bytes on purpose, so a rotation changes the `ETag` (spec §10 V7).
    public record FunctionEntry(String address, String functionId, String versionId, int version, String role,
                                String mode, String digest, String artifactRef, String signatureBundle,
                                JsonNode manifest, SignerView signer, String webhookSigningSecret) {

        static FunctionEntry of(Function f, FunctionVersion v, String role, String webhookSigningSecret) {
            String mode = "candidate".equals(role) ? "lazy" : (v.manifest().warm() ? "warm" : "lazy");
            return new FunctionEntry(f.address().render(), f.id(), v.id(), v.version(), role, mode,
                    v.digest().value(), v.artifactRef(), v.signatureBundle(), v.manifest().toJson(),
                    SignerView.from(v.signer()), webhookSigningSecret);
        }

        /// Masks the signing secret (spec §6: "the host never logs it"; the
        /// document itself is never logged either — `CONVENTIONS.md` §8's "a
        /// carrier of key material masks `toString`"). [Document]'s own
        /// (implicit) `toString` delegates to this one for every entry in its
        /// `functions` list, so masking here is the one place this needs doing.
        @Override
        public String toString() {
            return "FunctionEntry[address=" + address + ", functionId=" + functionId + ", versionId=" + versionId
                    + ", version=" + version + ", role=" + role + ", mode=" + mode + ", digest=" + digest
                    + ", artifactRef=" + artifactRef
                    + ", signatureBundle=" + (signatureBundle == null ? "null" : signatureBundle.length() + " chars")
                    + ", manifest=" + manifest + ", signer=" + signer
                    + ", webhookSigningSecret=" + (webhookSigningSecret == null ? "null" : "<redacted>") + "]";
        }
    }

    /// The wire shape of a recorded signer (spec §0): `{issuer, subject}`.
    public record SignerView(String issuer, String subject) {
        static SignerView from(SignerIdentity signer) {
            return signer == null ? null : new SignerView(signer.issuer(), signer.subject());
        }
    }

    public record UnloadEntry(String address, int version) {
    }
}
