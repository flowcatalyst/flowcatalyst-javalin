package io.flowcatalyst.router.traffic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeregisterTargetsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RegisterTargetsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;

import java.util.Objects;

/// [TargetGroup] against a real ELBv2 target group.
///
/// Deliberately thin. Everything with a decision in it — when to register,
/// how long to wait for a drain, what to do when the wait times out — lives in
/// [AlbTraffic] and is tested without AWS. What is left here is three calls,
/// and the only judgement is what counts as "still draining".
public final class Elbv2TargetGroup implements TargetGroup, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Elbv2TargetGroup.class);

    private final ElasticLoadBalancingV2Client client;
    private final String targetGroupArn;

    public Elbv2TargetGroup(ElasticLoadBalancingV2Client client, String targetGroupArn) {
        this.client = Objects.requireNonNull(client, "client");
        this.targetGroupArn = Objects.requireNonNull(targetGroupArn, "targetGroupArn");
    }

    /// Builds a client for `region`, or the SDK's default region chain when
    /// it is blank.
    ///
    /// The region has to be honoured explicitly: the default chain resolves
    /// from the environment or instance metadata, which is the region the
    /// **instance** is in — not necessarily the one holding the target group.
    /// Get that wrong and every registration call goes to the wrong region's
    /// endpoint, so the instance never joins the balancer and nothing says
    /// why.
    ///
    /// Adopts the client so a failure constructing this wrapper closes it
    /// rather than stranding its connection pool — the same shape that leaked
    /// in the queue factories.
    public static Elbv2TargetGroup create(String targetGroupArn, String region) {
        var builder = ElasticLoadBalancingV2Client.builder();
        if (region != null && !region.isBlank()) {
            builder.region(software.amazon.awssdk.regions.Region.of(region.trim()));
        }
        var client = builder.build();
        try {
            return new Elbv2TargetGroup(client, targetGroupArn);
        } catch (RuntimeException e) {
            try {
                client.close();
            } catch (RuntimeException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
    }

    @Override
    public void register(String targetId, int port) {
        client.registerTargets(RegisterTargetsRequest.builder()
                .targetGroupArn(targetGroupArn)
                .targets(target(targetId, port))
                .build());
        log.info("traffic: registered {}:{} with target group {}", targetId, port, targetGroupArn);
    }

    @Override
    public void deregister(String targetId, int port) {
        client.deregisterTargets(DeregisterTargetsRequest.builder()
                .targetGroupArn(targetGroupArn)
                .targets(target(targetId, port))
                .build());
        log.info("traffic: deregistered {}:{} from target group {}", targetId, port, targetGroupArn);
    }

    @Override
    public boolean draining(String targetId, int port) {
        var response = client.describeTargetHealth(DescribeTargetHealthRequest.builder()
                .targetGroupArn(targetGroupArn)
                .targets(target(targetId, port))
                .build());
        // Any description in DRAINING means connections are still finishing.
        // Everything else — healthy, unused, or the target having vanished
        // from the group entirely — means there is nothing left to wait for.
        //
        // An EMPTY response is therefore "not draining", and deliberately so:
        // a deregistration that completed removes the target, and treating its
        // absence as "still draining" would make a successful drain look like
        // a hung one and burn the whole drain timeout on every clean shutdown.
        return response.targetHealthDescriptions().stream()
                .anyMatch(description -> description.targetHealth() != null
                        && description.targetHealth().state() == TargetHealthStateEnum.DRAINING);
    }

    @Override
    public String arn() {
        return targetGroupArn;
    }

    private static TargetDescription target(String targetId, int port) {
        return TargetDescription.builder().id(targetId).port(port).build();
    }

    @Override
    public void close() {
        client.close();
    }
}
