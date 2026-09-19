package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.Instant;
import java.util.Objects;

/// A claimed hostname pending or completed DNS verification (spec
/// `function-registry.md` §6.5): once [#usableBy] is true, this hostname may
/// carry public [FunctionRoute]s for its owner — a client, or the platform
/// (ruling R2).
///
/// @param id                the domain's own `fnd_…` TSID
/// @param owner             the claiming client, or the platform
/// @param hostname          the claimed hostname
/// @param verificationToken the DNS TXT value the owner must publish
/// @param verification      `Pending` \| `Verified`
/// @param createdAt         creation time
public record FunctionDomain(
        String id,
        FunctionOwner owner,
        Hostname hostname,
        String verificationToken,
        Verification verification,
        Instant createdAt) implements HasId {

    public FunctionDomain {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
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
    public static FunctionDomain claim(FunctionOwner owner, Hostname hostname, String verificationToken, Instant now) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(verificationToken, "verificationToken");
        Objects.requireNonNull(now, "now");
        return new FunctionDomain(EntityType.FUNCTION_DOMAIN.generate(), owner, hostname, verificationToken,
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
        return new FunctionDomain(id, owner, hostname, verificationToken, new Verification.Verified(now), createdAt);
    }

    /// Verified, and owned by `owner` (`Platform` matches only `Platform`,
    /// spec §6.5, §8 M20).
    public boolean usableBy(FunctionOwner owner) {
        return verification instanceof Verification.Verified && this.owner.equals(owner);
    }

    /// Masks the verification token — never printed in full
    /// (`CONVENTIONS.md` §8).
    @Override
    public String toString() {
        return "FunctionDomain[id=" + id + ", owner=" + owner + ", hostname=" + hostname
                + ", verificationToken=***, verification=" + verification + ", createdAt=" + createdAt + "]";
    }
}
