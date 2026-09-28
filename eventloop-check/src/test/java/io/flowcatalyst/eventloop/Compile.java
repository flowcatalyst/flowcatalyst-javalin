package io.flowcatalyst.eventloop;

import com.sun.source.util.JavacTask;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Compiles in-memory sources with [EventLoopCheck] attached, against stub Vert.x types (so this
/// module needs no Vert.x dependency).
final class Compile {

    /// The slice of Vert.x's API the fixtures use. The check only looks at the package name.
    static final Map<String, String> VERTX_STUBS = Map.of(
            "io/vertx/core/Handler.java", """
                    package io.vertx.core;
                    @FunctionalInterface
                    public interface Handler<E> { void handle(E event); }
                    """,
            "io/vertx/core/Future.java", """
                    package io.vertx.core;
                    public interface Future<T> {
                        Future<T> onComplete(Handler<T> handler);
                        <U> Future<U> map(java.util.function.Function<T, U> mapper);
                        T await();
                    }
                    """,
            "io/vertx/core/Vertx.java", """
                    package io.vertx.core;
                    public interface Vertx {
                        long setTimer(long delay, Handler<Long> handler);
                        void runOnContext(Handler<Void> action);
                        <T> Future<T> executeBlocking(java.util.concurrent.Callable<T> blocking);
                    }
                    """);

    record Result(boolean success, List<String> errors) {
    }

    private Compile() {
    }

    static Result sources(Map<String, String> sources) {
        return sources(sources, List.of("-XDcompilePolicy=simple"));
    }

    static Result sources(Map<String, String> sources, List<String> policy) {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        List<JavaFileObject> files = new ArrayList<>();
        VERTX_STUBS.forEach((name, src) -> files.add(source(name, src)));
        sources.forEach((name, src) -> files.add(source(name, src)));
        try (StandardJavaFileManager fm = javac.getStandardFileManager(diagnostics, null, null)) {
            Path out = Files.createTempDirectory("eventloop-check");
            List<String> options = new ArrayList<>(policy);
            options.addAll(List.of("-proc:none", "-d", out.toString(), "-classpath", annotationsLocation()));
            var task = (JavacTask) javac.getTask(null, fm, diagnostics, options, null, files);
            new EventLoopCheck().init(task);
            boolean ok = task.call();
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getSource().getName().replaceFirst(".*/", "") + ":" + d.getLineNumber() + " " + d.getMessage(null))
                    .toList();
            return new Result(ok, errors);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String annotationsLocation() {
        try {
            return Path.of(OnEventLoop.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JavaFileObject source(String name, String src) {
        return new SimpleJavaFileObject(URI.create("string:///" + name), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return src;
            }
        };
    }
}
