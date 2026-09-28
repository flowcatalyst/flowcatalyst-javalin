package io.flowcatalyst.eventloop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks code the [EventLoopCheck] cannot see is run on a Vert.x event loop.
///
/// - **On a method:** the method runs on the loop. Use it when Vert.x reaches the method some way
///   the check cannot follow, such as a callback registered through another library.
/// - **On a parameter:** a lambda or method reference passed for that parameter runs on the loop.
///   An example is a helper that hands its `Runnable` to `Context.runOnContext` through a variable.
///
/// Any Vert.x callback is already recognised without it: a lambda whose target type is a Vert.x
/// type, a lambda passed to a Vert.x method, and a method overriding a Vert.x method.
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER})
public @interface OnEventLoop {
}
