# syntax=docker/dockerfile:1
#
# Production image for the unified fc-server jar — the Java counterpart of
# ../flowcatalyst-go/Dockerfile. Multi-stage:
#   1. build the executable fc-server jar (the Vue SPA and the SQL migrations
#      are classpath resources, so the runtime image needs ONLY the jar) and a
#      jlink runtime holding just the JDK modules the jar needs
#   2. bare Alpine + that runtime + the jar, with a working HEALTHCHECK
#
# Build:  docker build -t flowcatalyst-java .
# Run:    docker run -p 8080:8080 -e FC_DATABASE_URL=... -e FC_PLATFORM_ENABLED=true flowcatalyst-java
#         (the API port defaults to 8080 in code; pass API_PORT/PORT — or the
#         canonical FC_API_PORT — to run on another port; see the HEALTHCHECK
#         below, which probes whichever one actually applies)
#
# The platform API is on by default and every other subsystem is off; the
# same image is the API tier, the router tier or a worker depending on
# which FC_*_ENABLED you pass (FC_PLATFORM_ENABLED=false for a router-only
# instance, which then needs no database).

# ── Stage 1 — fc-server jar + jlink runtime ────────────────────────────────
FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /src
# Dependency layer cached independently of source.
COPY pom.xml ./
COPY usecase/pom.xml usecase/
COPY sdk/pom.xml sdk/
COPY server/pom.xml server/
COPY fcdev/pom.xml fcdev/
RUN mvn -q -B -pl server -am dependency:go-offline || true
COPY . .
RUN mvn -q -B -DskipTests -pl server -am package \
 && cp server/target/flowcatalyst-server-*-exec.jar /fc-server.jar
# jlink: the modules jdeps finds in the jar, plus the ones reached only by
# reflection (TLS EC curves, Unsafe users, JNDI in Hikari/logback, JMX, zipfs).
# --strip-debug/--compress halve the modules image; no JIT is removed — this is
# still HotSpot with full peak performance, just without unused modules.
RUN MODS=$(jdeps --ignore-missing-deps --multi-release 25 --print-module-deps /fc-server.jar) \
 && jlink --add-modules "$MODS,jdk.crypto.ec,jdk.unsupported,java.naming,jdk.management,jdk.zipfs" \
          --strip-debug --no-header-files --no-man-pages --compress zip-6 \
          --output /jre \
 && /jre/bin/java -version

# AOT training run (docs/spec/jvm-memory.md §1a, JEP 514): one step — the
# cache is written to /fc-server.aot at JVM exit (FC_EXIT_AFTER_START, see
# Main.java). Trains with /jre/bin/java against this exact /fc-server.jar,
# the SAME runtime the runtime stage copies below, because the cache is tied
# to the precise JDK build and classpath: training against a different JRE
# or jar would just be rejected at boot (a warning, never a failure — see
# entrypoint.sh) and the whole point of the training run is to have a cache
# that is actually used. Router-only: this build has no database, so only
# the classes a router-only boot touches are in the cache — platform-mode
# (API/identity) classes are not, though the entrypoint still applies the
# cache for every role since a partial cache still helps, it just doesn't
# cover everything a platform boot loads. A future training pass with an
# embedded Postgres (FC_PLATFORM_ENABLED=true against a throwaway database)
# could extend coverage to those classes too. No fence flags here — the
# fence (docker/jvm-opts.sh) only derives -Xmx/-XX:MaxDirectMemorySize,
# which do not affect what ends up in the cache — but
# -XX:+UseCompactObjectHeaders IS here, because that one changes object
# layout and MUST match the runtime flag exactly (entrypoint.sh) or the
# cache is rejected.
RUN FC_EXIT_AFTER_START=true FC_PLATFORM_ENABLED=false FC_ROUTER_ENABLED=true FC_API_PORT=0 FC_METRICS_PORT=0 FC_LOG_FORMAT=json \
      /jre/bin/java -XX:+UseCompactObjectHeaders -XX:AOTCacheOutput=/fc-server.aot --enable-preview --enable-native-access=ALL-UNNAMED -jar /fc-server.jar \
 && test -s /fc-server.aot

# ── Stage 2 — runtime ──────────────────────────────────────────────────────
# Bare Alpine (not a JRE image): the jlink runtime above is the JRE. wget for
# a self-contained HEALTHCHECK; ca-certificates for outbound TLS (SQS/Secrets
# Manager/webhooks). The Temurin alpine JDK is musl-built, so its jlink output
# runs here unchanged.
FROM alpine:3.22 AS runtime
RUN apk add --no-cache ca-certificates wget \
 && adduser -D -u 10001 flowcatalyst
COPY --from=build /jre /opt/jre
COPY --from=build /fc-server.jar /usr/local/lib/fc-server.jar
# docs/spec/jvm-memory.md: fences -Xmx/-XX:MaxDirectMemorySize to the
# container's cgroup limit before java starts (JVM ergonomics default a
# quarter of the container, wrong at both ends of the size range).
COPY --chmod=0755 docker/jvm-opts.sh docker/entrypoint.sh /usr/local/bin/
# docs/spec/jvm-memory.md §1a: the AOT cache the build stage's training run
# produced, tied to the exact /jre + /fc-server.jar above. entrypoint.sh
# passes -XX:AOTCache=... only when this file exists.
COPY --from=build /fc-server.aot /usr/local/lib/fc-server.aot
USER flowcatalyst
ENV JAVA_HOME=/opt/jre \
    PATH=/opt/jre/bin:$PATH
# No FC_API_PORT here on purpose: an image-level FC_API_PORT would silently
# beat a task definition's own API_PORT (Env's alias order is FC_API_PORT,
# then API_PORT, then PORT — see docs/spec/router-env.md §1), and the code
# default is already 8080, so this ENV line added nothing but a footgun for
# a drop-in deployment that only ever set API_PORT.
# 8080 = API (+ embedded SPA), 9090 = Prometheus metrics.
EXPOSE 8080 9090
# GET (not --spider/HEAD): the /health route is GET-only, so a HEAD probe
# 405s. Probes whichever port Env actually resolves to (FC_API_PORT, then
# API_PORT, then PORT, then the 8080 code default) rather than a hardcoded
# 8080, so a task definition that overrides the port still gets a working
# healthcheck instead of one probing a port nothing is listening on.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD sh -c 'wget -q -O /dev/null "http://127.0.0.1:${FC_API_PORT:-${API_PORT:-${PORT:-8080}}}/health" || exit 1'
# entrypoint.sh derives the memory fence (docs/spec/jvm-memory.md) and then
# execs java with --enable-preview/--enable-native-access — see that file for
# why those two flags are there; kept out of this ENTRYPOINT so the fence can
# run first and so java stays PID 1 (exec, no shell left running).
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
