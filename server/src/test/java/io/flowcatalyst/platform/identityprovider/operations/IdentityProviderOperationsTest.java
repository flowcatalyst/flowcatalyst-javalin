package io.flowcatalyst.platform.identityprovider.operations;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.emaildomainmapping.operations.EmailDomainMappingEvents;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Scope;
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
import java.util.UUID;
import java.util.stream.Stream;

import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPAL_ROLES;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDER_ALLOWED_ROLES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The identity-provider use cases against the embedded Postgres (spec
/// §4–8): validation, uniqueness, the domain orchestration against the real
/// mapping table, and the envelope's guarantee that every aggregate write
/// lands together with its `msg_events` and `aud_logs` rows. The pure rules
/// are covered by `IdentityProviderTest`; here each operation is exercised
/// once through the envelope.
///
/// The fixture never truncates, so every test owns its rows: codes and
/// domains are namespaced by a per-JVM suffix. The seeded `internal`
/// provider may or may not exist already — tests that need it ensure it.
@SuppressWarnings("deprecation")
class IdentityProviderOperationsTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final IdentityProviderRepository repo = new IdentityProviderRepository(DS);
    private static final EmailDomainMappingRepository mappings = new EmailDomainMappingRepository(DS);
    private static final UnitOfWork uow = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));

    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final String PRINCIPAL = EntityType.PRINCIPAL.generate();
    private static final AuthContext ANCHOR = new AuthContext(PRINCIPAL, Scope.ANCHOR, "anchor@x.io",
            List.of("*"), List.of(), List.of(), true, List.of());
    private static final ExecutionContext EC = ExecutionContext.of(PRINCIPAL);

    // ── Fixture ────────────────────────────────────────────────────────────

    private static <C, E extends DomainEvent> E runAsAnchor(Operation<C, E> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static <C, R> R runAsAnchor(TxOperation<C, R> op, C cmd) {
        return Auth.runAs(ANCHOR, () -> op.run(uow, cmd, EC));
    }

    private static String code(String tag) {
        return tag + "-" + RUN;
    }

    private static String domain(String tag) {
        return tag + "-" + RUN + ".example.com";
    }

    private static CreateCommand internalCommand(String code, String name) {
        return new CreateCommand(code, name, "INTERNAL", null, null, null, false, null, null, null, null, false, null);
    }

    /// `mappingScope` is derived for the fixture's convenience (never by
    /// production code, spec §4): ANCHOR when no client is given, CLIENT
    /// when one is, `null` when there are no domains to map.
    private static CreateCommand oidcCommand(String code, List<String> domains, String primaryClientId) {
        String scope = domains.isEmpty() ? null : (primaryClientId != null ? "CLIENT" : "ANCHOR");
        return new CreateCommand(code, code, "OIDC", "https://login." + code + ".example.com/v2.0", code + "-client-id",
                null, false, null, domains, scope, primaryClientId, false, null);
    }

    private static CreateResult createInternal(String code, String name) {
        return runAsAnchor(CreateIdentityProvider.of(repo, mappings), internalCommand(code, name));
    }

    private static CreateResult createOidc(String code, List<String> domains) {
        return runAsAnchor(CreateIdentityProvider.of(repo, mappings), oidcCommand(code, domains, null));
    }

    /// The seeded `internal` provider: found, or created through the public
    /// operation (race-tolerant against a parallel test doing the same).
    private static String ensureInternal() {
        return repo.findByCode(IdentityProvider.INTERNAL_CODE).map(IdentityProvider::id).orElseGet(() -> {
            try {
                return createInternal(IdentityProvider.INTERNAL_CODE, "Internal Authentication").identityProviderId();
            } catch (UseCaseException e) {
                return repo.findByCode(IdentityProvider.INTERNAL_CODE).orElseThrow().id();
            }
        });
    }

    private static IdentityProvider reload(String id) {
        return repo.findById(id).orElseThrow(() -> new AssertionError("identity provider " + id + " not found"));
    }

    private static EmailDomainMapping mapping(String domain) {
        return mappings.findByEmailDomain(domain).orElseThrow(() -> new AssertionError("mapping " + domain + " not found"));
    }

    private static UpdateCommand domainsOnly(String id, List<String> domains) {
        return new UpdateCommand(id, null, null, null, null, null, null, domains, null, null, null, null);
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

    private static JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    /// `msg_events` rows of one type on one subject.
    private static Result<Record> eventsOn(String subject, String type) {
        return DB.fetch("SELECT type, subject, source, message_group, data::text AS data, deduplication_id FROM msg_events WHERE subject = ? AND type = ?",
                subject, type);
    }

    /// `aud_logs` rows for one entity and command.
    private static Result<Record> auditsFor(String entityId, String operation) {
        return DB.fetch("SELECT entity_type, entity_id, operation, operation_json::text AS operation_json, principal_id FROM aud_logs WHERE entity_id = ? AND operation = ?",
                entityId, operation);
    }

    // ── Create ─────────────────────────────────────────────────────────────

    @Test
    void createWritesTheRowTheEventAndTheAuditTogether() {
        var res = createInternal(code("idpcrt"), "Create Me");
        assertThat(res.identityProviderId()).startsWith("idp_");
        assertThat(res.code()).isEqualTo(code("idpcrt"));
        assertThat(res.domainsCreated()).isEmpty();
        assertThat(res.domainsClaimed()).isEmpty();

        var got = reload(res.identityProviderId());
        assertThat(got.name()).isEqualTo("Create Me");
        assertThat(got.type()).isEqualTo(IdentityProviderType.INTERNAL);
        assertThat(got.hasClientSecret()).isFalse();
        assertThat(got.allowedEmailDomains()).isEmpty();
        assertThat(got.allowedRoleIds()).isEmpty();

        var events = eventsOn(IdentityProviderEvents.subjectFor(res.identityProviderId()), IdentityProviderEvents.CREATED);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("source")).isEqualTo(IdentityProviderEvents.SOURCE);
        assertThat(events.getFirst().get("message_group")).isEqualTo("platform:identityprovider:" + res.identityProviderId());
        var data = json(events.getFirst().get("data", String.class));
        assertThat(data.get("identityProviderId").asText()).isEqualTo(res.identityProviderId());
        assertThat(data.get("code").asText()).isEqualTo(code("idpcrt"));
        assertThat(data.propertyNames()).containsExactlyInAnyOrder("identityProviderId", "code");

        var audits = auditsFor(res.identityProviderId(), "CreateCommand");
        assertThat(audits).hasSize(1);
        assertThat(audits.getFirst().get("entity_type")).isEqualTo("Identityprovider");
        assertThat(audits.getFirst().get("principal_id")).isEqualTo(PRINCIPAL);
        assertThat(json(audits.getFirst().get("operation_json", String.class)).get("name").asText()).isEqualTo("Create Me");
    }

    @Test
    void createOidcStoresEverySettingAndMapsTheDomainsLowerCased() {
        var cmd = new CreateCommand(code("idpoidc"), "IdP Create Happy", "OIDC",
                "https://login.idpcrt.example.com/v2.0", "idpcrt-client-id", "encrypted:AAAA", true, "https://login\\.idpcrt\\.example\\.com/.*",
                List.of(domain("IDPOIDC-A").toUpperCase(Locale.ROOT), " " + domain("idpoidc-b") + " ", domain("idpoidc-b"), ""),
                "ANCHOR", null, true, List.of("rol_idpcrtrole1"));
        var res = runAsAnchor(CreateIdentityProvider.of(repo, mappings), cmd);

        assertThat(res.domainsCreated()).as("lower-cased, trimmed, de-duplicated, blanks skipped")
                .containsExactly(domain("idpoidc-a"), domain("idpoidc-b"));
        assertThat(res.domainsClaimed()).isEmpty();
        assertThat(res.domainsLinked()).isEmpty();

        var got = reload(res.identityProviderId());
        assertThat(got.type()).isEqualTo(IdentityProviderType.OIDC);
        assertThat(got.oidcIssuerUrl()).isEqualTo("https://login.idpcrt.example.com/v2.0");
        assertThat(got.oidcClientId()).isEqualTo("idpcrt-client-id");
        assertThat(got.oidcClientSecretRef()).isEqualTo("encrypted:AAAA");
        assertThat(got.hasClientSecret()).isTrue();
        assertThat(got.oidcMultiTenant()).isTrue();
        assertThat(got.oidcIssuerPattern()).isEqualTo("https://login\\.idpcrt\\.example\\.com/.*");
        assertThat(got.syncRolesFromIdp()).isTrue();
        assertThat(got.allowedRoleIds()).containsExactly("rol_idpcrtrole1");
        assertThat(got.allowedEmailDomains()).as("derived from the mapping table, in domain order")
                .containsExactly(domain("idpoidc-a"), domain("idpoidc-b"));

        var m = mapping(domain("idpoidc-a"));
        assertThat(m.identityProviderId()).isEqualTo(res.identityProviderId());
        assertThat(m.scopeType()).isEqualTo(ScopeType.ANCHOR);
        assertThat(m.primaryClientId()).isNull();
        assertThat(eventsOn(EmailDomainMappingEvents.subjectFor(m.id()), EmailDomainMappingEvents.CREATED))
                .as("the mapping aggregate's own created event").hasSize(1);
        var mappingAudit = auditsFor(m.id(), "CreateCommand");
        assertThat(mappingAudit).as("audited under the identity-provider command").hasSize(1);
        assertThat(mappingAudit.getFirst().get("entity_type")).isEqualTo("Emaildomainmapping");
    }

    /// Spec §4: an existing mapping is claimed (re-pointed), never duplicated; a
    /// primary-client link is filled only where missing and never overwritten;
    /// new mappings take the command's client and become CLIENT-scoped.
    @Test
    void createClaimsExistingDomainsFillingOnlyAMissingClientLink() {
        var withClient = "clt_idpclaimkeep";
        var newClient = "clt_idpclaimnew";
        runAsAnchor(CreateIdentityProvider.of(repo, mappings), oidcCommand(code("idpclaim-src1"), List.of(domain("idpclaim-hasclient")), withClient));
        createOidc(code("idpclaim-src2"), List.of(domain("idpclaim-noclient")));

        var res = runAsAnchor(CreateIdentityProvider.of(repo, mappings), oidcCommand(code("idpclaim-new"),
                List.of(domain("idpclaim-hasclient"), domain("idpclaim-noclient"), domain("idpclaim-fresh")), newClient));
        assertThat(res.domainsCreated()).containsExactly(domain("idpclaim-fresh"));
        assertThat(res.domainsClaimed()).containsExactly(domain("idpclaim-hasclient"), domain("idpclaim-noclient"));
        assertThat(res.domainsLinked()).as("the already-cliented mapping keeps its link untouched; the unclaimed and fresh mappings get linked")
                .containsExactlyInAnyOrder(domain("idpclaim-noclient"), domain("idpclaim-fresh"));

        var kept = mapping(domain("idpclaim-hasclient"));
        assertThat(kept.identityProviderId()).isEqualTo(res.identityProviderId());
        assertThat(kept.primaryClientId()).as("existing client link must not be overwritten").isEqualTo(withClient);
        assertThat(eventsOn(EmailDomainMappingEvents.subjectFor(kept.id()), EmailDomainMappingEvents.PROVIDER_CHANGED)).hasSize(1);

        var filled = mapping(domain("idpclaim-noclient"));
        assertThat(filled.identityProviderId()).isEqualTo(res.identityProviderId());
        assertThat(filled.primaryClientId()).as("unclaimed mapping takes the command's client").isEqualTo(newClient);

        var fresh = mapping(domain("idpclaim-fresh"));
        assertThat(fresh.scopeType()).as("client supplied → CLIENT scope on new mappings").isEqualTo(ScopeType.CLIENT);
        assertThat(fresh.primaryClientId()).isEqualTo(newClient);

        assertThat(reload(res.identityProviderId()).allowedEmailDomains())
                .containsExactly(domain("idpclaim-fresh"), domain("idpclaim-hasclient"), domain("idpclaim-noclient"));
        assertThat(repo.findByCode(code("idpclaim-src1")).orElseThrow().allowedEmailDomains()).as("source lost the domain").isEmpty();
    }

    static Stream<Arguments> malformedCreateCommands() {
        return Stream.of(
                Arguments.of("blank code", new CreateCommand("  ", "X", "INTERNAL", null, null, null, false, null, null, null, null, false, null), "CODE_REQUIRED"),
                Arguments.of("missing name", new CreateCommand("idpcrt-noname", null, "INTERNAL", null, null, null, false, null, null, null, null, false, null), "NAME_REQUIRED"),
                Arguments.of("oidc without issuer", new CreateCommand("idpcrt-noissuer", "X", "OIDC", null, "c", null, false, null, null, null, null, false, null), "OIDC_ISSUER_REQUIRED"),
                Arguments.of("oidc without client id", new CreateCommand("idpcrt-noclient", "X", "OIDC", "https://i", " ", null, false, null, null, null, null, false, null), "OIDC_CLIENT_ID_REQUIRED"),
                Arguments.of("bad domain", new CreateCommand("idpcrt-baddomain", "X", "INTERNAL", null, null, null, false, null, List.of("nodot"), null, null, false, null), "INVALID_EMAIL_DOMAIN"),
                Arguments.of("domain with slash", new CreateCommand("idpcrt-baddomain2", "X", "INTERNAL", null, null, null, false, null, List.of("a.b/c"), null, null, false, null), "INVALID_EMAIL_DOMAIN"),
                Arguments.of("CLIENT scope without primaryClientId", new CreateCommand("idpcrt-clientnoprimary", "X", "INTERNAL", null, null, null, false, null, null, "CLIENT", null, false, null), "PRIMARY_CLIENT_REQUIRED"),
                Arguments.of("ANCHOR scope with primaryClientId", new CreateCommand("idpcrt-anchorwithclient", "X", "INTERNAL", null, null, null, false, null, null, "ANCHOR", "cli_idpcrtanchor", false, null), "PRIMARY_CLIENT_NOT_ALLOWED"),
                Arguments.of("PARTNER mappingScope rejected", new CreateCommand("idpcrt-partnerscope", "X", "INTERNAL", null, null, null, false, null, null, "PARTNER", "cli_idpcrtpartner", false, null), "INVALID_MAPPING_SCOPE"),
                Arguments.of("primaryClientId without mappingScope", new CreateCommand("idpcrt-clientnoscope", "X", "INTERNAL", null, null, null, false, null, null, null, "cli_idpcrtnoscope", false, null), "MAPPING_SCOPE_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {2}")
    @MethodSource("malformedCreateCommands")
    void createRejectsAMalformedCommand(String label, CreateCommand cmd, String expectedCode) {
        assertUseCaseError(() -> runAsAnchor(CreateIdentityProvider.of(repo, mappings), cmd), UseCaseError.Validation.class, expectedCode);
    }

    @Test
    void createRejectsADuplicateCode() {
        createInternal(code("idpdup"), "First");
        assertUseCaseError(() -> createInternal(code("idpdup"), "Second"), UseCaseError.Conflict.class, "CODE_EXISTS");
        assertThatThrownBy(() -> createInternal(code("idpdup"), "Second"))
                .hasMessageContaining("Identity provider with code '" + code("idpdup") + "' already exists");
    }

    /// Owner ruling 2026-09-15 (spec §4 "Require a scope for new domains"): a
    /// create with a brand-new domain and no `mappingScope` fails — and
    /// because this is a `TxOperation`, the whole transaction rolls back:
    /// neither the IdP row nor any mapping is left behind.
    @Test
    void createWithAFreshDomainAndNoMappingScopeRollsBackTheWholeTransaction() {
        assertUseCaseError(() -> runAsAnchor(CreateIdentityProvider.of(repo, mappings),
                        new CreateCommand(code("idpcrt-noscope"), "X", "INTERNAL", null, null, null, false, null,
                                List.of(domain("idpcrt-noscope")), null, null, false, null)),
                UseCaseError.Validation.class, "MAPPING_SCOPE_REQUIRED");

        assertThat(repo.findByCode(code("idpcrt-noscope"))).as("IdP row must not survive a rolled-back create").isEmpty();
        assertThat(mappings.findByEmailDomain(domain("idpcrt-noscope"))).as("mapping must not survive a rolled-back create").isEmpty();
    }

    /// spec §4: a `CLIENT`-scoped create links the client on the fresh
    /// mapping and reports it `domainsLinked`.
    @Test
    void createWithClientScopeLinksTheNewMapping() {
        var res = runAsAnchor(CreateIdentityProvider.of(repo, mappings), oidcCommand(code("idpcrt-client"), List.of(domain("idpcrt-client")), "clt_idpcrtclient"));
        assertThat(res.domainsCreated()).containsExactly(domain("idpcrt-client"));
        assertThat(res.domainsLinked()).as("a new CLIENT-scoped mapping is reported linked").containsExactly(domain("idpcrt-client"));

        var m = mapping(domain("idpcrt-client"));
        assertThat(m.scopeType()).isEqualTo(ScopeType.CLIENT);
        assertThat(m.primaryClientId()).isEqualTo("clt_idpcrtclient");
    }

    // ── Update ─────────────────────────────────────────────────────────────

    @Test
    void updateAppliesTheSuppliedFieldsAndMapsNewDomains() {
        var seeded = createInternal(code("idpupd"), "Before");
        var res = runAsAnchor(UpdateIdentityProvider.of(repo, mappings), new UpdateCommand(seeded.identityProviderId(), "  After  ",
                "https://login.idpupd.example.com", null, null, true, null, List.of(domain("idpupd")), "ANCHOR", null, true, List.of("rol_idpupdrole1")));
        assertThat(res.identityProviderId()).isEqualTo(seeded.identityProviderId());
        assertThat(res.code()).isEqualTo(code("idpupd"));
        assertThat(res.domainsCreated()).containsExactly(domain("idpupd"));
        assertThat(res.domainsClaimed()).isEmpty();
        assertThat(res.domainsLinked()).isEmpty();
        assertThat(res.domainsReleased()).isEmpty();
        assertThat(res.usersReset()).isZero();

        var got = reload(seeded.identityProviderId());
        assertThat(got.name()).as("name is trimmed").isEqualTo("After");
        assertThat(got.code()).as("code is immutable").isEqualTo(code("idpupd"));
        assertThat(got.oidcIssuerUrl()).isEqualTo("https://login.idpupd.example.com");
        assertThat(got.oidcMultiTenant()).isTrue();
        assertThat(got.syncRolesFromIdp()).isTrue();
        assertThat(got.allowedRoleIds()).containsExactly("rol_idpupdrole1");
        assertThat(got.allowedEmailDomains()).containsExactly(domain("idpupd"));

        assertThat(eventsOn(IdentityProviderEvents.subjectFor(seeded.identityProviderId()), IdentityProviderEvents.UPDATED)).hasSize(1);
        assertThat(auditsFor(seeded.identityProviderId(), "UpdateCommand")).hasSize(1);
    }

    /// Spec §4: a domain removed from the desired set falls back to the internal
    /// provider; the mapping survives, only its routing changes.
    @Test
    void updateReleasesRemovedDomainsToTheInternalProvider() {
        String internalId = ensureInternal();
        var seeded = createOidc(code("idpupd-rel"), List.of(domain("idpupd-rel-keep"), domain("idpupd-rel-drop")));

        var res = runAsAnchor(UpdateIdentityProvider.of(repo, mappings), domainsOnly(seeded.identityProviderId(), List.of(domain("idpupd-rel-keep"))));
        assertThat(res.domainsReleased()).containsExactly(domain("idpupd-rel-drop"));
        assertThat(res.domainsCreated()).isEmpty();
        assertThat(res.domainsClaimed()).isEmpty();

        var dropped = mapping(domain("idpupd-rel-drop"));
        assertThat(dropped.identityProviderId()).as("released mapping survives, routed to internal").isEqualTo(internalId);
        assertThat(mapping(domain("idpupd-rel-keep")).identityProviderId()).isEqualTo(seeded.identityProviderId());
        assertThat(reload(seeded.identityProviderId()).allowedEmailDomains()).containsExactly(domain("idpupd-rel-keep"));
        var changed = eventsOn(EmailDomainMappingEvents.subjectFor(dropped.id()), EmailDomainMappingEvents.PROVIDER_CHANGED);
        assertThat(changed).hasSize(1);
        var data = json(changed.getFirst().get("data", String.class));
        assertThat(data.get("fromIdentityProviderId").asText()).isEqualTo(seeded.identityProviderId());
        assertThat(data.get("toIdentityProviderId").asText()).isEqualTo(internalId);
        assertThat(auditsFor(dropped.id(), "UpdateCommand")).hasSize(1);
    }

    @Test
    void updateWithoutADomainListLeavesMappingsAlone() {
        var seeded = createOidc(code("idpupd-nil"), List.of(domain("idpupd-nil")));
        var res = runAsAnchor(UpdateIdentityProvider.of(repo, mappings), new UpdateCommand(seeded.identityProviderId(), "Renamed",
                null, null, null, null, null, null, null, null, null, null));
        assertThat(res.domainsReleased()).isEmpty();
        assertThat(mapping(domain("idpupd-nil")).identityProviderId()).isEqualTo(seeded.identityProviderId());
        assertThat(reload(seeded.identityProviderId()).name()).isEqualTo("Renamed");
    }

    /// Adding a brand-new domain on update requires a `mappingScope`, exactly like create.
    @Test
    void updateWithANewDomainAndNoMappingScopeFails() {
        var seeded = createInternal(code("idpupd-noscope"), "No Scope");
        assertUseCaseError(() -> runAsAnchor(UpdateIdentityProvider.of(repo, mappings),
                        domainsOnly(seeded.identityProviderId(), List.of(domain("idpupd-noscope-new")))),
                UseCaseError.Validation.class, "MAPPING_SCOPE_REQUIRED");
        assertThat(reload(seeded.identityProviderId()).allowedEmailDomains()).as("the failed update changed nothing").isEmpty();
    }

    /// Regression for the owner's 2026-09-15 ruling: a `primaryClientId`
    /// supplied on update must link onto a mapping that was created without
    /// one, even though the domain already routes to this provider — the
    /// "edit later" fix (before it, `mapDomain` returned early for an
    /// already-routed domain and never applied the client). The mapping's
    /// scope is untouched, and a second update with a different client must
    /// not overwrite the link already made.
    @Test
    void updateLinksAClientOntoAnAlreadyRoutedDomainWithoutChangingItsScope() {
        var seeded = createOidc(code("idpupd-link"), List.of(domain("idpupd-link")));
        var primaryClient = "clt_idpupdlink";

        var res = runAsAnchor(UpdateIdentityProvider.of(repo, mappings), new UpdateCommand(seeded.identityProviderId(), null,
                null, null, null, null, null, List.of(domain("idpupd-link")), "CLIENT", primaryClient, null, null));
        assertThat(res.domainsLinked()).containsExactly(domain("idpupd-link"));
        assertThat(res.domainsCreated()).isEmpty();
        assertThat(res.domainsClaimed()).isEmpty();

        var linked = mapping(domain("idpupd-link"));
        assertThat(linked.primaryClientId()).isEqualTo(primaryClient);
        assertThat(linked.scopeType()).as("linking a client must not change the mapping's existing scope").isEqualTo(ScopeType.ANCHOR);
        assertThat(eventsOn(EmailDomainMappingEvents.subjectFor(linked.id()), EmailDomainMappingEvents.UPDATED)).hasSize(1);

        // A second update with a different client must not overwrite the existing link.
        var other = "clt_idpupdlinkother";
        var res2 = runAsAnchor(UpdateIdentityProvider.of(repo, mappings), new UpdateCommand(seeded.identityProviderId(), null,
                null, null, null, null, null, List.of(domain("idpupd-link")), "CLIENT", other, null, null));
        assertThat(res2.domainsLinked()).as("already linked, nothing to do").isEmpty();
        assertThat(mapping(domain("idpupd-link")).primaryClientId()).as("an existing client is never overwritten").isEqualTo(primaryClient);
    }

    /// Spec §4: releasing a domain converts its OIDC-provisioned users back to
    /// internal auth (provider marker, external id, `IDP_SYNC` roles) and
    /// leaves internal users and admin-assigned roles untouched.
    @Test
    void updateReleasingADomainResetsItsOidcUsers() {
        ensureInternal();
        var seeded = createOidc(code("idpmove-reset"), List.of(domain("idpmove-reset")));
        String oidcUser = EntityType.PRINCIPAL.generate();
        String internalUser = EntityType.PRINCIPAL.generate();
        insertUser(oidcUser, "reset-me@" + domain("idpmove-reset"), domain("idpmove-reset"), "OIDC", "ext-123", null);
        insertUser(internalUser, "leave-me@" + domain("idpmove-reset"), domain("idpmove-reset"), "INTERNAL", null, "$argon2id$hash");
        DB.insertInto(IAM_PRINCIPAL_ROLES).set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, oidcUser).set(IAM_PRINCIPAL_ROLES.ROLE_NAME, "idpmove:synced-role").set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "IDP_SYNC").execute();
        DB.insertInto(IAM_PRINCIPAL_ROLES).set(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID, oidcUser).set(IAM_PRINCIPAL_ROLES.ROLE_NAME, "idpmove:admin-role").set(IAM_PRINCIPAL_ROLES.ASSIGNMENT_SOURCE, "ADMIN_ASSIGNED").execute();

        var res = runAsAnchor(UpdateIdentityProvider.of(repo, mappings), domainsOnly(seeded.identityProviderId(), List.of()));
        assertThat(res.domainsReleased()).containsExactly(domain("idpmove-reset"));
        assertThat(res.usersReset()).as("exactly the OIDC-provisioned user is converted").isEqualTo(1);

        var reset = DB.selectFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(oidcUser)).fetchOne();
        assertThat(reset.getIdpType()).isEqualTo("INTERNAL");
        assertThat(reset.getExternalIdpId()).isNull();
        assertThat(DB.select(IAM_PRINCIPAL_ROLES.ROLE_NAME).from(IAM_PRINCIPAL_ROLES).where(IAM_PRINCIPAL_ROLES.PRINCIPAL_ID.eq(oidcUser)).fetch(IAM_PRINCIPAL_ROLES.ROLE_NAME))
                .as("IDP_SYNC roles dropped, admin roles kept").containsExactly("idpmove:admin-role");
        var hybrid = DB.selectFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.eq(internalUser)).fetchOne();
        assertThat(hybrid.getIdpType()).isEqualTo("INTERNAL");
        assertThat(hybrid.getPasswordHash()).as("internal user untouched").isEqualTo("$argon2id$hash");
    }

    private static void insertUser(String id, String email, String emailDomain, String idpType, String externalId, String passwordHash) {
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                .set(IAM_PRINCIPALS.NAME, email).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, emailDomain)
                .set(IAM_PRINCIPALS.IDP_TYPE, idpType).set(IAM_PRINCIPALS.EXTERNAL_IDP_ID, externalId)
                .set(IAM_PRINCIPALS.PASSWORD_HASH, passwordHash)
                .execute();
    }

    static Stream<Arguments> badUpdateCommands() {
        return Stream.of(
                Arguments.of("missing id", new UpdateCommand(null, "X", null, null, null, null, null, null, null, null, null, null), UseCaseError.Validation.class, "ID_REQUIRED"),
                Arguments.of("blank name when supplied", new UpdateCommand("idp_doesnotexist1", "  ", null, null, null, null, null, null, null, null, null, null), UseCaseError.Validation.class, "NAME_REQUIRED"),
                Arguments.of("bad domain", new UpdateCommand("idp_doesnotexist1", null, null, null, null, null, null, List.of("no dot"), null, null, null, null), UseCaseError.Validation.class, "INVALID_EMAIL_DOMAIN"),
                Arguments.of("unknown id", new UpdateCommand("idp_doesnotexist1", "X", null, null, null, null, null, null, null, null, null, null), UseCaseError.NotFound.class, "IdentityProvider_NOT_FOUND"),
                Arguments.of("CLIENT scope without primaryClientId", new UpdateCommand("idp_doesnotexist1", "X", null, null, null, null, null, null, "CLIENT", null, null, null), UseCaseError.Validation.class, "PRIMARY_CLIENT_REQUIRED"),
                Arguments.of("ANCHOR scope with primaryClientId", new UpdateCommand("idp_doesnotexist1", "X", null, null, null, null, null, null, "ANCHOR", "cli_idpupdanchor", null, null), UseCaseError.Validation.class, "PRIMARY_CLIENT_NOT_ALLOWED"),
                Arguments.of("PARTNER mappingScope rejected", new UpdateCommand("idp_doesnotexist1", "X", null, null, null, null, null, null, "PARTNER", "cli_idpupdpartner", null, null), UseCaseError.Validation.class, "INVALID_MAPPING_SCOPE"),
                Arguments.of("primaryClientId without mappingScope", new UpdateCommand("idp_doesnotexist1", "X", null, null, null, null, null, null, null, "cli_idpupdnoscope", null, null), UseCaseError.Validation.class, "MAPPING_SCOPE_REQUIRED"));
    }

    @ParameterizedTest(name = "{0} → {3}")
    @MethodSource("badUpdateCommands")
    void updateRejectsMissingIdBlankNameBadDomainOrUnknownRow(String label, UpdateCommand cmd, Class<? extends UseCaseError> kind, String code) {
        assertUseCaseError(() -> runAsAnchor(UpdateIdentityProvider.of(repo, mappings), cmd), kind, code);
    }

    // ── Delete ─────────────────────────────────────────────────────────────

    @Test
    void deleteRemovesTheRowItsRolesAndEmits() {
        var seeded = runAsAnchor(CreateIdentityProvider.of(repo, mappings),
                new CreateCommand(code("idpdel"), "Doomed", "INTERNAL", null, null, null, false, null, null, null, null, true, List.of("rol_x")));

        var ev = runAsAnchor(DeleteIdentityProvider.of(repo), new DeleteCommand(seeded.identityProviderId()));
        assertThat(ev.identityProviderId()).isEqualTo(seeded.identityProviderId());
        assertThat(ev.code()).isEqualTo(code("idpdel"));
        assertThat(ev.eventType()).isEqualTo(IdentityProviderEvents.DELETED);

        assertThat(repo.findById(seeded.identityProviderId())).as("deleted row must be gone").isEmpty();
        assertThat(DB.fetchCount(DB.selectFrom(OAUTH_IDENTITY_PROVIDER_ALLOWED_ROLES)
                .where(OAUTH_IDENTITY_PROVIDER_ALLOWED_ROLES.IDENTITY_PROVIDER_ID.eq(seeded.identityProviderId()))))
                .as("junction cleared").isZero();
        assertThat(eventsOn(IdentityProviderEvents.subjectFor(seeded.identityProviderId()), IdentityProviderEvents.DELETED)).hasSize(1);
        assertThat(auditsFor(seeded.identityProviderId(), "DeleteCommand")).hasSize(1);
    }

    @Test
    void deleteIsBlockedWhileDomainsStillRouteToTheProvider() {
        var seeded = createOidc(code("idpdel-guard"), List.of(domain("idpdel-guard")));
        assertUseCaseError(() -> runAsAnchor(DeleteIdentityProvider.of(repo), new DeleteCommand(seeded.identityProviderId())),
                UseCaseError.Conflict.class, "DOMAINS_STILL_MAPPED");
        assertThat(repo.findById(seeded.identityProviderId())).isPresent();
    }

    @Test
    void deleteRefusesTheSeededInternalProvider() {
        String internalId = ensureInternal();
        assertUseCaseError(() -> runAsAnchor(DeleteIdentityProvider.of(repo), new DeleteCommand(internalId)),
                UseCaseError.BusinessRule.class, "INTERNAL_IDP_PROTECTED");
    }

    @Test
    void deleteRejectsMissingIdOrUnknownRow() {
        assertUseCaseError(() -> runAsAnchor(DeleteIdentityProvider.of(repo), new DeleteCommand(" ")),
                UseCaseError.Validation.class, "ID_REQUIRED");
        assertUseCaseError(() -> runAsAnchor(DeleteIdentityProvider.of(repo), new DeleteCommand("idp_doesnotexist1")),
                UseCaseError.NotFound.class, "IdentityProvider_NOT_FOUND");
    }

    // ── Repository reads ───────────────────────────────────────────────────

    @Test
    void findAllIsOrderedByCodeAndFindByCodeIsExact() {
        var b = createInternal(code("idpall-b"), "B");
        var a = createInternal(code("idpall-a"), "A");
        assertThat(repo.findAll()).extracting(IdentityProvider::id).containsSubsequence(a.identityProviderId(), b.identityProviderId());
        assertThat(repo.findByCode(code("idpall-a"))).map(IdentityProvider::id).contains(a.identityProviderId());
        assertThat(repo.findByCode(code("idpall-a").toUpperCase(Locale.ROOT))).as("by-code is exact").isEmpty();
    }
}
