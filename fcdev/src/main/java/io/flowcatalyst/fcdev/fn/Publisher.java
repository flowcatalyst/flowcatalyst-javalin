package io.flowcatalyst.fcdev.fn;

import io.flowcatalyst.platform.function.api.FunctionApi;
import io.flowcatalyst.platform.shared.json.Json;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/// The shared publish logic behind `fn publish` and `fn deploy` (`fn deploy`
/// = publish + promote with the same options — spec §2). Kept as ordinary
/// static methods, not an object, so neither command's `@Option`-bound state
/// needs to be copied into a second type.
///
/// `--artifact-ref` omitted (spec `function-artifact-upload.md` §5): the jar
/// is UPLOADED through the platform (`PUT .../artifacts/{digest}`) and the
/// response's OWN `artifactRef` is what gets published — never a locally
/// built `platform://…` string, so a caller can never publish a ref the
/// platform did not itself hand back. One path, local dev and a deployed
/// platform alike — `Publisher.storeLocally`/`DevPaths.fnArtifactsDir` are
/// gone from this class (`fcdev start`'s own default store still uses the
/// accessor, just not from here).
final class Publisher {

    private static final Pattern REMOTE_REF = Pattern.compile("^(oci|s3)://.+");

    private Publisher() {
    }

    record Options(String jar, String manifestFile, String artifactRef, String bundleFile, String client,
                    boolean noCreate) {
    }

    record Outcome(String address, int version, String digest) {
    }

    /// The outcome of publishing a version (spec §5.1's `POST …/versions`):
    /// `Published` for the ordinary 201, `DigestExists` for the platform's
    /// `VERSION_DIGEST_EXISTS` 409 — the same jar republished is an expected
    /// answer, not a transport failure. Bare `fn publish` still treats a
    /// republish as a failure ([#publish] throws for it, unchanged, since
    /// `PublishCommand` wants exactly that); [#publishVersion] is the
    /// non-throwing view `fn deploy`/`fn watch` switch on instead of catching
    /// [FnClientException] and comparing `code()` (`DeployCommand`/
    /// `WatchCommand`'s own doc: "promotes the EXISTING version … rather than
    /// failing").
    sealed interface PublishOutcome {
        record Published(Outcome outcome) implements PublishOutcome {
        }

        record DigestExists(int existingVersion) implements PublishOutcome {
        }
    }

    /// [#publish], but `VERSION_DIGEST_EXISTS` comes back as
    /// [PublishOutcome.DigestExists] instead of a thrown [FnClientException]
    /// — every other failure (network, a different conflict, an upload
    /// rejection) still throws unchanged.
    static PublishOutcome publishVersion(CommandSpec spec, FnCommand root, String address, Options opts)
            throws IOException {
        try {
            return new PublishOutcome.Published(publish(spec, root, address, opts));
        } catch (FnClientException e) {
            Object existing = "VERSION_DIGEST_EXISTS".equals(e.code()) ? e.details().get("version") : null;
            if (existing instanceof Number n) {
                return new PublishOutcome.DigestExists(n.intValue());
            }
            throw e;
        }
    }

    static Outcome publish(CommandSpec spec, FnCommand root, String address, Options opts) throws IOException {
        Path jarPath = Path.of(opts.jar());
        String digest = sha256(jarPath);
        JsonNode manifest = readManifest(spec, opts.manifestFile());
        FnClient platform = root.client();

        String artifactRef;
        String signatureBundle = null;
        if (opts.artifactRef() != null && !opts.artifactRef().isBlank()) {
            if (!REMOTE_REF.matcher(opts.artifactRef()).matches()) {
                throw new CommandLine.ParameterException(spec.commandLine(),
                        "--artifact-ref must start with oci:// or s3://, got \"" + opts.artifactRef() + "\"");
            }
            artifactRef = opts.artifactRef();
            ensureFunctionExists(platform, address, manifest, opts.client(), opts.noCreate());
        } else {
            // The function must exist BEFORE the upload — the route 404s otherwise.
            ensureFunctionExists(platform, address, manifest, opts.client(), opts.noCreate());
            artifactRef = uploadArtifact(platform, address, digest, jarPath);
        }
        // Either way: the bundle signs the jar's bytes, not where they are kept, and a
        // platform with signatures required refuses an upload published without one.
        if (opts.bundleFile() != null && !opts.bundleFile().isBlank()) {
            signatureBundle = Files.readString(Path.of(opts.bundleFile()));
        }

        var body = new FunctionApi.PublishRequest(artifactRef, digest, signatureBundle, manifest);
        JsonNode response = platform.post("/api/functions/" + address + "/versions", Json.MAPPER.valueToTree(body));
        FunctionApi.PublishResponse parsed = Json.MAPPER.convertValue(response, FunctionApi.PublishResponse.class);
        return new Outcome(address, parsed.version(), parsed.digest());
    }

