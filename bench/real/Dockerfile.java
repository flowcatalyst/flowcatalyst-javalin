# The Java server as deployed: the exec jar on a JRE, nothing else. The
# memory fence (docs/spec/jvm-memory.md) is part of "as deployed", so the
# bench image carries the same two scripts and entrypoint as the real
# Dockerfile — see run.sh's images(), which copies them in before build.
FROM eclipse-temurin:25-jre-alpine
COPY flowcatalyst-server-exec.jar /usr/local/lib/fc-server.jar
COPY --chmod=0755 jvm-opts.sh entrypoint.sh /usr/local/bin/
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
