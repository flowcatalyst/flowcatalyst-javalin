package io.flowcatalyst.platform.passkey;

import io.flowcatalyst.platform.shared.json.Json;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_PAYLOADS;

/// Ceremony state in the shared `oauth_oidc_payloads` table (§3.5): id
/// `WebauthnRegistration:<stateId>` / `WebauthnAuthentication:<stateId>`,
/// payload `{principalId, session, displayName?}`, ten minutes, consumed
/// by `DELETE … WHERE expires_at > now() RETURNING`. `session` is this
/// implementation's own serialised request — a ceremony never outlives a
/// deployment, so the shape need not match Go's.
public final class CeremonyRepository {

    public static final String REGISTRATION = "WebauthnRegistration";
    public static final String AUTHENTICATION = "WebauthnAuthentication";
    public static final Duration TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Registration(String principalId, String session, String displayName) {
    }

    public record Authentication(String principalId, String session) {
    }

    private final DSLContext dsl;
    private final Clock clock;

    public CeremonyRepository(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public CeremonyRepository(DataSource dataSource, Clock clock) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// 16 random bytes, base64url unpadded: 22 characters.
    public static String newStateId() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public void storeRegistration(String stateId, Registration r) {
        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.put("principalId", r.principalId());
        payload.put("session", r.session());
        if (r.displayName() != null) {
            payload.put("displayName", r.displayName());
        } else {
            payload.putNull("displayName");
        }
        store(REGISTRATION, stateId, payload);
    }

    public Optional<Registration> consumeRegistration(String stateId) {
        return consume(REGISTRATION, stateId).map(n -> new Registration(n.path("principalId").asString(null),
                n.path("session").asString(null), n.path("displayName").isNull() ? null : n.path("displayName").asString(null)));
    }

    public void storeAuthentication(String stateId, Authentication a) {
        ObjectNode payload = Json.MAPPER.createObjectNode();
        payload.put("principalId", a.principalId());
        payload.put("session", a.session());
        store(AUTHENTICATION, stateId, payload);
    }

    public Optional<Authentication> consumeAuthentication(String stateId) {
        return consume(AUTHENTICATION, stateId).map(n -> new Authentication(n.path("principalId").asString(null),
                n.path("session").asString(null)));
    }

    private void store(String type, String stateId, ObjectNode payload) {
        Instant now = clock.instant();
        JSONB data = JSONB.jsonb(Json.write(payload));
        dsl.insertInto(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.ID, type + ":" + stateId)
                .set(OAUTH_OIDC_PAYLOADS.TYPE, type)
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, data)
                .set(OAUTH_OIDC_PAYLOADS.EXPIRES_AT, now.plus(TTL).atOffset(ZoneOffset.UTC))
                .set(OAUTH_OIDC_PAYLOADS.CREATED_AT, now.atOffset(ZoneOffset.UTC))
                .onConflict(OAUTH_OIDC_PAYLOADS.ID).doUpdate()
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, data)
                .set(OAUTH_OIDC_PAYLOADS.EXPIRES_AT, now.plus(TTL).atOffset(ZoneOffset.UTC))
                .execute();
    }

    private Optional<JsonNode> consume(String type, String stateId) {
        if (stateId == null || stateId.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return dsl.deleteFrom(OAUTH_OIDC_PAYLOADS)
                .where(OAUTH_OIDC_PAYLOADS.ID.eq(type + ":" + stateId))
                .and(OAUTH_OIDC_PAYLOADS.EXPIRES_AT.isNull().or(OAUTH_OIDC_PAYLOADS.EXPIRES_AT.gt(now.atOffset(ZoneOffset.UTC))))
                .returning(OAUTH_OIDC_PAYLOADS.PAYLOAD)
                .fetchOptional()
                .map(r -> Json.MAPPER.readTree(r.getPayload().data()))
                .filter(n -> n.has("session") && !n.path("session").isNull());
    }
}
