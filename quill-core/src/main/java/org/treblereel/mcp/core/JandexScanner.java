package org.treblereel.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.jboss.jandex.*;
import org.treblereel.mcp.model.ClassAnnotationRecord;
import org.treblereel.mcp.model.ClassMemberRecord;
import org.treblereel.mcp.model.MemberAnnotationRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.ExternalDepRecord;
import org.treblereel.mcp.model.FrameworkEndpointRecord;

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
            IndexView index, List<ClassRecord> classes,
            Map<String, KotlinMetadataReader.Result> kotlinMetadata,
            int cacheHits, int cacheShards) {}

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
                extractClasses(cached.index(), sourceRoots, sourceTokenCache,
                        BytecodeSourceMapper.map(classFiles, sourceRoots)),
                extractKotlinMetadata(cached.index()),
                cached.hits(), cached.shardCount());
    }

    private static Map<String, KotlinMetadataReader.Result> extractKotlinMetadata(
            IndexView index) {
        Map<String, KotlinMetadataReader.Result> result = new LinkedHashMap<>();
        index.getKnownClasses().stream()
                .sorted(Comparator.comparing(value -> value.name().toString()))
                .forEach(classInfo -> {
                    KotlinMetadataReader.Result metadata = KotlinMetadataReader.read(classInfo);
                    if (metadata.status() != KotlinMetadataReader.Status.ABSENT) {
                        result.put(classInfo.name().toString(), metadata);
                    }
                });
        return Map.copyOf(result);
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
        return extractClasses(index, sourceRoots, sourceTokenCache, Map.of());
    }

    private static List<ClassRecord> extractClasses(
            IndexView index, List<Path> sourceRoots, Path sourceTokenCache,
            Map<String, Path> bytecodeSources) {
        List<ClassRecord> result = new ArrayList<>();
        List<ClassInfo> knownClasses = List.copyOf(index.getKnownClasses());
        Map<String, Path> sourceFiles = sourceFilesByRelativePath(sourceRoots);
        Map<String, Path> sourcesByClass = new HashMap<>();
        Set<Path> matchedSources = new LinkedHashSet<>();
        for (ClassInfo ci : knownClasses) {
            String className = ci.name().toString();
            String classPath = className.replace('.', '/');
            Path source = bytecodeSources.get(className);
            if (source == null) source = sourceFiles.get(classPath + ".java");
            if (source == null) source = sourceFiles.get(classPath + ".kt");
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
                        .filter(path -> path.toString().endsWith(".java")
                                || path.toString().endsWith(".kt"))
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

    /** Extracts declared class annotations and their resolvable meta-annotation closure. */
    public static List<ClassAnnotationRecord> extractClassAnnotations(
            IndexView applicationIndex, IndexView lookupIndex,
            Map<String, Integer> classNameToId) {
        List<ClassAnnotationRecord> result = new ArrayList<>();
        Set<ClassAnnotationKey> seen = new HashSet<>();
        List<ClassInfo> classes = applicationIndex.getKnownClasses().stream()
                .sorted(Comparator.comparing(value -> value.name().toString()))
                .toList();
        for (ClassInfo classInfo : classes) {
            Integer classId = classNameToId.get(classInfo.name().toString());
            if (classId == null) continue;
            List<AnnotationInstance> declared = classInfo.declaredAnnotations().stream()
                    .sorted(Comparator.comparing(value -> value.name().toString()))
                    .toList();
            for (AnnotationInstance annotation : declared) {
                String directName = annotation.name().toString();
                addClassAnnotation(result, seen,
                        new ClassAnnotationRecord(classId, directName, true, null));
                collectMetaAnnotations(classId, annotation.name(), directName, lookupIndex,
                        new HashSet<>(), result, seen);
            }
        }
        return result;
    }

    private static void collectMetaAnnotations(int classId, DotName annotationName,
            String viaAnnotation, IndexView lookupIndex, Set<DotName> visited,
            List<ClassAnnotationRecord> result, Set<ClassAnnotationKey> seen) {
        if (!visited.add(annotationName)) return;
        ClassInfo annotationClass = lookupIndex.getClassByName(annotationName);
        if (annotationClass == null) return;
        for (AnnotationInstance meta : annotationClass.declaredAnnotations().stream()
                .sorted(Comparator.comparing(value -> value.name().toString())).toList()) {
            if (meta.name().equals(annotationName)
                    || meta.name().toString().equals(viaAnnotation)) continue;
            addClassAnnotation(result, seen,
                    new ClassAnnotationRecord(classId, meta.name().toString(), false,
                            viaAnnotation));
            collectMetaAnnotations(classId, meta.name(), viaAnnotation, lookupIndex, visited,
                    result, seen);
        }
    }

    private static void addClassAnnotation(List<ClassAnnotationRecord> result,
            Set<ClassAnnotationKey> seen, ClassAnnotationRecord annotation) {
        ClassAnnotationKey key = new ClassAnnotationKey(annotation.classId(),
                annotation.annotationName(), annotation.direct(), annotation.viaAnnotation());
        if (seen.add(key)) result.add(annotation);
    }

    private record ClassAnnotationKey(
            int classId, String annotationName, boolean direct, String viaAnnotation) {}

    /** Extracts declared fields, constructors, and methods for symbol inspection. */
    public static List<ClassMemberRecord> extractClassMembers(
            IndexView index, Map<String, Integer> classNameToId) {
        List<ClassMemberRecord> result = new ArrayList<>();
        for (ClassInfo classInfo : index.getKnownClasses().stream()
                .sorted(Comparator.comparing(value -> value.name().toString())).toList()) {
            Integer classId = classNameToId.get(classInfo.name().toString());
            if (classId == null) continue;
            for (FieldInfo field : classInfo.fields().stream()
                    .filter(value -> !synthetic(value.flags()))
                    .sorted(Comparator.comparing(FieldInfo::name)).toList()) {
                String type = field.type().toString();
                result.add(new ClassMemberRecord(classId, "FIELD", field.name(),
                        field.name() + ":" + type,
                        field.type().descriptor(identifier -> resolveTypeVariable(
                                identifier, List.of(), classInfo.typeParameters())),
                        type, List.of(),
                        java.lang.reflect.Modifier.toString(field.flags()),
                        memberAnnotations(field.annotations(), AnnotationTarget.Kind.FIELD),
                        annotationDetails(field.annotations())));
            }
            for (MethodInfo method : classInfo.methods().stream()
                    .filter(value -> !"<clinit>".equals(value.name()))
                    .filter(value -> !synthetic(value.flags()))
                    .filter(value -> (value.flags() & 0x0040) == 0)
                    .sorted(Comparator.comparing(MethodInfo::name)
                            .thenComparing(value -> value.parameterTypes().toString()))
                    .toList()) {
                boolean constructor = "<init>".equals(method.name());
                List<String> parameters = method.parameterTypes().stream()
                        .map(Type::toString).toList();
                String type = constructor ? classInfo.name().toString()
                        : method.returnType().toString();
                String simpleName = classInfo.simpleName();
                String name = constructor
                        ? (simpleName == null || simpleName.isBlank() ? "<init>" : simpleName)
                        : method.name();
                String signature = name + "(" + String.join(",", parameters) + ")"
                        + (constructor ? "" : ":" + type);
                result.add(new ClassMemberRecord(classId,
                        constructor ? "CONSTRUCTOR" : "METHOD", name, signature,
                        method.descriptor(identifier -> resolveTypeVariable(
                                identifier, method.typeParameters(), classInfo.typeParameters())),
                        type, parameters, java.lang.reflect.Modifier.toString(method.flags()),
                        methodAnnotations(method.annotations()),
                        annotationDetails(method.annotations())));
            }
        }
        return result;
    }

    /** Extracts Spring MVC and JAX-RS HTTP routes, including constant annotation paths. */
    public static List<FrameworkEndpointRecord> extractFrameworkEndpoints(
            IndexView index, Map<String, Integer> classNameToId) {
        List<FrameworkEndpointRecord> result = new ArrayList<>();
        for (ClassInfo classInfo : index.getKnownClasses().stream()
                .sorted(Comparator.comparing(value -> value.name().toString())).toList()) {
            Integer classId = classNameToId.get(classInfo.name().toString());
            if (classId == null) continue;
            List<AnnotationInstance> classAnnotations = classInfo.declaredAnnotations();
            List<String> springClassPaths = annotationPaths(classAnnotations,
                    Set.of("org.springframework.web.bind.annotation.RequestMapping"));
            List<String> jaxClassPaths = annotationPaths(classAnnotations,
                    Set.of("jakarta.ws.rs.Path", "javax.ws.rs.Path"));
            for (MethodInfo method : classInfo.methods()) {
                if (method.name().startsWith("<") || synthetic(method.flags())) continue;
                List<AnnotationInstance> annotations = method.annotations().stream()
                        .filter(annotation -> annotation.target() != null
                                && annotation.target().kind() == AnnotationTarget.Kind.METHOD)
                        .toList();
                EndpointMetadata metadata = endpointMetadata(annotations);
                if (metadata == null) continue;
                List<String> parameters = method.parameterTypes().stream()
                        .map(Type::toString).toList();
                String signature = method.name() + "(" + String.join(",", parameters) + "):"
                        + method.returnType();
                result.add(new FrameworkEndpointRecord(
                        classId, classInfo.name().toString(), method.name(), signature,
                        method.descriptor(identifier -> resolveTypeVariable(
                                identifier, method.typeParameters(), classInfo.typeParameters())),
                        metadata.framework(), metadata.httpMethods(),
                        "spring".equals(metadata.framework()) ? springClassPaths : jaxClassPaths,
                        metadata.paths(), metadata.annotations()));
            }
        }
        return result;
    }

    private static EndpointMetadata endpointMetadata(List<AnnotationInstance> annotations) {
        Set<String> methods = new TreeSet<>();
        Set<String> paths = new LinkedHashSet<>();
        Set<String> matches = new TreeSet<>();
        String framework = null;
        for (AnnotationInstance annotation : annotations) {
            String name = annotation.name().toString();
            String verb = switch (name) {
                case "jakarta.ws.rs.GET", "javax.ws.rs.GET" -> "GET";
                case "jakarta.ws.rs.POST", "javax.ws.rs.POST" -> "POST";
                case "jakarta.ws.rs.PUT", "javax.ws.rs.PUT" -> "PUT";
                case "jakarta.ws.rs.DELETE", "javax.ws.rs.DELETE" -> "DELETE";
                case "jakarta.ws.rs.PATCH", "javax.ws.rs.PATCH" -> "PATCH";
                case "jakarta.ws.rs.HEAD", "javax.ws.rs.HEAD" -> "HEAD";
                case "jakarta.ws.rs.OPTIONS", "javax.ws.rs.OPTIONS" -> "OPTIONS";
                case "org.springframework.web.bind.annotation.GetMapping" -> "GET";
                case "org.springframework.web.bind.annotation.PostMapping" -> "POST";
                case "org.springframework.web.bind.annotation.PutMapping" -> "PUT";
                case "org.springframework.web.bind.annotation.DeleteMapping" -> "DELETE";
                case "org.springframework.web.bind.annotation.PatchMapping" -> "PATCH";
                default -> null;
            };
            boolean springMapping = name.startsWith("org.springframework.web.bind.annotation.")
                    && (verb != null || name.endsWith("RequestMapping"));
            boolean jaxMapping = verb != null && (name.startsWith("jakarta.ws.rs.")
                    || name.startsWith("javax.ws.rs."));
            boolean jaxPath = name.equals("jakarta.ws.rs.Path")
                    || name.equals("javax.ws.rs.Path");
            if (!springMapping && !jaxMapping && !jaxPath) continue;
            matches.add(name);
            if (springMapping) framework = "spring";
            else if (framework == null) framework = "jax-rs";
            if (verb != null) methods.add(verb);
            if (name.endsWith("RequestMapping")) {
                AnnotationValue methodValue = annotation.value("method");
                if (methodValue != null) {
                    try {
                        methods.addAll(Arrays.asList(methodValue.asEnumArray()));
                    } catch (IllegalArgumentException ignored) {
                        methods.add(methodValue.asEnum());
                    }
                }
            }
            paths.addAll(annotationPaths(annotation));
        }
        if (framework == null) return null;
        if (methods.isEmpty()) methods.add("jax-rs".equals(framework) ? "SUBRESOURCE" : "ANY");
        if (paths.isEmpty()) paths.add("");
        return new EndpointMetadata(framework, List.copyOf(methods),
                List.copyOf(paths), List.copyOf(matches));
    }

    private static List<String> annotationPaths(
            Collection<AnnotationInstance> annotations, Set<String> accepted) {
        return annotations.stream()
                .filter(annotation -> accepted.contains(annotation.name().toString()))
                .flatMap(annotation -> annotationPaths(annotation).stream())
                .distinct().toList();
    }

    private static List<String> annotationPaths(AnnotationInstance annotation) {
        AnnotationValue value = annotation.value("path");
        if (value == null) value = annotation.value("value");
        if (value == null) return List.of();
        try {
            if (value.kind() == AnnotationValue.Kind.ARRAY) {
                return Arrays.asList(value.asStringArray());
            }
            return List.of(value.asString());
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }

    private record EndpointMetadata(
            String framework, List<String> httpMethods, List<String> paths,
            List<String> annotations) {}

    private static boolean synthetic(short flags) {
        return (flags & 0x1000) != 0;
    }

    private static Type resolveTypeVariable(String identifier,
            List<TypeVariable> methodVariables, List<TypeVariable> classVariables) {
        return Stream.concat(methodVariables.stream(), classVariables.stream())
                .filter(variable -> variable.identifier().equals(identifier))
                .findFirst()
                .flatMap(variable -> variable.bounds().stream().findFirst())
                .orElseGet(() -> Type.create(Object.class));
    }

    private static List<String> memberAnnotations(
            Collection<AnnotationInstance> annotations, AnnotationTarget.Kind targetKind) {
        return annotations.stream()
                .filter(annotation -> annotation.target() != null
                        && annotation.target().kind() == targetKind)
                .map(annotation -> annotation.name().toString())
                .distinct().sorted().toList();
    }

    private static List<String> methodAnnotations(Collection<AnnotationInstance> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.target() != null
                        && (annotation.target().kind() == AnnotationTarget.Kind.METHOD
                                || annotation.target().kind()
                                        == AnnotationTarget.Kind.METHOD_PARAMETER))
                .map(annotation -> annotation.name().toString())
                .distinct().sorted().toList();
    }

    private static List<MemberAnnotationRecord> annotationDetails(
            Collection<AnnotationInstance> annotations) {
        return annotations.stream()
                .filter(annotation -> annotation.target() != null)
                .filter(annotation -> annotation.target().kind() == AnnotationTarget.Kind.FIELD
                        || annotation.target().kind() == AnnotationTarget.Kind.METHOD
                        || annotation.target().kind()
                                == AnnotationTarget.Kind.METHOD_PARAMETER)
                .map(annotation -> {
                    if (annotation.target().kind()
                            == AnnotationTarget.Kind.METHOD_PARAMETER) {
                        var parameter = annotation.target().asMethodParameter();
                        return new MemberAnnotationRecord(annotation.name().toString(),
                                "METHOD_PARAMETER", (int) parameter.position(),
                                parameter.nameOrDefault(), parameter.type().toString());
                    }
                    return new MemberAnnotationRecord(annotation.name().toString(),
                            annotation.target().kind().name(), null, null, null);
                })
                .sorted(Comparator.comparing(MemberAnnotationRecord::annotationName)
                        .thenComparing(MemberAnnotationRecord::targetKind)
                        .thenComparing(value -> value.parameterIndex() == null
                                ? -1 : value.parameterIndex()))
                .toList();
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
