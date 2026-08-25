package io.flowcatalyst.router.observability.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.StackTrace;

/// Shared shape for the router's flight-recorder events.
///
/// These exist because of what the router's failures look like. Every
/// message-loss defect this codebase has shipped was **invisible from the
/// outside**: the message left the broker, which is a normal thing to happen,
/// and no counter, log line or alert could distinguish it from a delivery.
/// Counters say how many; logs say what one thread thought at one moment.
/// Neither answers "message X left, and why" after the fact.
///
/// JFR does, and it is the right tool rather than merely an available one:
/// it is always compiled in, costs nothing while disabled, and can be turned
/// on against a running process — which is when these questions get asked.
///
/// [StackTrace] is off throughout. The stack at an ack is the pool's dispatch
/// loop every single time; capturing it would be the most expensive field on
/// the event and the least informative.
@Category({"FlowCatalyst", "Router"})
@StackTrace(false)
abstract class RouterEvent extends Event {
}
