package io.flowcatalyst.platform.eventtype.api;

import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.eventtype.EventType;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// The read-side rules of every event-type read — `GET /api/event-types` and
/// the SPA's `/bff/event-types` reads and filter options (bugs 1, 2,
/// `docs/go-mirror/2026-10-10-platform-bug-fixes.md`). The read permission
/// itself ([Checks#requireEventTypeReadAny]) is the caller's to check first;
/// then a client-scoped event type needs access to its client, and a caller
/// admitted only by the application-service view (an SDK service account)
/// sees only the event types of the applications it is bound to.
public final class EventTypeReads {

    private EventTypeReads() {
    }

    /// The rows the caller may read, in their order.
    public static List<EventType> visibleTo(AuthContext ac, ApplicationRepository apps, List<EventType> rows) {
        List<EventType> visible = Checks.filterClientScoped(ac, rows, EventType::clientId);
        if (Checks.canReadAllEventTypes(ac)) return visible;
        Map<String, Boolean> seen = new HashMap<>();
        return visible.stream().filter(et -> appAccess(apps, ac, et.application(), seen)).toList();
    }

    /// `et`, or 403 `FORBIDDEN` "No access to this event type" when the caller
    /// may not read it.
    public static EventType readable(AuthContext ac, ApplicationRepository apps, EventType et) {
        if (et.clientId() != null && !ac.canAccessClient(et.clientId())) {
            throw HttpError.forbidden("No access to this event type");
        }
        if (!Checks.canReadAllEventTypes(ac)) requireAppAccess(ac, apps, et);
        return et;
    }

    /// Refuses an application-confined principal an event type that belongs to
    /// an application it is not bound to.
    static void requireAppAccess(AuthContext ac, ApplicationRepository apps, EventType et) {
        if (!appAccess(apps, ac, et.application(), new HashMap<>())) {
            throw HttpError.forbidden("No access to this event type");
        }
    }

    /// The rows whose application, subdomain and aggregate are each one of the
    /// chosen values; an empty choice does not filter. The SPA's facets are
    /// multi-selects sent as repeated query parameters (bug 2).
    public static List<EventType> matchAny(List<EventType> rows, List<String> applications, List<String> subdomains,
                                           List<String> aggregates) {
        return rows.stream()
                .filter(et -> in(applications, et.application()) && in(subdomains, et.subdomain())
                        && in(aggregates, et.aggregate()))
                .toList();
    }

    private static boolean in(List<String> chosen, String value) {
        return chosen.isEmpty() || chosen.contains(value);
    }

    /// Whether the principal is bound to the application with the given code.
    /// Results are remembered in `seen` for the life of one request, so a list
    /// resolves each application once. An unknown application is not accessible.
    private static boolean appAccess(ApplicationRepository apps, AuthContext ac, String code, Map<String, Boolean> seen) {
        return seen.computeIfAbsent(code, c -> apps.findByCode(c).map(a -> ac.canAccessApplication(a.id())).orElse(false));
    }
}
