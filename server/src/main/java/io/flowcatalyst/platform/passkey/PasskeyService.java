package io.flowcatalyst.platform.passkey;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import com.yubico.webauthn.exception.AssertionFailedException;
import com.yubico.webauthn.exception.RegistrationFailedException;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.server.EnvReader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/// The relying party (`docs/spec/auth-identity.md` §7.1, §7.3, §7.5 with
/// ruling I-Q13): registration and assertion ceremonies over the stored
/// credentials, the decoy challenge for unknown accounts, and the counter
/// check a clone would trip. The library's requests are serialised into
/// the ceremony row and read back to finish.
public final class PasskeyService {

    /// `FC_WEBAUTHN_RP_ID` (default `localhost`), `FC_WEBAUTHN_ORIGINS`
    /// (comma-separated) else `FC_WEBAUTHN_RP_ORIGIN`, default
    /// `http://localhost:8080`; the display name is the platform name.
    public record Config(String rpId, Set<String> origins, String displayName) {
        public Config {
            Objects.requireNonNull(rpId, "rpId");
            origins = Set.copyOf(Objects.requireNonNull(origins, "origins"));
            Objects.requireNonNull(displayName, "displayName");
        }

        public static Config fromEnv(EnvReader env, String displayName) {
            String rpId = env.or("FC_WEBAUTHN_RP_ID", "localhost").trim();
            String list = env.get("FC_WEBAUTHN_ORIGINS").trim();
            if (list.isEmpty()) {
                list = env.or("FC_WEBAUTHN_RP_ORIGIN", "http://localhost:8080").trim();
            }
            Set<String> origins = Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
            return new Config(rpId, origins, displayName);
        }
    }

    /// What a ceremony start hands the browser and what the row keeps.
    public record Started(JsonNode options, String session) {
    }

    /// A finished registration, ready to store.
    public record Registered(byte[] credentialId, byte[] publicKeyCose, long signCount, List<String> transports,
                             byte[] aaguid, boolean userVerified, boolean backupEligible, boolean backedUp) {
    }

    /// A finished assertion: which stored credential answered, the new
    /// counter, and whether that counter moved forward (ruling I-Q13: a
    /// counter that goes backwards is a cloned key and is refused).
    public record Asserted(Passkey credential, long signCount, boolean counterValid, boolean userVerified, boolean backedUp) {
    }

    public static final class CeremonyException extends Exception {
        public CeremonyException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Config config;
    private final PasskeyRepository credentials;
    private final RelyingParty rp;

    public PasskeyService(Config config, PasskeyRepository credentials) {
        this.config = Objects.requireNonNull(config, "config");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.rp = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(config.rpId()).name(config.displayName()).build())
                .credentialRepository(new StoreAdapter(credentials))
                .origins(config.origins())
                .build();
    }

    public Config config() {
        return config;
    }

    // ── registration ───────────────────────────────────────────────────────

