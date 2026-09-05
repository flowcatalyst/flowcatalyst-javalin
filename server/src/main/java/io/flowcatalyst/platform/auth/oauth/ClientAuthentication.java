package io.flowcatalyst.platform.auth.oauth;

import io.flowcatalyst.platform.oauthclient.OAuthClient;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/// OAuth client authentication at the token, introspection and revocation
/// endpoints (`docs/spec/auth-core.md` §6.2a step 3; Go `authenticateClient`,
/// `basicAuthCreds`, `acceptClientSecret`): `client_secret_basic` (RFC 6749
/// §2.3.1 — each part URL-decoded, scheme case-insensitive) wins over the
/// body pair; a PUBLIC client must not present a secret, a CONFIDENTIAL one
/// must; the secret is checked against the current ref **and** a previous
/// ref still inside its rotation overlap (ruling A-22), both compares always
/// running.
public final class ClientAuthentication {

    private static final Logger LOG = LoggerFactory.getLogger(ClientAuthentication.class);

    private ClientAuthentication() {
    }

    /// `client_id` and `client_secret` from an HTTP Basic header, when present.
    public record BasicCredentials(String clientId, String clientSecret) {
    }

    /// Either an authenticated client or the RFC error to answer.
    public record Result(OAuthClient client, OAuthError error) {
        static Result ok(OAuthClient c) {
            return new Result(c, null);
        }

        static Result fail(OAuthError e) {
            return new Result(null, e);
        }

        public boolean failed() {
            return error != null;
        }
    }

    public static Optional<BasicCredentials> basicCredentials(Context ctx) {
        String h = ctx.header("Authorization");
        if (h == null) {
            return Optional.empty();
        }
        int space = h.indexOf(' ');
        if (space < 0 || !h.substring(0, space).equalsIgnoreCase("Basic")) {
            return Optional.empty();
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(h.substring(space + 1).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            return Optional.empty();
        }
        return Optional.of(new BasicCredentials(formDecode(decoded.substring(0, colon)), formDecode(decoded.substring(colon + 1))));
    }

    /// Resolves and verifies the client from Basic auth, else the body pair.
    public static Result authenticateClient(OAuthState s, Context ctx, String bodyClientId, String bodyClientSecret) {
        String clientId;
        String clientSecret;
        Optional<BasicCredentials> basic = basicCredentials(ctx);
        if (basic.isPresent()) {
            clientId = basic.get().clientId();
            clientSecret = basic.get().clientSecret();
        } else {
            if (bodyClientId == null || bodyClientId.isEmpty()) {
                return Result.fail(OAuthError.invalidClient("Missing client credentials"));
            }
            clientId = bodyClientId;
            clientSecret = bodyClientSecret == null ? "" : bodyClientSecret;
        }

        Optional<OAuthClient> found;
        try {
            found = s.oauthClients().findByClientId(clientId);
        } catch (RuntimeException e) {
            LOG.error("oauth client lookup failed client_id={}", clientId, e);
            return Result.fail(OAuthError.serverError(""));
        }
        if (found.isEmpty()) {
            return Result.fail(OAuthError.invalidClient("Unknown client"));
        }
        OAuthClient client = found.get();
        if (!client.active()) {
            return Result.fail(OAuthError.invalidClient("Client is not active"));
        }
        if (client.secretRef() == null) {
            if (!clientSecret.isEmpty()) {
                return Result.fail(OAuthError.invalidClient("Public clients must not provide a client_secret"));
            }
            return Result.ok(client);
        }
        if (clientSecret.isEmpty()) {
            return Result.fail(OAuthError.invalidClient("Client secret required for confidential clients"));
        }
        if (!acceptClientSecret(s, client, clientSecret)) {
            return Result.fail(OAuthError.invalidClient("Invalid client credentials"));
        }
        return Result.ok(client);
    }

    /// The entity's constant-time, both-compares check, plus the rotation
    /// signal: authenticating on the superseded secret stamps
    /// `previous_secret_last_used_at` (best-effort, coalesced to a minute)
    /// so an operator can see who has not redeployed.
    public static boolean acceptClientSecret(OAuthState s, OAuthClient client, String provided) {
        Instant now = s.clock().instant();
        boolean accepted = client.acceptsSecret(provided, now, s.decryptor());
        if (accepted && client.secretRef() != null) {
            // Which one matched is what the signal needs; recompute only the
            // current compare (both already ran inside acceptsSecret).
            boolean current = s.decryptor().apply(client.secretRef())
                    .map(pt -> MessageDigest.isEqual(pt.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8)))
                    .orElse(false);
            if (!current) {
                notePreviousSecretUsed(s, client, now);
            }
        }
        return accepted;
    }

    private static void notePreviousSecretUsed(OAuthState s, OAuthClient client, Instant now) {
        try {
            s.oauthClients().touchPreviousSecretUsed(client.id(), now, now.minusSeconds(60));
            LOG.info("client authenticated with its superseded secret oauth_client_id={} at={}", client.id(), now);
        } catch (RuntimeException e) {
            LOG.warn("could not record previous-secret use oauth_client_id={}", client.id(), e);
        }
    }

    /// A stored ref against a provided plaintext, constant-time; false
    /// without an encryption service (the developer-credential path).
    public static boolean verifySecretRef(OAuthState s, String secretRef, String provided) {
        return s.decryptor().apply(secretRef)
                .map(pt -> MessageDigest.isEqual(pt.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8)))
                .orElse(false);
    }

    private static String formDecode(String v) {
        try {
            return URLDecoder.decode(v, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return v; // a malformed escape leaves the original untouched
        }
    }
}
