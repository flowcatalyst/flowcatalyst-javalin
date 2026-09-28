package io.flowcatalyst.platform.dispatch;

import io.flowcatalyst.platform.client.ClientIdentifier;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.dispatchpool.DispatchPool;
import io.flowcatalyst.platform.dispatchpool.DispatchPoolRepository;
import io.flowcatalyst.platform.scheduler.PoolCodeResolver;
import io.flowcatalyst.platform.shared.dispatch.DispatchQueueName;
import io.flowcatalyst.platform.shared.dispatch.QueuePriority;
import io.flowcatalyst.platform.subscription.Subscription;
import io.flowcatalyst.platform.subscription.SubscriptionRepository;
import io.flowcatalyst.router.config.PoolSpec;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.config.RouterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Builds the router's `{processingPools, queues}` config document from the
/// database (`docs/spec/deployed-dispatch.md` §3) — what
/// `/api/dispatch/router-config` serves (R3). No queue or pool is created or
/// checked here; this only describes what should exist.
///
/// ### Pools: every `msg_dispatch_pools` row, any status
///
/// [io.flowcatalyst.platform.dispatchpool.DispatchPoolStatus] governs
/// nothing downstream today: neither [PoolCodeResolver#resolve] (which a
/// claimed job's `poolCode` goes through) nor the scheduler's claim query
/// filters on it — verified by reading every caller of the resolver and of
/// the status enum; the only readers of `status` are the aggregate's own
/// CRUD and its list-filter API. A pool's stamped `poolCode` therefore does
/// not change when it is suspended or archived, so excluding non-`ACTIVE`
/// rows here would only make the document disagree with what jobs are
/// already being stamped with — the router would log a ROUTING warning and
/// silently fall back to the synthesised default pool for a perfectly real,
/// just-not-`ACTIVE`, pool, changing its effective concurrency/rate limit
/// with nothing but a log line to explain why. Every row is included,
/// namespaced exactly as [PoolCodeResolver#composeCode] composes it, so a
/// stamped code always has a match.
///
/// ### Queues: both priorities for every tenant a job can be published to
///
/// A tenant is a client's identifier, or [ClientIdentifier#RESERVED_PLATFORM]
/// for client-less dispatch (R5). The tenants are, in first-seen order:
/// `platform`, then every `msg_dispatch_pools` row's client (any status),
/// then every `ACTIVE` `msg_subscriptions` row's client, then **every
/// client** in `tnt_clients`. Each tenant gets a `DEFAULT` **and** a
/// `HIGH_PRIORITY` queue.
///
/// **Why every client and both priorities (review 2026-09-28, as the Rust
/// port does):** the document must list every queue the scheduler can
/// publish to, or a job published there is consumed by nobody and sits
/// `QUEUED` for ever. [io.flowcatalyst.platform.scheduler.DispatchDestinationResolver]
/// sends a job to its **own** client's tenant queue and takes the priority
/// from the job's own `queue` claim before its subscription's
/// (`docs/spec/dispatch-job-priority.md` R4). The earlier rule — tenants only
/// from pools and active subscriptions, `HIGH_PRIORITY` only where an active
/// subscription asks for it — predates that ruling, so a job for a client
/// with no pool or subscription of its own, or a job created with
/// `queue: HIGH_PRIORITY` for a client none of whose subscriptions asks for
/// it, landed on a queue no router consumed. An extra queue that turns out
/// unused costs nothing: queues are created lazily (settled item 3) and the
/// router already tolerates one that does not exist yet.
///
/// A tenant whose composed queue name would exceed SQS's 80-character limit
/// ([DispatchQueueName.QueueNameTooLongException]) is omitted from the
/// document entirely, with a logged `CONFIGURATION` warning naming the
/// tenant, rather than failing the whole build — one bad identifier must not
/// take every other tenant's routing down with it.
public final class RouterConfigDocumentBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(RouterConfigDocumentBuilder.class);

    private final DispatchPoolRepository pools;
    private final SubscriptionRepository subscriptions;
    private final ClientRepository clients;
    private final DispatchQueueSettings settings;

    public RouterConfigDocumentBuilder(DataSource dataSource, DispatchQueueSettings settings) {
        this(new DispatchPoolRepository(dataSource), new SubscriptionRepository(dataSource),
                new ClientRepository(dataSource), settings);
    }

    RouterConfigDocumentBuilder(DispatchPoolRepository pools, SubscriptionRepository subscriptions,
                                ClientRepository clients, DispatchQueueSettings settings) {
        this.pools = Objects.requireNonNull(pools, "pools");
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.clients = Objects.requireNonNull(clients, "clients");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public RouterConfig build() {
        List<DispatchPool> poolRows = pools.findAll();
        List<Subscription> activeSubscriptions = subscriptions.findActiveOrderedById();
        List<String> clientIdentifiers = clients.findAllIdentifiers();
        return new RouterConfig(buildPools(poolRows),
                buildQueues(poolRows, activeSubscriptions, clientIdentifiers));
    }

    private static List<PoolSpec> buildPools(List<DispatchPool> poolRows) {
        List<PoolSpec> specs = new ArrayList<>(poolRows.size());
        for (DispatchPool p : poolRows) {
            String code = PoolCodeResolver.composeCode(p.code(), p.clientIdentifier());
            specs.add(new PoolSpec(code, p.concurrency(), p.rateLimit() == null ? 0 : p.rateLimit()));
        }
        return specs;
    }

    private List<QueueConfig> buildQueues(List<DispatchPool> poolRows, List<Subscription> activeSubscriptions,
                                          List<String> clientIdentifiers) {
        Set<String> tenants = new LinkedHashSet<>();
        tenants.add(ClientIdentifier.RESERVED_PLATFORM);
        for (DispatchPool p : poolRows) {
            tenants.add(tenantOf(p.clientIdentifier()));
        }
        for (Subscription s : activeSubscriptions) {
            tenants.add(tenantOf(s.clientIdentifier()));
        }
        for (String identifier : clientIdentifiers) {
            tenants.add(tenantOf(identifier));
        }

        List<QueueConfig> queues = new ArrayList<>();
        for (String tenant : tenants) {
            try {
                // Both computed before either is added: a tenant whose identifier is too
                // long to compose ANY of its queue names is omitted entirely (below), never
                // half-published with just its DEFAULT queue.
                QueueConfig defaultQueue = queueFor(tenant, QueuePriority.DEFAULT);
                QueueConfig highPriorityQueue = queueFor(tenant, QueuePriority.HIGH_PRIORITY);
                queues.add(defaultQueue);
                queues.add(highPriorityQueue);
            } catch (DispatchQueueName.QueueNameTooLongException e) {
                LOG.atWarn().setMessage("dispatch queue name too long for SQS; omitting this client from "
                                + "the router-config document rather than failing the whole document")
                        .addKeyValue("category", "CONFIGURATION")
                        .addKeyValue("client", tenant)
                        .addKeyValue("reason", e.getMessage())
                        .log();
            }
        }
        return queues;
    }

    private QueueConfig queueFor(String tenant, QueuePriority priority) {
        DispatchQueueName name = DispatchQueueName.compose(settings.prefix(), tenant, priority, settings.sqs());
        return new QueueConfig(settings.queueUriFor(name), name.value(), 0, 0);
    }

    private static String tenantOf(String clientIdentifier) {
        return (clientIdentifier == null || clientIdentifier.isBlank())
                ? ClientIdentifier.RESERVED_PLATFORM
                : clientIdentifier;
    }
}
