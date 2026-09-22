package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionDomain;
import io.flowcatalyst.platform.function.FunctionDomainRepository;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionRoute;
import io.flowcatalyst.platform.function.FunctionRouteRepository;
import io.flowcatalyst.platform.function.Hostname;
import io.flowcatalyst.platform.function.operations.Access;
import io.flowcatalyst.platform.function.operations.ClaimCommand;
import io.flowcatalyst.platform.function.operations.ClaimFunctionDomain;
import io.flowcatalyst.platform.function.operations.DnsUnavailableException;
import io.flowcatalyst.platform.function.operations.FunctionEvents.DomainClaimed;
import io.flowcatalyst.platform.function.operations.ReleaseCommand;
import io.flowcatalyst.platform.function.operations.ReleaseFunctionDomain;
import io.flowcatalyst.platform.function.operations.VerifyCommand;
import io.flowcatalyst.platform.function.operations.VerifyFunctionDomain;
import io.flowcatalyst.platform.function.TxtResolver;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_DOMAIN_MANAGE;
import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_VIEW;

/// The `/api/function-domains` + `/api/function-routes` surface (spec
/// `function-public-routes.md` §1, §2). A separate class from `FunctionApi`
/// — that file is already large, and domains/routes are their own small,
/// self-contained slice (my call, per the task brief). Java-first, outside
/// the OpenAPI lockfile, listed in `parity/surface.json` (`CONVENTIONS.md`
/// §4, `function-api.md` §0's precedent).
///
/// | Method | Path | Permission |
/// |---|---|---|
/// | POST | `/api/function-domains` | `FUNCTION_DOMAIN_MANAGE` |
/// | GET | `/api/function-domains?clientId=` | `FUNCTION_VIEW` |
/// | GET | `/api/function-domains/{hostname}` | `FUNCTION_VIEW` (S3) |
/// | POST | `/api/function-domains/{hostname}/verify` | `FUNCTION_DOMAIN_MANAGE` |
/// | DELETE | `/api/function-domains/{hostname}` | `FUNCTION_DOMAIN_MANAGE` |
/// | GET | `/api/function-routes?hostname=&address=` | `FUNCTION_VIEW` |
public final class FunctionDomainApi {

    private FunctionDomainApi() {
    }

    /// `devMode` (spec §1: "dev mode taken from Env and passed into the
    /// operation factory") is resolved once at the composition root, never
    /// read from the process environment here. `resolver` is the test-visible
    /// seam (spec §1's deliverable 1): production wires [io.flowcatalyst.platform.function.JndiTxtResolver];
    /// a test hands in a fake.
    public record State(FunctionDomainRepository domains, FunctionRouteRepository routes,
                        FunctionRepository functions, UnitOfWork uow, TxtResolver resolver, boolean devMode) {
        public State {
            Objects.requireNonNull(domains, "domains");
            Objects.requireNonNull(routes, "routes");
            Objects.requireNonNull(functions, "functions");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(resolver, "resolver");
        }
    }

    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        write.post("/api/function-domains", Auth.scoped(ctx -> claim(ctx, s)));
        routes.get("/api/function-domains", Auth.scoped(ctx -> list(ctx, s)));
        routes.get("/api/function-domains/{hostname}", Auth.scoped(ctx -> getDomain(ctx, s)));
        write.post("/api/function-domains/{hostname}/verify", Auth.scoped(ctx -> verify(ctx, s)));
        write.delete("/api/function-domains/{hostname}", Auth.scoped(ctx -> release(ctx, s)));
        routes.get("/api/function-routes", Auth.scoped(ctx -> listRoutes(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    /// spec §1: `POST /api/function-domains` — `{hostname, clientId?}`.
    private static void claim(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_DOMAIN_MANAGE);
        var req = ctx.bodyAsClass(ClaimRequest.class);
        String clientId = blankToNull(req.clientId());
        FunctionOwner owner = FunctionOwner.ofClientId(clientId);
        DomainClaimed event = ClaimFunctionDomain.of(s.domains(), s.devMode())
                .run(s.uow(), new ClaimCommand(owner, req.hostname()), Auth.executionContext());
        FunctionDomain d = s.domains().findById(event.domainId())
                .orElseThrow(() -> HttpError.internal("REPO", "domain claimed but row not found", null));
        ctx.status(201).json(DomainResponse.from(d));
    }

    /// spec §1: `GET /api/function-domains?clientId=` — reach-filtered:
    /// `clientId` selects the exact owner to list (`platform` selects
    /// platform-owned, same wire literal `FunctionOwner#fromWire` uses
    /// elsewhere); a caller who cannot reach that owner sees an empty list
    /// rather than a 403/404 — consistent with spec §2's "never confirm
    /// what exists" reach discipline, applied to a query the caller chose
    /// themselves rather than to a single resource's existence.
    private static void list(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, FUNCTION_VIEW);
        String clientIdParam = queryParam(ctx, "clientId");
        if (clientIdParam == null) {
            throw UseCaseException.validation("CLIENT_ID_REQUIRED", "clientId is required");
        }
        FunctionOwner owner = FunctionOwner.fromWire(clientIdParam);
        List<DomainResponse> out = Checks.canAccessScope(ac, owner.clientIdOrNull())
                ? s.domains().listByOwner(owner).stream().map(DomainResponse::from).toList()
                : List.of();
        ctx.json(out);
    }

    /// spec §1 (S3): `GET /api/function-domains/{hostname}` — reach-or-404
    /// through [Access#byHostname] (already written for verify/release; not
    /// duplicated here), `FUNCTION_VIEW`. An invalid hostname is the same
    /// `400 HOSTNAME_INVALID` the claim route gives, since [Hostname#parse]
    /// is the one parser both routes share.
    private static void getDomain(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_VIEW);
        Hostname hostname = Hostname.parse(ctx.pathParam("hostname"));
        FunctionDomain d = Access.byHostname(s.domains(), hostname, Auth.current());
        ctx.json(DomainResponse.from(d));
    }

