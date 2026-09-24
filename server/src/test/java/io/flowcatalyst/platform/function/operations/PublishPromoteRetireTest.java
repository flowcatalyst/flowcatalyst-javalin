package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionSettingsRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.SecretValue;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.platform.function.operations.FunctionEvents.AliasChanged;
import io.flowcatalyst.platform.function.operations.FunctionEvents.VersionRetired;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `PublishVersion` / `PromoteVersion` / `RetireVersion` against embedded
/// Postgres (spec `function-api.md` §5, §8 P3, P5, P7, P10, P11, P12, and the
/// duplicate-digest/`FUNCTION_DISABLED`/out-of-reach clauses of §5.1).
/// `PublishSignaturesTest` (package `artifact`, for `TestSigstore` access)
/// covers P8; `FunctionApiTest` covers the HTTP wiring, including P10's
/// per-owner ceiling via the real `PUT /api/function-policies/{owner}` route.
@SuppressWarnings("deprecation")
class PublishPromoteRetireTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final FunctionRepository functions = new FunctionRepository(DS);
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(DS);
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(DS);
    // A real key (not Optional.empty()) so the SETTINGS_MISSING tests below can actually
    // set a secret/db-secretRef value through the repository, exactly like production.
    private static final FunctionSettingsRepository settings =
            new FunctionSettingsRepository(DS, Optional.of(Encryption.withKey(Encryption.generateKey())));
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = "usr_ppr_" + RUN;
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final AuthContext ANCHOR =
            new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io", List.of("*"), List.of(), List.of(), true, List.of());
    // client_id is VARCHAR(17) — short, fixed prefixes so a per-test tag never overflows it.
    private static final String CLIENT_ID = "clt_" + RUN;
    private static final String OTHER_CLIENT_ID = "clo_" + RUN;

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final Signatures OFF = new Signatures.Off();
    private static final TriggerSync NONE = TriggerSync.none();

    private static final String MINIMAL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice"
            }
            """;

    private static tools.jackson.databind.JsonNode manifestJson() {
        return Json.MAPPER.readTree(MINIMAL_JVM);
    }

    private static Digest digest(String suffix) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((RUN + ":" + suffix).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Digest.parse("sha256:" + java.util.HexFormat.of().formatHex(hash));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Function createFunction(String tag, FunctionOwner owner) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("ppr" + RUN), new DnsLabel("svc"), new DnsLabel(tag));
        Function f = Function.create("app_" + RUN, address, owner, Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static PublishVersion.Result publish(AuthContext ac, FunctionAddress address, String digestSuffix) {
        return publish(ac, address, digestSuffix, manifestJson());
    }

    private static PublishVersion.Result publish(AuthContext ac, FunctionAddress address, String digestSuffix,
            tools.jackson.databind.JsonNode manifest) {
        var cmd = new PublishCommand(address, "oci://artifact/" + digestSuffix, digest(digestSuffix).value(), null, manifest);
        return Auth.runAs(ac, () -> PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, NONE, java.util.Optional.empty()).run(uow, cmd, EC));
    }

    private static AliasChanged promote(AuthContext ac, FunctionAddress address, int version) {
        return Auth.runAs(ac, () -> PromoteVersion.of(functions, versions, NONE, settings)
                .run(uow, new PromoteCommand(address, Function.LIVE, version), EC));
    }

    private static AliasChanged promote(AuthContext ac, FunctionAddress address, String alias, int version) {
        return Auth.runAs(ac, () -> PromoteVersion.of(functions, versions, NONE, settings)
                .run(uow, new PromoteCommand(address, alias, version), EC));
    }

    private static VersionRetired retire(AuthContext ac, FunctionAddress address, int version) {
        return Auth.runAs(ac, () -> RetireVersion.of(functions, versions)
                .run(uow, new RetireCommand(address, version), EC));
    }

    private static void markReady(FunctionVersion v) {
        uow.inTransaction(tx -> {
            versions.persist(v.markReady(Instant.now()), tx.dbTx());
            return null;
        });
    }

    private static Result<Record> eventsFor(String subject, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                subject, type);
    }

    private static Result<Record> auditsFor(String entityId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                entityId, operation);
    }

    private static void assertUseCaseError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    // ── P5: publish/promote/retire each write exactly one event + one audit row ──

    @Test
    void publishWritesOneVersionPublishedEventAndOneAuditRow() {
        Function f = createFunction("p5publish", new FunctionOwner.Platform());
        var result = publish(ANCHOR, f.address(), "a");

        var events = eventsFor("platform.function." + f.id(), FunctionEvents.VERSION_PUBLISHED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("message_group")).as("mutant: wrong message group")
                .isEqualTo("platform:function:" + f.id());

        assertThat(auditsFor(f.id(), "PublishCommand")).as("mutant: wrong/no audit row").hasSize(1);
        assertThat(result.version().version()).isEqualTo(1);
        assertThat(result.event().digest()).isEqualTo(digest("a").value());
    }

    @Test
    void promoteWritesOneAliasChangedEventAndOneAuditRow() {
        Function f = createFunction("p5promote", new FunctionOwner.Platform());
        var v = publish(ANCHOR, f.address(), "a").version();
        markReady(v);

        AliasChanged event = promote(ANCHOR, f.address(), 1);

        var events = eventsFor("platform.function." + f.id(), FunctionEvents.ALIAS_CHANGED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("message_group")).as("mutant: wrong message group")
                .isEqualTo("platform:function:" + f.id());
        assertThat(auditsFor(f.id(), "PromoteCommand")).as("mutant: wrong/no audit row").hasSize(1);
        assertThat(event.previousVersionId()).as("first promotion has no previous version").isNull();
    }

    @Test
    void retireWritesOneVersionRetiredEventAndOneAuditRow() {
        Function f = createFunction("p5retire", new FunctionOwner.Platform());
        publish(ANCHOR, f.address(), "a");

        retire(ANCHOR, f.address(), 1);

        var events = eventsFor("platform.function." + f.id(), FunctionEvents.VERSION_RETIRED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("message_group")).as("mutant: wrong message group")
                .isEqualTo("platform:function:" + f.id());
        assertThat(auditsFor(f.id(), "RetireCommand")).as("mutant: wrong/no audit row").hasSize(1);
    }

    // ── P3: identity fields unchanged after publish/promote/retire ──────────

    @Test
    void identityFieldsAreUnchangedAfterPublishPromoteAndRetire() {
        Function f = createFunction("p3", FunctionOwner.ofClientId(CLIENT_ID));

        var v1 = publish(ANCHOR, f.address(), "a").version();
        assertIdentityUnchanged(f);

        markReady(v1);
        promote(ANCHOR, f.address(), 1);
        assertIdentityUnchanged(f);

        var v2 = publish(ANCHOR, f.address(), "b").version();
        markReady(v2);
        promote(ANCHOR, f.address(), 2);
        retire(ANCHOR, f.address(), 1);
        assertIdentityUnchanged(f);
    }

    private static void assertIdentityUnchanged(Function original) {
        Function reloaded = functions.findById(original.id()).orElseThrow();
        assertThat(reloaded.address()).isEqualTo(original.address());
        assertThat(reloaded.applicationId()).isEqualTo(original.applicationId());
        assertThat(reloaded.owner()).isEqualTo(original.owner());
        assertThat(reloaded.runtime()).isEqualTo(original.runtime());
    }

    // ── Duplicate digest ──────────────────────────────────────────────────

    @Test
    void duplicateDigestConflictsNamingTheExistingVersion() {
        Function f = createFunction("dup", new FunctionOwner.Platform());
        publish(ANCHOR, f.address(), "same");

        assertUseCaseError(() -> publish(ANCHOR, f.address(), "same"), UseCaseError.Conflict.class, "VERSION_DIGEST_EXISTS");
        assertThatThrownBy(() -> publish(ANCHOR, f.address(), "same"))
                .hasMessageContaining("version 1");

        // details.version lets a caller (fcdev `fn deploy`) recover the existing
        // version number without parsing the message's prose.
        assertThatThrownBy(() -> publish(ANCHOR, f.address(), "same"))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error().details())
                .isEqualTo(java.util.Map.of("version", 1));

        // Only the first version row exists — the duplicate attempt never persisted.
        assertThat(versions.listByFunction(f.id())).hasSize(1);
    }

    // ── FUNCTION_DISABLED ─────────────────────────────────────────────────

    @Test
    void publishToADisabledFunctionConflicts() {
        Function f = createFunction("disabled", new FunctionOwner.Platform());
        Function disabled = f.disable(Instant.now());
        uow.inTransaction(tx -> {
            functions.persist(disabled, tx.dbTx());
            return null;
        });

        assertUseCaseError(() -> publish(ANCHOR, f.address(), "a"), UseCaseError.Conflict.class, "FUNCTION_DISABLED");
        assertThat(versions.listByFunction(f.id())).as("mutant: skip the check — nothing must persist").isEmpty();
    }

    // ── Out of reach ──────────────────────────────────────────────────────

    @Test
    void publishToAFunctionOutOfReachIs404() {
        Function f = createFunction("reach", FunctionOwner.ofClientId(CLIENT_ID));
        AuthContext stranger =
                new AuthContext("usr_stranger", Scope.CLIENT, null, List.of(OTHER_CLIENT_ID), List.of(), List.of(), true, List.of());

        assertUseCaseError(() -> publish(stranger, f.address(), "a"), UseCaseError.NotFound.class, "Function_NOT_FOUND");
        assertThat(versions.listByFunction(f.id())).isEmpty();
    }

    // ── P7: publish is atomic — a throwing trigger seam leaves nothing ──────

    @Test
    void aThrowingTriggerSeamLeavesNoVersionRowNoEventAndTheNextPublishStillGetsVersionOne() {
        Function f = createFunction("p7", new FunctionOwner.Platform());
        TriggerSync none = TriggerSync.none();
        TriggerSync throwing = new TriggerSync() {
            @Override
            public void onPublish(io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork scoped, Function function,
                    io.flowcatalyst.platform.function.FunctionVersion version) {
                throw new RuntimeException("trigger sync exploded");
            }

            @Override
            public java.util.List<io.flowcatalyst.sdk.usecase.UseCaseError> checkPublish(Function function,
                    io.flowcatalyst.platform.function.Manifest manifest) {
                return none.checkPublish(function, manifest);
            }

            @Override
            public PromotePlan plan(Function function, io.flowcatalyst.platform.function.Manifest manifest,
                    int toVersion, String alias) {
                return none.plan(function, manifest, toVersion, alias);
            }

            @Override
            public void apply(io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork scoped, Function function,
                    io.flowcatalyst.platform.function.FunctionVersion newLive,
                    io.flowcatalyst.sdk.usecase.ExecutionContext ec, PromotePlan plan) {
            }

            @Override
            public void onDelete(io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork scoped, Function function,
                    io.flowcatalyst.sdk.usecase.ExecutionContext ec) {
            }

            @Override
            public void onStatusChange(io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork scoped, Function function,
                    io.flowcatalyst.sdk.usecase.ExecutionContext ec) {
            }
        };
        var cmd = new PublishCommand(f.address(), "oci://artifact/p7", digest("p7").value(), null, manifestJson());

        assertThatThrownBy(() -> Auth.runAs(ANCHOR, () ->
                PublishVersion.of(functions, versions, policies, DEFAULTS, OFF, throwing, java.util.Optional.empty()).run(uow, cmd, EC)))
                .isInstanceOf(RuntimeException.class);

        assertThat(versions.listByFunction(f.id())).as("mutant: call the seam after commit — no row must survive").isEmpty();
        assertThat(eventsFor("platform.function." + f.id(), FunctionEvents.VERSION_PUBLISHED))
                .as("mutant: call the seam after commit — no event must survive").isEmpty();

        // The version counter did not advance either — the next real publish still gets version 1.
        var result = publish(ANCHOR, f.address(), "p7-next");
        assertThat(result.version().version()).as("mutant: leaked version counter").isEqualTo(1);
    }

    // ── P11: two concurrent publishes of different digests both succeed, versions n/n+1 ──

    @Test
    void concurrentPublishesOfDifferentDigestsBothSucceedWithSequentialVersions() throws Exception {
        Function f = createFunction("p11", new FunctionOwner.Platform());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<PublishVersion.Result> f1 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return publish(ANCHOR, f.address(), "p11a");
            });
            Future<PublishVersion.Result> f2 = pool.submit(() -> {
                ready.countDown();
                go.await();
                return publish(ANCHOR, f.address(), "p11b");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();

            int v1 = f1.get(20, TimeUnit.SECONDS).version().version();
            int v2 = f2.get(20, TimeUnit.SECONDS).version().version();

            assertThat(List.of(v1, v2)).as("mutant: nextVersion outside the tx — must serialise to {1,2}, never {1,1}")
                    .containsExactlyInAnyOrder(1, 2);
            assertThat(versions.listByFunction(f.id())).hasSize(2);
        } finally {
            pool.shutdown();
        }
    }

    // ── P12: promote requires READY; retire refuses the live version; rollback ──

    @Test
    void promoteRequiresReadyRetireRefusesLiveAndRollbackWorks() {
        Function f = createFunction("p12", new FunctionOwner.Platform());
        var v1 = publish(ANCHOR, f.address(), "v1").version();

        // Not yet ready — the error names both the version and the pool (spec §5.2's exact wording).
        assertUseCaseError(() -> promote(ANCHOR, f.address(), 1), UseCaseError.Conflict.class, "VERSION_NOT_READY");
        assertThatThrownBy(() -> promote(ANCHOR, f.address(), 1))
                .hasMessageContaining("version 1").hasMessageContaining("pool 'default'");

        markReady(v1);
        AliasChanged firstPromote = promote(ANCHOR, f.address(), 1);
        assertThat(firstPromote.version()).isEqualTo(1);

        // The live version cannot be retired.
        assertUseCaseError(() -> retire(ANCHOR, f.address(), 1), UseCaseError.Conflict.class, "VERSION_IS_LIVE");

        var v2 = publish(ANCHOR, f.address(), "v2").version();
        markReady(v2);
        AliasChanged secondPromote = promote(ANCHOR, f.address(), 2);
        assertThat(secondPromote.previousVersionId()).isEqualTo(v1.id());

        // v1 is no longer live — retiring it now succeeds.
        retire(ANCHOR, f.address(), 1);

        // Promoting the now-retired v1 back is refused, distinctly from VERSION_NOT_READY.
        assertUseCaseError(() -> promote(ANCHOR, f.address(), 1), UseCaseError.Conflict.class, "VERSION_RETIRED");

        // Rollback: v2 -> v1 fails (v1 retired); publish v3, mark ready, promote back to v2 (still ready) works.
        var v3 = publish(ANCHOR, f.address(), "v3").version();
        markReady(v3);
        promote(ANCHOR, f.address(), 3);
        AliasChanged rollback = promote(ANCHOR, f.address(), 2);
        assertThat(rollback.version()).as("rollback to an older, still-READY version works").isEqualTo(2);
        assertThat(rollback.previousVersionId()).isEqualTo(v3.id());
    }

    // ── Review fix, slice B3: alias validity is checked BEFORE version state ──

    @Test
    void anInvalidAliasNameOnAnUnreadyVersionIsAliasInvalidNotVersionNotReady() {
        Function f = createFunction("aliasorder", new FunctionOwner.Platform());
        publish(ANCHOR, f.address(), "v1"); // version 1, still PUBLISHED — never marked ready

        // The mutant this pins: if PromoteVersion guarded version state before alias validity
        // (or duplicated the check in `execute` instead of `validate`), this would surface as
        // 409 VERSION_NOT_READY instead of 400 ALIAS_INVALID.
        assertUseCaseError(() -> promote(ANCHOR, f.address(), "CANARY", 1),
                UseCaseError.Validation.class, "ALIAS_INVALID");
    }

    // ── spec `function-zones-and-aliases.md` §2: named aliases ──────────────

    /// A1: a well-formed named alias, once its target is `READY`, is
    /// accepted — pins that the READY rule (R3) applies to a named alias
    /// exactly as it does to `live` (mutant: skip the R3 guard for a named
    /// alias).
    @Test
    void promotingANamedAliasRequiresReadyJustLikeLive() {
        Function f = createFunction("namedalias", new FunctionOwner.Platform());
        var v1 = publish(ANCHOR, f.address(), "v1").version();

        assertUseCaseError(() -> promote(ANCHOR, f.address(), "qa", 1), UseCaseError.Conflict.class, "VERSION_NOT_READY");

        markReady(v1);
        AliasChanged event = promote(ANCHOR, f.address(), "qa", 1);
        assertThat(event.alias()).isEqualTo("qa");
        assertThat(event.previousVersionId()).as("qa's first promotion has no previous target").isNull();

        // live is untouched by a named-alias promote.
        assertThat(functions.findById(f.id()).orElseThrow().liveVersionId()).isEmpty();
    }

    /// A named alias refuses a RETIRED target, distinctly from
    /// `VERSION_NOT_READY` — the same rule `live` gets (spec §2's own R3
    /// paragraph).
    @Test
    void promotingANamedAliasToARetiredVersionConflicts() {
        Function f = createFunction("namedretired", new FunctionOwner.Platform());
        var v1 = publish(ANCHOR, f.address(), "v1").version();
        markReady(v1);
        promote(ANCHOR, f.address(), Function.LIVE, 1); // v1 becomes live so it CAN be retired later
        var v2 = publish(ANCHOR, f.address(), "v2").version();
        markReady(v2);
        promote(ANCHOR, f.address(), Function.LIVE, 2); // v1 no longer live
        retire(ANCHOR, f.address(), 1);

        assertUseCaseError(() -> promote(ANCHOR, f.address(), "qa", 1), UseCaseError.Conflict.class, "VERSION_RETIRED");
    }

    /// Re-pointing a named alias at the version it already names is refused
    /// — but only for THAT alias; a different alias pointing at the same
    /// version is unaffected (mutant: compare against `live`'s target
    /// instead of the alias being promoted).
    @Test
    void promotingANamedAliasToItsOwnCurrentVersionConflictsButAnotherAliasIsUnaffected() {
        Function f = createFunction("namedunchanged", new FunctionOwner.Platform());
        var v1 = publish(ANCHOR, f.address(), "v1").version();
        markReady(v1);
        promote(ANCHOR, f.address(), Function.LIVE, 1);
        promote(ANCHOR, f.address(), "qa", 1);

        assertUseCaseError(() -> promote(ANCHOR, f.address(), "qa", 1), UseCaseError.Conflict.class, "ALIAS_UNCHANGED");
        // live already pointed at v1 too — re-promoting live to v1 must independently conflict,
        // not be silently skipped because qa's check ran first.
        assertUseCaseError(() -> promote(ANCHOR, f.address(), Function.LIVE, 1), UseCaseError.Conflict.class, "ALIAS_UNCHANGED");
    }

    // ── RemoveAlias (spec §2) ─────────────────────────────────────────────

    private static FunctionEvents.AliasRemoved removeAlias(AuthContext ac, FunctionAddress address, String alias) {
        return Auth.runAs(ac, () -> RemoveAlias.of(functions, versions)
                .run(uow, new RemoveAliasCommand(address, alias), EC));
    }

    /// A2: `live` can never be removed; a named alias is removed and gone
    /// from the aggregate afterward (mutant: allow removing `live`, or
    /// remove without checking the alias exists).
    @Test
    void removeAliasProtectsLiveDeletesNamedAndRefusesUnknown() {
        Function f = createFunction("removealias", new FunctionOwner.Platform());
        var v1 = publish(ANCHOR, f.address(), "v1").version();
        markReady(v1);
        promote(ANCHOR, f.address(), Function.LIVE, 1);
        promote(ANCHOR, f.address(), "qa", 1);

        assertUseCaseError(() -> removeAlias(ANCHOR, f.address(), Function.LIVE), UseCaseError.Conflict.class, "ALIAS_PROTECTED");
        assertThat(functions.findById(f.id()).orElseThrow().aliases()).as("mutant: live removed anyway").hasSize(2);

        FunctionEvents.AliasRemoved removed = removeAlias(ANCHOR, f.address(), "qa");
        assertThat(removed.alias()).isEqualTo("qa");
        assertThat(removed.versionId()).isEqualTo(v1.id());
        Function afterRemove = functions.findById(f.id()).orElseThrow();
        assertThat(afterRemove.aliases()).as("mutant: qa still present after removal")
                .hasSize(1).extracting(Function.FunctionAlias::alias).containsExactly(Function.LIVE);

        assertUseCaseError(() -> removeAlias(ANCHOR, f.address(), "qa"), UseCaseError.NotFound.class, "Alias_NOT_FOUND");
    }

    @Test
    void removeAliasWritesOneAliasRemovedEventAndOneAuditRow() {
        Function f = createFunction("removeevent", new FunctionOwner.Platform());
        var v1 = publish(ANCHOR, f.address(), "v1").version();
        markReady(v1);
        promote(ANCHOR, f.address(), "qa", 1);

        removeAlias(ANCHOR, f.address(), "qa");

        var events = eventsFor("platform.function." + f.id(), FunctionEvents.ALIAS_REMOVED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("message_group")).as("mutant: wrong message group")
                .isEqualTo("platform:function:" + f.id());
        assertThat(auditsFor(f.id(), "RemoveAliasCommand")).as("mutant: wrong/no audit row").hasSize(1);
    }

    // ── A3: retire refuses a version a named alias still points at ──────────

    /// spec §2: retiring a version a NAMED alias still points at is refused,
    /// naming the alias — distinctly from `VERSION_IS_LIVE` — and succeeds
    /// once the alias is moved elsewhere (mutant: skip the check entirely).
    @Test
    void retireRefusesAVersionANamedAliasPointsAtNamingItAndSucceedsOnceMoved() {
        Function f = createFunction("retirealiased", new FunctionOwner.Platform());
        var v1 = publish(ANCHOR, f.address(), "v1").version();
        markReady(v1);
        promote(ANCHOR, f.address(), "qa", 1);

        assertUseCaseError(() -> retire(ANCHOR, f.address(), 1), UseCaseError.Conflict.class, "VERSION_ALIASED");
        assertThatThrownBy(() -> retire(ANCHOR, f.address(), 1)).hasMessageContaining("qa");
        assertThat(versions.findByFunctionAndVersion(f.id(), 1).orElseThrow().state())
                .as("mutant: retire anyway").isInstanceOf(FunctionVersion.VersionState.Ready.class);

        var v2 = publish(ANCHOR, f.address(), "v2").version();
        markReady(v2);
        promote(ANCHOR, f.address(), "qa", 2); // move qa off v1

        retire(ANCHOR, f.address(), 1); // now succeeds
        assertThat(versions.findByFunctionAndVersion(f.id(), 1).orElseThrow().state())
                .isInstanceOf(FunctionVersion.VersionState.Retired.class);
    }

    // ── X3 (function-context.md §1): promote refuses SETTINGS_MISSING, one source at a time ──

    private static final String MANIFEST_WITH_CONFIG = """
            {"runtime":"jvm","entrypoint":"com.acme.billing.CreateInvoice","config":["FOO"]}
            """;
    private static final String MANIFEST_WITH_SECRET = """
            {"runtime":"jvm","entrypoint":"com.acme.billing.CreateInvoice","secrets":["API_KEY"]}
            """;
    private static final String MANIFEST_WITH_DB = """
            {"runtime":"jvm","entrypoint":"com.acme.billing.CreateInvoice",
             "db":[{"name":"main","secretRef":"DB_DSN"}]}
            """;

    /// X3: an unset `config` key, in isolation, refuses `SETTINGS_MISSING`
    /// naming it — and succeeds once it is set. Pins that promote actually
    /// checks the `config` source (mutant: skip it) and that the check does
    /// not merely always fail (mutant: fail unconditionally) — the second
    /// `promote` call must succeed.
    @Test
    void promoteRefusesSettingsMissingForAnUnsetConfigKeyAndSucceedsOnceSet() {
        Function f = createFunction("settings-config", new FunctionOwner.Platform());
        var v = publish(ANCHOR, f.address(), "cfg", Json.MAPPER.readTree(MANIFEST_WITH_CONFIG)).version();
        markReady(v);

        assertUseCaseError(() -> promote(ANCHOR, f.address(), 1), UseCaseError.Conflict.class, "SETTINGS_MISSING");
        assertThatThrownBy(() -> promote(ANCHOR, f.address(), 1)).hasMessageContaining("FOO");
        // Nothing was promoted by the refused attempt.
        assertThat(functions.findById(f.id()).orElseThrow().liveVersionId()).isEmpty();

        uow.inTransaction(tx -> {
            settings.replaceConfig(f.id(), Map.of("FOO", "bar"), PRINCIPAL, tx.dbTx());
            return null;
        });
        AliasChanged event = promote(ANCHOR, f.address(), 1);
        assertThat(event.version()).isEqualTo(1);
    }

    /// X3: an unset `secrets` key, in isolation.
    @Test
    void promoteRefusesSettingsMissingForAnUnsetSecretKeyAndSucceedsOnceSet() {
        Function f = createFunction("settings-secret", new FunctionOwner.Platform());
        var v = publish(ANCHOR, f.address(), "sec", Json.MAPPER.readTree(MANIFEST_WITH_SECRET)).version();
        markReady(v);

        assertUseCaseError(() -> promote(ANCHOR, f.address(), 1), UseCaseError.Conflict.class, "SETTINGS_MISSING");
        assertThatThrownBy(() -> promote(ANCHOR, f.address(), 1)).hasMessageContaining("API_KEY");

        uow.inTransaction(tx -> {
            settings.putSecret(f.id(), "API_KEY", new SecretValue("shh"), PRINCIPAL, tx.dbTx());
            return null;
        });
        AliasChanged event = promote(ANCHOR, f.address(), 1);
        assertThat(event.version()).isEqualTo(1);
    }

    /// X3: an unset `db[].secretRef`, in isolation — the DSN is itself a
    /// secret, named by `secretRef`, stored in the SAME `fn_secrets` table
    /// `secrets` uses.
    @Test
    void promoteRefusesSettingsMissingForAnUnsetDbSecretRefAndSucceedsOnceSet() {
        Function f = createFunction("settings-db", new FunctionOwner.Platform());
        var v = publish(ANCHOR, f.address(), "db", Json.MAPPER.readTree(MANIFEST_WITH_DB)).version();
        markReady(v);

        assertUseCaseError(() -> promote(ANCHOR, f.address(), 1), UseCaseError.Conflict.class, "SETTINGS_MISSING");
        assertThatThrownBy(() -> promote(ANCHOR, f.address(), 1)).hasMessageContaining("DB_DSN");

        uow.inTransaction(tx -> {
            // "jdbc:postgresql://…" (not a bare "postgres://…") — Encryption#encryptSecretRef
            // rejects an unknown bare "<scheme>://" value outright (docs/spec/encryption.md §3);
            // "jdbc:postgresql" is not a scheme token (it contains a colon), so this passes
            // through as an ordinary plaintext secret, encrypted normally.
            settings.putSecret(f.id(), "DB_DSN", new SecretValue("jdbc:postgresql://u:p@h/db"), PRINCIPAL, tx.dbTx());
            return null;
        });
        AliasChanged event = promote(ANCHOR, f.address(), 1);
        assertThat(event.version()).isEqualTo(1);
    }
}
