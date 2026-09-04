# native-config — GraalVM reachability metadata (trial, 2026-09-04)

`reachability-metadata.json` was captured by running the fc-server jar under
the native-image tracing agent in platform + router + default-broker mode
against a migrated database and exercising `/health`, `/ready`, the router
surface, every `/api/*` list route and one event-type create:

    JAVA_HOME=$(mise where java@oracle-graalvm-25.0.4.1)
    $JAVA_HOME/bin/java --enable-preview \
      -agentlib:native-image-agent=config-merge-dir=server/native-config \
      -jar server/target/flowcatalyst-server-0.0.1-SNAPSHOT-exec.jar

It covers the third-party reflection the image needs (jOOQ data-type array
classes, Jackson, the Postgres driver, Jetty). Our own classes are registered by
`io.flowcatalyst.tools.NativeReflectConfig` at build time (records, enums,
Jackson-annotated DTOs, jOOQ record types), so this file only has to track
library behaviour. Re-capture (merge) after exercising any code
path that fails in the image with a "not registered" error.
