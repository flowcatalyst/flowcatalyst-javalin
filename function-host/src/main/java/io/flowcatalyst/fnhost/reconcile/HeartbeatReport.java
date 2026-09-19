package io.flowcatalyst.fnhost.reconcile;

import io.flowcatalyst.platform.function.DnsLabel;
import io.flowcatalyst.platform.function.FunctionAddress;

import java.util.List;
import java.util.Objects;

/// The body of `POST /control/functions/heartbeat` (spec `function-api.md`
/// §6.2), as the host builds it (spec `function-host-reconciler.md` §1.2
/// step 5).
public record HeartbeatReport(String hostId, DnsLabel pool, HostState state, List<LoadedEntry> loaded) {

    public HeartbeatReport {
        Objects.requireNonNull(hostId, "hostId");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(state, "state");
        loaded = List.copyOf(loaded);
    }

    /// `ACTIVE`, or `DRAINING` once [Reconciler#drain] was called (spec
    /// §1.2 step 5).
    public enum HostState {
        ACTIVE, DRAINING
    }

    public record LoadedEntry(FunctionAddress address, int version, LoadState state) {
        public LoadedEntry {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(state, "state");
        }
    }

    /// One entry's state, exactly `function-api.md` §6.2's three wire values
    /// — [#ok] is abstract, not defaulted, so every case states its own
    /// opinion (`CONVENTIONS.md` §8 / this branch's rule: "a defaulted member
    /// on a sealed interface is untested by construction").
    public sealed interface LoadState {

        boolean ok();

        record Registered() implements LoadState {
            @Override
            public boolean ok() {
                return true;
            }
        }

        record Loaded() implements LoadState {
            @Override
            public boolean ok() {
                return true;
            }
        }

        record Failed(String error) implements LoadState {
            public Failed {
                Objects.requireNonNull(error, "error");
            }

            @Override
            public boolean ok() {
                return false;
            }
        }
    }
}
