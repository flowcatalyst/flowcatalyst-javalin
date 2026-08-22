package io.flowcatalyst.sdk.usecase.op;

import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.TxScopedUnitOfWork;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;

import java.util.Objects;

/// The multi-aggregate sibling of [Operation], for business operations that
/// must write several aggregates (and/or emit several domain events)
/// atomically and return a custom result `R` that is not a single domain
/// event — e.g. provisioning a service account together with its principal
/// and OAuth client.
///
/// It keeps the same enforced phase order — Validate → Authorize → Execute —
/// but `Execute` receives a transaction-scoped unit of work and performs its
/// writes directly through the scoped commit helpers
/// ([TxScopedUnitOfWork#commit], [TxScopedUnitOfWork#commitDelete],
/// [TxScopedUnitOfWork#emitEvent], and [TxScopedUnitOfWork#dbTx()] for the
/// rare raw write). [#run] runs the phases, opens ONE transaction, invokes
/// `Execute` inside it, commits on success and rolls back on any exception.
///
/// Prefer the single-event [Operation] + [Plan] for ordinary CRUD; reach for
/// `TxOperation` only when one logical command genuinely spans multiple
/// aggregates in one transaction.
public record TxOperation<C, R>(
        String name,
        Operation.Validate<C> validate,
        Operation.Authorize<C> authorize,
        Execute<C, R> execute) {

    public TxOperation {
        Objects.requireNonNull(name, "name");
        if (validate == null) {
            validate = Operation.Validate.none();
        }
        Objects.requireNonNull(authorize,
                () -> "tx operation " + name + " has no Authorize phase (set it, or use Authorize.publicAccess())");
        Objects.requireNonNull(execute, () -> "tx operation " + name + " has no Execute phase");
    }

    public static <C, R> Builder<C, R> named(String name) {
        return new Builder<>(name);
    }

    /// Validate → Authorize → one transaction around Execute. Returns
    /// Execute's custom result.
    ///
    /// @throws UseCaseException from any phase, or `TX_BEGIN` / `TX_COMMIT`
    public R run(UnitOfWork uow, C command, ExecutionContext ec) {
        Objects.requireNonNull(uow, "uow");
        Objects.requireNonNull(ec, "ec");
        validate.validate(command);
        authorize.authorize(command);
        return uow.inTransaction(scoped -> execute.execute(scoped, command, ec));
    }

    /// The orchestrated writes, inside the open transaction. Every aggregate
    /// change MUST go through a scoped commit helper so it is written with its
    /// domain event + audit log atomically. Throw to roll back.
    @FunctionalInterface
    public interface Execute<C, R> {
        R execute(TxScopedUnitOfWork scoped, C command, ExecutionContext ec);
    }

    public static final class Builder<C, R> {
        private final String name;
        private Operation.Validate<C> validate = Operation.Validate.none();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder<C, R> validate(Operation.Validate<C> validate) {
            this.validate = Objects.requireNonNull(validate, "validate");
            return this;
        }

        public Authorized<C, R> authorize(Operation.Authorize<C> authorize) {
            return new Authorized<>(name, validate, Objects.requireNonNull(authorize, "authorize"));
        }
    }

    public static final class Authorized<C, R> {
        private final String name;
        private final Operation.Validate<C> validate;
        private final Operation.Authorize<C> authorize;

        private Authorized(String name, Operation.Validate<C> validate, Operation.Authorize<C> authorize) {
            this.name = name;
            this.validate = validate;
            this.authorize = authorize;
        }

        public TxOperation<C, R> execute(Execute<C, R> execute) {
            return new TxOperation<>(name, validate, authorize, execute);
        }
    }
}
