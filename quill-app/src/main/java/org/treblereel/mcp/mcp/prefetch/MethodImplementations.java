package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/**
 * For an interface or abstract method: each indexed implementing class, where its body comes from
 * (its own declaration, calling super or not, or an ancestor's), and whether it is a bean. Apollo
 * AQ-010 (Claude): asked which implementations really serve
 * PermissionValidator#hasReleaseNamespacePermission, Claude read five classes.
 */
final class MethodImplementations {

    private static final ObjectMapper JSON = Json.MAPPER;
    private static final int MAX_LINES = 10;
    private static final Pattern QUALIFIER = Pattern.compile("@Qualifier\\(\\\\?\"([^\"\\\\]+)");

    private MethodImplementations() {}

    static void append(
            ReadQueryContext db, ObjectNode fact, int classId, String name, String descriptor) {
        ClassRecord target = db.symbols.findClassById(classId).orElse(null);
        if (target == null) return;
        boolean contract =
                "INTERFACE".equals(target.kind()) || isAbstract(db, classId, name, descriptor);
        if (!contract) return;
        List<ClassRecord> all = db.symbols.findAllClasses();
        Map<String, ClassRecord> byName = new HashMap<>();
        for (ClassRecord cls : all) byName.putIfAbsent(cls.className(), cls);
        ArrayNode lines = JSON.createArrayNode();
        List<String> main = new ArrayList<>();
        List<String> tests = new ArrayList<>();
        int total = 0;
        for (ClassRecord cls : all) {
            if (cls.id() == classId
                    || "INTERFACE".equals(cls.kind())
                    || "ANNOTATION".equals(cls.kind())) continue;
            if (!subtypeOf(cls, target.className(), byName)) continue;
            total++;
            String body;
            if (declares(db, cls.id(), name, descriptor)) {
                body =
                        "declares it"
                                + (callsSuper(db, cls, name, descriptor, byName)
                                        ? " (calls super)"
                                        : "");
            } else {
                ClassRecord from = declaringAncestor(db, cls, name, descriptor, byName);
                body =
                        from == null
                                ? "inherits it from an unindexed ancestor"
                                : "inherits " + from.className() + "#" + name;
            }
            String line = cls.className() + " " + body + "; " + beanText(db, cls);
            if (cls.inTestSourceSet()) tests.add(line + "; test source");
            else main.add(line);
        }
        // Production implementations first (Apollo: two AbstractPermissionValidatorTest impls).
        for (String line : main) if (lines.size() < MAX_LINES) lines.add(line);
        for (String line : tests) if (lines.size() < MAX_LINES) lines.add(line);
        if (lines.isEmpty()) return;
        fact.set("implementations", lines);
        if (total > lines.size())
            fact.putObject("implementations_truncated")
                    .put("shown", lines.size())
                    .put("total", total);
        fact.put(
                "implementations_basis",
                "indexed subtypes of the declaring type; the body a call runs is the class's own or the nearest ancestor's; a bean's runtime type decides");
    }

    private static boolean subtypeOf(
            ClassRecord cls, String type, Map<String, ClassRecord> byName) {
        java.util.Deque<ClassRecord> queue = new java.util.ArrayDeque<>(List.of(cls));
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (!queue.isEmpty()) {
            ClassRecord current = queue.poll();
            List<String> parents = new ArrayList<>(current.interfaces());
            if (current.superclass() != null) parents.add(current.superclass());
            for (String parent : parents) {
                if (parent.equals(type)) return true;
                if (!seen.add(parent)) continue;
                ClassRecord next = byName.get(parent);
                if (next != null) queue.add(next);
            }
        }
        return false;
    }

    private static List<Map<String, Object>> members(
            ReadQueryContext db, int classId, String name, String descriptor) {
        return db.jdbi()
                .withHandle(
                        handle ->
                                handle.createQuery(
                                                "SELECT modifiers FROM class_members WHERE class_id = :id AND name = :name AND descriptor = :descriptor")
                                        .bind("id", classId)
                                        .bind("name", name)
                                        .bind("descriptor", descriptor)
                                        .mapToMap()
                                        .list());
    }

