package io.flowcatalyst.platform.scheduler;

import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.dispatch.DispatchQueueSettings;
import io.flowcatalyst.platform.shared.dispatch.DispatchQueueName;
import io.flowcatalyst.platform.shared.dispatch.QueuePriority;

import java.util.Objects;

/// Resolves a claimed dispatch job's destination queue — the single place
/// [SqsDispatchPublisher] and [PostgresQueuePublisher] both compute (tenant,
/// priority) and turn it into the composed [DispatchQueueName]
/// (`docs/spec/deployed-dispatch.md` §3, unit D part 1).
///
/// Extracted so the two publishers CANNOT independently drift on where a job
/// goes: before this class existed, only [SqsDispatchPublisher] resolved a
/// job's tenant/priority at all — [PostgresQueuePublisher] published every
/// job to one fixed queue named after the database URL
/// (`Server#defaultQueueUri`). The instant the router started consuming the
/// platform's own served router-config document (unit B), which advertises
/// composed names like `platform-DEFAULT`, that mismatch would have meant
/// every dev dispatch job was published where nothing is listening. Both
/// publishers now call this one method instead of composing a name
/// themselves.
///
/// ### Tenant
///
/// [PoolCodeResolver#clientIdentifier(String)] — the SAME cached
/// `tnt_clients` lookup the rest of the scheduler already uses — falling
/// back to [ClientIdentifier#RESERVED_PLATFORM] (ruling R5) for a
/// client-less or unresolved job.
///
/// ### Priority
///
/// [SubscriptionPriorityCache#priorityFor(String)] (ruling R6): a job with no
/// subscription, an unresolvable one, a `NULL` stored value, or unrecognised
/// legacy text (`workers-high`) all read as [QueuePriority#DEFAULT] — never
/// an error, and never a dropped job.
///
/// ### Formatting
///
/// [DispatchQueueSettings#sqs()] alone decides whether the composed name is
/// `.fifo`-suffixed and length-capped ([DispatchQueueName#compose]) — the
/// SAME `settings` value the composition root resolves once from [Env] for
/// both publishers, so a dev/prod disagreement about queue *type* can never
/// leak into a disagreement about queue *identity*.
public final class DispatchDestinationResolver {

    private final PoolCodeResolver tenants;
    private final SubscriptionPriorityCache priorities;
    private final DispatchQueueSettings settings;

    public DispatchDestinationResolver(PoolCodeResolver tenants, SubscriptionPriorityCache priorities,
                                        DispatchQueueSettings settings) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.priorities = Objects.requireNonNull(priorities, "priorities");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /// The composed queue `m` publishes to.
    public DispatchQueueName destinationFor(PublishedMessage m) {
        String identifier = tenants.clientIdentifier(m.clientId());
        String tenant = identifier != null ? identifier : ClientIdentifier.RESERVED_PLATFORM;
        QueuePriority priority = priorities.priorityFor(m.subscriptionId());
        return DispatchQueueName.compose(settings.prefix(), tenant, priority, settings.sqs());
    }
}
