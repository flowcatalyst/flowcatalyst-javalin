package io.flowcatalyst.router.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/// One queue the router should consume from (`docs/spec/router.md` §2.5).
///
/// **Contract [C].** Two legacy aliases are accepted on input — `name` for
/// [#queueName] and `uri` for [#queueUri] — because deployed config documents
/// use them. Canonical keys are always what gets written back, so a
/// round-trip normalises rather than preserving whichever spelling arrived.
///
/// @param queueUri          selects the backend by scheme; required
/// @param queueName         consumer identity, and the Postgres `queue_name`.
///                          Defaults to [#queueUri]
/// @param connections       read by **no backend**. It exists only so that
///                          changing it restarts the consumer — see
///                          [#sameConsumerTopology]
/// @param visibilityTimeout seconds a claimed message stays invisible; 120
///                          when absent. Ignored by NATS, which takes
///                          ack-wait from the URI instead
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueueConfig(
        String queueUri,
        String queueName,
        int connections,
        int visibilityTimeout) {

    public static final int DEFAULT_CONNECTIONS = 1;
    public static final int DEFAULT_VISIBILITY_TIMEOUT = 120;

    /// Applies the defaults, so nothing downstream has to reason about a
    /// missing value. A blank name becomes the URI, which is what makes a
    /// minimal `{"queueUri": …}` entry usable on its own.
    public QueueConfig {
        if (queueUri == null || queueUri.isBlank()) {
            throw new IllegalArgumentException("queueUri is required");
        }
        queueName = queueName == null || queueName.isBlank() ? queueUri : queueName;
        connections = connections <= 0 ? DEFAULT_CONNECTIONS : connections;
        visibilityTimeout = visibilityTimeout <= 0 ? DEFAULT_VISIBILITY_TIMEOUT : visibilityTimeout;
    }

    public static QueueConfig of(String queueUri) {
        return new QueueConfig(queueUri, null, 0, 0);
    }

    /// Reads either spelling, **canonical winning** when both are present.
    ///
    /// Written by hand rather than with `@JsonAlias`, which treats an alias
    /// as another name for the same property and lets whichever key appears
    /// *last in the document* win. That makes precedence depend on field
    /// order, which is not a contract anyone should have to know — and the
    /// spec is explicit that canonical wins.
    @JsonCreator
    static QueueConfig fromJson(
            @JsonProperty("queueUri") String queueUri,
            @JsonProperty("uri") String legacyUri,
            @JsonProperty("queueName") String queueName,
            @JsonProperty("name") String legacyName,
            @JsonProperty("connections") Integer connections,
            @JsonProperty("visibilityTimeout") Integer visibilityTimeout) {
        return new QueueConfig(
                firstNonBlank(queueUri, legacyUri),
                firstNonBlank(queueName, legacyName),
                connections == null ? 0 : connections,
                visibilityTimeout == null ? 0 : visibilityTimeout);
    }

    private static String firstNonBlank(String canonical, String legacy) {
        return canonical != null && !canonical.isBlank() ? canonical : legacy;
    }

    /// Whether two configs describe the same consumer, such that the running
    /// one can be left alone.
    ///
    /// Every field counts, `connections` included — nothing reads it, but a
    /// change to it is still taken as an instruction to rebuild the consumer,
    /// which is the only effect it has ever had.
    @JsonIgnore
    public boolean sameConsumerTopology(QueueConfig other) {
        return other != null && equals(other);
    }
}
