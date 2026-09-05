package io.flowcatalyst.platform.auth.grant;

import io.flowcatalyst.platform.shared.json.Json;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.flowcatalyst.db.generated.Tables.OAUTH_OIDC_PAYLOADS;

/// The OAuth grant store over `oauth_oidc_payloads` (`docs/spec/auth-core.md`
/// §3.7, §11; Go `grantstore`): authorization codes, refresh tokens and
/// pending-auth stashes, each a row keyed `"{Type}:{id}"`, discriminated by
/// `type`, with a **camelCase JSONB payload** — byte-for-byte the shape a Go
/// instance sharing the database writes and reads, so the two can be mixed
/// during a cutover.
///
/// These are infrastructure rows, not aggregates: no unit of work, no
/// events, no audit. Every write is a single statement; the code consume is
/// one atomic `UPDATE … RETURNING`, so exactly one of two concurrent
/// redeemers wins.
public final class GrantStore {

    static final String TYPE_AUTH_CODE = "AuthorizationCode";
    static final String TYPE_REFRESH_TOKEN = "RefreshToken";
    static final String TYPE_PENDING_AUTH = "PendingAuth";

    /// Pending-auth rows expire after ten minutes; written for wire
    /// compatibility only (ruling C-Q3), consumed by nothing.
    static final long PENDING_AUTH_TTL_SECONDS = 600;

    private static final DateTimeFormatter RFC3339_NANO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final DSLContext dsl;
    private final Clock clock;

