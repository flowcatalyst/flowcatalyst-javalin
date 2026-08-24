package io.flowcatalyst.platform.shared.platformsink;

import io.flowcatalyst.sdk.usecase.DomainEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Guards the actor/subject distinction at the heart of `DomainEvent#principalId()`
/// (docs/usecase-envelope.md §1–2): the metadata's principal id is always the
/// *actor* who performed the operation. A `DomainEvent` record can accidentally
/// declare its own record component named `principalId` — naming the event's
/// *subject*, the principal acted upon, not the actor who acted — which silently
/// shadows `DomainEvent#principalId()`'s inherited default via ordinary Java
/// method resolution (a record's generated accessor always wins over an
/// interface default it happens to match). `PrincipalEvents.UserCreated` and
/// eleven siblings did exactly this: `aud_logs.principal_id` recorded the
/// created/target user instead of the administrator who created them.
///
/// This test scans every `*Events.java` holder class under
/// `platform/**/operations` for nested records implementing `DomainEvent` and
/// asserts that `principalId()` resolves to `DomainEvent`'s own default method
/// on every one of them — never a record-generated accessor. If your event
/// needs to carry the id of the principal it is about, name that component
/// `userId` (or `subjectId`) — never `principalId`. Discovery is by scanning
/// the source tree rather than a hand-maintained list, so a newly added
/// `*Events.java` file is covered automatically.
class DomainEventContractTest {

    private static final Pattern EVENTS_FILE = Pattern.compile(".*/operations/[A-Za-z0-9_]+Events\\.java$");
    private static final Pattern PACKAGE_DECL = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    @Test
    void noDomainEventRecordShadowsThePrincipalIdDefault() throws IOException {
        List<Class<?>> holders = eventHolderClasses();
        assertThat(holders).as("*Events.java holder classes discovered under platform/**/operations").isNotEmpty();

        List<String> offenders = new ArrayList<>();
        int checked = 0;
        for (Class<?> holder : holders) {
            for (Class<?> nested : holder.getDeclaredClasses()) {
                if (!nested.isRecord() || !DomainEvent.class.isAssignableFrom(nested)) continue;
                checked++;
                Method resolved;
                try {
                    resolved = nested.getMethod("principalId");
                } catch (NoSuchMethodException e) {
                    offenders.add(nested.getName() + " (principalId() did not resolve at all)");
                    continue;
                }
                if (resolved.getDeclaringClass() != DomainEvent.class) {
                    offenders.add(nested.getName());
                }
            }
        }
        assertThat(checked).as("DomainEvent records discovered to check").isGreaterThan(0);
        assertThat(offenders)
                .as("""
                        These DomainEvent records declare their own "principalId" record \
                        component. That component names the event's SUBJECT (the principal \
                        acted upon) but shadows DomainEvent#principalId()'s inherited default \
                        (metadata().principalId(), the ACTOR who performed the operation) via \
                        normal Java method resolution — a record's generated accessor always \
                        wins over a matching interface default. Rename the shadowing component \
                        to userId (or subjectId) so event.principalId() keeps returning the \
                        actor everywhere, including in aud_logs.principal_id.""")
                .isEmpty();
    }

    /// Discovers `*Events.java` holder classes by walking the server module's
    /// source tree rather than a maintained list, so the guard covers a new
    /// aggregate's events file automatically. Assumes the test runs with the
    /// server module directory as the working directory (Maven Surefire's
    /// default), so `src/main/java` resolves relative to it.
    private static List<Class<?>> eventHolderClasses() throws IOException {
        Path srcRoot = Path.of("src/main/java");
        assertThat(Files.isDirectory(srcRoot))
                .as("expected %s to exist — test must run from the server module directory", srcRoot.toAbsolutePath())
                .isTrue();

        List<Class<?>> holders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(srcRoot)) {
            List<Path> eventsFiles = paths
                    .filter(p -> EVENTS_FILE.matcher(p.toString().replace('\\', '/')).matches())
                    .toList();
            for (Path path : eventsFiles) {
                String source = Files.readString(path);
                Matcher pkg = PACKAGE_DECL.matcher(source);
                if (!pkg.find()) {
                    throw new IllegalStateException("no package declaration found in " + path);
                }
                String simpleName = path.getFileName().toString().replace(".java", "");
                String className = pkg.group(1) + "." + simpleName;
                try {
                    holders.add(Class.forName(className));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("scanned " + path + " but could not load " + className
                            + " — class name must match its file name", e);
                }
            }
        }
        return holders;
    }
}