    /// `PUT /api/functions/{address}/artifacts/{digest}` (spec §3/§5) — the
    /// returned `artifactRef` is what gets published, never a ref this CLI
    /// built itself.
    private static String uploadArtifact(FnClient platform, String address, String digest, Path jarPath) {
        JsonNode response = platform.putFile("/api/functions/" + address + "/artifacts/" + digest, jarPath);
        FunctionApi.UploadArtifactResponse parsed =
                Json.MAPPER.convertValue(response, FunctionApi.UploadArtifactResponse.class);
        return parsed.artifactRef();
    }

    /// Whether `address` already exists on the platform — [#ensureFunctionExists]'s
    /// own `GET /api/functions/{address}` lookup. A 404 is this lookup's
    /// routine negative answer, never a transport failure; every other
    /// non-2xx status is still thrown as [FnClientException] straight out of
    /// [#lookupFunction] (only "does this address exist yet" is an expected
    /// outcome here). `NotFound` carries the platform's own 404
    /// [FnClientException] so [#ensureFunctionExists] can rethrow it verbatim
    /// under `--no-create`, never rebuilding its message.
    sealed interface Lookup {
        record Found() implements Lookup {
        }

        record NotFound(FnClientException notFoundError) implements Lookup {
        }
    }

    private static Lookup lookupFunction(FnClient platform, String address) {
        try {
            platform.get("/api/functions/" + address);
            return new Lookup.Found();
        } catch (FnClientException e) {
            if (e.status() == 404) {
                return new Lookup.NotFound(e);
            }
            throw e;
        }
    }

    /// Shared by `fn publish`/`fn deploy` and, for the create-on-set behaviour
    /// (`function-backlog-2026-09-22.md` Unit F), `fn config set`/`fn secret
    /// set`: a GET for `address`, and — on a 404 only — creates the function
    /// from `manifest`'s `runtime` (`client` as the owner) before returning.
    ///
    /// @param manifest may be `null` (no `--manifest` given and no default
    ///                 `manifest.json` found) — fine when the function
    ///                 already exists, but on a 404 with a `null` manifest
    ///                 this throws instead of creating.
    /// @throws FnClientException the GET failed for any reason OTHER than the
    ///                           function not existing yet — including a 404
    ///                           under `--no-create` ("exit 1 with the
    ///                           platform's 404 message", uniformly handled
    ///                           by [FnCommand#runSafely])
    /// @throws IOException a 404 with no manifest to create from — the exact
    ///                     message [FnCommand#runSafely] prints on exit 1
    static void ensureFunctionExists(FnClient platform, String address, JsonNode manifest, String client,
                                      boolean noCreate) throws IOException {
        switch (lookupFunction(platform, address)) {
            case Lookup.Found ignored -> {
            }
            case Lookup.NotFound(FnClientException notFoundError) -> {
                if (noCreate) {
                    throw notFoundError;
                }
                if (manifest == null) {
                    throw new IOException("function " + address + " does not exist and no manifest.json was found to "
                            + "create it from — pass --manifest, or run fn publish first");
                }
                String[] parts = address.split("\\.", -1);
                String runtime = manifest.path("runtime").asString(null);
                String ownerClientId = client == null || client.isBlank() ? null : client;
                var create = new FunctionApi.CreateFunctionRequest(parts[0], parts[1], parts[2], runtime, null, ownerClientId);
                platform.post("/api/functions", Json.MAPPER.valueToTree(create));
            }
        }
    }

    static JsonNode readManifest(CommandSpec spec, String manifestFile) throws IOException {
        String raw = Files.readString(Path.of(manifestFile));
        try {
            return Json.MAPPER.readTree(raw);
        } catch (RuntimeException e) {
            throw new CommandLine.ParameterException(spec.commandLine(),
                    "invalid JSON in manifest file " + manifestFile + ": " + e.getMessage());
        }
    }

    /// `fn config set`/`fn secret set`'s `--manifest` (spec Unit F): unlike
    /// `publish`'s (required), theirs is optional — an explicit path is
    /// always read (and a bad one still fails loudly), but with no
    /// `--manifest` this looks for `manifest.json` in the working directory
    /// and returns `null`, not an error, when it is not there. A `null`
    /// result tells [#ensureFunctionExists] there is nothing to create from.
    static JsonNode resolveOptionalManifest(CommandSpec spec, String manifestFile) throws IOException {
        String file = manifestFile;
        if (file == null || file.isBlank()) {
            if (!Files.exists(Path.of("manifest.json"))) {
                return null;
            }
            file = "manifest.json";
        }
        return readManifest(spec, file);
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
