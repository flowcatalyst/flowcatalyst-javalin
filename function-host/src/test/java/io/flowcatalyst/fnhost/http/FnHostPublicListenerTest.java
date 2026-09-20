package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.fnhost.FnHost;
import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.fnhost.reconcile.ControlPlane;
import io.flowcatalyst.fnhost.reconcile.DesiredDocument;
import io.flowcatalyst.fnhost.reconcile.FakeControlPlane;
import io.flowcatalyst.fnhost.reconcile.HostEnv;
import io.flowcatalyst.fnhost.reconcile.Reconciler;
import io.flowcatalyst.fnhost.route.TrustedProxies;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import io.flowcatalyst.server.EnvReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// **Composition root pin** (task brief): a mutant that never wires
/// `HostEnv#publicPort`/`#trustedProxies` through `FnHost#start` into
/// `FnHttpServer.Options` — never binding the public listener, or binding
/// the PRIVATE pipeline onto its port instead — must die HERE, against the
/// real [FnHost], not just against a harness that already knows to pass
/// public options in (`FnHttpServerPublicListenerTest`, `FnHttpTestSupport`).
/// In package `.http` (not `.fnhost`) so it can use the package-private
/// [RawHttpClient] directly.
class FnHostPublicListenerTest {

    private static final FunctionAddress ADDR = FunctionAddress.parse("comp.svc.a");

    @Test
    void fnHostStartActuallyBindsThePublicListenerFromHostEnv(@TempDir Path dir) throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler reconciler = new Reconciler(new DnsLabel("pool"), "host-1", controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(), registry);
        controlPlane.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(), List.of(), List.of())));

        // The FULL canonical HostEnv constructor — publicPort=0 (ephemeral), the default
        // trusted-proxy list — is exactly what a real fcdev/production HostEnv.load() would
        // produce for FC_FN_PUBLIC_PORT unset/0.
        HostEnv env = new HostEnv(new DnsLabel("pool"), "http://127.0.0.1:1", "client-1", "secret-1", "host-1",
                new Signatures.Off(), 50, dir.resolve("cache2"), 0, 512, 3, 0, false, 16, 0, TrustedProxies.DEFAULT);
        FnHost host = new FnHost(env, reconciler, registry);
        try {
            host.start();
            assertThat(host.port()).as("mutant: never bind the private listener either").isPositive();
            assertThat(host.publicPort())
                    .as("mutant: never binds the public listener from HostEnv").isPositive();
            assertThat(host.publicPort()).as("the two listeners are different ports")
                    .isNotEqualTo(host.port());

            // And it must be the PUBLIC pipeline, not the private one reused on this port:
            // an address-shaped path is 404 here (mutant: bind the private pipeline on it).
            var resp = RawHttpClient.send(host.publicPort(), "GET",
                    "/functions/" + ADDR.render() + "/x", Map.of("Host", "somewhere.example.test"), null);
            assertThat(resp.status()).as("mutant: bind the private pipeline on the public port").isEqualTo(404);
        } finally {
            host.close();
        }
    }

    @Test
    void hostEnvOffDisablesThePublicListenerEndToEnd(@TempDir Path dir) throws Exception {
        FakeControlPlane controlPlane = new FakeControlPlane();
        FunctionRegistry registry = new FunctionRegistry(50);
        Reconciler reconciler = new Reconciler(new DnsLabel("pool"), "host-1", controlPlane,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(), registry);
        controlPlane.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.Changed("etag1",
                new DesiredDocument(List.of(), List.of(), List.of())));

        HostEnv env = new HostEnv(new DnsLabel("pool"), "http://127.0.0.1:1", "client-1", "secret-1", "host-1",
                new Signatures.Off(), 50, dir.resolve("cache2"), 0, 512, 3, 0, false, 16,
                HostEnv.PUBLIC_PORT_DISABLED, TrustedProxies.DEFAULT);
        FnHost host = new FnHost(env, reconciler, registry);
        try {
            host.start();
            assertThat(host.port()).isPositive();
            assertThat(host.publicPort()).as("FC_FN_PUBLIC_PORT=off must bind nothing")
                    .isEqualTo(HostEnv.PUBLIC_PORT_DISABLED);
        } finally {
            host.close();
        }
    }

    /// pin: `HostEnv.load` itself resolves `FC_FN_PUBLIC_PORT`/`FC_FN_TRUSTED_PROXIES`
    /// into exactly what `#start` above wires through.
    @Test
    void hostEnvLoadResolvesPublicPortAndTrustedProxiesFromTheEnvironment() {
        var reader = new EnvReader(Map.of(
                "FC_FN_PLATFORM_URL", "http://127.0.0.1:1",
                "FC_FN_CLIENT_ID", "c", "FC_FN_CLIENT_SECRET", "s", "FC_FN_HOST_ID", "host-1",
                "FC_FN_PUBLIC_PORT", "9099", "FC_FN_TRUSTED_PROXIES", "198.51.100.0/24"));
        HostEnv env = HostEnv.load(reader);
        assertThat(env.publicPort()).isEqualTo(9099);
        assertThat(env.trustedProxies().isTrusted("198.51.100.5")).isTrue();
        assertThat(env.trustedProxies().isTrusted("10.0.0.1")).as("mutant: fall back to the RFC1918 default")
                .isFalse();
    }

    @Test
    void hostEnvLoadDefaultsPublicPortTo8081() {
        var reader = new EnvReader(Map.of(
                "FC_FN_PLATFORM_URL", "http://127.0.0.1:1",
                "FC_FN_CLIENT_ID", "c", "FC_FN_CLIENT_SECRET", "s", "FC_FN_HOST_ID", "host-1"));
        assertThat(HostEnv.load(reader).publicPort()).isEqualTo(8081);
    }

    @Test
    void hostEnvLoadOffDisablesThePublicPort() {
        var reader = new EnvReader(Map.of(
                "FC_FN_PLATFORM_URL", "http://127.0.0.1:1",
                "FC_FN_CLIENT_ID", "c", "FC_FN_CLIENT_SECRET", "s", "FC_FN_HOST_ID", "host-1",
                "FC_FN_PUBLIC_PORT", "off"));
        assertThat(HostEnv.load(reader).publicPort()).isEqualTo(HostEnv.PUBLIC_PORT_DISABLED);
    }
}
