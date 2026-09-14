# Using `@flowcatalyst/sdk` with Effect

The SDK ships an Effect-flavored surface alongside the default neverthrow
surface. Reach for it when you want **compile-time invariant guarantees**
on the write path (creating events, dispatch jobs, audit logs) — chiefly:

- A use case that doesn't go through `UnitOfWork.commit()` **does not
  compile**, instead of throwing at runtime.
- Errors are `Data.TaggedError` classes — pattern-match with
  `Effect.catchTag` / `Effect.catchTags`.
- Service wiring is explicit: a use case declares what it needs in `R`,
  and the type system refuses to run it until the layers are provided.

> **The neverthrow surface is unchanged.** External consumers who don't
> install Effect see the same SDK they always have. This document is for
> teams that opt in.

## Installation

Effect is an **optional peer dependency**. Install it alongside the SDK:

```bash
npm install @flowcatalyst/sdk effect
# or
bun add @flowcatalyst/sdk effect
```

> **Effect v4 is currently in beta.** Until v4 ships stable, pin to a beta
> version explicitly — `npm i effect@^4.0.0-beta` (or a specific beta like
> `effect@4.0.0-beta.66`). The SDK's `peerDependencies` range is `^4.0.0`,
> which permits any 4.x once stable.

## When to reach for `/effect`

| Use case                                            | Surface              |
| --------------------------------------------------- | -------------------- |
| Writing events / dispatch jobs / audit logs         | `/effect/usecase` ✅ |
| Authoring `UseCase` classes with strict invariants  | `/effect/usecase` ✅ |
| Calling HTTP CRUD (event types, principals, …)      | root SDK            |
| Sync orchestration, scheduled-job runner            | root SDK            |

The Effect surface intentionally covers only the **invariant-bearing
write path**. HTTP CRUD and read operations stay on the neverthrow client
— rewrapping them adds verbosity without invariant value.

## The seal

`UnitOfWork.commit` is the only function that produces `Sealed<E>`. A use
case's `execute` returns `Effect<Sealed<TEvent>, UseCaseError, UnitOfWork
| ExecutionContext>` — at the type level it must `yield* uow.commit(…)`
because nothing else can build a `Sealed<E>`. The brand symbol is module-
private and not part of the package's `exports` map, so external code
cannot construct one even with a deep import.

```ts
// ❌ Type error: OrderShipped is not assignable to Sealed<OrderShipped>
execute = (cmd) => Effect.succeed(rawEvent);

// ❌ Type error: Property [unique symbol] is missing
execute = (cmd) => Effect.succeed({ event: rawEvent } as Sealed<OrderShipped>);

// ✅ Compiles — only path to Sealed<E>
execute = (cmd) =>
  Effect.gen(function* () {
    const uow = yield* UnitOfWork;
    return yield* uow.commit(event, cmd);
  });
```

Compare with the neverthrow surface's runtime token in
`src/usecase/result.ts:13`: that catches misuse at runtime, after the
faulty code has already shipped. The Effect surface catches it on save.

## Worked example: shipping an order

```ts
// 1. Event
import {
  BaseDomainEvent,
  DomainEvent,
  ExecutionContext,
} from "@flowcatalyst/sdk/effect/usecase";

interface OrderShippedData {
  readonly orderId: string;
  readonly carrierId: string;
  readonly trackingNumber: string;
}

class OrderShipped extends BaseDomainEvent<OrderShippedData> {
  constructor(
    ctx: { /* readonly fields of ExecutionContext */ },
    data: OrderShippedData,
  ) {
    super(
      {
        eventType: "orders:fulfillment:order:shipped",
        specVersion: "1.0",
        source: "orders:fulfillment",
        subject: DomainEvent.subject("fulfillment", "order", data.orderId),
        messageGroup: DomainEvent.messageGroup(
          "fulfillment",
          "order",
          data.orderId,
        ),
      },
      ctx as never, // pass the resolved ExecutionContext data here
      data,
    );
  }
}
```

```ts
// 2. Use case
import { Effect } from "effect";
import {
  ExecutionContext,
  UnitOfWork,
  ValidationError,
  type UseCase,
} from "@flowcatalyst/sdk/effect/usecase";

interface ShipOrderCommand {
  readonly orderId: string;
  readonly carrierId: string;
  readonly trackingNumber: string;
}

