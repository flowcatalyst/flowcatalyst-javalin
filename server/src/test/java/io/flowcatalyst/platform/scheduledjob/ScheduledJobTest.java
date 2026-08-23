package io.flowcatalyst.platform.scheduledjob;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.flowcatalyst.platform.scheduledjob.cron.Cron;
import io.flowcatalyst.platform.scheduledjob.cron.CronExpression;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The aggregate's pure rules (spec §1–3, §6, §8): the code parser, the
/// cron grammar and evaluation tables, lenient enum reads, defaults, every
/// transition with its error code, and the sync reconcile — no database.
class ScheduledJobTest {

    /// Friday 2026-05-29, 10:00:30 UTC — the base of every "next occurrence" row.
    private static final Instant BASE = Instant.parse("2026-05-29T10:00:30Z");

    private static final List<CronExpression> HOURLY = List.of(CronExpression.parse("0 0 * * * *"));

    private static ScheduledJob.Definition definition(String name) {
        return new ScheduledJob.Definition(name, null, HOURLY, null, null, false, false, null, null, null);
    }

    private static ScheduledJob job() {
        return ScheduledJob.create(ScheduledJobCode.parse("nightly-refresh"), definition("Nightly"));
    }

    private static void assertUseCaseError(ThrowingCallable call, Class<? extends UseCaseError> kind, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(UseCaseException.class)
                .extracting(t -> ((UseCaseException) t).error())
                .satisfies(err -> {
                    assertThat(err).as("error kind").isInstanceOf(kind);
                    assertThat(err.code()).as("error code").isEqualTo(code);
                });
    }

