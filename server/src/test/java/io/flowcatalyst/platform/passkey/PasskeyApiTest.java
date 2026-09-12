package io.flowcatalyst.platform.passkey;

import io.flowcatalyst.platform.auth.claims.DbClaimsResolver;
import io.flowcatalyst.platform.auth.login.BackoffCheck;
import io.flowcatalyst.platform.auth.login.BackoffPolicy;
import io.flowcatalyst.platform.auth.login.SessionCookie;
import io.flowcatalyst.platform.auth.token.TokenIssuer;
import io.flowcatalyst.platform.loginattempt.LoginAttemptRepository;
import io.flowcatalyst.platform.mail.Mail;
import io.flowcatalyst.platform.notify.Notifications;
import io.flowcatalyst.platform.passkey.api.PasskeyApi;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.role.RoleRepository;
import io.flowcatalyst.platform.shared.TestHttp;
import io.flowcatalyst.platform.shared.auth.Authenticator;
import io.flowcatalyst.platform.shared.auth.JwtVerifier;
import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.platform.shared.platformsink.PlatformSink;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.testpg.TestPg;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.flowcatalyst.db.generated.Tables.IAM_LOGIN_ATTEMPTS;
import static io.flowcatalyst.db.generated.Tables.IAM_PRINCIPALS;
import static io.flowcatalyst.db.generated.Tables.MSG_EVENTS;
import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_PAYLOADS;
import static io.flowcatalyst.db.generated.Tables.WEBAUTHN_CREDENTIALS;
import static org.assertj.core.api.Assertions.assertThat;

/// `docs/spec/auth-identity.md` §7.2–§7.5 with rulings I-Q13, I-Q24, I-Q25:
/// the six routes over the embedded Postgres, the ceremonies driven by a
/// software authenticator so registration and assertion are verified for
/// real, and the counter rule a cloned key trips.
class PasskeyApiTest {

    private static final DataSource DS = TestPg.dataSource();
    private static final DSLContext DB = DSL.using(DS, SQLDialect.POSTGRES);
    private static final String RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toLowerCase(Locale.ROOT);
    private static final OffsetDateTime NOW = Instant.now().atOffset(ZoneOffset.UTC);
    private static final SigningKeys KEYS = SigningKeys.generateEphemeral();
    private static final String ISSUER = "http://localhost:8080";
    private static final String ORIGIN = "http://localhost:8080";
    private static final UnitOfWork UOW = new UnitOfWork(DS, new PlatformSink(Json.MAPPER));
    private static final PrincipalRepository PRINCIPALS = new PrincipalRepository(DS);
    private static final PasskeyRepository CREDS = new PasskeyRepository(DS);
    private static final LoginAttemptRepository ATTEMPTS = new LoginAttemptRepository(DS);
    private static final TokenIssuer TOKEN_ISSUER = new TokenIssuer(KEYS, TokenIssuer.Config.of(ISSUER));
    private static final JwtVerifier VERIFIER = new JwtVerifier(new JwtVerifier.Config(ISSUER, new JwtVerifier.RsaKeys(KEYS.publicKey())));
    private static final List<Mail> SENT = new ArrayList<>();
    private static final List<String> principals = new ArrayList<>();
    private static TestHttp http;

    @BeforeAll
    static void start() {
        var service = new PasskeyService(new PasskeyService.Config("localhost", Set.of(ORIGIN), "FlowCatalyst " + RUN), CREDS);
        var state = new PasskeyApi.State(service, CREDS, new CeremonyRepository(DS), PRINCIPALS, UOW, TOKEN_ISSUER,
                new SessionCookie(false, (int) TokenIssuer.SESSION_TTL_SECONDS), new Notifications(SENT::add, () -> "Acme"), ATTEMPTS,
                new BackoffCheck(ATTEMPTS, BackoffPolicy.DEFAULT), Clock.systemUTC());
        var resolver = new DbClaimsResolver(PRINCIPALS, new RoleRepository(DS));
        http = TestHttp.routes(routes -> {
            HttpError.install(routes);
            routes.before("/auth/webauthn/*", new Authenticator(VERIFIER, resolver, Authenticator.Config.of(false)));
            PasskeyApi.register(routes, state);
        });
    }

