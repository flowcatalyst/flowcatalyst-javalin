package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.CorruptFunctionVersionException;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.EndpointAuth;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionHost;
import io.flowcatalyst.platform.function.FunctionHostRepository;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRoute;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionStatus;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.FunctionVersionRepository.CorruptVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository.VersionBatch;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.SignerIdentity;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/// Builds the `/control/functions/desired-state` document (spec
/// `function-api.md` §6.1). NOT a use case — reads never go through
/// `Operation` (`CONVENTIONS.md` §2: "reads do not go through use cases").
/// Deterministic byte output for one database state IS the point (spec §8
/// P14): [#build] always sorts `functions` and `unload`; nothing here
/// depends on row insertion order or `HashMap`/`HashSet` iteration order.
public final class DesiredState {

    private static final Logger LOG = LoggerFactory.getLogger(DesiredState.class);

    private final FunctionRepository functions;
    private final FunctionVersionRepository versions;
    private final FunctionHostRepository hosts;
    private final ServiceAccountRepository serviceAccounts;
    private final FunctionSettingsRepository settings;
    private final FunctionRouteRepository routes;

    /// `serviceAccounts` feeds [OutboundCredentials#resolve] — the SAME
    /// resolver chain the dispatch processor uses (spec §6, R9): "the
    /// application's oldest active service account's signing secret".
    /// `settings` feeds [#configAndSecretsFor] (`function-context.md` §1).
    /// `routes` feeds the top-level `publicRoutes` (spec
    /// `function-public-routes.md` §2).
    public DesiredState(FunctionRepository functions, FunctionVersionRepository versions, FunctionHostRepository hosts,
            ServiceAccountRepository serviceAccounts, FunctionSettingsRepository settings, FunctionRouteRepository routes) {
        this.functions = Objects.requireNonNull(functions, "functions");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.hosts = Objects.requireNonNull(hosts, "hosts");
        this.serviceAccounts = Objects.requireNonNull(serviceAccounts, "serviceAccounts");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.routes = Objects.requireNonNull(routes, "routes");
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
        VersionBatch liveLookup = versions.findByIds(liveIds);
        Map<String, FunctionVersion> liveVersions = liveLookup.versions();
        VersionBatch candidateLookup = versions.newestPublishedByFunctions(active.stream().map(Function::id).toList());
        Map<String, FunctionVersion> candidates = candidateLookup.versions();
        reportAndGuardCorrupt(liveLookup.corrupt(), candidateLookup.corrupt(), pool);

        // Spec §6, R9: the application's webhook signing secret, resolved at most
        // once per application per call — several functions of one application
        // share it, and this read runs on every control-plane poll.
        Map<String, Optional<String>> secretByApplication = new HashMap<>();

        List<FunctionEntry> entries = new ArrayList<>();
        // spec `function-public-routes.md` §2: publicRoutes is per the LIVE version's
        // pool only — a candidate is never served, so it never contributes a route.
        List<Function> liveInPool = new ArrayList<>();
        for (Function f : active) {
            FunctionVersion live = f.liveVersionId().map(liveVersions::get).orElse(null);
            if (live != null && live.manifest().pool().equals(pool)) {
                entries.add(FunctionEntry.of(f, live, "live", signingSecretFor(f, live, secretByApplication),
                        configAndSecretsFor(f, live)));
                liveInPool.add(f);
            }
            FunctionVersion candidate = candidates.get(f.id());
            if (candidate != null
                    && (live == null || candidate.version() > live.version())
                    && candidate.manifest().pool().equals(pool)) {
                entries.add(FunctionEntry.of(f, candidate, "candidate",
                        signingSecretFor(f, candidate, secretByApplication), configAndSecretsFor(f, candidate)));
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

        List<PublicRouteEntry> publicRoutes = publicRoutesFor(liveInPool);

        return new Document(pool.value(), List.copyOf(entries), unload, publicRoutes);
    }

    /// Logs ONE ERROR per build for every corrupt version this call touched (live or
    /// candidate, deduplicated by version id — structured fields only, never manifest
    /// content), then decides whether the build itself must fail.
    ///
    /// A corrupt CANDIDATE is safe to just skip: `function-host-reconciler.md` §1.2 step
    /// 3 — "a candidate is never loaded and never routed" — so a candidate silently
    /// missing from the document unloads nothing.
    ///
    /// A corrupt LIVE version is NOT safe to just skip. §1.2 step 4 unloads "everything
    /// loaded or in `lazyRoutes` whose address is no longer a `live` entry" — if the
    /// function's address is simply left out of `functions` because its live version
    /// can't be read, every host that already has it loaded unloads it on its next
    /// reconcile. That is worse than serving the stale-but-working version (§1.2 step 1:
    /// "a platform outage must never unload a function" — that is exactly the guarantee
    /// we'd be breaking). The smallest safe alternative: fail this build with the house
    /// corrupt-row error (500) so hosts of the affected pool treat it as a
    /// `ControlPlaneException` and keep serving what they have (§1.2 step 1), while
    /// OTHER pools' builds are unaffected. Since the version's own manifest can't be
    /// read, its `pool` is only a best-effort peek ([Manifest#peekStoredPool] — the ONE
    /// field that never depends on `runtime`/`entrypoint`); when even that peek fails
    /// (manifest not valid JSON at all), we cannot rule THIS pool out, so we fail safe.
    private void reportAndGuardCorrupt(List<CorruptVersion> live, List<CorruptVersion> candidates, DnsLabel pool) {
        Map<String, CorruptVersion> distinct = new LinkedHashMap<>();
        for (CorruptVersion c : live) {
            distinct.putIfAbsent(c.versionId(), c);
        }
        for (CorruptVersion c : candidates) {
            distinct.putIfAbsent(c.versionId(), c);
        }
        for (CorruptVersion c : distinct.values()) {
            LOG.atError().setMessage("fn_versions row has an unreadable manifest; excluded from the desired-state document")
                    .addKeyValue("functionId", c.functionId())
                    .addKeyValue("versionId", c.versionId())
                    .log();
        }
        for (CorruptVersion c : live) {
            if (c.pool() == null || c.pool().equals(pool)) {
                throw new CorruptFunctionVersionException(c.versionId(), c.cause());
            }
        }
    }

    /// spec §2: one entry per `(hostname, pathPrefix)` of every function
    /// whose LIVE version is in the requested pool — batch-read (one query,
    /// like every other desired-state read), sorted `(hostname, pathPrefix)`
    /// for deterministic bytes (spec §8 P14).
    private List<PublicRouteEntry> publicRoutesFor(List<Function> liveInPool) {
        Map<String, List<FunctionRoute>> byFunction =
                routes.listByFunctions(liveInPool.stream().map(Function::id).toList());
        List<PublicRouteEntry> out = new ArrayList<>();
        for (Function f : liveInPool) {
            for (FunctionRoute r : byFunction.getOrDefault(f.id(), List.of())) {
                out.add(new PublicRouteEntry(r.hostname().value(), r.pathPrefix().value(), f.address().render()));
            }
        }
        return out.stream()
                .sorted(Comparator.comparing(PublicRouteEntry::hostname).thenComparing(PublicRouteEntry::pathPrefix))
                .toList();
    }

    /// Whether `f`'s live version, or its newest PUBLISHED candidate, IS
    /// `v` in `pool` — the exact same selection [#build] itself computes for
    /// `functions` (live: its own manifest's pool matches; candidate: newer
    /// than live, its own manifest's pool matches), reused rather than
    /// re-derived so `FunctionControlApi`'s `/control/functions/events`
    /// route (spec `function-context.md` §3 check 2: "a host can speak only
    /// for what it runs") can never drift from what a host's own
    /// desired-state document actually told it.
    public boolean serves(Function f, FunctionVersion v, DnsLabel pool) {
        Objects.requireNonNull(f, "f");
        Objects.requireNonNull(v, "v");
        Objects.requireNonNull(pool, "pool");
        if (f.status() != FunctionStatus.ACTIVE) {
            return false;
        }
        // A corrupt live/candidate version is simply absent here too (never thrown) —
        // `serves` then falls through to "no match", which safely DENIES the host
        // rather than confirming reach for a version nobody can verify.
        FunctionVersion live =
                f.liveVersionId().map(id -> versions.findByIds(List.of(id)).versions().get(id)).orElse(null);
        if (live != null && live.id().equals(v.id()) && live.manifest().pool().equals(pool)) {
            return true;
        }
        FunctionVersion candidate = versions.newestPublishedByFunctions(List.of(f.id())).versions().get(f.id());
        return candidate != null && candidate.id().equals(v.id())
                && (live == null || candidate.version() > live.version())
                && candidate.manifest().pool().equals(pool);
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

    /// Spec §1: an entry's `config`/`secrets` are restricted to the keys
    /// `v`'s OWN manifest declares — a host never receives a value the
    /// version did not ask for — and `missingSettings` names every declared
    /// key (from all three sources: `config`, `secrets`, `db[].secretRef` —
    /// a `db` connection's DSN is itself a secret, named by `secretRef`, so
    /// it travels in the SAME `secrets` map [FunctionContext]'s `dataSource`
    /// reads from) that has no value. Sorted keys both ways
    /// ([FunctionSettingsRepository#configMap]/[FunctionSettingsRepository#decryptSecrets]
    /// already return a `TreeMap`) so the document's bytes are deterministic
    /// (spec §8 P14) — a settings change always moves the ETag.
    private Settings configAndSecretsFor(Function f, FunctionVersion v) {
        Manifest manifest = v.manifest();
        Map<String, String> allConfig = settings.configMap(f.id());
        Map<String, String> config = new TreeMap<>();
        for (String key : manifest.config()) {
            if (allConfig.containsKey(key)) {
                config.put(key, allConfig.get(key));
            }
        }

        Set<String> declaredSecretKeys = new LinkedHashSet<>(manifest.secrets());
        for (Manifest.DbRef ref : manifest.db()) {
            declaredSecretKeys.add(ref.secretRef());
        }
        Map<String, String> secrets = settings.decryptSecrets(f.id(), declaredSecretKeys);

        List<String> missing = new ArrayList<>();
        for (String key : manifest.config()) {
            if (!config.containsKey(key)) {
                missing.add(key);
            }
        }
        for (String key : declaredSecretKeys) {
            if (!secrets.containsKey(key)) {
                missing.add(key);
            }
        }
        return new Settings(config, secrets, List.copyOf(missing));
    }

    /// The three settings fields one [FunctionEntry] carries (spec §1);
    /// private — [#configAndSecretsFor]'s own return shape, unpacked into
    /// [FunctionEntry.of]'s parameters.
    private record Settings(Map<String, String> config, Map<String, String> secrets, List<String> missingSettings) {
    }

    // ── The wire document (spec §6.1) ────────────────────────────────────

    public record Document(String pool, List<FunctionEntry> functions, List<UnloadEntry> unload,
                           List<PublicRouteEntry> publicRoutes) {
        public Document {
            Objects.requireNonNull(pool, "pool");
            functions = List.copyOf(functions);
            unload = List.copyOf(unload);
            publicRoutes = List.copyOf(publicRoutes);
        }
    }

    /// One top-level `publicRoutes` entry (spec §2): `{hostname, pathPrefix,
    /// address}` — the same shape `FunctionDomainApi`'s `GET
    /// /api/function-routes` returns for a route.
    public record PublicRouteEntry(String hostname, String pathPrefix, String address) {
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
    /// `applicationId`/`clientId` (spec `function-host-listener.md` §1) are
    /// the function's owning application and client — the host needs them
    /// to decide *reach* for a versioned call (`function-invocation.md` §4).
    /// `applicationId` is ALWAYS carried — a platform-owned function still
    /// belongs to an application; only `clientId` is omitted for one
    /// ([FunctionOwner.Platform]), since a platform function's reach check
    /// is "anchor, full stop" and `clientId` is never consulted for it.
    public record FunctionEntry(String address, String functionId, String versionId, int version, String role,
                                String mode, String digest, String artifactRef, String signatureBundle,
                                JsonNode manifest, SignerView signer, String webhookSigningSecret,
                                String applicationId, String clientId, Map<String, String> config,
                                Map<String, String> secrets, List<String> missingSettings) {

        static FunctionEntry of(Function f, FunctionVersion v, String role, String webhookSigningSecret,
                Settings settings) {
            String mode = "candidate".equals(role) ? "lazy" : (v.manifest().warm() ? "warm" : "lazy");
            boolean platformOwned = f.owner() instanceof FunctionOwner.Platform;
            // applicationId is always carried — a platform-owned function still belongs to an
            // application; only clientId is omitted for one (anchor-only reach, R1 §1).
            String applicationId = f.applicationId();
            String clientId = platformOwned ? null : f.owner().clientIdOrNull();
            return new FunctionEntry(f.address().render(), f.id(), v.id(), v.version(), role, mode,
                    v.digest().value(), v.artifactRef(), v.signatureBundle(), v.manifest().toJson(),
                    SignerView.from(v.signer()), webhookSigningSecret, applicationId, clientId,
                    settings.config(), settings.secrets(), settings.missingSettings());
        }

        /// Masks the signing secret AND `secrets` (spec §6: "the host never
        /// logs it"; `function-context.md` §1: "same masking discipline as
        /// `webhookSigningSecret`" — `config` is not secret and prints
        /// plainly; the document itself is never logged either —
        /// `CONVENTIONS.md` §8's "a carrier of key material masks
        /// `toString`"). [Document]'s own (implicit) `toString` delegates to
        /// this one for every entry in its `functions` list, so masking here
        /// is the one place this needs doing.
        @Override
        public String toString() {
            return "FunctionEntry[address=" + address + ", functionId=" + functionId + ", versionId=" + versionId
                    + ", version=" + version + ", role=" + role + ", mode=" + mode + ", digest=" + digest
                    + ", artifactRef=" + artifactRef
                    + ", signatureBundle=" + (signatureBundle == null ? "null" : signatureBundle.length() + " chars")
                    + ", manifest=" + manifest + ", signer=" + signer
                    + ", webhookSigningSecret=" + (webhookSigningSecret == null ? "null" : "<redacted>")
                    + ", applicationId=" + applicationId + ", clientId=" + clientId + ", config=" + config
                    + ", secrets=" + (secrets.isEmpty() ? "{}" : secrets.keySet() + " (values redacted)")
                    + ", missingSettings=" + missingSettings + "]";
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
