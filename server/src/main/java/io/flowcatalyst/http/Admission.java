package io.flowcatalyst.http;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/// The request's admission scope (`docs/spec/admission.md` §1, §11.7 part B):
/// bound as [#CURRENT] by the listener adapter around the whole before →
/// handler → after chain, it carries the request's [Group] — so
/// [io.flowcatalyst.platform.shared.database.Pools#routed()] can resolve the
/// physical pool the request's connections come from — and records how many
/// pool connections the request currently holds, so the pool gate can decide
/// whether a *nested* checkout joins the outer one ([Mode#PINNED]) or is just
/// another independent checkout ([Mode#PER_STATEMENT]) — and which
/// connections, so the deadline can `cancelQuery()` them (§4). Outside a
/// request nothing is bound and the gate applies without either mechanism.
public final class Admission {
    public static final ScopedValue<Admission> CURRENT = ScopedValue.newInstance();

    /// Whether a request pins one connection for its whole lifetime, or
    /// returns each connection to the pool between statements
    /// (`docs/spec/admission.md` §11.7 "part B"). Derived from the [Group],
    /// never configured — see [Group#mode()].
    public enum Mode {
        /// One connection for the whole request: a nested checkout joins the
        /// outer one (§10) and the nested-acquire guard is armed. `API_WRITE`,
        /// `DISPATCH`, `LOGIN`, `OIDC` — and `NO_DB`, which never checks out a
        /// connection at all under ordinary operation, defaults here to match
        /// the gate's original (pre-part-B) behaviour if it ever does.
        PINNED,
        /// Every checkout goes to the gate and the pool and is returned to
        /// the pool when its `Connection` closes; nothing is pinned across
        /// statements and the nested-acquire guard is not armed — there is
        /// nothing held to deadlock against. `API_READ`, `BFF`.
        PER_STATEMENT
    }

    private final String path;
    private final Group group;
    private final List<Connection> held = new ArrayList<>(2);

    public Admission(String path, Group group) {
        this.path = path;
        this.group = Objects.requireNonNull(group, "group");
    }

    /// The bound scope, or `null` outside a request.
    public static Admission currentOrNull() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /// The request path, for the guard's message.
    public String path() {
        return path;
    }

    /// The request's group — what [io.flowcatalyst.platform.shared.database.Pools#routed()]
    /// resolves the physical pool from.
    public Group group() {
        return group;
    }

    /// [Group#mode()] of [#group()].
    public Mode mode() {
        return group.mode();
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
