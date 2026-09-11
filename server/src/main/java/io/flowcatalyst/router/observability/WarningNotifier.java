package io.flowcatalyst.router.observability;

import io.flowcatalyst.platform.shared.json.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/// Delivers warnings to Microsoft Teams as Adaptive Cards — the Java reading
/// of the Rust `fc-router`'s `TeamsWebhookNotificationService` +
/// `BatchingNotificationService` (`crates/fc-router/src/notification.rs`),
/// so a card posted by either binary looks the same in the channel
/// (`docs/spec/router-env.md` §4).
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
///
/// ### Batching (Rust's `BatchingNotificationService`)
///
/// `CRITICAL` bypasses batching entirely and is posted at once, its own
/// card, matching Rust's `notify_critical_error`. Everything else at or
/// above the configured floor accumulates — as a count plus the first
/// message per (severity, category), never an unbounded list of notices —
/// and is flushed as one summary card per tick of [#interval], or
/// immediately per notice when `interval` is zero (no batching).
public final class WarningNotifier implements Warnings, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WarningNotifier.class);

    /// Rust's `NotificationConfig::default().batch_interval_seconds`.
    public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(300);
    private static final Duration POST_TIMEOUT = Duration.ofSeconds(10);

    /// The synthetic identity Rust's `send_batch` gives the summary card
    /// (`category: Processing, source: "BatchingNotificationService"`) — the
    /// one place category and "source" (the card's `Source:` fact) legitimately
    /// differ, since every other card here has no separate source and uses its
    /// category for both facts (see [#raise]).
    private static final String SUMMARY_CATEGORY = "Processing";
    private static final String SUMMARY_SOURCE = "BatchingNotificationService";

    private static final DateTimeFormatter HEADER_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CARD_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final URI webhook;
    private final HttpClient client;
    /// Zero means "no batching": every accepted notice is posted at once as
    /// its own card, matching Rust's `batch_interval_seconds == 0`.
    private final Duration interval;
    private final Severity minSeverity;
    private final Clock clock;
    private final Object lock = new Object();
    /// Bounded aggregation: one entry per (severity, category) pair seen
    /// since the last flush, never a growing list of every notice — the Rust
    /// summary only ever needed a count and an example message per group.
    private Map<AggKey, Aggregate> pending = new LinkedHashMap<>();
    private Instant batchStart;
    private volatile Thread loop;
    private volatile boolean stopped;

    private record AggKey(Severity severity, String category) {
    }

    private static final class Aggregate {
        final String firstMessage;
        int count;

        Aggregate(String firstMessage) {
            this.firstMessage = firstMessage;
            this.count = 1;
        }
    }

    public WarningNotifier(URI webhook, HttpClient client, Duration interval, Severity minSeverity, Clock clock) {
        this.webhook = webhook;
        this.client = client;
        this.interval = interval;
        this.minSeverity = minSeverity;
        this.clock = clock;
        this.batchStart = clock.instant();
    }

    /// A notifier for `url`, or [Warnings#NO_OP] when it is absent or Teams
    /// notifications are explicitly turned off.
    ///
    /// An unconfigured webhook is the normal case for a developer machine, so
    /// it must be silent rather than a startup failure — but a *malformed* one
    /// is a typo someone wants to hear about, and is also degraded to no-op
    /// rather than taken as a reason to refuse to start.
    ///
    /// `teamsEnabledRaw` is the raw `NOTIFICATION_TEAMS_ENABLED` value
    /// (`""` when unset). Rust: a non-empty webhook alone means notify, and
    /// this flag can only ever *widen* that — Rust's own `teams_enabled`
    /// computation ORs an explicit `true` in but has no way to turn a
    /// configured URL back off. Java deliberately deviates: an explicit
    /// `false` here always disables, even with a URL configured — an
    /// operator who sets the flag to `false` expects silence, not a webhook
    /// firing anyway because Rust's flag was one-directional.
    public static Warnings create(String url, String teamsEnabledRaw, Severity minSeverity,
                                  Duration batchInterval, Clock clock) {
        if (url == null || url.isBlank()) {
            return Warnings.NO_OP;
        }
        if (isExplicitlyFalse(teamsEnabledRaw)) {
            log.info("NOTIFICATION_TEAMS_ENABLED=false; Teams notifications disabled despite a configured webhook");
            return Warnings.NO_OP;
        }
        try {
            return new WarningNotifier(new URI(url), HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5)).build(),
                    batchInterval, minSeverity, clock);
        } catch (java.net.URISyntaxException e) {
            log.atError().setMessage("FC_NOTIFY_WEBHOOK_URL is not a valid URI; warnings will not be delivered")
                    .addKeyValue("url", url)
                    .setCause(e)
                    .log();
            return Warnings.NO_OP;
        }
    }

    private static boolean isExplicitlyFalse(String raw) {
        if (raw == null) {
            return false;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "0", "false", "no", "off" -> true;
            default -> false;
        };
    }

    /// Starts the periodic flush. A no-op when [#interval] is zero — nothing
    /// ever accumulates in that mode, so there is nothing to schedule.
    /// Idempotent.
    public void start() {
        if (interval.isZero()) {
            return;
        }
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
        if (severity == Severity.CRITICAL) {
            // Bypasses batching entirely, its own POST — Rust's
            // notify_critical_error path — and is never counted in the next
            // summary, because it never entered `pending`.
            post(buildCriticalCard(message, category));
            return;
        }
        if (interval.isZero()) {
            post(buildWarningCard(severity, category, category, message, clock.instant()));
            return;
        }
        synchronized (lock) {
            pending.merge(new AggKey(severity, category), new Aggregate(message),
                    (existing, fresh) -> {
                        existing.count++;
                        return existing;
                    });
        }
    }

    /// Sends the accumulated batch as one summary card, if anything
    /// accumulated. Safe to call from anywhere, including tests.
    public void flush() {
        Map<AggKey, Aggregate> batch;
        Instant start;
        Instant end;
        synchronized (lock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = pending;
            pending = new LinkedHashMap<>();
            start = batchStart;
            end = clock.instant();
            batchStart = end;
        }
        var highest = batch.keySet().stream()
                .map(AggKey::severity)
                .max(Comparator.naturalOrder())
                .orElse(Severity.INFO);
        var summary = summaryText(batch, start, end);
        post(buildWarningCard(highest, SUMMARY_CATEGORY, SUMMARY_SOURCE, summary, end));
    }

    /// Rust's `send_batch` summary text: a header naming the window, then one
    /// section per severity (Critical, Error, Warn, Info order) that actually
    /// has entries, each category either `  - CAT: message` (a single
    /// occurrence) or `  - CAT: N occurrences` + `    Example: message`
    /// (more than one), and a trailing total.
    private static String summaryText(Map<AggKey, Aggregate> batch, Instant start, Instant end) {
        var sb = new StringBuilder();
        sb.append("FlowCatalyst Warning Summary (")
                .append(HEADER_TIME.format(start))
                .append(" to ")
                .append(HEADER_TIME.format(end))
                .append(")\n\n");

        int total = 0;
        for (var severity : List.of(Severity.CRITICAL, Severity.ERROR, Severity.WARNING, Severity.INFO)) {
            var forSeverity = batch.entrySet().stream()
                    .filter(e -> e.getKey().severity() == severity)
                    .toList();
            if (forSeverity.isEmpty()) {
                continue;
            }
            int sectionCount = forSeverity.stream().mapToInt(e -> e.getValue().count).sum();
            total += sectionCount;
            sb.append(displayName(severity)).append(" Issues (").append(sectionCount).append("):\n");
            for (var entry : forSeverity) {
                var category = entry.getKey().category();
                var agg = entry.getValue();
                if (agg.count == 1) {
                    sb.append("  - ").append(category).append(": ").append(agg.firstMessage).append('\n');
                } else {
                    sb.append("  - ").append(category).append(": ").append(agg.count).append(" occurrences\n");
                    sb.append("    Example: ").append(agg.firstMessage).append('\n');
                }
            }
            sb.append('\n');
        }
        sb.append("Total Warnings: ").append(total).append('\n');
        return sb.toString();
    }

    /// Rust's `WarningSeverity` `Debug` spelling — what the card prints,
    /// independent of Java's own enum names (which spell it `WARNING`).
    private static String displayName(Severity severity) {
        return switch (severity) {
            case INFO -> "Info";
            case WARNING -> "Warn";
            case ERROR -> "Error";
            case CRITICAL -> "Critical";
        };
    }

    private static String severityColor(Severity severity) {
        return switch (severity) {
            case CRITICAL, ERROR -> "Attention";
            case WARNING -> "Warning";
            case INFO -> "Accent";
        };
    }

    /// Rust's `build_warning_card`. `source` is the card's `Source:` fact;
    /// for a plain (non-batch) notice this is the same string as `category`
    /// — Java's [#raise] carries no separate source field — and for the
    /// batch summary it is the literal [#SUMMARY_SOURCE].
    private static Map<String, Object> buildWarningCard(Severity severity, String category, String source,
                                                         String message, Instant timestamp) {
        var color = severityColor(severity);

        var emojiColumn = obj("type", "Column", "width", "auto", "items",
                List.of(obj("type", "TextBlock", "text", "⚠️", "size", "Large")));
        var titleBlock = obj("type", "TextBlock", "text", "FlowCatalyst Alert", "weight", "Bolder", "size", "Large");
        var subtitleBlock = obj("type", "TextBlock",
                "text", displayName(severity) + " - " + category,
                "color", color, "weight", "Bolder", "size", "Medium", "spacing", "None");
        var textColumn = obj("type", "Column", "width", "stretch", "items", List.of(titleBlock, subtitleBlock));
        var columnSet = obj("type", "ColumnSet", "columns", List.of(emojiColumn, textColumn));
        var header = obj("type", "Container", "style", "emphasis", "items", List.of(columnSet));

        var facts = obj("type", "FactSet", "facts", List.of(
                obj("title", "Category:", "value", category),
                obj("title", "Source:", "value", source),
                obj("title", "Time:", "value", CARD_TIME.format(timestamp))));
        var messageLabel = obj("type", "TextBlock", "text", "Message", "weight", "Bolder", "separator", true);
        var messageBlock = obj("type", "TextBlock", "text", message, "wrap", true, "spacing", "Small");

        return cardPayload(List.of(header, facts, messageLabel, messageBlock));
    }

    /// Rust's `build_critical_error_card`: unlike [#buildWarningCard] there is
    /// no `Category:` or `Time:` fact — only `Source:`.
    private static Map<String, Object> buildCriticalCard(String message, String source) {
        var titleBlock = obj("type", "TextBlock", "text", "🚨 CRITICAL ERROR",
                "weight", "Bolder", "size", "ExtraLarge", "color", "Attention");
        var header = obj("type", "Container", "style", "attention", "items", List.of(titleBlock));

        var facts = obj("type", "FactSet", "facts", List.of(obj("title", "Source:", "value", source)));
        var messageBlock = obj("type", "TextBlock", "text", message, "wrap", true, "spacing", "Medium");
        var actionBlock = obj("type", "TextBlock", "text", "⚡ Immediate action required",
                "weight", "Bolder", "color", "Attention", "separator", true);

        return cardPayload(List.of(header, facts, messageBlock, actionBlock));
    }

    private static Map<String, Object> cardPayload(List<?> body) {
        var content = obj("type", "AdaptiveCard", "version", "1.4", "body", body);
        var attachment = obj("contentType", "application/vnd.microsoft.card.adaptive", "content", content);
        return obj("attachments", List.of(attachment));
    }

    private static Map<String, Object> obj(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void post(Map<String, Object> card) {
        try {
            var body = Json.MAPPER.writeValueAsBytes(card);
            var response = client.send(HttpRequest.newBuilder(webhook)
                            .timeout(POST_TIMEOUT)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                log.atWarn().setMessage("Teams webhook answered; card not delivered")
                        .addKeyValue("status", response.statusCode())
                        .log();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Deliberately broad. The card is dropped rather than retried: a
            // queue of undelivered notices that outlives the incident is
            // worse than silence, because it arrives as history claiming to
            // be news.
            log.atWarn().setMessage("could not deliver Teams card")
                    .setCause(e)
                    .log();
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
