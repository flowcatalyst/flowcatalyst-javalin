package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// [ArtifactStores] routes by scheme (spec `function-artifacts.md` §2).
class ArtifactStoresTest {

    @Test
    void routesFileAndOciByScheme(@TempDir Path cacheDir) throws Exception {
        var file = new FileArtifactStore(cacheDir.resolve("file"));
        var oci = new OciArtifactStore(cacheDir.resolve("oci"), RegistryCredentials.none());
        var stores = new ArtifactStores(file, oci);

        assertThat(stores.forRef("file:///abs/path.jar")).isSameAs(file);
        assertThat(stores.forRef("oci://ghcr.io/acme/thing")).isSameAs(oci);
    }

    @Test
    void s3SchemeIsUnsupported(@TempDir Path cacheDir) {
        var stores = new ArtifactStores(
                new FileArtifactStore(cacheDir.resolve("file")),
                new OciArtifactStore(cacheDir.resolve("oci"), RegistryCredentials.none()));

        assertThatThrownBy(() -> stores.forRef("s3://bucket/key"))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason())
                        .isEqualTo(new ArtifactException.UnsupportedScheme("s3")));
    }

    @Test
    void noSchemeIsBadRef(@TempDir Path cacheDir) {
        var stores = new ArtifactStores(
                new FileArtifactStore(cacheDir.resolve("file")),
                new OciArtifactStore(cacheDir.resolve("oci"), RegistryCredentials.none()));

        assertThatThrownBy(() -> stores.forRef("not-a-uri-at-all"))
                .isInstanceOf(ArtifactException.class)
                .satisfies(e -> assertThat(((ArtifactException) e).reason()).isInstanceOf(ArtifactException.BadRef.class));
    }

    @Test
    void fetchDelegatesToTheRoutedStore(@TempDir Path cacheDir) throws Exception {
        Path sourceDir = Files.createTempDirectory(cacheDir, "src");
        Path source = sourceDir.resolve("fn.jar");
        Files.writeString(source, "bytes");
        Digest digest = FileArtifactStoreTest.digestOf("bytes");

        var stores = new ArtifactStores(
                new FileArtifactStore(cacheDir.resolve("file")),
                new OciArtifactStore(cacheDir.resolve("oci"), RegistryCredentials.none()));

        ArtifactStore.Fetched fetched = stores.fetch("file://" + source, digest);
        assertThat(Files.readString(fetched.file())).isEqualTo("bytes");
    }
}
