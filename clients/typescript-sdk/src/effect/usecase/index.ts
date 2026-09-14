/**
 * Effect-flavored use-case surface.
 *
 * Provides the same UoW / UseCase pattern as `@flowcatalyst/sdk`, with the
 * seal expressed at the type level: a use case that doesn't go through
 * `UnitOfWork.commit()` (or the other UoW methods) fails to compile.
 *
 * The neverthrow surface in `src/usecase/` continues to ship for external
 * consumers; the two coexist, share `DomainEvent` / `BaseDomainEvent`, and
 * point at the same `OutboxManager` underneath.
 */

export type { Sealed } from "./seal.js";

export {
	ValidationError,
	NotFoundError,
	BusinessRuleViolation,
	ConcurrencyError,
	AuthorizationError,
	InfrastructureError,
	type UseCaseError,
	httpStatus,
} from "./errors.js";

export {
	UnitOfWork,
	ExecutionContext,
	type Aggregate,
} from "./unit-of-work.js";

export type { Command, UseCase } from "./use-case.js";

export * as OutboxUnitOfWork from "./outbox-unit-of-work.js";
export * as TestUnitOfWork from "./test-unit-of-work.js";

// Shared DomainEvent surface — pure data, portable across surfaces.
export {
	DomainEvent,
	BaseDomainEvent,
	type DomainEventBase,
} from "../../usecase/domain-event.js";
