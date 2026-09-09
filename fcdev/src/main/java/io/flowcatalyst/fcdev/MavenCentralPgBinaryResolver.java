package io.flowcatalyst.fcdev;

import io.zonky.test.db.postgres.embedded.DefaultPostgresBinaryResolver;
import io.zonky.test.db.postgres.embedded.PgBinaryResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/// Resolves the PostgreSQL server archive zonky's `EmbeddedPostgres` needs, in
/// place of bundling `embedded-postgres-binaries-*` for every platform into
/// the fcdev jar (Go's `fcdev` downloads the archive on first run; this is
/// the Java reading of that). Order, per `getPgBinary`:
///
///   1. `--embedded-db-binary` / `FC_EMBEDDED_DB_BINARY` — an operator-supplied
///      `.txz`, for offline/air-gapped use. Skips everything else.
///   2. the classpath — `DefaultPostgresBinaryResolver` (or a test delegate),
///      for the server module's tests, which still bundle the binaries.
///   3. `<cacheDir>/downloads/<artifact>-<version>.jar`, downloaded from Maven
///      Central (or `FC_EMBEDDED_PG_REPO`, for mirrors) on first use and
///      sha1-verified, then cached for every later start.
///
/// The `.txz` inside the downloaded jar is opened by scanning for the one
/// entry ending in `.txz` rather than reconstructing zonky's own naming
/// scheme (`postgres-darwin-arm_64.txz`, `postgres-linux-x86_64-alpine_linux.txz`, …).
public final class MavenCentralPgBinaryResolver implements PgBinaryResolver {

    private static final Logger LOG = LoggerFactory.getLogger(MavenCentralPgBinaryResolver.class);

    static final String DEFAULT_REPO_BASE_URL = "https://repo1.maven.org/maven2";

    private final PgBinaryResolver classpathDelegate;
    private final Path cacheDir;
    private final String repoBaseUrl;
    private final String version;
    private final Path offlineOverride;
    private final BooleanSupplier alpineDetector;

    /// Production wiring: classpath first ([DefaultPostgresBinaryResolver]),
    /// then `<cacheDir>/downloads`, repository base URL from
    /// `FC_EMBEDDED_PG_REPO` (default Maven Central), Alpine detected from
    /// `/etc/alpine-release`.
    ///
    /// @param version          the zonky binaries version to fetch, e.g. `18.4.0`
    ///                          ([Version#embeddedPgVersion()])
    /// @param offlineOverride  `--embedded-db-binary` / `FC_EMBEDDED_DB_BINARY`,
    ///                          or `null` when not set
    public MavenCentralPgBinaryResolver(Path cacheDir, String version, Path offlineOverride) {
        this(DefaultPostgresBinaryResolver.INSTANCE, cacheDir,
                System.getenv().getOrDefault("FC_EMBEDDED_PG_REPO", DEFAULT_REPO_BASE_URL),
                version, offlineOverride, MavenCentralPgBinaryResolver::alpineReleaseFilePresent);
    }

    /// Full constructor — tests inject a fake classpath delegate, a local
    /// `HttpServer` as the repository, and a fixed Alpine answer.
    MavenCentralPgBinaryResolver(PgBinaryResolver classpathDelegate, Path cacheDir, String repoBaseUrl,
                                  String version, Path offlineOverride, BooleanSupplier alpineDetector) {
        this.classpathDelegate = Objects.requireNonNull(classpathDelegate, "classpathDelegate");
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
        this.repoBaseUrl = Objects.requireNonNull(repoBaseUrl, "repoBaseUrl");
        this.version = Objects.requireNonNull(version, "version");
        this.offlineOverride = offlineOverride;
        this.alpineDetector = Objects.requireNonNull(alpineDetector, "alpineDetector");
    }

    @Override
    public InputStream getPgBinary(String system, String machineHardware) throws IOException {
        if (offlineOverride != null) {
            LOG.atInfo().setMessage("using --embedded-db-binary override instead of a bundled/downloaded postgres binary")
                    .addKeyValue("path", offlineOverride)
                    .log();
            return Files.newInputStream(offlineOverride);
        }

        try {
            InputStream fromClasspath = classpathDelegate.getPgBinary(system, machineHardware);
            if (fromClasspath != null) return fromClasspath;
        } catch (IOException | RuntimeException e) {
            // DefaultPostgresBinaryResolver throws IllegalStateException (not IOException,
            // despite the checked signature) when no bundled archive matches — that's the
            // normal case for the fcdev jar, which no longer bundles any. Fall through.
            LOG.debug("no classpath-bundled postgres binary for {}/{}, will download: {}", system, machineHardware, e.toString());
        }

        String artifact = artifactId(system, machineHardware, alpineDetector.getAsBoolean());
        Path downloads = cacheDir.resolve("downloads");
        Path jar = downloads.resolve(artifact + "-" + version + ".jar");
        if (!Files.exists(jar)) {
            download(artifact, downloads, jar);
        }
        return openTxzEntry(jar);
    }

