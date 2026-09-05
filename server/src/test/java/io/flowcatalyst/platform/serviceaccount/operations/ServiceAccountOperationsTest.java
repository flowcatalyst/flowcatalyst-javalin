package io.flowcatalyst.platform.serviceaccount.operations;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.serviceaccount.CorruptServiceAccountException;
import io.flowcatalyst.platform.serviceaccount.RoleAssignment;
import io.flowcatalyst.platform.serviceaccount.ServiceAccount;
import io.flowcatalyst.platform.serviceaccount.ServiceAccountRepository;
import io.flowcatalyst.platform.serviceaccount.WebhookAuthType;
import io.flowcatalyst.platform.serviceaccount.WebhookCredentials;
import io.flowcatalyst.platform.serviceaccount.operations.ServiceAccountEvents.ServiceAccountCreated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Decryption;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.TxOperation;
import io.flowcatalyst.testpg.TestPg;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_CLIENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The service-account use cases against the embedded Postgres (spec §4-9,
/// §11): validation, persistence, the envelope's `msg_events` + `aud_logs`
/// guarantee, roles hydration, encryption at rest, and the compare-and-set
/// legacy-plaintext upgrade. The pure transition rules are covered by
/// `ServiceAccountTest`; here each operation is exercised once through the
/// envelope (or, for `CreateServiceAccountWithCredentials`, through
/// `TxOperation.run`).
///
/// The fixture never truncates, so every test owns its rows: codes are
/// namespaced by a per-JVM suffix.
class ServiceAccountOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final Encryption ENCRYPTION = Encryption.withKey(Encryption.generateKey());
    private static final ServiceAccountRepository repo = new ServiceAccountRepository(DS, Optional.of(ENCRYPTION));
    private static final PrincipalRepository principals = new PrincipalRepository(DS);
    private static final OAuthClientRepository oauthClients = new OAuthClientRepository(DS, new ApplicationRepository(DS));
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String ACTOR = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(ACTOR, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(ACTOR);

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static <C, R> R runAsAnchorTx(TxOperation<C, R> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static String code(String tag) {
        return tag + "-" + RUN;
    }

    private static CreateServiceAccountWithCredentials.Result createWithCredentials(String code, String name, String applicationId) {
        return runAsAnchorTx(CreateServiceAccountWithCredentials.of(repo, principals, oauthClients, Optional.of(ENCRYPTION)),
                new CreateCommand(code, name, null, null, null, applicationId, null));
    }

    private static ServiceAccount reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("service account " + id + " not found"));
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String errorCode) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(errorCode);
                });
    }

    private static Result<Record> eventsFor(String serviceAccountId, String type) {
        return DB.fetch("SELECT type, subject, source, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                ServiceAccountEvents.subjectFor(serviceAccountId), type);
    }

    private static Result<Record> auditsFor(String serviceAccountId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                serviceAccountId, operation);
    }

    private static Result<Record> eventsForSubject(String subject, String type) {
        return DB.fetch("SELECT type, subject, source, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                subject, type);
    }

    private static Optional<String> decrypt(String ref) {
        return ENCRYPTION.decrypt(ref) instanceof Decryption.Plaintext(var pt) ? Optional.of(pt) : Optional.empty();
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowThePrincipalTheEventAndTheAuditTogether() {
        String c = code("sacreate");
        var res = createWithCredentials(c, "  Create Happy  ", null);

        assertThat(res.serviceAccount().id()).startsWith("sac_");
        assertThat(res.serviceAccount().code()).isEqualTo(c);
        assertThat(res.serviceAccount().name()).isEqualTo("Create Happy");
        assertThat(res.principalId()).startsWith("prn_");
        assertThat(res.authToken()).isNotBlank();
        assertThat(res.signingSecret()).isNotBlank();

        var got = reload(res.serviceAccount().id());
        assertThat(got.active()).isTrue();
        assertThat(got.webhookCredentials().authType()).isEqualTo(WebhookAuthType.BEARER_TOKEN);
        assertThat(got.webhookCredentials().token()).isEqualTo(res.authToken());
        assertThat(got.webhookCredentials().signingSecret()).isEqualTo(res.signingSecret());

        var principal = principals.findByServiceAccount(got.id()).orElseThrow();
        assertThat(principal.id()).isEqualTo(res.principalId());
        assertThat(principal.isService()).isTrue();
        assertThat(principal.allApplications()).as("no applicationId given -> unconfined").isTrue();

        var events = eventsFor(got.id(), ServiceAccountEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat((String) events.getFirst().get("deduplication_id"))
                .as("dedup id ties the row to its type").startsWith(ServiceAccountEvents.CREATED + "-");
        assertThat(events.getFirst().get("source")).isEqualTo(ServiceAccountEvents.SOURCE);

        var audits = auditsFor(got.id(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Serviceaccount");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(ACTOR);
    }

    @Test
    void createConfinesThePrincipalToTheGivenApplication() {
        String appId = EntityType.APPLICATION.generate();
        var res = createWithCredentials(code("sascoped"), "Scoped", appId);

        var principal = principals.findByServiceAccount(res.serviceAccount().id()).orElseThrow();
        assertThat(principal.allApplications()).isFalse();
        assertThat(principal.accessibleApplicationIds()).containsExactly(appId);
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("empty code", new CreateCommand("", "X", null, null, null, null, null), "CODE_REQUIRED"),
                Arguments.of("underscore code", new CreateCommand("bad_code", "X", null, null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("digit-leading code", new CreateCommand("9digit", "X", null, null, null, null, null), "INVALID_CODE_FORMAT"),
                Arguments.of("blank name", new CreateCommand(code("sacreatebad"), " ", null, null, null, null, null), "NAME_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchorTx(CreateServiceAccountWithCredentials.of(repo, principals, oauthClients, Optional.of(ENCRYPTION)), cmd),
                UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void createRejectsADuplicateCode() {
        String c = code("sadup");
        createWithCredentials(c, "First", null);
        assertUseCaseError(() -> createWithCredentials(c, "Second", null), UseCaseError.Conflict.class, "CODE_EXISTS");
    }

    @Test
    void createLowersAndTrimsTheCode() {
        String c = ("  SACreateCase-" + RUN + "  ");
        var res = createWithCredentials(c, "Case", null);
        assertThat(res.serviceAccount().code()).isEqualTo(("sacreatecase-" + RUN).toLowerCase(Locale.ROOT));
    }

    // ── OAuth client (spec §4.1, §8; retires the auth-not-ported stub) ──────

    /// The client the account uses for `client_credentials` (spec §8): a
    /// real, persisted row scoped to the SERVICE principal, committed
    /// through the same envelope as the service account and principal above
    /// (an `OAuthClientCreated` event + its own audit row, not just "a row
    /// exists somewhere"). Mutant killers named per the port brief:
    ///   - `principalId` assertion kills "drop withPrincipalId on the client";
    ///   - the decrypt-round-trip assertion kills "return the secret ref
    ///     instead of the plaintext" (a leaked ref would fail to decrypt back
    ///     to itself, or would equal the disclosed value only by coincidence —
    ///     the explicit "stored ref != disclosed plaintext" assertion below
    ///     closes that loophole);
    ///   - the `msg_events` assertion kills "commit the client outside the
    ///     transaction" (bypassing `scoped.commit` for a bare `persist` call
    ///     writes the row but skips the event the envelope is responsible for).
    @Test
    void createMintsAConfidentialOAuthClientCommittedThroughTheSameEnvelopeAsTheAccount() {
        var res = createWithCredentials(code("saoauth"), "OAuth Me", null);

        OAuthClient oc = oauthClients.findById(res.oauthClientId()).orElseThrow();
        assertThat(oc.clientId()).isEqualTo(res.oauthClientClientId());
        assertThat(oc.clientName()).isEqualTo("OAuth Me Client");
        assertThat(oc.clientType()).isEqualTo(ClientType.CONFIDENTIAL);
        assertThat(oc.principalId()).as("scoped to the linked SERVICE principal, not the account")
                .isEqualTo(res.principalId());
        assertThat(oc.grantTypes()).containsExactlyInAnyOrder("client_credentials", "refresh_token");
        assertThat(oc.defaultScopes()).containsExactly("openid");

        String storedRef = DB.fetchOne(OAUTH_CLIENTS, OAUTH_CLIENTS.ID.eq(oc.id())).get(OAUTH_CLIENTS.CLIENT_SECRET_REF);
        assertThat(storedRef).as("the column holds ciphertext, not the disclosed secret")
                .startsWith("encrypted:").isNotEqualTo(res.oauthClientSecret());
        assertThat(decrypt(storedRef)).as("the disclosed plaintext round-trips through the stored ciphertext")
                .contains(res.oauthClientSecret());

        var events = eventsForSubject(OAuthClientEvents.subjectFor(oc.id()), OAuthClientEvents.CREATED);
        assertThat(events).as("the client's own creation event was committed in the same transaction").hasSize(1);
        assertThat(auditsFor(oc.id(), "CreateCommand")).as("and its own audit row").hasSize(1);
    }

    /// Atomicity (spec §4.1): a missing app key rolls back everything the
    /// operation touched, including the two aggregates written before the
    /// failing client-secret encryption — not just the client itself.
    @Test
    void createFailsAtomicallyWhenNoAppKeyIsConfigured() {
        String c = code("sanokey");
        String name = "NoKeyAtomicity-" + RUN;

        assertUseCaseError(() -> runAsAnchorTx(CreateServiceAccountWithCredentials.of(repo, principals, oauthClients, Optional.empty()),
                        new CreateCommand(c, name, null, null, null, null, null)),
                UseCaseError.Internal.class, "SECRET");

        assertThat(repo.findByCode(c)).as("no service account row survives the rollback").isEmpty();
        assertThat(DB.fetchCount(IAM_PRINCIPALS, IAM_PRINCIPALS.NAME.eq(name))).as("no principal row survives the rollback").isZero();
        assertThat(DB.fetchCount(OAUTH_CLIENTS, OAUTH_CLIENTS.CLIENT_NAME.eq(name + " Client"))).as("no oauth client row survives the rollback").isZero();
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateReplacesMutableFieldsButNotTheCode() {
        String c = code("saupd");
        var seeded = createWithCredentials(c, "Before", null).serviceAccount();

        var ev = runAsAnchor(UpdateServiceAccount.of(repo),
                new UpdateCommand(seeded.id(), "After", "after-desc", "anchor", List.of("clt_x"), null));
        assertThat(ev.name()).isEqualTo("After");

        var got = reload(seeded.id());
        assertThat(got.name()).isEqualTo("After");
        assertThat(got.description()).isEqualTo("after-desc");
        assertThat(got.scope()).isEqualTo("anchor");
        assertThat(got.clientIds()).containsExactly("clt_x");
        assertThat(got.code()).as("code is immutable").isEqualTo(c);

        assertThat(eventsFor(seeded.id(), ServiceAccountEvents.UPDATED)).hasSize(1);
        assertThat(auditsFor(seeded.id(), "UpdateCommand")).hasSize(1);
    }

    @Test
    void updateRejectsMissingIdBlankNameOrUnknownRow() {
        assertUseCaseError(() -> runAsAnchor(UpdateServiceAccount.of(repo), new UpdateCommand(null, "X", null, null, null, null)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateServiceAccount.of(repo), new UpdateCommand("sac_doesnotexist1", " ", null, null, null, null)),
                UseCaseError.Validation.class, "NAME_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateServiceAccount.of(repo), new UpdateCommand("sac_doesnotexist1", "X", null, null, null, null)),
                UseCaseError.NotFound.class, "ServiceAccount_NOT_FOUND");
    }

    // ── Deactivate ─────────────────────────────────────────────────────────

    @Test
    void deactivateMarksTheAccountInactive() {
        var seeded = createWithCredentials(code("sadeact"), "Deact", null).serviceAccount();
        runAsAnchor(DeactivateServiceAccount.of(repo), new DeactivateCommand(seeded.id()));
        assertThat(reload(seeded.id()).active()).isFalse();
        assertThat(eventsFor(seeded.id(), ServiceAccountEvents.DEACTIVATED)).hasSize(1);
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRow() {
        var seeded = createWithCredentials(code("sadel"), "Del", null).serviceAccount();
        runAsAnchor(DeleteServiceAccount.of(repo), new DeleteCommand(seeded.id()));
        assertThat(repo.findById(seeded.id())).isEmpty();
        assertThat(eventsFor(seeded.id(), ServiceAccountEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(seeded.id(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteRejectsMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(DeleteServiceAccount.of(repo), new DeleteCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteServiceAccount.of(repo), new DeleteCommand("sac_doesnotexist1")),
                UseCaseError.NotFound.class, "ServiceAccount_NOT_FOUND");
    }

    // ── Assign roles ───────────────────────────────────────────────────────

    /// Pins Fix 1 (spec §9.1, §11): the account object's `roles` and the
    /// `/roles` sub-route's roles must be the SAME set, because both are read
    /// through the same hydration path — not merely both non-empty.
    @Test
    void assignRolesWritesThroughToThePrincipalAndAgreesWithTheAccountRead() {
        var seeded = createWithCredentials(code("saroles"), "Roles", null).serviceAccount();

        var ev = runAsAnchor(AssignRolesToServiceAccount.of(repo, principals),
                new AssignRolesCommand(seeded.id(), List.of("orders:admin", "orders:viewer")));
        assertThat(ev.rolesAdded()).containsExactlyInAnyOrder("orders:admin", "orders:viewer");
        assertThat(ev.rolesRemoved()).isEmpty();

        var fromAccount = reload(seeded.id()).roles().stream().map(RoleAssignment::roleName).toList();
        assertThat(fromAccount).containsExactlyInAnyOrder("orders:admin", "orders:viewer");

        var principal = principals.findByServiceAccount(seeded.id()).orElseThrow();
        assertThat(principal.roleNames()).as("the principal is where the role assignment actually lives")
                .containsExactlyInAnyOrder("orders:admin", "orders:viewer");

        // A second, different assignment reports the correct diff.
        var second = runAsAnchor(AssignRolesToServiceAccount.of(repo, principals),
                new AssignRolesCommand(seeded.id(), List.of("orders:viewer", "orders:auditor")));
        assertThat(second.rolesAdded()).containsExactly("orders:auditor");
        assertThat(second.rolesRemoved()).containsExactly("orders:admin");
        assertThat(reload(seeded.id()).roles().stream().map(RoleAssignment::roleName).toList())
                .containsExactlyInAnyOrder("orders:viewer", "orders:auditor");
    }

    @Test
    void assignRolesRejectsMissingIdOrUnknownAccount() {
        assertUseCaseError(() -> runAsAnchor(AssignRolesToServiceAccount.of(repo, principals), new AssignRolesCommand(" ", List.of())),
                UseCaseError.Validation.class, "SERVICE_ACCOUNT_ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(AssignRolesToServiceAccount.of(repo, principals), new AssignRolesCommand("sac_doesnotexist1", List.of())),
                UseCaseError.NotFound.class, "ServiceAccount_NOT_FOUND");
    }

    // ── Regenerate token / secret: the caller-owned sink (spec §5) ──────────

    @Test
    void regenerateAuthTokenDisclosesThePlaintextOnceAndRotatesTheColumn() {
        var seeded = createWithCredentials(code("saregtok"), "RegTok", null).serviceAccount();
        String originalToken = seeded.webhookCredentials().token();

        var disclosed = new AtomicReference<String>();
        var ev = runAsAnchor(RegenerateAuthToken.of(repo, disclosed::set), new RegenerateAuthTokenCommand(seeded.id()));
        assertThat(ev.code()).isEqualTo(seeded.code());

        assertThat(disclosed.get()).as("the sink received the plaintext exactly once").isNotBlank();
        assertThat(disclosed.get()).isNotEqualTo(originalToken);

        var got = reload(seeded.id());
        assertThat(got.webhookCredentials().token()).isEqualTo(disclosed.get());
        assertThat(got.webhookCredentials().authType()).isEqualTo(WebhookAuthType.BEARER_TOKEN);
    }

    /// A rejected request never reaches the minting path — the sink stays
    /// untouched. This is the structural property the caller-owned sink is
    /// FOR (spec §5): a stash-based design could not make this guarantee.
    @Test
    void regenerateAuthTokenNeverDisclosesOnAValidationFailure() {
        var disclosed = new AtomicReference<String>();
        assertUseCaseError(() -> runAsAnchor(RegenerateAuthToken.of(repo, disclosed::set), new RegenerateAuthTokenCommand(" ")),
                UseCaseError.Validation.class, "SERVICE_ACCOUNT_ID_REQUIRED");
        assertThat(disclosed.get()).as("nothing was minted for a rejected request").isNull();
    }

    /// The not-found case specifically: minting happens strictly after the
    /// load succeeds, so an unknown id must never reach the sink either.
    @Test
    void regenerateAuthTokenNeverDisclosesForAnUnknownAccount() {
        var disclosed = new AtomicReference<String>();
        assertUseCaseError(() -> runAsAnchor(RegenerateAuthToken.of(repo, disclosed::set), new RegenerateAuthTokenCommand("sac_doesnotexist1")),
                UseCaseError.NotFound.class, "ServiceAccount_NOT_FOUND");
        assertThat(disclosed.get()).as("nothing was minted for an unknown account").isNull();
    }

    @Test
    void regenerateSigningSecretDisclosesThePlaintextOnceAndLeavesAuthTypeAlone() {
        var seeded = createWithCredentials(code("saregsec"), "RegSec", null).serviceAccount();
        String originalSecret = seeded.webhookCredentials().signingSecret();

        var disclosed = new AtomicReference<String>();
        runAsAnchor(RegenerateSigningSecret.of(repo, disclosed::set), new RegenerateSigningSecretCommand(seeded.id()));

        assertThat(disclosed.get()).isNotBlank().isNotEqualTo(originalSecret);
        var got = reload(seeded.id());
        assertThat(got.webhookCredentials().signingSecret()).isEqualTo(disclosed.get());
        assertThat(got.webhookCredentials().authType()).as("authType untouched by a secret-only rotation")
                .isEqualTo(WebhookAuthType.BEARER_TOKEN);
    }

    // ── Encryption at rest (spec §11, Go fix 3) ─────────────────────────────

    @Test
    void webhookCredentialsAreEncryptedAtRestButDecryptTransparently() {
        var res = createWithCredentials(code("saenc"), "Enc", null);

        String storedToken = DB.fetchOne("SELECT wh_auth_token_ref FROM iam_service_accounts WHERE id = ?", res.serviceAccount().id())
                .get(0, String.class);
        String storedSecret = DB.fetchOne("SELECT wh_signing_secret_ref FROM iam_service_accounts WHERE id = ?", res.serviceAccount().id())
                .get(0, String.class);

        assertThat(storedToken).as("the column must hold ciphertext, not the bearer").startsWith("encrypted:");
        assertThat(storedToken).doesNotContain(res.authToken());
        assertThat(storedSecret).as("the column must hold ciphertext, not the signing key").startsWith("encrypted:");
        assertThat(storedSecret).doesNotContain(res.signingSecret());

        var loaded = reload(res.serviceAccount().id());
        assertThat(loaded.webhookCredentials().token()).isEqualTo(res.authToken());
        assertThat(loaded.webhookCredentials().signingSecret()).isEqualTo(res.signingSecret());
    }

    // Legacy plaintext upgrade + its compare-and-set race are pinned in
    // `ServiceAccountRepositoryTest` (same package as the repository, needed
    // for the race test's synthetic "as seen" seam).

    // ── Corrupt stored auth type (spec §11, X-06) ───────────────────────────
    // The single-row case (`findById`) is pinned in `ServiceAccountRepositoryTest`.

    /// X-06's list ruling explicitly: "a list containing the row fails too" —
    /// one corrupt row must not be silently skipped while a good row nearby
    /// still comes back with 200 OK.
    @Test
    void findAllFailsTheWholeListOnOneCorruptRow() {
        var good = createWithCredentials(code("sacorruptgood"), "Good", null).serviceAccount();
        String badId = EntityType.SERVICE_ACCOUNT.generate();
        TestPg.withConstraintDropped(DS, "iam_service_accounts", "chk_iam_service_accounts_wh_auth_type", () -> {
            DB.insertInto(DSL.table("iam_service_accounts"),
                            DSL.field("id"), DSL.field("code"), DSL.field("name"), DSL.field("active"), DSL.field("wh_auth_type"))
                    .values(badId, "corrupt-list-" + RUN, "Bad", true, "NOT_A_REAL_AUTH_TYPE")
                    .execute();
            try {
                assertThatThrownBy(repo::findAll).isInstanceOf(CorruptServiceAccountException.class);
            } finally {
                DB.deleteFrom(DSL.table("iam_service_accounts")).where(DSL.field("id", String.class).eq(badId)).execute();
            }
        });
        assertThat(repo.findById(good.id())).as("the good row is unaffected").isPresent();
    }

    // ── Last-used stamping (spec §9.2) ──────────────────────────────────────

    @Test
    void touchLastUsedStampsTheColumnAndIsBestEffortOnAnUnknownId() {
        var seeded = createWithCredentials(code("salast"), "Last", null).serviceAccount();
        assertThat(reload(seeded.id()).lastUsedAt()).isNull();

        repo.touchLastUsed(seeded.id());
        assertThat(reload(seeded.id()).lastUsedAt()).isNotNull();

        // An unknown id updates no row and must not throw.
        repo.touchLastUsed("sac_does_not_exist1");
        repo.touchLastUsed(null);
    }

    // ── Mint token (spec §8) ─────────────────────────────────────────────────

    @Test
    void mintTokenComputesTheGrantFromThePrincipalsRolesAndApplications() {
        String appId = "app_" + code("samint");
        var res = createWithCredentials(code("samint"), "Mint", appId);
        runAsAnchor(AssignRolesToServiceAccount.of(repo, principals), new AssignRolesCommand(res.serviceAccount().id(), List.of("orders:admin")));

        var keys = SigningKeys.generateEphemeral();
        var minter = new RsaServiceAccountTokenMinter(keys, "https://fc.test", "https://fc.test");
        var result = MintServiceAccountToken.mint(repo, principals, minter,
                roleNames -> roleNames.contains("orders:admin") ? List.of("platform:events:create") : List.of(),
                res.serviceAccount().id());

        assertThat(result.permissions()).containsExactly("platform:events:create");
        assertThat(result.accessToken()).isNotBlank();

        // The token really carries that scope and this account's application, verified
        // with a real RS256 signature check against the platform key (JwtVerifier),
        // not merely re-parsed unsigned.
        var verifier = new JwtVerifier(new JwtVerifier.Config("https://fc.test", "https://fc.test",
                new JwtVerifier.RsaKeys(keys.publicKey())));
        var verified = verifier.verify(result.accessToken());
        assertThat(verified).isInstanceOf(JwtVerifier.Verified.class);
        var claims = ((JwtVerifier.Verified) verified).claims();
        assertThat(claims.subject()).isEqualTo(res.principalId());
        assertThat(claims.permissions()).containsExactly("platform:events:create");
        assertThat(claims.applications()).as("an app-scoped account's token must carry that application").containsExactly(appId);
        assertThat(claims.tokenUse()).isEqualTo("api");
    }

    @Test
    void mintTokenRefusesADeactivatedAccount() {
        var res = createWithCredentials(code("samintinact"), "MintInactive", null);
        runAsAnchor(DeactivateServiceAccount.of(repo), new DeactivateCommand(res.serviceAccount().id()));

        var minter = new RsaServiceAccountTokenMinter(SigningKeys.generateEphemeral(), "https://fc.test", null);
        assertThatThrownBy(() -> MintServiceAccountToken.mint(repo, principals, minter, null, res.serviceAccount().id()))
                .isInstanceOf(UseCaseException.class)
                .satisfies(t -> {
                    var err = ((UseCaseException) t).error();
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("SERVICE_ACCOUNT_INACTIVE");
                    assertThat(err.message()).contains("the service account is deactivated");
                });
    }

    /// Spec §8 step 6: a deactivated *principal* is a distinct case from a
    /// deactivated *account* — same code, a DIFFERENT message, so the two are
    /// distinguishable in logs. If the mint ever collapsed both onto one
    /// message, this is the test that would go quiet.
    @Test
    void mintTokenRefusesADeactivatedPrincipalWithADifferentMessage() {
        var res = createWithCredentials(code("samintprininact"), "MintPrincipalInactive", null);
        var principal = principals.findByServiceAccount(res.serviceAccount().id()).orElseThrow();
        uow.inTransaction(tx -> {
            principals.persist(principal.deactivate(), tx.dbTx());
            return null;
        });

        var minter = new RsaServiceAccountTokenMinter(SigningKeys.generateEphemeral(), "https://fc.test", null);
        assertThatThrownBy(() -> MintServiceAccountToken.mint(repo, principals, minter, null, res.serviceAccount().id()))
                .isInstanceOf(UseCaseException.class)
                .satisfies(t -> {
                    var err = ((UseCaseException) t).error();
                    assertThat(err).isInstanceOf(UseCaseError.Validation.class);
                    assertThat(err.code()).isEqualTo("SERVICE_ACCOUNT_INACTIVE");
                    assertThat(err.message()).as("a different message under the same code")
                            .contains("the service account's principal is deactivated");
                });
    }

    @Test
    void mintTokenFailsWith500WhenNoMinterIsWired() {
        var res = createWithCredentials(code("samintnowire"), "NoWire", null);
        assertUseCaseError(() -> MintServiceAccountToken.mint(repo, principals, null, null, res.serviceAccount().id()),
                UseCaseError.Internal.class, "TOKEN");
    }
}
