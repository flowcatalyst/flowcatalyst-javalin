package io.flowcatalyst.testpg;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/// Tells [TestPg] which test class is running, so [TestPg#dataSource()] can
/// give each class its own database. Registered for every test through
/// `META-INF/services` + `junit.jupiter.extensions.autodetection.enabled`
/// (`junit-platform.properties`) — a test does not opt in, and cannot forget to.
///
/// Only the top-level class counts: a `@Nested` class shares its outer
/// class's database, and must not drop it on its own way out.
public final class TestPgPerClass implements BeforeAllCallback, AfterAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        if (isTopLevel(context)) TestPg.enter(context.getRequiredTestClass().getName());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (isTopLevel(context)) TestPg.leave(context.getRequiredTestClass().getName());
    }

    private static boolean isTopLevel(ExtensionContext context) {
        return context.getRequiredTestClass().getEnclosingClass() == null;
    }
}
