package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.dispatchpool.operations.DispatchPoolEvents;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.eventtype.EventTypeStatus;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionDomain;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRoute;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.FunctionStatus;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.PoolUrlTemplate;
import io.flowcatalyst.platform.function.TriggerObject;
import io.flowcatalyst.platform.function.TriggerObjectKind;
import io.flowcatalyst.platform.function.TriggerObjectRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import org.jooq.exception.DataAccessException;
import io.flowcatalyst.platform.scheduledjob.ScheduledJob;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobCode;
import io.flowcatalyst.platform.scheduledjob.ScheduledJobRepository;
import io.flowcatalyst.platform.scheduledjob.cron.CronExpression;
import io.flowcatalyst.platform.scheduledjob.operations.ScheduledJobEvents;
import io.flowcatalyst.platform.serviceaccount.OutboundCredentials;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.subscription.EventTypeBinding;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.platform.subscription.operations.SubscriptionEvents;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/// The real [TriggerSync]: publish validation plus the promote/delete/
/// status-change reconciliation (spec `function-invocation.md` §4).
///
/// Every write goes through the owning aggregate's OWN transition and
/// event via [TxScopedUnitOfWork#commit] / [TxScopedUnitOfWork#commitDelete]
/// — a function's subscription is a real subscription with a real audit
/// trail, never a bespoke `fn_` write path. `fn_trigger_objects` is
/// maintained alongside every such write through [TriggerObjectRepository#link]
/// / [#unlink], on the SAME open transaction ([TxScopedUnitOfWork#dbTx]).
///
/// Command records with the same simple name exist in the subscription,
/// dispatch-pool and scheduled-job `operations` packages (`CreateCommand`,
/// `UpdateCommand`, `DeleteCommand`, `PauseCommand`, `ResumeCommand` — each
/// is that aggregate's own audit `operation` name, `CONVENTIONS.md` §2) —
/// they collide pairwise, so every reference here is fully qualified rather
/// than imported.
public final class FunctionTriggerSync implements TriggerSync {

    private static final String KEY_PREFIX = "fn-";

    private static final String PUBLIC_ROUTE_UNIQUE_CONSTRAINT = "fn_routes_hostname_path_prefix_key";

    private final SubscriptionRepository subscriptions;
    private final DispatchPoolRepository pools;
    private final ScheduledJobRepository jobs;
    private final EventTypeRepository eventTypes;
    private final TriggerObjectRepository triggerObjects;
    private final ApplicationRepository applications;
    private final ServiceAccountRepository serviceAccounts;
    private final FunctionVersionRepository functionVersions;
    private final FunctionLimits functionLimits;
    private final PoolUrlTemplate poolUrlTemplate;
    private final FunctionDomainRepository domains;
    private final FunctionRouteRepository routes;
    private final FunctionRepository functions;
    private final java.util.function.Function<String, String> hasher;

    public FunctionTriggerSync(SubscriptionRepository subscriptions, DispatchPoolRepository pools,
            ScheduledJobRepository jobs, EventTypeRepository eventTypes, TriggerObjectRepository triggerObjects,
            ApplicationRepository applications, ServiceAccountRepository serviceAccounts,
            FunctionVersionRepository functionVersions, FunctionLimits functionLimits, PoolUrlTemplate poolUrlTemplate,
            FunctionDomainRepository domains, FunctionRouteRepository routes, FunctionRepository functions) {
        this(subscriptions, pools, jobs, eventTypes, triggerObjects, applications, serviceAccounts, functionVersions,
                functionLimits, poolUrlTemplate, domains, routes, functions, FunctionTriggerSync::hash8);
    }

    /// Test seam (review fix): a forced `hasher` lets a test simulate a
    /// genuine 32-bit hash collision between two different manifest entries
    /// (spec §4: "two entries of one function whose keys collide ... an
    /// internal error at promote") without needing to find a real SHA-256
    /// collision.
    FunctionTriggerSync(SubscriptionRepository subscriptions, DispatchPoolRepository pools,
            ScheduledJobRepository jobs, EventTypeRepository eventTypes, TriggerObjectRepository triggerObjects,
            ApplicationRepository applications, ServiceAccountRepository serviceAccounts,
            FunctionVersionRepository functionVersions, FunctionLimits functionLimits, PoolUrlTemplate poolUrlTemplate,
            FunctionDomainRepository domains, FunctionRouteRepository routes, FunctionRepository functions,
            java.util.function.Function<String, String> hasher) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.pools = Objects.requireNonNull(pools, "pools");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.eventTypes = Objects.requireNonNull(eventTypes, "eventTypes");
        this.triggerObjects = Objects.requireNonNull(triggerObjects, "triggerObjects");
        this.applications = Objects.requireNonNull(applications, "applications");
        this.serviceAccounts = Objects.requireNonNull(serviceAccounts, "serviceAccounts");
        this.functionVersions = Objects.requireNonNull(functionVersions, "functionVersions");
        this.functionLimits = Objects.requireNonNull(functionLimits, "functionLimits");
        this.poolUrlTemplate = Objects.requireNonNull(poolUrlTemplate, "poolUrlTemplate");
        this.domains = Objects.requireNonNull(domains, "domains");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.functions = Objects.requireNonNull(functions, "functions");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    // ── onPublish — VALIDATE ONLY (spec §4 first paragraph) ─────────────────

