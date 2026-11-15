package org.treblereel.mcp.mcp.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.model.ClassRecord;

/**
 * Calls to a Spring bean's method written in annotation expressions, such as
 * {@code @PreAuthorize("@unifiedPermissionValidator.hasReleaseNamespacePermission(#appId)")}. They never appear
 * in bytecode, so a caller list without them reads as "called only from Java" (Apollo AQ-041). The expressions
 * are already indexed as annotation values; this matches {@code @beanName.method(} against the bean's names.
 */
final class ExpressionReferences {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern BEAN_NAME = Pattern.compile("^@(?:Qualifier|Named)\\(\"(.+)\"\\)$");
    static final int LIMIT = 50;

    private ExpressionReferences() {}

    /** Bean names under which SpEL can reach {@code cls}; empty when it is not a Spring bean. */
    static Set<String> beanNames(ReadQueryContext db, ClassRecord cls) {
        Set<String> names = new LinkedHashSet<>();
        List<String> rows = db.beans.findSpringBeanQualifiers(cls.id(), cls.className());
        for (String qualifiers : rows) {
            try {
                for (JsonNode qualifier : JSON.readTree(String.valueOf(qualifiers))) {
                    Matcher name = BEAN_NAME.matcher(qualifier.asText());
                    if (name.matches()) names.add(name.group(1));
                }
            } catch (Exception unreadable) {
                // A malformed qualifier list names no bean.
            }
        }
        return names;
    }

    /**
     * Annotation expressions calling {@code method} on one of the bean's names, or null when {@code cls} is not
     * a Spring bean (no expression can name it, so the list would only add noise).
     */
    static ObjectNode find(ReadQueryContext db, ClassRecord cls, String method) {
        Set<String> names = beanNames(db, cls);
        if (names.isEmpty()) return null;
        List<ObjectNode> found = new ArrayList<>();
        int total = 0;
        for (String name : names) {
            Pattern call = Pattern.compile("@" + Pattern.quote(name) + "\\s*\\.\\s*" + Pattern.quote(method) + "\\s*\\(");
            String like = "%@" + name + "%" + method + "%";
            for (var row : db.symbols.findAnnotationValuesLike(like)) {
                for (String[] expression : expressions(row.member() == null, String.valueOf(row.details()), call)) {
                    total++;
                    if (found.size() >= LIMIT) continue;
                    ObjectNode node = JSON.createObjectNode().put("class", String.valueOf(row.className()));
                    if (row.member() != null) node.put("method", row.member());
                    else node.put("on", "class");
                    node.put("annotation", expression[0]).put("expression", expression[1]).put("bean_name", name);
                    if (row.sourceFile() != null) node.put("source", row.sourceFile());
                    if (row.module() != null) node.put("module", row.module());
                    if (row.sourceSet() != null) node.put("source_set", row.sourceSet());
                    found.add(node);
                }
            }
        }
        ObjectNode result = JSON.createObjectNode()
                .put("scope", "Spring bean method calls in indexed annotation values (SpEL such as @PreAuthorize, "
                        + "@Cacheable conditions, @Value) matched by bean name; not in bytecode, so absent from usages/calls")
                .put("total", total).put("showing", found.size()).put("has_more", total > found.size());
        result.set("bean_names", JSON.valueToTree(names));
        ArrayNode references = result.putArray("references");
        found.forEach(references::add);
        return result;
    }

    /** [annotation, expression] pairs whose value matches {@code call}. */
    private static List<String[]> expressions(boolean classLevel, String details, Pattern call) {
        List<String[]> result = new ArrayList<>();
        if (classLevel) {
            int separator = details.indexOf('=');
            String annotation = separator < 0 ? details : details.substring(0, separator);
            String values = separator < 0 ? "" : details.substring(separator + 1);
            collect(annotation, parse(values), call, result);
            return result;
        }
        JsonNode annotations = parse(details);
        for (JsonNode annotation : annotations) collect(annotation.path("annotationName").asText(), annotation.path("values"), call, result);
        return result;
    }

    private static void collect(String annotation, JsonNode values, Pattern call, List<String[]> result) {
        if (values.isTextual()) {
            if (call.matcher(values.asText()).find()) result.add(new String[] {annotation, values.asText()});
            return;
        }
        for (JsonNode value : values) collect(annotation, value, call, result);
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception unreadable) {
            return JSON.createArrayNode();
        }
    }
}