    /// Zonky artifact = `embedded-postgres-binaries-<os>-<arch>[-alpine]`.
    /// Anything not in the mapping (including Windows/arm64, which zonky does
    /// not publish) is an [IOException].
    static String artifactId(String system, String machineHardware, boolean alpine) throws IOException {
        String os = switch (system == null ? "" : system.toLowerCase(Locale.ROOT)) {
            case "darwin" -> "darwin";
            case "linux" -> "linux";
            case "windows" -> "windows";
            default -> null;
        };
        String arch = switch (machineHardware == null ? "" : machineHardware.toLowerCase(Locale.ROOT)) {
            case "x86_64", "amd64" -> "amd64";
            case "aarch64", "arm64" -> "arm64v8";
            default -> null;
        };
        boolean supported = os != null && arch != null && !(os.equals("windows") && arch.equals("arm64v8"));
        if (!supported) {
            throw new IOException("no PostgreSQL binary published for " + system + "/" + machineHardware
                    + " — see https://github.com/zonkyio/embedded-postgres#additional-architectures");
        }
        String suffix = alpine && os.equals("linux") ? "-alpine" : "";
        return "embedded-postgres-binaries-" + os + "-" + arch + suffix;
    }

    private static boolean alpineReleaseFilePresent() {
        return Files.exists(Path.of("/etc/alpine-release"));
    }

    // ── download + verify ───────────────────────────────────────────────────

    private void download(String artifact, Path downloads, Path dest) throws IOException {
        Files.createDirectories(downloads);
        String jarUrl = repoBaseUrl + "/io/zonky/test/postgres/" + artifact + "/" + version + "/" + artifact + "-" + version + ".jar";
        String sha1Url = jarUrl + ".sha1";

        LOG.atInfo().setMessage("downloading embedded PostgreSQL binary")
                .addKeyValue("artifact", artifact)
                .addKeyValue("version", version)
                .addKeyValue("url", jarUrl)
                .log();
        Instant start = Instant.now();

        Path tmp = Files.createTempFile(downloads, artifact, ".jar.tmp");
        try {
            long bytes = httpDownloadToFile(jarUrl, tmp);
            String expectedSha1 = firstToken(httpGetString(sha1Url));
            String actualSha1 = sha1Hex(tmp);
            if (!actualSha1.equalsIgnoreCase(expectedSha1)) {
                throw new IOException("sha1 mismatch downloading " + jarUrl + ": expected " + expectedSha1 + " but got " + actualSha1);
            }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
            Duration elapsed = Duration.between(start, Instant.now());
            LOG.atInfo().setMessage("downloaded embedded PostgreSQL binary")
                    .addKeyValue("artifact", artifact)
                    .addKeyValue("bytes", bytes)
                    .addKeyValue("seconds", elapsed.toSeconds())
                    .log();
        } finally {
            // No-op once the move above has succeeded; removes the partial file on any failure.
            Files.deleteIfExists(tmp);
        }
    }

    private static String firstToken(String s) {
        var t = s.strip();
        int i = t.indexOf(' ');
        return i < 0 ? t : t.substring(0, i);
    }

    /// One client, with timeouts: a first run must fail with a message, not
    /// hang, when a mirror accepts the connection and never answers.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static long httpDownloadToFile(String url, Path dest) throws IOException {
        var client = HTTP;
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
        try {
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(dest));
            if (response.statusCode() != 200) {
                throw new IOException("GET " + url + " -> HTTP " + response.statusCode());
            }
            return Files.size(dest);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted downloading " + url, e);
        }
    }

    private static String httpGetString(String url) throws IOException {
        var client = HTTP;
        var request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("GET " + url + " -> HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted downloading " + url, e);
        }
    }

    private static String sha1Hex(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
    }

    // ── archive extraction ───────────────────────────────────────────────────

    /// Opens the jar, finds the single `.txz` entry (zonky's own naming, not
    /// reconstructed here) and returns a stream over it that also closes the
    /// [JarFile] when the caller closes the stream.
    private static InputStream openTxzEntry(Path jarPath) throws IOException {
        JarFile jarFile = new JarFile(jarPath.toFile());
        JarEntry found = null;
        var entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.getName().endsWith(".txz")) {
                if (found != null) {
                    jarFile.close();
                    throw new IOException("multiple .txz entries in " + jarPath + ": " + found.getName() + " and " + entry.getName());
                }
                found = entry;
            }
        }
        if (found == null) {
            jarFile.close();
            throw new IOException("no .txz entry found in " + jarPath);
        }
        InputStream entryStream;
        try {
            entryStream = jarFile.getInputStream(found);
        } catch (IOException e) {
            jarFile.close();
            throw e;
        }
        return new FilterInputStream(entryStream) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    jarFile.close();
                }
            }
        };
    }
}
