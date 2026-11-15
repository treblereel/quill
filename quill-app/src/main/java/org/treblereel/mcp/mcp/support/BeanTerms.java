package org.treblereel.mcp.mcp.support;

import java.util.List;
import java.util.Locale;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.BuiltinQualifier;
import org.treblereel.mcp.core.Json;

/**
 * Presents a bean in its own container's vocabulary. The index stores Spring beans in the CDI-normalized
 * form the resolvers match on ({@code @Singleton}, {@code PRODUCER_METHOD}, implicit {@code @Default});
 * shown as is, those read as Spring facts that are not true, so Spring beans are translated on output.
 */
public final class BeanTerms {
    public static final String SPRING_BEAN_METHOD = "BEAN_METHOD";
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = Json.MAPPER;

    private BeanTerms() {}

    public static boolean spring(String framework) {
        return BeanRecord.SPRING.equals(framework);
    }

    public static String kind(BeanRecord bean) {
        return kind(bean.framework(), bean.kind());
    }

    public static String kind(String framework, String kind) {
        if (!spring(framework) || kind == null) return kind;
        return "PRODUCER_METHOD".equals(kind) ? SPRING_BEAN_METHOD : kind;
    }

    public static String scope(BeanRecord bean) {
        return scope(bean.framework(), bean.scope());
    }

    /** Spring scope names: singleton, prototype, request, session, application, or the custom name. */
    public static String scope(String framework, String scope) {
        if (!spring(framework) || scope == null) return scope;
        return switch (scope) {
            case "@Singleton" -> "singleton";
            case "@Prototype" -> "prototype";
            case "@RequestScoped" -> "request";
            case "@SessionScoped" -> "session";
            case "@ApplicationScoped" -> "application";
            default -> scope.startsWith("@") ? scope.substring(1) : scope;
        };
    }

    public static List<String> qualifiers(BeanRecord bean) {
        return qualifiers(bean.framework(), bean.qualifiers());
    }

    /** Spring has no implicit {@code @Default}/{@code @Any}; its bean name already shows as {@code @Qualifier}. */
    public static List<String> qualifiers(String framework, List<String> qualifiers) {
        if (!spring(framework) || qualifiers == null) return qualifiers;
        return qualifiers.stream()
                .filter(q -> !q.equals(BuiltinQualifier.DEFAULT.annotation()) && !q.equals(BuiltinQualifier.ANY.annotation()))
                .toList();
    }

    /** A JSON qualifier array read straight from the index, translated like {@link #qualifiers(String, List)}. */
    public static String qualifiersJson(Object framework, Object json) {
        if (json == null || !spring(String.valueOf(framework))) return json == null ? null : String.valueOf(json);
        try {
            List<String> values = JSON.readValue(String.valueOf(json), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return JSON.writeValueAsString(qualifiers(BeanRecord.SPRING, values));
        } catch (java.io.IOException unreadable) {
            return String.valueOf(json);
        }
    }

    public static String kind(Object framework, Object kind) {
        return kind == null ? null : kind(framework == null ? null : String.valueOf(framework), String.valueOf(kind));
    }

    public static String scope(Object framework, Object scope) {
        return scope == null ? null : scope(framework == null ? null : String.valueOf(framework), String.valueOf(scope));
    }

    /** Accepts a Spring scope name in a filter and returns the stored form; other values pass through. */
    public static String storedScope(String requested) {
        if (requested == null || requested.startsWith("@")) return requested;
        return switch (requested.toLowerCase(Locale.ROOT)) {
            case "singleton" -> "@Singleton";
            case "prototype" -> "@Prototype";
            case "request" -> "@RequestScoped";
            case "session" -> "@SessionScoped";
            case "application" -> "@ApplicationScoped";
            default -> "@" + requested;
        };
    }

    /** Accepts {@code BEAN_METHOD} in a filter and returns the stored kind. */
    public static String storedKind(String requested) {
        return SPRING_BEAN_METHOD.equalsIgnoreCase(String.valueOf(requested)) ? "PRODUCER_METHOD" : requested;
    }
}
