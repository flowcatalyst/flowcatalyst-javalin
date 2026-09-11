# Brief — router: a queue that does not exist yet is not consumed, and not alarmed about

Branch `router-missing-queue` (checked out; **do not commit, do not switch
branches**). Owner ruling 2026-09-11, context in `docs/backlog.md` ("a queue
that does not exist yet…"): Integral's control plane lists every
subscription's SQS queue, but creates each queue lazily on its first send, so
listed-but-absent queues are **normal**. Today Java polls them every second,
logs a WARN each time, and raises a `CONNECTION` warning (a Teams card) per
queue per restart.

Read `CLAUDE.md` (testing policy, mutation check) and `CONVENTIONS.md` §8
(sealed outcomes, not exceptions, for expected results). Code:
`router/queue/Consumer.java` (`PollResult`), `router/queue/sqs/SqsQueue.java`,
`router/queue/QueueFactory.java`, `router/manager/RouterManager.java`
(`applyConsumers`, `stopConsumer`, the `ConsumerFactory` it takes),
`router/manager/ConsumerLoop.java` (the poll-failure branch, `pollFailing`),
`router/manager/ConsumerSupervisor.java`, and whatever the stall detector and
the monitoring/health endpoints read about consumers.

Build: `export JAVA_HOME=$(mise where java)`;
`mvn -q -B -pl server -am test -Dtest='<pattern>' -Dsurefire.failIfNoSpecifiedTests=false`,
one Maven run at a time, in the foreground. Never `mvn install`. If an error
doesn't yield to one clear fix, stop and report with your diagnosis.

## Behaviour (the owner's ruling)

1. **Check before starting.** Building a consumer for an SQS queue first
   checks the queue exists (`GetQueueAttributes` on its URL, or `GetQueueUrl`;
   `QueueDoesNotExistException` ⇒ missing). Missing ⇒ **no consumer is
   started**. This is a third build outcome, distinct from "built" and
   "failed". Do not count it as a failure, and do not raise any warning that
   a failed build raises today. Log **one INFO** line: queue name, "does not
   exist yet; not consuming it; rechecked at the next config sync".
2. **Recheck on every config apply.** A missing queue holds no entry in the
   manager's consumer maps, so the next config sync, which re-applies even an
   unchanged config, tries it again. When it now exists, it starts normally
   and one INFO line says so. While it stays missing, repeated checks do
   **not** log again: one INFO per missing streak, tracked per queue name and
   cleared when it appears or leaves the config.
3. **Disappears while running.** A poll that fails with
   `QueueDoesNotExistException` returns a new sealed
   `PollResult.QueueMissing` (not an exception). `ConsumerLoop` ends that
   loop, and the manager detaches the consumer exactly as `stopConsumer` does,
   so the next config apply rechecks it. Same single INFO line, and no
   `CONNECTION` warning.
4. **Unknown ⇒ start as today.** If the existence check itself fails for any
   other reason (network, throttling, auth), start the consumer as today. The
   existing poll-failure path and its `CONNECTION` warning then apply. A
   transient AWS error must never silently stop consumption.
5. **Other backends unchanged.** Postgres and NATS queues are always treated
   as existing.
6. **Not a health problem.** A missing queue must not mark the router
   unhealthy, trip the stall detector, or count as a failed consumer in
   monitoring. If the monitoring API lists queues, show it as not-created,
   not failed.
7. Record the Go difference in a code comment and in
   `docs/spec/router-env.md`'s notes (or `docs/spec/router.md` if that is where
   consumer lifecycle lives): Go polls and WARNs every second, indefinitely.

## Tests (assert effects, not calls)

- Config lists a missing SQS queue, with a scripted/fake SQS client: no
  consumer running for it (no poll ever issued — count the `ReceiveMessage`
  calls), no warning raised, exactly one INFO across three config applies.
  The queue then exists and the next apply starts it: `ReceiveMessage` calls
  begin, and one INFO says so.
- Running consumer, the queue deleted: the poll returns `QueueMissing`, the
  loop stops, the consumer is detached, no `CONNECTION` warning, and the next
  apply rechecks.
- The existence check throws a non-missing error: the consumer starts.
- A genuine poll connection error still raises `CONNECTION`, as today
  (regression guard).
- Health and the stall detector with one missing queue: healthy, no stall.

Mutation check (list the assertion that kills each): treat missing as
failed (the warning comes back); skip the existence check (polls are issued);
log INFO on every recheck; stop the consumer on an unknown check error; make
`QueueMissing` raise `CONNECTION`.

Then the full server suite once (`mvn -q -B -pl server -am test`). Report:
files, the behaviour table as built, the mutation table, the full-suite
result, anything the design made awkward.
