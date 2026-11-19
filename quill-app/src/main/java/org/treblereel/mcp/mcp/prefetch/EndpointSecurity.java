package org.treblereel.mcp.mcp.prefetch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treblereel.mcp.db.ReadQueryContext;

/**
 * For a handler with a security annotation, the application classes that build or authorize the
 * request's SecurityIdentity. V65 CQ-032: Claude searched class names one by one to find what
 * grants @PermissionsAllowed.
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

    private static final Map<String, String> PROVIDERS =
            Map.of(
                    "io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism",
                            "HttpAuthenticationMechanism",
                    "io.quarkus.security.identity.SecurityIdentityAugmentor",
                            "SecurityIdentityAugmentor",
                    "io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy",
                            "HttpSecurityPolicy",
                    "io.quarkus.security.identity.IdentityProvider", "IdentityProvider");
    private static final int MAX_PROVIDERS = 6;

    private EndpointSecurity() {}

    /** One line, or null when neither the handler nor its class carries a security annotation. */
    static String describe(ReadQueryContext db, String owner, String method, String descriptor) {
        String spring = SpringEndpointSecurity.describe(db, owner, method, descriptor);
        if (spring != null) return spring;
        List<String> annotations =
                new ArrayList<>(
                        db.symbols.findMethodAnnotationNames(
                                owner, method, descriptor == null ? "" : descriptor));
        // Order does not matter here: both lists are only searched.
        annotations.addAll(db.symbols.findDirectAnnotationNames(owner));
        String joined = String.join(" ", annotations);
        List<String> present =
                ANNOTATIONS.entrySet().stream()
                        .filter(
                                e ->
                                        joined.contains("\"" + e.getKey() + "\"")
                                                || annotations.contains(e.getKey()))
                        .map(Map.Entry::getValue)
                        .distinct()
                        .toList();
        if (present.isEmpty()) return null;
        List<String> providers = new ArrayList<>();
        db.symbols
                .findMainSourceClassInterfaces()
                .forEach(
                        row ->
                                PROVIDERS.forEach(
                                        (type, label) -> {
                                            if (String.valueOf(row.interfaces())
                                                    .contains("\"" + type + "\""))
                                                providers.add(row.className() + " (" + label + ")");
                                        }));
        db.symbols
                .findPermissionCheckers()
                .forEach(checker -> providers.add(checker + " (@PermissionChecker)"));
        String text =
                String.join(", ", present)
                        + " is checked by Quarkus against the request's SecurityIdentity; ";
        if (providers.isEmpty())
            return text
                    + "no application HttpAuthenticationMechanism, SecurityIdentityAugmentor, HttpSecurityPolicy, "
                    + "IdentityProvider or @PermissionChecker is indexed, so framework or extension providers build it";
        String listed =
                String.join(", ", providers.subList(0, Math.min(MAX_PROVIDERS, providers.size())));
        return text
                + "application providers that build or authorize it (direct interfaces): "
                + listed
                + (providers.size() > MAX_PROVIDERS
                        ? " (+" + (providers.size() - MAX_PROVIDERS) + " more)"
                        : "");
    }
}
