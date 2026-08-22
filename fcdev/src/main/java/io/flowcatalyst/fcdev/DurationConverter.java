package io.flowcatalyst.fcdev;

import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/// Go `time.ParseDuration` for flags: `300ms`, `1.5s`, `2m`, `1h30m`
/// (units ns/us/µs/ms/s/m/h, a sign, concatenation). Formats the Go way too.
public final class DurationConverter implements ITypeConverter<Duration> {

    private static final Pattern PART = Pattern.compile("([0-9]*\\.?[0-9]+)(ns|us|µs|μs|ms|s|m|h)");

    @Override
    public Duration convert(String value) {
        return parse(value);
    }

    public static Duration parse(String raw) {
        var s = raw.strip();
        if (s.equals("0")) return Duration.ZERO;
        boolean negative = s.startsWith("-");
        if (negative || s.startsWith("+")) s = s.substring(1);
        if (s.isEmpty()) throw new TypeConversionException("invalid duration \"" + raw + "\"");
        var m = PART.matcher(s);
        long nanos = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) throw new TypeConversionException("invalid duration \"" + raw + "\"");
            double n = Double.parseDouble(m.group(1));
            long unit = switch (m.group(2)) {
                case "ns" -> 1L;
                case "us", "µs", "μs" -> 1_000L;
                case "ms" -> 1_000_000L;
                case "s" -> 1_000_000_000L;
                case "m" -> 60_000_000_000L;
                case "h" -> 3_600_000_000_000L;
                default -> throw new TypeConversionException("unknown unit in duration \"" + raw + "\"");
            };
            nanos += (long) (n * unit);
            end = m.end();
        }
        if (end != s.length()) throw new TypeConversionException("invalid duration \"" + raw + "\" (missing unit?)");
        var d = Duration.ofNanos(nanos);
        return negative ? d.negated() : d;
    }

    /// Go's `Duration.String()` for the common cases: `20s`, `1m30s`, `1.5s`, `150ms`.
    public static String format(Duration d) {
        long nanos = d.toNanos();
        if (nanos == 0) return "0s";
        var sb = new StringBuilder();
        if (nanos < 0) { sb.append('-'); nanos = -nanos; }
        if (nanos < 1_000_000_000L) {
            if (nanos >= 1_000_000L) return sb.append(trim(nanos / 1e6)).append("ms").toString();
            if (nanos >= 1_000L) return sb.append(trim(nanos / 1e3)).append("µs").toString();
            return sb.append(nanos).append("ns").toString();
        }
        long h = nanos / 3_600_000_000_000L; nanos %= 3_600_000_000_000L;
        long m = nanos / 60_000_000_000L; nanos %= 60_000_000_000L;
        double s = nanos / 1e9;
        if (h > 0) sb.append(h).append('h');
        if (h > 0 || m > 0) sb.append(m).append('m');
        sb.append(trim(s)).append('s');
        return sb.toString();
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) return Long.toString((long) v);
        var s = String.format(Locale.ROOT, "%.9f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
