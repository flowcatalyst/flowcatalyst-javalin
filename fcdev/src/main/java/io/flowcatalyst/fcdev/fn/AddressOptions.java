package io.flowcatalyst.fcdev.fn;

import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

/// `docs/spec/function-developer-surface.md` §2: "Addresses: a full
/// `app.service.name`, or `--app` `--service` (default `default`) `--name`;
/// a two-part address is a usage error." Mixed into every `fn` subcommand
/// that names a function; the owning command supplies whatever full-address
/// text it captured itself (its own positional parameter, at whatever index
/// fits alongside its other positional arguments — `jar`, `dir`, …) to
/// {@link #resolve(String)}, which does the XOR validation and the
/// `--service` default. A violation is a picocli [ParameterException] thrown
/// from business logic — picocli's `execute()` recognises this specially and
/// answers exit code 2 with the command's usage, not a stack trace, exactly
/// as a bad flag would (`docs/fcdev.md`: "usage errors are exit 2").
public final class AddressOptions {

    @Option(names = "--app", paramLabel = "<code>", description = "application code (with --name, instead of a full address)")
    String app;

    @Option(names = "--service", paramLabel = "<name>", defaultValue = "default",
            description = "service name (default: ${DEFAULT-VALUE})")
    String service;

    @Option(names = "--name", paramLabel = "<name>", description = "function name (with --app, instead of a full address)")
    String name;

    @Spec
    CommandSpec spec;

    /// @param fullAddress the command's own `<address>` positional text, or
    ///                     `null`/blank when none was given
    /// @return the resolved `app.service.name` address
    /// @throws ParameterException a full address AND `--app`/`--name` were
    ///                            both given; the full address is not exactly
    ///                            three `.`-separated parts (a two-part
    ///                            address is the named case, `spec §2`); or
    ///                            neither form was given
    public String resolve(String fullAddress) {
        boolean hasFull = fullAddress != null && !fullAddress.isBlank();
        boolean hasParts = notBlank(app) || notBlank(name);
        if (hasFull && hasParts) {
            throw usageError("give either a full address or --app/--service/--name, not both");
        }
        if (hasFull) {
            String[] segments = fullAddress.split("\\.", -1);
            if (segments.length != 3) {
                throw usageError("address must be app.service.name (three '.'-separated parts), got \""
                        + fullAddress + "\"");
            }
            return fullAddress;
        }
        if (!notBlank(app)) {
            throw usageError("an address is required: give app.service.name, or --app (with --name)");
        }
        if (!notBlank(name)) {
            throw usageError("--name is required together with --app");
        }
        String svc = notBlank(service) ? service : "default";
        return app + "." + svc + "." + name;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private ParameterException usageError(String message) {
        return new CommandLine.ParameterException(spec.commandLine(), message);
    }
}
