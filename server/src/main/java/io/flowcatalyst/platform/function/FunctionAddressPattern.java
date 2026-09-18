package io.flowcatalyst.platform.function;

import io.flowcatalyst.sdk.usecase.UseCaseException;

import java.util.Objects;

/// What a permission grant, a list filter or a status view names (spec
/// `function-registry.md` §3.3): an exact address, `app.service.*` or
/// `app.*`. "Everything" is the absence of a filter, not a pattern — there
/// is no wildcard for that.
///
/// [#matches] compares whole segments and never a prefix of a string; the
/// repository turns a pattern into column equalities (never a `LIKE`), so
/// the two must never disagree — §8 M3.
public sealed interface FunctionAddressPattern {

    /// The pattern's `.`-joined wire form.
    String render();

    /// Whether `address` falls under this pattern. Whole-segment comparison
    /// only; `null` never matches.
    boolean matches(FunctionAddress address);

    /// A single exact address.
    record Exact(FunctionAddress address) implements FunctionAddressPattern {
        public Exact {
            Objects.requireNonNull(address, "address");
        }

        @Override
        public String render() {
            return address.render();
        }

        @Override
        public boolean matches(FunctionAddress other) {
            return other != null && address.equals(other);
        }
    }

    /// Every function of one service: `application.service.*`.
    record Service(DnsLabel application, DnsLabel service) implements FunctionAddressPattern {
        public Service {
            Objects.requireNonNull(application, "application");
            Objects.requireNonNull(service, "service");
        }

        @Override
        public String render() {
            return application.value() + "." + service.value() + ".*";
        }

        @Override
        public boolean matches(FunctionAddress other) {
            return other != null && application.equals(other.application()) && service.equals(other.service());
        }
    }

    /// Every function of one application: `application.*`.
    record Application(DnsLabel application) implements FunctionAddressPattern {
        public Application {
            Objects.requireNonNull(application, "application");
        }

        @Override
        public String render() {
            return application.value() + ".*";
        }

        @Override
        public boolean matches(FunctionAddress other) {
            return other != null && application.equals(other.application());
        }
    }

    /// A bare `*`, a wildcard anywhere but last (`a.*.c`, `*.b.c`), a
    /// partial-segment wildcard (`billing.inv*`), and a four-segment pattern
    /// (`a.b.c.*`) are all rejected: a wildcard is only ever the whole final
    /// segment of a two- or three-segment pattern.
    ///
    /// @throws UseCaseException validation `ADDRESS_PATTERN_INVALID`
    static FunctionAddressPattern parse(String raw) {
        if (raw == null) throw invalid();
        String[] parts = raw.split("\\.", -1);
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("*")) throw invalid();
        }
        String last = parts[parts.length - 1];
        if (parts.length == 3) {
            if (last.equals("*")) {
                if (!DnsLabel.isValid(parts[0]) || !DnsLabel.isValid(parts[1])) throw invalid();
                return new Service(new DnsLabel(parts[0]), new DnsLabel(parts[1]));
            }
            try {
                return new Exact(FunctionAddress.parse(raw));
            } catch (UseCaseException e) {
                throw invalid();
            }
        }
        if (parts.length == 2 && last.equals("*")) {
            if (!DnsLabel.isValid(parts[0])) throw invalid();
            return new Application(new DnsLabel(parts[0]));
        }
        throw invalid();
    }

    private static UseCaseException invalid() {
        return UseCaseException.validation("ADDRESS_PATTERN_INVALID",
                "address pattern must be app.service.function, app.service.*, or app.*");
    }
}
