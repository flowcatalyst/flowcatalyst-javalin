package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.function.Digest;
import io.flowcatalyst.platform.function.artifact.ArtifactException;
import io.flowcatalyst.platform.function.artifact.ArtifactStoreSupport;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/// [io.flowcatalyst.platform.function.artifact.ArtifactStore] for scheme
/// `platform` (spec `function-artifact-upload.md` §5): `GET
/// /control/functions/artifacts/{versionId}` with the host's own
/// [TokenSource] bearer, refresh-once-on-401 exactly as [HttpControlPlane]
/// does, streamed through the SAME [ArtifactStoreSupport] cache/hash/cap
/// path [io.flowcatalyst.platform.function.artifact.FileArtifactStore] and
/// [io.flowcatalyst.platform.function.artifact.OciArtifactStore] use — a
/// temp file, the recomputed digest, an atomic move into
/// `<cache>/sha256/<hex>`, the same byte cap. A two-arg
/// [io.flowcatalyst.platform.function.artifact.ArtifactStore#fetch] on a
/// `platform://` ref carries no version id — the download route is keyed by
/// version id, not by ref — so [#open] raises
/// [ArtifactException.VersionRequired] rather than guessing one.
///
/// The token and this store's client secret never appear in a log field, an
/// exception message, or a `toString` (spec §1.1, R9) — this class never
/// logs at all, and holds the secret only indirectly, through [TokenSource],
/// whose own `toString` is masked.
public final class PlatformArtifactStore extends ArtifactStoreSupport {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client;
    private final String platformUrl;
    private final TokenSource tokenSource;

    public PlatformArtifactStore(HttpClient client, String platformUrl, TokenSource tokenSource, Path cacheDir) {
        this(client, platformUrl, tokenSource, cacheDir, defaultMaxBytes());
    }

    /// @param maxBytes injectable seam so a test can pin the cap without a
    ///                 256 MiB transfer
    public PlatformArtifactStore(HttpClient client, String platformUrl, TokenSource tokenSource, Path cacheDir,
                                  long maxBytes) {
        super(cacheDir, maxBytes);
        this.client = Objects.requireNonNull(client, "client");
        this.platformUrl = Objects.requireNonNull(platformUrl, "platformUrl");
        this.tokenSource = Objects.requireNonNull(tokenSource, "tokenSource");
    }

    @Override
    protected SourceStream open(String ref, Digest expected, String versionId) throws ArtifactException {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(expected, "expected");
        if (versionId == null || versionId.isBlank()) {
            throw new ArtifactException(new ArtifactException.VersionRequired());
        }
        String token = mintOrFail();
        HttpResponse<InputStream> response = get(versionId, token);
        if (response.statusCode() == 401) {
            closeQuietly(response);
            String refreshed = refreshOrFail();
            response = get(versionId, refreshed);
        }
        int status = response.statusCode();
        if (status == 200) {
            OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
            return new SourceStream(response.body(), contentLength);
        }
        closeQuietly(response);
        if (status == 401) {
            // A second consecutive 401 — same reading as HttpControlPlane#sendWithAuth:
            // exactly one refresh is ever attempted, never a retry loop.
            throw new ArtifactException(new ArtifactException.Unauthorized());
        }
        if (status == 404) {
            throw new ArtifactException(new ArtifactException.NotFound());
        }
        throw new ArtifactException(new ArtifactException.Transport(
                new IOException("control plane returned HTTP " + status + " for GET /control/functions/artifacts/" + versionId)));
    }

    private String mintOrFail() throws ArtifactException {
        try {
            return tokenSource.token();
        } catch (ControlPlaneException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    private String refreshOrFail() throws ArtifactException {
        try {
            return tokenSource.refresh();
        } catch (ControlPlaneException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    private HttpResponse<InputStream> get(String versionId, String token) throws ArtifactException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(platformUrl + "/control/functions/artifacts/" + versionId))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    private static void closeQuietly(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException _) {
            // draining/closing an already-handled response is best-effort
        }
    }

    /// Masked, same rule as [TokenSource]/[HttpControlPlane] — neither the
    /// platform URL alone reveals anything sensitive, but this class holds
    /// no other field worth printing.
    @Override
    public String toString() {
        return "PlatformArtifactStore[platformUrl=" + platformUrl + "]";
    }
}
