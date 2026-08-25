package io.flowcatalyst.router.traffic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetHealthResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeregisterTargetsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DeregisterTargetsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RegisterTargetsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.RegisterTargetsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealth;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthDescription;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetHealthStateEnum;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The three ELBv2 calls, and the one judgement among them.
///
/// The policy this sits under — when to register, how long to wait — is
/// [AlbTrafficTest]'s. What is asserted here is the request shape actually
/// sent (an ARN or port on the wrong field fails at AWS, not here) and what
/// counts as "still draining".
class Elbv2TargetGroupTest {

    private static final String ARN = "arn:aws:elasticloadbalancing:eu-west-1:1:targetgroup/fc/abc";

    private final FakeElbv2 client = new FakeElbv2();
    private final Elbv2TargetGroup group = new Elbv2TargetGroup(client, ARN);

    @Test
    @DisplayName("register sends the target group, id and port AWS expects")
    void registerSendsTheRightRequest() {
        group.register("10.0.0.7", 8080);

        assertThat(client.registered).singleElement().satisfies(request -> {
            assertThat(request.targetGroupArn()).isEqualTo(ARN);
            assertThat(request.targets()).singleElement().satisfies(target -> {
                assertThat(target.id()).isEqualTo("10.0.0.7");
                assertThat(target.port()).isEqualTo(8080);
            });
        });
    }

    @Test
    @DisplayName("deregister sends the target group, id and port AWS expects")
    void deregisterSendsTheRightRequest() {
        group.deregister("10.0.0.7", 8080);

        assertThat(client.deregistered).singleElement().satisfies(request -> {
            assertThat(request.targetGroupArn()).isEqualTo(ARN);
            assertThat(request.targets()).singleElement().satisfies(target -> {
                assertThat(target.id()).isEqualTo("10.0.0.7");
                assertThat(target.port()).isEqualTo(8080);
            });
        });
    }

    @Test
    @DisplayName("a target reported DRAINING is still draining")
    void drainingWhileAwsSaysSo() {
        client.health(TargetHealthStateEnum.DRAINING);

        assertThat(group.draining("10.0.0.7", 8080)).isTrue();
    }

    @Test
    @DisplayName("an empty health response is NOT draining")
    void emptyResponseIsNotDraining() {
        // The case that matters most, and the one an implementation is most
        // likely to get backwards. A deregistration that has completed removes
        // the target from the group, so the drain finishing and the target
        // vanishing are THE SAME EVENT. Reading absence as "still draining"
        // would make every clean shutdown burn the entire drain timeout —
        // five minutes per instance, on every deploy, waiting for a target
        // that is already gone.
        client.healthDescriptions.clear();

        assertThat(group.draining("10.0.0.7", 8080)).isFalse();
    }

    @Test
    @DisplayName("a healthy or unused target is not draining")
    void otherStatesAreNotDraining() {
        for (var state : List.of(TargetHealthStateEnum.HEALTHY, TargetHealthStateEnum.UNUSED,
                TargetHealthStateEnum.UNHEALTHY, TargetHealthStateEnum.INITIAL)) {
            client.health(state);

            assertThat(group.draining("10.0.0.7", 8080))
                    .as("%s is not a drain in progress", state)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a null health block is not draining")
    void missingHealthBlockIsNotDraining() {
        // TargetHealth is optional in the API. Dereferencing it blindly would
        // turn a shutdown into a NullPointerException on the drain path.
        client.healthDescriptions.clear();
        client.healthDescriptions.add(TargetHealthDescription.builder()
                .target(TargetDescription.builder().id("10.0.0.7").port(8080).build())
                .build());

        assertThat(group.draining("10.0.0.7", 8080)).isFalse();
    }

    @Test
    @DisplayName("draining asks about this target, not the whole group")
    void drainingScopesTheQuery() {
        // Without the target on the request, AWS answers for every instance in
        // the group — so one unrelated instance draining would hold this one's
        // shutdown open for the full timeout.
        client.health(TargetHealthStateEnum.HEALTHY);

        group.draining("10.0.0.7", 8080);

        assertThat(client.described).singleElement().satisfies(request -> {
            assertThat(request.targetGroupArn()).isEqualTo(ARN);
            assertThat(request.targets()).singleElement().satisfies(target ->
                    assertThat(target.id()).isEqualTo("10.0.0.7"));
        });
    }

    @Test
    @DisplayName("closing releases the SDK client")
    void closeReleasesTheClient() {
        // It owns a connection pool and its threads. Nothing else closes it.
        group.close();

        assertThat(client.closed).isTrue();
    }

    /// Hand-written, like the SQS fake: only the three calls are implemented,
    /// so a fourth appearing in the implementation fails loudly here.
    private static final class FakeElbv2 implements ElasticLoadBalancingV2Client {
        final List<RegisterTargetsRequest> registered = new ArrayList<>();
        final List<DeregisterTargetsRequest> deregistered = new ArrayList<>();
        final List<DescribeTargetHealthRequest> described = new ArrayList<>();
        final List<TargetHealthDescription> healthDescriptions = new ArrayList<>();
        boolean closed;

        void health(TargetHealthStateEnum state) {
            healthDescriptions.clear();
            healthDescriptions.add(TargetHealthDescription.builder()
                    .target(TargetDescription.builder().id("10.0.0.7").port(8080).build())
                    .targetHealth(TargetHealth.builder().state(state).build())
                    .build());
            described.clear();
        }

        @Override
        public RegisterTargetsResponse registerTargets(RegisterTargetsRequest request) {
            registered.add(request);
            return RegisterTargetsResponse.builder().build();
        }

        @Override
        public DeregisterTargetsResponse deregisterTargets(DeregisterTargetsRequest request) {
            deregistered.add(request);
            return DeregisterTargetsResponse.builder().build();
        }

        @Override
        public DescribeTargetHealthResponse describeTargetHealth(DescribeTargetHealthRequest request) {
            described.add(request);
            return DescribeTargetHealthResponse.builder()
                    .targetHealthDescriptions(healthDescriptions)
                    .build();
        }

        @Override
        public String serviceName() {
            return "elasticloadbalancing";
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