    @Override
    public void onPublish(TxScopedUnitOfWork scoped, Function function, FunctionVersion version) {
        Manifest manifest = version.manifest();

        for (Manifest.SubscriptionSpec spec : manifest.subscriptions()) {
            EventType et = eventTypes.findByCode(spec.eventType())
                    .orElseThrow(() -> UseCaseException.validation("EVENT_TYPE_NOT_FOUND",
                            "event type '" + spec.eventType() + "' not found"));
            if (et.status() == EventTypeStatus.ARCHIVED) {
                throw UseCaseException.validation("EVENT_TYPE_NOT_FOUND",
                        "event type '" + spec.eventType() + "' is archived");
            }
        }

        for (Manifest.ScheduleSpec spec : manifest.schedules()) {
            try {
                CronExpression.parse(spec.cron());
            } catch (UseCaseException e) {
                throw UseCaseException.validation("CRON_INVALID",
                        "cron expression '" + spec.cron() + "' invalid: " + e.error().message());
            }
            if (spec.timezone() != null) {
                try {
                    ZoneId.of(spec.timezone());
                } catch (DateTimeException e) {
                    throw UseCaseException.validation("TIMEZONE_INVALID",
                            "timezone '" + spec.timezone() + "' is not a recognised IANA zone");
                }
            }
        }

        if (!manifest.subscriptions().isEmpty() || !manifest.schedules().isEmpty()) {
            requireApplicationSigningSecret(function.applicationId());
        }

        if (manifest.warm()) {
            int liveWarmInPool = functionVersions.countLiveWarmInPool(manifest.pool(), function.id());
            if (liveWarmInPool + 1 > functionLimits.maxWarmPerHost()) {
                throw UseCaseException.validation("WARM_CAPACITY_EXCEEDED",
                        "pool '" + manifest.pool().value() + "' is at its warm-function limit ("
                                + functionLimits.maxWarmPerHost() + ")");
            }
        }

        // spec `function-public-routes.md` §1, §2: validated at publish, materialised at
        // promote — nothing is written here, same as every other onPublish check.
        validatePublicRoutes(function, manifest);
    }

    /// Spec `function-public-routes.md` §2: every `public[]` entry's hostname
    /// must be a `VERIFIED` domain OF THIS FUNCTION'S OWNER — checked as
    /// three independent conditions (unclaimed / pending / another owner's),
    /// each throwing the SAME `PUBLIC_HOSTNAME_NOT_VERIFIED` (spec §6 M2:
    /// "same code for all three ... no oracle") — and `(hostname,
    /// pathPrefix)` must not already be routed to ANOTHER function
    /// (`PUBLIC_ROUTE_TAKEN`, naming it only when the caller can reach it,
    /// spec §6 M5). Checked against `fn_routes` as it stands right now — the
    /// only rows there are what an earlier PROMOTE materialised, so two
    /// functions can both pass this at publish when neither has promoted yet
    /// (spec §6 M4's race); [#reconcilePublicRoutes] re-checks at promote.
    private void validatePublicRoutes(Function function, Manifest manifest) {
        for (Manifest.PublicRoute route : manifest.publicRoutes()) {
            requireVerifiedOwnedDomain(function, route.hostname());
            FunctionRoute existing = routes.findPublic(route.hostname(), route.pathPrefix()).orElse(null);
            if (existing != null && !existing.functionId().equals(function.id())) {
                throw publicRouteTaken(existing, route.hostname(), route.pathPrefix());
            }
        }
    }

