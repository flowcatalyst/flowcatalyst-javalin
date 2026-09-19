package io.flowcatalyst.platform.function;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/// The `FC_FN_POOL_URL` convention (spec `function-invocation.md` §4, R8):
/// one URL template with an OPTIONAL `{pool}` placeholder — an environment
/// with a single pool, or fcdev, names the host directly and never mentions
/// `{pool}` at all — resolved per manifest pool at promote — never at boot,
/// since the pool label is a property of each function's OWN manifest, not
/// of the process.
///
/// [#parse(String)] is the composition root's only way to build one, and
/// validates the whole shape up front (never a silent fallback to the
/// default, "impossible to misconfigure by typo", the same division of
/// labour as [io.flowcatalyst.platform.function.artifact.Signatures#resolve]):
///
/// - `{pool}` appears **at most once** (never twice — nothing sane to
///   substitute two placeholders with the same one value in different
///   URL positions).
/// - the template is an **absolute `http`/`https` URL**.
/// - **no userinfo** — `http://user@host` is rejected outright. A previous
///   version of this class's own test harness placed `{pool}` in the
///   userinfo position to route around the (then-mandatory) placeholder
///   requirement; that was a hack around a rule that was wrong for a
///   single-pool or `fcdev` setup, not a legitimate URL shape, so it is
///   rejected structurally now rather than merely discouraged.
/// - **no path beyond an optional trailing slash** (stripped at
///   [#resolve] time — [FunctionTriggerSync#endpointFor] string-concatenates
///   `resolve(pool) + "/functions/" + address + path` directly, so a
///   trailing slash here would double up).
/// - **no query, no fragment**.
///
/// @param template the raw template, unchanged, exactly as configured
public record PoolUrlTemplate(String template) {

    /// The default when `FC_FN_POOL_URL` is unset (spec §4 R8).
    public static final String DEFAULT = "http://fn-{pool}:8080";

    private static final String PLACEHOLDER = "{pool}";

    public PoolUrlTemplate {
        Objects.requireNonNull(template, "template");
        validate(template);
    }

    /// The composition root's entry point.
    ///
    /// @throws IllegalStateException `raw` does not parse as a valid pool URL template
    public static PoolUrlTemplate parse(String raw) {
        return new PoolUrlTemplate(raw);
    }

    private static void validate(String template) {
        int first = template.indexOf(PLACEHOLDER);
        int last = template.lastIndexOf(PLACEHOLDER);
        if (first >= 0 && first != last) {
            fail(template, "must contain at most one '{pool}' placeholder");
        }

        // '{' and '}' are not legal URI characters — substitute a throwaway, legal
        // DNS-label-shaped stand-in purely so the rest of the shape can be validated
        // with java.net.URI; the ORIGINAL template (with '{pool}' intact) is what is
        // actually stored and later resolved.
        String forParsing = first >= 0 ? template.replace(PLACEHOLDER, "fc-pool-placeholder") : template;
        URI uri;
        try {
            uri = new URI(forParsing);
        } catch (URISyntaxException e) {
            fail(template, "must be a valid URL");
            return;
        }
        if (!uri.isAbsolute()) {
            fail(template, "must be an absolute http/https URL");
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            fail(template, "must use the http or https scheme");
        }
        if (uri.getRawUserInfo() != null) {
            fail(template, "must not contain userinfo");
        }
        if (uri.getHost() == null) {
            fail(template, "must name a host");
        }
        if (uri.getRawQuery() != null) {
            fail(template, "must not contain a query");
        }
        if (uri.getRawFragment() != null) {
            fail(template, "must not contain a fragment");
        }
        String path = uri.getRawPath();
        if (path != null && !path.isEmpty() && !path.equals("/")) {
            fail(template, "must not contain a path beyond an optional trailing slash");
        }
    }

    private static void fail(String template, String rule) {
        throw new IllegalStateException("FC_FN_POOL_URL " + rule + ": '" + template + "'");
    }

    /// Substitutes `{pool}` with `pool`'s own value — never percent-encoded,
    /// since a [DnsLabel] is already a legal host-name segment — when the
    /// template names one at all; a template with no placeholder resolves to
    /// itself for every pool (a single-pool or `fcdev` environment). Any
    /// trailing slash is stripped, so
    /// [io.flowcatalyst.platform.function.operations.FunctionTriggerSync#endpointFor]'s
    /// own `resolve(pool) + "/functions/…"` concatenation never doubles a slash.
    public String resolve(DnsLabel pool) {
        Objects.requireNonNull(pool, "pool");
        String resolved = template.contains(PLACEHOLDER) ? template.replace(PLACEHOLDER, pool.value()) : template;
        return resolved.endsWith("/") ? resolved.substring(0, resolved.length() - 1) : resolved;
    }
}
