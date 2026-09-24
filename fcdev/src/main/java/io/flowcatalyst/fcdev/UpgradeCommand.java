package io.flowcatalyst.fcdev;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/// `fcdev upgrade` (Go `upgrade.go`; `docs/spec/fcdev-commands.md` §4):
/// downloads the latest `fcdev` release for this platform from GitHub,
/// verifies its SHA256, and atomically replaces the running artifact.
/// `--check` only reports whether a newer release exists; `--force`
/// reinstalls even when already current.
///
/// The Java divergence the spec calls for: **which artifact** depends on
/// how THIS process is running, detected via [#detectLaunchKind()] —
/// [LaunchKind#NATIVE] (a GraalVM native-image build) downloads the same
/// per-OS/arch `fcdev-v<ver>-<os>-<arch>.tar.gz`/`.zip` Go always did;
/// [LaunchKind#JAR] (`java -jar …`) downloads `fcdev-v<ver>.jar` and
/// replaces the jar at the code-source path; anything else (`mvn exec`, an
/// IDE run, JBang) is [LaunchKind#OTHER] and refuses — self-replacing a
/// shared `~/.m2` artifact or a JBang-managed cache entry would be wrong,
/// and JBang already has its own upgrade path
/// (`jbang app install --force …`).
@Command(name = "upgrade", description = "Update fcdev to the latest release",
        sortOptions = false)
