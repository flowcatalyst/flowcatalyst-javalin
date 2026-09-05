package io.flowcatalyst.platform.auth.oidc;

import com.nimbusds.jwt.JWTClaimsSet;

import java.text.ParseException;
import java.util.List;
import java.util.Locale;

/// The id_token claims the bridge reads (§4.4): `email`, `name`,
/// `preferred_username`, `tid`, `nonce`, `roles`.
public record IdTokenClaims(String email, String name, String preferredUsername, String tenantId, String nonce,
                            List<String> roles) {

    public IdTokenClaims {
        roles = roles == null ? List.of() : List.copyOf(roles);
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
        return new IdTokenClaims(cs.getStringClaim("email"), cs.getStringClaim("name"), cs.getStringClaim("preferred_username"),
                cs.getStringClaim("tid"), cs.getStringClaim("nonce"), roles);
    }
}
