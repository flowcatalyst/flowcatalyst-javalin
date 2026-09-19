package io.flowcatalyst.function;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Spec `docs/spec/function-host-core.md` §1, L10: the built jar carries no
/// preview bit and stays at `release 21` (mutant: add `--enable-preview`
/// back to this module's compiler/surefire config), and every public
/// signature under `io.flowcatalyst.function` is a JDK type, `javax.sql.*`,
/// or this package (mutant: widen a method to return/accept a third-party
/// type — nothing here does today, since the module has zero dependencies
/// to draw one from).
class FunctionSignatureTest {

    private static final Path CLASSES_DIR = Paths.get("target", "classes", "io", "flowcatalyst", "function");
    private static final int JAVA_21_MAJOR = 65;
    private static final int NO_PREVIEW_MINOR = 0;

    @Test
    void classFilesAreRelease21WithNoPreviewBit() throws IOException {
        List<Path> classFiles = listClassFiles();
        assertThat(classFiles).as("compiled classes under " + CLASSES_DIR).isNotEmpty();

        for (Path classFile : classFiles) {
            try (DataInputStream in = new DataInputStream(Files.newInputStream(classFile))) {
                int magic = in.readInt();
                assertThat(magic).as(classFile + " magic").isEqualTo(0xCAFEBABE);
                int minor = in.readUnsignedShort();
                int major = in.readUnsignedShort();
                assertThat(major).as(classFile + " major version (release 21 = 65)").isEqualTo(JAVA_21_MAJOR);
                assertThat(minor).as(classFile + " minor version (0xFFFF marks a preview class file)")
                        .isEqualTo(NO_PREVIEW_MINOR);
            }
        }
    }

    @Test
    void everyPublicSignatureStaysInsideTheAllowedTypes() throws ClassNotFoundException, IOException {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : loadPublicClasses()) {
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                if (!Modifier.isPublic(ctor.getModifiers())) continue;
                checkExecutable(ctor, ctor.getName() + " constructor", violations);
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                String where = type.getName() + "#" + method.getName();
                checkType(method.getGenericReturnType(), violations, where + " return type");
                checkExecutable(method, where, violations);
            }
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isPublic(field.getModifiers())) continue;
                checkType(field.getGenericType(), violations, type.getName() + "#" + field.getName() + " field");
            }
        }
        assertThat(violations).as("public signatures outside java.*, javax.sql.* and io.flowcatalyst.function")
                .isEmpty();
    }

    private static void checkExecutable(Executable executable, String where, List<String> violations) {
        for (Type param : executable.getGenericParameterTypes()) {
            checkType(param, violations, where + " parameter");
        }
        for (Type exceptionType : executable.getGenericExceptionTypes()) {
            checkType(exceptionType, violations, where + " throws");
        }
    }

    private static void checkType(Type type, List<String> violations, String where) {
        switch (type) {
            case Class<?> c -> {
                Class<?> component = c.isArray() ? c.getComponentType() : c;
                if (!component.isPrimitive() && !allowed(component)) {
                    violations.add(where + " -> " + c.getName());
                }
            }
            case ParameterizedType pt -> {
                checkType(pt.getRawType(), violations, where);
                for (Type arg : pt.getActualTypeArguments()) checkType(arg, violations, where);
            }
            case GenericArrayType gat -> checkType(gat.getGenericComponentType(), violations, where);
            case WildcardType wt -> {
                for (Type t : wt.getUpperBounds()) checkType(t, violations, where);
                for (Type t : wt.getLowerBounds()) checkType(t, violations, where);
            }
            case TypeVariable<?> tv -> {
                for (Type t : tv.getBounds()) checkType(t, violations, where);
            }
            default -> throw new IllegalStateException("unhandled reflective Type: " + type.getClass());
        }
    }

    private static boolean allowed(Class<?> type) {
        String name = type.getName();
        return name.startsWith("java.") || name.startsWith("javax.sql.") || name.startsWith("io.flowcatalyst.function.");
    }

    private static List<Class<?>> loadPublicClasses() throws ClassNotFoundException, IOException {
        List<Class<?>> classes = new ArrayList<>();
        for (Path classFile : listClassFiles()) {
            String fileName = classFile.getFileName().toString();
            String simpleName = fileName.substring(0, fileName.length() - ".class".length());
            Class<?> type = Class.forName("io.flowcatalyst.function." + simpleName);
            if (Modifier.isPublic(type.getModifiers())) {
                classes.add(type);
            }
        }
        return classes;
    }

    private static List<Path> listClassFiles() throws IOException {
        try (Stream<Path> paths = Files.list(CLASSES_DIR)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".class")).toList();
        }
    }
}