public final class UpgradeCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(UpgradeCommand.class);

    static final String DEFAULT_REPO = "flowcatalyst/flowcatalyst-javalin";
    static final String FN_HOST_ASSET = "fc-fnhost.jar";
    static final String DEFAULT_API_BASE = "https://api.github.com";
    static final String RELEASE_TAG_PREFIX = "fcdev/v";

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "show this help and exit")
    boolean help;

    @Spec
    CommandSpec spec;

    @Option(names = "--check", description = "only report whether a newer release exists; don't install")
    boolean check;

    @Option(names = "--force", description = "reinstall even if already on the latest version")
    boolean force;

    private final DevEnv env;
    private final String apiBase;

    /// Test seam: the on-disk file [#call()] replaces. Production default is
    /// the running artifact's own path ([#selfPath()]); [UpgradeCommandTest]
    /// points this at a temp file instead of touching anything real.
    Path selfPathOverride;

    /// Test seam: which artifact shape to install. Production default is
    /// [#detectLaunchKind()]; under `mvn test` the code source is an
    /// exploded `target/test-classes` directory (neither a jar nor a
    /// native image), which would otherwise always resolve to [LaunchKind#OTHER]
    /// — [UpgradeCommandTest] sets this directly to exercise the jar path.
    LaunchKind launchKindOverride;

    public UpgradeCommand() {
        this(DevEnv.system());
    }

    public UpgradeCommand(DevEnv env) {
        this(env, env.str("FC_DEV_UPGRADE_API_BASE", DEFAULT_API_BASE));
    }

    /// @param apiBase the GitHub API base URL — overridable so
    ///                 [UpgradeCommandTest] can point it at a stub
    ///                 `HttpServer` instead of the real network
    ///                 (`FC_DEV_UPGRADE_API_BASE`, a Java-only test seam;
    ///                 Go has no equivalent because it never needed one for
    ///                 automated tests).
    UpgradeCommand(DevEnv env, String apiBase) {
        this.env = Objects.requireNonNull(env, "env");
        this.apiBase = Objects.requireNonNull(apiBase, "apiBase");
    }

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        String repo = env.str("FC_DEV_UPGRADE_REPO", DEFAULT_REPO);
        String current = Version.current();

        out.printf("current version: %s%n", current);
        out.println("checking for updates…");

        Release rel = latestRelease(repo);
        out.printf("latest version:  %s%n", rel.version());

        boolean updateAvailable = compareSemver(rel.version(), current) > 0;

        if (check) {
            if (updateAvailable) {
                out.printf("update available: %s → %s (run `fcdev upgrade`)%n", current, rel.version());
            } else {
                out.println("fcdev is up to date.");
            }
            return 0;
        }

        if (!updateAvailable && !force) {
            out.println("fcdev is already up to date. Use --force to reinstall.");
            return 0;
        }

        LaunchKind kind = launchKindOverride != null ? launchKindOverride : detectLaunchKind();
        if (kind == LaunchKind.OTHER) {
            throw new IllegalStateException(
                    "fcdev upgrade: not running from a release artifact; reinstall with the method you used to install");
        }

        Asset asset = kind == LaunchKind.JAR ? jarAsset(rel.version()) : nativeAsset(rel.version());
        String assetUrl = rel.assets().get(asset.name());
        if (assetUrl == null) {
            throw new IllegalStateException("release " + rel.version() + " has no asset for this platform ("
                    + asset.name() + ")");
        }

        out.printf("downloading %s…%n", asset.name());
        byte[] archiveBytes = httpGet(assetUrl);

        String shaUrl = rel.assets().get(asset.name() + ".sha256");
        if (shaUrl != null) {
            byte[] shaBytes = httpGet(shaUrl);
            verifySha256(archiveBytes, shaBytes);
            out.println("sha256 verified.");
        } else {
            out.println("warning: no sha256 sidecar published for this asset — skipping checksum verification");
        }

        byte[] newContent = kind == LaunchKind.JAR ? archiveBytes : extractBinary(archiveBytes, asset);

        Path dest = selfPathOverride != null ? selfPathOverride : selfPath();
        if (kind == LaunchKind.JAR) {
            replaceFile(dest, newContent);
        } else {
            replaceExecutable(dest, newContent);
            // `docs/spec/function-developer-surface.md` §1: only the native
            // branch ever shells out to a function host jar (the JVM branch
            // runs it in-process) — fetch fc-fnhost.jar beside the binary,
            // same directory, same atomic-replace helper. A missing asset
            // (a release predating this) is not an error.
            fetchFunctionHostJarIfPresent(rel, dest.getParent(), out);
        }

        out.printf("upgraded fcdev %s -> %s (%s)%n", current, rel.version(), dest);
        return 0;
    }

    /// `fcdev upgrade` fetching `fc-fnhost.jar` "beside the binary" (spec
    /// §1): a fixed asset name, no version in it. The checksum is REQUIRED
    /// (`docs/spec/fcdev-release-0.9.md` §3, last line: "makes the checksum
    /// required the same way" as [#fetchOwnFunctionHostJar]) — a published
    /// asset with no `.sha256` sidecar refuses to install rather than
    /// falling back to an unverified copy. Package-visible for direct
    /// testing without staging a full native release fixture.
    void fetchFunctionHostJarIfPresent(Release rel, Path dir, PrintWriter out) throws IOException, InterruptedException {
        String assetUrl = rel.assets().get(FN_HOST_ASSET);
        if (assetUrl == null) {
            return;
        }
        out.println("downloading fc-fnhost.jar…");
        byte[] jarBytes = httpGet(assetUrl);
        String shaUrl = rel.assets().get(FN_HOST_ASSET + ".sha256");
        if (shaUrl == null) {
            throw new IllegalStateException("release " + rel.version() + " publishes " + FN_HOST_ASSET
                    + " with no .sha256 sidecar — refusing to install an unverified function host jar");
        }
        verifySha256(jarBytes, httpGet(shaUrl));
        replaceFile(dir.resolve(FN_HOST_ASSET), jarBytes);
        out.println("fc-fnhost.jar updated.");
    }

    // ── first-use fetch (fcdev start, docs/spec/fcdev-release-0.9.md §3) ──

    /// `fcdev start`'s first-use fetch of the function host: a NATIVE fcdev
    /// with no host jar resolvable from the four static places
    /// (`--fn-host-jar` / `FC_FN_HOST_JAR` / beside the binary / the cache
    /// path — [StartCommand#resolveHostJar]) fetches it instead of disabling
    /// functions. Reuses this class's GitHub-API/HTTP/checksum code — one
    /// release parser, one HTTP client — rather than a second copy for
    /// `fcdev start`.
    ///
    /// `CONVENTIONS.md` §8: a failed fetch is an EXPECTED outcome — offline,
    /// this version has no release yet, or the release's checksum is
    /// missing/mismatched — so this returns a sealed [FetchError], never
    /// throws, and the caller turns it into the existing
    /// `FnHostLauncher.Disabled` warning; `fcdev start` still succeeds.
    ///
    /// The checksum is REQUIRED (spec §3 item 2): no `.sha256` asset, or a
    /// mismatch, is a failed fetch — never an unverified install.
    ///
    /// @param repo       the upgrade repo (`FC_DEV_UPGRADE_REPO` / [#DEFAULT_REPO])
    /// @param installDir beside-the-binary directory — used when it exists and is writable
    /// @param cacheDir   `<fcdev data dir>/fnhost/<version>` (spec §3 item 3) —
    ///                    used when `installDir` is `null`, missing, or not writable
    Result<Path, FetchError> fetchOwnFunctionHostJar(String repo, Path installDir, Path cacheDir) {
        String version = Version.current();
        String tag = RELEASE_TAG_PREFIX + version;

        Release rel;
        try {
            rel = releaseByTag(repo, tag);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(new FetchError.Offline(String.valueOf(e.getMessage())));
        } catch (IOException e) {
            return Result.err(new FetchError.Offline(String.valueOf(e.getMessage())));
        }
        if (rel == null) {
            return Result.err(new FetchError.NoRelease(repo, tag));
        }

        String jarUrl = rel.assets().get(FN_HOST_ASSET);
        if (jarUrl == null) {
            return Result.err(new FetchError.NoRelease(repo, tag));
        }
        String shaUrl = rel.assets().get(FN_HOST_ASSET + ".sha256");
        if (shaUrl == null) {
            return Result.err(new FetchError.NoChecksum(FN_HOST_ASSET));
        }

        LOG.atInfo().setMessage("fetching the function host")
                .addKeyValue("asset", FN_HOST_ASSET)
                .addKeyValue("version", version)
                .log();
        byte[] jarBytes;
        byte[] shaBytes;
        try {
            jarBytes = httpGet(jarUrl);
            shaBytes = httpGet(shaUrl);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.err(new FetchError.Offline(String.valueOf(e.getMessage())));
        } catch (IOException e) {
            return Result.err(new FetchError.Offline(String.valueOf(e.getMessage())));
        }

        try {
            verifySha256(jarBytes, shaBytes);
        } catch (IllegalStateException e) {
            return Result.err(new FetchError.ChecksumMismatch(e.getMessage()));
        }

        Path dest = writableDirectory(installDir) ? installDir.resolve(FN_HOST_ASSET) : cacheDir.resolve(FN_HOST_ASSET);
        try {
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            replaceFile(dest, jarBytes);
        } catch (IOException e) {
            return Result.err(new FetchError.Offline("could not write " + dest + ": " + e.getMessage()));
        }
        return Result.ok(dest);
    }

    private static boolean writableDirectory(Path dir) {
        return dir != null && Files.isDirectory(dir) && Files.isWritable(dir);
    }

    /// Why [#fetchOwnFunctionHostJar] could not produce a usable jar — every
    /// case is the context [StartCommand] needs to build the
    /// `FnHostLauncher.Disabled` reason without re-deriving it.
    public sealed interface FetchError {
        /// The network request itself failed, or the fetched bytes could not
        /// be written locally — offline, DNS, an unexpected non-2xx status,
        /// a local disk error.
        record Offline(String detail) implements FetchError {
        }

        /// No `fcdev/v<version>` release exists yet for `repo` (or that
        /// release does not publish `fc-fnhost.jar`) — most likely this
        /// build is ahead of its first tagged release.
        record NoRelease(String repo, String tag) implements FetchError {
        }

        /// The release publishes `fc-fnhost.jar` but no `.sha256` sidecar —
        /// the checksum is required, so this is a refusal to install
        /// unverified content, not a download failure.
        record NoChecksum(String assetName) implements FetchError {
        }

        /// The downloaded bytes do not match the published `.sha256`.
        record ChecksumMismatch(String detail) implements FetchError {
        }
    }

    // ── which artifact ───────────────────────────────────────────────────

    enum LaunchKind {
        /// A GraalVM native-image build (`-Pnative`).
        NATIVE,
        /// `java -jar flowcatalyst-fcdev-<version>.jar` (the GitHub-Releases
        /// fallback jar, or `jbang app install`'s cached resolution — see
        /// the JBang caveat on [#detectLaunchKind()]).
        JAR,
        /// `mvn exec`, an IDE run (exploded `target/classes`), or anything
        /// else this process cannot safely self-replace.
        OTHER
    }

    /// Go's `fcdev` is always a native binary; the Java port additionally
    /// ships as a jar, so this decides which artifact shape applies to THIS
    /// running process. A `.jar`-suffixed, regular-file code source is
    /// [LaunchKind#JAR]; the GraalVM native-image marker property is
    /// [LaunchKind#NATIVE]; anything else — an exploded classes directory
    /// (`mvn exec`, an IDE) — is [LaunchKind#OTHER].
    ///
    /// JBang caveat: JBang resolves `io.flowcatalyst:flowcatalyst-fcdev`
    /// into its own managed cache and runs it with `java -jar` from there,
    /// which is structurally indistinguishable from the plain jar case —
    /// this deliberately does NOT special-case it, since self-replacing
    /// that cached jar in place would be no worse than the plain-jar case
    /// (both are "a jar on disk fcdev can find its own path to"); JBang's
    /// own `app install --force` remains the documented/preferred path
    /// (`docs/fcdev.md`) but is not required.
    static LaunchKind detectLaunchKind() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            return LaunchKind.NATIVE;
        }
        Path location;
        try {
            location = selfPath();
        } catch (RuntimeException e) {
            return LaunchKind.OTHER;
        }
        if (Files.isRegularFile(location) && location.toString().endsWith(".jar")) {
            return LaunchKind.JAR;
        }
        return LaunchKind.OTHER;
    }

    record Asset(String name, String innerPath) {
    }

    static Asset jarAsset(String version) {
        return new Asset("fcdev-v" + version + ".jar", null);
    }

    /// `fcdev-v<ver>-<os>-<arch>.tar.gz` (`.zip`/`fcdev.exe` on Windows) —
    /// Go's own asset naming, `os` ∈ `darwin|linux|windows`, `arch` ∈
    /// `amd64|arm64` (`os.arch`'s `x86_64`/`aarch64` normalised).
    static Asset nativeAsset(String version) {
        String os = osName();
        String arch = archName();
        boolean windows = os.equals("windows");
        String ext = windows ? "zip" : "tar.gz";
        String binName = windows ? "fcdev.exe" : "fcdev";
        String stem = "fcdev-v" + version + "-" + os + "-" + arch;
        return new Asset(stem + "." + ext, stem + "/" + binName);
    }

    static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) return "darwin";
        if (os.contains("win")) return "windows";
        return "linux";
    }

    static String archName() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64" -> "amd64";
            case "aarch64", "arm64" -> "arm64";
            default -> arch;
        };
    }

    /// Pulls the single binary out of the release archive — `.tar.gz` or
    /// `.zip` per [Asset#innerPath]'s extension. A basename fallback
    /// tolerates a flat archive (the binary at the root, no `<stem>/`
    /// prefix) the same way Go's `extractFromTarGz`/`extractFromZip` do.
    static byte[] extractBinary(byte[] archive, Asset asset) throws IOException {
        return asset.name().endsWith(".zip") ? extractFromZip(archive, asset.innerPath())
                : extractFromTarGz(archive, asset.innerPath());
    }

    private static byte[] extractFromZip(byte[] archive, String innerPath) throws IOException {
        String base = basename(innerPath);
        byte[] fallback = null;
        try (var zin = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals(innerPath)) {
                    return zin.readAllBytes();
                }
                if (fallback == null && !entry.isDirectory() && basename(name).equals(base)) {
                    fallback = zin.readAllBytes();
                }
            }
        }
        if (fallback != null) return fallback;
        throw new IOException("binary \"" + innerPath + "\" not found in archive");
    }

    /// A minimal (USTAR-compatible) tar reader: 512-byte header blocks,
    /// null-terminated name at offset 0/100 bytes, octal size at offset
    /// 124/12 bytes, content padded to a 512-byte boundary. Sufficient for
    /// the single-binary archives this release process publishes — a
    /// dependency on a full tar library (Apache Commons Compress) is not
    /// pulled in for this one narrow use.
    private static byte[] extractFromTarGz(byte[] archive, String innerPath) throws IOException {
        String base = basename(innerPath);
        byte[] fallback = null;
        try (var tar = new GZIPInputStream(new ByteArrayInputStream(archive))) {
            byte[] header = new byte[512];
            while (true) {
                int read = readFully(tar, header);
                if (read < 512 || isAllZero(header)) break; // EOF or the two-zero-block trailer
                String name = tarString(header, 0, 100);
                long size = Long.parseLong(tarString(header, 124, 12).trim(), 8);
                char typeflag = (char) header[156];
                byte[] content = new byte[(int) size];
                readFully(tar, content);
                long padding = (512 - (size % 512)) % 512;
                tar.skipNBytes(padding);
                boolean regularFile = typeflag == '0' || typeflag == '\0';
                if (regularFile && name.equals(innerPath)) {
                    return content;
                }
                if (regularFile && fallback == null && basename(name).equals(base)) {
                    fallback = content;
                }
            }
        }
        if (fallback != null) return fallback;
        throw new IOException("binary \"" + innerPath + "\" not found in archive");
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static boolean isAllZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) end++;
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    // ── GitHub releases ──────────────────────────────────────────────────

    record Release(String version, Map<String, String> assets) {
    }

    /// The highest `fcdev/vX.Y.Z` release published for `repo`, filtering out
    /// drafts, prereleases and anything not clean semver — the same
    /// filtering as Go's `latestRelease`.
    Release latestRelease(String repo) throws IOException, InterruptedException {
        String api = apiBase + "/repos/" + repo + "/releases?per_page=100";
        HttpResponse<String> response = getJson(api);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub API " + api + " returned " + response.statusCode() + ": "
                    + truncate(response.body(), 512));
        }
        GhRelease[] raw = Json.MAPPER.readValue(response.body(), GhRelease[].class);

        Release best = null;
        for (GhRelease r : raw) {
            if (r.draft() || r.prerelease() || r.tagName() == null || !r.tagName().startsWith(RELEASE_TAG_PREFIX)) {
                continue;
            }
            String ver = r.tagName().substring(RELEASE_TAG_PREFIX.length());
            if (!isCleanSemver(ver)) {
                continue;
            }
            if (best != null && compareSemver(ver, best.version()) <= 0) {
                continue;
            }
            best = new Release(ver, assetsOf(r));
        }
        if (best == null) {
            throw new IllegalStateException("no " + RELEASE_TAG_PREFIX + "X.Y.Z releases found for " + repo);
        }
        return best;
    }

    /// The single release tagged exactly `tag` (`GET
    /// /repos/{repo}/releases/tags/{tag}`) — used by
    /// [#fetchOwnFunctionHostJar] to ask for the release matching THIS
    /// fcdev's own version, never "latest" (`docs/spec/fcdev-release-0.9.md`
    /// §3 item 1/4: a native binary must fetch the function host that
    /// matches its own release). `null` when GitHub answers 404 (no such
    /// release) — never thrown, so the caller reports
    /// [FetchError.NoRelease] rather than a stack trace; any other non-200
    /// status is still an [IllegalStateException] like [#latestRelease].
    Release releaseByTag(String repo, String tag) throws IOException, InterruptedException {
        String api = apiBase + "/repos/" + repo + "/releases/tags/" + tag;
        HttpResponse<String> response = getJson(api);
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GitHub API " + api + " returned " + response.statusCode() + ": "
                    + truncate(response.body(), 512));
        }
        GhRelease r = Json.MAPPER.readValue(response.body(), GhRelease.class);
        String ver = tag.startsWith(RELEASE_TAG_PREFIX) ? tag.substring(RELEASE_TAG_PREFIX.length()) : tag;
        return new Release(ver, assetsOf(r));
    }

    private static Map<String, String> assetsOf(GhRelease r) {
        var assets = new LinkedHashMap<String, String>();
        if (r.assets() != null) {
            for (GhAsset a : r.assets()) {
                assets.put(a.name(), a.url());
            }
        }
        return assets;
    }

    /// The one GitHub-API GET, shared by [#latestRelease] and
    /// [#releaseByTag] — a single HTTP client, a single request shape, so
    /// there is exactly one place that builds a GitHub API request.
    private HttpResponse<String> getJson(String api) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(api))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "fcdev-upgrade")
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
        return httpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /// The subset of a GitHub release asset this reads.
    record GhAsset(String name, @JsonProperty("browser_download_url") String url) {
    }

    /// The subset of a GitHub release this reads.
    private record GhRelease(
            @JsonProperty("tag_name") String tagName,
            boolean draft,
            boolean prerelease,
            List<GhAsset> assets) {
    }

    byte[] httpGet(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "fcdev-upgrade")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("GET " + url + " returned " + response.statusCode());
        }
        return response.body();
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
    }

    // ── checksum + install ───────────────────────────────────────────────

    /// Checks `data` against a `<hex>  <filename>` sidecar (`sha256sum` /
    /// `shasum` format — only the leading hex digest matters). This is the
    /// one load-bearing gate between a downloaded archive and overwriting
    /// the running binary: [UpgradeCommandTest] breaks it on purpose (a
    /// mismatched sidecar) and asserts BOTH that the call throws AND that
    /// the destination file is untouched — a mutant that "skips" the check
    /// (calls this but ignores the result, or never calls it) would still
    /// replace the file and pass a test that only checked for the throw.
    static void verifySha256(byte[] data, byte[] sidecar) {
        String text = new String(sidecar, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            throw new IllegalStateException("empty sha256 sidecar");
        }
        String expected = text.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String actual = sha256Hex(data);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("checksum mismatch (expected " + expected + ", got " + actual + ")");
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /// Resolves the on-disk path of the running artifact (Go `selfPath`,
    /// `os.Executable()`) — the code source's own location, following
    /// symlinks so a launcher symlink is not what gets replaced.
    static Path selfPath() {
        try {
            var location = UpgradeCommand.class.getProtectionDomain().getCodeSource().getLocation();
            Path path = Path.of(location.toURI());
            try {
                return path.toRealPath();
            } catch (IOException notYetOnDiskOrNoPermission) {
                return path;
            }
        } catch (Exception e) {
            throw new IllegalStateException("locate the running fcdev artifact: " + e.getMessage(), e);
        }
    }

    /// Atomically swaps the file at `dest` for `newContent` — the jar path:
    /// written to a temp file in the SAME directory (so the rename stays on
    /// one filesystem) then renamed over `dest`. A partially-written file is
    /// never observable at `dest`'s path. A permission failure is reworded
    /// with Go's actionable hint.
    static void replaceFile(Path dest, byte[] newContent) throws IOException {
        Path dir = dest.toAbsolutePath().getParent();
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, ".fcdev-upgrade-", ".tmp");
        } catch (IOException e) {
            throw new IOException("cannot write to " + dir + " — re-run with elevated permissions or use the "
                    + "install script: " + e.getMessage(), e);
        }
        try {
            Files.write(tmp, newContent);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("install new artifact to " + dest + " — re-run with elevated permissions: "
                    + e.getMessage(), e);
        }
    }

    /// The native-binary path: same atomic swap as [#replaceFile], plus the
    /// executable bit (POSIX; a no-op elsewhere) and a Windows fallback —
    /// Windows refuses to overwrite a running `.exe`, so the live binary is
    /// moved aside to `.old` first, the new one slotted in, and the `.old`
    /// copy rolled back into place if that second rename fails.
    static void replaceExecutable(Path dest, byte[] newContent) throws IOException {
        Path dir = dest.toAbsolutePath().getParent();
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, ".fcdev-upgrade-", ".tmp");
        } catch (IOException e) {
            throw new IOException("cannot write to " + dir + " — re-run with elevated permissions or use the "
                    + "install script: " + e.getMessage(), e);
        }
        try {
            Files.write(tmp, newContent);
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException posixNotSupported) {
                // Windows: no POSIX permission bits; the OS decides executability by extension.
            }
            if (osName().equals("windows")) {
                Path old = Path.of(dest + ".old");
                Files.deleteIfExists(old);
                Files.move(dest, old, StandardCopyOption.ATOMIC_MOVE);
                try {
                    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException installFailed) {
                    Files.move(old, dest, StandardCopyOption.REPLACE_EXISTING); // roll back
                    throw installFailed;
                }
                Files.deleteIfExists(old);
            } else {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("install new artifact to " + dest + " — re-run with elevated permissions: "
                    + e.getMessage(), e);
        }
    }

    // ── semver ───────────────────────────────────────────────────────────

    /// Whether `s` is exactly `X.Y.Z` with numeric parts (no
    /// prerelease/build suffix) — Go's `isCleanSemver`.
    static boolean isCleanSemver(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 3) return false;
        for (String p : parts) {
            if (p.isEmpty()) return false;
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) return false;
            }
        }
        return true;
    }

    /// -1/0/+1 for clean `X.Y.Z` inputs (Go's `compareSemver`).
    static int compareSemver(String a, String b) {
        String[] pa = a.split("\\.", 3);
        String[] pb = b.split("\\.", 3);
        for (int i = 0; i < 3; i++) {
            int na = i < pa.length ? parseIntOrZero(pa[i]) : 0;
            int nb = i < pb.length ? parseIntOrZero(pb[i]) : 0;
            if (na != nb) return Integer.compare(na, nb);
        }
        return 0;
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }
}
