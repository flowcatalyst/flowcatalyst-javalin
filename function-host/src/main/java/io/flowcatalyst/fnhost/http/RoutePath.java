package io.flowcatalyst.fnhost.http;

import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.sdk.usecase.UseCaseException;

/// Parses `/functions/{address}[:{version}]/{path}` (spec
/// `function-host-listener.md` §2 step 2, `function-invocation.md` §2): `:`
/// is a legal path-segment character, but an address may never contain one,
/// so the split on the first `:` inside the address-and-version segment is
/// unambiguous. The raw path is matched **undecoded** — no segment here is
/// percent-decoded; that is [io.flowcatalyst.platform.function.RoutePattern#match]'s
/// job for a `{param}` capture, and doing it twice would double-decode.
///
/// @param address     the parsed function address
/// @param version     the explicit version, or `null` for an unversioned call
/// @param functionPath `/` + whatever followed the address segment — never
///                     without the leading `/`, `/` itself when nothing followed
public record RoutePath(FunctionAddress address, Integer version, String functionPath) {

    private static final String PREFIX = "/functions/";

    /// The outcome of [#parse] — a sealed type so a caller's `switch` is
    /// exhaustive over exactly the three responses spec §2 step 2 names.
    public sealed interface Result permits Matched, NotFunctionsRoute, AddressInvalid, VersionInvalid {
    }

    public record Matched(RoutePath path) implements Result {
    }

    /// The raw path did not start with `/functions/` at all — spec §2:
    /// "Only `/functions/…` is routed; anything else is 404."
    public record NotFunctionsRoute() implements Result {
    }

    /// Spec §2 step 2: `400 ADDRESS_INVALID`.
    public record AddressInvalid() implements Result {
    }

    /// Spec §2 step 2: `400 VERSION_INVALID` — present but not a positive int.
    public record VersionInvalid() implements Result {
    }

    public static Result parse(String rawPath) {
        if (rawPath == null || !rawPath.startsWith(PREFIX)) {
            return new NotFunctionsRoute();
        }
        String afterPrefix = rawPath.substring(PREFIX.length());
        int slash = afterPrefix.indexOf('/');
        String addressAndVersion = slash < 0 ? afterPrefix : afterPrefix.substring(0, slash);
        String rest = slash < 0 ? "" : afterPrefix.substring(slash + 1);
        if (addressAndVersion.isEmpty()) {
            return new AddressInvalid();
        }

        int colon = addressAndVersion.indexOf(':');
        String addressRaw = colon < 0 ? addressAndVersion : addressAndVersion.substring(0, colon);
        Integer version = null;
        if (colon >= 0) {
            String versionRaw = addressAndVersion.substring(colon + 1);
            try {
                version = Integer.parseInt(versionRaw);
            } catch (NumberFormatException e) {
                return new VersionInvalid();
            }
            if (version <= 0) {
                return new VersionInvalid();
            }
        }

        FunctionAddress address;
        try {
            address = FunctionAddress.parse(addressRaw);
        } catch (UseCaseException e) {
            return new AddressInvalid();
        }

        return new Matched(new RoutePath(address, version, "/" + rest));
    }
}