    /// The three independent clauses of `PUBLIC_HOSTNAME_NOT_VERIFIED`
    /// (spec §6 M2) — deliberately three separate `if`s, not one boolean
    /// expression, so a mutant dropping any single clause is caught by its
    /// own dedicated test rather than being masked by the others.
    private void requireVerifiedOwnedDomain(Function function, Hostname hostname) {
        FunctionDomain domain = domains.findByHostname(hostname).orElse(null);
        if (domain == null) {
            throw publicHostnameNotVerified(hostname);
        }
        if (!(domain.verification() instanceof FunctionDomain.Verification.Verified)) {
            throw publicHostnameNotVerified(hostname);
        }
        if (!domain.owner().equals(function.owner())) {
            throw publicHostnameNotVerified(hostname);
        }
    }

    private static UseCaseException publicHostnameNotVerified(Hostname hostname) {
        return UseCaseException.validation("PUBLIC_HOSTNAME_NOT_VERIFIED",
                "hostname '" + hostname.value() + "' is not a verified domain of this function's owner");
    }

    /// Spec §6 M5: names the other function's address only when the CURRENT
    /// caller (`Auth.current()`) can reach it — [Access#canReach] is the
    /// exact same predicate the read/write routes share, so this can never
    /// disagree with what `GET /api/functions/{address}` would itself reveal.
    private UseCaseException publicRouteTaken(FunctionRoute existing, Hostname hostname,
            io.flowcatalyst.platform.function.RoutePattern pathPrefix) {
        String routeText = hostname.value() + pathPrefix.value();
        Function other = functions.findById(existing.functionId()).orElse(null);
        if (other != null && Access.canReach(Auth.current(), other)) {
            return UseCaseException.conflict("PUBLIC_ROUTE_TAKEN",
                    "route '" + routeText + "' is already taken by function '" + other.address().render() + "'");
        }
        return UseCaseException.conflict("PUBLIC_ROUTE_TAKEN", "route '" + routeText + "' is already taken");
    }

    /// Spec §4, §6: the same resolver chain delivery signing uses — the
    /// application's oldest ACTIVE service account must carry a signing
    /// secret, or every webhook delivery to this function would be refused
    /// by the host before it ever reaches the manifest's own auth check.
    private void requireApplicationSigningSecret(String applicationId) {
        boolean hasSecret = OutboundCredentials.resolve(serviceAccounts, applicationId)
                .map(OutboundCredentials::signingSecret)
                .filter(s -> s != null && !s.isEmpty())
                .isPresent();
        if (!hasSecret) {
            throw UseCaseException.validation("APPLICATION_SIGNING_SECRET_REQUIRED",
                    "the function's application needs an active service account with a signing secret before it can "
                            + "declare subscriptions or schedules");
        }
    }

    // ── onPromote — RECONCILE (spec §4) ──────────────────────────────────────

    @Override
    public void onPromote(TxScopedUnitOfWork scoped, Function function, FunctionVersion newLive,
            FunctionVersion previousLive, ExecutionContext ec) {
        Manifest manifest = newLive.manifest();
        Instant now = Instant.now();
        String fid = fid(function.id());
        String applicationCode = applicationCodeOf(function);

        List<TriggerObject> linked = triggerObjects.listByFunction(function.id());
        Map<String, TriggerObject> linkedPools = byKind(linked, TriggerObjectKind.POOL);
        Map<String, TriggerObject> linkedSubs = byKind(linked, TriggerObjectKind.SUBSCRIPTION);
        Map<String, TriggerObject> linkedJobs = byKind(linked, TriggerObjectKind.SCHEDULED_JOB);

        // Pool first (spec: "pool first, deletions last") — every function
        // has exactly one, R7, so this branch is create-or-update only.
        String poolKey = KEY_PREFIX + fid;
        DispatchPool dispatchPool =
                reconcilePool(scoped, ec, function, manifest, poolKey, linkedPools.get(poolKey), now);

        List<TriggerObject> toDelete = new ArrayList<>();
        reconcileSubscriptions(scoped, ec, function, manifest, applicationCode, dispatchPool, fid, linkedSubs, now, toDelete);
        reconcileScheduledJobs(scoped, ec, function, manifest, fid, linkedJobs, now, toDelete);

        // Deletions last, so a create/update never races a delete of the same kind.
        for (TriggerObject t : toDelete) {
            switch (t.kind()) {
                case SUBSCRIPTION -> deleteSubscription(scoped, ec, t);
                case SCHEDULED_JOB -> deleteScheduledJob(scoped, ec, t);
                case POOL -> throw new IllegalStateException("a function's pool is never reconciliation-deleted");
            }
        }

        // spec `function-public-routes.md` §2: fn_routes := this manifest's public[] set,
        // re-checking the same conflict onPublish checked (another function may have
        // promoted in between) — not a fn_trigger_objects-linked kind (a route is a
        // materialisation, not an aggregate with its own event, spec `function-registry.md`
        // §6.6), so it is reconciled directly rather than through the toDelete loop above.
        reconcilePublicRoutes(scoped, function, manifest);
    }

