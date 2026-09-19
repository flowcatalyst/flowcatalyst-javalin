package io.flowcatalyst.platform.function;

import java.util.Objects;

/// The `FC_FN_POOL_URL` convention (spec `function-invocation.md` §4, R8):
/// one URL template with a `{pool}` placeholder, resolved per manifest pool
/// at promote — never at boot, since the pool label is a property of each
/// function's OWN manifest, not of the process.
///
/// [#resolve(String)] is the composition root's only way to build one: a
/// template with no `{pool}` placeholder fails startup, naming
/// `FC_FN_POOL_URL`, the same "impossible to misconfigure by typo" shape as
/// [io.flowcatalyst.platform.function.artifact.Signatures#resolve].
///
/// @param template the raw template, unchanged, always containing `{pool}`
public record PoolUrlTemplate(String template) {

    /// The default when `FC_FN_POOL_URL` is unset (spec §4 R8).
    public static final String DEFAULT = "http://fn-{pool}:8080";

    private static final String PLACEHOLDER = "{pool}";

    public PoolUrlTemplate {
        Objects.requireNonNull(template, "template");
        if (!template.contains(PLACEHOLDER)) {
            throw new IllegalStateException(
                    "FC_FN_POOL_URL must contain the '{pool}' placeholder: '" + template + "'");
        }
    }

    /// The composition root's entry point.
    ///
    /// @throws IllegalStateException `raw` does not contain `{pool}`
    public static PoolUrlTemplate parse(String raw) {
        return new PoolUrlTemplate(raw);
    }

    /// Substitutes `{pool}` with `pool`'s own value — never percent-encoded,
    /// since a [DnsLabel] is already a legal host-name segment.
    public String resolve(DnsLabel pool) {
        Objects.requireNonNull(pool, "pool");
        return template.replace(PLACEHOLDER, pool.value());
    }
}
