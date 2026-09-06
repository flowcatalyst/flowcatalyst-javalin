package io.flowcatalyst.platform.auth.mfa;

import io.flowcatalyst.platform.emaildomainmapping.EmailDomain;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMapping;
import io.flowcatalyst.platform.emaildomainmapping.EmailDomainMappingRepository;
import io.flowcatalyst.platform.emaildomainmapping.MfaMethod;
import io.flowcatalyst.platform.emaildomainmapping.ScopeType;
import io.flowcatalyst.platform.emaildomainmapping.TwoFactorPolicy;
import io.flowcatalyst.platform.identityprovider.IdentityProvider;
import io.flowcatalyst.platform.identityprovider.IdentityProviderRepository;
import io.flowcatalyst.platform.identityprovider.IdentityProviderType;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.encryption.Encryption;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_MFA_TRUSTED_DEVICES;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.IAM_USER_MFA_METHODS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_IDENTITY_PROVIDERS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPINGS;
import static io.flowcatalyst.db.generated.Tables.TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS;
import static org.assertj.core.api.Assertions.assertThat;

/// The `/auth/login` decision table (`docs/spec/auth-identity.md` §6.2)
/// and the domain policy (§6.1 with ruling I-Q11), driven through a real
/// request so the trusted-device cookie is read the way the login route
/// reads it.
class LoginMfaGateTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final Encryption ENC = Encryption.withKey(Encryption.generateKey());
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final EmailDomainMappingRepository MAPPINGS = new EmailDomainMappingRepository(DS);
    private static final IdentityProviderRepository IDPS = new IdentityProviderRepository(DS);
    private static final MfaRepository REPO = new MfaRepository(DS);
    private static final Mfa MFA = new Mfa(REPO, Optional.of(ENC), MailSender.logging(), Mfa.Config.DEFAULT, Clock.systemUTC());
    private static final MfaToken TOKENS = new MfaToken(KEYS.privateKey(), "http://localhost:8080");

    private static final String STRICT = "strict-" + RUN + ".example";     // requires TOTP only, remember on
    private static final String LOOSE = "loose-" + RUN + ".example";       // no requirement, remember on
    private static final String EXTERNAL = "ext-" + RUN + ".example";      // OIDC provider, "requires" 2FA + remember (must be inert)
    private static final String UNMAPPED = "unmapped-" + RUN + ".example";

    private static final List<String> principals = new ArrayList<>();
    private static final List<String> mappingIds = new ArrayList<>();
    private static final List<String> idpIds = new ArrayList<>();
    private static TestHttp http;

    @BeforeAll
    static void start() {
        mapping(STRICT, IdentityProviderType.INTERNAL, new TwoFactorPolicy(true, List.of(MfaMethod.TOTP), true, 7));
        mapping(LOOSE, IdentityProviderType.INTERNAL, new TwoFactorPolicy(false, List.of(), true, 0));
        mapping(EXTERNAL, IdentityProviderType.OIDC, new TwoFactorPolicy(true, List.of(MfaMethod.TOTP, MfaMethod.EMAIL_PIN), true, 7));
        var gate = new LoginMfaGate(MFA, new DomainPolicy.Evaluator(MAPPINGS), TOKENS, new TrustedDeviceCookie(false));
        http = TestHttp.routes(routes -> routes.post("/gate/{id}", ctx -> {
            var p = PRINCIPALS.findById(ctx.pathParam("id")).orElseThrow();
            var challenge = gate.evaluate(p, ctx);
            ctx.json(challenge.map(LoginMfaGate.Challenge::body).orElse(Map.of("status", "proceed")));
        }));
    }

    @AfterAll
    static void stop() {
        http.close();
        DB.deleteFrom(IAM_USER_MFA_METHODS).where(IAM_USER_MFA_METHODS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_MFA_TRUSTED_DEVICES).where(IAM_MFA_TRUSTED_DEVICES.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(principals)).execute();
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS).where(TNT_EMAIL_DOMAIN_MAPPING_2FA_METHODS.EMAIL_DOMAIN_MAPPING_ID.in(mappingIds)).execute();
        DB.deleteFrom(TNT_EMAIL_DOMAIN_MAPPINGS).where(TNT_EMAIL_DOMAIN_MAPPINGS.ID.in(mappingIds)).execute();
        DB.deleteFrom(OAUTH_IDENTITY_PROVIDERS).where(OAUTH_IDENTITY_PROVIDERS.ID.in(idpIds)).execute();
    }

    // ── the policy ─────────────────────────────────────────────────────────

    @Test
    void anExternalIdpDomainNeverRequiresAFactorHereNorRemembersADevice() {
        var eval = new DomainPolicy.Evaluator(MAPPINGS);
        DomainPolicy ext = eval.evaluate("someone@" + EXTERNAL);
        assertThat(ext.internal()).isFalse();
        assertThat(ext.mapped()).isTrue();
        assertThat(ext.requires2fa()).as("required && internal").isFalse();
        assertThat(ext.rememberEnabled()).as("ruling I-Q11: structurally off for external domains").isFalse();

        DomainPolicy strict = eval.evaluate("Someone@" + STRICT.toUpperCase(Locale.ROOT));
        assertThat(strict.internal()).isTrue();
        assertThat(strict.requires2fa()).isTrue();
        assertThat(strict.allowedMethods()).containsExactly(MfaMethod.TOTP);
        assertThat(strict.permittedMethods()).containsExactly(MfaMethod.TOTP);
        assertThat(strict.rememberEnabled()).isTrue();
        assertThat(strict.rememberDays()).isEqualTo(7);

        DomainPolicy loose = eval.evaluate("x@" + LOOSE);
        assertThat(loose.requires2fa()).isFalse();
        assertThat(loose.permittedMethods()).containsExactly(MfaMethod.TOTP, MfaMethod.EMAIL_PIN);
        assertThat(loose.rememberDays()).as("≤ 0 → 30").isEqualTo(30);

        assertThat(eval.evaluate("x@" + UNMAPPED)).isEqualTo(DomainPolicy.INTERNAL_UNMAPPED);
        assertThat(eval.evaluate("no-at-sign")).isEqualTo(DomainPolicy.INTERNAL_UNMAPPED);
        assertThat(eval.evaluate("trailing@")).isEqualTo(DomainPolicy.INTERNAL_UNMAPPED);
        assertThat(eval.evaluate(null)).isEqualTo(DomainPolicy.INTERNAL_UNMAPPED);
    }

    // ── the decision ───────────────────────────────────────────────────────

    @Test
    void noFactorAndNoRequirementProceeds() {
        String pid = user("free-" + RUN + "@" + LOOSE);
        assertThat(gate(pid).get("status").asString()).isEqualTo("proceed");
        String un = user("un-" + RUN + "@" + UNMAPPED);
        assertThat(gate(un).get("status").asString()).isEqualTo("proceed");
    }

    @Test
    void noFactorOnARequiringDomainDemandsEnrolmentWithTheDomainsMethods() {
        String pid = user("new-" + RUN + "@" + STRICT);
        JsonNode j = gate(pid);
        assertThat(j.get("status").asString()).isEqualTo("enrollment_required");
        assertThat(j.get("allowedMethods").toString()).isEqualTo("[\"TOTP\"]");
        String token = j.get("enrollToken").asString();
        assertThat(TOKENS.parse(token, MfaToken.Purpose.ENROLL)).as("an enrol token for this user").isPresent();
        assertThat(TOKENS.parse(token, MfaToken.Purpose.PENDING)).isEmpty();
        assertThat(TOKENS.parse(token, MfaToken.Purpose.ENROLL).get().subject()).isEqualTo(pid);
    }

    @Test
    void aConfirmedFactorDemandsTheSecondStepWithAPendingToken() {
        String pid = user("totp-" + RUN + "@" + LOOSE);
        enrolTotp(pid);
        JsonNode j = gate(pid);
        assertThat(j.get("status").asString()).isEqualTo("mfa_required");
        assertThat(j.get("methods").toString()).isEqualTo("[\"TOTP\"]");
        assertThat(j.get("rememberDeviceAllowed").asBoolean()).isTrue();
        assertThat(TOKENS.parse(j.get("mfaToken").asString(), MfaToken.Purpose.PENDING).get().subject()).isEqualTo(pid);
    }

    @Test
    void aFactorOutsideTheDomainsListIsNotUsableSoEnrolmentIsDemanded() {
        String pid = user("email-only-" + RUN + "@" + STRICT);
        DB.insertInto(IAM_USER_MFA_METHODS).set(IAM_USER_MFA_METHODS.ID, EntityType.MFA_METHOD.generate())
                .set(IAM_USER_MFA_METHODS.PRINCIPAL_ID, pid).set(IAM_USER_MFA_METHODS.METHOD, "EMAIL_PIN")
                .set(IAM_USER_MFA_METHODS.CONFIRMED_AT, NOW).set(IAM_USER_MFA_METHODS.CREATED_AT, NOW).execute();
        JsonNode j = gate(pid);
        assertThat(j.get("status").asString()).as("confirmed ∩ allowed is empty on a requiring domain").isEqualTo("enrollment_required");

        // The same factor on a domain that does not require one is usable.
        String loose = user("email-loose-" + RUN + "@" + LOOSE);
        DB.insertInto(IAM_USER_MFA_METHODS).set(IAM_USER_MFA_METHODS.ID, EntityType.MFA_METHOD.generate())
                .set(IAM_USER_MFA_METHODS.PRINCIPAL_ID, loose).set(IAM_USER_MFA_METHODS.METHOD, "EMAIL_PIN")
                .set(IAM_USER_MFA_METHODS.CONFIRMED_AT, NOW).set(IAM_USER_MFA_METHODS.CREATED_AT, NOW).execute();
        assertThat(gate(loose).get("status").asString()).isEqualTo("mfa_required");
    }

    @Test
    void aVerifiedTrustedDeviceSkipsTheSecondStepAndStampsItsUse() {
        String pid = user("td-" + RUN + "@" + LOOSE);
        enrolTotp(pid);
        String raw = MFA.issueTrustedDevice(pid, "Mozilla", Duration.ofDays(7));
        assertThat(gate(pid, "Cookie", "fc_td=" + raw).get("status").asString()).isEqualTo("proceed");
        assertThat(MFA.listTrustedDevices(pid).getFirst().lastUsedAt()).isNotNull();
        assertThat(gate(pid, "Cookie", "fc_td=wrong").get("status").asString()).isEqualTo("mfa_required");
        assertThat(gate(pid, "Cookie", "__Host-fc_td=" + raw).get("status").asString()).as("the insecure gate reads fc_td only").isEqualTo("mfa_required");
    }

    @Test
    void aTrustedDeviceCookieIsIgnoredWhenTheDomainDoesNotAllowRemembering() {
        String pid = user("nr-" + RUN + "@" + UNMAPPED);
        enrolTotp(pid);
        String raw = MFA.issueTrustedDevice(pid, "Mozilla", Duration.ofDays(7));
        JsonNode j = gate(pid, "Cookie", "fc_td=" + raw);
        assertThat(j.get("status").asString()).isEqualTo("mfa_required");
        assertThat(j.get("rememberDeviceAllowed").asBoolean()).isFalse();
    }

    @Test
    void anExternalDomainUserWithAPasswordAndAFactorGetsNoRememberOffer() {
        String pid = user("extuser-" + RUN + "@" + EXTERNAL);
        enrolTotp(pid);
        String raw = MFA.issueTrustedDevice(pid, "Mozilla", Duration.ofDays(7));
        JsonNode j = gate(pid, "Cookie", "fc_td=" + raw);
        assertThat(j.get("status").asString()).as("the cookie is inert for an external domain").isEqualTo("mfa_required");
        assertThat(j.get("rememberDeviceAllowed").asBoolean()).isFalse();
    }

    @Test
    void aFederatedIdentityIsNeverChallenged() {
        String pid = user("fed-" + RUN + "@" + STRICT);
        DB.update(IAM_PRINCIPALS).set(IAM_PRINCIPALS.IDP_TYPE, "OIDC").where(IAM_PRINCIPALS.ID.eq(pid)).execute();
        assertThat(gate(pid).get("status").asString()).isEqualTo("proceed");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static JsonNode gate(String pid, String... headers) {
        HttpResponse<String> r = http.post("/gate/" + pid, "{}", headers);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return Json.MAPPER.readTree(r.body());
    }

    private static void enrolTotp(String pid) {
        var e = MFA.beginTotpEnrollment(pid, "x@example.com");
        assertThat(MFA.confirmTotpEnrollment(pid, Totp.code(e.secret(), Totp.stepOf(Instant.now())))).isTrue();
    }

    private static String user(String email) {
        String id = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS)
                .set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                .set(IAM_PRINCIPALS.NAME, "Gate " + RUN).set(IAM_PRINCIPALS.ACTIVE, true)
                .set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, email.substring(email.indexOf('@') + 1))
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW)
                .execute();
        principals.add(id);
        return id;
    }

    private static void mapping(String domain, IdentityProviderType type, TwoFactorPolicy policy) {
        var idp = IdentityProvider.create("idp-" + domain, "IdP " + domain, type);
        if (type == IdentityProviderType.OIDC) {
            idp = idp.withOidc("https://" + domain, "client-" + RUN, null, false, null);
        }
        var m = EmailDomainMapping.create(EmailDomain.parse(domain), idp.id(), ScopeType.ANCHOR).withTwoFactor(policy);
        idpIds.add(idp.id());
        mappingIds.add(m.id());
        var ip = idp;
        UOW.inTransaction(tx -> {
            IDPS.persist(ip, tx.dbTx());
            MAPPINGS.persist(m, tx.dbTx());
            return null;
        });
    }
}