    public GrantStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    public GrantStore(DataSource dataSource, Clock clock) {
        this.dsl = DSL.using(Objects.requireNonNull(dataSource, "dataSource"), SQLDialect.POSTGRES);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /// The same store over one connection (a test holding a transaction open).
    GrantStore(Connection connection, Clock clock) {
        this.dsl = DSL.using(connection, SQLDialect.POSTGRES);
        this.clock = clock;
    }

    // ── authorization codes ───────────────────────────────────────────────

    /// Upsert on id: payload and expiry replaced.
    public void insert(AuthorizationCode c) {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("accountId", c.principalId());
        p.put("clientId", c.clientId());
        p.put("redirectUri", c.redirectUri());
        putNullable(p, "scope", c.scope());
        putNullable(p, "codeChallenge", c.codeChallenge());
        putNullable(p, "codeChallengeMethod", c.codeChallengeMethod());
        putNullable(p, "nonce", c.nonce());
        putNullable(p, "state", c.state());
        putNullable(p, "contextClientId", c.contextClientId());
        p.put("kind", TYPE_AUTH_CODE);
        p.put("iat", c.createdAt().getEpochSecond());
        p.put("exp", c.expiresAt().getEpochSecond());
        if (c.authTime() != null) {
            p.put("authTime", c.authTime().getEpochSecond());
        }
        JSONB payload = JSONB.jsonb(Json.write(p));
        dsl.insertInto(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.ID, TYPE_AUTH_CODE + ":" + c.code())
                .set(OAUTH_OIDC_PAYLOADS.TYPE, TYPE_AUTH_CODE)
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, payload)
                .set(OAUTH_OIDC_PAYLOADS.EXPIRES_AT, utc(c.expiresAt()))
                .set(OAUTH_OIDC_PAYLOADS.CREATED_AT, utc(c.createdAt()))
                .onConflict(OAUTH_OIDC_PAYLOADS.ID).doUpdate()
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, payload)
                .set(OAUTH_OIDC_PAYLOADS.EXPIRES_AT, utc(c.expiresAt()))
                .execute();
    }

    /// Atomically marks a still-valid code consumed and returns it; empty
    /// when the code is missing, expired, or already consumed. Two
    /// concurrent redeemers: exactly one gets the code.
    public Optional<AuthorizationCode> findAndConsume(String code) {
        Record row = dsl.update(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.CONSUMED_AT, DSL.currentOffsetDateTime())
                .where(OAUTH_OIDC_PAYLOADS.ID.eq(TYPE_AUTH_CODE + ":" + code))
                .and(OAUTH_OIDC_PAYLOADS.CONSUMED_AT.isNull())
                .and(OAUTH_OIDC_PAYLOADS.EXPIRES_AT.gt(DSL.currentOffsetDateTime()))
                .returning(OAUTH_OIDC_PAYLOADS.ID, OAUTH_OIDC_PAYLOADS.PAYLOAD, OAUTH_OIDC_PAYLOADS.EXPIRES_AT,
                        OAUTH_OIDC_PAYLOADS.CONSUMED_AT, OAUTH_OIDC_PAYLOADS.CREATED_AT)
                .fetchOne();
        return Optional.ofNullable(row).map(GrantStore::toAuthCode);
    }

    /// The code regardless of state.
    public Optional<AuthorizationCode> findCode(String code) {
        Record row = dsl.select(OAUTH_OIDC_PAYLOADS.ID, OAUTH_OIDC_PAYLOADS.PAYLOAD, OAUTH_OIDC_PAYLOADS.EXPIRES_AT,
                        OAUTH_OIDC_PAYLOADS.CONSUMED_AT, OAUTH_OIDC_PAYLOADS.CREATED_AT)
                .from(OAUTH_OIDC_PAYLOADS)
                .where(OAUTH_OIDC_PAYLOADS.ID.eq(TYPE_AUTH_CODE + ":" + code))
                .fetchOne();
        return Optional.ofNullable(row).map(GrantStore::toAuthCode);
    }

    private static AuthorizationCode toAuthCode(Record row) {
        JsonNode p = Json.MAPPER.readTree(row.get(OAUTH_OIDC_PAYLOADS.PAYLOAD).data());
        Instant createdAt = row.get(OAUTH_OIDC_PAYLOADS.CREATED_AT).toInstant();
        OffsetDateTime exp = row.get(OAUTH_OIDC_PAYLOADS.EXPIRES_AT);
        return new AuthorizationCode(
                row.get(OAUTH_OIDC_PAYLOADS.ID).substring(TYPE_AUTH_CODE.length() + 1),
                text(p, "clientId"),
                text(p, "accountId"),
                text(p, "redirectUri"),
                text(p, "scope"),
                text(p, "codeChallenge"),
                text(p, "codeChallengeMethod"),
                text(p, "nonce"),
                text(p, "state"),
                text(p, "contextClientId"),
                epochOrNull(p, "authTime"),
                createdAt,
                exp == null ? createdAt.plusSeconds(AuthorizationCode.TTL_SECONDS) : exp.toInstant(),
                row.get(OAUTH_OIDC_PAYLOADS.CONSUMED_AT) != null);
    }

    // ── refresh tokens ────────────────────────────────────────────────────

    /// Upsert on id; `grant_id` mirrors the family so a whole family can be
    /// revoked by column; `accessibleClients` is always an array.
    public void insert(RefreshToken t) {
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("accountId", t.principalId());
        putNullable(p, "clientId", t.oauthClientId());
        p.put("tokenHash", t.tokenHash());
        p.put("scope", String.join(" ", t.scopes()));
        var clients = p.putArray("accessibleClients");
        t.accessibleClients().forEach(clients::add);
        p.put("revoked", t.revoked());
        putNullable(p, "revokedAt", t.revokedAt() == null ? null : rfc3339(t.revokedAt()));
        putNullable(p, "tokenFamily", t.tokenFamily());
        putNullable(p, "replacedBy", t.replacedBy());
        putNullable(p, "lastUsedAt", t.lastUsedAt() == null ? null : rfc3339(t.lastUsedAt()));
        putNullable(p, "createdFromIp", t.createdFromIp());
        putNullable(p, "userAgent", t.userAgent());
        p.put("iat", t.createdAt().getEpochSecond());
        p.put("exp", t.expiresAt().getEpochSecond());
        p.put("kind", TYPE_REFRESH_TOKEN);
        if (t.authTime() != null) {
            p.put("authTime", t.authTime().getEpochSecond());
        }
        JSONB payload = JSONB.jsonb(Json.write(p));
        dsl.insertInto(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.ID, TYPE_REFRESH_TOKEN + ":" + t.id())
                .set(OAUTH_OIDC_PAYLOADS.TYPE, TYPE_REFRESH_TOKEN)
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, payload)
                .set(OAUTH_OIDC_PAYLOADS.GRANT_ID, t.tokenFamily())
                .set(OAUTH_OIDC_PAYLOADS.EXPIRES_AT, utc(t.expiresAt()))
                .set(OAUTH_OIDC_PAYLOADS.CREATED_AT, utc(t.createdAt()))
                .onConflict(OAUTH_OIDC_PAYLOADS.ID).doUpdate()
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, payload)
                .set(OAUTH_OIDC_PAYLOADS.GRANT_ID, t.tokenFamily())
                .set(OAUTH_OIDC_PAYLOADS.EXPIRES_AT, utc(t.expiresAt()))
                .execute();
    }

    /// By hash regardless of state.
    public Optional<RefreshToken> findByHash(String tokenHash) {
        Record row = dsl.select(OAUTH_OIDC_PAYLOADS.ID, OAUTH_OIDC_PAYLOADS.PAYLOAD, OAUTH_OIDC_PAYLOADS.EXPIRES_AT,
                        OAUTH_OIDC_PAYLOADS.CREATED_AT)
                .from(OAUTH_OIDC_PAYLOADS)
                .where(OAUTH_OIDC_PAYLOADS.TYPE.eq(TYPE_REFRESH_TOKEN))
                .and(payloadText("tokenHash").eq(tokenHash))
                .fetchOne();
        return Optional.ofNullable(row).map(GrantStore::toRefreshToken);
    }

    /// By hash, unexpired, unconsumed, and not revoked.
    public Optional<RefreshToken> findValidByHash(String tokenHash) {
        Record row = dsl.select(OAUTH_OIDC_PAYLOADS.ID, OAUTH_OIDC_PAYLOADS.PAYLOAD, OAUTH_OIDC_PAYLOADS.EXPIRES_AT,
                        OAUTH_OIDC_PAYLOADS.CREATED_AT)
                .from(OAUTH_OIDC_PAYLOADS)
                .where(OAUTH_OIDC_PAYLOADS.TYPE.eq(TYPE_REFRESH_TOKEN))
                .and(payloadText("tokenHash").eq(tokenHash))
                .and(OAUTH_OIDC_PAYLOADS.EXPIRES_AT.gt(DSL.currentOffsetDateTime()))
                .and(OAUTH_OIDC_PAYLOADS.CONSUMED_AT.isNull())
                .fetchOne();
        return Optional.ofNullable(row).map(GrantStore::toRefreshToken).filter(t -> !t.revoked());
    }

    /// Records the hash of the token that replaced this one.
    public boolean markReplaced(String tokenHash, String newTokenHash) {
        return dsl.update(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, DSL.field("jsonb_set({0}, '{replacedBy}', to_jsonb({1}::text))",
                        JSONB.class, OAUTH_OIDC_PAYLOADS.PAYLOAD, DSL.val(newTokenHash)))
                .where(OAUTH_OIDC_PAYLOADS.TYPE.eq(TYPE_REFRESH_TOKEN))
                .and(payloadText("tokenHash").eq(tokenHash))
                .execute() > 0;
    }

    /// Revokes one token: `revoked=true`, `revokedAt`, and `consumed_at`.
    public boolean revokeByHash(String tokenHash) {
        Instant now = clock.instant();
        return dsl.update(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, revokedPayload(now))
                .set(OAUTH_OIDC_PAYLOADS.CONSUMED_AT, utc(now))
                .where(OAUTH_OIDC_PAYLOADS.TYPE.eq(TYPE_REFRESH_TOKEN))
                .and(payloadText("tokenHash").eq(tokenHash))
                .execute() > 0;
    }

    /// Revokes every still-active token of a rotation family (by `grant_id`).
    public int revokeAllInFamily(String family) {
        Instant now = clock.instant();
        return dsl.update(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, revokedPayload(now))
                .set(OAUTH_OIDC_PAYLOADS.CONSUMED_AT, utc(now))
                .where(OAUTH_OIDC_PAYLOADS.TYPE.eq(TYPE_REFRESH_TOKEN))
                .and(OAUTH_OIDC_PAYLOADS.GRANT_ID.eq(family))
                .and(notRevoked())
                .execute();
    }

    /// Revokes every active refresh token of a principal (logout-all,
    /// password change, password reset).
    public int revokeAllForPrincipal(String principalId) {
        Instant now = clock.instant();
        return dsl.update(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, revokedPayload(now))
                .set(OAUTH_OIDC_PAYLOADS.CONSUMED_AT, utc(now))
                .where(OAUTH_OIDC_PAYLOADS.TYPE.eq(TYPE_REFRESH_TOKEN))
                .and(payloadText("accountId").eq(principalId))
                .and(OAUTH_OIDC_PAYLOADS.CONSUMED_AT.isNull())
                .and(OAUTH_OIDC_PAYLOADS.EXPIRES_AT.gt(DSL.currentOffsetDateTime()))
                .and(notRevoked())
                .execute();
    }

    private org.jooq.Field<JSONB> revokedPayload(Instant now) {
        return DSL.field("jsonb_set(jsonb_set({0}, '{revoked}', 'true'::jsonb), '{revokedAt}', to_jsonb({1}::text))",
                JSONB.class, OAUTH_OIDC_PAYLOADS.PAYLOAD, DSL.val(rfc3339(now)));
    }

    private static org.jooq.Condition notRevoked() {
        return payloadText("revoked").isNull().or(payloadText("revoked").eq("false"));
    }

    private static RefreshToken toRefreshToken(Record row) {
        JsonNode p = Json.MAPPER.readTree(row.get(OAUTH_OIDC_PAYLOADS.PAYLOAD).data());
        Instant createdAt = row.get(OAUTH_OIDC_PAYLOADS.CREATED_AT).toInstant();
        OffsetDateTime exp = row.get(OAUTH_OIDC_PAYLOADS.EXPIRES_AT);
        String scope = text(p, "scope");
        List<String> scopes = scope == null || scope.isBlank() ? List.of() : List.of(scope.trim().split("\\s+"));
        var clients = new ArrayList<String>();
        JsonNode ac = p.get("accessibleClients");
        if (ac != null && ac.isArray()) {
            ac.forEach(n -> clients.add(n.asString()));
        }
        return new RefreshToken(
                row.get(OAUTH_OIDC_PAYLOADS.ID).substring(TYPE_REFRESH_TOKEN.length() + 1),
                text(p, "tokenHash"),
                text(p, "accountId"),
                text(p, "clientId"),
                scopes,
                clients,
                p.path("revoked").asBoolean(false),
                parseRfc3339(text(p, "revokedAt")),
                text(p, "tokenFamily"),
                text(p, "replacedBy"),
                parseRfc3339(text(p, "lastUsedAt")),
                text(p, "createdFromIp"),
                text(p, "userAgent"),
                epochOrNull(p, "authTime"),
                createdAt,
                exp == null ? createdAt.plusSeconds(RefreshToken.TTL_SECONDS) : exp.toInstant());
    }

    // ── pending auth (wire compatibility only, ruling C-Q3) ───────────────

    /// @param clientId            the OAuth client
    /// @param redirectUri         the registered redirect
    /// @param scope               nullable
    /// @param codeChallenge       nullable
    /// @param codeChallengeMethod nullable
    /// @param nonce               nullable
    public record PendingAuth(String clientId, String redirectUri, String scope, String codeChallenge,
                              String codeChallengeMethod, String nonce, Instant createdAt) {
    }

    public void insertPendingAuth(String state, PendingAuth pa) {
        Instant now = clock.instant();
        ObjectNode p = Json.MAPPER.createObjectNode();
        p.put("clientId", pa.clientId());
        p.put("redirectUri", pa.redirectUri());
        putNullable(p, "scope", pa.scope());
        putNullable(p, "codeChallenge", pa.codeChallenge());
        putNullable(p, "codeChallengeMethod", pa.codeChallengeMethod());
        putNullable(p, "nonce", pa.nonce());
        p.put("createdAt", rfc3339(pa.createdAt() == null ? now : pa.createdAt()));
        JSONB payload = JSONB.jsonb(Json.write(p));
        OffsetDateTime expires = utc(now.plusSeconds(PENDING_AUTH_TTL_SECONDS));
        dsl.insertInto(OAUTH_OIDC_PAYLOADS)
                .set(OAUTH_OIDC_PAYLOADS.ID, TYPE_PENDING_AUTH + ":" + state)
                .set(OAUTH_OIDC_PAYLOADS.TYPE, TYPE_PENDING_AUTH)
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, payload)
                .set(OAUTH_OIDC_PAYLOADS.EXPIRES_AT, expires)
                .set(OAUTH_OIDC_PAYLOADS.CREATED_AT, utc(now))
                .onConflict(OAUTH_OIDC_PAYLOADS.ID).doUpdate()
                .set(OAUTH_OIDC_PAYLOADS.PAYLOAD, payload)
                .set(OAUTH_OIDC_PAYLOADS.EXPIRES_AT, expires)
                .execute();
    }

    /// `DELETE … RETURNING`: single-use; empty when missing or expired.
    public Optional<PendingAuth> consumePendingAuth(String state) {
        Record row = dsl.deleteFrom(OAUTH_OIDC_PAYLOADS)
                .where(OAUTH_OIDC_PAYLOADS.ID.eq(TYPE_PENDING_AUTH + ":" + state))
                .and(OAUTH_OIDC_PAYLOADS.CONSUMED_AT.isNull())
                .and(OAUTH_OIDC_PAYLOADS.EXPIRES_AT.gt(DSL.currentOffsetDateTime()))
                .returning(OAUTH_OIDC_PAYLOADS.PAYLOAD)
                .fetchOne();
        if (row == null) {
            return Optional.empty();
        }
        JsonNode p = Json.MAPPER.readTree(row.get(OAUTH_OIDC_PAYLOADS.PAYLOAD).data());
        Instant createdAt = Optional.ofNullable(parseRfc3339(text(p, "createdAt"))).orElse(clock.instant());
        return Optional.of(new PendingAuth(text(p, "clientId"), text(p, "redirectUri"), text(p, "scope"),
                text(p, "codeChallenge"), text(p, "codeChallengeMethod"), text(p, "nonce"), createdAt));
    }

    // ── housekeeping ──────────────────────────────────────────────────────

    /// Deletes expired rows of every type this store writes; the purger's sweep.
    public int deleteExpired() {
        return dsl.deleteFrom(OAUTH_OIDC_PAYLOADS)
                .where(OAUTH_OIDC_PAYLOADS.TYPE.in(TYPE_AUTH_CODE, TYPE_REFRESH_TOKEN, TYPE_PENDING_AUTH))
                .and(OAUTH_OIDC_PAYLOADS.EXPIRES_AT.lt(DSL.currentOffsetDateTime()))
                .execute();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static org.jooq.Field<String> payloadText(String key) {
        return DSL.field("{0} ->> {1}", String.class, OAUTH_OIDC_PAYLOADS.PAYLOAD, DSL.inline(key));
    }

    private static void putNullable(ObjectNode node, String key, String value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value);
        }
    }

    private static String text(JsonNode p, String key) {
        JsonNode n = p.get(key);
        return n == null || n.isNull() ? null : n.asString();
    }

    private static Instant epochOrNull(JsonNode p, String key) {
        JsonNode n = p.get(key);
        if (n == null || n.isNull() || !n.isNumber() || n.asLong() == 0) {
            return null;
        }
        return Instant.ofEpochSecond(n.asLong());
    }

    static String rfc3339(Instant t) {
        return RFC3339_NANO.format(t.atOffset(ZoneOffset.UTC));
    }

    static Instant parseRfc3339(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static OffsetDateTime utc(Instant t) {
        return t.atOffset(ZoneOffset.UTC);
    }
}
