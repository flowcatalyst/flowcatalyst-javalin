package io.flowcatalyst.eventloop;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/// What counts as a blocking call (`docs/spec/eventloop-check.md` §2). There are two rules:
///
/// 1. **A method declaring `InterruptedException` waits.** That covers `Thread.sleep`/`join`,
///    `Future.get`, latches, semaphores, `BlockingQueue.take`/`put`, `Condition.await`,
///    `Object.wait`, `HttpClient.send`, `Process.waitFor` and our own methods alike.
/// 2. **A fixed list of blockers that do not declare it:** JDBC, jOOQ execution, stream, reader,
///    file, socket and DNS I/O, `CompletableFuture.join`, parking, and Vert.x `Future.await`.
///
/// Taking a lock or a monitor is not on the list. The loop then waits only as long as the holder
/// keeps it, and whether a holder blocks while holding it cannot be seen at the call site. That is
/// a review rule (spec §3): never block while holding a lock the loop shares.
///
/// A rule names the receiver's static type, so `connection.unwrap(…)` counts as JDBC even though
/// `unwrap` is declared on `java.sql.Wrapper`. A type missing from the class path, jOOQ in a module
/// without it for example, drops its rules.
final class BlockingCalls {

    private record Rule(TypeMirror owner, String packagePrefix, Pattern name, List<TypeMirror> exempt, String what) {
    }

    private final Types types;
    private final TypeMirror interrupted;
    private final List<Rule> methodRules = new ArrayList<>();
    private final List<Rule> constructorRules = new ArrayList<>();

    BlockingCalls(Types types, Elements elements) {
        this.types = types;
        this.interrupted = type(elements, "java.lang.InterruptedException");

        rule(elements, "java.util.concurrent.CompletableFuture", "join", "waits for the future");
        rule(elements, "java.util.concurrent.ForkJoinTask", "join|invoke", "waits for the task");
        rule(elements, "java.util.concurrent.locks.LockSupport", "park.*", "parks the thread");
        rule(elements, "java.util.concurrent.locks.Condition", "awaitUninterruptibly", "waits on the condition");
        rule(elements, "java.util.concurrent.Semaphore", "acquireUninterruptibly", "waits for a permit");
        rule(elements, "java.util.concurrent.ExecutorService", "close", "waits for the executor's tasks");
        rule(elements, "io.vertx.core.Future", "await", "waits for the future");

        rule(elements, "java.io.InputStream", "read|readAllBytes|readNBytes|transferTo|skip|skipNBytes",
                "is blocking I/O", "java.io.ByteArrayInputStream");
        rule(elements, "java.io.Reader", "read|readLine|transferTo|skip", "is blocking I/O",
                "java.io.StringReader", "java.io.CharArrayReader");
        rule(elements, "java.nio.file.Files", ".*", "is file I/O");
        rule(elements, "java.nio.channels.FileChannel", ".*", "is file I/O");
        rule(elements, "java.nio.channels.SocketChannel", ".*", "is socket I/O");
        rule(elements, "java.nio.channels.ServerSocketChannel", ".*", "is socket I/O");
        rule(elements, "java.io.RandomAccessFile", ".*", "is file I/O");
        rule(elements, "java.net.Socket", "connect", "is socket I/O");
        rule(elements, "java.net.ServerSocket", "accept", "is socket I/O");
        rule(elements, "java.net.InetAddress",
                "getByName|getAllByName|getLocalHost|getHostName|getCanonicalHostName|isReachable", "is a DNS lookup");
        rule(elements, "java.net.URL", "openStream|openConnection|getContent", "is network I/O");
        rule(elements, "java.net.URLConnection",
                "connect|getInputStream|getOutputStream|getContent|getResponseCode|getResponseMessage|getHeaderField.*",
                "is network I/O");

        for (String jdbc : List.of("java.sql.Connection", "java.sql.Statement", "java.sql.ResultSet",
                "java.sql.DatabaseMetaData", "java.sql.DriverManager", "javax.sql.DataSource")) {
            rule(elements, jdbc, ".*", "is JDBC, which blocks on the database");
        }
        methodRules.add(new Rule(null, "org.jooq.",
                Pattern.compile("fetch.*|execute|transaction.*|connection.*|stream|collect|iterator"),
                List.of(), "runs a jOOQ query, which blocks on the database"));

        for (String file : List.of("java.io.FileInputStream", "java.io.FileOutputStream", "java.io.FileReader",
                "java.io.FileWriter", "java.io.RandomAccessFile", "java.net.Socket")) {
            TypeMirror t = type(elements, file);
            if (t != null) constructorRules.add(new Rule(t, null, null, List.of(), "opens a file or socket"));
        }
    }

