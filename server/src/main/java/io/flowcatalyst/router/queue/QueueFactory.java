package io.flowcatalyst.router.queue;

import io.flowcatalyst.platform.shared.database.Database;
import io.flowcatalyst.platform.shared.database.GatedDataSource;
import io.flowcatalyst.router.config.QueueConfig;
import io.flowcatalyst.router.manager.RouterManager;
import io.flowcatalyst.router.queue.nats.NatsQueue;
import io.flowcatalyst.router.queue.postgres.PostgresQueue;
import io.flowcatalyst.router.queue.sqs.SqsQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;

/// Picks a [Consumer] backend for a queue by the scheme of its URI
/// (`docs/spec/router.md` §7.1).
///
/// A [RouterManager.ConsumerFactory]: [#create] never throws. One queue whose
/// backend cannot be built — an unknown scheme, an unreachable broker, a
/// malformed URI — must not stop the others from starting, so every failure
/// is reported as [Optional#empty()] and left for the reconfigure to surface.
public final class QueueFactory implements RouterManager.ConsumerFactory {

    private static final Logger log = LoggerFactory.getLogger(QueueFactory.class);

    /// The registered backend keys (`docs/spec/router.md` §7.1 "Registered
    /// schemes in fc-server").
    private static final String SQS = "sqs";
    private static final String POSTGRES = "postgres";
    private static final String NATS = "nats";

    /// Shared with every Postgres-backed queue this factory builds. May be
    /// `null` when the deployment has no database — a queue that needs one
    /// then fails to build rather than dereferencing it.
    private final DataSource dataSource;

    /// [#dataSource]'s own connection string, normalised the same way
    /// [io.flowcatalyst.server.Router#defaultQueueUri] builds the synthesised
    /// default-broker queue's URI — `null` when [#dataSource] is `null`.
    /// Used only to recognise "this queue's URI names the platform's own
    /// database" so [#createPostgres] reuses [#dataSource] instead of opening
    /// a redundant second pool to the identical database (see its doc).
    private final String sharedDatabaseUrl;

    public QueueFactory(DataSource dataSource) {
        this(dataSource, null);
    }

    /// @param sharedDatabaseUrl [#dataSource]'s connection string (`postgres://…`),
    ///                          or `null` when `dataSource` is `null` or its
    ///                          exact connection string is not known — either
    ///                          way, [#createPostgres] then never treats a
    ///                          queue URI as "the same database" and always
    ///                          opens its own pool for a URI that carries a
    ///                          connection.
    public QueueFactory(DataSource dataSource, String sharedDatabaseUrl) {
        this.dataSource = dataSource;
        this.sharedDatabaseUrl = dataSource == null ? null : sharedDatabaseUrl;
    }

    @Override
    public Optional<Consumer> create(QueueConfig config) {
        var scheme = resolveScheme(config.queueUri());
        try {
            return switch (scheme) {
                case SQS -> Optional.of(SqsQueue.create(
                        config.queueUri(), config.queueName(), config.visibilityTimeout()));
                case POSTGRES -> createPostgres(config);
                case NATS -> Optional.of(new NatsQueue(config.queueUri()));
                default -> {
                    log.error("queue {} uses a scheme with no registered consumer: \"{}\" ({})",
                            config.queueName(), scheme, config.queueUri());
                    yield Optional.empty();
                }
            };
        } catch (RuntimeException e) {
            // Client construction, stream/consumer provisioning (NATS) or a
            // malformed URI can all throw here — none of it may propagate,
            // per the ConsumerFactory contract.
            log.error("could not build consumer for queue {}", config.queueName(), e);
            return Optional.empty();
        }
    }

