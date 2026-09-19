package io.flowcatalyst.platform.function.operations;

import io.flowcatalyst.platform.client.ClientRepository;
import io.flowcatalyst.platform.function.ClientPolicy;
import io.flowcatalyst.platform.function.ClientPolicyRepository;
import io.flowcatalyst.platform.function.FunctionOwner;
import io.flowcatalyst.platform.function.Runtime;
import io.flowcatalyst.platform.function.operations.FunctionEvents.PolicyUpdated;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/// Replaces a client's (or the platform's) signer allow-list and resource
/// ceilings wholesale (spec `function-api.md` §4.3). `Authorize: Public` —
/// the handler gates `requireAnchor` + `FUNCTION_POLICY_MANAGE` (spec §2);
/// this operation only checks that a client owner actually exists.
public final class PutFunctionPolicy {

    private PutFunctionPolicy() {
    }

    public static Operation<PutPolicyCommand, PolicyUpdated> of(ClientPolicyRepository policies, ClientRepository clients) {
        return Operation.<PutPolicyCommand, PolicyUpdated>named("PutFunctionPolicy")
                .validate(cmd -> {
                    Set<List<String>> seen = new HashSet<>();
                    for (var s : cmd.signers()) {
                        if (isBlank(s.issuer()) || isBlank(s.subject())) {
                            throw UseCaseException.validation("SIGNER_INVALID", "issuer and subject are required");
                        }
                        if (s.runtimes().isEmpty()) {
                            throw UseCaseException.validation("RUNTIME_INVALID", "at least one runtime is required");
                        }
                        for (String runtime : s.runtimes()) {
                            Runtime.parseStrict(runtime);
                        }
                        if (!seen.add(List.of(s.issuer(), s.subject()))) {
                            throw UseCaseException.validation("SIGNER_DUPLICATE",
                                    "duplicate signer: issuer '" + s.issuer() + "', subject '" + s.subject() + "'");
                        }
                    }
                    requireValidCeiling(cmd.maxDurationMs(), "maxDurationMs");
                    requireValidCeiling(cmd.maxConcurrency(), "maxConcurrency");
                    requireValidCeiling(cmd.maxWasmMemoryMb(), "maxWasmMemoryMb");
                    requireValidCeiling(cmd.maxDbPoolSize(), "maxDbPoolSize");
                })
                .authorize(Operation.Authorize.publicAccess()) // requireAnchor + FUNCTION_POLICY_MANAGE is the handler's
                .execute((cmd, ec) -> {
                    if (cmd.owner() instanceof FunctionOwner.Client(String clientId)) {
                        clients.findById(clientId).orElseThrow(() -> UseCaseException.resourceNotFound("Client", clientId));
                    }

                    Instant now = Instant.now();
                    Instant createdAt = policies.findByOwner(cmd.owner()).map(ClientPolicy::createdAt).orElse(now);

                    List<ClientPolicy.SignerRule> rules = cmd.signers().stream()
                            .map(s -> new ClientPolicy.SignerRule(s.issuer(), s.subject(),
                                    s.runtimes().stream().map(Runtime::parseStrict).collect(Collectors.toSet())))
                            .toList();

                    ClientPolicy policy = new ClientPolicy(cmd.owner(), rules, cmd.maxDurationMs(), cmd.maxConcurrency(),
                            cmd.maxWasmMemoryMb(), cmd.maxDbPoolSize(), createdAt, now);
                    return Plan.save(policy, policies, PolicyUpdated.of(ec, policy));
                });
    }

    private static void requireValidCeiling(Integer value, String field) {
        if (value != null && value <= 0) {
            throw UseCaseException.validation("CEILING_INVALID", field + " must be a positive integer");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
