package io.flowcatalyst.platform.auth.grant;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/// Refresh-token rotation, in one place so the two refresh surfaces
/// (`/oauth/token` `refresh_token` and `/auth/refresh`) cannot drift
/// (`docs/spec/auth-core.md` §7.4, §8.2; Go `grantstore.Rotate`):
///
///   - **Reuse detection** (OAuth 2.0 Security BCP §4.14.2): a presented
///     token that is not valid but was rotated out (has `replacedBy` and a
///     family) means the family is presumed compromised — every token in
///     it is revoked.
///   - **Rotation**: the presented token is revoked before its replacement
///     is inserted.
///   - **Lineage**: the replacement keeps the scopes, accessible clients,
///     OAuth client binding and `auth_time` (a rotation is not a
///     re-authentication), stays in the family (a legacy token roots a new
///     one at the replacement's id), and — the family's absolute cap —
///     **inherits the presented token's expiry**, never a fresh one.
///
/// The steps are three statements, not a transaction (Go parity): a crash
/// between revoke and insert strands the user into a re-login, and a crash
/// before `markReplaced` loses reuse detection for that hop only.
public final class RefreshRotation {

    /// What a rotation produced.
    ///
    /// @param stored   the token that was presented — empty when it was invalid, expired or revoked
    /// @param newRaw   the replacement's raw value — present only on a completed rotation
    /// @param replacement the replacement's stored form — present only on a completed rotation
    public record Result(Optional<RefreshToken> stored, Optional<String> newRaw, Optional<RefreshToken> replacement) {
        public static final Result INVALID = new Result(Optional.empty(), Optional.empty(), Optional.empty());

        public boolean rotated() {
            return replacement.isPresent();
        }

        @Override
        public String toString() {
            return "Result[stored=" + stored.map(RefreshToken::id) + ", rotated=" + rotated() + "]";
        }
    }

    /// A binding check that refused the presented token: nothing was rotated.
    public static final class NotAuthorized extends RuntimeException {
        private final RefreshToken stored;

        public NotAuthorized(String message, RefreshToken stored) {
            super(message);
            this.stored = stored;
        }

        public RefreshToken stored() {
            return stored;
        }
    }

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
    /// @param authorize the caller's binding check, run between lookup and
    ///                  revocation; return a message to refuse (nothing is
    ///                  rotated and [NotAuthorized] is thrown), `null` to allow
    public Result rotate(String raw, Function<RefreshToken, String> authorize) {
        String hash = RefreshToken.hash(raw);
        Optional<RefreshToken> valid = store.findValidByHash(hash);
        if (valid.isEmpty()) {
            store.findByHash(hash)
                    .filter(prior -> prior.wasReplaced() && prior.tokenFamily() != null)
                    .ifPresent(prior -> store.revokeAllInFamily(prior.tokenFamily()));
            return Result.INVALID;
        }
        RefreshToken stored = valid.get();
        if (authorize != null) {
            String refusal = authorize.apply(stored);
            if (refusal != null) {
                throw new NotAuthorized(refusal, stored);
            }
        }

        store.revokeByHash(hash);

        RefreshToken.Issued issued = RefreshToken.issue(stored.principalId(), clock.instant(), refreshTtlSeconds);
        RefreshToken replacement = issued.token()
                .withBinding(stored.oauthClientId(), stored.scopes(), stored.accessibleClients(), stored.authTime())
                .withExpiresAt(stored.expiresAt())
                .withFamily(stored.tokenFamily() != null ? stored.tokenFamily() : issued.token().id());
        store.insert(replacement);
        store.markReplaced(hash, replacement.tokenHash());
        return new Result(Optional.of(stored), Optional.of(issued.raw()), Optional.of(replacement));
    }
}
