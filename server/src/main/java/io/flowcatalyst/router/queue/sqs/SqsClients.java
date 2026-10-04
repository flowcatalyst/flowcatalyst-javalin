package io.flowcatalyst.router.queue.sqs;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.time.Duration;

/// The one place an SQS client is configured with bounded calls: an
/// attempt timeout, a whole-call timeout, and an explicit connection pool.
/// A bare `SqsClient.builder()` has no call timeout, so a stalled connection
/// blocks its caller for good — the router's consumers and the dispatch
/// scheduler's publisher both build through here so neither can be left
/// unbounded. Region, endpoint and credentials stay with the caller.
public final class SqsClients {

    private SqsClients() {
    }

    /// @param apiCallTimeout  longest a whole call may take, retries included
    /// @param attemptTimeout  longest one attempt may take
    /// @param maxConnections  connections the client's pool may hold
    public static SqsClientBuilder builder(Duration apiCallTimeout, Duration attemptTimeout, int maxConnections) {
        return SqsClient.builder()
                .httpClientBuilder(Apache5HttpClient.builder().maxConnections(maxConnections))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(apiCallTimeout)
                        .apiCallAttemptTimeout(attemptTimeout)
                        .build());
    }
}
