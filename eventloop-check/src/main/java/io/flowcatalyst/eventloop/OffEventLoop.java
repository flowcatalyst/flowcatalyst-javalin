package io.flowcatalyst.eventloop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks a parameter whose lambda or method reference runs on another thread, never on the
/// caller's. An example is a worker pool's `submit(Runnable)`.
///
/// Loop code may pass blocking work there, and the [EventLoopCheck] does not treat that lambda's
/// body as loop code. The JDK's executors, thread builders, `Thread` constructors,
/// `CompletableFuture`'s `…Async` methods and Vert.x `executeBlocking` are already recognised
/// without it.
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PARAMETER)
public @interface OffEventLoop {
}
