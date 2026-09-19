package io.flowcatalyst.platform.scheduledjob;

import tools.jackson.databind.JsonNode;
import io.flowcatalyst.platform.scheduledjob.cron.Cron;
import io.flowcatalyst.platform.scheduledjob.cron.CronExpression;
import io.flowcatalyst.platform.shared.tsid.EntityType;
import io.flowcatalyst.sdk.usecase.HasId;
import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/// The scheduled-job aggregate root (spec: `docs/spec/scheduledjob.md`): a
/// cron-driven firing definition, unique by `code` within its client scope
/// (`clientId`, or the platform scope when `null`).
///
/// Immutable record: each transition returns a copy (and bumps `version`)
/// and throws [UseCaseException] when an invariant is violated, so an
/// operation is just load → transition → event. The repository persists
/// whatever copy it is handed and stamps `updatedAt` itself. Firings are
/// [ScheduledJobInstance] rows — a projection the aggregate mints
/// ([#fireNow]) but never owns.
///
/// `crons` are kept as the stored text: every write goes through
/// [CronExpression#parse] (the factories take the parsed type), but a legacy
/// row written before the six-field ruling must still load, so reads are
/// lenient and [#latestSlotInWindow] skips what does not parse (spec §3.2).
///
/// @param id                  `sjb_…` TSID
/// @param clientId            owning client; `null` = platform-scoped (immutable)
/// @param applicationId       owning application, optional
/// @param code                normalised code (see [ScheduledJobCode])
/// @param name                human-readable name, trimmed
/// @param description         optional
/// @param status              `ACTIVE` | `PAUSED` | `ARCHIVED`
/// @param crons               ≥ 1 six-field expressions, stored text
/// @param timezone            IANA zone name the crons are evaluated in; unvalidated (spec §1)
/// @param payload             opaque JSON delivered with each firing; `null` = none
/// @param concurrent          consumer-owned flag, not enforced by the platform
/// @param tracksCompletion    whether `DELIVERED` waits for a completion callback
/// @param timeoutSeconds      optional
/// @param deliveryMaxAttempts dispatcher retry budget, default [#DEFAULT_DELIVERY_MAX_ATTEMPTS]
/// @param targetUrl           optional firing target
/// @param lastFiredAt         the last cron *slot* fired, advanced by the poller; `null` = never
/// @param createdAt           creation time
/// @param updatedAt           last change
/// @param createdBy           creating principal, optional
/// @param updatedBy           last changing principal, optional
/// @param version             1 on create, +1 per transition
public record ScheduledJob(
        String id,
        String clientId,
        String applicationId,
        String code,
        String name,
        String description,
        ScheduledJobStatus status,
        List<String> crons,
        String timezone,
        JsonNode payload,
        boolean concurrent,
        boolean tracksCompletion,
        Integer timeoutSeconds,
        int deliveryMaxAttempts,
        String targetUrl,
        Instant lastFiredAt,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy,
        int version) implements HasId {

    public static final String DEFAULT_TIMEZONE = "UTC";
    public static final int DEFAULT_DELIVERY_MAX_ATTEMPTS = 3;

    public ScheduledJob {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        crons = crons == null ? List.of() : List.copyOf(crons);
        Objects.requireNonNull(timezone, "timezone");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    // ── Construction ───────────────────────────────────────────────────────

    /// Everything a job is defined by, apart from its identity and scope —
    /// the shape a create command and a sync entry share. Defaults are
    /// applied here (spec §1): a blank `timezone` is [#DEFAULT_TIMEZONE], an
    /// absent `deliveryMaxAttempts` is [#DEFAULT_DELIVERY_MAX_ATTEMPTS], a
    /// JSON `null` payload is no payload; `name` is trimmed.
    ///
    /// @param crons ≥ 1 parsed expressions (the validate phase guarantees it)
    public record Definition(String name, String description, List<CronExpression> crons, String timezone,
                             JsonNode payload, boolean concurrent, boolean tracksCompletion, Integer timeoutSeconds,
                             Integer deliveryMaxAttempts, String targetUrl) {
        public Definition {
            name = Objects.requireNonNull(name, "name").strip();
            crons = List.copyOf(Objects.requireNonNull(crons, "crons"));
            timezone = timezone == null || timezone.isBlank() ? DEFAULT_TIMEZONE : timezone;
            payload = presentPayload(payload);
            deliveryMaxAttempts = deliveryMaxAttempts == null ? DEFAULT_DELIVERY_MAX_ATTEMPTS : deliveryMaxAttempts;
        }

        public List<String> cronText() {
            return crons.stream().map(CronExpression::expression).toList();
        }
    }

    /// A fresh `ACTIVE`, version-1, platform-scoped job; scope and
    /// provenance are set with the `with…` copies before the first persist.
    public static ScheduledJob create(ScheduledJobCode code, Definition d) {
        Instant now = Instant.now();
        return new ScheduledJob(EntityType.SCHEDULED_JOB.generate(), null, null, code.value(), d.name(), d.description(),
                ScheduledJobStatus.ACTIVE, d.cronText(), d.timezone(), d.payload(), d.concurrent(), d.tracksCompletion(),
                d.timeoutSeconds(), d.deliveryMaxAttempts(), d.targetUrl(), null, now, now, null, null, 1);
    }

    public boolean isArchived() {
        return status == ScheduledJobStatus.ARCHIVED;
    }

    public boolean isPlatformScoped() {
        return clientId == null;
    }

    /// The zone the crons are evaluated in; an unknown name reads as UTC
    /// (spec §1, open question 2).
    public ZoneId zoneId() {
        try {
            return ZoneId.of(timezone);
        } catch (DateTimeException _) {
            return ZoneOffset.UTC;
        }
    }

    /// [Cron#latestSlotInWindow] over this job's crons and zone (spec §3.2).
    public Optional<Instant> latestSlotInWindow(Instant after, Instant upTo) {
        return Cron.latestSlotInWindow(crons, zoneId(), after, upTo);
    }

    // ── Transitions (spec §2) ──────────────────────────────────────────────

    /// A partial admin update (spec §4, `PUT`): `null` = untouched. A
    /// non-null `crons` is non-empty (the validate phase guarantees it); a
    /// blank `timezone` means the default; a JSON `null` payload clears it.
    public record Changes(String name, String description, List<CronExpression> crons, String timezone,
                          JsonNode payload, Boolean concurrent, Boolean tracksCompletion, Integer timeoutSeconds,
                          Integer deliveryMaxAttempts, String targetUrl) {
        public Changes {
            name = name == null ? null : name.strip();
            crons = crons == null ? null : List.copyOf(crons);
        }
    }

    /// Applies the non-null [Changes]; `code`, `status` and scope never change.
    public ScheduledJob update(Changes c, String by) {
        return new ScheduledJob(id, clientId, applicationId, code,
                c.name() != null ? c.name() : name,
                c.description() != null ? c.description() : description,
                status,
                c.crons() != null ? c.crons().stream().map(CronExpression::expression).toList() : crons,
                c.timezone() != null ? (c.timezone().isBlank() ? DEFAULT_TIMEZONE : c.timezone()) : timezone,
                c.payload() != null ? presentPayload(c.payload()) : payload,
                c.concurrent() != null ? c.concurrent() : concurrent,
                c.tracksCompletion() != null ? c.tracksCompletion() : tracksCompletion,
                c.timeoutSeconds() != null ? c.timeoutSeconds() : timeoutSeconds,
                c.deliveryMaxAttempts() != null ? c.deliveryMaxAttempts() : deliveryMaxAttempts,
                c.targetUrl() != null ? c.targetUrl() : targetUrl,
                lastFiredAt, createdAt, Instant.now(), createdBy, by, version + 1);
    }

    /// → `PAUSED` (no precondition, spec §2).
    public ScheduledJob pause(String by) {
        return withStatus(ScheduledJobStatus.PAUSED, by);
    }

    /// → `ACTIVE` (no precondition, spec §2).
    public ScheduledJob resume(String by) {
        return withStatus(ScheduledJobStatus.ACTIVE, by);
    }

    /// → `ARCHIVED` (no precondition, spec §2).
    public ScheduledJob archive(String by) {
        return withStatus(ScheduledJobStatus.ARCHIVED, by);
    }

    /// Mints the `MANUAL`, `QUEUED` firing of this job (spec §6.1). The job
    /// row itself does not change. A `PAUSED` job is firable — a manual fire
    /// is the human override of the schedule.
    ///
    /// @throws UseCaseException conflict `ARCHIVED`
    public ScheduledJobInstance fireNow(String correlationId) {
        if (isArchived()) {
            throw UseCaseException.conflict("ARCHIVED", "Archived jobs cannot be fired");
        }
        return ScheduledJobInstance.manual(this, correlationId);
    }

    /// The declarative sync reconcile (spec §8): the copy with the
    /// definition applied, `ACTIVE` again, `applicationId` backfilled when
    /// given, `version + 1` — or empty when nothing differs, so a no-op
    /// re-sync is neither persisted nor reported.
    public Optional<ScheduledJob> reconcile(Definition d, String newApplicationId, String by) {
        String appId = newApplicationId != null ? newApplicationId : applicationId;
        boolean changed = !name.equals(d.name())
                || !Objects.equals(description, d.description())
                || !crons.equals(d.cronText())
                || !timezone.equals(d.timezone())
                || !Objects.equals(payload, d.payload())
                || concurrent != d.concurrent()
                || tracksCompletion != d.tracksCompletion()
                || !Objects.equals(timeoutSeconds, d.timeoutSeconds())
                || deliveryMaxAttempts != d.deliveryMaxAttempts()
                || !Objects.equals(targetUrl, d.targetUrl())
                || !Objects.equals(applicationId, appId)
                || status != ScheduledJobStatus.ACTIVE;
        if (!changed) {
            return Optional.empty();
        }
        return Optional.of(new ScheduledJob(id, clientId, appId, code, d.name(), d.description(), ScheduledJobStatus.ACTIVE,
                d.cronText(), d.timezone(), d.payload(), d.concurrent(), d.tracksCompletion(), d.timeoutSeconds(),
                d.deliveryMaxAttempts(), d.targetUrl(), lastFiredAt, createdAt, Instant.now(), createdBy, by, version + 1));
    }

    // ── Copies (construction-time) ─────────────────────────────────────────

    public ScheduledJob withClientId(String newClientId) {
        return new ScheduledJob(id, newClientId, applicationId, code, name, description, status, crons, timezone, payload,
                concurrent, tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, lastFiredAt, createdAt,
                updatedAt, createdBy, updatedBy, version);
    }

    public ScheduledJob withApplicationId(String newApplicationId) {
        return new ScheduledJob(id, clientId, newApplicationId, code, name, description, status, crons, timezone, payload,
                concurrent, tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, lastFiredAt, createdAt,
                updatedAt, createdBy, updatedBy, version);
    }

    public ScheduledJob withCreatedBy(String principalId) {
        return new ScheduledJob(id, clientId, applicationId, code, name, description, status, crons, timezone, payload,
                concurrent, tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, lastFiredAt, createdAt,
                updatedAt, principalId, updatedBy, version);
    }

    private ScheduledJob withStatus(ScheduledJobStatus newStatus, String by) {
        return new ScheduledJob(id, clientId, applicationId, code, name, description, newStatus, crons, timezone, payload,
                concurrent, tracksCompletion, timeoutSeconds, deliveryMaxAttempts, targetUrl, lastFiredAt, createdAt,
                Instant.now(), createdBy, by, version + 1);
    }

    /// A JSON `null` on the wire is "no payload" (spec §4).
    private static JsonNode presentPayload(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }
}
