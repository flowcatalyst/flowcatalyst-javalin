package io.flowcatalyst.platform.shared.auth;

import java.util.List;

/// Parses the two authority list-claims — `clients` and `applications` —
/// into the bare ids the rest of the platform reasons in.
///
/// ### Why a parser is needed at all
///
/// Both claims carry **`"{id}:{label}"` pairs**, not bare ids: `clients` has
/// always emitted `"{clientId}:{clientIdentifier}"`, and `applications`
/// joined it as `"{applicationId}:{applicationCode}"`. The label is there so
/// a consumer wanting the human-meaningful half does not have to look it up.
/// Either claim may instead hold the single **`"*"` sentinel**, meaning "every
/// one" — what an anchor's `clients` carries, and what an all-applications
/// principal's `applications` carries.
///
/// This mattered because the platform compared the claim entries to bare ids
/// directly. A token minted with pairs then failed **every** scope check:
/// `canAccessClient("clt_x")` asked whether the list contained `"clt_x"` when
/// it contained `"clt_x:acme"`. Self-minted tokens hid it — the same code
/// wrote and read bare ids, so it was consistent with itself — and it only
/// surfaces against a token minted by the other implementation, which is the
/// entire point of a drop-in replacement.
///
/// Failing *closed* is what made it invisible: the caller is simply denied,
/// which looks like a permissions problem rather than a parsing one.
///
/// ### Where it runs
///
/// **Exactly once**, where a token becomes an [AuthContext]. The internal
/// model reasons in bare ids everywhere else ([AuthContext#canAccessClient],
/// [AuthContext#canAccessApplication]), so the pair form must not leak past
/// this boundary.
public final class ScopeClaim {

    /// "every one" — an anchor's `clients`, an all-applications principal's
    /// `applications`.
    public static final String WILDCARD = "*";

    private ScopeClaim() {
    }

    /// @param ids      the bare ids, pairs split at the first `:`
    /// @param wildcard whether the claim carried [#WILDCARD]
    public record Parsed(List<String> ids, boolean wildcard) {
        public Parsed {
            ids = ids == null ? List.of() : List.copyOf(ids);
        }
    }

    /// Splits `"{id}:{label}"` entries to their id, recognises [#WILDCARD],
    /// and passes bare ids through unchanged.
    ///
    /// The bare form is still accepted deliberately: tokens minted before the
    /// pair form existed stay valid for their remaining TTL, and an id whose
    /// label could not be resolved is emitted bare rather than dropped —
    /// dropping it would silently narrow the principal's access.
    ///
    /// A leading `:` leaves no id, so the entry is discarded rather than
    /// contributing an empty one that could never match.
    public static Parsed parse(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return new Parsed(List.of(), false);
        }
        var ids = new java.util.ArrayList<String>(entries.size());
        boolean wildcard = false;
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            if (WILDCARD.equals(entry)) {
                wildcard = true;
                continue;
            }
            int colon = entry.indexOf(':');
            String id = colon > 0 ? entry.substring(0, colon) : entry;
            if (!id.isBlank()) {
                ids.add(id);
            }
        }
        return new Parsed(List.copyOf(ids), wildcard);
    }
}
