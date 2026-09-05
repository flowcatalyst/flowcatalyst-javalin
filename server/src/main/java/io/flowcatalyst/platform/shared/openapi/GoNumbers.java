package io.flowcatalyst.platform.shared.openapi;

/// Renders a `double` the way Go's `fmt.Sprintf("%v", …)` renders a
/// `float64` schema bound (spec §1's closing paragraph): a whole number
/// prints without a decimal point (`3`, not `3.0`) and a fractional value
/// prints in the shortest round-tripping decimal form (`0.5`). Every bound
/// in the committed lockfile today is a whole number (`minimum: 0`); this
/// covers the fractional case for the day one isn't, and for
/// [ValidationMessages]'s own tests.
final class GoNumbers {

    private GoNumbers() {
    }

    static String format(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) {
            return Double.toString(n);
        }
        if (n == Math.rint(n) && Math.abs(n) < 1e15) {
            return Long.toString((long) n);
        }
        // Double.toString is already the shortest round-tripping form; it
        // always carries a decimal point (unlike Go's %v), which is exactly
        // what a genuinely fractional value needs here.
        return Double.toString(n);
    }
}
