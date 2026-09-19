package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.FunctionAddressPattern;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.FunctionRepository;
import io.flowcatalyst.platform.function.FunctionStatus;
import io.flowcatalyst.platform.function.operations.Access;
import io.flowcatalyst.platform.function.operations.CreateCommand;
import io.flowcatalyst.platform.function.operations.CreateFunction;
import io.flowcatalyst.platform.function.operations.DeleteCommand;
import io.flowcatalyst.platform.function.operations.DeleteFunction;
import io.flowcatalyst.platform.function.operations.UpdateCommand;
import io.flowcatalyst.platform.function.operations.UpdateFunction;
import io.flowcatalyst.platform.application.ApplicationRepository;
import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.shared.apicommon.OffsetPage;
import io.flowcatalyst.platform.shared.apicommon.PageQuery;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.AuthContext;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.auth.Visibility;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.platform.shared.json.Json;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_MANAGE;
import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_VIEW;

/// The `/api/functions` surface (spec `function-api.md` §4.1, §4.2). Java-first —
/// outside the OpenAPI lockfile (spec §0); listed in `parity/surface.json`
/// instead. A write handler does exactly: coarse permission → command from
/// DTO → `Operation.run` → response. Reads go straight to the repository and
/// apply reach themselves (`CONVENTIONS.md` §2: "reads do not go through use
/// cases").
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/functions` | 200 [OffsetPage] of [FunctionResponse] |
/// | POST | `/api/functions` | 201 [FunctionResponse] |
/// | GET | `/api/functions/{address}` | 200 [FunctionResponse] |
/// | PUT | `/api/functions/{address}` | 204 |
/// | DELETE | `/api/functions/{address}` | 204 |
public final class FunctionApi {

    private FunctionApi() {
    }

    /// The immutable fields a `PUT` body may never carry (spec §4.2), in the
    /// order they are checked — the first one present in the raw body names
    /// the `FUNCTION_IMMUTABLE_FIELD` error.
    private static final List<String> IMMUTABLE_FIELDS =
            List.of("serviceName", "name", "applicationCode", "clientId", "runtime");

    public record State(FunctionRepository repo, ApplicationRepository applications, ClientRepository clients,
                        UnitOfWork uow) {
        public State {
            Objects.requireNonNull(repo, "repo");
            Objects.requireNonNull(applications, "applications");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(uow, "uow");
        }
    }

    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/functions", Auth.scoped(ctx -> list(ctx, s)));
        write.post("/api/functions", Auth.scoped(ctx -> create(ctx, s)));
        routes.get("/api/functions/{address}", Auth.scoped(ctx -> getOne(ctx, s)));
        write.put("/api/functions/{address}", Auth.scoped(ctx -> update(ctx, s)));
        write.delete("/api/functions/{address}", Auth.scoped(ctx -> delete(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void list(Exchange ctx, State s) {
        AuthContext ac = Auth.current();
        Checks.require(ac, FUNCTION_VIEW);
        PageQuery page = PageQuery.from(ctx);
        FunctionRepository.PageFilter filter = listFilter(ctx, ac);
        List<Function> rows = s.repo().findWithFilters(filter, page.pageSize(), (int) page.offset());
        long total = s.repo().countWithFilters(filter);
        ctx.json(OffsetPage.of(rows.stream().map(FunctionResponse::from).toList(), page, total));
    }

    private static void getOne(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_VIEW);
        Function f = functionByAddress(s, parseAddress(ctx.pathParam("address")), Auth.current());
        ctx.json(FunctionResponse.from(f));
    }

    private static void create(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_MANAGE);
        var req = ctx.bodyAsClass(CreateFunctionRequest.class);
        var event = CreateFunction.of(s.repo(), s.applications(), s.clients())
                .run(s.uow(), req.toCommand(), Auth.executionContext());
        Function f = s.repo().findById(event.functionId())
                .orElseThrow(() -> HttpError.internal("REPO", "function created but row not found", null));
        ctx.status(201).json(FunctionResponse.from(f));
    }

    /// `FUNCTION_IMMUTABLE_FIELD` (spec §4.2) is checked against the RAW body
    /// tree, before `UpdateFunctionRequest` binding ever discards an unknown
    /// key silently — that is how someone would come to believe they had
    /// renamed a function (design §10.17).
    private static void update(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_MANAGE);
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        rejectImmutableFields(ctx);
        var req = ctx.bodyAsClass(UpdateFunctionRequest.class);
        UpdateFunction.of(s.repo()).run(s.uow(), req.toCommand(address), Auth.executionContext());
        ctx.status(204);
    }

    private static void delete(Exchange ctx, State s) {
        Checks.require(Auth.current(), FUNCTION_MANAGE);
        FunctionAddress address = parseAddress(ctx.pathParam("address"));
        DeleteFunction.of(s.repo()).run(s.uow(), new DeleteCommand(address), Auth.executionContext());
        ctx.status(204);
    }

    // ── Read-side helpers ──────────────────────────────────────────────────

    /// Load-or-404, out-of-reach-or-404 (spec §2: never 403, which would
    /// confirm the address) — shares [Access#canReach] with the write
    /// operations' [Access#requireReach] so the two paths can never
    /// disagree about what "out of reach" means.
    private static Function functionByAddress(State s, FunctionAddress address, AuthContext ac) {
        Function f = s.repo().findByAddress(address).orElseThrow(() -> HttpError.notFound("Function", address.render()));
        if (!Access.canReach(ac, f)) {
            throw HttpError.notFound("Function", address.render());
        }
        return f;
    }

    /// `{address}` is one path segment that may itself contain dots (spec
    /// §1) — `FunctionAddress.parse` is what turns a two-part value into 400
    /// `ADDRESS_INVALID`, never a 404 (spec §8 P16).
    private static FunctionAddress parseAddress(String raw) {
        return FunctionAddress.parse(raw);
    }

    /// The list filter: the caller's optional narrowing (`address`,
    /// `clientId`, `status`) AND-ed with its mandatory reach (spec §4.2,
    /// `FunctionRepository.PageFilter`'s own doc). `clientId=platform`
    /// selects platform-owned functions via [FunctionOwner#fromWire].
    private static FunctionRepository.PageFilter listFilter(Exchange ctx, AuthContext ac) {
        String addressParam = queryParam(ctx, "address");
        FunctionAddressPattern pattern = addressParam == null ? null : FunctionAddressPattern.parse(addressParam);
        String clientIdParam = queryParam(ctx, "clientId");
        FunctionOwner owner = clientIdParam == null ? null : FunctionOwner.fromWire(clientIdParam);
        String statusParam = queryParam(ctx, "status");
        FunctionStatus status = statusParam == null ? null : parseStatus(statusParam);
        Visibility visibility = ac == null ? new Visibility.Tenants(List.of()) : ac.visibility();
        List<String> applicationIds = ac != null && ac.isApplicationScoped() ? ac.applications() : List.of();
        return new FunctionRepository.PageFilter(pattern, owner, status, visibility, applicationIds);
    }

    private static FunctionStatus parseStatus(String raw) {
        try {
            return FunctionStatus.parse(raw);
        } catch (IllegalArgumentException e) {
            throw UseCaseException.validation("STATUS_INVALID", "status must be ACTIVE or DISABLED");
        }
    }

    /// Absent or empty query parameter → `null`.
    private static String queryParam(Exchange ctx, String name) {
        String v = ctx.queryParam(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private static void rejectImmutableFields(Exchange ctx) {
        JsonNode tree = Json.MAPPER.readTree(ctx.body());
        for (String field : IMMUTABLE_FIELDS) {
            if (tree.has(field)) {
                throw UseCaseException.validation("FUNCTION_IMMUTABLE_FIELD",
                        "field '" + field + "' cannot be changed after creation");
            }
        }
    }

    // ── Wire DTOs ────────────────────────────────────────────────────────

    /// Body of `POST /api/functions` (spec §4.1).
    public record CreateFunctionRequest(String applicationCode, String serviceName, String name, String runtime,
                                        String description, String clientId) {
        public CreateCommand toCommand() {
            return new CreateCommand(applicationCode, serviceName, name, runtime, description, clientId);
        }
    }

    /// Body of `PUT /api/functions/{address}` (spec §4.2): only these two
    /// fields exist on the DTO at all — the immutable ones are rejected
    /// before binding even reaches this record.
    public record UpdateFunctionRequest(String description, String status) {
        public UpdateCommand toCommand(FunctionAddress address) {
            return new UpdateCommand(address, description, status);
        }
    }

    /// The function response (spec §4.4). `live` is always absent in this
    /// slice — no version can exist before B3's publish/promote land.
    public record FunctionResponse(
            String id, String address, String applicationCode, String serviceName, String name,
            String applicationId, String clientId, String runtime, String description, String status,
            Live live, Instant createdAt, Instant updatedAt) {

        public static FunctionResponse from(Function f) {
            return new FunctionResponse(f.id(), f.address().render(), f.address().application().value(),
                    f.address().service().value(), f.address().name().value(), f.applicationId(),
                    f.owner().clientIdOrNull(), f.runtime().wireValue(), f.description(), f.status().name(),
                    null, f.createdAt(), f.updatedAt());
        }

        /// Populated once B3's publish/promote exist; carried on the wire
        /// shape now so the field never needs to be added later.
        public record Live(String version, String versionId) {
        }
    }
}
