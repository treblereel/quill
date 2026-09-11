package org.treblereel.mcp.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.jboss.jandex.*;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ExternalDepRecord;

public final class JandexScanner {

    private static final Set<DotName> BEAN_DEFINING_ANNOTATIONS = Set.of(
            DotName.createSimple("jakarta.enterprise.context.ApplicationScoped"),
            DotName.createSimple("jakarta.enterprise.context.RequestScoped"),
            DotName.createSimple("jakarta.enterprise.context.SessionScoped"),
            DotName.createSimple("jakarta.enterprise.context.Dependent"),
            DotName.createSimple("jakarta.inject.Singleton"),
            DotName.createSimple("jakarta.enterprise.inject.Model"),
            DotName.createSimple("org.springframework.stereotype.Component"),
            DotName.createSimple("org.springframework.stereotype.Service"),
            DotName.createSimple("org.springframework.stereotype.Repository"),
            DotName.createSimple("org.springframework.stereotype.Controller"),
            DotName.createSimple("org.springframework.web.bind.annotation.RestController"),
            DotName.createSimple("org.springframework.context.annotation.Configuration")
    );

    private JandexScanner() {}

    public record ScanResult(Index index, List<ClassRecord> classes) {}

    public static ScanResult scan(Path classesDir) {
        return scan(List.of(classesDir));
    }

    public static ScanResult scan(List<Path> classesDirs) {
        List<Path> sourceRoots = classesDirs.stream()
                .map(JandexScanner::sourceRoot)
                .filter(Files::isDirectory)
                .toList();
        return scan(classesDirs, sourceRoots);
    }

    public static ScanResult scan(List<Path> classesDirs, List<Path> sourceRoots) {
        Indexer indexer = new Indexer();
        for (Path classesDir : classesDirs) {
            try (Stream<Path> files = Files.walk(classesDir)) {
                files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    try (InputStream is = Files.newInputStream(p)) {
                        indexer.index(is);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to index " + p, e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException("Failed to scan " + classesDir, e);
            }
        }
        Index index = indexer.complete();

        return new ScanResult(index, extractClasses(index, sourceRoots));
    }

    private static Path sourceRoot(Path classesDir) {
        Path normalized = classesDir.toAbsolutePath().normalize();
        if (normalized.endsWith("target/classes")) {
            return normalized.getParent().getParent().resolve("src/main/java");
        }
        try {
            return BuildSystem.GRADLE.moduleDir(normalized).resolve("src/main/java");
        } catch (IllegalArgumentException ignored) {
            return normalized.resolve("src/main/java");
        }
    }

    public static List<ClassRecord> extractClasses(Index index) {
        return extractClasses(index, List.of());
    }

    public static List<ClassRecord> extractClasses(Index index, List<Path> sourceRoots) {
        List<ClassRecord> result = new ArrayList<>();
        for (ClassInfo ci : index.getKnownClasses()) {
            String relativePath = ci.name().toString().replace('.', '/') + ".java";
            String sourceFile = null;
            int sourceTokens = 0;
            for (Path root : sourceRoots) {
                Path src = root.resolve(relativePath);
                if (Files.isRegularFile(src)) {
                    sourceFile = src.toString();
                    try {
                        sourceTokens = TokenCounter.count(Files.readString(src));
                    } catch (IOException e) {
                        // leave 0
                    }
                    break;
                }
            }
            result.add(new ClassRecord(
                    0,
                    ci.name().toString(),
                    classKind(ci),
                    ci.superName() != null ? ci.superName().toString() : null,
                    ci.interfaceNames().stream().map(DotName::toString).toList(),
                    sourceFile,
                    0,
                    isBean(ci),
                    sourceTokens,
                    null,
                    sourceFile == null ? "orphan_output" : "source",
                    "current"
            ));
        }
        return result;
    }

    private static String classKind(ClassInfo ci) {
        if (ci.isAnnotation()) return "ANNOTATION";
        if (ci.isInterface()) return "INTERFACE";
        if (ci.isEnum()) return "ENUM";
        if (ci.isRecord()) return "RECORD";
        return "CLASS";
    }

    private static boolean isBean(ClassInfo ci) {
        for (DotName ann : BEAN_DEFINING_ANNOTATIONS) {
            if (ci.hasDeclaredAnnotation(ann)) return true;
        }
        return false;
    }

    public static List<ExternalDepRecord> extractExternalDeps(Index index, Map<String, Integer> classNameToId) {
        Set<String> knownClasses = new HashSet<>();
        for (ClassInfo ci : index.getKnownClasses()) {
            knownClasses.add(ci.name().toString());
        }

        List<ExternalDepRecord> result = new ArrayList<>();
        for (ClassInfo ci : index.getKnownClasses()) {
            Integer classId = classNameToId.get(ci.name().toString());
            if (classId == null) continue;

            Set<String> seen = new HashSet<>();

            if (ci.superName() != null) {
                collectExternal(ci.superName().toString(), "EXTENDS", classId, knownClasses, seen, result);
            }
            for (DotName iface : ci.interfaceNames()) {
                collectExternal(iface.toString(), "IMPLEMENTS", classId, knownClasses, seen, result);
            }
            for (FieldInfo fi : ci.fields()) {
                collectTypeRefs(fi.type(), "FIELD", classId, knownClasses, seen, result);
            }
            for (MethodInfo mi : ci.methods()) {
                if (mi.name().equals("<init>") || mi.name().equals("<clinit>")) continue;
                collectTypeRefs(mi.returnType(), "METHOD", classId, knownClasses, seen, result);
                for (Type pt : mi.parameterTypes()) {
                    collectTypeRefs(pt, "METHOD", classId, knownClasses, seen, result);
                }
            }
            for (AnnotationInstance ai : ci.declaredAnnotations()) {
                collectExternal(ai.name().toString(), "ANNOTATION", classId, knownClasses, seen, result);
            }
        }
        return result;
    }

    private static void collectTypeRefs(Type type, String kind, int classId,
                                         Set<String> knownClasses, Set<String> seen,
                                         List<ExternalDepRecord> result) {
        if (type == null) return;
        switch (type.kind()) {
            case CLASS -> collectExternal(type.name().toString(), kind, classId, knownClasses, seen, result);
            case PARAMETERIZED_TYPE -> {
                collectExternal(type.asParameterizedType().name().toString(), kind, classId, knownClasses, seen, result);
                for (Type arg : type.asParameterizedType().arguments()) {
                    collectTypeRefs(arg, kind, classId, knownClasses, seen, result);
                }
            }
            case ARRAY -> collectTypeRefs(type.asArrayType().constituent(), kind, classId, knownClasses, seen, result);
            case WILDCARD_TYPE -> {
                collectTypeRefs(type.asWildcardType().extendsBound(), kind, classId, knownClasses, seen, result);
                collectTypeRefs(type.asWildcardType().superBound(), kind, classId, knownClasses, seen, result);
            }
            default -> { /* PRIMITIVE, VOID, TYPE_VARIABLE — skip */ }
        }
    }

    private static void collectExternal(String typeName, String kind, int classId,
                                         Set<String> knownClasses, Set<String> seen,
                                         List<ExternalDepRecord> result) {
        if (knownClasses.contains(typeName)) return;
        if (typeName.startsWith("java.") || typeName.startsWith("javax.")) return;
        String key = typeName + ":" + kind;
        if (!seen.add(key)) return;
        result.add(new ExternalDepRecord(classId, typeName, kind));
    }
}
