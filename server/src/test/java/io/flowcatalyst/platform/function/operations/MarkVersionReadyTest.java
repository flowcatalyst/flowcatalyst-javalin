package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionVersion;
import io.flowcatalyst.platform.function.FunctionVersionRepository;
import io.flowcatalyst.platform.function.Manifest;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.operations.FunctionEvents.VersionReady;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `MarkVersionReady` against embedded Postgres (spec `function-api.md` §6.2,
/// §3's `version:ready` event, §8 P6/P3's heartbeat clause).
class MarkVersionReadyTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final FunctionRepository functions = new FunctionRepository(DS);
    private static final FunctionVersionRepository versions = new FunctionVersionRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = "prn_host_" + RUN;
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final AuthContext HOST = new AuthContext(PRINCIPAL, Scope.ANCHOR, null, List.of("*"), List.of(), List.of(), true, List.of());

    private static final FunctionLimits DEFAULTS = FunctionLimits.defaults();
    private static final ClientCeilings UNRESTRICTED = ClientCeilings.of(DEFAULTS);

    private static final String MINIMAL_JVM = """
            {
              "runtime": "jvm",
              "entrypoint": "com.acme.billing.CreateInvoice"
            }
            """;

    private static Manifest manifest() {
        return Manifest.parseStrict(Json.MAPPER.readTree(MINIMAL_JVM), Runtime.JVM, DEFAULTS, UNRESTRICTED);
    }

    private static Function createFunction(String tag) {
        FunctionAddress address = FunctionAddress.of(new DnsLabel("mvr" + RUN), new DnsLabel("svc"), new DnsLabel(tag));
        Function f = Function.create("app_" + RUN, address, FunctionOwner.ofClientId("clt_" + RUN), Runtime.JVM, null);
        uow.inTransaction(tx -> {
            functions.persist(f, tx.dbTx());
            return null;
        });
        return f;
    }

    private static FunctionVersion publish(Function f, int version, String digestSuffix) {
        FunctionVersion v = FunctionVersion.publish(f.id(), version, "oci://artifact", digest(digestSuffix), null,
                null, null, manifest(), "prn_publisher", Instant.now());
        uow.inTransaction(tx -> {
            versions.persist(v, tx.dbTx());
            return null;
        });
        return v;
    }

    private static Digest digest(String suffix) {
        return Digest.parse("sha256:" + suffix.repeat(64).substring(0, 64));
    }

    private static VersionReady run(FunctionVersion v, String hostId) {
        return Auth.runAs(HOST, () -> MarkVersionReady.of(versions, functions)
                .run(uow, new MarkVersionReadyCommand(v.id(), hostId), EC));
    }

    private static Result<Record> eventsFor(String subject, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data FROM msg_events WHERE subject = ? AND type = ?",
                subject, type);
    }

    private static Result<Record> auditsFor(String entityId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                entityId, operation);
    }

    private static tools.jackson.databind.JsonNode json(String s) {
        return Json.MAPPER.readTree(s);
    }

    // ── Marks the version READY and emits version:ready ─────────────────────

    @Test
    void marksAPublishedVersionReadyAndEmitsVersionReadyWithTheHostId() {
        Function f = createFunction("ready");
        FunctionVersion v = publish(f, 1, "a");

        VersionReady ev = run(v, "host-1");
        assertThat(ev.functionId()).isEqualTo(f.id());
        assertThat(ev.address()).isEqualTo(f.address().render());
        assertThat(ev.versionId()).isEqualTo(v.id());
        assertThat(ev.version()).isEqualTo(1);
        assertThat(ev.hostId()).isEqualTo("host-1");

        FunctionVersion reloaded = versions.findById(v.id()).orElseThrow();
        assertThat(reloaded.state()).isInstanceOf(FunctionVersion.VersionState.Ready.class);

        // P5-shaped: exactly one event, the FUNCTION's message group (not the policy carve-out).
        var events = eventsFor("platform.function." + f.id(), FunctionEvents.VERSION_READY);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("message_group")).as("mutant guard: wrong message group")
                .isEqualTo("platform:function:" + f.id());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("versionId").asString()).isEqualTo(v.id());
        assertThat(data.get("version").asInt()).isEqualTo(1);
        assertThat(data.get("hostId").asString()).isEqualTo("host-1");

        assertThat(auditsFor(f.id(), "MarkVersionReadyCommand")).hasSize(1);
        assertThat(auditsFor(f.id(), "MarkVersionReadyCommand").getFirst().get("principal_id")).as(
                        "spec §6.2: the audit principal is the calling host's own principal")
                .isEqualTo(PRINCIPAL);

        // P3's heartbeat clause: the function's own identity is untouched by a heartbeat.
        Function afterwards = functions.findById(f.id()).orElseThrow();
        assertThat(afterwards.address()).isEqualTo(f.address());
        assertThat(afterwards.applicationId()).isEqualTo(f.applicationId());
        assertThat(afterwards.owner()).isEqualTo(f.owner());
        assertThat(afterwards.runtime()).isEqualTo(f.runtime());
    }

    // ── markReady keeps the first ready_at (§8 M12, exercised through the operation) ──

    @Test
    void aSecondCallOnAnAlreadyReadyVersionKeepsTheOriginalReadyAt() {
        Function f = createFunction("keepready");
        FunctionVersion v = publish(f, 1, "b");

        run(v, "host-1");
        FunctionVersion firstReady = versions.findById(v.id()).orElseThrow();
        Instant firstReadyAt = ((FunctionVersion.VersionState.Ready) firstReady.state()).at();

        // The caller (FunctionControlApi) is what normally prevents this second call —
        // MarkVersionReady itself has no such guard (see its class doc) — so calling it
        // again must still never resurrect a fresh `ready_at`.
        run(v, "host-2");
        FunctionVersion secondReady = versions.findById(v.id()).orElseThrow();
        assertThat(((FunctionVersion.VersionState.Ready) secondReady.state()).at())
                .as("mutant: overwrite ready_at on a second Ready->Ready transition")
                .isEqualTo(firstReadyAt);
    }

    // ── Not-found ─────────────────────────────────────────────────────────

    @Test
    void anUnknownVersionIdIsNotFound() {
        assertThatThrownBy(() -> run0("fnv_doesnotexist_" + RUN, "host-1"))
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).isInstanceOf(UseCaseError.NotFound.class);
                    assertThat(err.code()).isEqualTo("FunctionVersion_NOT_FOUND");
                });
    }

    private static VersionReady run0(String versionId, String hostId) {
        return Auth.runAs(HOST, () -> MarkVersionReady.of(versions, functions)
                .run(uow, new MarkVersionReadyCommand(versionId, hostId), EC));
    }
}
