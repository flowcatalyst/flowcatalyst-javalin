package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.function.Function;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Unit-level coverage of [ApiOnlyParentLoader]'s own routing rules
/// (`docs/spec/function-host-core.md` §2.1), ahead of the jar-level
/// integration tests (`docs/spec/function-host-core.md` §3, L2-L5) that
/// exercise it end to end through a real [JvmFunctionLoader].
class ApiOnlyParentLoaderTest {

    @Test
    void loadsAJdkClassFromThePlatformLoader() throws ClassNotFoundException {
        ApiOnlyParentLoader loader = newLoader();
        Class<?> loaded = loader.loadClass("java.util.ArrayList");
        assertThat(loaded).isSameAs(ArrayList.class);
        // The platform loader, not this loader, defined it.
        assertThat(loaded.getClassLoader()).isNotEqualTo(loader);
    }

    @Test
    void loadsAnApiClassFromTheHostLoader() throws ClassNotFoundException {
        ApiOnlyParentLoader loader = newLoader();
        Class<?> loaded = loader.loadClass("io.flowcatalyst.function.Function");
        assertThat(loaded).isSameAs(Function.class);
    }

    @ParameterizedTest(name = "[{index}] rejects {0}")
    @CsvSource({
        "io.flowcatalyst.functionx.Y",
        "io.flowcatalyst.function.sub.Y",
        "io.flowcatalyst.functio",
        "com.example.lib.Version",
        "io.vertx.core.Vertx",
        "io.flowcatalyst.server.Platform",
        "tools.jackson.databind.ObjectMapper"
    })
    void rejectsEverythingOutsideTheTwoRoutedCases(String className) {
        ApiOnlyParentLoader loader = newLoader();
        assertThatThrownBy(() -> loader.loadClass(className)).isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void getResourceRoutesOnlyJdkPrefixedNames() {
        ApiOnlyParentLoader loader = newLoader();
        assertThat(loader.getResource("java/util/ArrayList.class")).isNotNull();
        assertThat(loader.getResource("META-INF/services/java.security.Provider")).isNull();
        assertThat(loader.getResource("io/flowcatalyst/function/Function.class")).isNull();
    }

    @Test
    void getResourcesNeverExposesServiceRegistrations() throws IOException {
        ApiOnlyParentLoader loader = newLoader();
        Enumeration<URL> resources = loader.getResources("META-INF/services/java.util.function.Supplier");
        assertThat(Collections.list(resources)).isEmpty();
    }

    private static ApiOnlyParentLoader newLoader() {
        // Same package as production: the package-private (ClassLoader) constructor
        // pins the host loader explicitly rather than relying on ApiOnlyParentLoader's
        // own defining loader, which is what the no-arg constructor uses in production.
        return new ApiOnlyParentLoader(ApiOnlyParentLoaderTest.class.getClassLoader());
    }
}
