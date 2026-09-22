package io.flowcatalyst.router.queue.nats;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/// The `nats://` connection URI, parsed (`docs/spec/router.md` §7.4):
///
///     nats://host:port[,host2…]?stream=&consumer=&subject=&max-messages=&
///            poll-timeout-ms=&ack-wait-secs=&max-deliver=&max-ack-pending=&
///            storage=file|memory&replicas=&max-age-days=
///
/// Every field not present on the URI falls back to the documented default.
/// A present-but-empty value (`?stream=`) is treated the same as absent — it
/// matches Go's `if v := q.Get(x); v != ""` guard, which never overwrites the
/// default with an explicit empty string. A present-but-unparseable numeric
/// value is likewise ignored rather than rejected, matching Go's
/// `if n, err := strconv.Atoi(v); err == nil`.
///
/// Deliberately hand-rolled string splitting rather than [java.net.URI]:
/// `java.net.URI`'s authority parser rejects a comma inside the host
/// component, which the multi-host form above relies on.
///
/// @param servers            one `nats://host:port` entry per configured host
/// @param pollTimeout        **parsed but unused** (owner ruling 2026-09-07,
///                            `docs/spec/router.md` §7.4): `NatsQueue` is a
///                            genuine listener, not a poller — `poll()`
///                            blocks untimed on its own already-buffered
///                            messages rather than issuing a timed pull
///                            request, so there is no wait for this to
///                            bound. Kept parsed (never removed as a
///                            parameter — an existing URI must keep working)
///                            so `poll-timeout-ms=` on a URI is accepted and
///                            silently ignored rather than rejected.
/// @param maxAge             stream retention; [Duration#ZERO] means unlimited
record NatsQueueUri(
        List<String> servers,
        String streamName,
        String consumerName,
        String subject,
        int maxMessagesPerPoll,
        Duration pollTimeout,
        Duration ackWait,
        int maxDeliver,
        int maxAckPending,
        String storage,
        int replicas,
        Duration maxAge) {

    static final String DEFAULT_STREAM = "FLOWCATALYST";
    static final String DEFAULT_CONSUMER = "fc-router";
    static final String DEFAULT_SUBJECT = "flowcatalyst.>";
    static final int DEFAULT_MAX_MESSAGES = 10;
    static final Duration DEFAULT_POLL_TIMEOUT = Duration.ofSeconds(20);
    static final Duration DEFAULT_ACK_WAIT = Duration.ofSeconds(120);
    /// Unlimited by default (owner ruling 2026-09-22, hand-off §4): the
    /// router owns give-up — it terminal-ACKs what must not be retried and
    /// releases the rest to the broker on its own schedule. A finite cap
    /// converts a slow backlog (a dedicated pool draining for hours) into
    /// silent, permanent loss of work never attempted once the cap is spent.
    /// Still settable per URI (`max-deliver=`).
    static final int DEFAULT_MAX_DELIVER = -1;
    /// Unlimited by default (owner ruling 2026-09-22, hand-off §4): a NAKed
    /// message stays outstanding on the server for its whole delay, so a
    /// finite cap would suspend delivery of the WHOLE stream — every other
    /// pool's messages included — the moment a deferred backlog exceeded it,
    /// recreating the head-of-line block this ruling removes. Still settable
    /// per URI (`max-ack-pending=`).
    static final int DEFAULT_MAX_ACK_PENDING = -1;
    static final String DEFAULT_STORAGE = "file";
    static final int DEFAULT_REPLICAS = 1;
    static final Duration DEFAULT_MAX_AGE = Duration.ofDays(7);

    NatsQueueUri {
        servers = List.copyOf(servers);
    }

    /// Parses `uri`, applying every default above. Pure and side-effect
    /// free — no DNS resolution, no connection attempt.
    ///
    /// @throws IllegalArgumentException the scheme isn't `nats` or the URI
    ///                                  has no host
    static NatsQueueUri parse(String uri) {
        Objects.requireNonNull(uri, "uri");
        int schemeSep = uri.indexOf("://");
        if (schemeSep < 0) {
            throw new IllegalArgumentException("nats: expected scheme nats://, got \"" + uri + "\"");
        }
        String scheme = uri.substring(0, schemeSep);
        if (!"nats".equals(scheme)) {
            throw new IllegalArgumentException("nats: expected scheme nats://, got \"" + scheme + "\"");
        }

        String rest = uri.substring(schemeSep + 3);
        int queryStart = rest.indexOf('?');
        String authority = queryStart < 0 ? rest : rest.substring(0, queryStart);
        String query = queryStart < 0 ? "" : rest.substring(queryStart + 1);

        List<String> servers = Arrays.stream(authority.split(","))
                .map(String::strip)
                .filter(host -> !host.isEmpty())
                .map(host -> "nats://" + host)
                .toList();
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("nats: missing host in \"" + uri + "\"");
        }

        Map<String, String> params = parseQuery(query);
        return new NatsQueueUri(
                servers,
                stringOr(params.get("stream"), DEFAULT_STREAM),
                stringOr(params.get("consumer"), DEFAULT_CONSUMER),
                stringOr(params.get("subject"), DEFAULT_SUBJECT),
                intOr(params.get("max-messages"), DEFAULT_MAX_MESSAGES),
                millisOr(params.get("poll-timeout-ms"), DEFAULT_POLL_TIMEOUT),
                secondsOr(params.get("ack-wait-secs"), DEFAULT_ACK_WAIT),
                intOr(params.get("max-deliver"), DEFAULT_MAX_DELIVER),
                intOr(params.get("max-ack-pending"), DEFAULT_MAX_ACK_PENDING),
                stringOr(params.get("storage"), DEFAULT_STORAGE),
                intOr(params.get("replicas"), DEFAULT_REPLICAS),
                maxAgeDaysOr(params.get("max-age-days"), DEFAULT_MAX_AGE));
    }

    /// The durable-consumer identity: `<stream>/<consumer>` — the historical
    /// format the Go queue used as `Identifier()`.
    String identifier() {
        return streamName + "/" + consumerName;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        if (query.isEmpty()) {
            return params;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq < 0 ? pair : pair.substring(0, eq);
            String rawValue = eq < 0 ? "" : pair.substring(eq + 1);
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            // First occurrence wins, matching Go's url.Values.Get.
            params.putIfAbsent(key, URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
        }
        return params;
    }

    private static String stringOr(String raw, String defaultValue) {
        return (raw == null || raw.isEmpty()) ? defaultValue : raw;
    }

    private static int intOr(String raw, int defaultValue) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Duration millisOr(String raw, Duration defaultValue) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Duration.ofMillis(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Duration secondsOr(String raw, Duration defaultValue) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /// `n > 0` → `n` days; `n <= 0` → [Duration#ZERO] (unlimited) — matches
    /// Go's `if n > 0 { … } else { cfg.MaxAge = 0 }` branch exactly.
    private static Duration maxAgeDaysOr(String raw, Duration defaultValue) {
        if (raw == null || raw.isEmpty()) {
            return defaultValue;
        }
        try {
            long days = Long.parseLong(raw);
            return days > 0 ? Duration.ofDays(days) : Duration.ZERO;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
