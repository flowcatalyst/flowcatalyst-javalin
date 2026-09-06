package io.flowcatalyst.http;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/// The request's admission scope (`docs/spec/admission.md` §1): bound as
/// [#CURRENT] by the listener adapter around the route handler, it records
/// how many pool connections the request currently holds — so the pool gate
/// can refuse a *nested* checkout before it waits (a second checkout while
/// holding one is a deadlock under a full gate) — and which ones, so the
/// deadline can `cancelQuery()` them (§4). Outside a request nothing is bound
/// and the gate applies without the guard.
public final class Admission {
    public static final ScopedValue<Admission> CURRENT = ScopedValue.newInstance();

    private final String path;
    private final List<Connection> held = new ArrayList<>(2);

    public Admission(String path) {
        this.path = path;
    }

    /// The bound scope, or `null` outside a request.
    public static Admission currentOrNull() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /// The request path, for the guard's message.
    public String path() {
        return path;
    }

    public synchronized void checkedOut(Connection c) {
        held.add(c);
    }

    public synchronized void released(Connection c) {
        held.remove(c);
    }

    public synchronized int held() {
        return held.size();
    }

    /// A snapshot of the connections the request holds right now.
    public synchronized List<Connection> heldConnections() {
        return List.copyOf(held);
    }
}
