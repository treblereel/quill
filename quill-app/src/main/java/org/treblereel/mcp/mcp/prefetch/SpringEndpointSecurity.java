package org.treblereel.mcp.mcp.prefetch;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.treblereel.mcp.core.Json;
import org.treblereel.mcp.db.BeanRepository;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.diagnostics.QuillLog;

/**
 * The access checks around a Spring handler with a method-security annotation: the expression, the
 * beans it calls and what they delegate to, where method security is enabled, and the servlet
 * filters and security chains of the handler's module. Apollo AQ-032: both clients assembled these
 * one read at a time (Claude 21 calls, Codex 625K tokens).
 */
final class SpringEndpointSecurity {

    private static final Map<String, String> ANNOTATIONS =
            Map.of(
                    "org.springframework.security.access.prepost.PreAuthorize", "@PreAuthorize",
                    "org.springframework.security.access.prepost.PostAuthorize", "@PostAuthorize",
                    "org.springframework.security.access.annotation.Secured", "@Secured");
    private static final List<String> ENABLERS =
            List.of(
                    "org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity",
                    "org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity");
    private static final Pattern BEAN_CALL = Pattern.compile("@([A-Za-z_$][\\w$]*)\\s*\\.");
    private static final int LIMIT = 6;

    private SpringEndpointSecurity() {}

    /** One line, or null when the handler carries no Spring method-security annotation. */
    static String describe(ReadQueryContext db, String owner, String method, String descriptor) {
        List<String> checks = new ArrayList<>();
        Set<String> beans = new LinkedHashSet<>();
        for (String details :
                db.symbols.findMethodAnnotationDetails(owner, method, descriptor == null ? "" : descriptor)) {
            for (JsonNode annotation : parse(details)) {
                String label = ANNOTATIONS.get(annotation.path("annotationName").asText());
                if (label == null) continue;
                JsonNode value = annotation.path("values").path("value");
                String expression = value.isArray() ? value.toString() : value.asText();
                checks.add(label + "(\"" + expression + "\")");
                Matcher call = BEAN_CALL.matcher(expression);
                while (call.find()) beans.add(call.group(1));
            }
        }
        if (checks.isEmpty()) return null;
        StringBuilder text =
                new StringBuilder(String.join(", ", checks))
                        .append(" is checked by Spring method security before the handler runs");
        for (String name : beans) {
            List<BeanRepository.NamedBean> named = db.beans.findSpringBeansNamed(name);
            if (named.isEmpty()) {
                text.append("; @").append(name).append(" names no indexed bean");
                continue;
            }
            BeanRepository.NamedBean bean = named.getFirst();
            text.append("; @").append(name).append(" is bean ").append(bean.className());
            if (bean.memberName() != null) text.append('#').append(bean.memberName());
            List<String> delegates =
                    db.beans.findInjectionPoints(bean.beanId()).stream()
                            .map(point -> simple(point.targetType()))
                            .distinct()
                            .limit(LIMIT)
                            .toList();
            if (!delegates.isEmpty())
                text.append(" (injects ").append(String.join(", ", delegates)).append(')');
        }
        String module =
                db.symbols.findClassByName(owner).map(cls -> cls.module()).orElse(null);
        if (module == null) return text.toString();
        List<String> filters = db.symbols.findModuleServletFilters(module);
        if (!filters.isEmpty()) {
            text.append("; servlet filters in module ")
                    .append(module)
                    .append(": ")
                    .append(list(filters.stream().map(SpringEndpointSecurity::simple).toList()));
            Map<String, Integer> registrations = new java.util.LinkedHashMap<>();
            for (var provider :
                    db.beans.findModuleTypeProviders(
                            "org.springframework.boot.web.servlet.FilterRegistrationBean", module))
                registrations.merge(simple(provider.className()), 1, Integer::sum);
            if (!registrations.isEmpty()) {
                List<String> owners = new ArrayList<>();
                registrations.forEach((cls, count) -> owners.add(cls + " x" + count));
                text.append(" (FilterRegistrationBean in ").append(list(owners)).append(')');
            }
        }
        Set<String> enablers =
                new LinkedHashSet<>(db.symbols.findModuleClassesAnnotated(module, ENABLERS));
        List<String> chains = new ArrayList<>();
        for (var provider :
                db.beans.findModuleTypeProviders(
                        "org.springframework.security.web.SecurityFilterChain", module)) {
            String line = simple(provider.className());
            String profiles = provider.profiles();
            if (profiles != null && !profiles.isBlank() && !"[]".equals(profiles))
                line += " " + profiles.replace("\"", "").replace(",", ", ");
            if (enablers.remove(provider.className())) line += " +method security";
            chains.add(line);
        }
        if (!chains.isEmpty())
            text.append("; SecurityFilterChain beans by class: ").append(grouped(chains));
        if (!enablers.isEmpty())
            text.append("; method security enabled on ")
                    .append(list(enablers.stream().map(SpringEndpointSecurity::simple).toList()));
        return text.toString();
    }

    /** {@code Outer$A, Outer$B} as {@code Outer$ A, B}: nested configuration classes share a prefix. */
    private static String grouped(List<String> names) {
        Map<String, List<String>> byOuter = new java.util.LinkedHashMap<>();
        for (String name : names) {
            int nested = name.indexOf('$');
            String outer = nested < 0 ? "" : name.substring(0, nested + 1);
            byOuter.computeIfAbsent(outer, key -> new ArrayList<>())
                    .add(nested < 0 ? name : name.substring(nested + 1));
        }
        List<String> parts = new ArrayList<>();
        byOuter.forEach((outer, inner) -> parts.add(outer.isEmpty() ? list(inner) : outer + " " + list(inner)));
        return String.join("; ", parts);
    }

    private static String list(List<String> items) {
        String shown = String.join(", ", items.subList(0, Math.min(LIMIT, items.size())));
        return items.size() > LIMIT ? shown + " (+" + (items.size() - LIMIT) + " more)" : shown;
    }

    private static String simple(String className) {
        return className.substring(className.lastIndexOf('.') + 1);
    }

    private static JsonNode parse(String json) {
        try {
            return Json.MAPPER.readTree(json);
        } catch (Exception unreadable) {
            QuillLog.swallowed(unreadable);
            return Json.MAPPER.createArrayNode();
        }
    }
}
