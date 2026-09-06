package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.shared.auth.SigningKeys;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// `GET /.well-known/openid-configuration` and `GET /.well-known/jwks.json`
/// (`docs/spec/auth-core.md` §6.2b, §4; Go `discovery.go`). The document
/// advertises the algorithm actually in use — RS256, the only one Java
/// signs (ruling A-16) — and JWKS lists the current key first, then the
/// previous one during a rotation, `n`/`e` unpadded base64url big-endian.
public final class OAuthDiscoveryApi {

    private OAuthDiscoveryApi() {
    }

    public static void register(Routes routes, OAuthState s) {
        routes.get("/.well-known/openid-configuration", ctx -> configuration(ctx, s));
        routes.get("/.well-known/jwks.json", ctx -> jwks(ctx, s));
    }

    static void configuration(Exchange ctx, OAuthState s) {
        String base = s.baseUrl();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("issuer", base);
        d.put("authorization_endpoint", base + "/oauth/authorize");
        d.put("token_endpoint", base + "/oauth/token");
        d.put("userinfo_endpoint", base + "/oauth/userinfo");
        d.put("end_session_endpoint", base + "/auth/oidc/session/end");
        d.put("introspection_endpoint", base + "/oauth/introspect");
        d.put("revocation_endpoint", base + "/oauth/revoke");
        d.put("jwks_uri", base + "/.well-known/jwks.json");
        d.put("response_types_supported", List.of("code"));
        d.put("subject_types_supported", List.of("public"));
        d.put("id_token_signing_alg_values_supported", List.of("RS256"));
        d.put("scopes_supported", List.of("openid", "profile", "email", "offline_access"));
        d.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
        d.put("grant_types_supported", List.of("authorization_code", "refresh_token", "client_credentials"));
        d.put("claims_supported", List.of("sub", "iss", "aud", "exp", "iat", "auth_time", "nonce", "name", "email",
                "email_verified", "acr", "amr", "azp", "type", "scope", "client_id", "roles", "applications", "clients"));
        d.put("code_challenge_methods_supported", List.of("S256"));
        d.put("request_parameter_supported", false);
        d.put("request_uri_parameter_supported", false);
        ctx.status(200).json(d);
    }

    static void jwks(Exchange ctx, OAuthState s) {
        var keys = new ArrayList<Map<String, Object>>();
        for (SigningKeys.PublicKeyEntry entry : s.signingKeys().rotation().verificationKeys()) {
            keys.add(jwk(entry));
        }
        ctx.status(200).json(Map.of("keys", keys));
    }

    static Map<String, Object> jwk(SigningKeys.PublicKeyEntry entry) {
        RSAPublicKey pub = entry.publicKey();
        Map<String, Object> k = new LinkedHashMap<>();
        k.put("kty", "RSA");
        k.put("use", "sig");
        k.put("kid", entry.kid());
        k.put("alg", "RS256");
        k.put("n", b64(pub.getModulus()));
        k.put("e", b64(pub.getPublicExponent()));
        return k;
    }

    /// Unsigned big-endian bytes (no sign byte), unpadded base64url.
    static String b64(BigInteger v) {
        byte[] bytes = v.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
