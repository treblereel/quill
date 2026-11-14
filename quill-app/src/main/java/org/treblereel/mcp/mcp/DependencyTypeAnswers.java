package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.treblereel.mcp.db.BeanRepository.TypeInjectionPoint;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/**
 * What the application index holds about a dependency class it references: its injection points
 * (with the bean each resolves to), the application classes referencing or subtyping it. Usage,
 * bean and hierarchy questions about Base64 or jakarta Event are answered from these instead of
 * CLASS_NOT_FOUND.
 */
final class DependencyTypeAnswers {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Set<String>> REFERENCE_KINDS = Map.of(
            "inheritance", Set.of("EXTENDS", "IMPLEMENTS"),
            "annotation", Set.of("ANNOTATION"),
            "type_reference", Set.of("FIELD", "METHOD"),
            "service_loader", Set.of("SERVICE_PROVIDES"));

    private DependencyTypeAnswers() {
    }

    /** The dependency class a failed lookup names, when the application references it; otherwise null. */
    static String referencedType(ReadQueryContext reader, ClassTargetResolver.Lookup lookup, String target) {
        if (lookup.error() == null || !"CLASS_NOT_FOUND".equals(lookup.errorCode()) || target == null
                || target.contains("/") || target.lastIndexOf('.') <= 0) return null;
        if (!ClassTargetResolver.errorResponse(JSON, lookup, target).path("candidates").isEmpty()) return null;
        if (!reader.symbols.searchClasses(target.substring(0, target.lastIndexOf('.')) + ".%", 1).isEmpty()) return null;
        boolean referenced = reader.usages.isExternalTypeReferenced(target);
        return referenced ? target : null;
    }

    /** A stand-in record so subtype traversals keyed by class name can start from the dependency type. */
    static ClassRecord standIn(String type) {
        return new ClassRecord(-1, type, "dependency", null, List.of(), null, 0, false, 0, null,
                "dependency", "current", null, null);
    }

    static String usages(ReadQueryContext reader, String type, String usageKind, int limit) {
        ObjectNode root = header(type);
        root.put("granularity", "class");
        if (usageKind == null) root.putNull("usage_kind");
        else root.put("usage_kind", usageKind);
        if (usageKind == null || usageKind.equals("injection")) {
            root.set("injection_points", injectionPoints(reader, type));
            appendDependencyBeans(root, reader, type);
        }
        if (usageKind == null || !usageKind.equals("injection")) {
            ToolResponseSupport.appendApplicationReferences(root, reader, type, usageKind == null ? null : REFERENCE_KINDS.get(usageKind), limit);
            if (usageKind != null && !REFERENCE_KINDS.containsKey(usageKind))
                root.put("references_note", "Calls and field accesses into dependency classes are not indexed per member; "
                        + "these are all application classes referencing it");
        }
        root.put("basis", "Dependency class: application classes referencing it and its injection points from the "
                + "application index; its own bytecode is in get_external_symbol_details");
        ToolResponseSupport.appendMeta(root, reader, 0);
        return root.toString();
    }

    /** get_symbol_details: the dependency's own summary from its jar and where the application uses it. */
    static String symbol(ReadQueryContext reader, String type) {
        ObjectNode root = header(type);
        String projectRoot = reader.index.getMetadata().get("project_root");
        if (projectRoot != null) {
            try {
                var details = JSON.readTree(new ExternalSymbolQueries().details(reader, java.nio.file.Path.of(projectRoot), type, 10, 0, "summary"));
                if (details instanceof ObjectNode external && !external.has("error_code")) {
                    external.remove(List.of("bytecode_request", "_meta", "class_name"));
                    root.set("external", external);
                }
            } catch (Exception unavailable) {
                // Without the jar the application references still answer.
            }
        }
        ToolResponseSupport.appendApplicationReferences(root, reader, type, null);
        root.put("basis", "Dependency class: summary from its jar (get_external_symbol_details has members and bytecode) "
                + "and the application classes and members referencing it");
        ToolResponseSupport.appendMeta(root, reader, 0);
        return root.toString();
    }

    /** list_beans / list_injection_points: the application beans of the type and what its injection points resolve to. */
    static String beans(ReadQueryContext reader, String type) {
        ObjectNode root = header(type);
        ArrayNode beans = root.putArray("beans");
        reader.beans.findCurrentTypeBeans(type).forEach(row -> beans.add(
                        (row.memberName() != null ? (row.declaringClass() != null ? row.declaringClass() : row.className())
                                + "#" + row.memberName() : String.valueOf(row.className()))
                                + " (" + BeanTerms.kind((Object) row.framework(), row.kind())
                                + (row.scope() != null ? " " + BeanTerms.scope((Object) row.framework(), row.scope()) : "") + ")"));
        ArrayNode points = injectionPoints(reader, type);
        root.set("injection_points", points);
        appendDependencyBeans(root, reader, type);
        root.put("answer", (beans.isEmpty() ? "No application bean has" : beans.size() + " application bean"
                + (beans.size() == 1 ? " has" : "s have")) + " dependency type " + type + "; "
                + (points.isEmpty() ? "nothing in the application injects it"
                        : "its " + points.size() + " injection points resolve as listed"));
        ToolResponseSupport.appendMeta(root, reader, 0);
        return root.toString();
    }

    private static ObjectNode header(String type) {
        return JSON.createObjectNode().put("target", type).put("origin", "dependency");
    }

    static ArrayNode injectionPoints(ReadQueryContext reader, String type) {
        ArrayNode lines = JSON.createArrayNode();
        reader.beans.findTypeInjectionPoints(type).forEach(row -> {
                    String point = row.owner() + "." + (row.fieldName() != null ? row.fieldName()
                            : String.valueOf(row.kind()).toLowerCase());
                    if (!type.equals(row.targetType())) point += " (" + row.targetType() + ")";
                    lines.add(point + " -> " + resolution(row));
                });
        return lines;
    }

    /** Beans the dependencies' own bean archives declare with the type, such as VertxProducer#mutinyEventBus. */
    private static void appendDependencyBeans(ObjectNode root, ReadQueryContext reader, String type) {
        ArrayNode lines = JSON.createArrayNode();
        reader.beans.findExternalTypeBeans(type).forEach(row -> lines.add(
                        row.className() + (row.memberName() != null ? "#" + row.memberName() : "")
                                + " (" + BeanTerms.kind((Object) row.framework(), row.kind())
                                + (row.scope() != null ? " " + BeanTerms.scope((Object) row.framework(), row.scope()) : "")
                                + (row.artifact() != null ? ", " + row.artifact() : "") + ")"));
        if (!lines.isEmpty()) root.set("dependency_beans", lines);
    }

    private static String resolution(TypeInjectionPoint row) {
        if (row.beanKind() != null) {
            String owner = row.beanDeclaringClass() != null ? row.beanDeclaringClass() : String.valueOf(row.beanClass());
            String bean = row.beanMember() != null ? owner + "#" + row.beanMember() : String.valueOf(row.beanClass());
            return bean + " (" + BeanTerms.kind((Object) row.beanFramework(), row.beanKind())
                    + (row.beanScope() != null ? " " + BeanTerms.scope((Object) row.beanFramework(), row.beanScope()) : "") + ")";
        }
        if ("BUILTIN_BEAN".equals(row.resolutionReason())) return "built-in bean";
        if ("NO_CONTEXT_CANDIDATE".equals(row.resolutionReason())) return "no application bean";
        return row.resolutionStatus() + " " + row.resolutionReason();
    }
}
