package io.flowcatalyst.function;

/// The host-mediated outbound HTTP client a function calls through — never
/// the JDK's own client directly, so the host can apply the manifest's host
/// allowlist and its own timeouts. The real implementation is slice D4; in
/// D1 [FunctionContext#http()] returns one that throws
/// `UnsupportedOperationException`.
public interface HttpCaller {

    /// Makes one outbound call and returns its reply.
    HttpReply send(HttpCall call) throws Exception;
}
