package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.fcdev.DevPaths;
import io.flowcatalyst.platform.function.api.FunctionApi;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/// The shared publish logic behind `fn publish` and `fn deploy` (`fn deploy`
/// = publish + promote with the same options — spec §2). Kept as ordinary
/// static methods, not an object, so neither command's `@Option`-bound state
/// needs to be copied into a second type.
final class Publisher {

    private static final Pattern REMOTE_REF = Pattern.compile("^(oci|s3)://.+");

    private Publisher() {
    }

    record Options(String jar, String manifestFile, String artifactRef, String bundleFile, String client,
                    boolean noCreate) {
    }

    record Outcome(String address, int version, String digest) {
    }

    static Outcome publish(CommandSpec spec, FnCommand root, String address, Options opts) throws IOException {
        Path jarPath = Path.of(opts.jar());
        String digest = sha256(jarPath);
        JsonNode manifest = readManifest(spec, opts.manifestFile());

        String artifactRef;
        String signatureBundle = null;
        if (opts.artifactRef() != null && !opts.artifactRef().isBlank()) {
            if (!REMOTE_REF.matcher(opts.artifactRef()).matches()) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "--artifact-ref must start with oci:// or s3://, got \"" + opts.artifactRef() + "\"");
            }
            artifactRef = opts.artifactRef();
            if (opts.bundleFile() != null && !opts.bundleFile().isBlank()) {
                signatureBundle = Files.readString(Path.of(opts.bundleFile()));
            }
        } else {
            artifactRef = storeLocally(jarPath, digest, root.paths());
        }

        FnClient platform = root.client();
        ensureFunctionExists(platform, address, manifest, opts.client(), opts.noCreate());
        var body = new FunctionApi.PublishRequest(artifactRef, digest, signatureBundle, manifest);
        JsonNode response = platform.post("/api/functions/" + address + "/versions", Json.MAPPER.valueToTree(body));
        FunctionApi.PublishResponse parsed = Json.MAPPER.convertValue(response, FunctionApi.PublishResponse.class);
        return new Outcome(address, parsed.version(), parsed.digest());
    }

    /// @throws FnClientException the GET failed for any reason OTHER than the
    ///                           function not existing yet — including a 404
    ///                           under `--no-create` ("exit 1 with the
    ///                           platform's 404 message", uniformly handled
    ///                           by [FnCommand#runSafely])
    private static void ensureFunctionExists(FnClient platform, String address, JsonNode manifest, String client,
                                              boolean noCreate) {
        try {
            platform.get("/api/functions/" + address);
        } catch (FnClientException e) {
            if (e.status() != 404 || noCreate) {
                throw e;
            }
            String[] parts = address.split("\\.", -1);
            String runtime = manifest.path("runtime").asString(null);
            String ownerClientId = client == null || client.isBlank() ? null : client;
            var create = new FunctionApi.CreateFunctionRequest(parts[0], parts[1], parts[2], runtime, null, ownerClientId);
            platform.post("/api/functions", Json.MAPPER.valueToTree(create));
        }
    }

    private static JsonNode readManifest(CommandSpec spec, String manifestFile) throws IOException {
        String raw = Files.readString(Path.of(manifestFile));
        try {
            return Json.MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "invalid JSON in manifest file " + manifestFile + ": " + e.getMessage());
        }
    }

    /// Copies `jarPath` into `<state>/fn-artifacts/<hex>.jar` — an atomic move
    /// from a temp file in the same directory, so a reader never sees a
    /// partial file; reused verbatim when the digest already has a copy
    /// there (spec §4 E4: "stores the copy, not the build path").
    static String storeLocally(Path jarPath, String digest, DevPaths paths) throws IOException {
        String hex = digest.substring("sha256:".length());
        Path dir = paths.fnArtifactsDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(hex + ".jar");
        if (!Files.exists(target)) {
            Path tmp = Files.createTempFile(dir, "upload-", ".jar.tmp");
            try {
                Files.copy(jarPath, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        return target.toUri().toString();
    }

    static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
        }
        return "sha256:" + HexFormat.of().formatHex(md.digest());
    }
}
