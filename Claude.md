# Multi-Agent Translation Guardrails: Go to Java

This file guides Claude Code during the migration of our complex message routing, OIDC identity server, and distributed task scheduling platform from Go to idiomatic Java.

## Multi-Agent Execution Policy
- **Routing by kind of work, not by size** (owner, 2026-09-24). The orchestrator is the parent
  model (Opus or Fable).
  - **The parent model does the work itself, or delegates it to an agent on the parent model,**
    for: security-sensitive code (auth, secrets, redaction, signatures, permissions); concurrency
    and transactional code (locks, permits, deadlines, reconcilers, plan/apply); new abstractions
    and seams other code will build on; and long, complicated work whose items are interrelated
    enough that a local change can break a distant invariant.
  - **`sonnet` subagents** do well-specified volume work: ports and mechanical refactors, Java
    boilerplate, fixtures and test volume, UI that follows an existing pattern, doc sweeps.
  - When unsure, it is the parent model's.
- **Briefs forbid out-of-scope changes.** Every subagent brief says: change nothing outside the
  files and behaviour named; anything you would have fixed or improved elsewhere, report instead
  of changing. (A Sonnet coder once "fixed" a deliberate NUL separator into a space and would have
  caused false duplicate errors.)
- **Orchestrator Role**: the parent model writes strict specifications, and reviews every line a
  subagent produces before it is merged: reads the code, re-runs the load-bearing mutants itself,
  and runs the full suite.
- **Token Efficiency**: Sonnet subagents are kept at `medium` or `low` effort. If a subagent
  encounters a compilation or logic error, the orchestrator steps in to debug rather than letting
  the subagent cycle through reasoning loops.


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
| `targetUnavailable()` is true for a connection error | five of seven outcomes never overrode the default, so an open circuit ACK-deleted whole ordered groups |

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
- **A defaulted member on a sealed interface is untested by construction.**
  An exhaustive `switch` protects against a missing *case*; nothing protects
  against a wrong *default*, because the records that inherit it are exactly
  the ones no test thought to name. If every implementation ought to have an
  opinion, make the member abstract and let the compiler collect them.
- **Mutation-check with a bounded runner.** `timeout` is not on macOS —
  `timeout 60 mvn ...` exits 127 immediately and reads like a killed mutant.
  Use `-Dsurefire.timeout=<seconds>`; a mutant that hangs the fork is a
  killed mutant, but only if something actually timed it.
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
- **`~/.m2` is shared too.** A worktree agent that runs `mvn install` for
  the server module overwrites the SNAPSHOT jar every other worktree's
  `fcdev` build resolves — a sibling then compiles against someone else's
  server. Build downstream modules through the reactor instead
  (`mvn -pl fcdev -am test -Dtest=… -Dsurefire.failIfNoSpecifiedTests=false`),
  which resolves siblings from their `target/classes`, and never install
  from a worktree.
