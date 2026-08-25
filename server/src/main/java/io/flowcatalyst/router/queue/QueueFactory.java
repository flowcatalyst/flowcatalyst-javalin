package io.flowcatalyst.router.queue;

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

    public QueueFactory(DataSource dataSource) {
        this.dataSource = dataSource;
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

    private Optional<Consumer> createPostgres(QueueConfig config) {
        if (dataSource == null) {
            log.error("queue {} needs postgres but no database is configured", config.queueName());
            return Optional.empty();
        }
        return Optional.of(new PostgresQueue(dataSource, config.queueName(),
                Duration.ofSeconds(config.visibilityTimeout())));
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
