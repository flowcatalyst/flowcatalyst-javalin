package io.flowcatalyst.router.pool;

/// The HTTP version a mediation target actually spoke, independent of which
/// [MediationTransport] made the call (`docs/spec/router-h2.md` §3/§5).
///
/// Kept as its own tiny enum, rather than reusing
/// `java.net.http.HttpClient.Version` or `io.vertx.core.http.HttpVersion`,
/// because [PoolMetrics] and [io.flowcatalyst.router.observability.PoolMetricsCollector]
/// are shared by both transports and neither client library's own version
/// type belongs on that seam.
public enum HttpVersion {
    HTTP_1_1,
    HTTP_2
}
