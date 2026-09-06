# Brief — native fcdev (`-Pnative` for the developer binary)

Orchestrator: Fable. Coder: Sonnet, medium effort, own worktree. Owner
ruling 2026-09-06 #20: the native fcdev ships **before cutover**. The
template is the server's profile — `server/pom.xml` `<profile><id>native</id>`
(read all of it, and `docs/STATUS.md` "GraalVM native-image trial" for what
each build arg is for) — and the tool that writes our own reflection
config, `io.flowcatalyst.tools.NativeReflectConfig` (server test scope).

## What you build

```
fcdev/pom.xml                 a `native` profile producing fcdev/target/fcdev
fcdev/native-config/          reachability metadata captured with the tracing agent (+ README, like server/native-config)
docs/fcdev.md                 a "native binary" section: build command, size, what was verified
```

- **picocli**: add `info.picocli:picocli-codegen` as an annotation
  processor (provided scope) so `@Command` classes get their
  reflect-config generated at compile time (`-Aproject=fcdev`); do not
  hand-write picocli's reflection entries.
- **Our own classes**: reuse `NativeReflectConfig` through `exec-maven-plugin`
  exactly as the server profile does, pointed at `fcdev/target/classes` and
  the sibling modules' `target/classes` (server, usecase, sdk) — the fcdev
  binary contains the whole server.
- **Embedded Postgres**: fcdev downloads the platform binary at first run
  (`MavenCentralPgBinaryResolver`) and runs it as a subprocess — nothing to
  embed, but zonky's `embedded-postgres` uses reflection/resources you must
  capture with the agent (`-agentlib:native-image-agent=config-merge-dir=fcdev/native-config`)
  while running **`fcdev init` and `fcdev start` against a fresh
  `--embedded-db-path`**, hitting `/health`, `/api/event-types`, the SPA
  `/index.html`, and `fcdev stop`.
- **Build args**: the server's list, plus `-H:IncludeResources` for fcdev's
  own resources (find them: `find fcdev/src/main/resources`), `--enable-preview`,
  `-Dfc.version`; `-Os`; `--no-fallback`. `imageName` `fcdev`, `mainClass`
  `io.flowcatalyst.fcdev.FcDev`.
- Build: `mise install java@oracle-graalvm-25.0.4.1` (may already be
  installed — `mise ls java`), then
  `JAVA_HOME=$(mise where java@oracle-graalvm-25.0.4.1) mvn -q -DskipTests -pl fcdev -am -Pnative package`.
  Never `mvn install`. The default JDK for everything else stays Temurin.

## What you verify (with the binary, not the jar)

1. `fcdev/target/fcdev init --yes --admin-email a@example.com --admin-password '<16+ chars>' --code dev --name Dev --root <scratch> --embedded-db-path <scratch>/pg --embedded-db-port <free>` on an empty directory.
2. `fcdev start …` then `GET /health` 200 with the stamped version, `GET /index.html` the SPA, a login through `POST /auth/login`, an event-type create (201), `fcdev stop`.
3. Size on disk and gzipped; start-to-`/health` time. Compare with the jar's.
4. Every "not registered for reflection" the image throws at runtime is a
   metadata gap: re-run the agent over that path, merge, rebuild — say
   which paths needed it.

No native tests in Surefire (the JVM suite is the authority, as for the
server). `mvn -q -pl fcdev -am test` must stay green without the profile.
`.github/workflows/ci.yml`'s `native` job: add a second step building
`-pl fcdev -am -Pnative` and probing `fcdev/target/fcdev --version` on each
matrix OS (read the job; keep its shape).

## Report

Build time and size per platform you built on; the verification list
above with outputs; the agent-captured paths; anything the server profile
does that fcdev could not reuse (`// SPEC?`). Commit on your branch; do not
merge.
