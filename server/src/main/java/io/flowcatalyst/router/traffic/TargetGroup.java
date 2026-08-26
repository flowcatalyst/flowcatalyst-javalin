package io.flowcatalyst.router.traffic;

/// The three load-balancer operations [AlbTraffic] needs.
///
/// An interface so the *policy* — when to register, how long to wait for a
/// drain, what to do when it fails — is testable without AWS, which is the
/// part with decisions in it. The ELBv2 calls themselves are mechanical.
public interface TargetGroup {

    /// Add this instance to the target group. Idempotent at the AWS API.
    void register(String targetId, int port);

    /// Remove this instance. Existing connections drain afterwards.
    void deregister(String targetId, int port);

    /// @return whether the balancer still reports the target as draining,
    ///         i.e. finishing in-flight requests
    boolean draining(String targetId, int port);

    /// How the balancer names this group — the ARN for ELBv2.
    ///
    /// Reported on `/monitoring/traffic-status` and nowhere else: registration
    /// itself never needs it, because the implementation already holds it.
    /// Abstract rather than defaulted so a new implementation has to say what
    /// it registers with; a default of `""` would let one quietly report
    /// nothing on the endpoint whose whole job is naming the group.
    String arn();
}