    private void rule(Elements elements, String owner, String names, String what, String... exempt) {
        TypeMirror t = type(elements, owner);
        if (t == null) return;
        List<TypeMirror> ex = new ArrayList<>();
        for (String e : exempt) {
            TypeMirror et = type(elements, e);
            if (et != null) ex.add(et);
        }
        methodRules.add(new Rule(t, null, Pattern.compile(names), ex, what));
    }

    private TypeMirror type(Elements elements, String name) {
        TypeElement e = elements.getTypeElement(name);
        return e == null ? null : types.erasure(e.asType());
    }

    /// Why calling `m` on a receiver of static type `receiver` blocks, or empty when it does not.
    Optional<String> method(ExecutableElement m, TypeMirror receiver) {
        String call = (receiver instanceof DeclaredType dt ? dt.asElement().getSimpleName().toString() : ownerName(m))
                + "." + m.getSimpleName();
        if (interrupted != null) {
            for (TypeMirror thrown : m.getThrownTypes()) {
                if (types.isAssignable(types.erasure(thrown), interrupted)) {
                    return Optional.of("`" + call + "` waits (it declares InterruptedException)");
                }
            }
        }
        TypeMirror recv = receiver == null || receiver.getKind() == TypeKind.NONE
                ? types.erasure(m.getEnclosingElement().asType())
                : types.erasure(receiver);
        String name = m.getSimpleName().toString();
        for (Rule r : methodRules) {
            if (!r.name().matcher(name).matches()) continue;
            if (r.owner() != null ? isSubtype(recv, r.owner()) && !exempt(recv, r) : inPackage(recv, r.packagePrefix())) {
                return Optional.of("`" + call + "` " + r.what());
            }
        }
        return Optional.empty();
    }

    /// Why constructing an instance with `constructor` blocks, or empty when it does not. A
    /// `Socket` blocks only when given somewhere to connect to.
    Optional<String> constructor(ExecutableElement constructor) {
        TypeMirror owner = types.erasure(constructor.getEnclosingElement().asType());
        for (Rule r : constructorRules) {
            if (!types.isSameType(owner, r.owner())) continue;
            if (owner.toString().equals("java.net.Socket") && constructor.getParameters().isEmpty()) continue;
            return Optional.of("`new " + simpleName(owner) + "(…)` " + r.what());
        }
        return Optional.empty();
    }

    private boolean isSubtype(TypeMirror t, TypeMirror of) {
        return t.getKind() == TypeKind.DECLARED && types.isSubtype(t, of);
    }

    private boolean exempt(TypeMirror recv, Rule r) {
        for (TypeMirror e : r.exempt()) {
            if (types.isSubtype(recv, e)) return true;
        }
        return false;
    }

    private static boolean inPackage(TypeMirror t, String prefix) {
        return t instanceof DeclaredType dt
                && ((TypeElement) dt.asElement()).getQualifiedName().toString().startsWith(prefix);
    }

    private static String ownerName(ExecutableElement m) {
        Element owner = m.getEnclosingElement();
        return owner instanceof TypeElement te ? te.getSimpleName().toString() : owner.toString();
    }

    private static String simpleName(TypeMirror t) {
        String s = t.toString();
        return s.substring(s.lastIndexOf('.') + 1);
    }
}
