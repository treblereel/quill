package org.treblereel.mcp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassAnnotationRecord;

/**
 * Registration conditions and ArC retention signals derived from indexed annotation values.
 * Agents previously equated a configuration branch with a lookup condition that compares
 * differently, or claimed unconditional retention from a scope alone.
 */
final class BeanEvidence {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, String> CONDITIONS = Map.of(
            "io.quarkus.arc.lookup.LookupIfProperty",
            "Programmatic lookup (Instance) only: available when property `name` equals `stringValue` "
                    + "(String.equals, case-sensitive); lookupIfMissing defaults to false. @Inject resolution is unaffected",
            "io.quarkus.arc.lookup.LookupUnlessProperty",
            "Programmatic lookup (Instance) only: hidden when property `name` equals `stringValue` "
                    + "(String.equals, case-sensitive); @Inject resolution is unaffected",
            "io.quarkus.arc.properties.IfBuildProperty",
            "Build time: the bean is removed unless property `name` equals `stringValue`; enableIfMissing defaults to false",
            "io.quarkus.arc.properties.UnlessBuildProperty",
            "Build time: the bean is removed when property `name` equals `stringValue`",
            "io.quarkus.arc.profile.IfBuildProfile", "Build time: kept only for the listed profiles",
            "io.quarkus.arc.profile.UnlessBuildProfile", "Build time: removed for the listed profiles",
            "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
            "Startup: registered when the property equals `havingValue` (case-insensitive; any value but false when empty); matchIfMissing defaults to false",
            "org.springframework.context.annotation.Profile", "Startup: registered only for the listed profiles",
            "org.springframework.context.annotation.Conditional", "Startup: registered when the listed Condition classes match");
    private static final String UNREMOVABLE = "io.quarkus.arc.Unremovable";
    private static final List<String> ROOT_ANNOTATIONS = List.of(
            "io.quarkus.runtime.Startup", "jakarta.ws.rs.Path", "io.quarkus.scheduler.Scheduled");

    private BeanEvidence() {}

    static void appendConditions(ObjectNode node, BeanRecord bean, List<ClassAnnotationRecord> annotations) {
        if (bean.memberName() != null) return; // producer conditions sit on the member, not the class
        ArrayNode conditions = JSON.createArrayNode();
        for (ClassAnnotationRecord annotation : annotations) {
            String meaning = CONDITIONS.get(annotation.annotationName());
            if (meaning == null || !annotation.direct()) continue;
            ObjectNode condition = conditions.addObject().put("annotation", annotation.annotationName());
            condition.set("values", JSON.valueToTree(annotation.values()));
            condition.put("meaning", meaning);
        }
        if (!conditions.isEmpty()) node.set("registration_conditions", conditions);
    }

    /** Positive reasons ArC keeps the bean when unused-bean removal is enabled (Quarkus default). */
    static void appendRetention(ObjectNode node, BeanRecord bean, int injectedInto,
            List<ClassAnnotationRecord> annotations) {
        if (bean.memberName() != null || "SYNTHETIC".equals(bean.kind())) return;
        List<String> signals = new ArrayList<>();
        if (injectedInto > 0) signals.add("injected_into:" + injectedInto);
        if (annotations.stream().anyMatch(annotation -> annotation.annotationName().equals(UNREMOVABLE)))
            signals.add("unremovable");
        if (bean.qualifiers() != null && bean.qualifiers().stream().anyMatch(q -> q.startsWith("@Named")))
            signals.add("named");
        annotations.stream().map(ClassAnnotationRecord::annotationName).filter(ROOT_ANNOTATIONS::contains)
                .distinct().forEach(name -> signals.add("root:" + name));
        ObjectNode retention = node.putObject("arc_retention");
        retention.set("signals", JSON.valueToTree(signals));
        retention.put("removal_ruled_out", !signals.isEmpty());
        retention.put("note", signals.isEmpty()
                ? "No indexed retention signal. ArC also keeps beans with observer or producer members, "
                        + "beans used through Instance/@All, and extension-registered roots; a normal scope "
                        + "or client proxy alone does not keep a bean"
                : "At least one indexed reason keeps the bean");
    }
}
