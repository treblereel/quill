package org.treblereel.mcp.mcp;

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

/** Lazy, bounded lookup of declarations in resolved dependency JARs. */
final class ExternalSymbolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PROJECT_CACHES = 8;
    private static final ConcurrentHashMap<Path, Catalog> CATALOGS = new ConcurrentHashMap<>();

    String search(ReadQueryContext reader, Path root, String pattern, String kind, String className,
            String library, String matchMode, boolean includeOccurrences, int limit, int offset) {
        if (pattern == null || pattern.isBlank()) return error("Search pattern must not be blank");
        String requestedKind = kind == null || kind.isBlank()
                ? "all" : kind.strip().toLowerCase(Locale.ROOT);
        boolean memberSearch = Set.of("field", "constructor", "method").contains(requestedKind);
        if (memberSearch && (className == null || className.isBlank())) {
            return error("Member search requires class_name to bound dependency bytecode scanning");
        }
        String needle = pattern.strip().toLowerCase(Locale.ROOT).replace("*", "");
        MatchMode matching = MatchMode.parse(matchMode);
        if (matching == null) return error("match_mode must be exact, prefix, or contains");
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
            return searchMembers(catalog, candidates, pattern, requestedKind, matching,
                    limit, offset);
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

    String details(ReadQueryContext reader, Path root, String className, int limit, int offset) {
        return details(reader, root, className, limit, offset, "full");
    }

    String details(ReadQueryContext reader, Path root, String className, int limit, int offset, String detail) {
        if (!java.util.Set.of("summary", "full", "source").contains(detail)) return error("detail must be summary, full or source");
        Catalog catalog = catalog(reader, root);
        List<ClassRef> refs = catalog.classes().stream()
                .filter(candidate -> candidate.className().equals(className)
                        || simpleName(candidate.className()).equals(className))
                .sorted(Comparator.comparing(ClassRef::className)
                        .thenComparing(ClassRef::artifact)).toList();
        if (refs.isEmpty()) {
            return JSON.createObjectNode().put("error", "External class not found: " + className)
                    .put("reason", "Only JARs on the project's resolved main/test classpaths are indexed; "
                            + "build-time classes (for example Quarkus *-deployment processors) and "
                            + "runtime-generated classes are absent")
                    .set("next_step", JSON.createObjectNode().put("tool", "search_external_symbols")
                            .set("arguments", JSON.createObjectNode().put("pattern", simpleName(className))))
                    .toString();
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
        for (MethodInfo method : info.methods()) {
            if (method.name().equals("<clinit>")) continue;
            members.add(new Member(method.name().equals("<init>") ? "constructor" : "method",
                    method.name().equals("<init>") ? simpleName(ref.className()) : method.name(),
                    method.toString(), method.returnType().toString(),
                    java.lang.reflect.Modifier.toString(method.flags()),
                    method.annotations().stream().filter(a -> a.target() != null
                            && a.target().kind() == org.jboss.jandex.AnnotationTarget.Kind.METHOD)
                            .map(a -> a.toString(false)).sorted().toList()));
        }
        members.sort(Comparator.comparing(Member::kind).thenComparing(Member::name)
                .thenComparing(Member::signature));
        int from = Math.min(offset, members.size());
        int to = Math.min(from + limit, members.size());
        ObjectNode result = JSON.createObjectNode();
        result.put("class_name", ref.className());
        result.put("kind", classKind(info));
        result.set("annotations", JSON.valueToTree(info.declaredAnnotations().stream()
                .map(a -> a.toString(false)).sorted().toList()));
        result.put("runtime_resolution_proven", false);
        if (info.superName() != null) result.put("superclass", info.superName().toString());
        ArrayNode interfaces = result.putArray("interfaces");
        info.interfaceNames().forEach(name -> interfaces.add(name.toString()));
        result.put("artifact", ref.artifact());
        result.put("jar", ref.jar().toString());
        ArrayNode occurrences = result.putArray("occurrences");
        refs.forEach(candidate -> occurrences.addObject()
                .put("artifact", candidate.artifact()).put("jar", candidate.jar().toString()));
        result.put("version_count", refs.size());
        result.put("detail", detail);
        appendSource(ref, result, !detail.equals("summary"));
        if (detail.equals("source")) return result.toString();
        if (detail.equals("full")) {
            result.set("bytecode_method_calls", methodCalls(ref));
            result.put("bytecode_method_calls_scope", "At most 40 invocation instructions and 12 string constants per method, 200 invocations and 60 constants per class; omitted invokedynamic bodies, reflection and runtime dispatch are not inferred");
        } else {
            result.putObject("bytecode_request").put("class_name", ref.className()).put("detail", "full");
        }
        result.put("source", "resolved_dependency_bytecode");
        result.put("total_members", members.size());
        result.put("showing", to - from);
        result.put("offset", offset);
        result.put("has_more", to < members.size());
        ArrayNode listed = result.putArray("members");
        members.subList(from, to).forEach(member -> {
            ObjectNode item = listed.addObject();
            item.put("kind", member.kind());
            item.put("name", member.name());
            item.put("signature", member.signature());
            item.put("type", member.type());
            item.put("modifiers", member.modifiers());
            item.set("annotations", JSON.valueToTree(member.annotations()));
            if (info.isAnnotation()) info.methods().stream()
                    .filter(method -> method.name().equals(member.name()) && method.defaultValue() != null)
                    .findFirst().ifPresent(method -> item.put("annotation_default", method.defaultValue().toString()));
        });
        result.putArray("limitations")
                .add("Declaration annotations are bytecode evidence; method bodies, build-time registration and runtime activation are not established")
                .add("Companion source JARs are read only for this class when locally available; excerpts are bounded to 12000 bytes")
                .add("Class-name catalog is cached; bytecode details are read only for the selected class");
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
                byte[] bytes = input.readNBytes(12001);
                result.put("source_available", true);
                ObjectNode source = result.putObject("source_excerpt");
                source.put("artifact", sources + "!/" + entryName);
                source.put("text", new String(bytes, 0, Math.min(bytes.length, 12000), java.nio.charset.StandardCharsets.UTF_8));
                source.put("truncated", bytes.length > 12000);
                source.put("scope", "Companion source artifact; not proof that source matches compiled bytecode");
            }
        } catch (IOException | RuntimeException unavailable) {
            result.put("source_status", "unreadable");
        }
    }

    private static String searchMembers(Catalog catalog, List<ClassRef> candidates,
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
        return result.toString();
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

    static ObjectNode producerEvidence(Path jar, String owner, String member) {
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

    private static ObjectNode methodCalls(ClassRef ref) {
        return methodCalls(ref, null);
    }

    private static ObjectNode methodCalls(ClassRef ref, String selectedMember) {
        ObjectNode methods = JSON.createObjectNode();
        try (JarFile jar = new JarFile(ref.jar().toFile());
                InputStream input = jar.getInputStream(jar.getJarEntry(ref.entry()))) {
            new org.objectweb.asm.ClassReader(input).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                int total;
                int totalConstants;
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    if (selectedMember != null && !name.equals(selectedMember)) return null;
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        int count;
                        int literalCount;
                        int resultCallCount;
                        @Override public void visitLdcInsn(Object value) {
                            if (!(value instanceof String text) || text.length() > 200) return;
                            if (totalConstants++ >= 60) { methods.put("constants_truncated", true); return; }
                            ObjectNode evidence = methods.withObject(name + descriptor);
                            if (literalCount++ < 12) evidence.withArray("string_constants").add(text);
                            else evidence.put("constants_truncated", true);
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String member, String desc, boolean itf) {
                            if (total++ >= 200) { methods.put("truncated", true); return; }
                            ObjectNode evidence = methods.withObject(name + descriptor);
                            String resultType = org.objectweb.asm.Type.getReturnType(descriptor).getDescriptor();
                            boolean constructsResult = member.equals("<init>") && resultType.equals("L" + owner + ";");
                            boolean returnsResult = !resultType.equals("V") && resultType.equals(org.objectweb.asm.Type.getReturnType(desc).getDescriptor());
                            if ((constructsResult || returnsResult) && resultCallCount++ < 4) {
                                String call = owner.replace('/', '.') + "#" + member + desc;
                                String previous = evidence.path("result_calls").asText();
                                evidence.put("result_calls", previous.isEmpty() ? call : previous + "; " + call);
                            }
                            ArrayNode calls = evidence.withArray("calls");
                            if (count++ < 40) calls.add(owner.replace('/', '.') + "#" + member + desc);
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

        static MatchMode parse(String value) {
            String normalized = value == null || value.isBlank()
                    ? "contains" : value.strip().toLowerCase(Locale.ROOT);
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