    private static boolean isAbstract(
            ReadQueryContext db, int classId, String name, String descriptor) {
        return members(db, classId, name, descriptor).stream()
                .anyMatch(row -> String.valueOf(row.get("modifiers")).contains("abstract"));
    }

    private static boolean declares(
            ReadQueryContext db, int classId, String name, String descriptor) {
        return members(db, classId, name, descriptor).stream()
                .anyMatch(row -> !String.valueOf(row.get("modifiers")).contains("abstract"));
    }

    private static ClassRecord declaringAncestor(
            ReadQueryContext db,
            ClassRecord cls,
            String name,
            String descriptor,
            Map<String, ClassRecord> byName) {
        ClassRecord current = cls;
        while (current.superclass() != null) {
            ClassRecord parent = byName.get(current.superclass());
            if (parent == null) return null;
            if (declares(db, parent.id(), name, descriptor)) return parent;
            current = parent;
        }
        return null;
    }

    private static boolean callsSuper(
            ReadQueryContext db,
            ClassRecord cls,
            String name,
            String descriptor,
            Map<String, ClassRecord> byName) {
        if (cls.superclass() == null || byName.get(cls.superclass()) == null) return false;
        int superId = byName.get(cls.superclass()).id();
        return db.jdbi()
                        .withHandle(
                                handle ->
                                        handle.createQuery(
                                                        "SELECT COUNT(*) FROM method_calls WHERE from_class_id = :id AND from_method = :name AND from_descriptor = :descriptor "
                                                                + "AND to_method = :name AND to_descriptor = :descriptor AND to_class_id = :super")
                                                .bind("id", cls.id())
                                                .bind("name", name)
                                                .bind("descriptor", descriptor)
                                                .bind("super", superId)
                                                .mapTo(Integer.class)
                                                .one())
                > 0;
    }

    /** ACC_ABSTRACT from the captured class file, else any abstract member. */
    private static boolean abstractClass(ReadQueryContext db, int classId) {
        String evidence =
                db.jdbi()
                        .withHandle(
                                handle ->
                                        handle.createQuery(
                                                        "SELECT evidence FROM jvm_api WHERE class_id = :id")
                                                .bind("id", classId)
                                                .mapTo(String.class)
                                                .findOne()
                                                .orElse(null));
        if (evidence != null) {
            try {
                var flags = JSON.readTree(evidence).path("access_flags");
                if (flags.isInt()) return (flags.asInt() & 0x400) != 0;
            } catch (Exception unreadable) {
                // Fall back to the members.
            }
        }
        return db.jdbi()
                        .withHandle(
                                handle ->
                                        handle.createQuery(
                                                        "SELECT COUNT(*) FROM class_members WHERE class_id = :id AND modifiers LIKE '%abstract%'")
                                                .bind("id", classId)
                                                .mapTo(Integer.class)
                                                .one())
                > 0;
    }

    private static String beanText(ReadQueryContext db, ClassRecord cls) {
        var bean = db.beans.findBeanByClassId(cls.id());
        boolean abstractClass = abstractClass(db, cls.id());
        if (bean.isEmpty()) return abstractClass ? "abstract, not a bean" : "not a bean";
        String qualifiers = String.valueOf(bean.get().qualifiers());
        Matcher named = QUALIFIER.matcher(qualifiers);
        String simple =
                cls.className()
                        .substring(
                                Math.max(
                                                cls.className().lastIndexOf('.'),
                                                cls.className().lastIndexOf('$'))
                                        + 1);
        String beanName =
                named.find()
                        ? named.group(1)
                        : Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
        String profiles =
                bean.get().profiles() == null || bean.get().profiles().isEmpty()
                        ? ""
                        : " profiles " + bean.get().profiles();
        return "bean " + beanName + profiles;
    }
}