    /// Spec §2: re-checks the `PUBLIC_ROUTE_TAKEN` conflict (spec §6 M4: "the
    /// race: both published before either promoted" — the fn_routes rows may
    /// have changed since publish), then materialises `fn_routes` to exactly
    /// this manifest's `public[]` set via
    /// [FunctionRouteRepository#replaceForFunction] — skipped entirely when
    /// the desired set already matches (spec §2: "no difference ⇒ no
    /// write"). The unique constraint is the race backstop: two promotes of
    /// two different functions racing past the re-check both above would
    /// otherwise surface as an unhandled 500 from the second `INSERT`; caught
    /// here BY THE SPECIFIC CONSTRAINT NAME (never a bare `DataAccessException`,
    /// which would also swallow an unrelated failure, e.g. a dropped
    /// connection, as if it were this one conflict) and mapped to the SAME
    /// `409 PUBLIC_ROUTE_TAKEN` (spec §6 M4: "never a 500").
    private void reconcilePublicRoutes(TxScopedUnitOfWork scoped, Function function, Manifest manifest) {
        Instant now = Instant.now();
        for (Manifest.PublicRoute route : manifest.publicRoutes()) {
            FunctionRoute existing = routes.findPublic(route.hostname(), route.pathPrefix()).orElse(null);
            if (existing != null && !existing.functionId().equals(function.id())) {
                throw publicRouteTaken(existing, route.hostname(), route.pathPrefix());
            }
        }

        List<FunctionRoute> current = routes.listByFunction(function.id());
        if (sameRoutes(current, manifest.publicRoutes())) {
            return; // no difference: no write (spec §2)
        }

        List<FunctionRoute> desired = manifest.publicRoutes().stream()
                .map(r -> FunctionRoute.of(function.id(), r.hostname(), r.pathPrefix(), now))
                .toList();
        try {
            routes.replaceForFunction(function.id(), desired, scoped.dbTx());
        } catch (DataAccessException e) {
            if (isUniqueViolation(e, PUBLIC_ROUTE_UNIQUE_CONSTRAINT)) {
                throw UseCaseException.conflict("PUBLIC_ROUTE_TAKEN",
                        "route is already taken (detected by the database's own unique constraint)");
            }
            throw e;
        }
    }

    private static boolean sameRoutes(List<FunctionRoute> current, List<Manifest.PublicRoute> desired) {
        if (current.size() != desired.size()) {
            return false;
        }
        java.util.Set<String> currentKeys = new java.util.HashSet<>();
        for (FunctionRoute r : current) {
            currentKeys.add(r.hostname().value() + "|" + r.pathPrefix().value());
        }
        java.util.Set<String> desiredKeys = new java.util.HashSet<>();
        for (Manifest.PublicRoute r : desired) {
            desiredKeys.add(r.hostname().value() + "|" + r.pathPrefix().value());
        }
        return currentKeys.equals(desiredKeys);
    }

