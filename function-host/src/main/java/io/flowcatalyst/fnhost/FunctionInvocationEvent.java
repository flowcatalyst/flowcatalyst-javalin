package io.flowcatalyst.fnhost;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/// One function invocation, from the moment it is entered (permits + load
/// succeeded, the worker thread is about to start) to the moment the caller's
/// outcome is decided (`docs/spec/function-host-process.md` §2). Mirrors the
/// platform's own `.../jfr` events (e.g.
/// `io.flowcatalyst.platform.dispatchjob.jfr.DispatchProcessedEvent`): always
/// compiled in, free while disabled, `shouldCommit()` respected before any
/// field is written. [io.flowcatalyst.fnhost.http.FnHttpServer] commits this
/// once per entered invocation — never for a host refusal (busy,
/// unauthorized, unavailable, not_found), which never enters the function at
/// all.
@Name("io.flowcatalyst.fnhost.FunctionInvocation")
@Label("Function Invocation")
@Description("One function invocation, from entry to its caller-visible outcome")
@Category({"FlowCatalyst", "FunctionHost"})
@StackTrace(false)
public final class FunctionInvocationEvent extends Event {

    @Label("Address")
    public String address;

    @Label("Version")
    public int version;

    @Label("Invocation ID")
    public String invocationId;

    /// The HTTP status the caller received: the function's own `Result`
    /// status for `ok`/`client_error`/`retry`/`error`, or the host's own
    /// (`500`/`504`) for a throw/null result/timeout.
    @Label("Status")
    public int status;

    /// `ok`, `client_error`, `retry`, `error`, or `timeout` — spec §2's
    /// outcome vocabulary, restricted to the outcomes an ENTERED invocation
    /// can reach (never `busy`/`unauthorized`/`unavailable`/`not_found`,
    /// which are refused before entry and never get this event at all).
    @Label("Outcome")
    public String outcome;
}
