package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;

/// `FC_FN_ARTIFACT_STORE=s3://bucket[/prefix]` (spec
/// `function-artifact-upload.md` §2): key `<prefix>/<functionId>/<hex>`.
/// `exists` then `put` — `If-None-Match: *` is not relied on, since the
/// content at a given digest is identical by construction (spec's own
/// note).
public final class S3ArtifactBlobStore implements ArtifactBlobStore {

    private final S3Client client;
    private final String bucket;
    /// `""` when none configured, otherwise carrying neither a leading nor a
    /// trailing slash — [#objectPrefix] appends the one separator it needs.
    private final String prefix;

    public S3ArtifactBlobStore(S3Client client, String bucket, String prefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
    }

    @Override
    public void put(String functionId, Digest digest, Path file) throws ArtifactException {
        if (exists(functionId, digest)) {
            return;
        }
        String key = objectKey(functionId, digest);
        try {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromFile(file));
        } catch (S3Exception e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    @Override
    public boolean exists(String functionId, Digest digest) throws ArtifactException {
        String key = objectKey(functionId, digest);
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    @Override
    public InputStream open(String functionId, Digest digest) throws ArtifactException {
        String key = objectKey(functionId, digest);
        try {
            return client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException e) {
            throw new ArtifactException(new ArtifactException.NotFound());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new ArtifactException(new ArtifactException.NotFound());
            }
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    @Override
    public long size(String functionId, Digest digest) throws ArtifactException {
        String key = objectKey(functionId, digest);
        try {
            var head = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return head.contentLength();
        } catch (NoSuchKeyException e) {
            throw new ArtifactException(new ArtifactException.NotFound());
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new ArtifactException(new ArtifactException.NotFound());
            }
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    @Override
    public void deleteAll(String functionId) throws ArtifactException {
        String listPrefix = objectPrefix(functionId);
        try {
            String continuationToken = null;
            do {
                var reqBuilder = ListObjectsV2Request.builder().bucket(bucket).prefix(listPrefix);
                if (continuationToken != null) {
                    reqBuilder.continuationToken(continuationToken);
                }
                ListObjectsV2Response page = client.listObjectsV2(reqBuilder.build());
                for (S3Object obj : page.contents()) {
                    client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(obj.key()).build());
                }
                continuationToken = Boolean.TRUE.equals(page.isTruncated()) ? page.nextContinuationToken() : null;
            } while (continuationToken != null);
        } catch (S3Exception e) {
            throw new ArtifactException(new ArtifactException.Transport(e));
        }
    }

    private String objectKey(String functionId, Digest digest) throws ArtifactException {
        ArtifactBlobKeys.Key k = ArtifactBlobKeys.of(functionId, digest);
        return objectPrefix(k.functionId()) + k.hex();
    }

    /// `<prefix>/<functionId>/`, or `<functionId>/` with no configured prefix.
    private String objectPrefix(String functionId) throws ArtifactException {
        String validated = ArtifactBlobKeys.validateFunctionId(functionId);
        return (prefix.isEmpty() ? "" : prefix + "/") + validated + "/";
    }
}
