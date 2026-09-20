package io.flowcatalyst.example.hello;

/// The `data` payload of the `hello:greeting:sent` event [HelloFunction] emits.
/// Serialised by Jackson through reflection only — nothing in this jar calls
/// {@link #name()}/{@link #greeting()} directly, which is exactly why
/// `proguard.conf` must keep this class's members explicitly (a shrink-only
/// pass still removes members nothing in the analysed call graph reaches).
public record GreetingPayload(String name, String greeting) {
}
