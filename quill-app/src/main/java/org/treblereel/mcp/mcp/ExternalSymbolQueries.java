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
import org.jdbi.v3.core.Jdbi;

/** Lazy, bounded lookup of declarations in resolved dependency JARs. */
final class ExternalSymbolQueries {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PROJECT_CACHES = 8;
    private static final ConcurrentHashMap<Path, Catalog> CATALOGS = new ConcurrentHashMap<>();

    String search(Jdbi jdbi, Path root, String pattern, String kind, String className,
            String library, int limit, int offset) {
        if (pattern == null || pattern.isBlank()) return error("Search pattern must not be blank");
        String requestedKind = kind == null || kind.isBlank()
                ? "all" : kind.strip().toLowerCase(Locale.ROOT);
        boolean memberSearch = Set.of("field", "constructor", "method").contains(requestedKind);
        if (memberSearch && (className == null || className.isBlank())) {
            return error("Member search requires class_name to bound dependency bytecode scanning");
        }
        String needle = pattern.strip().toLowerCase(Locale.ROOT).replace("*", "");
        String libraryNeedle = library == null ? ""
                : library.strip().toLowerCase(Locale.ROOT).replace("*", "");
        Catalog catalog = catalog(jdbi, root);
        List<ClassRef> candidates = catalog.classes().stream()
                .filter(ref -> className == null || className.isBlank()
                        || matchesClassName(ref.className(), className))
                .filter(ref -> memberSearch
                        || ref.className().toLowerCase(Locale.ROOT).contains(needle))
                .filter(ref -> libraryNeedle.isEmpty()
                        || ref.className().toLowerCase(Locale.ROOT).contains(libraryNeedle)
                        || ref.artifact().toLowerCase(Locale.ROOT).contains(libraryNeedle))
                .sorted(Comparator.comparing(ClassRef::className)).toList();
        if (memberSearch) {
            return searchMembers(catalog, candidates, pattern, requestedKind, limit, offset);
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
            ArrayNode occurrences = item.putArray("occurrences");
            match.getValue().stream().sorted(Comparator.comparing(ClassRef::artifact))
                    .forEach(ref -> occurrences.addObject()
                            .put("artifact", ref.artifact()).put("jar", ref.jar().toString()));
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

    String details(Jdbi jdbi, Path root, String className, int limit, int offset) {
        Catalog catalog = catalog(jdbi, root);
        List<ClassRef> refs = catalog.classes().stream()
                .filter(candidate -> candidate.className().equals(className)
                        || simpleName(candidate.className()).equals(className))
                .sorted(Comparator.comparing(ClassRef::className)
                        .thenComparing(ClassRef::artifact)).toList();
        if (refs.isEmpty()) return error("External class not found: " + className);
        List<String> names = refs.stream().map(ClassRef::className).distinct().toList();
        if (names.size() > 1) return ambiguous(className, names);
        ClassRef ref = refs.get(0);
        ClassInfo info = readClass(ref);
        if (info == null) return error("Could not inspect external class: " + ref.className());

        List<Member> members = new ArrayList<>();
        for (FieldInfo field : info.fields()) {
            members.add(new Member("field", field.name(), field.toString(),
                    field.type().toString(), java.lang.reflect.Modifier.toString(field.flags())));
        }
        for (MethodInfo method : info.methods()) {
            if (method.name().equals("<clinit>")) continue;
            members.add(new Member(method.name().equals("<init>") ? "constructor" : "method",
                    method.name().equals("<init>") ? simpleName(ref.className()) : method.name(),
                    method.toString(), method.returnType().toString(),
                    java.lang.reflect.Modifier.toString(method.flags())));
        }
        members.sort(Comparator.comparing(Member::kind).thenComparing(Member::name)
                .thenComparing(Member::signature));
        int from = Math.min(offset, members.size());
        int to = Math.min(from + limit, members.size());
        ObjectNode result = JSON.createObjectNode();
        result.put("class_name", ref.className());
        result.put("kind", classKind(info));
        if (info.superName() != null) result.put("superclass", info.superName().toString());
        ArrayNode interfaces = result.putArray("interfaces");
        info.interfaceNames().forEach(name -> interfaces.add(name.toString()));
        result.put("artifact", ref.artifact());
        result.put("jar", ref.jar().toString());
        ArrayNode occurrences = result.putArray("occurrences");
        refs.forEach(candidate -> occurrences.addObject()
                .put("artifact", candidate.artifact()).put("jar", candidate.jar().toString()));
        result.put("version_count", refs.size());
        result.put("source_available", false);
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
        });
        result.putArray("limitations")
                .add("Dependency source JARs are not indexed; declaration source lines are unavailable")
                .add("Class-name catalog is cached; bytecode details are read only for the selected class");
        return result.toString();
    }

    private static String searchMembers(Catalog catalog, List<ClassRef> candidates,
            String pattern, String kind, int limit, int offset) {
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
                    addMember(unique, ref, member, needle);
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
                    addMember(unique, ref, member, needle);
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
            Member member, String needle) {
        String searchable = (member.name() + " " + member.signature()).toLowerCase(Locale.ROOT);
        if (!searchable.contains(needle)) return;
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

    private static Catalog catalog(Jdbi jdbi, Path root) {
        Path key = root.toAbsolutePath().normalize();
        List<Path> jars = ProjectDependencyQueries.resolvedJars(jdbi, key);
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
            String type, String modifiers) {}
    private record ExternalMember(ClassRef ref, Member member) {}
}
