package io.flowcatalyst.platform.ingest.api;

import io.flowcatalyst.platform.application.Application;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.subscription.Subscription;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/// Confines an application-scoped caller (an application service account:
/// [AuthContext#isApplicationScoped]) to the applications it is bound to on the
/// SDK ingest routes (Go 9c9986a). A caller holding all-applications access is
/// never restricted, and never causes a lookup. Memoises code to application id
/// (and subscription id to its application code) for the life of one request,
/// so a 1000-item batch costs one lookup per DISTINCT code.
///
/// Not thread-safe; build one per request.
final class IngestAppScope {

    static final String EVENT_REFUSED = "event type belongs to an application this credential cannot ingest for";
    static final String JOB_CODE_REFUSED = "dispatch job code belongs to an application this credential cannot ingest for";
    static final String JOB_SUBSCRIPTION_REFUSED = "subscription belongs to an application this credential cannot ingest for";
    static final String AUDIT_APP_REQUIRED = "applicationCode is required for an application-scoped credential";
    static final String AUDIT_REFUSED = "audit entry belongs to an application this credential cannot write for";

    private final Function<String, Optional<Application>> applications;
    private final Function<String, Optional<Subscription>> subscriptions;
    private final Map<String, Optional<String>> appIdByCode = new HashMap<>();
    private final Map<String, String> appCodeBySubscription = new HashMap<>();

    IngestAppScope(Function<String, Optional<Application>> applications,
                   Function<String, Optional<Subscription>> subscriptions) {
        this.applications = applications;
        this.subscriptions = subscriptions;
    }

    /// The application code of a `{application}:...` code (an event type, a
    /// dispatch-job code): everything before the first colon, or the whole
    /// string when there is none (Go `FirstSegment`).
    static String firstSegment(String code) {
        if (code == null) return "";
        String c = code.strip();
        int colon = c.indexOf(':');
        return colon < 0 ? c : c.substring(0, colon);
    }

    /// Whether `ac` may act for the application with `code`: always for a
    /// non-scoped caller (no lookup); for a scoped caller only when the code
    /// names an existing application it can access. An unknown or empty code is
    /// refused, indistinguishable from an inaccessible one.
    boolean canAccessCode(AuthContext ac, String code) {
        if (!ac.isApplicationScoped()) return true;
        if (code == null || code.isEmpty()) return false;
        var id = appIdByCode.computeIfAbsent(code, c -> applications.apply(c).map(Application::id));
        return id.isPresent() && ac.canAccessApplication(id.get());
    }

    /// Why a dispatch job must be refused for `ac`, or `null` when it passes.
    /// Both must hold: (1) the application named by the first segment of the
    /// job's `code` exists and is accessible; (2) if the job carries a
    /// `subscriptionId`, that subscription exists, has an application code, and
    /// that application is accessible (a job cannot be routed through another
    /// application's subscription). No-op for a non-scoped caller.
    String jobRefusal(AuthContext ac, String code, String subscriptionId) {
        if (!ac.isApplicationScoped()) return null;
        if (!canAccessCode(ac, firstSegment(code))) return JOB_CODE_REFUSED;
        if (subscriptionId == null || subscriptionId.isEmpty()) return null;
        String appCode = appCodeBySubscription.computeIfAbsent(subscriptionId,
                id -> subscriptions.apply(id).map(Subscription::applicationCode).orElse(""));
        return canAccessCode(ac, appCode) ? null : JOB_SUBSCRIPTION_REFUSED;
    }
}