    @AfterAll
    static void stop() {
        http.close();
        DB.deleteFrom(MSG_EVENTS).where(MSG_EVENTS.SUBJECT.like("platform.passkey.%")).and(MSG_EVENTS.DATA.cast(String.class).like("%" + RUN + "%")).execute();
        DB.deleteFrom(WEBAUTHN_CREDENTIALS).where(WEBAUTHN_CREDENTIALS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.PRINCIPAL_ID.in(principals)).execute();
        DB.deleteFrom(IAM_PRINCIPALS).where(IAM_PRINCIPALS.ID.in(principals)).execute();
    }

    // ── registration ───────────────────────────────────────────────────────

    @Test
    void registrationStoresAGoShapedCredentialAndAssertionSignsInMovingTheCounter() throws Exception {
        String email = "pk-" + RUN + "@example.com";
        String pid = user(email);
        var auth = new SoftAuthenticator(0);

        var begin = http.post("/auth/webauthn/register/begin", "{\"displayName\":\"Pat\"}", "Cookie", session(pid, email));
        assertThat(begin.statusCode()).as(begin.body()).isEqualTo(200);
        JsonNode b = json(begin);
        assertThat(b.get("stateId").asString()).hasSize(22);
        JsonNode pk = b.get("options").get("publicKey");
        assertThat(pk.get("rp").get("id").asString()).isEqualTo("localhost");
        assertThat(pk.get("user").get("id").asString()).isEqualTo(SoftAuthenticator.b64url(pid.getBytes()));
        assertThat(pk.get("user").get("displayName").asString()).isEqualTo("Pat");
        assertThat(DB.fetchCount(OAUTH_OIDC_PAYLOADS, OAUTH_OIDC_PAYLOADS.ID.eq("WebauthnRegistration:" + b.get("stateId").asString()))).isEqualTo(1);

        var complete = http.post("/auth/webauthn/register/complete", Json.write(Map.of("stateId", b.get("stateId").asString(),
                "name", "Laptop", "credential", auth.register(b.get("options"), ORIGIN))), "Cookie", session(pid, email));
        assertThat(complete.statusCode()).as(complete.body()).isEqualTo(200);
        String credentialId = json(complete).get("credentialId").asString();
        assertThat(credentialId).startsWith("pkc_");
        var row = DB.selectFrom(WEBAUTHN_CREDENTIALS).where(WEBAUTHN_CREDENTIALS.ID.eq(credentialId)).fetchOne();
        assertThat(row.getCredentialId()).isEqualTo(auth.credentialId);
        JsonNode stored = Json.MAPPER.readTree(row.getPasskeyData().data());
        assertThat(stored.get("id").asString()).isEqualTo(java.util.Base64.getEncoder().encodeToString(auth.credentialId));
        assertThat(stored.get("authenticator").get("signCount").asLong()).isEqualTo(0);
        assertThat(stored.get("transport").toString()).isEqualTo("[\"internal\"]");
        assertThat(SENT).extracting(Mail::subject).contains("A new passkey was registered");
        assertThat(DB.fetchCount(OAUTH_OIDC_PAYLOADS, OAUTH_OIDC_PAYLOADS.ID.eq("WebauthnRegistration:" + b.get("stateId").asString()))).as("consumed").isZero();
        assertThat(events(credentialId, "platform:iam:passkey:registered")).isEqualTo(1);

        // Sign in with it.
        var ab = http.post("/auth/webauthn/authenticate/begin", Json.write(Map.of("email", email.toUpperCase(Locale.ROOT))));
        assertThat(ab.statusCode()).as(ab.body()).isEqualTo(200);
        JsonNode a = json(ab);
        assertThat(a.get("options").get("publicKey").get("allowCredentials").get(0).get("id").asString())
                .isEqualTo(SoftAuthenticator.b64url(auth.credentialId));
        var ac = http.post("/auth/webauthn/authenticate/complete", Json.write(Map.of("stateId", a.get("stateId").asString(),
                "credential", auth.assertion(a.get("options"), ORIGIN, pid))), "User-Agent", "SoftAuth/1");
        assertThat(ac.statusCode()).as(ac.body()).isEqualTo(200);
        JsonNode body = json(ac);
        assertThat(body.get("principalId").asString()).isEqualTo(pid);
        assertThat(body.get("email").asString()).isEqualTo(email);
        assertThat(body.get("roles").isArray()).isTrue();
        assertThat(body.has("permissions")).as("no permissions on a passkey login").isFalse();
        String cookie = ac.headers().firstValue("set-cookie").orElseThrow();
        assertThat(cookie).startsWith("fc_session=").contains("SameSite=Lax");
        assertThat(VERIFIER.verify(cookie.substring("fc_session=".length(), cookie.indexOf(';')))).isInstanceOf(JwtVerifier.Verified.class);

        // Ruling I-Q13: the counter and last_used_at are persisted, the event emitted.
        var after = DB.selectFrom(WEBAUTHN_CREDENTIALS).where(WEBAUTHN_CREDENTIALS.ID.eq(credentialId)).fetchOne();
        assertThat(Json.MAPPER.readTree(after.getPasskeyData().data()).get("authenticator").get("signCount").asLong()).isEqualTo(1);
        assertThat(after.getLastUsedAt()).isNotNull();
        assertThat(events(credentialId, "platform:iam:passkey:authenticated")).isEqualTo(1);
        var attempt = DB.selectFrom(IAM_LOGIN_ATTEMPTS).where(IAM_LOGIN_ATTEMPTS.PRINCIPAL_ID.eq(pid)).orderBy(IAM_LOGIN_ATTEMPTS.ATTEMPTED_AT.desc()).limit(1).fetchOne();
        assertThat(attempt.getOutcome()).isEqualTo("SUCCESS");
        assertThat(attempt.getUserAgent()).isEqualTo("SoftAuth/1");

        // Replaying the same state is refused: it was consumed.
        var replay = http.post("/auth/webauthn/authenticate/complete", Json.write(Map.of("stateId", a.get("stateId").asString(),
                "credential", auth.assertion(a.get("options"), ORIGIN, pid))));
        assertThat(replay.statusCode()).isEqualTo(403);
        assertThat(json(replay).get("error").asString()).isEqualTo("INVALID_CREDENTIALS");

        // A cloned authenticator (counter behind the stored one) is refused and
        // recorded. Two guards stand here: the library refuses a non-increasing
        // counter inside finishAssertion, and the route re-checks the result —
        // dropping the route's check alone is an equivalent mutant.
        var clone = new SoftAuthenticator(0);
        System.arraycopy(auth.credentialId, 0, clone.credentialId, 0, auth.credentialId.length);
        var cloneBegin = json(http.post("/auth/webauthn/authenticate/begin", Json.write(Map.of("email", email))));
        int failuresBefore = failures(pid);
        JsonNode cloneAssertion = auth.assertion(cloneBegin.get("options"), ORIGIN, pid);
        auth.counter = 0; // wind the real key back: the server saw 1 already
        cloneAssertion = auth.assertion(cloneBegin.get("options"), ORIGIN, pid); // counter 1 == stored 1 → not forward
        var cloned = http.post("/auth/webauthn/authenticate/complete", Json.write(Map.of("stateId", cloneBegin.get("stateId").asString(), "credential", cloneAssertion)));
        assertThat(cloned.statusCode()).as("ruling I-Q13: a counter that did not move forward is refused").isEqualTo(403);
        assertThat(failures(pid)).isEqualTo(failuresBefore + 1);

        // A genuine next assertion still works after the clone scare.
        auth.counter = 5;
        var next = json(http.post("/auth/webauthn/authenticate/begin", Json.write(Map.of("email", email))));
        var ok = http.post("/auth/webauthn/authenticate/complete", Json.write(Map.of("stateId", next.get("stateId").asString(),
                "credential", auth.assertion(next.get("options"), ORIGIN, pid))));
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);

