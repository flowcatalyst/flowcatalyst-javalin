# syntax=docker/dockerfile:1
#
# Production image for the JVM function host (fc-fnhost) — package D slice
# D5 (docs/spec/function-host-process.md §3). Same two-stage shape as the
# root Dockerfile (fc-server): build the executable jar + a jlink runtime
# holding just the JDK modules it needs, then a bare Alpine runtime.
#
# No AOT cache in this slice: the class set a host loads depends on which
# functions actually land on it, so a fixed training-run cache would be
# misleading — revisit with real numbers once there are some.
#
# Build:  docker build -f function-host/Dockerfile -t flowcatalyst-fnhost .
# Run:    docker run -p 8080:8080 -p 9090:9090 \
#           -e FC_FN_PLATFORM_URL=https://platform.example -e FC_FN_CLIENT_ID=... \
#           -e FC_FN_CLIENT_SECRET=... flowcatalyst-fnhost

# ── Stage 1 — fc-fnhost jar + jlink runtime ────────────────────────────────
FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /src
# Dependency layer cached independently of source — best-effort (`|| true`,
# same as the root Dockerfile): the reactor's other module poms are not all
# copied yet, so a full resolve here would fail; the real `package` below
# runs after `COPY . .`, once every module is present.
COPY pom.xml ./
COPY usecase/pom.xml usecase/
COPY sdk/pom.xml sdk/
COPY function-api/pom.xml function-api/
COPY server/pom.xml server/
COPY function-host/pom.xml function-host/
RUN mvn -q -B -pl function-host -am dependency:go-offline || true
COPY . .
RUN mvn -q -B -DskipTests -pl function-host -am package \
 && cp function-host/target/flowcatalyst-function-host-*-exec.jar /fc-fnhost.jar

# jdeps + jlink: the modules jdeps finds in the jar, plus the ones reached only by
# reflection (TLS EC curves, Unsafe users, JNDI in Hikari/logback carried in via the
# server module, JMX, zipfs) — the same extra-modules list the root Dockerfile uses.
# jdk.compiler is deliberately NOT added: the host never compiles anything at runtime
# (only FixtureJars, a TEST-only class under function-host/src/test, touches
# javax.tools.ToolProvider — it never ships in this jar).
RUN MODS=$(jdeps --ignore-missing-deps --multi-release 25 --print-module-deps /fc-fnhost.jar) \
 && jlink --add-modules "$MODS,jdk.crypto.ec,jdk.unsupported,java.naming,jdk.management,jdk.zipfs" \
          --strip-debug --no-header-files --no-man-pages --compress zip-6 \
          --output /jre \
 && /jre/bin/java -version

# ── Stage 2 — runtime ──────────────────────────────────────────────────────
# Bare Alpine (not a JRE image): the jlink runtime above is the JRE. wget for a
# self-contained HEALTHCHECK; ca-certificates for outbound TLS (the platform's
# control-plane API, OCI artifact registries).
FROM alpine:3.22 AS runtime
RUN apk add --no-cache ca-certificates wget \
 && adduser -D -u 10001 flowcatalyst \
 && mkdir -p /var/lib/fc-fnhost/cache && chown flowcatalyst:flowcatalyst /var/lib/fc-fnhost/cache
COPY --from=build /jre /opt/jre
COPY --from=build /fc-fnhost.jar /usr/local/lib/fc-fnhost.jar
# docker/jvm-opts.sh (root, shared verbatim — see that file's own comment: this
# image is the only one that ever sets FC_JVM_METASPACE_PERCENT, so the
# fc-server image's computed flags stay byte-identical) + this image's own
# entrypoint.
COPY --chmod=0755 docker/jvm-opts.sh /usr/local/bin/jvm-opts.sh
COPY --chmod=0755 function-host/docker/entrypoint.sh /usr/local/bin/entrypoint.sh
USER flowcatalyst
ENV JAVA_HOME=/opt/jre \
    PATH=/opt/jre/bin:$PATH
# A writable volume for fetched/verified function artifacts (FC_FN_CACHE_DIR) —
# HostEnv's own code default is java.io.tmpdir-relative, but an explicit, owned
# path is clearer in a task definition/compose file and survives a container
# restart if mounted as a real volume.
ENV FC_FN_CACHE_DIR=/var/lib/fc-fnhost/cache
VOLUME /var/lib/fc-fnhost/cache
# 8080 = the function listener (`/functions/...`), 9090 = health/ready/metrics.
EXPOSE 8080 9090
# GET (not --spider/HEAD): /health is GET-only. Probes FC_METRICS_PORT's code
# default (9090) unless overridden, same reasoning as the root Dockerfile's probe.
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD sh -c 'wget -q -O /dev/null "http://127.0.0.1:${FC_METRICS_PORT:-9090}/health" || exit 1'
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
