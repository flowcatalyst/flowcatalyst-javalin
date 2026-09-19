package io.flowcatalyst.fnhost.load;

import java.util.Objects;

/// The jar loaded cleanly and its entrypoint instantiated.
public record Loaded(LoadedFunction function) implements LoadOutcome {

    public Loaded {
        Objects.requireNonNull(function, "function");
    }
}