        // The credential list and revocation.
        var list = json(http.get("/auth/webauthn/credentials", "Cookie", session(pid, email)));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("id").asString()).isEqualTo(credentialId);
        assertThat(list.get(0).get("name").asString()).isEqualTo("Laptop");
        assertThat(list.get(0).has("lastUsedAt")).isTrue();
        String other = user("other-" + RUN + "@example.com");
        assertThat(http.delete("/auth/webauthn/credentials/" + credentialId, "Cookie", session(other, "other-" + RUN + "@example.com")).statusCode())
                .as("not the caller's: 404, never 403").isEqualTo(404);
        assertThat(http.delete("/auth/webauthn/credentials/" + credentialId, "Cookie", session(pid, email)).statusCode()).isEqualTo(204);
        assertThat(CREDS.findById(credentialId)).isEmpty();
        assertThat(events(credentialId, "platform:iam:passkey:revoked")).isEqualTo(1);
    }

    @Test
    void registrationCompleteChecksTheNameBeforeConsumingAndTheStatesOwner() throws Exception {
        String email = "reg-" + RUN + "@example.com";
        String pid = user(email);
        var auth = new SoftAuthenticator();
        JsonNode b = json(http.post("/auth/webauthn/register/begin", "{}", "Cookie", session(pid, email)));
        var noName = http.post("/auth/webauthn/register/complete", Json.write(Map.of("stateId", b.get("stateId").asString(), "name", "  ",
                "credential", auth.register(b.get("options"), ORIGIN))), "Cookie", session(pid, email));
        assertThat(noName.statusCode()).isEqualTo(400);
        assertThat(json(noName).get("error").asString()).isEqualTo("NAME_REQUIRED");
        assertThat(DB.fetchCount(OAUTH_OIDC_PAYLOADS, OAUTH_OIDC_PAYLOADS.ID.eq("WebauthnRegistration:" + b.get("stateId").asString())))
                .as("the name check runs before the consume").isEqualTo(1);

        String intruder = user("intruder-" + RUN + "@example.com");
        var wrongOwner = http.post("/auth/webauthn/register/complete", Json.write(Map.of("stateId", b.get("stateId").asString(), "name", "X",
                "credential", auth.register(b.get("options"), ORIGIN))), "Cookie", session(intruder, "intruder-" + RUN + "@example.com"));
        assertThat(wrongOwner.statusCode()).isEqualTo(403);
        assertThat(json(wrongOwner).get("error").asString()).isEqualTo("FORBIDDEN");

        var gone = http.post("/auth/webauthn/register/complete", Json.write(Map.of("stateId", b.get("stateId").asString(), "name", "X",
                "credential", auth.register(b.get("options"), ORIGIN))), "Cookie", session(pid, email));
        assertThat(json(gone).get("error").asString()).as("burned by the intruder's attempt").isEqualTo("STATE_NOT_FOUND");

        JsonNode b2 = json(http.post("/auth/webauthn/register/begin", "{}", "Cookie", session(pid, email)));
        var badOrigin = http.post("/auth/webauthn/register/complete", Json.write(Map.of("stateId", b2.get("stateId").asString(), "name", "X",
                "credential", auth.register(b2.get("options"), "https://evil.example"))), "Cookie", session(pid, email));
        assertThat(badOrigin.statusCode()).isEqualTo(400);
        assertThat(json(badOrigin).get("error").asString()).isEqualTo("ATTESTATION_INVALID");
        assertThat(CREDS.findByPrincipal(pid)).isEmpty();
        assertThat(http.post("/auth/webauthn/register/begin", "{}").statusCode()).as("no session").isEqualTo(403);
    }

    @Test
    void anUnknownOrPasskeylessAccountGetsADecoyAndAWrongSignatureIsRecordedAsAFailure() throws Exception {
        var unknown = json(http.post("/auth/webauthn/authenticate/begin", Json.write(Map.of("email", "nobody-" + RUN + "@example.com"))));
        JsonNode pk = unknown.get("options").get("publicKey");
        assertThat(unknown.get("stateId").asString()).hasSize(22);
        assertThat(pk.get("rpId").asString()).isEqualTo("localhost");
        assertThat(pk.get("timeout").asInt()).isEqualTo(60000); // the decoy keeps go-webauthn's 60 s; real ceremonies use 300 s (parity S1-B)
        assertThat(pk.get("userVerification").asString()).isEqualTo("preferred");
        assertThat(pk.get("allowCredentials").get(0).get("type").asString()).isEqualTo("public-key");
        assertThat(pk.get("allowCredentials").get(0).get("id").asString()).hasSize(43);
        assertThat(DB.fetchCount(OAUTH_OIDC_PAYLOADS, OAUTH_OIDC_PAYLOADS.ID.eq("WebauthnAuthentication:" + unknown.get("stateId").asString())))
                .as("nothing stored for a decoy").isZero();
        var decoyComplete = http.post("/auth/webauthn/authenticate/complete", Json.write(Map.of("stateId", unknown.get("stateId").asString(),
                "credential", Map.of("id", "x"))));
        assertThat(decoyComplete.statusCode()).isEqualTo(403);

        String email = "nokey-" + RUN + "@example.com";
        String pid = user(email);
        var noKey = json(http.post("/auth/webauthn/authenticate/begin", Json.write(Map.of("email", email))));
        assertThat(noKey.get("options").get("publicKey").has("rpId")).as("same decoy shape").isTrue();
        assertThat(failures(pid)).as("probing never records an attempt").isZero();

        // A registered key, then an assertion signed by the wrong private key.
        var auth = new SoftAuthenticator();
        JsonNode b = json(http.post("/auth/webauthn/register/begin", "{}", "Cookie", session(pid, email)));
        assertThat(http.post("/auth/webauthn/register/complete", Json.write(Map.of("stateId", b.get("stateId").asString(), "name", "K",
                "credential", auth.register(b.get("options"), ORIGIN))), "Cookie", session(pid, email)).statusCode()).isEqualTo(200);
        var a = json(http.post("/auth/webauthn/authenticate/begin", Json.write(Map.of("email", email))));
        auth.rogue = true;
        var bad = http.post("/auth/webauthn/authenticate/complete", Json.write(Map.of("stateId", a.get("stateId").asString(),
                "credential", auth.assertion(a.get("options"), ORIGIN, pid))));
        assertThat(bad.statusCode()).isEqualTo(403);
        assertThat(json(bad).get("error").asString()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(failures(pid)).as("a real ceremony that fails is an attempt").isEqualTo(1);
        assertThat(bad.headers().firstValue("set-cookie")).isEmpty();
        assertThat(http.post("/auth/webauthn/authenticate/begin", "{\"email\":\"\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void aLegacyRowIsSkippedInTheListAndNeverOffered() {
        String email = "legacy-" + RUN + "@example.com";
        String pid = user(email);
        DB.insertInto(WEBAUTHN_CREDENTIALS).set(WEBAUTHN_CREDENTIALS.ID, EntityType.WEBAUTHN_CREDENTIAL.generate())
                .set(WEBAUTHN_CREDENTIALS.PRINCIPAL_ID, pid).set(WEBAUTHN_CREDENTIALS.CREDENTIAL_ID, new byte[] {9, 9})
                .set(WEBAUTHN_CREDENTIALS.PASSKEY_DATA, JSONB.jsonb("{\"cred\":{\"cred_id\":\"x\"}}")).set(WEBAUTHN_CREDENTIALS.NAME, "old")
                .set(WEBAUTHN_CREDENTIALS.CREATED_AT, NOW).execute();
        var list = json(http.get("/auth/webauthn/credentials", "Cookie", session(pid, email)));
        assertThat(list.size()).as("legacy rows are skipped with a warning").isZero();
        var begin = json(http.post("/auth/webauthn/authenticate/begin", Json.write(Map.of("email", email))));
        assertThat(DB.fetchCount(OAUTH_OIDC_PAYLOADS, OAUTH_OIDC_PAYLOADS.ID.eq("WebauthnAuthentication:" + begin.get("stateId").asString())))
                .as("no usable credential ⇒ decoy").isZero();
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String session(String pid, String email) {
        return "fc_session=" + TOKEN_ISSUER.sessionToken(pid, email);
    }

    private static int failures(String pid) {
        return DB.fetchCount(IAM_LOGIN_ATTEMPTS, IAM_LOGIN_ATTEMPTS.PRINCIPAL_ID.eq(pid).and(IAM_LOGIN_ATTEMPTS.OUTCOME.eq("FAILURE")));
    }

    private static int events(String credentialId, String type) {
        return DB.fetchCount(MSG_EVENTS, MSG_EVENTS.SUBJECT.eq("platform.passkey." + credentialId).and(MSG_EVENTS.TYPE.eq(type)));
    }

    private static JsonNode json(HttpResponse<String> r) {
        return Json.MAPPER.readTree(r.body());
    }

    private static String user(String email) {
        String id = EntityType.PRINCIPAL.generate();
        DB.insertInto(IAM_PRINCIPALS).set(IAM_PRINCIPALS.ID, id).set(IAM_PRINCIPALS.TYPE, "USER").set(IAM_PRINCIPALS.SCOPE, "ANCHOR")
                .set(IAM_PRINCIPALS.NAME, "Pass Key").set(IAM_PRINCIPALS.ACTIVE, true).set(IAM_PRINCIPALS.ALL_APPLICATIONS, false)
                .set(IAM_PRINCIPALS.EMAIL, email).set(IAM_PRINCIPALS.EMAIL_DOMAIN, "example.com")
                .set(IAM_PRINCIPALS.CREATED_AT, NOW).set(IAM_PRINCIPALS.UPDATED_AT, NOW).execute();
        principals.add(id);
        return id;
    }
}
