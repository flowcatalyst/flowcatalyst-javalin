package io.flowcatalyst.platform.function.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// `FC_FN_ARTIFACT_STORE` resolution (spec `function-artifact-upload.md`
/// §2): the composition root's value-taking factory.
class ArtifactBlobStoresTest {

    @Test
    void unsetOrBlankIsNoStore() {
        assertThat(ArtifactBlobStores.configure(null)).isEmpty();
        assertThat(ArtifactBlobStores.configure("")).isEmpty();
        assertThat(ArtifactBlobStores.configure("   ")).isEmpty();
    }

    @Test
    void fileSchemeBuildsAFileArtifactBlobStore(@TempDir Path dir) {
        Optional<ArtifactBlobStore> store = ArtifactBlobStores.configure("file://" + dir);
        assertThat(store).isPresent();
        assertThat(store.get()).isInstanceOf(FileArtifactBlobStore.class);
    }

    /// U8: an unrecognised scheme fails startup naming the variable, rather
    /// than silently defaulting to "no store" the way a blank value does.
    @Test
    void anUnrecognisedSchemeFailsStartupNamingTheVariable() {
        assertThatThrownBy(() -> ArtifactBlobStores.configure("ftp://x"))
                .as("mutant: default silently")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_FN_ARTIFACT_STORE")
                .hasMessageContaining("ftp://x");
    }

    @Test
    void aFileUriWithAHostIsRejected() {
        assertThatThrownBy(() -> ArtifactBlobStores.configure("file://host/some/path"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FC_FN_ARTIFACT_STORE");
    }

    @Test
    void aRelativeFileUriIsRejected() {
        assertThatThrownBy(() -> ArtifactBlobStores.configure("file://relative/path"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anS3UriWithNoBucketIsRejected() {
        assertThatThrownBy(() -> ArtifactBlobStores.configure("s3:///no-bucket"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket");
    }
}
