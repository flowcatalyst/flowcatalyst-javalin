/**
 * `TestUnitOfWork.layer` — in-memory UoW that records emitted events without
 * persisting. Use it in unit tests to assert which events a use case emits.
 *
 * @example
 * ```ts
 * import { Effect } from "effect";
 * import {
 *   ExecutionContext,
 *   TestUnitOfWork,
 * } from "@flowcatalyst/sdk/effect/usecase";
 *
 * const recorded: DomainEvent[] = [];
 * await Effect.runPromise(
 *   new ShipOrderUseCase().execute(command).pipe(
 *     Effect.provide(TestUnitOfWork.layer(recorded)),
 *     Effect.provideService(ExecutionContext, ctx),
 *   ),
 * );
 * expect(recorded.map((e) => e.eventType)).toEqual([
 *   "orders:fulfillment:order:shipped",
 * ]);
 * ```
 */

import { Effect, Layer } from "effect";
import type { DomainEvent } from "../../usecase/domain-event.js";
import { seal } from "./seal.js";
import { UnitOfWork } from "./unit-of-work.js";

export const layer = (recorder: DomainEvent[]): Layer.Layer<UnitOfWork> =>
	Layer.succeed(UnitOfWork, {
		commit: (event, _command, _persist) =>
			Effect.sync(() => {
				recorder.push(event);
				return seal(event);
			}),
		commitDelete: (_aggregate, event, _command, _persist) =>
			Effect.sync(() => {
				recorder.push(event);
				return seal(event);
			}),
		emitEvent: (event, _command) =>
			Effect.sync(() => {
				recorder.push(event);
				return seal(event);
			}),
	});
