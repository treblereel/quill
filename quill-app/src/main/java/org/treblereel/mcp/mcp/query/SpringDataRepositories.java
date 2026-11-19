package org.treblereel.mcp.mcp.query;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/**
 * Where a Spring Data repository bean comes from: the application interface is no bean class, so an
 * injection point of its type stays unresolved. Apollo AQ-018: asked what springSecurityUserService
 * needs, no answer in any arm said that UserRepository and AuthorityRepository are repository
 * proxies registered by Spring Boot's JPA repository auto-configuration.
 */
public final class SpringDataRepositories {

    private static final String DATA = "org.springframework.data.";
    private static final int MAX_CLASSES = 4;

    private SpringDataRepositories() {}

    /** A sentence on the repository bean of {@code type}, or null when it is no repository. */
    public static String describe(ReadQueryContext reader, String type) {
        ClassRecord cls = reader.symbols.findClassByName(type).orElse(null);
        if (cls == null || !"INTERFACE".equals(cls.kind())) return null;
        String ancestor = repositoryAncestor(reader, cls);
        if (ancestor == null) return null;
        String base = ancestor.substring(ancestor.lastIndexOf('.') + 1);
        // org.springframework.data.jpa.repository.JpaRepository -> @EnableJpaRepositories
        String store = ancestor.substring(DATA.length()).split("\\.")[0];
        String enable =
                store.equals("repository")
                        ? "@Enable…Repositories"
                        : "@Enable"
                                + switch (store) {
                                    case "jpa" -> "Jpa";
                                    case "mongodb" -> "Mongo";
                                    case "jdbc" -> "Jdbc";
                                    case "r2dbc" -> "R2dbc";
                                    case "redis" -> "Redis";
                                    case "elasticsearch" -> "Elasticsearch";
                                    case "neo4j" -> "Neo4j";
                                    case "cassandra" -> "Cassandra";
                                    default ->
                                            Character.toUpperCase(store.charAt(0))
                                                    + store.substring(1);
                                }
                                + "Repositories";
        String simple = type.substring(type.lastIndexOf('.') + 1).replace('$', '.');
        String bean = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        List<String> visible = visibleModules(reader, cls.module());
        List<String> enabling =
                annotated(reader, "org.springframework.data.%.Enable%Repositories", true, visible);
        String registration;
        if (!enabling.isEmpty()) {
            registration = "registered by repository scanning: " + String.join("; ", enabling);
        } else {
            List<String> boot = new ArrayList<>();
            for (String annotation :
                    List.of(
                            "org.springframework.boot.autoconfigure.SpringBootApplication",
                            "org.springframework.boot.autoconfigure.EnableAutoConfiguration"))
                for (String found : annotated(reader, annotation, false, visible))
                    if (!boot.contains(found) && boot.size() < MAX_CLASSES) boot.add(found);
            registration =
                    "registered by Spring Boot's repository auto-configuration in the auto-configuration packages of "
                            + (boot.isEmpty()
                                    ? "the application (no indexed @SpringBootApplication)"
                                    : String.join(", ", boot))
                            + "; no "
                            + enable
                            + " in source";
        }
        return "Spring Data repository interface (extends "
                + base
                + "): its bean '"
                + bean
                + "' is a proxy Spring Data creates, not an indexed class; "
                + registration;
    }

    private static String repositoryAncestor(ReadQueryContext reader, ClassRecord start) {
        Deque<ClassRecord> queue = new ArrayDeque<>(List.of(start));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            ClassRecord current = queue.poll();
            for (String parent : current.interfaces()) {
                if (!seen.add(parent)) continue;
                if (parent.startsWith(DATA)) return parent;
                reader.symbols.findClassByName(parent).ifPresent(queue::add);
            }
        }
        return null;
    }

    /** "@EnableJpaRepositories on p.JpaConfig" (pattern) or class names carrying the annotation. */
    /** Modules on the repository module's classpath; empty when the index records none. */
    private static List<String> visibleModules(ReadQueryContext reader, String module) {
        return module == null ? List.of() : List.copyOf(reader.index.findVisibleModules(module));
    }

    private static List<String> annotated(
            ReadQueryContext reader, String annotation, boolean pattern, List<String> modules) {
        return reader
                .symbols
                .findProductionClassesAnnotated(annotation, pattern, modules, MAX_CLASSES)
                .stream()
                .map(
                        found -> {
                            String name = found.annotationName();
                            return pattern
                                    ? "@"
                                            + name.substring(name.lastIndexOf('.') + 1)
                                            + " on "
                                            + found.className()
                                    : found.className();
                        })
                .toList();
    }
}
