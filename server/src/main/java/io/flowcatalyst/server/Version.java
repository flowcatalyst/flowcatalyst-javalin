package io.flowcatalyst.server;

/// The build version reported by `/health`. Go injects it with
/// `-ldflags -X …/internal/server.Version=<v>`; here it comes from the jar
/// manifest's `Implementation-Version` (set by the release build), and is
/// `dev` when running from an IDE or `mvn exec`.
public final class Version {

    private static final String CURRENT = resolve();

    private Version() {}

    public static String current() {
        return CURRENT;
    }

    private static String resolve() {
        String fromProperty = System.getProperty("fc.version");
        if (fromProperty != null && !fromProperty.isBlank()) return fromProperty;
        Package pkg = Version.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }
}
