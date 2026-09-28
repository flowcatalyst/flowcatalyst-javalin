# Diagnostics: thread dumps and flight recordings in production

What to pull out of a running fc-server when something is wrong, how to get it
without shelling into the host, and how to read it. Added 2026-09-28.

Every fc-server instance (the API tier, a worker, the router) keeps a
**continuous flight recording** of its last 30 minutes, and serves three
**authenticated** diagnostics routes under the router API's prefix (default
`/router`). Nothing here is anonymous.

## 1. The continuous flight recording

JDK Flight Recorder (JFR) runs from boot as a bounded, rolling, to-disk
recording named `flowcatalyst-continuous`:

- the JDK's `default` settings (GC, allocation, locks and pinning, socket and
  file I/O, CPU samples every 20 ms, thread dumps once a minute, …);
- every FlowCatalyst event, which is where "what happened to message X" lives
  (`docs/spec/jfr-events.md`): `io.flowcatalyst.router.Dispatch` (one per
  delivery attempt: pool, message id, group, attempt, outcome, disposition,
  status), `…router.MessageSettled` (ack/nack/defer/release, with the reason
  and the delay asked for), `…router.GroupDecision` (an ordered group's fate
  after its head failed), `…platform.dispatchjob.DispatchProcessed` (the
  platform's `/process` callback), the scheduler, stream, outbox, purger and
  scheduled-job events;
- **not** `jdk.InitialEnvironmentVariable` or `jdk.InitialSystemProperty`.
  The JDK's own settings record both, which would copy `FC_DATABASE_URL`,
  `FC_APP_KEY` and every other secret in the environment into a file an
  operator downloads. They are switched off in code.

Data older than the age bound, or beyond the size bound, is discarded, so the
recording never grows past its bounds. The chunks live in the JVM's JFR
repository under `java.io.tmpdir` (`/tmp` in the image).

| Variable | Default | Meaning |
|---|---|---|
| `FC_JFR_ENABLED` | `true` (fc-server), `false` (fcdev) | start the recording at boot |
| `FC_JFR_MAX_AGE_MINUTES` | `30` | how far back the recording keeps data |
| `FC_JFR_MAX_SIZE_MB` | `256` | disk it may use; whichever bound is hit first wins |
| `FC_JFR_SETTINGS` | `default` | `default`, `profile`, or a path to a `.jfc` file |

**Overhead.** The `default` settings are designed to run always-on in
production at about **1% CPU**; `profile` samples more (method profiling every
10 ms, allocation profiling, more lock events) at about **2%**. The
FlowCatalyst events cost well under a microsecond each and are written only
while a recording is running. At 1,000 messages/s the router writes roughly
2,000 small events/s (≈200 KB/s), so at that rate the 256 MB bound, not the
30-minute one, is what limits how far back you can look (about 20 minutes).
Raise `FC_JFR_MAX_SIZE_MB` if you need more; it is disk, not heap.

To put the repository somewhere else (a volume, a larger disk), pass
`JAVA_TOOL_OPTIONS=-XX:FlightRecorderOptions:repository=/data/jfr`. The
public JFR API cannot set it, so there is no `FC_` variable for it. If the
recording cannot start (bad `FC_JFR_SETTINGS`, an unwritable repository), the
server logs a warning and starts without it: a diagnostic never fails a boot.

## 2. The routes

All four are `GET`s under `{FC_ROUTER_HTTP_PREFIX}/diagnostics` (default
`/router/diagnostics`) on the API port (8080), on every tier. They need a
platform **API bearer token** whose principal holds
**`platform:messaging:router:operate`**, even though they are `GET`s. A
recording holds message ids, target hosts and the shape of the code, so
reading one is an operator's act, and `router:view` is not enough.

| Route | Returns |
|---|---|
| `/router/diagnostics` | JSON: runtime (`hotspot`/`native-image`), whether JFR is available and recording, its bounds and current size, whether the thread dump includes virtual threads |
| `/router/diagnostics/thread-dump` | JSON thread dump of **every** thread, virtual threads included (below) |
| `/router/diagnostics/jfr?minutes=N` | a `.jfr` file: the last N minutes (default 10) of the continuous recording |
| `/router/diagnostics/jfr/events?messageId=X&group=G&minutes=N` | JSON: the FlowCatalyst events for one message (`messageId` or `jobId` = X) and/or group, oldest first, at most 1,000 |

Answers: `401` without a valid token, `403` without `router:operate`, `404
NO_RECORDING` when no continuous recording is running (or the runtime has no
JFR), `400` for a bad `minutes` or an events query with neither `messageId`
nor `group`, `429` while another diagnostic is being produced or downloaded
(one at a time; retry after a few seconds). In dev mode the router API's
guard is Basic auth, and it is open when no user is configured. The
diagnostics then answer `403 DIAGNOSTICS_REQUIRE_AUTH` rather than serve
anyone. Set `FC_ROUTER_AUTH_USER`/`FC_ROUTER_AUTH_PASS`, or use `jcmd`
locally.

Which platform verifies the token: the router tier uses
`FC_ROUTER_PLATFORM_URL` (as for the rest of its API); the API tier uses its
own platform over loopback; a worker needs `FC_ROUTER_PLATFORM_URL` set, or
every diagnostics call is `401` (it fails closed).

### Getting a token

Any OAuth client whose service account holds a role with
`platform:messaging:router:operate` (the built-in `platform:router-operator`
role has it, together with `router:view`):

```sh
TOKEN=$(curl -s https://platform.example.com/oauth/token \
  -d grant_type=client_credentials -d client_id=$CLIENT_ID -d client_secret=$CLIENT_SECRET \
  | jq -r .access_token)
H="Authorization: Bearer $TOKEN"
```

### What happened to message X

```sh
curl -s -H "$H" "https://router.example.com/router/diagnostics/jfr/events?messageId=$ID&minutes=30" | jq
```

Each event carries its time, type, thread and fields. A typical unhappy life
reads: `Dispatch` (outcome `ErrorProcess`, status 503) → `MessageSettled`
(action `nack`, reason `target-unavailable`, requested delay) → later
`Dispatch` (outcome `Success`) → `MessageSettled` (action `ack`, reason
`delivered`). For an ordered group, query `group=G` to see the
`GroupDecision` that returned or blocked it and every sibling's dispatches.
The query reads the recording, so it answers only for the recording's window
(section 1).

### A thread dump

```sh
curl -s -H "$H" https://router.example.com/router/diagnostics/thread-dump > threads.json
jq '.threadDump.threadContainers[] | {container, threadCount}' threads.json
jq -r '.threadDump.threadContainers[].threads[] | select(.stack | any(test("Pool.runDrainer"))) | .name' threads.json
```

This is `HotSpotDiagnosticMXBean.dumpThreads(…, JSON)`, the format `jcmd
Thread.dump_to_file -format=json` writes. It lists virtual threads grouped by
their container (executor, structured scope). Nearly all request and message
work here runs on virtual threads, so `jstack` and
`Thread.getAllStackTraces()` show almost none of it. Look for many threads
parked at the same frame (a pool's semaphore, a Postgres connection wait, a
broker call). The JDK's own JSON is not a Mission Control format; `jq`, or
any JSON viewer, is the reader.

### A flight recording

```sh
curl -s -H "$H" -OJ "https://router.example.com/router/diagnostics/jfr?minutes=15"   # flowcatalyst-<pid>-<time>.jfr
```

Reading it:

- **JDK Mission Control** (`jmc`, free from Adoptium or Oracle): File → Open.
  *Automated Analysis* flags GC pressure, lock contention, hot methods and
  virtual-thread pinning; the *Event Browser* has a *FlowCatalyst* category
  with the router, platform and stream events.
- **`jfr`** (in every JDK, and in the image):
  ```sh
  jfr summary flowcatalyst-….jfr
  jfr print --events io.flowcatalyst.router.MessageSettled flowcatalyst-….jfr
  jfr print --events jdk.VirtualThreadPinned,jdk.JavaMonitorEnter --stack-depth 20 flowcatalyst-….jfr
  jfr view hot-methods flowcatalyst-….jfr        # also: gc, allocation-by-site, contention-by-site
  jfr print --json --events io.flowcatalyst.router.Dispatch flowcatalyst-….jfr | jq '…'
  ```

## 3. Inside the container: `jcmd`

The image's runtime includes `jdk.jcmd` (with `jstack`, `jmap`, `jinfo`,
`jstat`) and `jdk.management.jfr`, about 0.5 MB. The server is PID 1 and
runs as the image user, so an ECS Exec or `docker exec` session (same
user) can attach:

