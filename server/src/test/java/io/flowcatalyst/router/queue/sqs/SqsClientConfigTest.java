package io.flowcatalyst.router.queue.sqs;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqsClientConfigTest {

    /// An SQS call on a stalled connection must be cut off by the call timeout,
    /// not block its caller for good. Without a bound an acknowledgement held a
    /// pool worker and its concurrency slot indefinitely.
    @Test
    void aCallOnAStalledConnectionFailsAfterTheCallTimeout() throws Exception {
        var stall = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                stall.await(); // never answers
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/000000000000/q";
            try (var client = SqsQueue.clientBuilder(url, Duration.ofMillis(800), Duration.ofMillis(400))
                    .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .region(Region.US_EAST_1)
                    .credentialsProvider(AnonymousCredentialsProvider.create())
                    .build()) {
                long start = System.nanoTime();
                assertThatThrownBy(() -> client.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(url).receiptHandle("r").build()))
                        .isInstanceOf(SdkClientException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
            }
        } finally {
            stall.countDown();
            server.stop(0);
        }
    }

    /// One queue's client carries the long poll plus a DeleteMessage from every
    /// concurrent worker of its pool, so the connection pool must be larger than
    /// the SDK's 50 and above a large pool's concurrency; the attempt timeout must
    /// exceed the long-poll wait.
    @Test
    void theClientIsSizedForABusyQueue() {
        assertThat(SqsQueue.MAX_HTTP_CONNECTIONS).isGreaterThan(64);
        assertThat(SqsQueue.API_CALL_ATTEMPT_TIMEOUT.toSeconds()).isGreaterThan(SqsQueue.WAIT_TIME_SECONDS);
        assertThat(SqsQueue.API_CALL_TIMEOUT).isGreaterThanOrEqualTo(SqsQueue.API_CALL_ATTEMPT_TIMEOUT);
    }
}
