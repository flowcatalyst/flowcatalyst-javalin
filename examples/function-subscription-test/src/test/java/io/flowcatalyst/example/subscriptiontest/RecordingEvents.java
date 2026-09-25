package io.flowcatalyst.example.subscriptiontest;

import io.flowcatalyst.function.EmitResult;
import io.flowcatalyst.function.Events;
import io.flowcatalyst.function.OutboundEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// An [Events] test double: records every emitted event, and can be told to
/// refuse the NEXT [#emit] call only — so a test can pin `HelloFunction`'s
/// branch on the refusal's status (5xx ⇒ retry, everything else ⇒ fail) one
/// condition at a time.
final class RecordingEvents implements Events {

    final List<OutboundEvent> emitted = new CopyOnWriteArrayList<>();
    private volatile EmitResult.Refused nextRefusal;

    void refuseNext(EmitResult.Refused refusal) {
        this.nextRefusal = refusal;
    }

    @Override
    public EmitResult emit(OutboundEvent event) {
        EmitResult.Refused pending = nextRefusal;
        if (pending != null) {
            nextRefusal = null;
            return pending;
        }
        emitted.add(event);
        return new EmitResult.Emitted("evt_recorded_" + emitted.size());
    }
}
