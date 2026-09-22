package io.flowcatalyst.example.subscriptiontest;

import io.flowcatalyst.function.EventEmitException;
import io.flowcatalyst.function.Events;
import io.flowcatalyst.function.OutboundEvent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/// An [Events] test double: records every emitted event, and can be told to
/// throw a specific [EventEmitException] on the NEXT [#emit] call only — so a
/// test can pin `HelloFunction`'s branch on the exception's status (5xx ⇒
/// retry, everything else ⇒ fail) one condition at a time.
final class RecordingEvents implements Events {

    final List<OutboundEvent> emitted = new CopyOnWriteArrayList<>();
    private volatile EventEmitException nextFailure;

    void throwNext(EventEmitException e) {
        this.nextFailure = e;
    }

    @Override
    public void emit(OutboundEvent event) {
        EventEmitException pending = nextFailure;
        if (pending != null) {
            nextFailure = null;
            throw pending;
        }
        emitted.add(event);
    }
}
