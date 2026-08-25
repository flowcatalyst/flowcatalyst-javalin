package io.flowcatalyst.router.observability;

import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Delivers warnings to an external channel — Teams, Slack, anything that
/// accepts a JSON POST (`FC_NOTIFY_WEBHOOK_URL`).
///
/// Without this the router's warnings are complete and go nowhere. A broken
/// mediation target ACK-drops every message routed to it, raises an ERROR
/// notice, and that notice sits in a store behind a dashboard nobody is
/// watching. The store answers "what happened?" once you already suspect
/// something; this answers "something is wrong" to someone who does not.
///
/// **Nothing here may fail a delivery.** Every failure path logs and returns:
/// a webhook being down is not a reason to stop routing messages, and an
/// operator notice that could take the router with it would be worse than no
/// notice at all.
public final class WarningNotifier implements Warnings, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WarningNotifier.class);

    public static final int DEFAULT_BATCH_SIZE = 20;
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(30);
    private static final Duration POST_TIMEOUT = Duration.ofSeconds(10);

    /// One warning as the webhook receives it.
    public record Notice(String category, Severity severity, String message, Instant raisedAt) {
    }

    private final URI webhook;
    private final HttpClient client;
    private final int batchSize;
    private final Duration interval;
    private final java.time.Clock clock;
    private final Object lock = new Object();
    private final List<Notice> pending = new ArrayList<>();
    private volatile Severity minSeverity;
    private volatile Thread loop;
    private volatile boolean stopped;

    public WarningNotifier(URI webhook, HttpClient client, int batchSize, Duration interval,
                           Severity minSeverity, java.time.Clock clock) {
        this.webhook = webhook;
        this.client = client;
        this.batchSize = Math.max(1, batchSize);
        this.interval = interval;
        this.minSeverity = minSeverity;
        this.clock = clock;
    }

    /// A notifier for `url`, or [Warnings#NO_OP] when it is absent.
    ///
    /// An unconfigured webhook is the normal case for a developer machine, so
    /// it must be silent rather than a startup failure — but a *malformed* one
    /// is a typo someone wants to hear about, and is also degraded to no-op
    /// rather than taken as a reason to refuse to start.
    public static Warnings create(String url, Severity minSeverity, java.time.Clock clock) {
        if (url == null || url.isBlank()) {
            return Warnings.NO_OP;
        }
        try {
            return new WarningNotifier(new URI(url), HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build(),
                    DEFAULT_BATCH_SIZE, DEFAULT_INTERVAL, minSeverity, clock);
        } catch (java.net.URISyntaxException e) {
            log.error("FC_NOTIFY_WEBHOOK_URL is not a valid URI; warnings will not be delivered: {}", url, e);
            return Warnings.NO_OP;
        }
    }

    /// Starts the periodic flush. Idempotent.
    public void start() {
        synchronized (lock) {
            if (loop == null && !stopped) {
                loop = Thread.ofVirtual().name("warning-notifier").start(this::run);
            }
        }
    }

    @Override
    public void raise(Severity severity, String category, String message) {
        if (stopped || severity.compareTo(minSeverity) < 0) {
            return;
        }
        boolean flushNow;
        synchronized (lock) {
            pending.add(new Notice(category, severity, message, clock.instant()));
            // CRITICAL goes at once. Everything else can wait for the tick:
            // batching is what stops a flapping target turning one incident
            // into a thousand messages in a channel, which is how a channel
            // becomes something people mute.
            flushNow = pending.size() >= batchSize || severity == Severity.CRITICAL;
        }
        if (flushNow) {
            flush();
        }
    }

    /// Sends whatever is pending. Safe to call from anywhere, including tests.
    public void flush() {
        List<Notice> batch;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = List.copyOf(pending);
            pending.clear();
        }
        post(batch);
    }

    private void post(List<Notice> batch) {
        try {
            var body = Json.MAPPER.writeValueAsBytes(Map.of("warnings", batch));
            var response = client.send(HttpRequest.newBuilder(webhook)
                            .timeout(POST_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                log.warn("warning webhook answered {}; {} notice(s) not delivered",
                        response.statusCode(), batch.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Deliberately broad. The batch is dropped rather than retried:
            // a queue of undelivered notices that outlives the incident is
            // worse than silence, because it arrives as history claiming to
            // be news.
            log.warn("could not deliver {} warning notice(s)", batch.size(), e);
        }
    }

    private void run() {
        while (!stopped) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            flush();
        }
    }

    /// Stops the loop and delivers what is pending — the last thing an
    /// operator hears about a shutting-down instance is the most likely to
    /// explain why it is shutting down.
    @Override
    public void close() {
        stopped = true;
        Thread running;
        synchronized (lock) {
            running = loop;
            loop = null;
        }
        if (running != null) {
            running.interrupt();
        }
        flush();
    }
}
