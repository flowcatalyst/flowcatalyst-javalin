package io.flowcatalyst.function;

import java.lang.System.Logger;
import java.time.Clock;
import javax.sql.DataSource;

/// Everything a function may reach outside its own invocation. The host
/// builds one instance per loaded version and passes it to [Function#init],
/// [Function#handle] and [Function#stop].
///
/// `logger`, `config`, `secrets`, `dataSource`, `http`, `events` and `clock`
/// are exactly the services design `docs/function-runner-plan.md` §5 lists;
/// [HttpCaller] and [Events] are interfaces with value records here, but
/// their real implementations — along with `config`, `secrets` and
/// `dataSource` — are slice D4. In this slice (D1) the host supplies a
/// `FunctionContext` whose unimplemented services throw
/// `UnsupportedOperationException` naming D4.
public interface FunctionContext {

    /// A logger carrying the host's MDC keys (function address, version,
    /// invocation id).
    Logger logger();

    /// Manifest-declared config keys, resolved from env/secrets by the host.
    Config config();

    /// Manifest-declared secret references, resolved by the host. Values
    /// never appear in a `toString`.
    Secrets secrets();

    /// The pool for the manifest-declared database named `name`.
    DataSource dataSource(String name);

    /// The host-mediated outbound HTTP client.
    HttpCaller http();

    /// Emits platform events on this function's behalf.
    Events events();

    /// The host's clock — never `Clock.systemUTC()` read directly, so a test
    /// can fix time.
    Clock clock();

    /// The address of the function this context was built for.
    FunctionAddress address();

    /// The loaded version of the function this context was built for.
    int version();
}