    /// spec §1: `POST /api/function-domains/{hostname}/verify`. A
    /// [DnsUnavailableException] from the resolver is caught HERE, at the API
    /// boundary — `503 DNS_UNAVAILABLE`, never surfaced as the generic 500 the
    /// installed error handler would otherwise give an unrecognised
    /// `RuntimeException` (spec §1: "resolver failure ⇒ 503").
    private static void verify(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_DOMAIN_MANAGE);
        String hostname = ctx.pathParam("hostname");
        try {
            VerifyFunctionDomain.Result result = VerifyFunctionDomain.of(s.domains(), s.resolver())
                    .run(s.uow(), new VerifyCommand(hostname), Auth.executionContext());
            ctx.json(DomainResponse.from(result.domain()));
        } catch (DnsUnavailableException e) {
            HttpError.write(ctx, 503, "DNS_UNAVAILABLE", "the DNS resolver is unavailable; try again shortly", Map.of());
        }
    }

    /// spec §1: `DELETE /api/function-domains/{hostname}`.
    private static void release(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_DOMAIN_MANAGE);
        String hostname = ctx.pathParam("hostname");
        ReleaseFunctionDomain.of(s.domains(), s.routes(), s.functions())
                .run(s.uow(), new ReleaseCommand(hostname), Auth.executionContext());
        ctx.status(204);
    }

    /// spec §2: `GET /api/function-routes?hostname=&address=` — one of the
    /// two filters is required; `address` resolves to one function's routes
    /// (reach-or-404, same rule as every by-address function read);
    /// `hostname` lists every route for that hostname, filtered to the
    /// routes whose owning function the caller can reach (a route naming a
    /// function out of reach is simply omitted, never a 403/404 on the whole
    /// list — the same reach-filtering discipline as `FunctionApi#list`).
    private static void listRoutes(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, FUNCTION_VIEW);
        String addressParam = queryParam(ctx, "address");
        String hostnameParam = queryParam(ctx, "hostname");

        List<FunctionRoute> rows;
        if (addressParam != null) {
            var f = functionByAddress(s, FunctionAddress.parse(addressParam), ac);
            rows = s.routes().listByFunction(f.id());
        } else if (hostnameParam != null) {
            Hostname hostname = Hostname.parse(hostnameParam);
            rows = s.routes().listByHostname(hostname).stream()
                    .filter(r -> s.functions().findById(r.functionId())
                            .map(f -> Access.canReach(ac, f)).orElse(false))
                    .toList();
        } else {
            throw UseCaseException.validation("FUNCTION_ROUTE_FILTER_REQUIRED", "hostname or address is required");
        }

        List<FunctionRouteResponse> out = rows.stream()
                .map(r -> new FunctionRouteResponse(r.hostname().value(), r.pathPrefix().value(),
                        s.functions().findById(r.functionId()).map(f -> f.address().render()).orElse(r.functionId())))
                .sorted((a, b) -> {
                    int byHost = a.hostname().compareTo(b.hostname());
                    return byHost != 0 ? byHost : a.pathPrefix().compareTo(b.pathPrefix());
                })
                .toList();
        ctx.json(out);
    }

    private static io.flowcatalyst.platform.function.Function functionByAddress(State s, FunctionAddress address,
            AuthContext ac) {
        var f = s.functions().findByAddress(address).orElseThrow(() -> HttpError.notFound("Function", address.render()));
        if (!Access.canReach(ac, f)) {
            throw HttpError.notFound("Function", address.render());
        }
        return f;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String queryParam(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    // ── Wire DTOs ────────────────────────────────────────────────────────

    /// Body of `POST /api/function-domains` (spec §1).
    public record ClaimRequest(String hostname, String clientId) {
    }

    /// `{id, hostname, owner, verification: {state, record?}}` (spec §1).
    /// `record` is present only while `state` is `PENDING` — a verified
    /// domain's TXT record is no longer interesting and the token itself is
    /// never repeated on a re-read (spec §1: "the TXT record is shown only
    /// while PENDING").
    public record DomainResponse(String id, String hostname, String owner, VerificationView verification,
                                 Instant createdAt) {
        static DomainResponse from(FunctionDomain d) {
            return new DomainResponse(d.id(), d.hostname().value(), d.owner().toWire(),
                    VerificationView.from(d), d.createdAt());
        }
    }

    public record VerificationView(String state, RecordView record) {
        static VerificationView from(FunctionDomain d) {
            return switch (d.verification()) {
                case FunctionDomain.Verification.Pending ignored ->
                        new VerificationView("PENDING", RecordView.of(d.hostname(), d.verificationToken()));
                case FunctionDomain.Verification.Verified ignored -> new VerificationView("VERIFIED", null);
            };
        }
    }

    /// `{type: "TXT", name: "_flowcatalyst.<hostname>", value: "fc-verify=<token>"}` (spec §1).
    public record RecordView(String type, String name, String value) {
        static RecordView of(Hostname hostname, String token) {
            return new RecordView("TXT", "_flowcatalyst." + hostname.value(), "fc-verify=" + token);
        }
    }

    /// One entry of `GET /api/function-routes` and of `DesiredState`'s
    /// top-level `publicRoutes` (spec §2).
    public record FunctionRouteResponse(String hostname, String pathPrefix, String address) {
    }
}
