/**
 * `UseCase` contract for the Effect surface.
 *
 * `execute` produces `Sealed<TEvent>` — and the only thing that can produce
 * one is `UnitOfWork`. At the type level a use case is forced to route
 * success through the UoW; bypassing it is a compile error.
 */

import type { Effect } from "effect";
import type { DomainEvent } from "../../usecase/domain-event.js";
import type { UseCaseError } from "./errors.js";
import type { Sealed } from "./seal.js";
import type { ExecutionContext, UnitOfWork } from "./unit-of-work.js";

export interface Command {
	// Marker — concrete commands extend with their fields.
}

export interface UseCase<TCommand extends Command, TEvent extends DomainEvent> {
	readonly execute: (
		command: TCommand,
	) => Effect.Effect<
		Sealed<TEvent>,
		UseCaseError,
		UnitOfWork | ExecutionContext
	>;
}
