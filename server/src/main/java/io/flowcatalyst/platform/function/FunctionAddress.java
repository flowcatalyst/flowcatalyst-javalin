package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;

/// A function's fully-qualified address, `app.service.name` (spec
/// `function-registry.md` §3.2) — the identity used in router targets,
/// permission grants and metrics.
///
/// @param application the owning application's DNS label
/// @param service     the service grouping's DNS label
/// @param name        the function's own DNS label
public record FunctionAddress(DnsLabel application, DnsLabel service, DnsLabel name) {

    private static final String MESSAGE =
            "address must be app.service.function: three DNS labels separated by '.'";

    public FunctionAddress {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(name, "name");
    }

    /// Builds an address from already-parsed labels — the type carries the
    /// proof, so a caller that already has [DnsLabel]s never re-parses.
    public static FunctionAddress of(DnsLabel application, DnsLabel service, DnsLabel name) {
        return new FunctionAddress(application, service, name);
    }

    /// Splits on `.` and requires exactly three segments, each a
    /// [DnsLabel]. The parser never supplies a default service segment —
    /// that is a tooling default, not a parsing rule (spec §3.2).
    ///
    /// @throws UseCaseException validation `ADDRESS_INVALID` for any
    ///                          failure — wrong segment count, an empty or
    ///                          malformed segment, or a `null` input
    public static FunctionAddress parse(String raw) {
        if (raw == null) throw invalid();
        String[] parts = raw.split("\\.", -1);
        if (parts.length != 3) throw invalid();
        for (String part : parts) {
            if (!DnsLabel.isValid(part)) throw invalid();
        }
        return new FunctionAddress(new DnsLabel(parts[0]), new DnsLabel(parts[1]), new DnsLabel(parts[2]));
    }

    /// `app.service.name`.
    public String render() {
        return application.value() + "." + service.value() + "." + name.value();
    }

    @Override
    public String toString() {
        return render();
    }

    private static UseCaseException invalid() {
        return UseCaseException.validation("ADDRESS_INVALID", MESSAGE);
    }
}
