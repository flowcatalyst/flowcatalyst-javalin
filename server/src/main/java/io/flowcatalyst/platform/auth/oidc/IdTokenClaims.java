package io.flowcatalyst.platform.auth.oidc;

import com.nimbusds.jwt.JWTClaimsSet;

import java.text.ParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// The id_token claims the bridge reads (§4.4): `email`, `name`,
/// `preferred_username`, `tid`, `nonce`, `roles`, plus the full verified
/// claim set (`rawClaims`, spec `docs/spec/oidc-logged-in-event.md`) for
/// the `UserLoggedIn` event's `federatedClaims.idToken` — `nonce`,
/// `at_hash` and `c_hash` are already stripped, since `nonce` is carried
/// in its own field and the hash claims are JOSE plumbing, not identity.
public record IdTokenClaims(String email, String name, String preferredUsername, String tenantId, String nonce,
                            List<String> roles, Map<String, Object> rawClaims) {

    public IdTokenClaims {
        roles = roles == null ? List.of() : List.copyOf(roles);
        rawClaims = rawClaims == null ? Map.of() : Map.copyOf(rawClaims);
    }

    /// The account identifier: `email`, else `preferred_username`; empty when neither.
    public String identifier() {
        if (email != null && !email.isEmpty()) {
            return email;
        }
        return preferredUsername == null ? "" : preferredUsername;
    }

    /// The domain after the last `@`, lower-cased; empty when there is none.
    public String domain() {
        String id = identifier().toLowerCase(Locale.ROOT);
        int at = id.lastIndexOf('@');
        return at < 0 || at == id.length() - 1 ? "" : id.substring(at + 1);
    }

    static IdTokenClaims from(JWTClaimsSet cs) throws ParseException {
        List<String> roles;
        Object raw = cs.getClaim("roles");
        if (raw == null) {
            roles = List.of();
        } else if (raw instanceof List<?> l) {
            roles = l.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        } else {
            throw new ParseException("roles is not an array", 0);
        }
        Map<String, Object> rawClaims = new LinkedHashMap<>(cs.toJSONObject());
        rawClaims.remove("nonce");
        rawClaims.remove("at_hash");
        rawClaims.remove("c_hash");
        return new IdTokenClaims(cs.getStringClaim("email"), cs.getStringClaim("name"), cs.getStringClaim("preferred_username"),
                cs.getStringClaim("tid"), cs.getStringClaim("nonce"), roles, rawClaims);
    }
}
