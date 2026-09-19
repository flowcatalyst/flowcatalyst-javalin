package io.flowcatalyst.fnhost.http;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/// A tiny loopback `/.well-known/jwks.json` (H4: `BearerAuthenticator`'s
/// `platform` auth mode) and an RS256 minter — self-contained, JDK-only
/// (`com.sun.net.httpserver`) plus the nimbus classes [JwtVerifier]/
/// [BearerAuthenticator] already pull in.
final class TestJwks implements AutoCloseable {

    final String issuer;
    private final HttpServer server;
    private final AtomicInteger jwksRequestCount = new AtomicInteger();
    private RSAPrivateKey currentPrivate;
    private RSAPublicKey currentPublic;
    private String currentKid = "kid-1";
    private RSAPublicKey previousPublic;
    private String previousKid;

    TestJwks() throws IOException {
        KeyPair pair = generate();
        this.currentPrivate = (RSAPrivateKey) pair.getPrivate();
        this.currentPublic = (RSAPublicKey) pair.getPublic();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/.well-known/jwks.json", this::serveJwks);
        this.server.start();
        this.issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Rotates: the current key becomes "previous" (still listed in the
    /// JWKS), and a fresh key becomes current.
    void rotate() {
        KeyPair pair = generate();
        previousPublic = currentPublic;
        previousKid = currentKid;
        currentPrivate = (RSAPrivateKey) pair.getPrivate();
        currentPublic = (RSAPublicKey) pair.getPublic();
        currentKid = "kid-" + System.nanoTime();
    }

    /// A key from a DIFFERENT, unrelated trust root — for a token signed by
    /// nobody this JWKS document ever lists.
    static KeyPair foreignKeyPair() {
        return generate();
    }

    int jwksRequestCount() {
        return jwksRequestCount.get();
    }

    /// H1's "the event loop is not blocked" proof: makes the NEXT JWKS
    /// fetch park (after being counted) until [#releaseParkedFetch] is
    /// called. `awaitFetchStarted` lets the test know the fetch has actually
    /// begun blocking (bounded — never a bare sleep).
    private volatile boolean parkNext;
    private final CountDownLatch fetchStarted = new CountDownLatch(1);
    private final CountDownLatch releaseFetch = new CountDownLatch(1);

    void parkNextJwksFetch() {
        parkNext = true;
    }

    boolean awaitFetchStarted(java.time.Duration timeout) throws InterruptedException {
        return fetchStarted.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    void releaseParkedFetch() {
        releaseFetch.countDown();
    }

    String mint(String subject, String type, String tier, String scope, List<String> clients,
                List<String> applications, boolean allApplications, Instant expiresAt) {
        return mint(currentPrivate, currentKid, issuer, subject, type, tier, scope, clients, applications,
                allApplications, expiresAt);
    }

    /// Same current (known) key/kid, but a DIFFERENT `iss` claim — isolates
    /// the issuer check from the kid-refetch machinery (H4's "wrong issuer" case).
    String mintWithIssuer(String issuer, String subject, String type, String tier, String scope,
                          List<String> clients, List<String> applications, boolean allApplications,
                          Instant expiresAt) {
        return mint(currentPrivate, currentKid, issuer, subject, type, tier, scope, clients, applications,
                allApplications, expiresAt);
    }

    static String mint(RSAPrivateKey key, String kid, String issuer, String subject, String type, String tier,
                        String scope, List<String> clients, List<String> applications, boolean allApplications,
                        Instant expiresAt) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(issuer)
                    .issueTime(java.util.Date.from(Instant.now()))
                    .expirationTime(java.util.Date.from(expiresAt))
                    .claim("type", type)
                    .claim("tier", tier)
                    .claim("scope", scope)
                    .claim("clients", clients)
                    .claim("applications", applications)
                    .claim("all_applications", allApplications);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(kid).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private void serveJwks(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
        jwksRequestCount.incrementAndGet();
        if (parkNext) {
            parkNext = false;
            fetchStarted.countDown();
            try {
                releaseFetch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        StringBuilder sb = new StringBuilder("{\"keys\":[");
        sb.append(jwk(currentKid, currentPublic));
        if (previousPublic != null) {
            sb.append(",").append(jwk(previousKid, previousPublic));
        }
        sb.append("]}");
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static String jwk(String kid, RSAPublicKey key) {
        return "{\"kty\":\"RSA\",\"use\":\"sig\",\"kid\":\"" + kid + "\",\"alg\":\"RS256\",\"n\":\""
                + b64(key.getModulus()) + "\",\"e\":\"" + b64(key.getPublicExponent()) + "\"}";
    }

    private static String b64(BigInteger v) {
        byte[] bytes = v.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