    /// Walks the cause chain for a PostgreSQL unique-violation naming
    /// EXACTLY `constraintName` — a `PSQLException`'s own `ServerErrorMessage#getConstraint()`,
    /// never a bare SQLSTATE check (which would match ANY unique violation on
    /// this table, or any other) and never a bare `DataAccessException` catch
    /// (spec §6 M4's own mutant: "let the constraint surface as 500" is what
    /// happens if this check is skipped and the exception simply rethrown).
    private static boolean isUniqueViolation(Throwable e, String constraintName) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof org.postgresql.util.PSQLException psql) {
                var serverError = psql.getServerErrorMessage();
                if (serverError != null && constraintName.equals(serverError.getConstraint())) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    private DispatchPool reconcilePool(TxScopedUnitOfWork scoped, ExecutionContext ec, Function function,
            Manifest manifest, String poolKey, TriggerObject linked, Instant now) {
        int desiredConcurrency = manifest.limits().maxConcurrency();
        String name = "function " + function.address().render();

        Optional<DispatchPool> existing = linked == null ? Optional.empty() : pools.findById(linked.objectId());
        DispatchPool pool;
        if (existing.isPresent()) {
            DispatchPool current = existing.get();
            if (current.concurrency() == desiredConcurrency) {
                return current; // no difference (spec §10 V6): no write, no event
            }
            pool = current.withConcurrency(desiredConcurrency);
            scoped.commit(pool, pools, DispatchPoolEvents.DispatchPoolUpdated.of(ec, pool),
                    new io.flowcatalyst.platform.dispatchpool.operations.UpdateCommand(
                            pool.id(), null, null, null, desiredConcurrency));
        } else {
            pool = DispatchPool.create(poolKey, name).withConcurrency(desiredConcurrency);
            scoped.commit(pool, pools, DispatchPoolEvents.DispatchPoolCreated.of(ec, pool),
                    new io.flowcatalyst.platform.dispatchpool.operations.CreateCommand(
                            poolKey, name, null, null, desiredConcurrency, null));
        }
        triggerObjects.link(TriggerObject.of(function.id(), TriggerObjectKind.POOL, pool.id(), poolKey, now),
                scoped.dbTx());
        return pool;
    }

    private void reconcileSubscriptions(TxScopedUnitOfWork scoped, ExecutionContext ec, Function function,
            Manifest manifest, String applicationCode, DispatchPool dispatchPool, String fid,
            Map<String, TriggerObject> linkedSubs, Instant now, List<TriggerObject> toDelete) {
        List<Manifest.SubscriptionSpec> specs = manifest.subscriptions();
        List<String> keys = new ArrayList<>(specs.size());
        for (Manifest.SubscriptionSpec spec : specs) {
            keys.add(KEY_PREFIX + fid + "-" + hasher.apply(spec.eventType()));
        }
        checkNoCollisions(keys, "subscriptions");

        Set<String> desiredKeys = new LinkedHashSet<>(keys);
        for (int i = 0; i < specs.size(); i++) {
            String key = keys.get(i);
            reconcileSubscription(scoped, ec, function, manifest, applicationCode, dispatchPool, key, specs.get(i),
                    linkedSubs.get(key), now);
        }
        for (Map.Entry<String, TriggerObject> e : linkedSubs.entrySet()) {
            if (!desiredKeys.contains(e.getKey())) {
                toDelete.add(e.getValue());
            }
        }
    }

    /// Spec §4: "two entries of one function whose keys collide ... an
    /// internal error at promote, never a silent overwrite of one link by
    /// the other" — checked before ANY of `keys`' entries is reconciled, so
    /// a collision leaves the whole promote's reconciliation unwritten (the
    /// surrounding transaction rolls everything else in this promote back
    /// with it, spec §10 "order and atomicity").
    private static void checkNoCollisions(List<String> keys, String what) {
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                throw UseCaseException.internal("TRIGGER_KEY_COLLISION",
                        "two " + what + " entries of this function's manifest hash to the same trigger key '" + key
                                + "'", null);
            }
        }
    }

    private void reconcileSubscription(TxScopedUnitOfWork scoped, ExecutionContext ec, Function function,
            Manifest manifest, String applicationCode, DispatchPool dispatchPool, String key,
            Manifest.SubscriptionSpec spec, TriggerObject linked, Instant now) {
        String endpoint = endpointFor(manifest, function, spec.path().value());
        String name = function.address().render() + ": " + spec.eventType();
        String clientId = function.owner().clientIdOrNull();
        // No manifest `filter` (spec §3): a subscription binding's filter has no column
        // anywhere in the platform (`SubscriptionRepository`) — always null, for every
        // subscription source, not just functions' (docs/backlog.md).
        EventTypeBinding binding = new EventTypeBinding(null, spec.eventType(), null, null);

        Optional<Subscription> existing = linked == null ? Optional.empty() : subscriptions.findById(linked.objectId());
        Subscription result;
        if (existing.isPresent()) {
            Subscription current = existing.get();
            if (sameSubscription(current, name, endpoint, applicationCode, clientId, dispatchPool, binding, spec)) {
                return; // no difference (spec §10 V3): no write, no event, updated_at unchanged
            }
            result = current.withName(name).withApplicationCode(applicationCode).withClientId(clientId)
                    .withEndpoint(endpoint).withDispatchPool(dispatchPool.id(), dispatchPool.code())
                    .withEventTypes(List.of(binding)).withMode(spec.mode()).withTimeoutSeconds(spec.timeoutSeconds())
                    .withMaxRetries(spec.maxRetries()).withDataOnly(spec.dataOnly());
            scoped.commit(result, subscriptions, SubscriptionEvents.SubscriptionUpdated.of(ec, result),
                    new io.flowcatalyst.platform.subscription.operations.UpdateCommand(result.id(), name, null,
                            endpoint, null, List.of(binding), null, spec.mode().name(), null, spec.timeoutSeconds(),
                            spec.maxRetries(), null, null, dispatchPool.id(), null, spec.dataOnly()));
        } else {
            result = Subscription.forFunction(key, name, endpoint, applicationCode, clientId, dispatchPool.id(),
                    dispatchPool.code(), binding, spec.mode(), spec.maxRetries(), spec.timeoutSeconds(),
                    spec.dataOnly());
            scoped.commit(result, subscriptions, SubscriptionEvents.SubscriptionCreated.of(ec, result),
                    new io.flowcatalyst.platform.subscription.operations.CreateCommand(key, name, endpoint, null,
                            clientId, null, dispatchPool.id(), null, List.of(binding), null, spec.mode().name(), null,
                            spec.timeoutSeconds(), spec.maxRetries(), null, null, spec.dataOnly()));
        }
        triggerObjects.link(
                TriggerObject.of(function.id(), TriggerObjectKind.SUBSCRIPTION, result.id(), key, now),
                scoped.dbTx());
    }

    /// Everything the manifest controls, EXCLUDING the binding's `filter`:
    /// [EventTypeBinding] has no `filter` column
    /// ([io.flowcatalyst.platform.subscription.SubscriptionRepository] — "a
    /// binding's filter has no column") — it always reads back `null`, so
    /// comparing it here would report "different" on every single promote
    /// forever for any entry that names one.
    private static boolean sameSubscription(Subscription current, String name, String endpoint,
            String applicationCode, String clientId, DispatchPool dispatchPool, EventTypeBinding binding,
            Manifest.SubscriptionSpec spec) {
        if (!current.name().equals(name)) return false;
        if (!current.endpoint().equals(endpoint)) return false;
        if (!Objects.equals(current.applicationCode(), applicationCode)) return false;
        if (!Objects.equals(current.clientId(), clientId)) return false;
        if (!Objects.equals(current.dispatchPoolId(), dispatchPool.id())) return false;
        if (!Objects.equals(current.dispatchPoolCode(), dispatchPool.code())) return false;
        if (current.mode() != spec.mode()) return false;
        if (current.maxRetries() != spec.maxRetries()) return false;
        if (current.timeoutSeconds() != spec.timeoutSeconds()) return false;
        if (current.dataOnly() != spec.dataOnly()) return false;
        List<EventTypeBinding> bindings = current.eventTypes();
        if (bindings.size() != 1) return false;
        return Objects.equals(bindings.get(0).eventTypeCode(), binding.eventTypeCode());
    }

    private void deleteSubscription(TxScopedUnitOfWork scoped, ExecutionContext ec, TriggerObject linked) {
        triggerObjects.unlink(linked.functionId(), linked.kind(), linked.triggerKey(), scoped.dbTx());
        subscriptions.findById(linked.objectId()).ifPresent(s -> scoped.commitDelete(s, subscriptions,
                SubscriptionEvents.SubscriptionDeleted.of(ec, s),
                new io.flowcatalyst.platform.subscription.operations.DeleteCommand(s.id())));
    }

    private void reconcileScheduledJobs(TxScopedUnitOfWork scoped, ExecutionContext ec, Function function,
            Manifest manifest, String fid, Map<String, TriggerObject> linkedJobs, Instant now,
            List<TriggerObject> toDelete) {
        List<Manifest.ScheduleSpec> specs = manifest.schedules();
        List<String> keys = new ArrayList<>(specs.size());
        for (Manifest.ScheduleSpec spec : specs) {
            keys.add(KEY_PREFIX + fid + "-" + hasher.apply(spec.cron() + "\0" + zoneOrEmpty(spec)));
        }
        checkNoCollisions(keys, "schedules");

        Set<String> desiredKeys = new LinkedHashSet<>(keys);
        for (int i = 0; i < specs.size(); i++) {
            String key = keys.get(i);
            reconcileScheduledJob(scoped, ec, function, manifest, key, specs.get(i), linkedJobs.get(key), now);
        }
        for (Map.Entry<String, TriggerObject> e : linkedJobs.entrySet()) {
            if (!desiredKeys.contains(e.getKey())) {
                toDelete.add(e.getValue());
            }
        }
    }

    private void reconcileScheduledJob(TxScopedUnitOfWork scoped, ExecutionContext ec, Function function,
            Manifest manifest, String key, Manifest.ScheduleSpec spec, TriggerObject linked, Instant now) {
        String targetUrl = endpointFor(manifest, function, spec.path().value());
        String name = function.address().render() + ": " + spec.cron();
        ScheduledJob.Definition definition = new ScheduledJob.Definition(name, null,
                List.of(CronExpression.parse(spec.cron())), spec.timezone(), spec.payload(), false, false, null,
                null, targetUrl);

        Optional<ScheduledJob> existing = linked == null ? Optional.empty() : jobs.findById(linked.objectId());
        ScheduledJob result;
        if (existing.isPresent()) {
            Optional<ScheduledJob> updated =
                    existing.get().reconcile(definition, function.applicationId(), ec.principalId());
            if (updated.isEmpty()) {
                return; // ScheduledJob#reconcile itself found no difference: no write, no event
            }
            result = updated.get();
            scoped.commit(result, jobs, ScheduledJobEvents.ScheduledJobUpdated.of(ec, result),
                    new io.flowcatalyst.platform.scheduledjob.operations.UpdateCommand(result.id(), name, null,
                            List.of(spec.cron()), definition.timezone(), definition.payload(), false, false, null,
                            null, targetUrl));
        } else {
            ScheduledJobCode code = ScheduledJobCode.parse(key);
            // The job's client scope is the function's owner (spec §4 table): null
            // for a platform-owned function, never the platform-wide default a bare
            // ScheduledJob.create leaves it at.
            String clientId = function.owner().clientIdOrNull();
            result = ScheduledJob.create(code, definition).withClientId(clientId)
                    .withApplicationId(function.applicationId()).withCreatedBy(ec.principalId());
            scoped.commit(result, jobs, ScheduledJobEvents.ScheduledJobCreated.of(ec, result),
                    new io.flowcatalyst.platform.scheduledjob.operations.CreateCommand(key, name,
                            List.of(spec.cron()), definition.timezone(), clientId, function.applicationId(), null,
                            definition.payload(), false, false, null, null, targetUrl));
        }
        triggerObjects.link(
                TriggerObject.of(function.id(), TriggerObjectKind.SCHEDULED_JOB, result.id(), key, now),
                scoped.dbTx());
    }

    private void deleteScheduledJob(TxScopedUnitOfWork scoped, ExecutionContext ec, TriggerObject linked) {
        triggerObjects.unlink(linked.functionId(), linked.kind(), linked.triggerKey(), scoped.dbTx());
        jobs.findById(linked.objectId()).ifPresent(j -> scoped.commitDelete(j, jobs,
                ScheduledJobEvents.ScheduledJobDeleted.of(ec, j),
                new io.flowcatalyst.platform.scheduledjob.operations.DeleteCommand(j.id())));
    }

    // ── onDelete (spec §4) ────────────────────────────────────────────────

    /// spec `function-public-routes.md` §2: this function's `fn_routes` rows
    /// need no code here at all — `fn_routes_function_id_fkey` is
    /// `ON DELETE CASCADE` (`V13__functions.sql`), so they are gone the
    /// instant `DeleteFunction` deletes the function row itself, in the same
    /// transaction. `FunctionTriggerSyncTest` asserts this directly rather
    /// than trusting the schema silently.
    @Override
    public void onDelete(TxScopedUnitOfWork scoped, Function function, ExecutionContext ec) {
        List<TriggerObject> linked = triggerObjects.listByFunction(function.id());
        for (TriggerObject t : linked) {
            if (t.kind() == TriggerObjectKind.SUBSCRIPTION) {
                deleteSubscription(scoped, ec, t);
            }
        }
        for (TriggerObject t : linked) {
            if (t.kind() == TriggerObjectKind.SCHEDULED_JOB) {
                deleteScheduledJob(scoped, ec, t);
            }
        }
        for (TriggerObject t : linked) {
            if (t.kind() == TriggerObjectKind.POOL) {
                triggerObjects.unlink(t.functionId(), t.kind(), t.triggerKey(), scoped.dbTx());
                pools.findById(t.objectId()).ifPresent(p -> scoped.commitDelete(p, pools,
                        DispatchPoolEvents.DispatchPoolDeleted.of(ec, p),
                        new io.flowcatalyst.platform.dispatchpool.operations.DeleteCommand(p.id())));
            }
        }
    }

    // ── onStatusChange (spec §4) ──────────────────────────────────────────

    @Override
    public void onStatusChange(TxScopedUnitOfWork scoped, Function function, ExecutionContext ec) {
        boolean disable = function.status() == FunctionStatus.DISABLED;
        for (TriggerObject t : triggerObjects.listByFunction(function.id())) {
            if (t.kind() == TriggerObjectKind.SUBSCRIPTION) {
                subscriptions.findById(t.objectId()).ifPresent(s -> pauseOrResumeSubscription(scoped, ec, s, disable));
            } else if (t.kind() == TriggerObjectKind.SCHEDULED_JOB) {
                jobs.findById(t.objectId()).ifPresent(j -> pauseOrResumeJob(scoped, ec, j, disable));
            }
        }
    }

    /// Spec §4: disabling pauses only `ACTIVE` linked subscriptions; enabling
    /// resumes only `PAUSED` ones. An object already in the target state (an
    /// operator paused it by hand, or a sibling call already moved it) is
    /// left alone — no write, no event — rather than re-flipped: [Subscription#pause]
    /// / [#resume] are themselves unconditional idempotent flips, so without
    /// this guard EVERY linked subscription would get a fresh `paused`/`resumed`
    /// event on EVERY disable/enable regardless of its current status, and an
    /// operator's hand-pause would be invisible in the event stream (spec §4:
    /// "the platform cannot tell who paused it" — that not-telling only holds
    /// if the platform does not also emit its own redundant event over it).
    private void pauseOrResumeSubscription(TxScopedUnitOfWork scoped, ExecutionContext ec, Subscription s,
            boolean disable) {
        if (disable) {
            if (!s.isActive()) return; // already PAUSED (or otherwise not ACTIVE): left alone
            Subscription updated = s.pause();
            scoped.commit(updated, subscriptions, SubscriptionEvents.SubscriptionPaused.of(ec, updated),
                    new io.flowcatalyst.platform.subscription.operations.PauseCommand(s.id()));
        } else {
            if (!s.isPaused()) return; // already ACTIVE: left alone
            Subscription updated = s.resume();
            scoped.commit(updated, subscriptions, SubscriptionEvents.SubscriptionResumed.of(ec, updated),
                    new io.flowcatalyst.platform.subscription.operations.ResumeCommand(s.id()));
        }
    }

    /// The scheduled-job twin of [#pauseOrResumeSubscription] — same guard,
    /// same reasoning; [ScheduledJob#pause]/[#resume] are unconditional too.
    private void pauseOrResumeJob(TxScopedUnitOfWork scoped, ExecutionContext ec, ScheduledJob j, boolean disable) {
        if (disable) {
            if (j.status() != io.flowcatalyst.platform.scheduledjob.ScheduledJobStatus.ACTIVE) return;
            ScheduledJob updated = j.pause(ec.principalId());
            scoped.commit(updated, jobs, ScheduledJobEvents.ScheduledJobPaused.of(ec, updated),
                    new io.flowcatalyst.platform.scheduledjob.operations.PauseCommand(j.id()));
        } else {
            if (j.status() != io.flowcatalyst.platform.scheduledjob.ScheduledJobStatus.PAUSED) return;
            ScheduledJob updated = j.resume(ec.principalId());
            scoped.commit(updated, jobs, ScheduledJobEvents.ScheduledJobResumed.of(ec, updated),
                    new io.flowcatalyst.platform.scheduledjob.operations.ResumeCommand(j.id()));
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────

    private String applicationCodeOf(Function function) {
        Application app = applications.findById(function.applicationId())
                .orElseThrow(() -> UseCaseException.internal("APPLICATION_NOT_FOUND",
                        "function '" + function.address().render() + "' names an application row that is missing",
                        null));
        return app.code();
    }

    /// `<pool URL>/functions/<address><literalPath>` — NEVER a version (spec
    /// §10 V2): a dispatch job stores its target URL at creation, so a
    /// version in it would keep calling the OLD version after every promote.
    private String endpointFor(Manifest manifest, Function function, String literalPath) {
        return poolUrlTemplate.resolve(manifest.pool()) + "/functions/" + function.address().render() + literalPath;
    }

    private static String zoneOrEmpty(Manifest.ScheduleSpec spec) {
        return spec.timezone() == null ? "" : spec.timezone();
    }

    /// The function id lower-cased without its `fnc_` prefix (spec §4):
    /// legal in every code pattern used here, unique by construction.
    private static String fid(String functionId) {
        return functionId.substring("fnc_".length()).toLowerCase(Locale.ROOT);
    }

    /// 8 hex characters of `sha256(input)` (spec §4's `<8 hex sha256(...)>`).
    private static String hash8(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Map<String, TriggerObject> byKind(List<TriggerObject> linked, TriggerObjectKind kind) {
        Map<String, TriggerObject> out = new LinkedHashMap<>();
        for (TriggerObject t : linked) {
            if (t.kind() == kind) {
                out.put(t.triggerKey(), t);
            }
        }
        return out;
    }
}
