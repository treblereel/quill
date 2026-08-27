package org.treblereel.mcp.mcp;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import org.treblereel.mcp.core.ProjectRootFinder;
import org.treblereel.mcp.db.IndexReader;
import org.treblereel.mcp.db.JokerDatabase;
import org.treblereel.mcp.model.*;

@ApplicationScoped
public class JokerTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Tool(description = "List CDI beans with optional filtering by class_name, scope, kind, profile, qualifier")
    public String get_beans(
            @ToolArg(description = "Class name filter (supports * wildcard)") String class_name,
            @ToolArg(description = "Scope filter, e.g. @ApplicationScoped") String scope,
            @ToolArg(description = "Bean kind: CLASS, PRODUCER_METHOD, PRODUCER_FIELD, INTERCEPTOR, DECORATOR") String kind,
            @ToolArg(description = "Build profile filter, e.g. dev") String profile,
            @ToolArg(description = "Qualifier filter, e.g. @Premium") String qualifier) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getBeans(conn, class_name, scope, kind, profile, qualifier);
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    @Tool(description = "Get dependency graph for a specific bean or class. Shows what it depends on and what depends on it.")
    public String get_dependencies(
            @ToolArg(description = "Class name (short or FQCN)") String target,
            @ToolArg(description = "Direction: inbound, outbound, or both (default: both)") String direction,
            @ToolArg(description = "Graph traversal depth (default: 1)") Integer depth) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getDependencies(conn, target, direction != null ? direction : "both", depth != null ? depth : 1);
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    @Tool(description = "Get detailed injection point information for a bean, showing all candidates and resolution status")
    public String get_injection_points(
            @ToolArg(description = "Bean class name (short or FQCN)") String target) {
        Path root = ProjectRootFinder.find(null);
        try (Connection conn = JokerDatabase.open(root.resolve(".joker/index.db"))) {
            return getInjectionPoints(conn, target);
        } catch (Exception e) {
            return errorResponse(e.getMessage());
        }
    }

    // Package-private for testing with injected connection
    String getBeans(Connection conn, String className, String scope, String kind, String profile, String qualifier) {
        Map<String, String> filter = new HashMap<>();
        if (className != null) filter.put("class_name", className);
        if (scope != null) filter.put("scope", scope);
        if (kind != null) filter.put("kind", kind);

        List<BeanRecord> beans = IndexReader.findBeans(conn, filter.isEmpty() ? null : filter);
        ObjectNode root = JSON.createObjectNode();
        ArrayNode arr = root.putArray("beans");
        int[] naiveTokensWrapper = {0};

        for (BeanRecord b : beans) {
            ObjectNode node = arr.addObject();
            var classOpt = IndexReader.findClassById(conn, b.classId());
            String fqcn = classOpt.map(ClassRecord::className).orElse("unknown");
            node.put("class", fqcn);
            node.put("kind", b.kind());
            node.put("scope", b.scope());
            node.set("qualifiers", JSON.valueToTree(b.qualifiers()));
            node.set("bean_types", JSON.valueToTree(b.beanTypes()));
            node.set("profiles", JSON.valueToTree(b.profiles()));
            classOpt.ifPresent(c -> {
                node.put("source", c.sourceFile() + ":" + c.sourceLine());
                naiveTokensWrapper[0] += c.sourceTokens();
            });
        }
        root.put("total", beans.size());

        return root.toString();
    }

    String getDependencies(Connection conn, String target, String direction, int depth) {
        var classOpt = IndexReader.findClassByName(conn, target);
        if (classOpt.isEmpty()) return errorResponse("Class not found: " + target);
        ClassRecord cls = classOpt.get();

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());
        root.put("is_bean", cls.isBean());

        if (cls.isBean()) {
            IndexReader.findBeanByClassId(conn, cls.id()).ifPresent(b -> {
                root.put("scope", b.scope());
            });
        }

        List<DependencyRecord> deps = IndexReader.findDependencies(conn, cls.id(), direction);

        ArrayNode dependsOn = root.putArray("depends_on");
        ArrayNode dependedBy = root.putArray("depended_by");

        for (DependencyRecord d : deps) {
            if (d.fromClassId() == cls.id()) {
                IndexReader.findClassById(conn, d.toClassId()).ifPresent(c -> {
                    ObjectNode node = dependsOn.addObject();
                    node.put("class", c.className());
                    node.put("kind", d.kind());
                });
            }
            if (d.toClassId() == cls.id()) {
                IndexReader.findClassById(conn, d.fromClassId()).ifPresent(c -> {
                    ObjectNode node = dependedBy.addObject();
                    node.put("class", c.className());
                    node.put("kind", d.kind());
                });
            }
        }

        return root.toString();
    }

    String getInjectionPoints(Connection conn, String target) {
        var classOpt = IndexReader.findClassByName(conn, target);
        if (classOpt.isEmpty()) return errorResponse("Class not found: " + target);
        ClassRecord cls = classOpt.get();

        var beanOpt = IndexReader.findBeanByClassId(conn, cls.id());
        if (beanOpt.isEmpty()) return errorResponse("Not a CDI bean: " + target);

        ObjectNode root = JSON.createObjectNode();
        root.put("target", cls.className());

        List<InjectionPointRecord> ips = IndexReader.findInjectionPoints(conn, beanOpt.get().id());

        ArrayNode arr = root.putArray("injection_points");
        ArrayNode unsatisfied = root.putArray("unsatisfied");
        ArrayNode ambiguous = root.putArray("ambiguous");

        for (InjectionPointRecord ip : ips) {
            ObjectNode node = arr.addObject();
            node.put("kind", ip.kind());
            node.put("field", ip.fieldName());
            node.put("required_type", ip.targetType());
            node.set("qualifiers", JSON.valueToTree(ip.qualifiers()));

            if (ip.resolvedBeanId() != null) {
                IndexReader.findBeanById(conn, ip.resolvedBeanId()).ifPresent(resolved -> {
                    IndexReader.findClassById(conn, resolved.classId()).ifPresent(c -> {
                        node.put("resolved_to", c.className());
                    });
                });
                node.put("resolution", ip.isAmbiguous() ? "ambiguous" : "unique");
            } else {
                node.put("resolved_to", (String) null);
                node.put("resolution", "unsatisfied");
                unsatisfied.add(ip.fieldName());
            }

            if (ip.isAmbiguous()) {
                ambiguous.add(ip.fieldName());
            }
        }

        return root.toString();
    }

    private String errorResponse(String message) {
        return "{\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
    }
}
