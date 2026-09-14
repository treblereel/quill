package org.treblereel.mcp.core;

import java.io.IOException;
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

    public record ScanResult(
            IndexView index, List<ClassRecord> classes, int cacheHits, int cacheShards) {}

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
        return scan(ClassFileSnapshot.capture(classesDirs), sourceRoots);
    }

    public static ScanResult scan(ClassFileSnapshot classFiles, List<Path> sourceRoots) {
        return scan(classFiles, sourceRoots, null, null);
    }

    public static ScanResult scan(
            ClassFileSnapshot classFiles, List<Path> sourceRoots, Path sourceTokenCache) {
        return scan(classFiles, sourceRoots, sourceTokenCache, null);
    }

    public static ScanResult scan(ClassFileSnapshot classFiles, List<Path> sourceRoots,
            Path sourceTokenCache, Path applicationIndexCache) {
        ApplicationIndexCache.Result cached =
                ApplicationIndexCache.loadOrBuild(classFiles, applicationIndexCache);
        return new ScanResult(cached.index(),
                extractClasses(cached.index(), sourceRoots, sourceTokenCache),
                cached.hits(), cached.shardCount());
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

    public static List<ClassRecord> extractClasses(IndexView index) {
        return extractClasses(index, List.of());
    }

    public static List<ClassRecord> extractClasses(IndexView index, List<Path> sourceRoots) {
        return extractClasses(index, sourceRoots, null);
    }

    static List<ClassRecord> extractClasses(
            IndexView index, List<Path> sourceRoots, Path sourceTokenCache) {
        List<ClassRecord> result = new ArrayList<>();
        List<ClassInfo> knownClasses = List.copyOf(index.getKnownClasses());
        Map<String, Path> sourceFiles = sourceFilesByRelativePath(sourceRoots);
        Map<String, Path> sourcesByClass = new HashMap<>();
        Set<Path> matchedSources = new LinkedHashSet<>();
        for (ClassInfo ci : knownClasses) {
            Path source = sourceFiles.get(ci.name().toString().replace('.', '/') + ".java");
            if (source != null) {
                sourcesByClass.put(ci.name().toString(), source);
                matchedSources.add(source);
            }
        }
        SourceTokenCache.Result tokenResult = SourceTokenCache.count(
                matchedSources, sourceTokenCache);
        Map<Path, Integer> tokenCounts = tokenResult.tokenCounts();
        if (sourceTokenCache != null && tokenResult.hits() > 0) {
            System.err.println("[quill] Reusing source token counts for "
                    + tokenResult.hits() + "/" + matchedSources.size() + " files...");
        }
        for (ClassInfo ci : knownClasses) {
            String sourceFile = null;
            int sourceTokens = 0;
            Path source = sourcesByClass.get(ci.name().toString());
            if (source != null) {
                sourceFile = source.toString();
                sourceTokens = tokenCounts.getOrDefault(source, 0);
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

    private static Map<String, Path> sourceFilesByRelativePath(List<Path> sourceRoots) {
        Map<String, Path> result = new HashMap<>();
        for (Path root : sourceRoots) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> result.putIfAbsent(
                                root.relativize(path).toString().replace('\\', '/'), path));
            } catch (IOException ignored) {
                // An unreadable source root leaves its classes marked as orphan output.
            }
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

    public static List<ExternalDepRecord> extractExternalDeps(
            IndexView index, Map<String, Integer> classNameToId) {
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
