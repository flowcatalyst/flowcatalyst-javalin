# Multi-Agent Translation Guardrails: Go to Java

This file guides Claude Code during the migration of our complex message routing, OIDC identity server, and distributed task scheduling platform from Go to idiomatic Java.

## Multi-Agent Execution Policy
- **Subagent Routing**: The master orchestrator (Opus 5 or Fable 5) MUST offload all high-volume file generation, Java boilerplate creation, and structural refactoring to the `sonnet-5` subagent.
- **Orchestrator Role**: Opus 5 acts purely as the strategic gatekeeper. It analyzes the architectural intent of the Go code, writes strict specifications, and comprehensively reviews all Java code produced by the subagents before it is finalized.
- **Token Efficiency**: Subagents must be kept at `medium` or `low` effort. If a subagent encounters a compilation or logic error, the master orchestrator should step in to debug rather than letting the subagent cycle through infinite reasoning loops.

