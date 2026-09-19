package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.HasId;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/// A running function host, self-registered under its own id (spec
/// `function-registry.md` §6.3). Natural key — no TSID, `id` is the host's
/// own name.
///
/// @param id            the host's own identity
/// @param pool          the execution pool it serves; immutable after [#register]
/// @param state         `ACTIVE` or `DRAINING`
/// @param loaded        the versions this host has fetched, verified or loaded
/// @param startedAt     insert-only — never rewritten by a heartbeat
/// @param lastHeartbeat updated on every [#heartbeat]
public record FunctionHost(
        String id,
        DnsLabel pool,
        HostState state,
        List<LoadedVersion> loaded,
        Instant startedAt,
        Instant lastHeartbeat) implements HasId {

    /// Three missed 15 s beats (spec `function-api.md` §6.1: "45 s = three
    /// missed 15 s beats") — the window [io.flowcatalyst.platform.function.FunctionHostRepository#listLive]
    /// / `#pools` and Status's `stale` all key off, driven by an explicit
    /// `now` everywhere it is used rather than `Instant.now()` so a test can
    /// move it without sleeping.
    public static final Duration LIVE_WINDOW = Duration.ofSeconds(45);

    public FunctionHost {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(state, "state");
        loaded = List.copyOf(loaded);
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(lastHeartbeat, "lastHeartbeat");
    }

    /// The constant name is the stored string (`CONVENTIONS.md` §2).
    public enum HostState {
        ACTIVE, DRAINING;

        /// @throws IllegalArgumentException `raw` is not `ACTIVE` or `DRAINING`
        public static HostState parse(String raw) {
            return switch (raw) {
                case "ACTIVE" -> ACTIVE;
                case "DRAINING" -> DRAINING;
                case null, default -> throw new IllegalArgumentException("unrecognised host state: " + raw);
            };
        }
    }

    /// One function version this host has fetched (spec §6.3).
    ///
    /// @param address the function's address
    /// @param version the version number this entry describes
    /// @param state   `Registered` \| `Loaded` \| `Failed`
    public record LoadedVersion(FunctionAddress address, int version, LoadState state) {
        public LoadedVersion {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(state, "state");
        }
    }

    /// A loaded version's lifecycle on the host (spec §6.3): `Registered` is
    /// a lazy function whose artifact the host has fetched and verified but
    /// not loaded (design §4), `Loaded` is in memory, `Failed` carries the
    /// last error for Status. [#ok] is declared abstract, not defaulted —
    /// every state must state its own opinion (`CONVENTIONS.md` §8: "a
    /// defaulted member on a sealed interface is untested by construction").
    public sealed interface LoadState {

        /// Whether traffic may still be routed here — true for the first two.
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

    /// A fresh, `ACTIVE` host with nothing loaded.
    public static FunctionHost register(String id, DnsLabel pool, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pool, "pool");
        Objects.requireNonNull(now, "now");
        return new FunctionHost(id, pool, HostState.ACTIVE, List.of(), now, now);
    }

    /// Replaces `state` and `loaded` wholesale and stamps `lastHeartbeat`
    /// (spec §6.3). `pool` never changes after [#register].
    public FunctionHost heartbeat(HostState newState, List<LoadedVersion> newLoaded, Instant now) {
        Objects.requireNonNull(newState, "newState");
        Objects.requireNonNull(newLoaded, "newLoaded");
        Objects.requireNonNull(now, "now");
        return new FunctionHost(id, pool, newState, newLoaded, startedAt, now);
    }
}
