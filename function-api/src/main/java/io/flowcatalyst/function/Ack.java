package io.flowcatalyst.function;

/// The invocation succeeded. Built only through [Result#ack()]; the one
/// instance is shared since there is nothing to distinguish two acks by.
public record Ack() implements Result {

    static final Ack INSTANCE = new Ack();
}
