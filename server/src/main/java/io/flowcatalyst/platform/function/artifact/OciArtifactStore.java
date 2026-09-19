package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.shared.json.Json;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Fetches an artifact blob from an OCI registry over
/// `oci://<registry>/<repository>` (spec `function-artifacts.md` §2.2). The
/// blob is addressed by the version's [Digest] alone — a tag or `@digest`
/// suffix is rejected. Auth, in order: anonymous; on `401` with a `Bearer`
/// challenge the token dance, retried once; on `401` with a `Basic`
/// challenge, Basic, retried once. **The `Authorization` header is never
/// forwarded across a redirect to another host** — registries commonly
/// redirect blob reads to object-storage signed URLs on a different host,
/// so this store follows redirects by hand instead of leaving it to
/// [HttpClient].
public final class OciArtifactStore extends ArtifactStoreSupport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern CHALLENGE_PARAM = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    private final RegistryCredentials credentials;
    private final HttpClient httpClient;

    public OciArtifactStore(Path cacheDir, RegistryCredentials credentials) {
        this(cacheDir, defaultMaxBytes(), credentials);
    }

    public OciArtifactStore(Path cacheDir, long maxBytes, RegistryCredentials credentials) {
        super(cacheDir, maxBytes);
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        // redirects are followed by hand (below) so Authorization is never forwarded cross-origin
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    SourceStream open(String ref, Digest expected) throws ArtifactException {
        OciRef parsed = OciRef.parse(ref);
        URI blobUri = blobUri(parsed, expected);
        try {
            HttpResponse<InputStream> response = getFollowingRedirect(blobUri, null);
            if (response.statusCode() == 200) {
                return toSourceStream(response);
            }
            if (response.statusCode() != 401) {
                close(response);
                throw statusException(response.statusCode());
            }
            String challenge = response.headers().firstValue("WWW-Authenticate").orElse("");
            close(response);
            String authorization = authenticate(parsed, challenge);
            if (authorization == null) {
                throw new ArtifactException(new ArtifactException.Unauthorized());
            }
            HttpResponse<InputStream> retry = getFollowingRedirect(blobUri, authorization);
            if (retry.statusCode() == 200) {
                return toSourceStream(retry);
            }
            close(retry);
            throw statusException(retry.statusCode());
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    private static ArtifactException statusException(int status) {
        return switch (status) {
            case 401, 403 -> new ArtifactException(new ArtifactException.Unauthorized());
            case 404 -> new ArtifactException(new ArtifactException.NotFound());
            default -> new ArtifactException(new ArtifactException.Transport(
                    new IOException("registry returned HTTP " + status)));
        };
    }

    private SourceStream toSourceStream(HttpResponse<InputStream> response) {
        OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
        return new SourceStream(response.body(), contentLength);
    }

    /// Issues `GET uri` with `authorization` (when non-null) as the
    /// `Authorization` header, following at most one redirect by hand —
    /// forwarding `authorization` only when the redirect target shares the
    /// origin (host **and** port) of `uri`.
    private HttpResponse<InputStream> getFollowingRedirect(URI uri, String authorization)
            throws IOException, InterruptedException {
        HttpResponse<InputStream> response = get(uri, authorization);
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("redirect with no Location header"));
            close(response);
            URI target = uri.resolve(location);
            String forwarded = sameOrigin(uri, target) ? authorization : null;
            return get(target, forwarded);
        }
        return response;
    }

    private HttpResponse<InputStream> get(URI uri, String authorization) throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(uri).GET().timeout(REQUEST_TIMEOUT);
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static boolean sameOrigin(URI a, URI b) {
        return Objects.equals(a.getHost(), b.getHost()) && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equals(uri.getScheme()) ? 443 : 80;
    }

    /// The auth dance (spec §2.2): a `Bearer` challenge fetches a token from
    /// its `realm`, scoped to `repository:<repo>:pull`, with Basic
    /// credentials when this store has them for the registry; a `Basic`
    /// challenge retries with those same credentials. `null` when the
    /// challenge cannot be answered (no `realm`, or an unrecognised scheme).
    private String authenticate(OciRef ref, String challenge) throws IOException, InterruptedException {
        String lower = challenge.toLowerCase(Locale.ROOT);
        if (lower.startsWith("bearer")) {
            return bearerToken(ref, challenge);
        }
        if (lower.startsWith("basic")) {
            return credentials.forRegistry(ref.registry()).map(OciArtifactStore::basicHeader).orElse(null);
        }
        return null;
    }

    private String bearerToken(OciRef ref, String challenge) throws IOException, InterruptedException {
        Map<String, String> params = challengeParams(challenge);
        String realm = params.get("realm");
        if (realm == null) {
            return null;
        }
        String service = params.getOrDefault("service", "");
        String scope = "repository:" + ref.repository() + ":pull";
        String query = "service=" + urlEncode(service) + "&scope=" + urlEncode(scope);
        URI tokenUri = URI.create(realm + (realm.contains("?") ? "&" : "?") + query);
        var builder = HttpRequest.newBuilder(tokenUri).GET().timeout(REQUEST_TIMEOUT);
        credentials.forRegistry(ref.registry()).ifPresent(basic -> builder.header("Authorization", basicHeader(basic)));
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            return null;
        }
        JsonNode node = Json.MAPPER.readTree(response.body());
        String token = node.path("token").isString() ? node.path("token").asString()
                : node.path("access_token").isString() ? node.path("access_token").asString() : null;
        return token == null ? null : "Bearer " + token;
    }

    private static Map<String, String> challengeParams(String challenge) {
        Map<String, String> params = new HashMap<>();
        Matcher matcher = CHALLENGE_PARAM.matcher(challenge);
        while (matcher.find()) {
            params.put(matcher.group(1), matcher.group(2));
        }
        return params;
    }

    private static String basicHeader(RegistryCredentials.BasicAuth auth) {
        String raw = auth.username() + ":" + auth.password();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException _) {
            // draining/closing an already-handled response is best-effort
        }
    }

    private static URI blobUri(OciRef ref, Digest digest) {
        // http:// only for the local registries fcdev and tests point at (spec §2.2); every real registry is https
        boolean useHttp = "localhost".equalsIgnoreCase(ref.host()) || "127.0.0.1".equals(ref.host());
        String scheme = useHttp ? "http" : "https";
        return URI.create(scheme + "://" + ref.registry() + "/v2/" + ref.repository() + "/blobs/" + digest.value());
    }

    /// `oci://<registry>/<repository>`, no tag, no `@digest` (spec §2.2):
    /// the blob is addressed by the version's [Digest] alone.
    private record OciRef(String host, String registry, String repository) {

        static OciRef parse(String ref) throws ArtifactException {
            URI uri;
            try {
                uri = new URI(ref);
            } catch (URISyntaxException e) {
                throw new ArtifactException(new ArtifactException.BadRef("malformed URI: " + e.getMessage()));
            }
            if (!"oci".equals(uri.getScheme())) {
                throw new ArtifactException(new ArtifactException.BadRef("not an oci:// reference"));
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                throw new ArtifactException(new ArtifactException.BadRef("oci:// reference must name a registry host"));
            }
            int port = uri.getPort();
            String registry = port == -1 ? host : host + ":" + port;
            String path = uri.getPath();
            if (path == null || path.length() < 2 || !path.startsWith("/")) {
                throw new ArtifactException(new ArtifactException.BadRef("oci:// reference must name a repository"));
            }
            String repository = path.substring(1);
            if (repository.contains("@") || repository.contains(":") || uri.getFragment() != null) {
                throw new ArtifactException(new ArtifactException.BadRef(
                        "oci:// reference must not carry a tag or @digest — the blob is addressed by Digest alone"));
            }
            return new OciRef(host, registry, repository);
        }
    }
}