    /// Builds a Postgres consumer, connecting the same way Go's Postgres
    /// queue backend does (`../flowcatalyst-go/internal/queue/postgres/postgres.go:53-61`,
    /// `pgxpool.New(ctx, cfg.URI)`): the queue URI **is** the connection
    /// string when it carries one (`docs/spec/router.md` §7.3). A router
    /// consuming from `FLOWCATALYST_CONFIG_URL`-supplied `postgres://…`
    /// queues therefore never needs the platform's own database pool — each
    /// queue opens its own, sized off [QueueConfig#connections].
    ///
    /// The one case that still prefers [#dataSource]: a queue URI that names
    /// the identical database [#dataSource] already connects to — chiefly
    /// [io.flowcatalyst.server.Router#configSource]'s synthesised
    /// default-broker queue, whose URI is the platform's own database URL
    /// rewritten to `postgres://…`. Opening a second pool to that same
    /// database would be pure waste, so that case reuses [#dataSource].
    ///
    /// A queue URI with no connection info at all (`URI#getHost` is `null` —
    /// a bare scheme sentinel, or a URI that does not even parse) has
    /// nothing of its own to connect with and falls back to [#dataSource],
    /// matching the pre-existing behaviour when a database was configured.
    private Optional<Consumer> createPostgres(QueueConfig config) {
        var ownConnection = connectionUrl(config.queueUri());
        if (ownConnection.isEmpty() || sameConnection(ownConnection.get(), sharedDatabaseUrl)) {
            if (dataSource == null) {
                log.error("queue {} needs postgres but no database is configured", config.queueName());
                return Optional.empty();
            }
            return Optional.of(new PostgresQueue(dataSource, config.queueName(),
                    Duration.ofSeconds(config.visibilityTimeout())));
        }

        // `PostgresQueue#poll` holds at most one connection at a time, and it
        // is the only poll loop this consumer ever runs (RouterServer starts
        // exactly one poll thread per consumer); `#ack`/`#nack`/`#metrics`
        // each borrow-and-return their own. `connections` is the config's
        // knob for exactly this (`QueueConfig` §2.5) — sizing to
        // `connections + 1` gives the poll loop headroom to run alongside one
        // concurrent ack/nack/metrics call without contending for the same
        // connection; a bigger burst just waits its turn, the same as any
        // pool.
        GatedDataSource ownPool = Database.newPool(ownConnection.get(), config.connections() + 1);
        return Optional.of(new PostgresQueue(ownPool, config.queueName(),
                Duration.ofSeconds(config.visibilityTimeout()), ownPool));
    }

    /// `queueUri` when it carries its own connection (has a host per
    /// `URI#getHost`); empty when it is a bare scheme/sentinel with nothing
    /// to connect to, or does not parse as a URI at all.
    private static Optional<String> connectionUrl(String queueUri) {
        try {
            return new URI(queueUri).getHost() != null ? Optional.of(queueUri) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /// Whether `queueUri` and `sharedDatabaseUrl` name the same database,
    /// compared after [Database#toJdbc] normalises scheme/host/port/path —
    /// credentials and query parameters deliberately excluded, since the
    /// platform pool may have resolved its credentials from AWS Secrets
    /// Manager while the queue config still carries the nominal ones.
    /// `false` whenever `sharedDatabaseUrl` is `null` (no shared pool, or its
    /// connection string is not known) or either URL fails to parse.
    private static boolean sameConnection(String queueUri, String sharedDatabaseUrl) {
        if (sharedDatabaseUrl == null) {
            return false;
        }
        try {
            return Database.toJdbc(queueUri).url().equals(Database.toJdbc(sharedDatabaseUrl).url());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /// The backend key a queue URI resolves to (`docs/spec/router.md` §7.1
    /// "Scheme resolution").
    ///
    /// The key is the text before `://` — the whole string when there is
    /// none — with one exception: an `http`/`https` URL whose host starts
    /// with `sqs.` or `sqs-fips.` **and** contains `.amazonaws.` is a real
    /// SQS REST endpoint and resolves to `sqs` regardless of its literal
    /// scheme text. Anything else keeps its literal key, matched or not.
    static String resolveScheme(String uri) {
        int separator = uri.indexOf("://");
        String key = separator < 0 ? uri : uri.substring(0, separator);
        if (("http".equals(key) || "https".equals(key)) && looksLikeSqsEndpoint(uri)) {
            return SQS;
        }
        return key;
    }

    private static boolean looksLikeSqsEndpoint(String uri) {
        String host;
        try {
            host = new URI(uri).getHost();
        } catch (URISyntaxException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        return (host.startsWith("sqs.") || host.startsWith("sqs-fips.")) && host.contains(".amazonaws.");
    }
}
