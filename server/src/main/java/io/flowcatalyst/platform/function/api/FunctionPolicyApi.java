package io.flowcatalyst.platform.function.api;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.ClientCeilings;
import io.flowcatalyst.platform.function.ClientPolicy;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.FunctionLimits;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.operations.PutFunctionPolicy;
import io.flowcatalyst.platform.function.operations.PutPolicyCommand;
import io.flowcatalyst.platform.shared.auth.Auth;
import io.flowcatalyst.platform.shared.auth.Checks;
import io.flowcatalyst.platform.shared.httperror.HttpError;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;
import io.flowcatalyst.http.Exchange;
import io.flowcatalyst.http.Group;
import io.flowcatalyst.http.Routes;

import java.util.List;
import java.util.Objects;

import static io.flowcatalyst.platform.shared.auth.Permission.FUNCTION_POLICY_MANAGE;

/// `/api/function-policies/{owner}` (spec `function-api.md` §4.3). Java-first,
/// outside the lockfile (spec §0). `{owner}` is a client id or the literal
/// `platform`. Both routes: `requireAnchor` + `FUNCTION_POLICY_MANAGE` (spec
/// §2) — a coarse handler-level gate, no per-resource reach (a policy has no
/// narrower audience than its owner).
///
/// | Method | Path | Status |
/// |---|---|---|
/// | GET | `/api/function-policies/{owner}` | 200 [PolicyResponse] |
/// | PUT | `/api/function-policies/{owner}` | 200 [PolicyResponse] |
public final class FunctionPolicyApi {

    private FunctionPolicyApi() {
    }

    public record State(ClientPolicyRepository policies, ClientRepository clients, UnitOfWork uow,
                        FunctionLimits defaults) {
        public State {
            Objects.requireNonNull(policies, "policies");
            Objects.requireNonNull(clients, "clients");
            Objects.requireNonNull(uow, "uow");
            Objects.requireNonNull(defaults, "defaults");
        }
    }

    public static void register(Routes routes, State s) {
        Routes write = routes.in(Group.API_WRITE);
        routes.get("/api/function-policies/{owner}", Auth.scoped(ctx -> get(ctx, s)));
        write.put("/api/function-policies/{owner}", Auth.scoped(ctx -> put(ctx, s)));
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    private static void get(Exchange ctx, State s) {
        gate();
        FunctionOwner owner = ownerFromPath(ctx);
        ctx.json(s.policies().findByOwner(owner)
                .map(p -> PolicyResponse.from(p, s.defaults()))
                .orElseGet(() -> PolicyResponse.effectiveDefault(owner, s.defaults())));
    }

    private static void put(Exchange ctx, State s) {
        gate();
        FunctionOwner owner = ownerFromPath(ctx);
        var req = ctx.bodyAsClass(PutPolicyRequest.class);
        PutFunctionPolicy.of(s.policies(), s.clients()).run(s.uow(), req.toCommand(owner), Auth.executionContext());
        ClientPolicy saved = s.policies().findByOwner(owner)
                .orElseThrow(() -> HttpError.internal("REPO", "policy written but row not found", null));
        ctx.json(PolicyResponse.from(saved, s.defaults()));
    }

    private static void gate() {
        Checks.requireAnchor(Auth.current());
        Checks.require(Auth.current(), FUNCTION_POLICY_MANAGE);
    }

    private static FunctionOwner ownerFromPath(Exchange ctx) {
        String raw = ctx.pathParam("owner");
        if (raw == null || raw.isBlank()) {
            throw UseCaseException.validation("OWNER_REQUIRED", "owner is required");
        }
        return FunctionOwner.fromWire(raw);
    }

    // ── Wire DTOs ────────────────────────────────────────────────────────

    /// Body of `PUT /api/function-policies/{owner}` (spec §4.3): a full
    /// replacement.
    public record PutPolicyRequest(List<SignerRequest> signers, CeilingsRequest ceilings) {
        public PutPolicyCommand toCommand(FunctionOwner owner) {
            List<PutPolicyCommand.SignerInput> signerInputs = signers == null ? List.of()
                    : signers.stream().map(sg -> new PutPolicyCommand.SignerInput(sg.issuer(), sg.subject(), sg.runtimes())).toList();
            return new PutPolicyCommand(owner, signerInputs,
                    ceilings == null ? null : ceilings.maxDurationMs(),
                    ceilings == null ? null : ceilings.maxConcurrency(),
                    ceilings == null ? null : ceilings.maxWasmMemoryMb(),
                    ceilings == null ? null : ceilings.maxDbPoolSize());
        }

        public record SignerRequest(String issuer, String subject, List<String> runtimes) {
        }

        public record CeilingsRequest(Integer maxDurationMs, Integer maxConcurrency, Integer maxWasmMemoryMb,
                                      Integer maxDbPoolSize) {
        }
    }

    /// `{owner, signers, ceilings, stored}` (spec §4.3): `ceilings` is
    /// always the EFFECTIVE resolved values, whether or not a row exists.
    public record PolicyResponse(String owner, List<SignerResponse> signers, CeilingsResponse ceilings, boolean stored) {

        static PolicyResponse from(ClientPolicy p, FunctionLimits defaults) {
            return new PolicyResponse(p.owner().toWire(),
                    p.signers().stream()
                            .map(r -> new SignerResponse(r.issuer(), r.subject(),
                                    r.runtimes().stream().map(Runtime::wireValue).toList()))
                            .toList(),
                    CeilingsResponse.from(p.ceilings(defaults)), true);
        }

        /// No stored row — the effective default (spec §4.3): `signers: []`,
        /// `ceilings` = the platform defaults, `stored: false`.
        static PolicyResponse effectiveDefault(FunctionOwner owner, FunctionLimits defaults) {
            return new PolicyResponse(owner.toWire(), List.of(), CeilingsResponse.from(ClientCeilings.of(defaults)), false);
        }

        public record SignerResponse(String issuer, String subject, List<String> runtimes) {
        }

        public record CeilingsResponse(int maxDurationMs, int maxConcurrency, int maxWasmMemoryMb, int maxDbPoolSize) {
            static CeilingsResponse from(ClientCeilings c) {
                return new CeilingsResponse(c.maxDurationMs(), c.maxConcurrency(), c.wasmMemoryMb(), c.dbPoolSize());
            }
        }
    }
}
