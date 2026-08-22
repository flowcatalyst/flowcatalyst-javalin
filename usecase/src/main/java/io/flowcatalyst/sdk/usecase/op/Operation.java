package io.flowcatalyst.sdk.usecase.op;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import io.flowcatalyst.sdk.usecase.ExecutionContext;
import io.flowcatalyst.sdk.usecase.UseCaseException;
import io.flowcatalyst.sdk.usecase.jdbc.UnitOfWork;

import java.util.Objects;

/// One business operation expressed as four named phases. Build it with a
/// factory method that captures the operation's dependencies (repositories,
/// services) in the phase lambdas, and run it with [#run].
///
/// ```java
/// static Operation<CreateCommand, EventTypeCreated> createEventType(EventTypeRepository repo) {
///     return Operation.<CreateCommand, EventTypeCreated>named("CreateEventType")
///         .validate(cmd -> {
///             if (cmd.code().isBlank()) throw UseCaseException.validation("CODE_REQUIRED", "Event type code is required");
///         })
///         .authorize(cmd -> Auth.checkScopeAccess(Auth.current(), cmd.clientId()))
///         .execute((cmd, ec) -> {
///             if (repo.findByCode(cmd.code()).isPresent())
///                 throw UseCaseException.conflict("CODE_EXISTS", "Event type with code '" + cmd.code() + "' already exists");
///             var et = EventType.create(cmd.code(), cmd.name(), ec.principalId());
///             var event = new EventTypeCreated(EventMetadata.of(ec, EventTypeEvents.CREATED, EventTypeEvents.SOURCE, et.subject()), et);
///             return Plan.save(et, repo, event);
///         });
/// }
/// ```
///
/// The phases throw [UseCaseException] to fail; `run` stops at the first
/// failure and nothing is written.
///
/// @param name      identifies the operation in diagnostics
/// @param validate  command-shape checks — pure, no DB. Optional (`null` → no-op).
/// @param authorize resource-level access check. REQUIRED: a real check, or
///                  [Authorize#publicAccess()] to declare the operation
///                  intentionally open. Authorization data (the authenticated
///                  principal) is read from wherever the transport bound it —
///                  a `ScopedValue` in the platform.
/// @param execute   invariant checks (loads, business rules), build the event,
///                  return the [Plan]. REQUIRED. Returning a Plan is the ONLY
///                  way an operation reaches the database.
public record Operation<C, E extends DomainEvent>(
        String name,
        Validate<C> validate,
        Authorize<C> authorize,
        Execute<C, E> execute) {

    public Operation {
        Objects.requireNonNull(name, "name");
        if (validate == null) {
            validate = Validate.none();
        }
        Objects.requireNonNull(authorize,
                () -> "operation " + name + " has no Authorize phase (set it, or use Authorize.publicAccess())");
        Objects.requireNonNull(execute, () -> "operation " + name + " has no Execute phase");
    }

    /// Staged builder: `named(...)[.validate(...)].authorize(...).execute(...)`.
    /// The stages make "Authorize before Execute, both present" a compile-time
    /// fact — the Java equivalent of the Go `uowseal` analyzer.
    public static <C, E extends DomainEvent> Builder<C, E> named(String name) {
        return new Builder<>(name);
    }

    /// Executes the phases in order — Validate, Authorize, Execute — stopping
    /// at the first failure, then applies the returned [Plan] in one
    /// transaction (aggregate change + domain event + audit log, atomically)
    /// and returns the committed event.
    ///
    /// @throws UseCaseException from any phase, or wrapping any infrastructure
    ///                          failure (`TX_BEGIN`, `PERSIST`, `EVENT_WRITE`,
    ///                          `AUDIT_WRITE`, `TX_COMMIT`, …)
    public E run(UnitOfWork uow, C command, ExecutionContext ec) {
        Objects.requireNonNull(uow, "uow");
        Objects.requireNonNull(ec, "ec");
        validate.validate(command);
        authorize.authorize(command);
        Plan<E> plan = execute.execute(command, ec);
        if (plan == null) {
            throw UseCaseException.internal("USECASE_NIL_PLAN",
                    "operation " + name + " Execute returned a null Plan without an error", null);
        }
        return PlanApplier.apply(plan, uow, command);
    }

    /// Command-shape validation: presence, format, length, patterns — anything
    /// that does not require loading data. Throw a validation-kind
    /// [UseCaseException] on failure.
    @FunctionalInterface
    public interface Validate<C> {
        void validate(C command);

        /// No validation — for commands with nothing to check.
        static <C> Validate<C> none() {
            return _ -> { };
        }
    }

    /// Resource-level access check: ownership, client scope, state-based
    /// permission. Throw an authorization-kind [UseCaseException] to refuse.
    @FunctionalInterface
    public interface Authorize<C> {
        void authorize(C command);

        /// The explicit value for operations that are intentionally open — no
        /// resource-level authorization beyond whatever the transport already
        /// enforced. "Deliberately open" should be a visible decision in the
        /// code, not an omission.
        static <C> Authorize<C> publicAccess() {
            return _ -> { };
        }
    }

    /// Invariant checks and the planned change.
    @FunctionalInterface
    public interface Execute<C, E extends DomainEvent> {
        Plan<E> execute(C command, ExecutionContext ec);
    }

    /// First stage: optional `validate`, then the required `authorize`.
    public static final class Builder<C, E extends DomainEvent> {
        private final String name;
        private Validate<C> validate = Validate.none();

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "name");
        }

        public Builder<C, E> validate(Validate<C> validate) {
            this.validate = Objects.requireNonNull(validate, "validate");
            return this;
        }

        public Authorized<C, E> authorize(Authorize<C> authorize) {
            return new Authorized<>(name, validate, Objects.requireNonNull(authorize, "authorize"));
        }
    }

    /// Second stage: only `execute` remains, and it yields the operation.
    public static final class Authorized<C, E extends DomainEvent> {
        private final String name;
        private final Validate<C> validate;
        private final Authorize<C> authorize;

        private Authorized(String name, Validate<C> validate, Authorize<C> authorize) {
            this.name = name;
            this.validate = validate;
            this.authorize = authorize;
        }

        public Operation<C, E> execute(Execute<C, E> execute) {
            return new Operation<>(name, validate, authorize, execute);
        }
    }
}
