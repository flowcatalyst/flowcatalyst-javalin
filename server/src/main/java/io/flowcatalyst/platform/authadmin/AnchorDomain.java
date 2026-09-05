package io.flowcatalyst.platform.authadmin;

import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/// An e-mail domain whose users are anchor (platform operator) users (spec
/// §1). Read by the login flow ([#matchesEmail]); no client dimension.
/// Anchor domains are anchor-only platform configuration with no
/// per-resource scope.
///
/// @param id        `anc_…` TSID
/// @param domain    normalised (trimmed, lower-cased) domain, unique
/// @param createdAt creation time
/// @param updatedAt last change
public record AnchorDomain(String id, String domain, Instant createdAt, Instant updatedAt) implements HasId {

    public AnchorDomain {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /// A fresh anchor domain. The domain arrives already normalised and
    /// validated ([AnchorDomainValue#parse]).
    public static AnchorDomain create(AnchorDomainValue domain) {
        Instant now = Instant.now();
        return new AnchorDomain(EntityType.ANCHOR_DOMAIN.generate(), domain.value(), now, now);
    }

    /// Replaces the domain (spec §4.1: the update's only transition).
    public AnchorDomain changeDomain(AnchorDomainValue newDomain) {
        return new AnchorDomain(id, newDomain.value(), createdAt, Instant.now());
    }

    /// Login routing (spec §1): `email`, lower-cased, ends with `@domain`. A
    /// `null` email never matches.
    public boolean matchesEmail(String email) {
        return email != null && email.toLowerCase(Locale.ROOT).endsWith("@" + domain);
    }
}
