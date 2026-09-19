package io.flowcatalyst.function;

import java.util.regex.Pattern;

/// A function's fully-qualified address, `app.service.name` — the identity a
/// function reads off every [Invocation] and passes to
/// [FunctionContext#address()].
///
/// This is a **second, deliberate copy** of the parser in
/// `platform/function/FunctionAddress` on the server: the API jar has zero
/// dependencies and cannot import the server's `DnsLabel`/`FunctionAddress`
/// types, so the rule is re-stated here against the same table
/// (`docs/spec/function-host-core.md` §1; a shared `@CsvSource` file run
/// through both copies in `function-host` fails if they ever disagree).
///
/// Each segment is a DNS label: 1-63 characters of lower-case letters,
/// digits and `-`, not starting or ending with `-`. No normalisation — the
/// input is not trimmed and not lower-cased.
///
/// @param application the owning application's DNS label
/// @param service     the service grouping's DNS label
/// @param name        the function's own DNS label
public record FunctionAddress(String application, String service, String name) {

    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final String MESSAGE =
            "address must be app.service.function: three DNS labels separated by '.'";

    public FunctionAddress {
        requireLabel(application);
        requireLabel(service);
        requireLabel(name);
    }

    /// Splits on `.` and requires exactly three segments, each a valid DNS
    /// label. Never supplies a default service segment — that is a tooling
    /// default, not a parsing rule.
    ///
    /// @throws IllegalArgumentException for any failure — wrong segment
    ///                                  count, an empty or malformed
    ///                                  segment, or a `null` input
    public static FunctionAddress parse(String raw) {
        if (raw == null) throw invalid();
        String[] parts = raw.split("\\.", -1);
        if (parts.length != 3) throw invalid();
        for (String part : parts) {
            if (!isValidLabel(part)) throw invalid();
        }
        return new FunctionAddress(parts[0], parts[1], parts[2]);
    }

    /// `app.service.name`.
    public String render() {
        return application + "." + service + "." + name;
    }

    @Override
    public String toString() {
        return render();
    }

    private static boolean isValidLabel(String raw) {
        return raw != null && LABEL.matcher(raw).matches();
    }

    private static void requireLabel(String value) {
        if (!isValidLabel(value)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(MESSAGE);
    }
}
