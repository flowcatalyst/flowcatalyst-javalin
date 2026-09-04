package io.flowcatalyst.tools;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.reflect.AccessFlag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/// Emits the GraalVM `reflect-config.json` for our own classes — only the
/// ones something reflects over, so everything else stays eligible for
/// dead-code elimination:
///
///   - **records** (Jackson reads components and calls the canonical
///     constructor; also the sealed-outcome DTOs)
///   - **enums** (`values()` / `valueOf` through Jackson)
///   - classes carrying a **Jackson annotation** (the few hand-written DTOs)
///   - **jOOQ generated record types** (`Tools.newRecord` instantiates them
///     reflectively; the tables and keys are plain statics and need nothing)
///
/// Registering every class instead (the first trial did) made 15,000 of our
/// methods image entry points, kept all of jOOQ reachable, and through
/// `org.jooq.tools.reflect.Compile` pulled the Java compiler into the binary.
///
///   java io.flowcatalyst.tools.NativeReflectConfig <out.json> <classes-dir>...
public final class NativeReflectConfig {

    private static final String FULL = "\"allDeclaredConstructors\":true,\"allPublicConstructors\":true,"
            + "\"allDeclaredMethods\":true,\"allPublicMethods\":true,\"allDeclaredFields\":true,"
            + "\"allPublicFields\":true";
    private static final String RECORD = FULL + ",\"allRecordComponents\":true";
    private static final String ENUM = "\"allPublicMethods\":true,\"allDeclaredFields\":true";
    private static final String CONSTRUCT = "\"allDeclaredConstructors\":true,\"allPublicConstructors\":true";

    private NativeReflectConfig() {}

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        List<String> entries = new ArrayList<>();
        int records = 0, enums = 0, jackson = 0, jooq = 0;
        for (int i = 1; i < args.length; i++) {
            Path dir = Path.of(args[i]);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".class"))::iterator) {
                    ClassModel cm = ClassFile.of().parse(Files.readAllBytes(p));
                    String name = cm.thisClass().asInternalName();
                    if (name.endsWith("module-info") || name.endsWith("package-info")) continue;
                    String dotted = name.replace('/', '.');
                    String superName = cm.superclass().map(c -> c.asInternalName()).orElse("");
                    if (superName.equals("java/lang/Record")) {
                        entries.add(entry(dotted, RECORD)); records++;
                    } else if (cm.flags().has(AccessFlag.ENUM)) {
                        entries.add(entry(dotted, ENUM)); enums++;
                    } else if (hasJacksonAnnotation(cm)) {
                        entries.add(entry(dotted, FULL)); jackson++;
                    } else if (name.startsWith("io/flowcatalyst/db/generated/tables/records/")) {
                        entries.add(entry(dotted, CONSTRUCT)); jooq++;
                    }
                }
            }
        }
        entries.sort(null);
        Files.createDirectories(out.getParent());
        Files.writeString(out, "[\n" + String.join(",\n", entries) + "\n]\n");
        System.out.printf("wrote %d reflection entries to %s (records=%d enums=%d jackson=%d jooq-records=%d)%n",
                entries.size(), out, records, enums, jackson, jooq);
    }

    private static boolean hasJacksonAnnotation(ClassModel cm) {
        return cm.findAttribute(java.lang.classfile.Attributes.runtimeVisibleAnnotations())
                .map(RuntimeVisibleAnnotationsAttribute::annotations)
                .map(list -> list.stream().anyMatch(a -> a.className().stringValue().contains("jackson")))
                .orElse(false);
    }

    private static String entry(String name, String flags) {
        return "  {\"name\":\"" + name + "\"," + flags + "}";
    }
}
