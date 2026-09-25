package io.flowcatalyst.fnhost.context;

import io.flowcatalyst.fnhost.reconcile.ControlPlane;
import io.flowcatalyst.function.EmitResult;
import io.flowcatalyst.function.Events;
import io.flowcatalyst.function.OutboundEvent;
import io.flowcatalyst.platform.function.FunctionAddress;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/// [Events] over [ControlPlane#emit] (spec `function-context.md` §3): one
/// `POST /control/functions/events` per [#emit] call, a batch of one, with
/// this loaded version's own address/version so the platform can check
/// `hostId`/`address`/`version` against what this host actually serves.
/// `correlationId`/`causationId` fall back to [InvocationEmitDefaults]
/// (bound per-invocation by [io.flowcatalyst.fnhost.http.InvocationRunner])
/// only when the event itself did not set them — never a mutable field on
/// the shared [HostFunctionContext].
final class ControlPlaneEvents implements Events {

    private final ControlPlane controlPlane;
    private final String hostId;
    private final FunctionAddress address;
    private final int version;

    ControlPlaneEvents(ControlPlane controlPlane, String hostId, FunctionAddress address, int version) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.hostId = Objects.requireNonNull(hostId, "hostId");
        this.address = Objects.requireNonNull(address, "address");
        this.version = version;
    }

    @Override
    public EmitResult emit(OutboundEvent event) {
        Objects.requireNonNull(event, "event");
        InvocationEmitDefaults.Defaults defaults = InvocationEmitDefaults.current();
        String correlationId = event.correlationId() != null ? event.correlationId() : defaults.correlationId();
        String causationId = event.causationId() != null ? event.causationId() : defaults.causationId();
        String dataJson = new String(event.data(), StandardCharsets.UTF_8);
        ControlPlane.EmitItem item = new ControlPlane.EmitItem(event.type(), event.subject(), event.dedupId(),
                dataJson, correlationId, causationId, event.messageGroup());
        return controlPlane.emit(new ControlPlane.EmitRequest(hostId, address, version, List.of(item)));
    }
}
