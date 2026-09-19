package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.server.EnvReader;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/// The host process's own environment (spec `function-host-reconciler.md`
/// §1.4, extended by `function-host-listener.md` §2 with the listener's own
/// three variables), read through the server's [EnvReader] — one rule, one
/// place for every `envOr`/`envBool` lookup, same as `io.flowcatalyst.server.Env`.
///
/// @param port            `FC_FN_PORT` — the listener's bind port (default 8080)
/// @param maxConcurrency  `FC_FN_MAX_CONCURRENCY` — the host-global invocation
///                        permit ceiling (default 512, spec §2 step 7)
/// @param drainTimeoutSeconds `FC_DRAIN_TIMEOUT_SECONDS` — how long [#close]
///                        waits for in-flight requests before closing anyway
///                        (default 60, spec §5)
public record HostEnv(DnsLabel pool, String platformUrl, String clientId, String clientSecret, String hostId,
                       Signatures signatures, int maxLoaded, Path cacheDir, int port, int maxConcurrency,
                       int drainTimeoutSeconds) {

    /// The heartbeat's own host-id rule (`function-api.md` §6.2): 1-100
    /// characters of `[A-Za-z0-9._:-]`.
    private static final Pattern HOST_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,100}$");
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public HostEnv {
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(platformUrl, "platformUrl");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(clientSecret, "clientSecret");
        Objects.requireNonNull(hostId, "hostId");
        Objects.requireNonNull(signatures, "signatures");
        Objects.requireNonNull(cacheDir, "cacheDir");
    }

    /// @throws IllegalStateException one message naming every required
    ///                                variable ([#FC_FN_PLATFORM_URL],
    ///                                [#FC_FN_CLIENT_ID], [#FC_FN_CLIENT_SECRET])
    ///                                that is missing, blank, or — for
    ///                                `FC_FN_HOST_ID` when explicitly set —
    ///                                not a valid host id
    public static HostEnv load(EnvReader e) {
        return load(e, HostEnv::defaultHostId);
    }

    /// @param defaultHostId supplies `<hostname>-<6 random base32>` when
    ///                      `FC_FN_HOST_ID` is unset — a seam so a test can
    ///                      pin the generated id without touching the real
    ///                      hostname or `SecureRandom`
    public static HostEnv load(EnvReader e, java.util.function.Supplier<String> defaultHostId) {
        Objects.requireNonNull(e, "e");
        Objects.requireNonNull(defaultHostId, "defaultHostId");

        List<String> missing = new ArrayList<>();

        String poolRaw = e.or("FC_FN_POOL", "default");
        DnsLabel pool;
        try {
            pool = new DnsLabel(poolRaw);
        } catch (RuntimeException ex) {
            missing.add("FC_FN_POOL (not a valid DNS label: '" + poolRaw + "')");
            pool = new DnsLabel("default");
        }

        String platformUrl = e.get("FC_FN_PLATFORM_URL");
        if (platformUrl.isBlank()) {
            missing.add("FC_FN_PLATFORM_URL");
        }
        String clientId = e.get("FC_FN_CLIENT_ID");
        if (clientId.isBlank()) {
            missing.add("FC_FN_CLIENT_ID");
        }
        String clientSecret = e.get("FC_FN_CLIENT_SECRET");
        if (clientSecret.isBlank()) {
            missing.add("FC_FN_CLIENT_SECRET");
        }

        String hostIdRaw = e.get("FC_FN_HOST_ID");
        String hostId;
        if (hostIdRaw.isBlank()) {
            hostId = defaultHostId.get();
        } else if (!HOST_ID.matcher(hostIdRaw).matches()) {
            missing.add("FC_FN_HOST_ID (not 1-100 characters of [A-Za-z0-9._:-]: '" + hostIdRaw + "')");
            hostId = hostIdRaw;
        } else {
            hostId = hostIdRaw;
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("missing or invalid required environment variable(s): "
                    + String.join(", ", missing));
        }

        // The same resolve — one rule, one place — the platform's own composition
        // root uses (spec §1.4): FC_FN_SIGNATURES + FLOWCATALYST_DEV_MODE +
        // FC_FN_TRUST_ROOT (the platform reads the same variable — a private
        // Sigstore instance needs both sides to trust it).
        Signatures signatures = Signatures.resolve(
                io.flowcatalyst.platform.function.artifact.SignaturesMode.parse(e.or("FC_FN_SIGNATURES", "required")),
                e.bool("FLOWCATALYST_DEV_MODE", false),
                e.get("FC_FN_TRUST_ROOT"));

        int maxLoaded = e.integer("FC_FN_MAX_LOADED", 200);
        Path cacheDir = Path.of(e.or("FC_FN_CACHE_DIR", System.getProperty("java.io.tmpdir") + "/fc-fn-cache"));
        int port = e.integer("FC_FN_PORT", 8080);
        int maxConcurrency = e.integer("FC_FN_MAX_CONCURRENCY", 512);
        int drainTimeoutSeconds = e.integer("FC_DRAIN_TIMEOUT_SECONDS", 60);

        return new HostEnv(pool, platformUrl, clientId, clientSecret, hostId, signatures, maxLoaded, cacheDir,
                port, maxConcurrency, drainTimeoutSeconds);
    }

    private static String defaultHostId() {
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            hostname = "fn-host";
        }
        // Sanitised to the host-id alphabet: an OS hostname may carry characters
        // (spaces, underscores) HOST_ID does not accept.
        hostname = hostname.replaceAll("[^A-Za-z0-9._:-]", "-");
        if (hostname.isBlank()) {
            hostname = "fn-host";
        }
        return hostname + "-" + randomBase32(6);
    }

    private static String randomBase32(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE32_ALPHABET.charAt(random.nextInt(BASE32_ALPHABET.length())));
        }
        return sb.toString();
    }

    /// Masks the client secret (`CONVENTIONS.md` §8 / spec §1.1, §3 R9: a
    /// carrier of key material masks `toString`).
    @Override
    public String toString() {
        return "HostEnv[pool=" + pool + ", platformUrl=" + platformUrl + ", clientId=" + clientId
                + ", clientSecret=<redacted>, hostId=" + hostId + ", signatures=" + signatures
                + ", maxLoaded=" + maxLoaded + ", cacheDir=" + cacheDir + ", port=" + port
                + ", maxConcurrency=" + maxConcurrency + ", drainTimeoutSeconds=" + drainTimeoutSeconds + "]";
    }
}
