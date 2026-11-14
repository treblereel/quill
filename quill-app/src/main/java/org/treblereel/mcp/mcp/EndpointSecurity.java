package org.treblereel.mcp.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * For a handler with a security annotation, the application classes that build or authorize the request's
 * SecurityIdentity. V65 CQ-032: Claude searched class names one by one to find what grants @PermissionsAllowed.
 */
final class EndpointSecurity {

    private static final Map<String, String> ANNOTATIONS = new LinkedHashMap<>();
    static {
        ANNOTATIONS.put("io.quarkus.security.PermissionsAllowed", "@PermissionsAllowed");
        ANNOTATIONS.put("jakarta.annotation.security.RolesAllowed", "@RolesAllowed");
        ANNOTATIONS.put("javax.annotation.security.RolesAllowed", "@RolesAllowed");
        ANNOTATIONS.put("io.quarkus.security.Authenticated", "@Authenticated");
        ANNOTATIONS.put("jakarta.annotation.security.DenyAll", "@DenyAll");
        ANNOTATIONS.put("jakarta.annotation.security.PermitAll", "@PermitAll");
    }
    private static final Map<String, String> PROVIDERS = Map.of(
            "io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism", "HttpAuthenticationMechanism",
            "io.quarkus.security.identity.SecurityIdentityAugmentor", "SecurityIdentityAugmentor",
            "io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy", "HttpSecurityPolicy",
            "io.quarkus.security.identity.IdentityProvider", "IdentityProvider");
    private static final int MAX_PROVIDERS = 6;

    private EndpointSecurity() {
    }

    /** One line, or null when neither the handler nor its class carries a security annotation. */
    static String describe(ReadQueryContext db, String owner, String method, String descriptor) {
        List<String> annotations = db.jdbi().withHandle(h -> {
            List<String> found = new ArrayList<>(h.createQuery("""
                    SELECT m.annotations FROM class_members m JOIN classes c ON c.id=m.class_id
                    WHERE c.class_name=:owner AND m.kind='METHOD' AND m.name=:method AND (:descriptor='' OR m.descriptor=:descriptor)""")
                    .bind("owner", owner).bind("method", method).bind("descriptor", descriptor == null ? "" : descriptor)
                    .mapTo(String.class).list());
            found.addAll(h.createQuery("""
                    SELECT a.annotation_name FROM class_annotations a JOIN classes c ON c.id=a.class_id
                    WHERE c.class_name=:owner AND a.direct=1""").bind("owner", owner).mapTo(String.class).list());
            return found;
        });
        String joined = String.join(" ", annotations);
        List<String> present = ANNOTATIONS.entrySet().stream()
                .filter(e -> joined.contains("\"" + e.getKey() + "\"") || annotations.contains(e.getKey()))
                .map(Map.Entry::getValue).distinct().toList();
        if (present.isEmpty()) return null;
        List<String> providers = db.jdbi().withHandle(h -> {
            List<String> rows = new ArrayList<>();
            h.createQuery("SELECT class_name, interfaces FROM classes WHERE origin='source' AND source_set='main' AND interfaces IS NOT NULL ORDER BY class_name")
                    .mapToMap().forEach(row -> PROVIDERS.forEach((type, label) -> {
                        if (String.valueOf(row.get("interfaces")).contains("\"" + type + "\""))
                            rows.add(row.get("class_name") + " (" + label + ")");
                    }));
            h.createQuery("""
                    SELECT c.class_name || '#' || m.name FROM class_members m JOIN classes c ON c.id=m.class_id
                    WHERE c.origin='source' AND m.annotations LIKE '%"io.quarkus.security.PermissionChecker"%' ORDER BY 1""")
                    .mapTo(String.class).forEach(checker -> rows.add(checker + " (@PermissionChecker)"));
            return rows;
        });
        String text = String.join(", ", present) + " is checked by Quarkus against the request's SecurityIdentity; ";
        if (providers.isEmpty())
            return text + "no application HttpAuthenticationMechanism, SecurityIdentityAugmentor, HttpSecurityPolicy, "
                    + "IdentityProvider or @PermissionChecker is indexed, so framework or extension providers build it";
        String listed = String.join(", ", providers.subList(0, Math.min(MAX_PROVIDERS, providers.size())));
        return text + "application providers that build or authorize it (direct interfaces): " + listed
                + (providers.size() > MAX_PROVIDERS ? " (+" + (providers.size() - MAX_PROVIDERS) + " more)" : "");
    }
}
