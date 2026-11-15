package org.treblereel.mcp.mcp.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Indexer;
import org.jboss.jandex.MethodInfo;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.support.NameSimilarity;
import org.treblereel.mcp.mcp.project.ProjectDependencyQueries;
import org.treblereel.mcp.core.Json;

/** Lazy, bounded lookup of declarations in resolved dependency JARs. */
public final class ExternalSymbolQueries {
    private static final int MEMBER_CANDIDATES = 10;


    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int MAX_PROJECT_CACHES = 8;
    private static final ConcurrentHashMap<Path, Catalog> CATALOGS = new ConcurrentHashMap<>();

    public String search(ReadQueryContext reader, Path root, String pattern, String kind, String className,
            String library, String matchMode, boolean includeOccurrences, int limit, int offset) {
        if (pattern == null || pattern.isBlank()) return error("Search pattern must not be blank");
        String requestedKind = kind == null || kind.isBlank()
                ? "all" : kind.strip().toLowerCase(Locale.ROOT);
        boolean memberSearch = Set.of("field", "constructor", "method").contains(requestedKind);
        if (memberSearch && (className == null || className.isBlank())) {
            return error("Member search requires class_name to bound dependency bytecode scanning");
        }
        String needle = pattern.strip().toLowerCase(Locale.ROOT).replace("*", "");
        MatchMode matching = MatchMode.parse(matchMode, pattern);
        if (matching == null) return error("match_mode must be exact, prefix, contains or glob");
        String libraryNeedle = library == null ? ""
                : library.strip().toLowerCase(Locale.ROOT).replace("*", "");
        Catalog catalog = catalog(reader, root);
        List<ClassRef> candidates = catalog.classes().stream()
                .filter(ref -> className == null || className.isBlank()
                        || matchesClassName(ref.className(), className))
                .filter(ref -> memberSearch
                        || matches(ref.className(), needle, matching))
                .filter(ref -> libraryNeedle.isEmpty()
                        || ref.className().toLowerCase(Locale.ROOT).contains(libraryNeedle)
                        || ref.artifact().toLowerCase(Locale.ROOT).contains(libraryNeedle))
                .sorted(Comparator.comparing(ClassRef::className)).toList();
        if (memberSearch) {
            boolean buildTime = false;
            if (candidates.isEmpty()) {
                // V66 CQ-005: details read a deployment processor that this search then said had no methods.
                candidates = buildTimeClasses(reader, root, catalog).stream()
                        .filter(ref -> matchesClassName(ref.className(), className))
                        .sorted(Comparator.comparing(ClassRef::className)).toList();
                buildTime = !candidates.isEmpty();
            }
            ObjectNode members = searchMembers(catalog, candidates, pattern, requestedKind, matching, limit, offset);
            if (buildTime) members.put("source", "quarkus_deployment_bytecode").put("classpath_scope",
                    "Quarkus deployment artifact from the local Maven repository: runs at build time, not on the application classpath");
            return members.toString();
        }
        Map<String, List<ClassRef>> grouped = new LinkedHashMap<>();
        candidates.forEach(match -> grouped.computeIfAbsent(
                match.className(), ignored -> new ArrayList<>()).add(match));
        List<Map.Entry<String, List<ClassRef>>> matches = grouped.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + limit, matches.size());
        ObjectNode result = JSON.createObjectNode();
        result.put("pattern", pattern);
        result.put("match_mode", matching.value);
        result.put("total", matches.size());
        result.put("showing", to - from);
        result.put("offset", offset);
        result.put("has_more", to < matches.size());
        ArrayNode symbols = result.putArray("symbols");
        matches.subList(from, to).forEach(match -> {
            ObjectNode item = symbols.addObject();
            item.put("kind", "class");
            item.put("name", simpleName(match.getKey()));
            item.put("class_name", match.getKey());
            ArrayNode artifacts = item.putArray("artifacts");
            match.getValue().stream().map(ClassRef::artifact).distinct().sorted()
                    .forEach(artifacts::add);
            if (includeOccurrences) {
                ArrayNode occurrences = item.putArray("occurrences");
                match.getValue().stream().sorted(Comparator.comparing(ClassRef::artifact))
                        .forEach(ref -> occurrences.addObject()
                                .put("artifact", ref.artifact()).put("jar", ref.jar().toString()));
            }
            item.put("version_count", match.getValue().size());
        });
        result.put("source", "resolved_dependency_jars");
        ObjectNode discovery = result.putObject("discovery");
        discovery.put("jar_count", catalog.jarCount());
        discovery.put("indexed_class_entries", catalog.classes().size());
        discovery.put("unique_class_names", grouped.size());
        discovery.put("cache", catalog.cacheHit() ? "hit" : "miss");
        return result.toString();
    }

    private static final int MAX_CLASSES = 5;

    /**
     * A guess abbreviates a name when it is a prefix of it or each of its camel-case words begins
     * the name's word at the same place: initHttpSecurity for initializeHttpSecurity.
     */
    static boolean abbreviates(String guess, String name) {
        if (guess.length() < 3) return false;
        if (name.startsWith(guess)) return true;
        String[] guessed = guess.split("(?=[A-Z])"), words = name.split("(?=[A-Z])");
        if (guessed.length != words.length || guessed.length < 2) return false;
        for (int i = 0; i < words.length; i++) if (!words[i].startsWith(guessed[i])) return false;
        return true;
    }

    /** A class this version does not ship: the closest names in the package it was guessed in. */
    private static void appendSimilarClasses(ObjectNode missing, List<String> classes, String className, String simple) {
        int dot = className.lastIndexOf('.');
        if (dot <= 0) return;
        String pkg = className.substring(0, dot);
        Map<String, String> bySimple = new java.util.TreeMap<>();
        classes.stream()
                .filter(candidate -> candidate.startsWith(pkg + ".") && candidate.indexOf('.', pkg.length() + 1) < 0)
                .forEach(candidate -> bySimple.putIfAbsent(simpleName(candidate), candidate));
        if (bySimple.isEmpty()) return;
        var similar = missing.putArray("similar_classes");
        NameSimilarity.closest(simple, bySimple.keySet(), 5).forEach(name -> similar.add(bySimple.get(name)));
        missing.put("similar_classes_basis", "the " + similar.size() + " closest of " + bySimple.size()
                + " indexed classes in package " + pkg);
    }

    public String details(ReadQueryContext reader, Path root, String className, int limit, int offset) {
        return details(reader, root, className, limit, offset, "full");
    }

    public String details(ReadQueryContext reader, Path root, String className, int limit, int offset, String detail) {
        return details(reader, root, className, limit, offset, detail, null);
    }

    /** {@code method} narrows members and bytecode to one method name; a constructor is named by its class. */
    public String details(ReadQueryContext reader, Path root, String className, int limit, int offset, String detail,
            String selectedMethod) {
        if (!java.util.Set.of("summary", "full", "source", "bytecode").contains(detail)) return error("detail must be summary, full, source or bytecode");
        if (className.contains(",")) {
            List<String> names = java.util.Arrays.stream(className.split(",")).map(String::strip)
                    .filter(name -> !name.isEmpty()).distinct().toList();
            if (names.size() > MAX_CLASSES) return error("At most " + MAX_CLASSES + " comma-separated classes per call");
            ObjectNode batch = JSON.createObjectNode();
            ArrayNode classes = batch.putArray("classes");
            for (String name : names) {
                try {
                    classes.add(JSON.readTree(details(reader, root, name, limit, offset, detail, selectedMethod)));
                } catch (IOException unreadable) {
                    classes.add(JSON.createObjectNode().put("class_name", name).put("error", "unreadable answer"));
                }
            }
            return batch.toString();
        }
        String method = selectedMethod == null || selectedMethod.isBlank() ? null : selectedMethod;
        Catalog catalog = catalog(reader, root);
        List<ClassRef> refs = matching(catalog.classes(), className);
        boolean buildTime = false;
        if (refs.isEmpty()) {
            refs = matching(buildTimeClasses(reader, root, catalog), className);
            buildTime = !refs.isEmpty();
        }
        if (refs.isEmpty() && ArcGeneratedClasses.describe(reader, className) instanceof ObjectNode generated)
            return generated.toString();
        if (refs.isEmpty()) {
            // A guessed package is the common miss: the same simple name settles it without a search.
            String simple = simpleName(className);
            List<String> sameName = catalog.classes().stream().map(ClassRef::className)
                    .filter(candidate -> simpleName(candidate).equals(simple)).distinct().sorted().limit(10).toList();
            ObjectNode missing = JSON.createObjectNode().put("error_code", "EXTERNAL_CLASS_NOT_FOUND")
                    .put("error", "External class not found: " + className
                            + (sameName.isEmpty() ? "; no class named " + simple + " on the indexed classpaths" : ""))
                    .put("reason", "Only JARs on the project's captured classpaths and the local-repository "
                            + "deployment artifacts of its Quarkus extensions are indexed; runtime-generated classes are absent"
                            + missingTestClasspaths(reader));
            if (!sameName.isEmpty()) sameName.forEach(missing.putArray("candidates")::add);
            else {
                missing.set("next_step", JSON.createObjectNode().put("tool", "search_external_symbols")
                        .set("arguments", JSON.createObjectNode().put("pattern", simple)));
                appendSimilarClasses(missing, java.util.stream.Stream.concat(catalog.classes().stream(),
                        buildTimeClasses(reader, root, catalog).stream()).map(ClassRef::className).toList(), className, simple);
            }
            return missing.toString();
        }
        List<String> names = refs.stream().map(ClassRef::className).distinct().toList();
        if (names.size() > 1) return ambiguous(className, names);
        ClassRef ref = refs.get(0);
        ClassInfo info = readClass(ref);
        if (info == null) return error("Could not inspect external class: " + ref.className());

        List<Member> members = new ArrayList<>();
        for (FieldInfo field : info.fields()) {
            members.add(new Member("field", field.name(), field.toString(),
                    field.type().toString(), java.lang.reflect.Modifier.toString(field.flags()),
                    field.annotations().stream().map(a -> a.toString(false)).sorted().toList()));
        }
        for (MethodInfo declared : info.methods()) {
            if (declared.name().equals("<clinit>")) continue;
            members.add(new Member(declared.name().equals("<init>") ? "constructor" : "method",
                    declared.name().equals("<init>") ? simpleName(ref.className()) : declared.name(),
                    declared.toString(), declared.returnType().toString(),
                    java.lang.reflect.Modifier.toString(declared.flags()),
                    declared.annotations().stream().filter(a -> a.target() != null
                            && a.target().kind() == org.jboss.jandex.AnnotationTarget.Kind.METHOD)
                            .map(a -> a.toString(false)).sorted().toList()));
        }
        members.sort(Comparator.comparing(Member::kind).thenComparing(Member::name)
                .thenComparing(Member::signature));
        String jvmMethod = null;
        String resolvedFrom = null;
        if (method != null) {
            String requested = method.equals("<init>") ? simpleName(ref.className()) : method;
            List<String> declaredMethods = members.stream().filter(member -> !member.kind().equals("field"))
                    .map(Member::name).distinct().toList();
            List<String> abbreviated = declaredMethods.contains(requested) ? List.of()
                    : declaredMethods.stream().filter(name -> abbreviates(requested, name)).toList();
            if (abbreviated.size() == 1) resolvedFrom = method;
            String selected = abbreviated.size() == 1 ? abbreviated.getFirst() : requested;
            List<Member> matching = members.stream().filter(member -> !member.kind().equals("field")
                    && member.name().equals(selected)).toList();
            if (matching.isEmpty()) {
                ObjectNode missing = JSON.createObjectNode().put("error_code", "EXTERNAL_MEMBER_NOT_FOUND")
                        .put("error", "No method " + method + " in " + ref.className());
                ArrayNode candidates = missing.putArray("candidates");
                List<String> methodNames = members.stream().filter(member -> !member.kind().equals("field"))
                        .map(Member::name).distinct().toList();
                NameSimilarity.closest(method, methodNames, MEMBER_CANDIDATES).forEach(candidates::add);
                if (methodNames.size() > MEMBER_CANDIDATES) missing.put("candidates_basis", "the " + MEMBER_CANDIDATES
                        + " closest of " + methodNames.size() + " method names");
                return missing.toString();
            }
            members = matching;
            jvmMethod = matching.get(0).kind().equals("constructor") ? "<init>" : selected;
        }
        int from = Math.min(offset, members.size());
        int to = Math.min(from + limit, members.size());
        ObjectNode result = JSON.createObjectNode();
        result.put("class_name", ref.className());
        result.put("kind", classKind(info));
        if (!info.declaredAnnotations().isEmpty()) result.set("annotations", JSON.valueToTree(info.declaredAnnotations().stream()
                .map(a -> a.toString(false)).sorted().toList()));
        result.put("runtime_resolution_proven", false);
        if (info.superName() != null && !info.superName().toString().equals("java.lang.Object"))
            result.put("superclass", info.superName().toString());
        if (!info.interfaceNames().isEmpty()) {
            ArrayNode interfaces = result.putArray("interfaces");
            info.interfaceNames().forEach(name -> interfaces.add(name.toString()));
        }
        result.put("artifact", ref.artifact());
        result.put("jar", ref.jar().toString());
        if (refs.size() > 1) {
            ArrayNode occurrences = result.putArray("occurrences");
            refs.forEach(candidate -> occurrences.addObject()
                    .put("artifact", candidate.artifact()).put("jar", candidate.jar().toString()));
            result.put("version_count", refs.size());
        }
        result.put("detail", detail);
        if (method != null) result.put("method", method);
        appendSource(ref, result, !detail.equals("summary"));
        if (detail.equals("source")) return result.toString();
        // Recorded agents used about 2% of these calls, so full detail points to them instead.
        // Without companion source, full holds no more than summary and agents asked again for bytecode.
        if (detail.equals("full") && !result.path("source_available").asBoolean()) {
            ObjectNode calls = methodCalls(ref, jvmMethod);
            if (jvmMethod != null || withinCallBudget(calls)) {
                result.put("detail", "bytecode");
                result.put("detail_basis", "Requested full; no companion source, so its bytecode calls instead");
                result.set("bytecode_method_calls", calls);
                detail = "bytecode";
            }
        }
        if (detail.equals("bytecode")) {
            if (!result.has("bytecode_method_calls")) result.set("bytecode_method_calls", methodCalls(ref, jvmMethod));
            result.put("bytecode_method_calls_scope", "Bounded: 40 calls, 12 strings and 12 class literals per method; invokedynamic bodies, reflection and runtime dispatch not inferred");
        } else {
            ObjectNode request = result.putObject("bytecode_request").put("class_name", ref.className()).put("detail", "bytecode");
            if (method != null) request.put("method", method);
        }
        result.put("source", buildTime ? "quarkus_deployment_bytecode" : "resolved_dependency_bytecode");
        if (buildTime) result.put("classpath_scope", "Quarkus deployment artifact from the local Maven repository: "
                + "runs at build time, not on the application classpath");
        if (resolvedFrom != null && !members.isEmpty()) result.putObject("method_resolved").put("requested", resolvedFrom)
                .put("answered", members.getFirst().name())
                .put("basis", "No method " + resolvedFrom + "; the only declared method name it abbreviates");
        result.put("total_members", members.size());
        result.put("showing", to - from);
        result.put("offset", offset);
        result.put("has_more", to < members.size());
        ArrayNode listed = result.putArray("members");
        if (to > from) result.put("member_format", "Each signature names its member: <init> is a constructor, "
                + "one without a parameter list is a field, the rest are methods");
        String owner = ref.className() + ".";
        members.subList(from, to).forEach(member -> {
            ObjectNode item = listed.addObject();
            // The signature already holds the name, kind and type, and a field's repeats its owner.
            item.put("signature", member.signature().replace(owner, ""));
            if (!member.modifiers().isEmpty()) item.put("modifiers", member.modifiers());
            if (!member.annotations().isEmpty()) item.set("annotations", JSON.valueToTree(member.annotations()));
            if (info.isAnnotation()) info.methods().stream()
                    .filter(declared -> declared.name().equals(member.name()) && declared.defaultValue() != null)
                    .findFirst().ifPresent(declared -> item.put("annotation_default", declared.defaultValue().toString()));
        });
        result.putArray("limitations")
                .add("Bytecode declarations; build-time registration and runtime activation are not established");
        return result.toString();
    }

    private static void appendSource(ClassRef ref, ObjectNode result, boolean includeText) {
        result.put("source_available", false);
        String filename = ref.jar().getFileName().toString();
        Path sources = ref.jar().resolveSibling(filename.substring(0, filename.length() - 4) + "-sources.jar");
        if (!Files.isRegularFile(sources)) return;
        String entryName = ref.className().split("\\$", 2)[0].replace('.', '/') + ".java";
        try (JarFile jar = new JarFile(sources.toFile())) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) return;
            result.put("source_available", true);
            if (!includeText) {
                result.putObject("source_request").put("class_name", ref.className()).put("detail", "source");
                return;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                String whole = withoutLicenseHeader(new String(input.readNBytes(1_000_000), java.nio.charset.StandardCharsets.UTF_8));
                result.put("source_available", true);
                ObjectNode source = result.putObject("source_excerpt");
                source.put("artifact", sources + "!/" + entryName);
                source.put("text", whole.substring(0, Math.min(whole.length(), 12000)));
                source.put("truncated", whole.length() > 12000);
                source.put("scope", "Companion source artifact; not proof that source matches compiled bytecode");
            }
        } catch (IOException | RuntimeException unavailable) {
            result.put("source_status", "unreadable");
        }
    }

    /** A leading block comment before {@code package} is a license, not the class's contract. */
    static String withoutLicenseHeader(String text) {
        String trimmed = text.stripLeading();
        if (!trimmed.startsWith("/*") || trimmed.startsWith("/**")) return text;
        int end = trimmed.indexOf("*/");
        if (end < 0) return text;
        String rest = trimmed.substring(end + 2).stripLeading();
        return rest.startsWith("package ") ? rest : text;
    }

    private static ObjectNode searchMembers(Catalog catalog, List<ClassRef> candidates,
            String pattern, String kind, MatchMode matching, int limit, int offset) {
        String needle = pattern.strip().toLowerCase(Locale.ROOT).replace("*", "");
        Map<String, ExternalMember> unique = new LinkedHashMap<>();
        for (ClassRef ref : candidates) {
            ClassInfo info = readClass(ref);
            if (info == null) continue;
            if (kind.equals("field")) {
                for (FieldInfo field : info.fields()) {
                    Member member = new Member("field", field.name(), field.toString(),
                            field.type().toString(),
                            java.lang.reflect.Modifier.toString(field.flags()));
                    addMember(unique, ref, member, needle, matching);
                }
            } else {
                for (MethodInfo method : info.methods()) {
                    if (method.name().equals("<clinit>")) continue;
                    String memberKind = method.name().equals("<init>") ? "constructor" : "method";
                    if (!kind.equals(memberKind)) continue;
                    Member member = new Member(memberKind,
                            method.name().equals("<init>") ? simpleName(ref.className()) : method.name(),
                            method.toString(), method.returnType().toString(),
                            java.lang.reflect.Modifier.toString(method.flags()));
                    addMember(unique, ref, member, needle, matching);
                }
            }
        }
        List<ExternalMember> matches = unique.values().stream()
                .sorted(Comparator.comparing((ExternalMember value) -> value.ref().className())
                        .thenComparing(value -> value.member().name())
                        .thenComparing(value -> value.member().signature())).toList();
        int from = Math.min(offset, matches.size());
        int to = Math.min(from + limit, matches.size());
        ObjectNode result = JSON.createObjectNode();
        result.put("pattern", pattern);
        result.put("kind", kind);
        result.put("match_mode", matching.value);
        result.put("total", matches.size());
        result.put("showing", to - from);
        result.put("offset", offset);
        result.put("has_more", to < matches.size());
        ArrayNode symbols = result.putArray("symbols");
        matches.subList(from, to).forEach(value -> {
            ObjectNode item = symbols.addObject();
            item.put("kind", value.member().kind());
            item.put("name", value.member().name());
            item.put("signature", value.member().signature());
            item.put("type", value.member().type());
            item.put("modifiers", value.member().modifiers());
            item.put("class_name", value.ref().className());
            item.put("artifact", value.ref().artifact());
        });
        result.put("source", "resolved_dependency_bytecode");
        result.putObject("discovery")
                .put("jar_count", catalog.jarCount())
                .put("classes_inspected", candidates.stream()
                        .map(ClassRef::className).distinct().count())
                .put("cache", catalog.cacheHit() ? "hit" : "miss");
        return result;
    }

    private static void addMember(Map<String, ExternalMember> target, ClassRef ref,
            Member member, String needle, MatchMode matching) {
        String name = member.name().toLowerCase(Locale.ROOT);
        String signature = member.signature().toLowerCase(Locale.ROOT);
        if (!matching.test(name, needle) && !matching.test(signature, needle)) return;
        String key = ref.className() + "\0" + member.kind() + "\0" + member.signature();
        target.putIfAbsent(key, new ExternalMember(ref, member));
    }

    private static String ambiguous(String requested, List<String> candidates) {
        ObjectNode result = JSON.createObjectNode();
        result.put("error", "External class name is ambiguous: " + requested);
        ArrayNode values = result.putArray("candidates");
        candidates.forEach(values::add);
        result.put("hint", "Retry with an exact fully-qualified class name");
        return result.toString();
    }

    /** Artifacts on the resolved classpath that contain exactly {@code className}; empty when none or unknown. */
    static List<String> artifactsOf(ReadQueryContext reader, String className) {
        String projectRoot = reader.index.getMetadata().get("project_root");
        if (projectRoot == null || className == null) return List.of();
        try {
            return catalog(reader, Path.of(projectRoot)).classes().stream()
                    .filter(candidate -> candidate.className().equals(className))
                    .map(ClassRef::artifact).distinct().sorted().toList();
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    private static List<ClassRef> matching(List<ClassRef> classes, String className) {
        return classes.stream().filter(candidate -> candidate.className().equals(className)
                        || simpleName(candidate.className()).equals(className))
                .sorted(Comparator.comparing(ClassRef::className).thenComparing(ClassRef::artifact)).toList();
    }

    private static final Map<String, List<ClassRef>> BUILD_TIME = new java.util.concurrent.ConcurrentHashMap<>();

    /** Classes of the deployment artifacts of the classpath's Quarkus extensions, looked up only after a miss. */
    private static List<ClassRef> buildTimeClasses(ReadQueryContext reader, Path root, Catalog catalog) {
        return BUILD_TIME.computeIfAbsent(catalog.fingerprint(), fingerprint -> {
            if (BUILD_TIME.size() > MAX_PROJECT_CACHES) BUILD_TIME.clear();
            List<ClassRef> classes = new ArrayList<>();
            for (var jar : QuarkusDeploymentArtifacts.jars(fingerprint,
                    ProjectDependencyQueries.resolvedJars(reader, root.toAbsolutePath().normalize()))) {
                List<ClassRef> found = new ArrayList<>();
                scanClassNames(jar.path(), found);
                found.forEach(ref -> classes.add(new ClassRef(ref.className(), ref.jar(), ref.entry(), jar.coordinates())));
            }
            return List.copyOf(classes);
        });
    }

    /** States which modules' test classpaths were never captured, so test-only classes there are unknown, not absent. */
    private static String missingTestClasspaths(ReadQueryContext reader) {
        String missing = reader.index.getMetadata().get("missing_test_classpath_modules");
        if (missing == null || missing.isBlank() || missing.equals("[]")) return "";
        return "; test classpath not captured for modules " + missing.replace("\"", "")
                + ", so test-only classes there are unknown, not absent";
    }

    private static Catalog catalog(ReadQueryContext reader, Path root) {
        Path key = root.toAbsolutePath().normalize();
        List<Path> jars = ProjectDependencyQueries.resolvedJars(reader, key);
        String fingerprint = jars.stream().map(path -> path + ":" + modified(path))
                .reduce("", (left, right) -> left + "|" + right);
        Catalog cached = CATALOGS.get(key);
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.withCacheHit(true);
        }
        List<ClassRef> classes = new ArrayList<>();
        for (Path jar : jars) scanClassNames(jar, classes);
        Catalog created = new Catalog(fingerprint, List.copyOf(classes), jars.size(), false);
        if (CATALOGS.size() >= MAX_PROJECT_CACHES) CATALOGS.clear();
        CATALOGS.put(key, created);
        return created;
    }

    private static void scanClassNames(Path jar, List<ClassRef> target) {
        String artifact = ProjectDependencyQueries.coordinates(jar).id();
        try (JarFile file = new JarFile(jar.toFile())) {
            file.stream().filter(entry -> !entry.isDirectory())
                    .map(JarEntry::getName).filter(name -> name.endsWith(".class"))
                    .filter(name -> !name.startsWith("META-INF/versions/"))
                    .filter(name -> !name.equals("module-info.class"))
                    .forEach(name -> target.add(new ClassRef(
                            name.substring(0, name.length() - 6).replace('/', '.'),
                            jar, name, artifact)));
        } catch (IOException ignored) {
            // One unreadable dependency must not make the rest unavailable.
        }
    }

    public static ObjectNode producerEvidence(Path jar, String owner, String member) {
        var ref = new ClassRef(owner, jar, owner.replace('.', '/') + ".class", "");
        ObjectNode evidence = JSON.createObjectNode();
        ClassInfo info = readClass(ref);
        if (info == null) return evidence.put("status", "unavailable");
        ArrayNode declarations = evidence.putArray("declarations");
        for (MethodInfo method : info.methods()) {
            if (!method.name().equals(member)) continue;
            var declaration = declarations.addObject().put("signature", method.toString());
            declaration.set("annotations", JSON.valueToTree(method.annotations().stream()
                    .filter(a -> a.target() != null && a.target().kind() == org.jboss.jandex.AnnotationTarget.Kind.METHOD)
                    .map(a -> a.toString(false)).sorted().toList()));
        }
        ObjectNode calls = evidence.putObject("method_calls");
        methodCalls(ref, member).properties().stream()
                .forEach(e -> calls.set(e.getKey(), e.getValue()));
        evidence.put("scope", "Selected producer declarations and direct invocation instructions; runtime activation not proven");
        return evidence;
    }

    /** {@code name(Type, ...)} with simple parameter names; the return type rarely tells overloads apart. */
    static String javaMethod(String name, String descriptor) {
        StringBuilder text = new StringBuilder(name).append('(');
        org.objectweb.asm.Type[] parameters = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) text.append(", ");
            String type = parameters[i].getClassName();
            text.append(type.substring(type.lastIndexOf('.') + 1));
        }
        return text.append(')').toString();
    }

    /** {@code owner#name(Type, ...)}: the owner stays qualified so the call can be followed up. */
    static String javaCall(String owner, String member, String descriptor) {
        return owner.replace('/', '.') + "#" + javaMethod(member, descriptor);
    }

    /** Calls a whole class's bytecode may carry when it answers detail=full for a class with no companion source. */
    static final int FULL_BYTECODE_CALLS = 60;

    static boolean withinCallBudget(ObjectNode methodCalls) {
        if (methodCalls.has("truncated") || methodCalls.has("status")) return false;
        int calls = 0;
        for (var method : methodCalls.properties()) calls += method.getValue().path("calls").size();
        return calls <= FULL_BYTECODE_CALLS;
    }

    private static ObjectNode methodCalls(ClassRef ref, String selectedMember) {
        ObjectNode methods = JSON.createObjectNode();
        try (JarFile jar = new JarFile(ref.jar().toFile());
                InputStream input = jar.getInputStream(jar.getJarEntry(ref.entry()))) {
            new org.objectweb.asm.ClassReader(input).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                int total;
                int totalConstants;
                final Map<String, String> descriptors = new java.util.HashMap<>();
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (selectedMember != null && !name.equals(selectedMember)) return null;
                    // Bridge methods share Java parameters with their target; keep their descriptor apart.
                    String javaKey = javaMethod(name, descriptor);
                    String key = descriptors.getOrDefault(javaKey, descriptor).equals(descriptor) ? javaKey : name + descriptor;
                    descriptors.putIfAbsent(javaKey, descriptor);
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        int count;
                        int literalCount;
                        int classCount;
                        int resultCallCount;
                        @Override public void visitLdcInsn(Object value) {
                            // V62 CQ-005: X.class arguments (e.g. to UnremovableBeanBuildItem.beanTypes) sent Codex to javap.
                            if (value instanceof org.objectweb.asm.Type type && type.getSort() >= org.objectweb.asm.Type.ARRAY) {
                                if (totalConstants++ >= 60) { methods.put("constants_truncated", true); return; }
                                ObjectNode evidence = methods.withObject(key);
                                if (classCount++ < 12) evidence.withArray("class_constants").add(type.getClassName());
                                else evidence.put("constants_truncated", true);
                                return;
                            }
                            if (!(value instanceof String text) || text.length() > 200) return;
                            if (totalConstants++ >= 60) { methods.put("constants_truncated", true); return; }
                            ObjectNode evidence = methods.withObject(key);
                            if (literalCount++ < 12) evidence.withArray("string_constants").add(text);
                            else evidence.put("constants_truncated", true);
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String member, String desc, boolean itf) {
                            if (total++ >= 200) { methods.put("truncated", true); return; }
                            ObjectNode evidence = methods.withObject(key);
                            String resultType = org.objectweb.asm.Type.getReturnType(descriptor).getDescriptor();
                            boolean constructsResult = member.equals("<init>") && resultType.equals("L" + owner + ";");
                            boolean returnsResult = !resultType.equals("V") && resultType.equals(org.objectweb.asm.Type.getReturnType(desc).getDescriptor());
                            if ((constructsResult || returnsResult) && resultCallCount++ < 4) {
                                String call = javaCall(owner, member, desc);
                                String previous = evidence.path("result_calls").asText();
                                evidence.put("result_calls", previous.isEmpty() ? call : previous + "; " + call);
                            }
                            ArrayNode calls = evidence.withArray("calls");
                            if (count++ < 40) calls.add(javaCall(owner, member, desc));
                            else evidence.put("truncated", true);
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        } catch (IOException | RuntimeException unavailable) {
            methods.removeAll(); methods.put("status", "unavailable");
        }
        return methods;
    }

    private static ClassInfo readClass(ClassRef ref) {
        try (JarFile jar = new JarFile(ref.jar().toFile())) {
            JarEntry entry = jar.getJarEntry(ref.entry());
            if (entry == null) return null;
            try (InputStream input = jar.getInputStream(entry)) {
                Indexer indexer = new Indexer();
                indexer.index(input);
                return indexer.complete().getClassByName(ref.className());
            }
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    private static String classKind(ClassInfo info) {
        if (info.isAnnotation()) return "annotation";
        if (info.isInterface()) return "interface";
        if (info.isEnum()) return "enum";
        return "class";
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis() ^ Files.size(path);
        } catch (IOException error) {
            return 0;
        }
    }

    private static String simpleName(String className) {
        int separator = Math.max(className.lastIndexOf('.'), className.lastIndexOf('$'));
        return separator < 0 ? className : className.substring(separator + 1);
    }

    private static boolean matchesClassName(String candidate, String requested) {
        String value = requested.strip();
        return value.indexOf('.') >= 0 || value.indexOf('$') >= 0
                ? candidate.equals(value) : simpleName(candidate).equals(value);
    }

    private static boolean matches(String className, String needle, MatchMode mode) {
        String qualified = className.toLowerCase(Locale.ROOT);
        String simple = simpleName(className).toLowerCase(Locale.ROOT);
        return mode.test(qualified, needle) || mode.test(simple, needle);
    }

    private static String error(String message) {
        return JSON.createObjectNode().put("error", message).toString();
    }

    private record ClassRef(String className, Path jar, String entry, String artifact) {}
    private record Catalog(String fingerprint, List<ClassRef> classes,
            int jarCount, boolean cacheHit) {
        Catalog withCacheHit(boolean value) {
            return new Catalog(fingerprint, classes, jarCount, value);
        }
    }
    private record Member(String kind, String name, String signature,
            String type, String modifiers, List<String> annotations) {
        Member(String kind, String name, String signature, String type, String modifiers) {
            this(kind, name, signature, type, modifiers, List.of());
        }
    }
    private record ExternalMember(ClassRef ref, Member member) {}

    private enum MatchMode {
        EXACT("exact"), PREFIX("prefix"), CONTAINS("contains");

        private final String value;

        MatchMode(String value) {
            this.value = value;
        }

        /** glob reads the pattern's wildcards: *x* contains, x* prefix, no wildcard exact. */
        static MatchMode parse(String value, String pattern) {
            String normalized = value == null || value.isBlank()
                    ? "contains" : value.strip().toLowerCase(Locale.ROOT);
            if (normalized.equals("glob")) {
                String glob = pattern.strip();
                return glob.startsWith("*") ? CONTAINS : glob.endsWith("*") ? PREFIX : glob.contains("*") ? CONTAINS : EXACT;
            }
            for (MatchMode mode : values()) {
                if (mode.value.equals(normalized)) return mode;
            }
            return null;
        }

        boolean test(String value, String needle) {
            return switch (this) {
                case EXACT -> value.equals(needle);
                case PREFIX -> value.startsWith(needle);
                case CONTAINS -> value.contains(needle);
            };
        }
    }
}
