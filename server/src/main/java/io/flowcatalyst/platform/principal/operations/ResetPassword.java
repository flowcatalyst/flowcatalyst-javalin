package io.flowcatalyst.platform.principal.operations;

import io.flowcatalyst.platform.principal.PasswordPolicy;
import io.flowcatalyst.platform.principal.Principal;
import io.flowcatalyst.platform.principal.PrincipalRepository;
import io.flowcatalyst.platform.principal.operations.PrincipalEvents.UserPasswordReset;
import io.flowcatalyst.platform.shared.auth.PasswordHash;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.op.Operation;
import io.flowcatalyst.sdk.usecase.op.Plan;

/// Hashes and sets a user's password and emits [UserPasswordReset] (spec
/// §4). The strict path (default) checks the cheap length floor up front and
/// the identity-aware policy once the principal's email and name are loaded;
/// the relaxed SDK path (`enforcePasswordComplexity = false`) checks only a
/// two-character floor — the caller owns its policy by contract.
///
/// Authorization is deliberately [Operation.Authorize#publicAccess()]: the
/// operation is reached by the admin route (coarse write permission +
/// per-resource scope, both in the handler) **and** by the unauthenticated,
/// token-gated password-reset confirm flow. Each entry point keeps its own gate.
public final class ResetPassword {

    private static final int RELAXED_MIN_LENGTH = 2;

    private ResetPassword() {
    }

    public static Operation<ResetPasswordCommand, UserPasswordReset> of(PrincipalRepository repo) {
        return Operation.<ResetPasswordCommand, UserPasswordReset>named("ResetPassword")
                .validate(cmd -> {
                    UseCaseException.requireNonBlank(cmd.id(), "ID_REQUIRED", "id is required");
                    int min = cmd.strict() ? PasswordPolicy.MIN_LENGTH : RELAXED_MIN_LENGTH;
                    if (cmd.newPassword() == null || cmd.newPassword().length() < min) {
                        throw UseCaseException.validation("PASSWORD_TOO_SHORT", "newPassword must be at least " + min + " characters");
                    }
                })
                .authorize(Operation.Authorize.publicAccess())
                .execute((cmd, ec) -> {
                    Principal p = Access.loadPrincipal(repo, cmd.id());
                    if (!p.isUser()) {
                        throw UseCaseException.conflict("NOT_A_USER", "Password reset only applies to USER principals");
                    }
                    if (cmd.strict()) {
                        PasswordPolicy.check(cmd.newPassword(), p.email(), p.name()).require();
                    }
                    p = p.withPasswordHash(PasswordHash.hash(cmd.newPassword()));
                    return Plan.save(p, repo, UserPasswordReset.of(ec, p));
                });
    }
}
