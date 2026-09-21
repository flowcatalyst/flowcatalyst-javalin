package io.flowcatalyst.platform.function.artifact;

import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Optional;

/// `FC_FN_ARTIFACT_STORE` (spec `function-artifact-upload.md` §2): the
/// composition root's single resolution rule — a value-taking factory over
/// the raw string (`CONVENTIONS.md` §8, "subsystem knobs reach the
/// composition root through `Env`"), never read from the process
/// environment directly, so fcdev's map-loaded environment reaches it the
/// same way a real deployment's does.
public final class ArtifactBlobStores {

    private static final String FILE_PREFIX = "file://";
    private static final String S3_PREFIX = "s3://";

    private ArtifactBlobStores() {
    }

    /// `Optional.empty()` when `spec` is unset/blank — no store; the upload
    /// route and a `platform://` publish/download all answer `503` (spec
    /// §2's own table). Anything not `file://`/`s3://` **fails startup**,
    /// naming the variable and the two accepted shapes — never a silent
    /// default (spec §2's table, U8).
    public static Optional<ArtifactBlobStore> configure(String spec) {
        return configure(spec, null);
    }

    /// @param s3EndpointOverride test-only: forces path-style S3 access
    ///                           against a fake endpoint (spec §2's own note).
    public static Optional<ArtifactBlobStore> configure(String spec, URI s3EndpointOverride) {
        if (spec == null || spec.isBlank()) {
            return Optional.empty();
        }
        if (spec.startsWith(FILE_PREFIX)) {
            return Optional.of(new FileArtifactBlobStore(fileDir(spec)));
        }
        if (spec.startsWith(S3_PREFIX)) {
            return Optional.of(s3Store(spec, s3EndpointOverride));
        }
        throw new IllegalStateException(
                "FC_FN_ARTIFACT_STORE must be file:///abs/dir or s3://bucket[/prefix], got: " + spec);
    }

    private static Path fileDir(String spec) {
        URI uri = parse(spec);
        if (uri.getHost() != null && !uri.getHost().isEmpty()) {
            throw new IllegalStateException("FC_FN_ARTIFACT_STORE file:// must not carry a host: " + spec);
        }
        String path = uri.getPath();
        if (path == null || !path.startsWith("/")) {
            throw new IllegalStateException("FC_FN_ARTIFACT_STORE file:// must be an absolute path: " + spec);
        }
        return Path.of(path);
    }

    private static S3ArtifactBlobStore s3Store(String spec, URI endpointOverride) {
        URI uri = parse(spec);
        String bucket = uri.getHost();
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("FC_FN_ARTIFACT_STORE s3:// must name a bucket: " + spec);
        }
        String prefix = trimSlashes(uri.getPath() == null ? "" : uri.getPath());
        var builder = S3Client.builder()
                // The blob store's own sha256 (spec §2/§3) is the integrity check that
                // matters; skip the SDK's default per-request trailing checksum (which
                // wraps the body in aws-chunked framing) since nothing here needs it.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED);
        if (endpointOverride != null) {
            builder.endpointOverride(endpointOverride).forcePathStyle(true).region(Region.US_EAST_1);
        }
        return new S3ArtifactBlobStore(builder.build(), bucket, prefix);
    }

    private static URI parse(String spec) {
        try {
            return new URI(spec);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("FC_FN_ARTIFACT_STORE is not a valid URI: " + spec, e);
        }
    }

    private static String trimSlashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(start, end);
    }
}
