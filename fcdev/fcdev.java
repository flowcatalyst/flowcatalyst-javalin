///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25
//REPOS mavencentral,local
//DEPS io.flowcatalyst:flowcatalyst-fcdev:0.0.1-SNAPSHOT

// JBang entry for fcdev — see docs/fcdev.md.
//
//   jbang fcdev/fcdev.java start            (from a checkout, after `mvn -q -pl fcdev -am install`)
//   jbang app install fcdev@<catalog>       (once; then plain `fcdev start`)
//
// The `local` repo makes the freshly `mvn install`ed 0.0.1-SNAPSHOT resolvable from
// ~/.m2; mavencentral is the published-release path. Everything else is in the
// flowcatalyst-fcdev jar (fc-server + embedded PostgreSQL for every platform).
public class fcdev {
    public static void main(String... args) {
        io.flowcatalyst.fcdev.FcDev.main(args);
    }
}