class ShipOrderUseCase implements UseCase<ShipOrderCommand, OrderShipped> {
  execute = (command: ShipOrderCommand) =>
    Effect.gen(function* () {
      const ctx = yield* ExecutionContext;
      const uow = yield* UnitOfWork;

      if (!command.trackingNumber) {
        return yield* Effect.fail(
          new ValidationError({
            code: "MISSING_TRACKING",
            message: "trackingNumber is required",
          }),
        );
      }

      const event = new OrderShipped(ctx, command);
      return yield* uow.commit(event, command); // ← only path to Sealed<OrderShipped>
    });
}
```

```ts
// 3. Wire it up at the HTTP boundary
import { Effect, Layer } from "effect";
import { OutboxManager } from "@flowcatalyst/sdk";
import {
  ExecutionContext,
  OutboxUnitOfWork,
  httpStatus,
} from "@flowcatalyst/sdk/effect/usecase";

const outboxManager = new OutboxManager(driver, clientId);
const UoWLive = OutboxUnitOfWork.layer(outboxManager, { auditEnabled: true });

async function shipOrderRoute(req: Request) {
  const command: ShipOrderCommand = await req.json();
  const ctx = {
    executionId: req.headers.get("x-execution-id") ?? crypto.randomUUID(),
    correlationId: req.headers.get("x-correlation-id") ?? crypto.randomUUID(),
    causationId: null,
    principalId: req.headers.get("x-principal-id") ?? "anonymous",
    initiatedAt: new Date(),
  };

  const program = new ShipOrderUseCase().execute(command).pipe(
    Effect.map((sealed) => ({ ok: true, eventType: sealed.event.eventType })),
    Effect.catchTags({
      ValidationError: (e) =>
        Effect.succeed({ ok: false, status: httpStatus(e), error: e }),
      ConcurrencyError: (e) =>
        Effect.succeed({ ok: false, status: httpStatus(e), error: e }),
      InfrastructureError: (e) =>
        Effect.succeed({ ok: false, status: httpStatus(e), error: e }),
      // …handle every tag, or use Effect.catchAll for the rest
    }),
    Effect.provide(UoWLive),
    Effect.provideService(ExecutionContext, ctx),
  );

  return Response.json(await Effect.runPromise(program));
}
```

If you forget `Effect.provide(UoWLive)` or
`Effect.provideService(ExecutionContext, ctx)`, the call to
`Effect.runPromise` is a **compile error** — `R` is not `never`.

## Testing with `TestUnitOfWork`

`TestUnitOfWork.layer(buffer)` records emitted events into an array
without persisting. Use it to assert which events your use case emits:

```ts
import { Effect } from "effect";
import {
  ExecutionContext,
  TestUnitOfWork,
  type DomainEvent,
} from "@flowcatalyst/sdk/effect/usecase";

test("ShipOrderUseCase emits OrderShipped", async () => {
  const recorded: DomainEvent[] = [];

  await Effect.runPromise(
    new ShipOrderUseCase()
      .execute({ orderId: "ord_1", carrierId: "ups", trackingNumber: "X1" })
      .pipe(
        Effect.provide(TestUnitOfWork.layer(recorded)),
        Effect.provideService(ExecutionContext, {
          executionId: "exec-1",
          correlationId: "corr-1",
          causationId: null,
          principalId: "test",
          initiatedAt: new Date(),
        }),
      ),
  );

  expect(recorded.map((e) => e.eventType)).toEqual([
    "orders:fulfillment:order:shipped",
  ]);
});
```

## Error vocabulary

The Effect surface mirrors the neverthrow `UseCaseError` variants as
`Data.TaggedError` classes — same `code` / `message` / `details` shape,
same HTTP-status mapping (`httpStatus(error)`).

| Class                  | Tag                      | HTTP |
| ---------------------- | ------------------------ | ---- |
| `ValidationError`      | `ValidationError`        | 400  |
| `AuthorizationError`   | `AuthorizationError`     | 403  |
| `NotFoundError`        | `NotFoundError`          | 404  |
| `BusinessRuleViolation`| `BusinessRuleViolation`  | 409  |
| `ConcurrencyError`     | `ConcurrencyError`       | 409  |
| `InfrastructureError`  | `InfrastructureError`    | 500  |

Throw them from use cases with `Effect.fail(new ValidationError({ code,
message }))`; pattern-match on them at the handler boundary with
`Effect.catchTag("ValidationError", …)` or `Effect.catchTags({ … })`.

## Coexistence with the neverthrow surface

Both surfaces share the same `DomainEvent` / `BaseDomainEvent` /
`OutboxManager` / `OutboxDriver` under the hood. You can:

- Use the neverthrow `OutboxUnitOfWork` in one app and the Effect one in
  another against the same outbox table — they emit the same row shape.
- Mix surfaces within a single app: HTTP CRUD via the root SDK, write-path
  via `/effect/usecase`.
- Migrate use cases one at a time. The platform doesn't care.

The Effect entry point is `@flowcatalyst/sdk/effect` (re-exports
`usecase`); the typed surface is `@flowcatalyst/sdk/effect/usecase`.
