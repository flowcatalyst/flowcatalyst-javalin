package io.flowcatalyst.fnhost.load;

import io.flowcatalyst.function.Function;
import io.flowcatalyst.platform.function.FunctionAddress;

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
public final class JvmFunctionLoader {

    private static final List<String> NATIVE_LIBRARY_SUFFIXES = List.of(".so", ".dll", ".dylib", ".jnilib");
    private static final String SECURITY_PROVIDER_ENTRY = "META-INF/services/java.security.Provider";
    private static final String API_PACKAGE_PATH = "io/flowcatalyst/function/";

    /// Scans `jar`, and — if nothing refuses it — defines a `URLClassLoader`
    /// named `fn:<address>@<version>` over it, parented by a fresh
    /// [ApiOnlyParentLoader], loads `entrypoint`, and instantiates it.
    public LoadOutcome load(Path jar, String entrypoint, FunctionAddress address, int version) {
        Refused refusal = scanForRefusal(jar);
        if (refusal != null) {
            return refusal;
        }

        URLClassLoader loader;
        try {
            URL jarUrl = jar.toUri().toURL();
            loader = new URLClassLoader("fn:" + address.render() + "@" + version,
                    new URL[] {jarUrl}, new ApiOnlyParentLoader());
        } catch (IOException e) {
            return new Refused(Reason.UNREADABLE_JAR, describe(jar, e));
        }

        Class<?> entrypointClass;
        try {
            entrypointClass = Class.forName(entrypoint, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            closeQuietly(loader);
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
            closeQuietly(loader);
            return new Refused(Reason.ENTRYPOINT_NOT_INSTANTIABLE, entrypoint + ": " + rootMessage(e));
        }

        return new Loaded(new LoadedFunction(instance, loader, address, version));
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
        try {
            loader.close();
        } catch (IOException ignored) {
            // The loader never defined anything usable; nothing to release beyond closing the jar handle.
        }
    }
}
