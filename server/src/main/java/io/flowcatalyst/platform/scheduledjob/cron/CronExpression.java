package io.flowcatalyst.platform.scheduledjob.cron;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// A parsed six-field cron expression `second minute hour day-of-month
/// month day-of-week` (spec §3.1) — the one reader of cron text: the
/// create / update / sync validate phases and [Cron#latestSlotInWindow] all
/// go through [#parse], so every entry point rejects a malformed
/// expression with the same codes and messages.
///
/// Each field is a bit set over its range; day-of-month and day-of-week
/// additionally carry a *star* flag (bit 63) when written as `*`/`?`
/// without a step, which selects AND versus OR day matching (spec §3.1).
///
/// @param expression the text as given (trimmed), the stored form
/// @param seconds    bits 0–59
/// @param minutes    bits 0–59
/// @param hours      bits 0–23
/// @param daysOfMonth bits 1–31, plus the star flag
/// @param months     bits 1–12
/// @param daysOfWeek bits 0–6 (0 = Sunday), plus the star flag
public record CronExpression(String expression, long seconds, long minutes, long hours,
                             long daysOfMonth, long months, long daysOfWeek) {

    /// The validation code for a blank, descriptor, zone-prefixed or
    /// grammatically malformed expression.
    public static final String INVALID_CRON = "INVALID_CRON";

    /// The validation code for the wrong field count (spec §3.1, ruling).
    public static final String CRON_INVALID_SHAPE = "CRON_INVALID_SHAPE";

    public static final int FIELD_COUNT = 6;

    /// How far [#next] searches before giving up (robfig's `yearLimit`).
    private static final int YEAR_LIMIT = 5;

    /// `*` / `?` without a step: set on the day fields only.
    static final long STAR_BIT = 1L << 63;

    private record Bounds(int min, int max, Map<String, Integer> names) {
    }

    private static final Bounds SECONDS = new Bounds(0, 59, Map.of());
    private static final Bounds MINUTES = new Bounds(0, 59, Map.of());
    private static final Bounds HOURS = new Bounds(0, 23, Map.of());
    private static final Bounds DOM = new Bounds(1, 31, Map.of());
    private static final Bounds MONTHS = new Bounds(1, 12, Map.ofEntries(
            Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3), Map.entry("apr", 4),
            Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12)));
    private static final Bounds DOW = new Bounds(0, 6, Map.of(
            "sun", 0, "mon", 1, "tue", 2, "wed", 3, "thu", 4, "fri", 5, "sat", 6));

    public CronExpression {
        Objects.requireNonNull(expression, "expression");
    }

    // ── Parsing ────────────────────────────────────────────────────────────

    /// Parses an expression.
    ///
    /// @throws UseCaseException validation `INVALID_CRON` (blank, descriptor,
    ///                          `TZ=` prefix, malformed field) or
    ///                          `CRON_INVALID_SHAPE` (not six fields)
    public static CronExpression parse(String text) {
        if (text == null || text.isBlank()) {
            throw UseCaseException.validation(INVALID_CRON, "cron expressions cannot be empty");
        }
        String expr = text.strip();
        if (expr.startsWith("@")) {
            throw UseCaseException.validation(INVALID_CRON,
                    "cron expression '" + expr + "': descriptors are not supported");
        }
        if (expr.startsWith("TZ=") || expr.startsWith("CRON_TZ=")) {
            throw UseCaseException.validation(INVALID_CRON,
                    "cron expression '" + expr + "': a per-expression time zone is not supported; use the job's timezone");
        }
        String[] fields = expr.split("\\s+");
        if (fields.length != FIELD_COUNT) {
            throw UseCaseException.validation(CRON_INVALID_SHAPE,
                    "cron expression must have 6 whitespace-separated fields (sec min hour dom mon dow), got "
                            + fields.length + ": '" + expr + "'");
        }
        try {
            return new CronExpression(expr,
                    field(fields[0], SECONDS),
                    field(fields[1], MINUTES),
                    field(fields[2], HOURS),
                    field(fields[3], DOM),
                    field(fields[4], MONTHS),
                    field(fields[5], DOW));
        } catch (Malformed e) {
            throw UseCaseException.validation(INVALID_CRON, "cron expression '" + expr + "': " + e.getMessage());
        }
    }

    /// [#parse] as an outcome, for readers of *stored* text that must not
    /// fail on a legacy row (spec §3.2): empty when the text does not parse.
    public static Optional<CronExpression> tryParse(String text) {
        try {
            return Optional.of(parse(text));
        } catch (UseCaseException _) {
            return Optional.empty();
        }
    }

    /// A field is a comma-separated list of ranges; an empty item is a
    /// malformation (spec §3.1, "list").
    private static long field(String field, Bounds r) {
        long bits = 0;
        for (String item : field.split(",", -1)) {
            if (item.isEmpty()) {
                throw new Malformed("empty list item in field: " + field);
            }
            bits |= range(item, r);
        }
        return bits;
    }

    /// One range `*|?|N|N-M|name[-name]` with an optional `/step`.
    private static long range(String expr, Bounds r) {
        String[] rangeAndStep = expr.split("/", -1);
        if (rangeAndStep.length > 2) {
            throw new Malformed("too many slashes: " + expr);
        }
        String[] lowAndHigh = rangeAndStep[0].split("-", -1);
        boolean singleValue = lowAndHigh.length == 1;
        int start;
        int end;
        long extra = 0;
        if (lowAndHigh[0].equals("*") || lowAndHigh[0].equals("?")) {
            start = r.min();
            end = r.max();
            extra = STAR_BIT;
        } else {
            start = intOrName(lowAndHigh[0], r.names());
            switch (lowAndHigh.length) {
                case 1 -> end = start;
                case 2 -> end = intOrName(lowAndHigh[1], r.names());
                default -> throw new Malformed("too many hyphens: " + expr);
            }
        }
        int step = 1;
        if (rangeAndStep.length == 2) {
            step = nonNegativeInt(rangeAndStep[1]);
            if (singleValue) {
                end = r.max(); // "N/step" means "N-max/step"
            }
            if (step > 1) {
                extra = 0; // a stepped wildcard is a restriction, not "any day"
            }
        }
        if (start < r.min()) {
            throw new Malformed("beginning of range (" + start + ") below minimum (" + r.min() + "): " + expr);
        }
        if (end > r.max()) {
            throw new Malformed("end of range (" + end + ") above maximum (" + r.max() + "): " + expr);
        }
        if (start > end) {
            throw new Malformed("beginning of range (" + start + ") beyond end of range (" + end + "): " + expr);
        }
        if (step == 0) {
            throw new Malformed("step of range should be a positive number: " + expr);
        }
        long bits = 0;
        for (int i = start; i <= end; i += step) {
            bits |= 1L << i;
        }
        return bits | extra;
    }

    private static int intOrName(String token, Map<String, Integer> names) {
        Integer named = names.get(token.toLowerCase(Locale.ROOT));
        return named != null ? named : nonNegativeInt(token);
    }

    /// Go `Atoi` semantics: an optional sign and ASCII digits; negatives are rejected.
    private static int nonNegativeInt(String token) {
        if (!token.matches("[+-]?[0-9]+")) {
            throw new Malformed("failed to parse int from " + token);
        }
        int n;
        try {
            n = Integer.parseInt(token);
        } catch (NumberFormatException _) {
            throw new Malformed("failed to parse int from " + token);
        }
        if (n < 0) {
            throw new Malformed("negative number (" + n + ") not allowed: " + token);
        }
        return n;
    }

    /// Internal grammar failure, translated to the one validation error by [#parse].
    private static final class Malformed extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Malformed(String message) {
            super(message, null, false, false);
        }
    }

    // ── Evaluation ─────────────────────────────────────────────────────────

    /// The first occurrence strictly after `t`, at whole-second resolution,
    /// in `t`'s zone; empty when none falls within [#YEAR_LIMIT] years
    /// (spec §3.1 "next occurrence"). Months and days advance on the local
    /// calendar, hours/minutes/seconds on the instant timeline, so a slot in
    /// a DST gap is skipped and one in an overlap fires once, at its first
    /// occurrence.
    public Optional<ZonedDateTime> next(ZonedDateTime t) {
        var zone = t.getZone();
        t = t.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
        boolean added = false;
        int yearLimit = t.getYear() + YEAR_LIMIT;

        wrap:
        while (true) {
            if (t.getYear() > yearLimit) {
                return Optional.empty();
            }
            while (!bit(months, t.getMonthValue())) {
                if (!added) {
                    added = true;
                    t = ZonedDateTime.of(t.getYear(), t.getMonthValue(), 1, 0, 0, 0, 0, zone);
                }
                t = t.plusMonths(1);
                if (t.getMonthValue() == 1) {
                    continue wrap;
                }
            }
            while (!dayMatches(t)) {
                if (!added) {
                    added = true;
                    t = ZonedDateTime.of(t.getYear(), t.getMonthValue(), t.getDayOfMonth(), 0, 0, 0, 0, zone);
                }
                t = t.plusDays(1);
                // A DST transition at midnight can leave the hour at 23 or 1; re-align.
                if (t.getHour() != 0) {
                    t = t.getHour() > 12 ? t.plusHours(24 - t.getHour()) : t.minusHours(t.getHour());
                }
                if (t.getDayOfMonth() == 1) {
                    continue wrap;
                }
            }
            while (!bit(hours, t.getHour())) {
                if (!added) {
                    added = true;
                    t = t.truncatedTo(ChronoUnit.HOURS);
                }
                t = t.plusHours(1);
                if (t.getHour() == 0) {
                    continue wrap;
                }
            }
            while (!bit(minutes, t.getMinute())) {
                if (!added) {
                    added = true;
                    t = t.truncatedTo(ChronoUnit.MINUTES);
                }
                t = t.plusMinutes(1);
                if (t.getMinute() == 0) {
                    continue wrap;
                }
            }
            while (!bit(seconds, t.getSecond())) {
                if (!added) {
                    added = true;
                    t = t.truncatedTo(ChronoUnit.SECONDS);
                }
                t = t.plusSeconds(1);
                if (t.getSecond() == 0) {
                    continue wrap;
                }
            }
            return Optional.of(t);
        }
    }

    /// Spec §3.1 "day matching": AND when either day field is a bare
    /// wildcard, OR when both are restricted.
    private boolean dayMatches(ZonedDateTime t) {
        boolean domMatch = bit(daysOfMonth, t.getDayOfMonth());
        boolean dowMatch = bit(daysOfWeek, t.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : t.getDayOfWeek().getValue());
        if ((daysOfMonth & STAR_BIT) != 0 || (daysOfWeek & STAR_BIT) != 0) {
            return domMatch && dowMatch;
        }
        return domMatch || dowMatch;
    }

    private static boolean bit(long bits, int i) {
        return (bits & (1L << i)) != 0;
    }

    /// The parsed expressions of a stored list, skipping what does not parse.
    static List<CronExpression> lenient(List<String> crons) {
        return crons.stream().flatMap(c -> tryParse(c).stream()).toList();
    }

    @Override
    public String toString() {
        return expression;
    }
}
