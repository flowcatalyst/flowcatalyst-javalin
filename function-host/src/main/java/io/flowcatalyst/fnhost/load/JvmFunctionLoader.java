package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;
import io.flowcatalyst.platform.function.Manifest;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/// Loads one JVM function jar into a fresh, isolated [LoadedFunction]
/// (`docs/spec/function-host-core.md` §2.2). Scans the jar for the refusal
/// conditions **before defining any class from it** — a refusal is routine,
/// so it is a [LoadOutcome], never an exception.
public final class JvmFunctionLoader implements FunctionLoader {

    private static final List<String> NATIVE_LIBRARY_SUFFIXES = List.of(".so", ".dll", ".dylib", ".jnilib");
    private static final String SECURITY_PROVIDER_ENTRY = "META-INF/services/java.security.Provider";
    private static final String API_PACKAGE_PATH = "io/flowcatalyst/function/";

    /// [FunctionLoader]'s form: the manifest's `entrypoint` is the class name.
    @Override
    public LoadOutcome load(Path artifact, Manifest manifest, FunctionAddress address, int version) {
        return load(artifact, manifest.entrypoint(), address, version);
    }

    /// Scans `jar`, and — if nothing refuses it — defines a `URLClassLoader`
    /// named `fn:<address>@<version>` over it, parented by a fresh
    /// [ApiOnlyParentLoader], loads `entrypoint`, and instantiates it.
    ///
    /// `docs/spec/function-host-process.md` §3's metaspace fence is meant to
    /// produce "a catchable `OutOfMemoryError: Metaspace` that fails one
    /// load" — class DEFINITION (`Class.forName`), reflective metadata
    /// lookup (`getDeclaredConstructor`) and the entrypoint's own constructor
    /// can all throw it directly. Every one of those is caught HERE, at the
    /// single call site that owns the (possibly partially built)
    /// `URLClassLoader` and can close it — never further up the call chain,
    /// where there would be nothing left to close. A Java-heap
    /// `OutOfMemoryError` is deliberately NOT caught: [#isMetaspaceOom]
    /// checks the error's own message, and anything else is rethrown.
    public LoadOutcome load(Path jar, String entrypoint, FunctionAddress address, int version) {
        Refused refusal = scanForRefusal(jar);
        if (refusal != null) {
            return refusal;
        }

        URLClassLoader loader = null;
        try {
            URL jarUrl = jar.toUri().toURL();
            loader = new URLClassLoader("fn:" + address.render() + "@" + version,
                    new URL[] {jarUrl}, new ApiOnlyParentLoader());

            Class<?> entrypointClass;
            try {
                entrypointClass = Class.forName(entrypoint, false, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                // A LinkageError can itself be an ExceptionInInitializerError wrapping a
                // metaspace OutOfMemoryError (the class's own static initialiser bootstrapping
                // a lambda/string-concat call site and running out of room) — findMetaspaceOom
                // unwraps that; a genuine "class not found/incompatible" LinkageError has no
                // such cause and falls through to the ordinary refusal below. A NON-metaspace
                // OutOfMemoryError anywhere in the chain (findAnyOom) is rethrown rather than
                // silently reported as a mundane ENTRYPOINT_NOT_FOUND — it is a real emergency.
                OutOfMemoryError metaspaceOom = findMetaspaceOom(e);
                if (metaspaceOom != null) {
                    closeQuietly(loader);
                    return new Refused(Reason.OUT_OF_METASPACE, describeOom(metaspaceOom));
                }
                OutOfMemoryError anyOom = findAnyOom(e);
                closeQuietly(loader);
                if (anyOom != null) {
                    throw anyOom;
                }
                return new Refused(Reason.ENTRYPOINT_NOT_FOUND, entrypoint);
            }

            if (!Function.class.isAssignableFrom(entrypointClass)) {
                closeQuietly(loader);
                return new Refused(Reason.ENTRYPOINT_NOT_A_FUNCTION, entrypoint);
            }

            Function instance;
            try {
                Constructor<?> ctor = entrypointClass.getDeclaredConstructor();
                if (!Modifier.isPublic(ctor.getModifiers())) {
                    closeQuietly(loader);
                    return new Refused(Reason.ENTRYPOINT_NOT_INSTANTIABLE, entrypoint + ": constructor is not public");
                }
                instance = (Function) ctor.newInstance();
            } catch (NoSuchMethodException e) {
                closeQuietly(loader);
                return new Refused(Reason.ENTRYPOINT_NOT_INSTANTIABLE, entrypoint + ": no public no-arg constructor");
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | LinkageError e) {
                // Reflection wraps ANY throwable the constructor itself raises — including an
                // OutOfMemoryError, heap or metaspace — in InvocationTargetException; and the
                // constructor bootstrapping a lambda/string-concat call site under a tight fence
                // can wrap ITS OutOfMemoryError one level further still (InternalError /
                // BootstrapMethodError) — findMetaspaceOom walks the whole chain. A Java-heap OOM
                // found this way (findAnyOom) is rethrown rather than reported as a mundane "not
                // instantiable" refusal — it is not this method's failure mode to swallow.
                OutOfMemoryError metaspaceOom = findMetaspaceOom(e);
                if (metaspaceOom != null) {
                    closeQuietly(loader);
                    return new Refused(Reason.OUT_OF_METASPACE, describeOom(metaspaceOom));
                }
                OutOfMemoryError anyOom = findAnyOom(e);
                closeQuietly(loader);
                if (anyOom != null) {
                    throw anyOom;
                }
                return new Refused(Reason.ENTRYPOINT_NOT_INSTANTIABLE, entrypoint + ": " + rootMessage(e));
            }

            return new Loaded(new LoadedFunction(instance, loader, address, version));
        } catch (IOException e) {
            closeQuietly(loader);
            return new Refused(Reason.UNREADABLE_JAR, describe(jar, e));
        } catch (Error e) {
            // Broadened from `OutOfMemoryError` alone for the same reason as the reflection
            // catch above: a metaspace exhaustion during class DEFINITION or the loader's own
            // construction does not always arrive as a bare OutOfMemoryError.
            OutOfMemoryError oom = findMetaspaceOom(e);
            closeQuietly(loader);
            if (oom != null) {
                return new Refused(Reason.OUT_OF_METASPACE, describeOom(oom));
            }
            throw e; // a Java-heap OOM, or any other real Error, is not this method's to swallow
        }
    }

    /// Spec `function-host-process.md` §3: only an `OutOfMemoryError` whose
    /// own message names Metaspace or Compressed class space is this host's
    /// to catch — every other `OutOfMemoryError` (heap, direct memory,
    /// "unable to create native thread", …) is a real emergency and must
    /// keep propagating. Public: [io.flowcatalyst.fnhost.reconcile.Reconciler#attachContextAndInit]
    /// applies the SAME rule around `Function#init`, so both call sites share
    /// one definition of "this host's fence, not a real emergency".
    public static boolean isMetaspaceOom(Throwable t) {
        if (!(t instanceof OutOfMemoryError)) {
            return false;
        }
        String message = t.getMessage();
        return message != null && (message.contains("Metaspace") || message.contains("Compressed class space"));
    }

    /// Walks `t`'s own cause chain (bounded — a cycle must never loop this
    /// forever) looking for a metaspace [OutOfMemoryError], and returns it if
    /// found. A metaspace exhaustion does not always surface as a bare
    /// `OutOfMemoryError` at the point it is caught: `invokedynamic`
    /// bootstrapping (a lambda, string concatenation, a record's hidden
    /// accessor, …) wraps ANY failure of the class it spins up in
    /// `BootstrapMethodError`/`InternalError`, and a class's own static
    /// initialiser failing wraps it in `ExceptionInInitializerError` — both
    /// `Error` subtypes with the real `OutOfMemoryError` one level down as
    /// `getCause()`. Observed directly, forking a real JVM at a tight fence:
    /// `com.networknt.schema.ValidatorTypeCode`'s `<clinit>` bootstrapping a
    /// lambda threw `InternalError` wrapping `OutOfMemoryError: Metaspace` —
    /// a catch on `OutOfMemoryError` alone missed it entirely.
    public static OutOfMemoryError findMetaspaceOom(Throwable t) {
        Throwable current = t;
        for (int hop = 0; current != null && hop < 8; hop++) {
            if (current instanceof OutOfMemoryError oom && isMetaspaceOom(oom)) {
                return oom;
            }
            // The JVM's own retelling of "this class already failed to initialize once" (every
            // caller AFTER the first one gets a fresh NoClassDefFoundError/ExceptionInInitializerError,
            // `getCause()` empty) does not always carry the ORIGINAL OutOfMemoryError as a real,
            // structured cause — only its own toString() embedded as plain text in the wrapper's
            // own message. Observed directly, forking a real JVM at a tight fence: logging a
            // metaspace failure touched Logback's own `ThrowableProxy` for the first time: its
            // `<clinit>` itself threw the metaspace OOM, and every log call after that (this
            // process never retries a class whose init already failed) got
            // `NoClassDefFoundError: Could not initialize class ...ThrowableProxy`, caused by an
            // `ExceptionInInitializerError` whose message is exactly
            // `"Exception java.lang.OutOfMemoryError: Metaspace [in thread \"main\"]"` — no
            // nested Throwable object, just that string.
            String message = current.getMessage();
            if (message != null && message.contains("OutOfMemoryError")
                    && (message.contains("Metaspace") || message.contains("Compressed class space"))) {
                return new OutOfMemoryError(message);
            }
            current = current.getCause();
        }
        return null;
    }

    /// Same chain walk as [#findMetaspaceOom], but returns ANY
    /// [OutOfMemoryError] found — metaspace or not. Used where finding a
    /// NON-metaspace one changes what a caller does next: a heap
    /// `OutOfMemoryError` buried inside an entrypoint's constructor (wrapped
    /// in `InvocationTargetException`, say) must still be rethrown as the
    /// real emergency it is, never silently folded into an ordinary
    /// `ENTRYPOINT_NOT_INSTANTIABLE`/`ENTRYPOINT_NOT_FOUND` refusal.
    private static OutOfMemoryError findAnyOom(Throwable t) {
        Throwable current = t;
        for (int hop = 0; current != null && hop < 8; hop++) {
            if (current instanceof OutOfMemoryError oom) {
                return oom;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String describeOom(OutOfMemoryError e) {
        String message = e.getMessage();
        return message != null ? message : "OutOfMemoryError";
    }

    /// Opens `jar` and checks every refusal condition before any class from
    /// it is defined. Returns `null` when nothing refuses it.
    private Refused scanForRefusal(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();

                if (isNativeLibrary(name)) {
                    return new Refused(Reason.NATIVE_LIBRARY, name);
                }
                if (name.equals(SECURITY_PROVIDER_ENTRY)) {
                    return new Refused(Reason.SECURITY_PROVIDER, name);
                }
                if (bundlesApi(name)) {
                    return new Refused(Reason.BUNDLES_API, name);
                }
            }
        } catch (IOException e) {
            return new Refused(Reason.UNREADABLE_JAR, describe(jar, e));
        }
        return null;
    }

    private static boolean isNativeLibrary(String entryName) {
        for (String suffix : NATIVE_LIBRARY_SUFFIXES) {
            if (entryName.endsWith(suffix)) return true;
        }
        return false;
    }

    /// A `.class` entry directly under `io/flowcatalyst/function/` — the
    /// exact API package, not a subpackage (`endsWith(".class")` plus no
    /// further `/` after the package prefix keeps `io/flowcatalyst/functionx/Y.class`
    /// and `io/flowcatalyst/function/sub/Y.class` from matching).
    private static boolean bundlesApi(String entryName) {
        if (!entryName.startsWith(API_PACKAGE_PATH) || !entryName.endsWith(".class")) {
            return false;
        }
        return entryName.indexOf('/', API_PACKAGE_PATH.length()) < 0;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t.getCause();
        Throwable root = cause != null ? cause : t;
        String message = root.getMessage();
        return message != null ? message : root.getClass().getName();
    }

    private static String describe(Path jar, IOException e) {
        String message = e.getMessage();
        return jar + (message != null ? ": " + message : "");
    }

    private static void closeQuietly(URLClassLoader loader) {
        if (loader == null) {
            return; // construction itself failed/OOM'd before a loader ever existed
        }
        try {
            loader.close();
        } catch (IOException ignored) {
            // The loader never defined anything usable; nothing to release beyond closing the jar handle.
        }
    }
}
