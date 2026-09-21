package io.flowcatalyst.platform.function.artifact;

import io.flowcatalyst.platform.function.Digest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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

    /// Spec `function-artifact-upload.md` §5: a caller outside this package
    /// (`function-host`'s `PlatformArtifactStore`, scheme `platform`) is
    /// registered without `ArtifactStores` depending on it — a small,
    /// additive `extra` map checked after `file`/`oci`. Mutant: drop the
    /// `extra` lookup (always `UnsupportedScheme`).
    @Test
    void extraSchemeIsRoutedToItsRegisteredStore(@TempDir Path cacheDir) throws Exception {
        ArtifactStore platformStore = new RecordingStore();
        var stores = new ArtifactStores(
                new FileArtifactStore(cacheDir.resolve("file")),
                new OciArtifactStore(cacheDir.resolve("oci"), RegistryCredentials.none()),
                Map.of("platform", platformStore));

        assertThat(stores.forRef("platform://fnc_1/deadbeef"))
                .as("mutant: drop the extra lookup").isSameAs(platformStore);
    }

    /// The three-arg `fetch` must reach the routed store's OWN three-arg
    /// overload with `versionId` intact — not silently fall back to the
    /// routed store's two-arg form. Mutant: `ArtifactStores#fetch(ref,
    /// expected, versionId)` calls the routed store's two-arg `fetch`.
    @Test
    void fetchThreeArgPassesVersionIdToTheRoutedStore(@TempDir Path cacheDir) throws Exception {
        var recording = new RecordingStore();
        var stores = new ArtifactStores(
                new FileArtifactStore(cacheDir.resolve("file")),
                new OciArtifactStore(cacheDir.resolve("oci"), RegistryCredentials.none()),
                Map.of("platform", recording));

        stores.fetch("platform://fnc_1/deadbeef", FileArtifactStoreTest.digestOf("x"), "ver-9");

        assertThat(recording.versionIdSeen).as("mutant: call the routed store's two-arg fetch").isEqualTo("ver-9");
        assertThat(recording.twoArgCalls).as("mutant: call the routed store's two-arg fetch").isZero();
    }

    /// A trivial [ArtifactStore] that records which overload it was called
    /// through — a plain lambda cannot distinguish the two, since the
    /// interface's default three-arg form just calls the two-arg one.
    private static final class RecordingStore implements ArtifactStore {
        String versionIdSeen;
        int twoArgCalls;

        @Override
        public Fetched fetch(String artifactRef, Digest expected) {
            twoArgCalls++;
            return new Fetched(Path.of("/dev/null"), 0);
        }

        @Override
        public Fetched fetch(String artifactRef, Digest expected, String versionId) {
            versionIdSeen = versionId;
            return new Fetched(Path.of("/dev/null"), 0);
        }
    }
}
