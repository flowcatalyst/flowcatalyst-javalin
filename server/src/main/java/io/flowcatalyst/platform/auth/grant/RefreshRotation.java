package io.flowcatalyst.platform.auth.grant;

import io.flowcatalyst.sdk.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/// Refresh-token rotation, in one place so the two refresh surfaces
/// (`/oauth/token` `refresh_token` and `/auth/refresh`) cannot drift
/// (`docs/spec/auth-core.md` §7.4, §8.2; `docs/spec/security-fixes-2026-09-24.md`
/// S2.5):
///
///   - **Single use, atomically.** The presented token is consumed by one
///     `UPDATE … WHERE consumed_at IS NULL … RETURNING`; the replacement is
///     issued only when that statement changed exactly one row. Consume,
///     insert and `markReplaced` share one transaction, so of two concurrent
///     presentations exactly one rotates, and the other — blocked on the row
///     until the winner commits — then sees a token already replaced.
///   - **Reuse detection** (OAuth 2.0 Security BCP §4.14.2): a presented
///     token that is no longer valid but was rotated out (has `replacedBy`
///     and a family) means the family is presumed compromised — every token
///     in it is revoked, and the event is logged at WARN with the family.
///   - **Replay leeway** ([#REPLAY_LEEWAY]): a token rotated out moments ago
///     is presented again by its own client far more often than by a thief —
///     two requests of one session refreshing at once (the SDKs hold no lock),
///     or a retry after a response that never arrived. Within the leeway, and
///     only while the family is intact (the replacement is still valid), the
///     presentation rotates again into a sibling in the same family instead of
///     revoking it. After the leeway it is reuse, as above. A fixed constant,
///     not a setting (the "reuse interval" of the common OAuth providers).
///   - **Binding.** A token issued to an OAuth client may be refreshed only
///     by that client; one issued outside any client, by anyone presenting
///     it. A refusal consumes nothing.
///   - **Lineage**: the replacement keeps the scopes, accessible clients,
///     OAuth client binding and `auth_time` (a rotation is not a
///     re-authentication), stays in the family (a legacy token roots a new
///     one at the replacement's id), and — the family's absolute cap —
///     **inherits the presented token's expiry**, never a fresh one.
public final class RefreshRotation {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshRotation.class);

    /// A completed rotation.
    ///
    /// @param stored      the token that was presented (now consumed)
    /// @param newRaw      the replacement's raw value — handed to the caller once, never stored
    /// @param replacement the replacement's stored form
    public record Rotated(RefreshToken stored, String newRaw, RefreshToken replacement) {
        public Rotated {
            Objects.requireNonNull(stored, "stored");
            Objects.requireNonNull(newRaw, "newRaw");
            Objects.requireNonNull(replacement, "replacement");
        }

        @Override
        public String toString() {
            return "Rotated[stored=" + stored.id() + ", replacement=" + replacement.id() + "]";
        }
    }

    /// Why nothing was rotated. `reason` is what a log line says; every case
    /// states its own.
    public sealed interface Rejection {

        String reason();

        /// Never issued, expired, or revoked — and not a replay of a rotated-out token.
        record Unknown() implements Rejection {
            @Override
            public String reason() {
                return "unknown, expired or revoked refresh token";
            }
        }

        /// A rotated-out token was presented again: its whole family was revoked.
        ///
        /// @param family       the revoked family (`grant_id`)
        /// @param revokedCount how many still-live tokens the revocation caught
        record ReuseDetected(String family, int revokedCount) implements Rejection {
            public ReuseDetected {
                Objects.requireNonNull(family, "family");
            }

            @Override
            public String reason() {
                return "refresh token reuse detected; family revoked";
            }
        }

        /// The token is bound to another OAuth client than the one presenting it.
        ///
        /// @param tokenClientId      the client the token was issued to
        /// @param requestingClientId the authenticated client presenting it; `null` when none
        record Refused(String tokenClientId, String requestingClientId) implements Rejection {
            public Refused {
                Objects.requireNonNull(tokenClientId, "tokenClientId");
            }

            @Override
            public String reason() {
                return "Token was not issued to this client";
            }
        }
    }

    /// How long after a token is rotated out a second presentation of it is
    /// still its own client racing or retrying, not a replay (see the class doc).
    static final Duration REPLAY_LEEWAY = Duration.ofSeconds(10);

    private final GrantStore store;
    private final Clock clock;
    private final long refreshTtlSeconds;

    /// @param refreshTtlSeconds the TTL a freshly-rooted family gets at issuance
    ///                          (`Env.refreshTokenTtlSeconds()`, owner ruling 2026-09-11
    ///                          supersedes C-Q16); irrelevant to a rotation itself, which
    ///                          always inherits the presented token's own expiry below —
    ///                          [RefreshToken#issue] still needs a value to construct with.
    public RefreshRotation(GrantStore store, Clock clock, long refreshTtlSeconds) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    /// Consumes `raw` and issues its replacement.
    ///
    /// @param requestingClientId the authenticated OAuth client presenting the
    ///                           token; `null` when the caller is no client
    ///                           (`/auth/refresh`) — then only a token issued
    ///                           outside any client rotates
    public Result<Rotated, Rejection> rotate(String raw, String requestingClientId) {
        String hash = RefreshToken.hash(raw);
        Optional<RefreshToken> valid = store.findValidByHash(hash);
        if (valid.isEmpty()) {
            return notValid(hash, requestingClientId);
        }
        RefreshToken stored = valid.get();
        if (stored.oauthClientId() != null && !stored.oauthClientId().equals(requestingClientId)) {
            return Result.err(new Rejection.Refused(stored.oauthClientId(), requestingClientId));
        }

        RefreshToken.Issued issued = successorOf(stored);
        RefreshToken replacement = issued.token();
        boolean rotated = store.inTransaction(tx -> {
            if (tx.consumeValidByHash(hash).isEmpty()) {
                return false; // someone else consumed it between the read and here
            }
            tx.insert(replacement);
            tx.markReplaced(hash, replacement.tokenHash());
            return true;
        });
        if (!rotated) {
            // The winner has committed by now (our UPDATE waited on its row
            // lock), so the token reads as rotated out moments ago: the leeway
            // case, handled below like any second presentation.
            return notValid(hash, requestingClientId);
        }
        return Result.ok(new Rotated(stored, issued.raw(), replacement));
    }

    /// The replacement for `stored`: its lineage (binding, scopes, clients,
    /// `auth_time`), its family (a legacy token roots one here) and its expiry
    /// cap — never a fresh one.
    private RefreshToken.Issued successorOf(RefreshToken stored) {
        RefreshToken.Issued issued = RefreshToken.issue(stored.principalId(), clock.instant(), refreshTtlSeconds);
        RefreshToken token = issued.token()
                .withBinding(stored.oauthClientId(), stored.scopes(), stored.accessibleClients(), stored.authTime())
                .withExpiresAt(stored.expiresAt())
                .withFamily(stored.tokenFamily() != null ? stored.tokenFamily() : issued.token().id());
        return new RefreshToken.Issued(issued.raw(), token);
    }

    /// A presented token that is not valid. Rotated out within the leeway, with
    /// its family intact: a sibling replacement (see the class doc). Rotated out
    /// earlier, or its family already revoked: reuse — the family is revoked.
    /// Anything else is simply unknown.
    private Result<Rotated, Rejection> notValid(String hash, String requestingClientId) {
        Optional<RefreshToken> prior = store.findByHash(hash)
                .filter(t -> t.wasReplaced() && t.tokenFamily() != null);
        if (prior.isEmpty()) {
            return Result.err(new Rejection.Unknown());
        }
        RefreshToken p = prior.get();
        if (withinLeeway(p) && store.findValidByHash(p.replacedBy()).isPresent()) {
            if (p.oauthClientId() != null && !p.oauthClientId().equals(requestingClientId)) {
                return Result.err(new Rejection.Refused(p.oauthClientId(), requestingClientId));
            }
            RefreshToken.Issued sibling = successorOf(p);
            store.insert(sibling.token());
            return Result.ok(new Rotated(p, sibling.raw(), sibling.token()));
        }
        return Result.err(reuse(p));
    }

    private boolean withinLeeway(RefreshToken rotatedOut) {
        Instant at = rotatedOut.revokedAt();
        return at != null && !at.isBefore(clock.instant().minus(REPLAY_LEEWAY));
    }

    private Rejection reuse(RefreshToken prior) {
        String family = prior.tokenFamily();
        int revoked = store.revokeAllInFamily(family);
        LOG.atWarn().setMessage("refresh token reuse detected; family revoked")
                .addKeyValue("family", family)
                .addKeyValue("token", prior.id())
                .addKeyValue("principal", prior.principalId())
                .addKeyValue("revoked", revoked)
                .log();
        return new Rejection.ReuseDetected(family, revoked);
    }
}
