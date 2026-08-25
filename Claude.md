# Multi-Agent Translation Guardrails: Go to Java

This file guides Claude Code during the migration of our complex message routing, OIDC identity server, and distributed task scheduling platform from Go to idiomatic Java.

## Multi-Agent Execution Policy
- **Subagent Routing**: The master orchestrator (Opus 5 or Fable 5) MUST offload all high-volume file generation, Java boilerplate creation, and structural refactoring to the `sonnet-5` subagent.
- **Orchestrator Role**: Opus 5 acts purely as the strategic gatekeeper. It analyzes the architectural intent of the Go code, writes strict specifications, and comprehensively reviews all Java code produced by the subagents before it is finalized.
- **Token Efficiency**: Subagents must be kept at `medium` or `low` effort. If a subagent encounters a compilation or logic error, the master orchestrator should step in to debug rather than letting the subagent cycle through infinite reasoning loops.


## Testing Policy: assert that it WORKS, not that it EXISTS

A test that asserts a thing was *created* proves almost nothing. Assert the
**behaviour a caller depends on**, and prefer the assertion that would still
fail if the implementation were quietly wrong.

This is not a style preference. Every one of these passed here, on code that
was broken:

| The test asserted | What it missed |
|---|---|
| the pool objects survive a failover | they were permanently stopped and nacked every message for ever |
| `classify()` returns `Malformed` | nothing ever called `term()`, so the message redelivered for ever |
| `dispatchMode` was populated | it was populated with a value that parsed back to `IMMEDIATE` |
| consumers were started | they were started one at a time, not concurrently as claimed |

The habit that catches these: **after a test passes, break the code on
purpose and confirm that same test fails.** If it still passes, the test is
decorative — fix the test before moving on. Do this for every behaviour the
spec calls load-bearing, and say in the report which assertion pins which
behaviour.

Corollaries worth stating:

- **Assert the observable effect, not the internal call.** "The row is gone
  from the live table" beats "the delete method was invoked".
- **Prefer a counter that must change** over an absence that would hold
  either way — a re-claimed poison message yields no message whether or not
  the bug exists, but `receive_count` differs.
- **A timing claim needs a timing assertion.** "Built concurrently" is not
  proven by a passing test; it is proven by elapsed time being closer to the
  slowest item than to their sum.
- If a behaviour genuinely cannot be pinned cheaply, **say so** and leave it
  unasserted rather than writing something fragile that will be deleted the
  first time it flakes.

## Build hygiene under multiple agents

Concurrent agents are safe for **editing** and unsafe for **building**:
Maven's `target/` is shared state, and two runs clobber each other's
compiled classes. Symptoms are `NoClassDefFoundError` on classes that
plainly exist, and unrelated tests failing in ways that never reproduce
alone.

- An agent's completion notification does **not** mean its build has
  stopped. Check for live Maven processes before trusting a result.
- Prefer `mvn clean test` after any interface change — incremental runs
  reuse stale classes and will hide a signature break.
- Never treat a failure as flaky until it reproduces on an uncontended run.
