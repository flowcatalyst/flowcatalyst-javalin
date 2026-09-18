package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.Objects;

/// A client-claimed hostname pending or completed DNS verification (spec
/// `function-registry.md` §6.5): once [#usableBy] is true, this hostname may
/// carry public [FunctionRoute]s for that client.
///
/// @param id                the domain's own `fnd_…` TSID
/// @param clientId          the claiming client
/// @param hostname          the claimed hostname
/// @param verificationToken the DNS TXT value the client must publish
/// @param verification      `Pending` \| `Verified`
/// @param createdAt         creation time
public record FunctionDomain(
        String id,
        String clientId,
        Hostname hostname,
        String verificationToken,
        Verification verification,
        Instant createdAt) implements HasId {

    public FunctionDomain {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(verificationToken, "verificationToken");
        Objects.requireNonNull(verification, "verification");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public sealed interface Verification {
        record Pending() implements Verification {
        }

        record Verified(Instant at) implements Verification {
            public Verified {
                Objects.requireNonNull(at, "at");
            }
        }
    }

    /// A freshly claimed, `Pending` domain.
    public static FunctionDomain claim(String clientId, Hostname hostname, String verificationToken, Instant now) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(verificationToken, "verificationToken");
        Objects.requireNonNull(now, "now");
        return new FunctionDomain(EntityType.FUNCTION_DOMAIN.generate(), clientId, hostname, verificationToken,
                new Verification.Pending(), now);
    }

    /// `Pending` ⇒ `Verified(now)`.
    ///
    /// @throws UseCaseException conflict `DOMAIN_ALREADY_VERIFIED`
    public FunctionDomain verified(Instant now) {
        Objects.requireNonNull(now, "now");
        if (verification instanceof Verification.Verified) {
            throw UseCaseException.conflict("DOMAIN_ALREADY_VERIFIED", "domain is already verified");
        }
        return new FunctionDomain(id, clientId, hostname, verificationToken, new Verification.Verified(now), createdAt);
    }

    /// Verified, and owned by `clientId`.
    public boolean usableBy(String clientId) {
        return verification instanceof Verification.Verified && this.clientId.equals(clientId);
    }

    /// Masks the verification token — never printed in full
    /// (`CONVENTIONS.md` §8).
    @Override
    public String toString() {
        return "FunctionDomain[id=" + id + ", clientId=" + clientId + ", hostname=" + hostname
                + ", verificationToken=***, verification=" + verification + ", createdAt=" + createdAt + "]";
    }
}
