package io.flowcatalyst.platform.bff.api;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.client.ClientStatus;
import io.flowcatalyst.platform.eventtype.EventTypeRepository;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Routes;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/// The two most-called dropdown endpoints (bff spec §3), pulling every row
/// from the underlying repository and filtering/sorting in memory (cheap
/// given typical row counts). No coarse permission gate — same as Go: a
/// missing [AuthContext] simply sees no accessible clients.
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/bff/filter-options/clients` | 200 [ClientOptionsResponse] |
/// | GET | `/bff/event-types/filters/applications` | 200 [OptionsResponse] |
public final class FilterOptionsBff {

    private FilterOptionsBff() {
    }

    public record State(ClientRepository clients, EventTypeRepository eventTypes) {
        public State {
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(eventTypes, "eventTypes");
        }
    }

    public static void register(Routes routes, State s) {
        routes.get("/bff/filter-options/clients", Auth.scoped(ctx -> clientOptions(ctx, s)));
        routes.get("/bff/event-types/filters/applications", Auth.scoped(ctx -> eventTypeApplications(ctx, s)));
    }

    /// Every ACTIVE client the caller can access (anchor: all), sorted by label.
    private static void clientOptions(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        List<FilterOption> options = s.clients().findAll().stream()
                .filter(c -> c.status() == ClientStatus.ACTIVE)
                .filter(c -> ac != null && (ac.isAnchor() || ac.canAccessClient(c.id())))
                .map(c -> new FilterOption(c.id(), c.name()))
                .sorted(Comparator.comparing(FilterOption::label))
                .toList();
        ctx.json(new ClientOptionsResponse(options));
    }

    /// The distinct first code segments of every event type, sorted.
    private static void eventTypeApplications(Exchange ctx, State s) {
        ctx.json(new OptionsResponse(s.eventTypes().distinctApplications()));
    }

    // ── Wire DTOs ────────────────────────────────────────────────────────

    /// `{value, label}` — the canonical filter-dropdown pair.
    public record FilterOption(String value, String label) {
    }

    /// `{"clients": [...]}` (SPA `ClientFilterOptions`).
    public record ClientOptionsResponse(List<FilterOption> clients) {
        public ClientOptionsResponse {
            clients = clients == null ? List.of() : List.copyOf(clients);
        }
    }

    /// `{"options": [...]}` (SPA `BffFilterOptionsResponse`).
    public record OptionsResponse(List<String> options) {
        public OptionsResponse {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }
}
