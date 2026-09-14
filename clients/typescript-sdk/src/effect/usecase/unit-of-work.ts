/**
 * `UnitOfWork` and `ExecutionContext` Tags.
 *
 * `UnitOfWork` is the only thing that produces a `Sealed<E>`. A use case's
 * return type is `Effect<Sealed<E>, UseCaseError, UnitOfWork | ExecutionContext>`,
 * which means at the type level it MUST `yield*` the UoW service —
 * `Effect.succeed(rawEvent)` does not type-check.
 */

import { Context, type Effect } from "effect";
import type { DomainEvent } from "../../usecase/domain-event.js";
import type { ConcurrencyError, InfrastructureError } from "./errors.js";
import type { Sealed } from "./seal.js";

export interface Aggregate {
	readonly id: string;
}

export class ExecutionContext extends Context.Service<
	ExecutionContext,
	{
		readonly executionId: string;
		readonly correlationId: string;
		readonly causationId: string | null;
		readonly principalId: string;
		readonly initiatedAt: Date;
	}
>()("@flowcatalyst/ExecutionContext") {}

export class UnitOfWork extends Context.Service<
	UnitOfWork,
	{
		/**
		 * Commit a domain event to the outbox.
		 *
		 * `persist` runs your entity writes before the event is emitted —
		 * wrap both in your own DB transaction (using a tx-aware
		 * `OutboxDriver`) for true atomicity.
		 */
		readonly commit: <E extends DomainEvent>(
			event: E,
			command: unknown,
			persist?: () => Promise<void>,
		) => Effect.Effect<Sealed<E>, ConcurrencyError | InfrastructureError>;

		/** Commit a delete — same semantics as `commit`, signals intent. */
		readonly commitDelete: <E extends DomainEvent>(
			aggregate: Aggregate,
			event: E,
			command: unknown,
			persist?: () => Promise<void>,
		) => Effect.Effect<Sealed<E>, ConcurrencyError | InfrastructureError>;

		/** Emit an event without aggregate changes (e.g. `UserLoggedIn`). */
		readonly emitEvent: <E extends DomainEvent>(
			event: E,
			command: unknown,
		) => Effect.Effect<Sealed<E>, InfrastructureError>;
	}
>()("@flowcatalyst/UnitOfWork") {}
