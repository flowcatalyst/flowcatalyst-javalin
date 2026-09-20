package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.fnhost.load.FunctionRegistry;
import io.flowcatalyst.fnhost.load.JvmFunctionLoader;
import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.artifact.FileArtifactStore;
import io.flowcatalyst.platform.function.artifact.Signatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/// [Reconciler#readiness(boolean, boolean)] (`docs/spec/function-host-process.md`
/// §3 item 3) in isolation — pure state, no HTTP: [io.flowcatalyst.fnhost.http.FnObservabilityHealthReadyTest]
/// pins that the real `/health`/`/ready` handlers actually call this with
/// live suppliers and map every state to the right status code.
class ReconcilerReadinessTest {

    @Test
    void precedenceAmongEveryState(@TempDir Path dir) {
        FakeControlPlane fake = new FakeControlPlane();
        Reconciler r = new Reconciler(new DnsLabel("pool"), "host-1", fake,
                new FileArtifactStore(dir.resolve("cache")), new Signatures.Off(), new JvmFunctionLoader(),
                new FunctionRegistry(10));

        // Nothing has been attempted yet — STARTING regardless of listener/loop.
        assertThat(r.readiness(true, true))
                .as("mutant: report something other than STARTING before any attempt").isEqualTo(Reconciler.Readiness.STARTING);
        assertThat(r.readiness(false, false)).isEqualTo(Reconciler.Readiness.STARTING);

        // Attempted, but every attempt so far has failed — PLATFORM_UNREACHABLE.
        fake.desiredStateReturns((pool, etag) ->
                { throw new ControlPlaneException(ControlPlaneException.Reason.UNAVAILABLE, "down"); });
        r.reconcileOnce(Instant.now());
        assertThat(r.readiness(true, true))
                .as("mutant: report READY on first ATTEMPT rather than first SUCCESS")
                .isEqualTo(Reconciler.Readiness.PLATFORM_UNREACHABLE);

        // A real success.
        fake.desiredStateReturns((pool, etag) -> new ControlPlane.Fetched.NotModified());
        r.reconcileOnce(Instant.now());

        assertThat(r.readiness(true, true))
                .as("mutant: ignore listenerBound/reconcileLoopAlive and just report READY")
                .isEqualTo(Reconciler.Readiness.READY);
        assertThat(r.readiness(false, true))
                .as("mutant: never check listenerBound").isEqualTo(Reconciler.Readiness.LISTENER_DOWN);
        assertThat(r.readiness(true, false))
                .as("mutant: never check reconcileLoopAlive").isEqualTo(Reconciler.Readiness.RECONCILER_DOWN);
        assertThat(r.readiness(false, false))
                .as("mutant: wrong precedence between LISTENER_DOWN and RECONCILER_DOWN — listener wins")
                .isEqualTo(Reconciler.Readiness.LISTENER_DOWN);

        // DRAINING wins over every other state, including LISTENER_DOWN/RECONCILER_DOWN.
        r.drain();
        assertThat(r.readiness(false, false))
                .as("mutant: DRAINING loses precedence to LISTENER_DOWN/RECONCILER_DOWN")
                .isEqualTo(Reconciler.Readiness.DRAINING);
        assertThat(r.readiness(true, true))
                .as("mutant: DRAINING loses precedence to READY").isEqualTo(Reconciler.Readiness.DRAINING);
    }
}
