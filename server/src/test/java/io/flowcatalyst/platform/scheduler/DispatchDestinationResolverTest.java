package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.dispatch.DispatchQueueSettings;
import io.flowcatalyst.platform.shared.dispatch.DispatchMode;
import io.flowcatalyst.router.wire.MediationType;
import io.flowcatalyst.router.wire.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static io.flowcatalyst.platform.dispatchjob.DispatchJobFixture.RUN;
import static io.flowcatalyst.platform.scheduler.SchedulerFixture.DATA_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;

/// [DispatchDestinationResolver]'s R4 priority-resolution order
/// (`docs/spec/dispatch-job-priority.md`): the job's OWN `queue` wins over
/// its subscription's when it names a recognised value; a job with no
/// queue of its own — or one holding legacy text that names nothing — falls
/// through to the subscription; unrecognised text everywhere reads as
/// `DEFAULT`, never an error. `SqsDispatchPublisherTest`/
/// `PostgresQueuePublisherTest` cover R6's subscription-only fallback rules
/// (no job-level `queue` at all); this class is the R4 job-vs-subscription
/// precedence itself.
class DispatchDestinationResolverTest {

    /// Postgres/dev-shaped settings (`sqs=false`, no prefix): the `.fifo`
    /// suffix and length cap never apply, keeping the composed names in
    /// this class simple `tenant-PRIORITY` strings.
    private static final DispatchQueueSettings SETTINGS = new DispatchQueueSettings(false, "", "", "", "");

    private static DispatchDestinationResolver resolver() {
        return new DispatchDestinationResolver(
                new PoolCodeResolver(DATA_SOURCE), new SubscriptionPriorityCache(DATA_SOURCE), SETTINGS);
    }

    private static PublishedMessage published(String jobId, String clientId, String subscriptionId, String queue) {
        Message message = new Message(jobId, "pool-code", "auth-token", null, MediationType.HTTP,
                "http://localhost/api/dispatch/process", "g", false, DispatchMode.IMMEDIATE);
        return new PublishedMessage(jobId, Instant.now(), clientId, subscriptionId, queue, message);
    }

    private static String queueUrl(String tenant, String priority) {
        return tenant + "-" + priority;
    }

    /// T6: the job's own recognised queue wins even when the subscription
    /// disagrees. Mutant: consult the subscription first — this must fail.
    @Test
    void jobsOwnQueueWinsOverADisagreeingSubscription() {
        String clientIdentifier = "ddrwins" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);
        String lowSub = SchedulerFixture.subscriptionWithQueue("DEFAULT");

        var name = resolver().destinationFor(published("ddr-wins-" + RUN, clientId, lowSub, "HIGH_PRIORITY"));

        assertThat(name.value()).as("the job's own queue must win over a disagreeing subscription")
                .isEqualTo(queueUrl(clientIdentifier, "HIGH_PRIORITY"));
    }

    /// T7: a legacy job (no queue of its own — `null`) still resolves
    /// through its subscription, exactly as before this column existed.
    /// Mutant: drop the subscription fallback — this must fail.
    @Test
    void legacyJobWithNoQueueOfItsOwnFallsBackToItsSubscription() {
        String clientIdentifier = "ddrlegacy" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);
        String highSub = SchedulerFixture.subscriptionWithQueue("HIGH_PRIORITY");

        var name = resolver().destinationFor(published("ddr-legacy-" + RUN, clientId, highSub, null));

        assertThat(name.value()).as("a legacy job (no queue of its own) must still fall back to its subscription")
                .isEqualTo(queueUrl(clientIdentifier, "HIGH_PRIORITY"));
    }

    /// The extra case the review added: a job whose OWN queue holds
    /// unrecognised legacy text has not named a priority at all, so it
    /// defers to its subscription — distinct from T8, where the
    /// subscription ALSO names nothing. Mutant: treat unrecognised text on
    /// the job as DEFAULT+ok instead of falling through — this must fail
    /// (T7 and T8 alone would both survive that mutant).
    @Test
    void jobWithLegacyTextOfItsOwnFallsBackToItsSubscriptionRatherThanReadingAsDefault() {
        String clientIdentifier = "ddrjoblegacytext" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);
        String highSub = SchedulerFixture.subscriptionWithQueue("HIGH_PRIORITY");

        var name = resolver().destinationFor(published("ddr-joblegacy-" + RUN, clientId, highSub, "workers-high"));

        assertThat(name.value())
                .as("legacy text on the job names no priority, so the subscription still decides — never DEFAULT")
                .isEqualTo(queueUrl(clientIdentifier, "HIGH_PRIORITY"));
    }

    /// T8: unrecognised text on BOTH the job's own queue and its
    /// subscription's reads as DEFAULT, never an error. Mutant: throw on
    /// unrecognised text anywhere — this must fail (as an exception).
    @Test
    void unrecognisedTextEverywhereReadsAsDefault() {
        String clientIdentifier = "ddrbothlegacy" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);
        String legacySub = SchedulerFixture.subscriptionWithQueue("workers-high");

        var name = resolver().destinationFor(published("ddr-bothlegacy-" + RUN, clientId, legacySub, "workers-high"));

        assertThat(name.value())
                .as("unrecognised text anywhere in the chain must read as DEFAULT, never error")
                .isEqualTo(queueUrl(clientIdentifier, "DEFAULT"));
    }

    /// T1/T2 at the publish-routing level: a directly-created job (no
    /// subscription_id — the SDK ingest surface, spec R3) publishes per its
    /// own queue when set, and to DEFAULT when it isn't.
    @Test
    void directJobWithNoSubscriptionUsesItsOwnQueueOrDefaultsWhenUnset() {
        String clientIdentifier = "ddrdirect" + RUN;
        String clientId = SchedulerFixture.client(clientIdentifier);

        var high = resolver().destinationFor(published("ddr-direct-hi-" + RUN, clientId, null, "HIGH_PRIORITY"));
        assertThat(high.value()).isEqualTo(queueUrl(clientIdentifier, "HIGH_PRIORITY"));

        var def = resolver().destinationFor(published("ddr-direct-lo-" + RUN, clientId, null, null));
        assertThat(def.value()).isEqualTo(queueUrl(clientIdentifier, "DEFAULT"));
    }
}