    private static JsonNode json(String s) {
        try {
            return Json.MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ── Code ───────────────────────────────────────────────────────────────

    @Test
    void codeIsTrimmedAndLowerCased() {
        assertThat(ScheduledJobCode.parse("  SJCRT-Happy  ").value()).isEqualTo("sjcrt-happy");
        assertThat(ScheduledJobCode.parse("a1-b2").value()).isEqualTo("a1-b2");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void codeRejectsBlank(String code) {
        assertUseCaseError(() -> ScheduledJobCode.parse(code), UseCaseError.Validation.class, "CODE_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sj_underscore", "1sjcrt-bad", "-leading-hyphen", "has space", "dot.code", "colon:code"})
    void codeRejectsTheStrictHyphenOnlyPattern(String code) {
        assertUseCaseError(() -> ScheduledJobCode.parse(code), UseCaseError.Validation.class, "INVALID_CODE_FORMAT");
        assertThatThrownBy(() -> ScheduledJobCode.parse(code)).hasMessageContaining(ScheduledJobCode.FORMAT_MESSAGE);
    }

    @Test
    void verbatimCodeIsOnlyCheckedForBlank() {
        assertThat(ScheduledJobCode.verbatim("Sync_Code").value()).isEqualTo("Sync_Code");
        assertUseCaseError(() -> ScheduledJobCode.verbatim(" "), UseCaseError.Validation.class, "CODE_REQUIRED");
    }

    // ── Enums (lenient stored reads) ───────────────────────────────────────

    @Test
    void enumsReadStoredValuesLeniently() {
        assertThat(ScheduledJobStatus.parse("PAUSED")).isEqualTo(ScheduledJobStatus.PAUSED);
        assertThat(ScheduledJobStatus.parse("garbage")).isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(ScheduledJobStatus.parse(null)).isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(InstanceStatus.parse("DELIVERY_FAILED")).isEqualTo(InstanceStatus.DELIVERY_FAILED);
        assertThat(InstanceStatus.parse("nope")).isEqualTo(InstanceStatus.QUEUED);
        assertThat(TriggerKind.parse("BACKFILL")).isEqualTo(TriggerKind.BACKFILL);
        assertThat(TriggerKind.parse("")).isEqualTo(TriggerKind.CRON);
    }

    // ── Create / defaults ──────────────────────────────────────────────────

    @Test
    void createIsActiveVersionOneWithTheDomainDefaults() {
        var j = ScheduledJob.create(ScheduledJobCode.parse("nightly-refresh"), definition("  Nightly  "));
        assertThat(j.id()).startsWith("sjb_");
        assertThat(j.code()).isEqualTo("nightly-refresh");
        assertThat(j.name()).as("name is trimmed").isEqualTo("Nightly");
        assertThat(j.status()).isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(j.version()).isEqualTo(1);
        assertThat(j.timezone()).isEqualTo(ScheduledJob.DEFAULT_TIMEZONE);
        assertThat(j.deliveryMaxAttempts()).isEqualTo(ScheduledJob.DEFAULT_DELIVERY_MAX_ATTEMPTS);
        assertThat(j.crons()).containsExactly("0 0 * * * *");
        assertThat(j.payload()).isNull();
        assertThat(j.isPlatformScoped()).isTrue();
        assertThat(j.lastFiredAt()).isNull();
        assertThat(j.createdAt()).isEqualTo(j.updatedAt());
    }

    @Test
    void definitionAppliesDefaultsAndDropsAJsonNullPayload() {
        var d = new ScheduledJob.Definition("X", "d", HOURLY, "  ", NullNode.getInstance(), true, true, 30, null, "https://t");
        assertThat(d.timezone()).isEqualTo("UTC");
        assertThat(d.deliveryMaxAttempts()).isEqualTo(3);
        assertThat(d.payload()).isNull();
        var given = new ScheduledJob.Definition("X", null, HOURLY, "Europe/Amsterdam", json("{\"k\":1}"), false, false, null, 5, null);
        assertThat(given.timezone()).isEqualTo("Europe/Amsterdam");
        assertThat(given.deliveryMaxAttempts()).isEqualTo(5);
        assertThat(given.payload()).isEqualTo(json("{\"k\":1}"));
    }

    @Test
    void zoneIdReadsAnUnknownTimezoneAsUtc() {
        var j = job();
        assertThat(j.zoneId()).isEqualTo(ZoneId.of("UTC"));
        var ams = ScheduledJob.create(ScheduledJobCode.parse("ams"), new ScheduledJob.Definition("A", null, HOURLY,
                "Europe/Amsterdam", null, false, false, null, null, null));
        assertThat(ams.zoneId()).isEqualTo(ZoneId.of("Europe/Amsterdam"));
        var bad = ScheduledJob.create(ScheduledJobCode.parse("bad"), new ScheduledJob.Definition("A", null, HOURLY,
                "Mars/Olympus", null, false, false, null, null, null));
        assertThat(bad.zoneId()).isEqualTo(ZoneOffset.UTC);
    }

    // ── Transitions ────────────────────────────────────────────────────────

    @Test
    void pauseResumeArchiveHaveNoPreconditionsAndBumpTheVersion() {
        var j = job();
        var paused = j.pause("prn_a");
        assertThat(paused.status()).isEqualTo(ScheduledJobStatus.PAUSED);
        assertThat(paused.version()).isEqualTo(2);
        assertThat(paused.updatedBy()).isEqualTo("prn_a");
        var resumed = paused.resume("prn_b");
        assertThat(resumed.status()).isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(resumed.version()).isEqualTo(3);
        var archived = resumed.archive("prn_c");
        assertThat(archived.isArchived()).isTrue();
        assertThat(archived.version()).isEqualTo(4);
        // spec §2, open question 1: no preconditions — resume un-archives.
        assertThat(archived.resume("prn_d").status()).isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(archived.archive("prn_d").version()).isEqualTo(5);
    }

    @Test
    void updateAppliesOnlyTheNonNullChanges() {
        var j = job().withClientId("cli_1").withApplicationId("app_1").withCreatedBy("prn_c");
        var c = new ScheduledJob.Changes("  After  ", null, List.of(CronExpression.parse("0 15 3 * * *")), null,
                null, null, Boolean.TRUE, 45, null, "https://after");
        var u = j.update(c, "prn_u");
        assertThat(u.name()).isEqualTo("After");
        assertThat(u.description()).isNull();
        assertThat(u.crons()).containsExactly("0 15 3 * * *");
        assertThat(u.timezone()).isEqualTo("UTC");
        assertThat(u.tracksCompletion()).isTrue();
        assertThat(u.concurrent()).isFalse();
        assertThat(u.timeoutSeconds()).isEqualTo(45);
        assertThat(u.deliveryMaxAttempts()).isEqualTo(3);
        assertThat(u.targetUrl()).isEqualTo("https://after");
        assertThat(u.version()).isEqualTo(2);
        assertThat(u.updatedBy()).isEqualTo("prn_u");
        assertThat(u.createdBy()).isEqualTo("prn_c");
        assertThat(u.code()).as("code, scope and status are immutable on update").isEqualTo(j.code());
        assertThat(u.clientId()).isEqualTo("cli_1");
        assertThat(u.status()).isEqualTo(ScheduledJobStatus.ACTIVE);
    }

    @Test
    void updateClearsThePayloadOnJsonNullAndDefaultsABlankTimezone() {
        var withPayload = job().update(new ScheduledJob.Changes(null, null, null, "Europe/Amsterdam", json("{\"k\":1}"),
                null, null, null, null, null), "p");
        assertThat(withPayload.payload()).isEqualTo(json("{\"k\":1}"));
        assertThat(withPayload.timezone()).isEqualTo("Europe/Amsterdam");
        var untouched = withPayload.update(new ScheduledJob.Changes(null, null, null, null, null, null, null, null, null, null), "p");
        assertThat(untouched.payload()).isEqualTo(json("{\"k\":1}"));
        var cleared = withPayload.update(new ScheduledJob.Changes(null, null, null, " ", NullNode.getInstance(),
                null, null, null, null, null), "p");
        assertThat(cleared.payload()).isNull();
        assertThat(cleared.timezone()).isEqualTo("UTC");
    }

    @Test
    void fireNowMintsAManualQueuedInstanceAndRefusesAnArchivedJob() {
        var j = job().withClientId("cli_1");
        var inst = j.fireNow("corr-1");
        assertThat(inst.id()).startsWith("sji_");
        assertThat(inst.scheduledJobId()).isEqualTo(j.id());
        assertThat(inst.clientId()).isEqualTo("cli_1");
        assertThat(inst.jobCode()).isEqualTo(j.code());
        assertThat(inst.triggerKind()).isEqualTo(TriggerKind.MANUAL);
        assertThat(inst.status()).isEqualTo(InstanceStatus.QUEUED);
        assertThat(inst.deliveryAttempts()).isZero();
        assertThat(inst.scheduledFor()).isNull();
        assertThat(inst.correlationId()).isEqualTo("corr-1");
        assertThat(inst.firedAt()).isEqualTo(inst.createdAt());

        assertThat(j.pause("p").fireNow(null).triggerKind()).as("a PAUSED job is firable").isEqualTo(TriggerKind.MANUAL);
        assertUseCaseError(() -> j.archive("p").fireNow(null), UseCaseError.Conflict.class, "ARCHIVED");
    }

    // ── Sync reconcile (spec §8) ───────────────────────────────────────────

    @Test
    void reconcileIsANoOpWhenNothingDiffers() {
        var j = job().withApplicationId("app_1");
        assertThat(j.reconcile(definition("Nightly"), "app_1", "p")).isEmpty();
        assertThat(j.reconcile(definition("Nightly"), null, "p")).as("no application id given keeps the current one").isEmpty();
    }

    @Test
    void reconcileAppliesTheDefinitionReactivatesAndBackfillsTheApplication() {
        var j = job().pause("p");
        Optional<ScheduledJob> r = j.reconcile(definition("Nightly"), null, "prn_s");
        assertThat(r).as("a reappearing paused job is re-activated").isPresent();
        assertThat(r.orElseThrow().status()).isEqualTo(ScheduledJobStatus.ACTIVE);
        assertThat(r.orElseThrow().version()).isEqualTo(3);
        assertThat(r.orElseThrow().updatedBy()).isEqualTo("prn_s");

        var renamed = job().reconcile(definition("Renamed"), null, "p").orElseThrow();
        assertThat(renamed.name()).isEqualTo("Renamed");

        var backfilled = job().reconcile(definition("Nightly"), "app_9", "p").orElseThrow();
        assertThat(backfilled.applicationId()).as("NULL → set application linkage counts as a change").isEqualTo("app_9");

        var d = new ScheduledJob.Definition("Nightly", "desc", List.of(CronExpression.parse("0 0 3 * * *")), "Europe/Amsterdam",
                json("{\"a\":1}"), true, true, 10, 7, "https://x");
        var changed = job().reconcile(d, null, "p").orElseThrow();
        assertThat(changed.description()).isEqualTo("desc");
        assertThat(changed.crons()).containsExactly("0 0 3 * * *");
        assertThat(changed.timezone()).isEqualTo("Europe/Amsterdam");
        assertThat(changed.payload()).isEqualTo(json("{\"a\":1}"));
        assertThat(changed.concurrent()).isTrue();
        assertThat(changed.tracksCompletion()).isTrue();
        assertThat(changed.timeoutSeconds()).isEqualTo(10);
        assertThat(changed.deliveryMaxAttempts()).isEqualTo(7);
        assertThat(changed.targetUrl()).isEqualTo("https://x");
        assertThat(changed.reconcile(d, null, "p")).as("idempotent").isEmpty();
    }

    // ── Cron grammar: accepted (spec §3.1) ─────────────────────────────────

    @ParameterizedTest(name = "[{0}] {1} → {3}")
    @CsvSource(delimiter = '|', textBlock = """
            every second             | * * * * * *             | 2026-05-29T10:00:30Z | 2026-05-29T10:00:31Z
            every minute             | 0 * * * * *             | 2026-05-29T10:00:30Z | 2026-05-29T10:01:00Z
            hourly                   | 0 0 * * * *             | 2026-05-29T10:00:30Z | 2026-05-29T11:00:00Z
            daily midnight           | 0 0 0 * * *             | 2026-05-29T10:00:30Z | 2026-05-30T00:00:00Z
            fixed time               | 30 15 14 * * *          | 2026-05-29T10:00:30Z | 2026-05-29T14:15:30Z
            dow range names          | 0 0 9 * * MON-FRI       | 2026-05-29T10:00:30Z | 2026-06-01T09:00:00Z
            dow range numbers        | 0 30 9 * * 1-5          | 2026-05-29T10:00:30Z | 2026-06-01T09:30:00Z
            first of month           | 0 0 0 1 * *             | 2026-05-29T10:00:30Z | 2026-06-01T00:00:00Z
            dom 31                   | 0 0 0 31 * *            | 2026-05-29T10:00:30Z | 2026-05-31T00:00:00Z
            sunday numeric           | 0 0 0 * * 0             | 2026-05-29T10:00:30Z | 2026-05-31T00:00:00Z
            sunday name mixed case   | 0 0 0 * * Sun           | 2026-05-29T10:00:30Z | 2026-05-31T00:00:00Z
            leap day                 | 0 0 0 29 FEB *          | 2026-05-29T10:00:30Z | 2028-02-29T00:00:00Z
            step seconds             | */15 * * * * *          | 2026-05-29T10:00:30Z | 2026-05-29T10:00:45Z
            step minutes             | 0 */10 * * * *          | 2026-05-29T10:00:30Z | 2026-05-29T10:10:00Z
            offset step N/step       | 0 5/10 * * * *          | 2026-05-29T10:00:30Z | 2026-05-29T10:05:00Z
            dom list                 | 0 0 12 1,15 * *         | 2026-05-29T10:00:30Z | 2026-06-01T12:00:00Z
            dom OR dow (both set)    | 0 0 0 10-20 * 2         | 2026-05-29T10:00:30Z | 2026-06-02T00:00:00Z
            dom AND dow (dow star)   | 0 0 0 15 * *            | 2026-05-29T10:00:30Z | 2026-06-15T00:00:00Z
            question mark dow        | 0 0 0 * * ?             | 2026-05-29T10:00:30Z | 2026-05-30T00:00:00Z
            question mark dom        | 0 0 0 ? * 1             | 2026-05-29T10:00:30Z | 2026-06-01T00:00:00Z
            month name               | 0 0 0 1 JAN *           | 2026-05-29T10:00:30Z | 2027-01-01T00:00:00Z
            month range names        | 0 0 0 1 jan-mar *       | 2026-05-29T10:00:30Z | 2027-01-01T00:00:00Z
            dow list names           | 0 0 8 * * mon,wed,fri   | 2026-05-29T10:00:30Z | 2026-06-01T08:00:00Z
            month numeric            | 0 0 0 * 6 *             | 2026-05-29T10:00:30Z | 2026-06-01T00:00:00Z
            friday after today       | 0 0 0 * * 5             | 2026-05-29T10:00:30Z | 2026-06-05T00:00:00Z
            saturday                 | 0 0 0 * * 6             | 2026-05-29T10:00:30Z | 2026-05-30T00:00:00Z
            year end                 | 59 59 23 31 12 *        | 2026-05-29T10:00:30Z | 2026-12-31T23:59:59Z
            stepped dom clears star  | 0 0 0 */2 * *           | 2026-05-29T10:00:30Z | 2026-05-31T00:00:00Z
            leading plus (Atoi)      | +5 * * * * *            | 2026-05-29T10:00:30Z | 2026-05-29T10:01:05Z
            leading zero             | 05 * * * * *            | 2026-05-29T10:00:30Z | 2026-05-29T10:01:05Z
            extra whitespace         |   0   0  *  *  *  *     | 2026-05-29T10:00:30Z | 2026-05-29T11:00:00Z
            """)
    void cronAcceptsTheGrammarAndFindsTheNextOccurrenceInUtc(String rule, String expression, Instant after, Instant expected) {
        var cron = CronExpression.parse(expression);
        assertThat(cron.next(after.atZone(ZoneOffset.UTC)).map(t -> t.toInstant())).as(rule).contains(expected);
    }

    @ParameterizedTest(name = "[{0}] {1} in {2} → {4}")
    @CsvSource(delimiter = '|', textBlock = """
            DST gap slot is skipped      | 0 30 2 * * * | Europe/Amsterdam | 2026-03-28T11:00:00Z | 2026-03-30T00:30:00Z
            DST overlap fires once first | 0 30 2 * * * | Europe/Amsterdam | 2026-10-24T10:00:00Z | 2026-10-25T00:30:00Z
            DST day 03:00 is 01:00Z      | 0 0 3 * * *  | Europe/Amsterdam | 2026-03-28T23:00:00Z | 2026-03-29T01:00:00Z
            zone wall clock              | 0 0 9 * * *  | Europe/Amsterdam | 2026-05-28T12:00:00Z | 2026-05-29T07:00:00Z
            """)
    void cronEvaluatesOnTheZonesWallClock(String rule, String expression, String zone, Instant after, Instant expected) {
        var cron = CronExpression.parse(expression);
        assertThat(cron.next(after.atZone(ZoneId.of(zone))).map(t -> t.toInstant())).as(rule).contains(expected);
    }

    @Test
    void cronGivesUpAfterFiveYearsWithoutAnOccurrence() {
        assertThat(CronExpression.parse("0 0 0 30 2 *").next(BASE.atZone(ZoneOffset.UTC))).isEmpty();
        assertThat(CronExpression.parse("0 0 0 31 4 *").next(BASE.atZone(ZoneOffset.UTC))).isEmpty();
    }

    @Test
    void cronKeepsTheTrimmedTextAsItsStoredForm() {
        assertThat(CronExpression.parse("  0 0 * * * *  ").expression()).isEqualTo("0 0 * * * *");
        assertThat(CronExpression.tryParse("* * * * *")).isEmpty();
        assertThat(CronExpression.tryParse("0 0 * * * *")).isPresent();
    }

    // ── Cron grammar: rejected (spec §3.1) ─────────────────────────────────

    @ParameterizedTest(name = "[{0}] {1} → {2}")
    @CsvSource(delimiter = '|', textBlock = """
            five fields (POSIX)   | * * * * *                    | CRON_INVALID_SHAPE
            seven fields (year)   | 0 0 0 * * * 2030             | CRON_INVALID_SHAPE
            three fields          | * * *                        | CRON_INVALID_SHAPE
            eight fields          | a b c d e f g h              | CRON_INVALID_SHAPE
            descriptor            | @daily                       | INVALID_CRON
            descriptor every      | @every 1h                    | INVALID_CRON
            per-expression zone   | TZ=Europe/Paris 0 0 * * * *  | INVALID_CRON
            second above max      | 60 * * * * *                 | INVALID_CRON
            minute above max      | * 60 * * * *                 | INVALID_CRON
            hour above max        | * * 24 * * *                 | INVALID_CRON
            dom below min         | * * * 0 * *                  | INVALID_CRON
            dom above max         | * * * 32 * *                 | INVALID_CRON
            month below min       | * * * * 0 *                  | INVALID_CRON
            month above max       | * * * * 13 *                 | INVALID_CRON
            dow above max         | * * * * * 7                  | INVALID_CRON
            L unsupported         | * * * * * L                  | INVALID_CRON
            W unsupported         | * * * W * *                  | INVALID_CRON
            hash unsupported      | * * * * * 1#2                | INVALID_CRON
            unknown month name    | * * * * foo *                | INVALID_CRON
            descending range      | 5-3 * * * * *                | INVALID_CRON
            too many hyphens      | 1-2-3 * * * * *              | INVALID_CRON
            zero step             | */0 * * * * *                | INVALID_CRON
            too many slashes      | 1/2/3 * * * * *              | INVALID_CRON
            non-numeric step      | */x * * * * *                | INVALID_CRON
            negative              | -1 * * * * *                 | INVALID_CRON
            non-numeric           | abc * * * * *                | INVALID_CRON
            decimal               | 1.5 * * * * *                | INVALID_CRON
            empty list item       | 1,,2 * * * * *               | INVALID_CRON
            trailing comma        | 1, * * * * *                 | INVALID_CRON
            lone comma            | , * * * * *                  | INVALID_CRON
            """)
    void cronRejectsMalformedExpressions(String rule, String expression, String code) {
        assertUseCaseError(() -> CronExpression.parse(expression), UseCaseError.Validation.class, code);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void cronRejectsBlank(String expression) {
        assertUseCaseError(() -> CronExpression.parse(expression), UseCaseError.Validation.class, "INVALID_CRON");
        assertThatThrownBy(() -> CronExpression.parse(expression)).hasMessageContaining("cron expressions cannot be empty");
    }

    @Test
    void cronShapeErrorNamesTheCountAndTheExpression() {
        assertThatThrownBy(() -> CronExpression.parse("* * * * *"))
                .hasMessageContaining("must have 6 whitespace-separated fields (sec min hour dom mon dow), got 5: '* * * * *'");
    }

    // ── latestSlotInWindow (spec §3.2) ─────────────────────────────────────

    @ParameterizedTest(name = "[{0}] {1} ({3}, {4}] → {5}")
    @CsvSource(delimiter = '|', textBlock = """
            skip missed to latest  | 0 * * * * *              | UTC              | 2026-05-29T10:00:30Z | 2026-05-29T10:03:45Z | 2026-05-29T10:03:00Z
            no slot in window      | 0 0 0 * * *              | UTC              | 2026-05-29T10:00:00Z | 2026-05-29T11:00:00Z | NONE
            empty window           | 0 * * * * *              | UTC              | 2026-05-29T10:00:00Z | 2026-05-29T10:00:00Z | NONE
            inverted window        | 0 * * * * *              | UTC              | 2026-05-29T11:00:00Z | 2026-05-29T10:00:00Z | NONE
            latest across crons    | 0 15 * * * *;0 25 * * * * | UTC             | 2026-05-29T10:00:00Z | 2026-05-29T10:30:00Z | 2026-05-29T10:25:00Z
            lower bound exclusive  | 0 * * * * *              | UTC              | 2026-05-29T10:00:00Z | 2026-05-29T10:00:30Z | NONE
            upper bound inclusive  | 0 * * * * *              | UTC              | 2026-05-29T10:00:30Z | 2026-05-29T10:03:00Z | 2026-05-29T10:03:00Z
            evaluated in the zone  | 0 0 9 * * *              | Europe/Amsterdam | 2026-05-28T12:00:00Z | 2026-05-29T12:00:00Z | 2026-05-29T07:00:00Z
            unparseable is skipped | * * * * *                | UTC              | 2026-05-29T10:00:00Z | 2026-05-29T10:05:00Z | NONE
            bad one skipped, good fires | * * * * *;0 * * * * * | UTC           | 2026-05-29T10:00:30Z | 2026-05-29T10:02:00Z | 2026-05-29T10:02:00Z
            """)
    void latestSlotInWindowIsTheLatestSlotAcrossCronsInTheHalfOpenWindow(String rule, String crons, String zone,
                                                                         Instant after, Instant upTo, String expected) {
        var slot = Cron.latestSlotInWindow(Arrays.asList(crons.split(";")), ZoneId.of(zone), after, upTo);
        if (expected.equals("NONE")) {
            assertThat(slot).as(rule).isEmpty();
        } else {
            assertThat(slot).as(rule).contains(Instant.parse(expected));
        }
    }

    @Test
    void theJobAnswersLatestSlotInWindowInItsOwnZone() {
        var ams = ScheduledJob.create(ScheduledJobCode.parse("ams"), new ScheduledJob.Definition("A", null,
                List.of(CronExpression.parse("0 0 9 * * *")), "Europe/Amsterdam", null, false, false, null, null, null));
        assertThat(ams.latestSlotInWindow(Instant.parse("2026-05-28T12:00:00Z"), Instant.parse("2026-05-29T12:00:00Z")))
                .contains(Instant.parse("2026-05-29T07:00:00Z"));
    }
}