    public Started beginRegistration(String principalId, String username, String displayName) throws CeremonyException {
        var user = UserIdentity.builder().name(username == null || username.isEmpty() ? principalId : username)
                .displayName(displayName == null || displayName.isEmpty() ? principalId : displayName)
                .id(handle(principalId)).build();
        try {
            PublicKeyCredentialCreationOptions options = rp.startRegistration(StartRegistrationOptions.builder().user(user)
                    .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                            .userVerification(UserVerificationRequirement.PREFERRED).build())
                    .timeout(300_000L)
                    .build());
            return new Started(Json.MAPPER.readTree(options.toCredentialsCreateJson()), options.toJson());
        } catch (IOException | RuntimeException e) {
            throw new CeremonyException("begin registration failed", e);
        }
    }

    /// `credentialJson` is the browser's `PublicKeyCredential` as JSON.
    /// (The library deprecates both ways of reading the backup flags; the
    /// flag bits are WebAuthn L3 and stable, so the raw ones are read.)
    @SuppressWarnings("deprecation")
    public Registered finishRegistration(String session, String credentialJson) throws CeremonyException, InvalidCredential {
        PublicKeyCredentialCreationOptions request;
        try {
            request = PublicKeyCredentialCreationOptions.fromJson(session);
        } catch (IOException | RuntimeException e) {
            throw new CeremonyException("ceremony state unreadable", e);
        }
        PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAttestationResponse, com.yubico.webauthn.data.ClientRegistrationExtensionOutputs> response;
        try {
            response = PublicKeyCredential.parseRegistrationResponseJson(credentialJson);
        } catch (IOException | RuntimeException e) {
            throw new InvalidCredential("INVALID_CREDENTIAL", "Parse error for Registration"); // go-webauthn's wording; never the parser's own text (parity S1-B)
        }
        RegistrationResult result;
        try {
            result = rp.finishRegistration(FinishRegistrationOptions.builder().request(request).response(response).build());
        } catch (RegistrationFailedException | RuntimeException e) {
            throw new InvalidCredential("ATTESTATION_INVALID", rootMessage(e, "attestation rejected"));
        }
        List<String> transports = result.getKeyId().getTransports().map(t -> t.stream().map(x -> x.getId()).toList()).orElse(List.of());
        byte[] aaguid = result.getAaguid().getBytes();
        var flags = response.getResponse().getParsedAuthenticatorData().getFlags();
        return new Registered(result.getKeyId().getId().getBytes(), result.getPublicKeyCose().getBytes(), result.getSignatureCount(),
                transports, aaguid, result.isUserVerified(), flags.BE, flags.BS);
    }

    // ── assertion ──────────────────────────────────────────────────────────

    public Started beginAssertion(String principalId) throws CeremonyException {
        try {
            AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder().userHandle(handle(principalId))
                    .userVerification(UserVerificationRequirement.PREFERRED).timeout(300_000L).build());
            return new Started(Json.MAPPER.readTree(request.toCredentialsGetJson()), request.toJson());
        } catch (IOException | RuntimeException e) {
            throw new CeremonyException("begin assertion failed", e);
        }
    }

    /// Verifies the assertion against the stored credentials; every failure
    /// is the same [InvalidCredential] (the route answers a uniform 403).
    @SuppressWarnings("deprecation")
    public Asserted finishAssertion(String session, String credentialJson) throws CeremonyException, InvalidCredential {
        AssertionRequest request;
        try {
            request = AssertionRequest.fromJson(session);
        } catch (IOException | RuntimeException e) {
            throw new CeremonyException("ceremony state unreadable", e);
        }
        PublicKeyCredential<com.yubico.webauthn.data.AuthenticatorAssertionResponse, com.yubico.webauthn.data.ClientAssertionExtensionOutputs> response;
        try {
            response = PublicKeyCredential.parseAssertionResponseJson(credentialJson);
        } catch (IOException | RuntimeException e) {
            throw new InvalidCredential("INVALID_CREDENTIALS", "Parse error for Assertion"); // go-webauthn's wording
        }
        AssertionResult result;
        try {
            result = rp.finishAssertion(FinishAssertionOptions.builder().request(request).response(response).build());
        } catch (AssertionFailedException | RuntimeException e) {
            throw new InvalidCredential("INVALID_CREDENTIALS", rootMessage(e, "assertion rejected"));
        }
        if (!result.isSuccess()) {
            throw new InvalidCredential("INVALID_CREDENTIALS", "assertion rejected");
        }
        Optional<Passkey> stored = credentials.findByCredentialId(result.getCredential().getCredentialId().getBytes());
        if (stored.isEmpty()) {
            throw new InvalidCredential("INVALID_CREDENTIALS", "credential unknown");
        }
        boolean backedUp = response.getResponse().getParsedAuthenticatorData().getFlags().BS;
        return new Asserted(stored.get(), result.getSignatureCount(), result.isSignatureCounterValid(),
                result.isUserVerified(), backedUp);
    }

    /// §7.3: shape-identical to a real non-discoverable challenge so an
    /// unknown account cannot be told from one without a passkey.
    public JsonNode decoyChallenge() {
        byte[] challenge = new byte[32];
        byte[] fakeId = new byte[32];
        RANDOM.nextBytes(challenge);
        RANDOM.nextBytes(fakeId);
        ObjectNode pk = Json.MAPPER.createObjectNode();
        pk.put("challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(challenge));
        pk.put("timeout", 60000); // the decoy keeps go-webauthn's 60 s; only real ceremonies use 300 s (parity S1-B)
        pk.put("rpId", config.rpId());
        ObjectNode allow = pk.putArray("allowCredentials").addObject();
        allow.put("type", "public-key");
        allow.put("id", Base64.getUrlEncoder().withoutPadding().encodeToString(fakeId));
        pk.put("userVerification", "preferred");
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.set("publicKey", pk);
        return out;
    }

    /// A rejected credential and the code the route answers with.
    public static final class InvalidCredential extends Exception {
        public final String code;

        public InvalidCredential(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /// The user handle is the principal id's UTF-8 bytes (Go: `[]byte(principalID)`).
    static ByteArray handle(String principalId) {
        return new ByteArray(principalId.getBytes(StandardCharsets.UTF_8));
    }

    static String principalOf(ByteArray handle) {
        return new String(handle.getBytes(), StandardCharsets.UTF_8);
    }

    /// The library's view of the store: usernames are principal ids here
    /// (the ceremony always starts from a known principal).
    static final class StoreAdapter implements CredentialRepository {
        private final PasskeyRepository store;

        StoreAdapter(PasskeyRepository store) {
            this.store = store;
        }

        @Override
        public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
            var out = new HashSet<PublicKeyCredentialDescriptor>();
            for (Passkey p : store.findByPrincipal(username)) {
                // The stored transports ride along so allowCredentials carries them, as
                // go-webauthn's do (parity S1-B); an unknown name is dropped, never fatal.
                var transports = new java.util.TreeSet<com.yubico.webauthn.data.AuthenticatorTransport>();
                for (String t : p.transports()) {
                    try {
                        transports.add(com.yubico.webauthn.data.AuthenticatorTransport.of(t));
                    } catch (RuntimeException ignored) {
                        // not a transport this library names
                    }
                }
                var descriptor = PublicKeyCredentialDescriptor.builder().id(new ByteArray(p.credentialId()));
                if (!transports.isEmpty()) descriptor.transports(java.util.Optional.of(transports));
                out.add(descriptor.build());
            }
            return out;
        }

        @Override
        public Optional<ByteArray> getUserHandleForUsername(String username) {
            return Optional.of(handle(username));
        }

        @Override
        public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
            return Optional.of(principalOf(userHandle));
        }

        @Override
        public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
            return store.findByCredentialId(credentialId.getBytes())
                    .filter(p -> p.principalId().equals(principalOf(userHandle)))
                    .map(StoreAdapter::registered);
        }

        @Override
        public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
            return store.findByCredentialId(credentialId.getBytes()).map(StoreAdapter::registered).map(Set::of).orElse(Set.of());
        }

        private static RegisteredCredential registered(Passkey p) {
            return RegisteredCredential.builder().credentialId(new ByteArray(p.credentialId())).userHandle(handle(p.principalId()))
                    .publicKeyCose(new ByteArray(p.publicKeyCose())).signatureCount(p.signCount()).build();
        }
    }

    /// The innermost cause's own words, never an exception class name (the
    /// library wraps a plain IllegalArgumentException in a failure whose
    /// message starts with the class; parity S1-B saw it on the wire).
    private static String rootMessage(Throwable e, String fallback) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null || m.isBlank() ? fallback : m;
    }
}
