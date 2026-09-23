package io.flowcatalyst.platform.function;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Objects;

/// A claimed hostname (spec `function-registry.md` §6.5, amended by
/// `function-domains-no-dns.md`): a claim is verified by being made — with
/// one operator claiming every domain there is nobody to defend against, so
/// this hostname may carry public [FunctionRoute]s for its owner (a client,
/// or the platform, ruling R2) the instant it is claimed.
///
/// @param id        the domain's own `fnd_…` TSID
/// @param owner     the claiming client, or the platform
/// @param hostname  the claimed hostname
/// @param createdAt creation time
public record FunctionDomain(
        String id,
        FunctionOwner owner,
        Hostname hostname,
        Instant createdAt) implements HasId {

    public FunctionDomain {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    /// A freshly claimed, immediately usable domain (spec
    /// `function-domains-no-dns.md`: "a claim is verified by being made").
    public static FunctionDomain claim(FunctionOwner owner, Hostname hostname, Instant now) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(hostname, "hostname");
        Objects.requireNonNull(now, "now");
        return new FunctionDomain(EntityType.FUNCTION_DOMAIN.generate(), owner, hostname, now);
    }
}