```sh
docker exec -it <container> jcmd 1 Thread.dump_to_file -format=json /tmp/threads.json
docker exec -it <container> jcmd 1 JFR.check
docker exec -it <container> jcmd 1 JFR.dump name=flowcatalyst-continuous maxage=10m filename=/tmp/last10.jfr
docker cp <container>:/tmp/last10.jfr .
```

On ECS: `aws ecs execute-command --cluster … --task … --container … --interactive --command "jcmd 1 JFR.check"`.
`jcmd 1 JFR.dump` without `maxage` writes the whole recording. Note that a
recording started by hand with `jcmd 1 JFR.start` uses the JDK's settings
unchanged, so it **does** record the environment. Prefer the continuous one.

## 4. Native image

The native `fc-server`/`fcdev` binaries (GraalVM, `-Pnative`) are built
without `--enable-monitoring`, so:

| | JVM (image, jar) | Native image |
|---|---|---|
| continuous recording | yes | no: the server logs "no flight recorder" once; `/diagnostics/jfr*` answer `404 NO_RECORDING` saying why |
| thread dump | every thread, virtual included | falls back to `Thread.getAllStackTraces()`: **platform threads only**, marked `"virtualThreadsIncluded": false` with the reason in the body |
| `jcmd` | yes | no |
| capabilities route | `runtime: hotspot` | `runtime: native-image`, `jfr.available: false`, `threadDump.virtualThreads: false` |

GraalVM can add most of this at build time with
`--enable-monitoring=jfr,threaddump` (and the experimental `jcmd`): JFR
then works in the binary, with a subset of the JDK's events plus
FlowCatalyst's custom ones, started either by these routes' continuous
recording or by `-XX:StartFlightRecording`, and `kill -QUIT <pid>` prints a
thread dump to stdout. It costs binary size and build time, and it has not
been turned on for the native builds.

Observed 2026-09-28 against a native `fc-server` (GraalVM 25.0.4.1, macOS
arm64, the `-Pnative` flags as they stand, router-only in dev mode with
Basic auth): the boot logs the "no flight recorder" warning once;
`/diagnostics` answers `runtime: native-image, jfr.available: false`;
`/diagnostics/thread-dump` answers 200 with the fallback dump,
`virtualThreadsIncluded: false`, reason `HotSpotDiagnosticMXBean is not a
platform management interface`; `/diagnostics/jfr` answers `404
NO_RECORDING` naming `--enable-monitoring=jfr`.
