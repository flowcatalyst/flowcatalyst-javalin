package io.flowcatalyst.platform.oauthclient.operations;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.oauthclient.ClientType;
import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.flowcatalyst.platform.oauthclient.OAuthClientRepository;
import io.flowcatalyst.platform.oauthclient.operations.OAuthClientEvents.OAuthClientCreated;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The OAuth-client use cases against embedded Postgres (spec
/// `auth-core.md` §6.3; A-22 `docs/improvements.md`): validation, the
/// anchor-only design (enforced at the handler — verified in
/// `OAuthClientApiTest`; operations themselves declare `Authorize.publicAccess()`),
/// persistence, and the envelope's guarantee that an aggregate write lands
/// with its `msg_events` + `aud_logs` rows. The pure transition rules are
/// covered by `OAuthClientTest`; here each operation is exercised once
/// through the envelope.
@SuppressWarnings("deprecation")
class OAuthClientOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final OAuthClientRepository repo = new OAuthClientRepository(DS, new ApplicationRepository(DS));
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final Optional<Encryption> ENCRYPTION = Optional.of(Encryption.withKey(Encryption.generateKey()));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static String clientName(String tag) {
        return "oc-" + tag + "-" + RUN;
    }

    private static AtomicReference<String> secretSink() {
        return new AtomicReference<>();
    }

    private static OAuthClientCreated createPublic(String tag) {
        return runAsAnchor(CreateOAuthClient.of(repo, ENCRYPTION, secretSink()::set),
                new CreateCommand(null, clientName(tag), "PUBLIC", null, null, null, null, null, null, null, null, null, null));
    }

    private static OAuthClient reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("oauth client " + id + " not found"));
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

    private static Result<Record> eventsFor(String oauthClientId, String type) {
        return DB.fetch("SELECT type, subject, source, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                OAuthClientEvents.subjectFor(oauthClientId), type);
    }

    private static Result<Record> auditsFor(String oauthClientId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                oauthClientId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditTogether() {
        var secret = secretSink();
        var ev = runAsAnchor(CreateOAuthClient.of(repo, ENCRYPTION, secret::set), new CreateCommand(
                null, clientName("create"), "CONFIDENTIAL", List.of("https://a.example/cb"), null,
                List.of("authorization_code"), List.of("read"), null, null, null, null, null, null));

        assertThat(ev.oauthClientId()).startsWith("oac_");
        assertThat(ev.clientName()).isEqualTo(clientName("create"));
        assertThat(ev.eventType()).isEqualTo(OAuthClientEvents.CREATED);
        assertThat(ev.source()).isEqualTo(OAuthClientEvents.SOURCE);
        assertThat(ev.subject()).isEqualTo(OAuthClientEvents.subjectFor(ev.oauthClientId()));

        var got = reload(ev.oauthClientId());
        assertThat(got.clientType()).isEqualTo(ClientType.CONFIDENTIAL);
        assertThat(got.redirectUris()).containsExactly("https://a.example/cb");
        assertThat(got.grantTypes()).containsExactly("authorization_code");
        assertThat(got.defaultScopes()).containsExactly("read");
        assertThat(got.secretRef()).as("a CONFIDENTIAL client is minted a secret").startsWith("encrypted:");
        assertThat(secret.get()).as("plaintext disclosed once").isNotBlank();
        assertThat(got.acceptsSecret(secret.get(), java.time.Instant.now(),
                ref -> ENCRYPTION.get().decrypt(ref) instanceof io.flowcatalyst.platform.shared.encryption.Decryption.Plaintext(var pt)
                        ? Optional.of(pt) : Optional.empty()))
                .as("the disclosed plaintext round-trips through the stored ciphertext").isTrue();

        var events = eventsFor(ev.oauthClientId(), OAuthClientEvents.CREATED);
        assertThat(events).hasSize(1);
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("oauthClientId").asText()).isEqualTo(ev.oauthClientId());
        assertThat(data.get("clientName").asText()).isEqualTo(clientName("create"));

        var audits = auditsFor(ev.oauthClientId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
    }

    @Test
    void createDefaultsToPublicShapeWithNoSecretAndPkceRequired() {
        var ev = createPublic("defaults");
        var got = reload(ev.oauthClientId());
        assertThat(got.clientType()).isEqualTo(ClientType.PUBLIC);
        assertThat(got.secretRef()).as("no secret is minted for PUBLIC").isNull();
        assertThat(got.pkceRequired()).as("pkceRequired defaults true").isTrue();
        assertThat(got.active()).isTrue();
    }

    @Test
    void createRejectsAMissingNameOrInvalidClientType() {
        assertUseCaseError(() -> runAsAnchor(CreateOAuthClient.of(repo, ENCRYPTION, secretSink()::set),
                        new CreateCommand(null, " ", "PUBLIC", null, null, null, null, null, null, null, null, null, null)),
                UseCaseError.Validation.class, "CLIENT_NAME_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(CreateOAuthClient.of(repo, ENCRYPTION, secretSink()::set),
                        new CreateCommand(null, clientName("badtype"), "BOGUS", null, null, null, null, null, null, null, null, null, null)),
                UseCaseError.Validation.class, "INVALID_CLIENT_TYPE");
    }

    @Test
    void createRejectsADuplicateClientId() {
        var first = createPublic("dupid");
        var got = reload(first.oauthClientId());
        assertUseCaseError(() -> runAsAnchor(CreateOAuthClient.of(repo, ENCRYPTION, secretSink()::set),
                        new CreateCommand(got.clientId(), clientName("dupid2"), "PUBLIC", null, null, null, null, null, null, null, null, null, null)),
                UseCaseError.Conflict.class, "CLIENT_ID_EXISTS");
    }

    @Test
    void createRejectsThePortalApiAccessCombination() {
        assertUseCaseError(() -> runAsAnchor(CreateOAuthClient.of(repo, ENCRYPTION, secretSink()::set),
                        new CreateCommand(null, clientName("conflict"), "PUBLIC", null, null, null, null, null, null,
                                null, null, "cli_portal_owner", true)),
                UseCaseError.Validation.class, "PORTAL_API_ACCESS_CONFLICT");
    }

    @Test
    void createFailsWithSecretWhenNoEncryptionIsConfigured() {
        assertUseCaseError(() -> runAsAnchor(CreateOAuthClient.of(repo, Optional.empty(), secretSink()::set),
                        new CreateCommand(null, clientName("nokey"), "CONFIDENTIAL", null, null, null, null, null, null, null, null, null, null)),
                UseCaseError.Internal.class, "SECRET");
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateAppliesFieldsAndIsAudited() {
        var seeded = createPublic("update");
        var ev = runAsAnchor(UpdateOAuthClient.of(repo), new UpdateCommand(seeded.oauthClientId(), "Renamed",
                List.of("https://new.example"), null, null, null, null, null, null, null, null));
        assertThat(ev.clientName()).isEqualTo("Renamed");
        assertThat(ev.eventType()).isEqualTo(OAuthClientEvents.UPDATED);

        var got = reload(seeded.oauthClientId());
        assertThat(got.clientName()).isEqualTo("Renamed");
        assertThat(got.redirectUris()).containsExactly("https://new.example");
        assertThat(auditsFor(seeded.oauthClientId(), "UpdateCommand")).hasSize(1);
    }

    @Test
    void updateRejectsMissingIdBlankNameOrMissingRow() {
        assertUseCaseError(() -> runAsAnchor(UpdateOAuthClient.of(repo), new UpdateCommand(
                        null, "X", null, null, null, null, null, null, null, null, null)),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateOAuthClient.of(repo), new UpdateCommand(
                        "oac_doesnotexist1", " ", null, null, null, null, null, null, null, null, null)),
                UseCaseError.Validation.class, "CLIENT_NAME_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(UpdateOAuthClient.of(repo), new UpdateCommand(
                        "oac_doesnotexist1", "X", null, null, null, null, null, null, null, null, null)),
                UseCaseError.NotFound.class, "OAuthClient_NOT_FOUND");
    }

    @Test
    void updateRejectsThePortalApiAccessCombination() {
        var seeded = createPublic("updconflict");
        assertUseCaseError(() -> runAsAnchor(UpdateOAuthClient.of(repo), new UpdateCommand(
                        seeded.oauthClientId(), null, null, null, null, null, null, null, null, "cli_owner", true)),
                UseCaseError.Validation.class, "PORTAL_API_ACCESS_CONFLICT");
    }

    // ── Activate / Deactivate ────────────────────────────────────────────────

    @Test
    void activateAndDeactivateAreIdempotentAndAudited() {
        var seeded = createPublic("lifecycle");

        var deactivated = runAsAnchor(DeactivateOAuthClient.of(repo), new DeactivateCommand(seeded.oauthClientId()));
        assertThat(deactivated.eventType()).isEqualTo(OAuthClientEvents.DEACTIVATED);
        assertThat(reload(seeded.oauthClientId()).active()).isFalse();

        // A second deactivate does not error.
        runAsAnchor(DeactivateOAuthClient.of(repo), new DeactivateCommand(seeded.oauthClientId()));
        assertThat(auditsFor(seeded.oauthClientId(), "DeactivateCommand")).hasSize(2);

        var activated = runAsAnchor(ActivateOAuthClient.of(repo), new ActivateCommand(seeded.oauthClientId()));
        assertThat(activated.eventType()).isEqualTo(OAuthClientEvents.ACTIVATED);
        assertThat(reload(seeded.oauthClientId()).active()).isTrue();
        assertThat(auditsFor(seeded.oauthClientId(), "ActivateCommand")).hasSize(1);
    }

    @Test
    void activateAndDeactivateRejectMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(ActivateOAuthClient.of(repo), new ActivateCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(ActivateOAuthClient.of(repo), new ActivateCommand("oac_doesnotexist1")),
                UseCaseError.NotFound.class, "OAuthClient_NOT_FOUND");
        assertUseCaseError(() -> runAsAnchor(DeactivateOAuthClient.of(repo), new DeactivateCommand("oac_doesnotexist1")),
                UseCaseError.NotFound.class, "OAuthClient_NOT_FOUND");
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowAndIsAudited() {
        var seeded = createPublic("delete");
        var ev = runAsAnchor(DeleteOAuthClient.of(repo), new DeleteCommand(seeded.oauthClientId()));
        assertThat(ev.oauthClientId()).isEqualTo(seeded.oauthClientId());
        assertThat(repo.findById(seeded.oauthClientId())).as("deleted row must be gone").isEmpty();
        assertThat(auditsFor(seeded.oauthClientId(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteRejectsMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(DeleteOAuthClient.of(repo), new DeleteCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteOAuthClient.of(repo), new DeleteCommand("oac_doesnotexist1")),
                UseCaseError.NotFound.class, "OAuthClient_NOT_FOUND");
    }

    // ── RotateSecret / RevokePreviousSecret (A-22) ──────────────────────────

    private static OAuthClientCreated createConfidential(String tag) {
        return runAsAnchor(CreateOAuthClient.of(repo, ENCRYPTION, secretSink()::set),
                new CreateCommand(null, clientName(tag), "CONFIDENTIAL", null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void rotateSecretKeepsThePreviousOneUsableForTheDefaultGraceAndIsAudited() {
        var seeded = createConfidential("rotate");
        String originalSecretRef = reload(seeded.oauthClientId()).secretRef();

        var secret = secretSink();
        var ev = runAsAnchor(RotateOAuthClientSecret.of(repo, ENCRYPTION, secret::set),
                new RotateSecretCommand(seeded.oauthClientId(), null));
        assertThat(ev.eventType()).isEqualTo(OAuthClientEvents.SECRET_ROTATED);
        assertThat(ev.previousSecretExpiresAt()).as("default 24h grace, not an immediate cutover").isNotNull();
        assertThat(secret.get()).isNotBlank();

        var got = reload(seeded.oauthClientId());
        assertThat(got.secretRef()).as("current secret changed").isNotEqualTo(originalSecretRef);
        assertThat(got.previousSecretRef()).isEqualTo(originalSecretRef);
        assertThat(auditsFor(seeded.oauthClientId(), "RotateSecretCommand")).hasSize(1);
    }

    @Test
    void rotateSecretWithZeroGraceIsAnImmediateCutoverOmittingTheExpiry() {
        var seeded = createConfidential("rotate0");
        var ev = runAsAnchor(RotateOAuthClientSecret.of(repo, ENCRYPTION, secretSink()::set),
                new RotateSecretCommand(seeded.oauthClientId(), 0L));
        assertThat(ev.previousSecretExpiresAt()).as("graceSeconds:0 keeps nothing").isNull();
        assertThat(reload(seeded.oauthClientId()).previousSecretRef()).isNull();
    }

    @Test
    void rotateSecretRejectsANegativeGraceAPublicClientOrAMissingRow() {
        var publicClient = createPublic("rotatepub");
        assertUseCaseError(() -> runAsAnchor(RotateOAuthClientSecret.of(repo, ENCRYPTION, secretSink()::set),
                        new RotateSecretCommand(publicClient.oauthClientId(), -1L)),
                UseCaseError.Validation.class, "GRACE_INVALID");
        assertUseCaseError(() -> runAsAnchor(RotateOAuthClientSecret.of(repo, ENCRYPTION, secretSink()::set),
                        new RotateSecretCommand(publicClient.oauthClientId(), null)),
                UseCaseError.Conflict.class, "NOT_CONFIDENTIAL");
        assertUseCaseError(() -> runAsAnchor(RotateOAuthClientSecret.of(repo, ENCRYPTION, secretSink()::set),
                        new RotateSecretCommand("oac_doesnotexist1", null)),
                UseCaseError.NotFound.class, "OAuthClient_NOT_FOUND");
    }

    @Test
    void revokePreviousSecretIsIdempotentAndAlwaysAudited() {
        var seeded = createConfidential("revoke");
        runAsAnchor(RotateOAuthClientSecret.of(repo, ENCRYPTION, secretSink()::set),
                new RotateSecretCommand(seeded.oauthClientId(), null));
        assertThat(reload(seeded.oauthClientId()).previousSecretRef()).isNotNull();

        var first = runAsAnchor(RevokeOAuthClientPreviousSecret.of(repo), new RevokePreviousSecretCommand(seeded.oauthClientId()));
        assertThat(first.dropped()).isTrue();
        assertThat(reload(seeded.oauthClientId()).previousSecretRef()).isNull();

        var second = runAsAnchor(RevokeOAuthClientPreviousSecret.of(repo), new RevokePreviousSecretCommand(seeded.oauthClientId()));
        assertThat(second.dropped()).as("idempotent: nothing left to revoke").isFalse();

        assertThat(auditsFor(seeded.oauthClientId(), "RevokePreviousSecretCommand"))
                .as("both calls are audited, whether or not anything changed").hasSize(2);
    }

    @Test
    void revokePreviousSecretRejectsMissingIdOrRow() {
        assertUseCaseError(() -> runAsAnchor(RevokeOAuthClientPreviousSecret.of(repo), new RevokePreviousSecretCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(RevokeOAuthClientPreviousSecret.of(repo), new RevokePreviousSecretCommand("oac_doesnotexist1")),
                UseCaseError.NotFound.class, "OAuthClient_NOT_FOUND");
    }

    private static tools.jackson.databind.JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
