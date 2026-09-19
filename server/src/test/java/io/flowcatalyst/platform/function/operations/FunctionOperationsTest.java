package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.application.ApplicationType;
import io.flowcatalyst.platform.client.Client;
import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.ClientPolicy;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionStatus;
import io.flowcatalyst.platform.function.operations.FunctionEvents.FunctionCreated;
import io.flowcatalyst.platform.function.operations.FunctionEvents.FunctionDeleted;
import io.flowcatalyst.platform.function.operations.FunctionEvents.FunctionUpdated;
import io.flowcatalyst.platform.function.operations.FunctionEvents.PolicyUpdated;
import io.flowcatalyst.platform.function.operations.TriggerSync;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.testpg.TestPg;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `CreateFunction` / `UpdateFunction` / `DeleteFunction` / `PutFunctionPolicy`
/// against embedded Postgres (spec `function-api.md` §4.1–§4.3, §8): R1
/// (P1), the immutable-fields invariant (P3), one `msg_events` + one
/// `aud_logs` row per write with the function's message group (P5), and —
/// end to end through one operation each — that a function out of reach is
/// 404 (P2; `AccessTest` covers the three reach clauses exhaustively).
@SuppressWarnings("deprecation")
class FunctionOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final ApplicationRepository applications = new ApplicationRepository(DS);
    private static final ClientRepository clients = new ClientRepository(DS);
    private static final FunctionRepository functions = new FunctionRepository(DS);
    private static final ClientPolicyRepository policies = new ClientPolicyRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = "usr_" + RUN;
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());

    private static <C, E extends io.flowcatalyst.sdk.usecase.DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static <C, E extends io.flowcatalyst.sdk.usecase.DomainEvent> E runAs(AuthContext ac, Operation<C, E> op, C cmd) {
        return Auth.runAs(ac, () -> op.run(uow, cmd, EC));
    }

    private static <C, R> R runAsAnchor(io.flowcatalyst.sdk.usecase.op.TxOperation<C, R> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static <C, R> R runAs(AuthContext ac, io.flowcatalyst.sdk.usecase.op.TxOperation<C, R> op, C cmd) {
        return Auth.runAs(ac, () -> op.run(uow, cmd, EC));
    }

    private static String testApplication(String tag, String code) {
        Application a = Application.create(ApplicationType.APPLICATION, code, "Function Ops " + tag);
        uow.inTransaction(tx -> {
            applications.persist(a, tx.dbTx());
            return null;
        });
        return a.id();
    }

    private static String testClient(String tag) {
        Client c = Client.create("Function Ops " + tag, ClientIdentifier.parse("fno-" + RUN + "-" + tag));
        uow.inTransaction(tx -> {
            clients.persist(c, tx.dbTx());
            return null;
        });
        return c.id();
    }

    private static Function reload(FunctionAddress address) {
        return functions.findByAddress(address).orElseThrow(() -> new AssertionError("function " + address.render() + " not found"));
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
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

    private static tools.jackson.databind.JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ── CreateFunction (§4.1) ────────────────────────────────────────────────

    @Test
    void createWritesTheRowThePlatformScopedEventAndTheAuditForAPlatformOwnedFunction() {
        String appId = testApplication("create-platform", "createplat-" + RUN);

        FunctionCreated ev = runAsAnchor(CreateFunction.of(functions, applications, clients),
                new CreateCommand("createplat-" + RUN, "svc", "fn", "jvm", "desc", null));

        assertThat(ev.functionId()).startsWith("fnc_");
        assertThat(ev.address()).isEqualTo("createplat-" + RUN + ".svc.fn");
        assertThat(ev.clientId()).isNull();
        assertThat(ev.runtime()).isEqualTo("JVM");

        Function f = reload(FunctionAddress.parse("createplat-" + RUN + ".svc.fn"));
        assertThat(f.applicationId()).isEqualTo(appId);
        assertThat(f.owner()).isEqualTo(new FunctionOwner.Platform());
        assertThat(f.description()).isEqualTo("desc");
        assertThat(f.status()).isEqualTo(FunctionStatus.ACTIVE);

        // P5: exactly one msg_events row, the function's message group.
        var events = eventsFor("platform.function." + ev.functionId(), FunctionEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(FunctionEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).as("P5 mutant guard: wrong message group")
                .isEqualTo("platform:function:" + ev.functionId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("functionId").asText()).isEqualTo(ev.functionId());
        assertThat(data.get("applicationId").asText()).isEqualTo(appId);
        assertThat(data.has("clientId")).as("absent optional omitted, not null").isFalse();

        assertThat(auditsFor(ev.functionId(), "CreateCommand")).hasSize(1);
    }

    @Test
    void createWritesAClientOwnedFunctionAndReachIsCheckedAgainstThatClient() {
        String clientId = testClient("create-client");
        testApplication("create-client", "createclient-" + RUN);

        FunctionCreated ev = runAsAnchor(CreateFunction.of(functions, applications, clients),
                new CreateCommand("createclient-" + RUN, "svc", "fn", "jvm", null, clientId));
        assertThat(ev.clientId()).isEqualTo(clientId);

        Function f = reload(FunctionAddress.parse("createclient-" + RUN + ".svc.fn"));
        assertThat(f.owner()).isEqualTo(FunctionOwner.ofClientId(clientId));

        // A client-scoped principal for a DIFFERENT client cannot create under this one.
        AuthContext otherClient = new AuthContext("usr_other", Scope.CLIENT, null, List.of("clt_someoneelse"),
                List.of(), List.of(), true, List.of());
        assertUseCaseError(() -> runAs(otherClient, CreateFunction.of(functions, applications, clients),
                        new CreateCommand("createclient-" + RUN, "svc", "fn2", "jvm", null, clientId)),
                UseCaseError.Authorization.class, "SCOPE_FORBIDDEN");
    }

    /// spec §8 P1: an application whose STORED code is not a DNS label
    /// (contains `_`) cannot own a function, and the message says why.
    /// Mutant: skip the `DnsLabel` check on the stored code — verified by
    /// hand (see the operation's report) that removing
    /// `addressableApplicationLabel`'s try/catch makes this test fail with a
    /// different code (`LABEL_INVALID` from the generic parser, not
    /// `APPLICATION_CODE_NOT_ADDRESSABLE`) rather than pass.
    @Test
    void createRejectsAnApplicationWhoseStoredCodeIsNotADnsLabel() {
        testApplication("r1", "logistics_portal_" + RUN);

        assertUseCaseError(() -> runAsAnchor(CreateFunction.of(functions, applications, clients),
                        new CreateCommand("logistics_portal_" + RUN, "svc", "fn", "jvm", null, null)),
                UseCaseError.Validation.class, "APPLICATION_CODE_NOT_ADDRESSABLE");

        assertThatThrownBy(() -> runAsAnchor(CreateFunction.of(functions, applications, clients),
                new CreateCommand("logistics_portal_" + RUN, "svc", "fn", "jvm", null, null)))
                .extracting(t -> ((UseCaseException) t).error().message())
                .satisfies(msg -> assertThat(msg).contains("logistics_portal_" + RUN).contains("DNS label"));
    }

    @Test
    void createRejectsADuplicateAddress() {
        testApplication("dup", "createdup-" + RUN);
        runAsAnchor(CreateFunction.of(functions, applications, clients),
                new CreateCommand("createdup-" + RUN, "svc", "fn", "jvm", null, null));

        assertUseCaseError(() -> runAsAnchor(CreateFunction.of(functions, applications, clients),
                        new CreateCommand("createdup-" + RUN, "svc", "fn", "jvm", null, null)),
                UseCaseError.Conflict.class, "FUNCTION_EXISTS");
    }

    @Test
    void createRejectsAMissingApplicationOrAnInvalidRuntime() {
        assertUseCaseError(() -> runAsAnchor(CreateFunction.of(functions, applications, clients),
                        new CreateCommand("nosuchapp-" + RUN, "svc", "fn", "jvm", null, null)),
                UseCaseError.NotFound.class, "Application_NOT_FOUND");

        testApplication("badruntime", "badruntime-" + RUN);
        assertUseCaseError(() -> runAsAnchor(CreateFunction.of(functions, applications, clients),
                        new CreateCommand("badruntime-" + RUN, "svc", "fn", "bogus", null, null)),
                UseCaseError.Validation.class, "RUNTIME_INVALID");
    }

    // ── UpdateFunction (§4.2) ─────────────────────────────────────────────────

    /// spec §8 P3: after the write, address/applicationId/owner/runtime are
    /// byte-identical; only description + status moved, in ONE event.
    @Test
    void updateAppliesDescriptionAndStatusAndLeavesIdentityFieldsUntouched() {
        String appId = testApplication("update", "update-" + RUN);
        FunctionCreated created = runAsAnchor(CreateFunction.of(functions, applications, clients),
                new CreateCommand("update-" + RUN, "svc", "fn", "jvm", "before", null));
        FunctionAddress address = FunctionAddress.parse("update-" + RUN + ".svc.fn");
        Function before = reload(address);

        FunctionUpdated ev = runAsAnchor(UpdateFunction.of(functions, TriggerSync.none()), new UpdateCommand(address, "after", "DISABLED"));
        assertThat(ev.description()).isEqualTo("after");
        assertThat(ev.status()).isEqualTo("DISABLED");

        Function after = reload(address);
        assertThat(after.description()).isEqualTo("after");
        assertThat(after.status()).isEqualTo(FunctionStatus.DISABLED);
        assertThat(after.address()).as("address unchanged").isEqualTo(before.address());
        assertThat(after.applicationId()).as("applicationId unchanged").isEqualTo(appId).isEqualTo(before.applicationId());
        assertThat(after.owner()).as("owner unchanged").isEqualTo(before.owner());
        assertThat(after.runtime()).as("runtime unchanged").isEqualTo(before.runtime());

        // P5: exactly one updated event, the function's message group, and one audit row.
        var updatedEvents = eventsFor("platform.function." + created.functionId(), FunctionEvents.UPDATED);
        assertThat(updatedEvents).hasSize(1);
        assertThat(updatedEvents.getFirst().get("message_group")).as("P5 mutant guard: wrong message group")
                .isEqualTo("platform:function:" + created.functionId());
        assertThat(auditsFor(created.functionId(), "UpdateCommand")).hasSize(1);
    }

    @Test
    void updateOfAnAbsentAddressIs404() {
        assertUseCaseError(() -> runAsAnchor(UpdateFunction.of(functions, TriggerSync.none()),
                        new UpdateCommand(FunctionAddress.parse("nosuch-" + RUN + ".svc.fn"), "x", null)),
                UseCaseError.NotFound.class, "Function_NOT_FOUND");
    }

    /// spec §8 P2, write clause, end to end through the operation (the three
    /// clauses themselves are `AccessTest`'s job).
    @Test
    void updateOfAFunctionOutOfReachIs404NotForbidden() {
        String clientId = testClient("update-reach");
        testApplication("update-reach", "updatereach-" + RUN);
        runAsAnchor(CreateFunction.of(functions, applications, clients),
                new CreateCommand("updatereach-" + RUN, "svc", "fn", "jvm", null, clientId));
        FunctionAddress address = FunctionAddress.parse("updatereach-" + RUN + ".svc.fn");

        AuthContext otherClient = new AuthContext("usr_other2", Scope.CLIENT, null, List.of("clt_notthisone"),
                List.of(), List.of(), true, List.of());
        assertUseCaseError(() -> runAs(otherClient, UpdateFunction.of(functions, TriggerSync.none()), new UpdateCommand(address, "x", null)),
                UseCaseError.NotFound.class, "Function_NOT_FOUND");
    }

    @Test
    void updateStatusRejectsAnUnknownValue() {
        testApplication("badstatus", "badstatus-" + RUN);
        runAsAnchor(CreateFunction.of(functions, applications, clients),
                new CreateCommand("badstatus-" + RUN, "svc", "fn", "jvm", null, null));
        FunctionAddress address = FunctionAddress.parse("badstatus-" + RUN + ".svc.fn");

        assertUseCaseError(() -> runAsAnchor(UpdateFunction.of(functions, TriggerSync.none()), new UpdateCommand(address, null, "BOGUS")),
                UseCaseError.Validation.class, "STATUS_INVALID");
    }

    // ── DeleteFunction (§4.2) ─────────────────────────────────────────────────

    @Test
    void deleteRemovesTheFunctionAndWritesOneEventAndOneAudit() {
        testApplication("delete", "delete-" + RUN);
        FunctionCreated created = runAsAnchor(CreateFunction.of(functions, applications, clients),
                new CreateCommand("delete-" + RUN, "svc", "fn", "jvm", null, null));
        FunctionAddress address = FunctionAddress.parse("delete-" + RUN + ".svc.fn");

        FunctionDeleted ev = runAsAnchor(DeleteFunction.of(functions, TriggerSync.none()), new DeleteCommand(address));
        assertThat(ev.functionId()).isEqualTo(created.functionId());
        assertThat(functions.findByAddress(address)).as("row is gone").isEmpty();

        // P5: exactly one deleted event, the function's message group, and one audit row.
        var deletedEvents = eventsFor("platform.function." + created.functionId(), FunctionEvents.DELETED);
        assertThat(deletedEvents).hasSize(1);
        assertThat(deletedEvents.getFirst().get("message_group")).as("P5 mutant guard: wrong message group")
                .isEqualTo("platform:function:" + created.functionId());
        assertThat(auditsFor(created.functionId(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteOfAFunctionOutOfReachIs404AndTheRowSurvives() {
        testApplication("delete-reach", "deletereach-" + RUN);
        runAsAnchor(CreateFunction.of(functions, applications, clients),
                new CreateCommand("deletereach-" + RUN, "svc", "fn", "jvm", null, null));
        FunctionAddress address = FunctionAddress.parse("deletereach-" + RUN + ".svc.fn");

        // Platform-owned function, non-anchor caller: clause 2.
        AuthContext nonAnchor = new AuthContext("usr_nonanchor", Scope.CLIENT, null, List.of("*"), List.of(), List.of(), true, List.of());
        assertUseCaseError(() -> runAs(nonAnchor, DeleteFunction.of(functions, TriggerSync.none()), new DeleteCommand(address)),
                UseCaseError.NotFound.class, "Function_NOT_FOUND");
        assertThat(functions.findByAddress(address)).as("refused delete leaves the row in place").isPresent();
    }

    // ── PutFunctionPolicy (§4.3) ──────────────────────────────────────────────

    @Test
    void putPolicyReplacesSignersAndCeilingsAndWritesEventAndAudit() {
        String clientId = testClient("policy");
        var signer = new PutPolicyCommand.SignerInput("https://issuer", "subject-1", List.of("jvm"));
        PolicyUpdated ev = runAsAnchor(PutFunctionPolicy.of(policies, clients),
                new PutPolicyCommand(FunctionOwner.ofClientId(clientId), List.of(signer), 1000, null, null, null));
        assertThat(ev.owner()).isEqualTo(clientId);
        assertThat(ev.signerCount()).isEqualTo(1);

        ClientPolicy saved = policies.findByOwner(FunctionOwner.ofClientId(clientId)).orElseThrow();
        assertThat(saved.signers()).hasSize(1);
        assertThat(saved.maxDurationMs()).isEqualTo(1000);

        // P5: message group + audit — the platform's key (never null, spec §4.3).
        // Item 0 (spec §3's parenthesis): the policy event's subject/group are
        // `function-policy`, not `function` — a policy is not itself a function.
        assertThat(eventsFor("platform.function-policy." + clientId, FunctionEvents.POLICY_UPDATED)).hasSize(1);
        assertThat(eventsFor("platform.function-policy." + clientId, FunctionEvents.POLICY_UPDATED).getFirst().get("message_group"))
                .as("P5 mutant guard: wrong message group").isEqualTo("platform:function-policy:" + clientId);
        assertThat(auditsFor(clientId, "PutPolicyCommand")).hasSize(1);
    }

    /// spec §4.3, §9 (rulings): the platform's own policy audits with a
    /// non-null entity id — `FunctionOwner.PLATFORM_KEY`, never `null`.
    @Test
    void putPolicyForThePlatformAuditsWithTheReservedKeyNotNull() {
        runAsAnchor(PutFunctionPolicy.of(policies, clients),
                new PutPolicyCommand(new FunctionOwner.Platform(), List.of(), null, null, null, null));

        assertThat(auditsFor("PLATFORM", "PutPolicyCommand")).as("platform audit entity id is never null").hasSize(1);
    }

    @Test
    void putPolicyRejectsInvalidSignersAndCeilings() {
        FunctionOwner platform = new FunctionOwner.Platform();
        assertUseCaseError(() -> runAsAnchor(PutFunctionPolicy.of(policies, clients),
                        new PutPolicyCommand(platform, List.of(new PutPolicyCommand.SignerInput("", "sub", List.of("jvm"))),
                                null, null, null, null)),
                UseCaseError.Validation.class, "SIGNER_INVALID");

        assertUseCaseError(() -> runAsAnchor(PutFunctionPolicy.of(policies, clients),
                        new PutPolicyCommand(platform, List.of(new PutPolicyCommand.SignerInput("iss", "sub", List.of())),
                                null, null, null, null)),
                UseCaseError.Validation.class, "RUNTIME_INVALID");

        assertUseCaseError(() -> runAsAnchor(PutFunctionPolicy.of(policies, clients),
                        new PutPolicyCommand(platform, List.of(new PutPolicyCommand.SignerInput("iss", "sub", List.of("bogus"))),
                                null, null, null, null)),
                UseCaseError.Validation.class, "RUNTIME_INVALID");

        assertUseCaseError(() -> runAsAnchor(PutFunctionPolicy.of(policies, clients),
                        new PutPolicyCommand(platform, List.of(
                                new PutPolicyCommand.SignerInput("iss", "sub", List.of("jvm")),
                                new PutPolicyCommand.SignerInput("iss", "sub", List.of("wasm"))),
                                null, null, null, null)),
                UseCaseError.Validation.class, "SIGNER_DUPLICATE");

        assertUseCaseError(() -> runAsAnchor(PutFunctionPolicy.of(policies, clients),
                        new PutPolicyCommand(platform, List.of(), 0, null, null, null)),
                UseCaseError.Validation.class, "CEILING_INVALID");
        assertUseCaseError(() -> runAsAnchor(PutFunctionPolicy.of(policies, clients),
                        new PutPolicyCommand(platform, List.of(), -5, null, null, null)),
                UseCaseError.Validation.class, "CEILING_INVALID");
    }

    @Test
    void putPolicyForAMissingClientIs404() {
        assertUseCaseError(() -> runAsAnchor(PutFunctionPolicy.of(policies, clients),
                        new PutPolicyCommand(FunctionOwner.ofClientId("clt_doesnotexist" + RUN), List.of(), null, null, null, null)),
                UseCaseError.NotFound.class, "Client_NOT_FOUND");
    }

    /// A rule scoped to WASM only does not permit a JVM signer — exercises
    /// the runtime `Set` actually round-trips through `PutFunctionPolicy`.
    @Test
    void putPolicySignerRuntimesRoundTrip() {
        String clientId = testClient("policy-runtimes");
        runAsAnchor(PutFunctionPolicy.of(policies, clients),
                new PutPolicyCommand(FunctionOwner.ofClientId(clientId),
                        List.of(new PutPolicyCommand.SignerInput("iss", "sub", List.of("wasm"))), null, null, null, null));
        ClientPolicy saved = policies.findByOwner(FunctionOwner.ofClientId(clientId)).orElseThrow();
        assertThat(saved.signers().getFirst().runtimes()).containsExactly(io.flowcatalyst.platform.function.Runtime.WASM);
    }
}
