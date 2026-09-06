package io.flowcatalyst.http;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// Registration and resolution of `exception` mappers, shared by every
/// adapter so exception specificity is the seam's logic and not the
/// framework's: [#resolve] returns the mapper for the **most specific**
/// registered supertype of a thrown exception's class — the exact class
/// first, then its superclasses in order — regardless of the order the
/// mappers were registered in. Adapters route every throwable through one
/// shared instance of this.
public final class ExceptionMappers {

    private final Map<Class<?>, ExceptionHandler<Exception>> mappers = new LinkedHashMap<>();

    /// The cast is safe: [#resolve] only ever hands a registered handler
    /// back a throwable that is an instance of the class it was registered
    /// under (or a subclass matched via that exact class, per resolution
    /// order below).
    @SuppressWarnings("unchecked")
    public <E extends Exception> void register(Class<E> type, ExceptionHandler<E> handler) {
        mappers.put(type, (ExceptionHandler<Exception>) handler);
    }

    /// The mapper for the most specific registered supertype of `t`'s
    /// class, or `Optional.empty()` when nothing matches.
    public Optional<ExceptionHandler<Exception>> resolve(Throwable t) {
        if (t == null) return Optional.empty();
        for (Class<?> c = t.getClass(); c != null; c = c.getSuperclass()) {
            ExceptionHandler<Exception> h = mappers.get(c);
            if (h != null) return Optional.of(h);
        }
        return Optional.empty();
    }
}
