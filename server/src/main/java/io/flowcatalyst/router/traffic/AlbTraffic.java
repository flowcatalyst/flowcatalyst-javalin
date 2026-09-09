package io.flowcatalyst.router.traffic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Registers and deregisters this instance with a load-balancer target group
/// (`docs/spec/router.md` §10.3).
///
/// ### Why deregistering waits
///
/// Removing a target tells the balancer to stop sending *new* requests; the
/// ones already in flight keep going. Exiting immediately would cut them
/// off, so a deregister polls until the balancer says the target has
/// finished draining — bounded, because a balancer that never stops saying
/// "draining" must not hold the process open forever.
///
/// ### Why nothing here is fatal
///
/// Traffic management concerns the HTTP API and dashboard. Message delivery
/// comes from queues and does not depend on it, so an instance that cannot
/// reach the balancer should keep draining its queues rather than refuse to
/// run. Every failure is recorded in [Traffic.Status#lastError] and
/// swallowed.
public final class AlbTraffic implements Traffic {

    private static final Logger log = LoggerFactory.getLogger(AlbTraffic.class);

    /// What [Traffic.Status#mode] reports while this is the strategy in use.
    public static final String MODE = "alb-target-group";

    /// How often to ask whether draining has finished (spec constant 51).
    static final Duration DRAIN_POLL_INTERVAL = Duration.ofSeconds(5);

    /// Default wait when none is configured (constant 51). Matches the AWS
    /// default deregistration delay, so the wait and the balancer's own
    /// timeout agree instead of one expiring first for no reason.
    public static final Duration DEFAULT_DRAIN_TIMEOUT = Duration.ofSeconds(300);

    /// @param targetId     this instance's IP or instance id in the group
    /// @param port         the port registered, usually the API port
    /// @param drainTimeout how long to wait for connections to finish
    /// @param pollInterval how often to ask whether draining has finished.
    ///                     Injectable because it is real elapsed time, and a
    ///                     test asserting that the wait is bounded should not
    ///                     have to sleep through the production cadence
    public record Config(String targetId, int port, Duration drainTimeout, Duration pollInterval) {

        public Config(String targetId, int port, Duration drainTimeout) {
            this(targetId, port, drainTimeout, DRAIN_POLL_INTERVAL);
        }

        public Config {
            if (targetId == null || targetId.isBlank()) {
                throw new IllegalArgumentException("targetId is required");
            }
            if (port < 1) {
                throw new IllegalArgumentException("port must be positive");
            }
            drainTimeout = drainTimeout == null || drainTimeout.isNegative() || drainTimeout.isZero()
                    ? DEFAULT_DRAIN_TIMEOUT
                    : drainTimeout;
            pollInterval = pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                    ? DRAIN_POLL_INTERVAL
                    : pollInterval;
        }
    }

    private final Config config;
    private final TargetGroup targetGroup;
    private final Clock clock;
    private final AtomicBoolean registered = new AtomicBoolean();
    private final AtomicReference<Instant> lastChange = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public AlbTraffic(Config config, TargetGroup targetGroup, Clock clock) {
        this.config = config;
        this.targetGroup = targetGroup;
        this.clock = clock;
    }

    @Override
    public void register() {
        try {
            targetGroup.register(config.targetId(), config.port());
            registered.set(true);
            lastChange.set(clock.instant());
            lastError.set(null);
            log.atInfo().setMessage("registered for traffic")
                    .addKeyValue("target", config.targetId())
                    .addKeyValue("port", config.port())
                    .log();
        } catch (RuntimeException e) {
            // Recorded, not thrown: an instance that cannot take HTTP traffic
            // can still drain its queues, and that is the more important job.
            lastError.set(e.toString());
            log.atWarn().setMessage("could not register for traffic")
                    .addKeyValue("target", config.targetId())
                    .addKeyValue("port", config.port())
                    .setCause(e)
                    .log();
        }
    }

    @Override
    public void deregister() {
        try {
            targetGroup.deregister(config.targetId(), config.port());
            // Set before the drain wait: from here the balancer is sending no
            // new requests, which is what "registered" is asked about, and a
            // long drain should not leave us reporting the wrong state.
            registered.set(false);
            lastChange.set(clock.instant());
            lastError.set(null);
        } catch (RuntimeException e) {
            // Deliberately leaves `registered` alone. Believing we are out
            // when the balancer still has us in is the dangerous direction:
            // it would let a shutdown proceed while requests still arrive.
            lastError.set(e.toString());
            log.atWarn().setMessage("could not deregister from traffic")
                    .addKeyValue("target", config.targetId())
                    .addKeyValue("port", config.port())
                    .setCause(e)
                    .log();
            return;
        }
        awaitDrain();
    }

    /// Polls until the balancer stops reporting the target as draining, or
    /// the configured wait elapses.
    private void awaitDrain() {
        var deadline = clock.instant().plus(config.drainTimeout());
        while (clock.instant().isBefore(deadline)) {
            try {
                if (!targetGroup.draining(config.targetId(), config.port())) {
                    log.atInfo().setMessage("traffic drained")
                            .addKeyValue("target", config.targetId())
                            .addKeyValue("port", config.port())
                            .log();
                    return;
                }
            } catch (RuntimeException e) {
                // We cannot tell whether it has drained. Stop asking rather
                // than spin: the deadline is the backstop either way, and a
                // balancer we cannot query will not answer differently in
                // five seconds.
                lastError.set(e.toString());
                log.atWarn().setMessage("could not check drain state; continuing")
                        .addKeyValue("target", config.targetId())
                        .addKeyValue("port", config.port())
                        .setCause(e)
                        .log();
                return;
            }
            try {
                Thread.sleep(config.pollInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.atWarn().setMessage("gave up waiting to drain")
                .addKeyValue("target", config.targetId())
                .addKeyValue("port", config.port())
                .addKeyValue("drain_timeout", config.drainTimeout())
                .log();
    }

    /// Closes the target group if it holds anything. Deregistration is a
    /// separate step and deliberately not done here: [Router] deregisters and
    /// waits for the drain *before* it stops the server, and folding that into
    /// close would either duplicate the wait or move it after the listeners
    /// have already gone.
    @Override
    public void close() {
        if (targetGroup instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("closing the target group client failed", e);
            }
        }
    }

    @Override
    public Status status() {
        return new Status(true, MODE, Optional.of(targetGroup.arn()), registered.get(),
                Optional.ofNullable(lastChange.get()), Optional.ofNullable(lastError.get()));
    }
}
