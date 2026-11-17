package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-032: asked which access checks guard a {@code @PreAuthorize} handler, both clients
 * found the SpEL bean, its delegating validators, the module's servlet filters and the security
 * chains one read at a time (Claude 21 calls, Codex 625K tokens); the endpoint fact named none.
 */
class SpringEndpointSecurityTest {
    @TempDir Path root;

    @Test
    void aPreAuthorizeHandlerNamesTheExpressionBeanItsDelegatesFiltersAndChains() throws Exception {
        String security = TestReads.read(index(), db -> EndpointSecurity.describe(db, "p.Controller", "create", "(Ljava/lang/String;)I"));
        assertTrue(security.contains("@PreAuthorize(\"@validator.canRelease(#app)\")"), security);
        assertTrue(security.contains("@validator is bean p.Validator (injects UserCheck, ConsumerCheck)"), security);
        assertTrue(security.contains("servlet filters in module app: ConsumerFilter, TypeFilter"), security);
        assertFalse(security.contains("OtherFilter"), "another module's filter does not guard this route: " + security);
        assertTrue(security.contains("(FilterRegistrationBean in FilterConfig x1)"), security);
        // The class declaring the chain also enables method security: one entry, not two lists.
        assertTrue(security.contains("SecurityFilterChain beans by class: SecurityConfig [auth] +method security"), security);
        assertFalse(security.contains("method security enabled on"), security);
    }

    private Jdbi index() throws Exception {
        Jdbi db = JavaEvidenceFixture.index(root, "package p; class Controller { int create(String app) { return 1; } } "
                + "class Validator { Validator(UserCheck u, ConsumerCheck c) {} boolean canRelease(String a) { return true; } } "
                + "class UserCheck {} class ConsumerCheck {} class ConsumerFilter {} class TypeFilter {} class SecurityConfig {} "
                + "class FilterConfig {} class OtherFilter {}");
        db.useHandle(h -> {
            h.execute("UPDATE classes SET module = 'app', source_set = 'main'");
            h.execute("UPDATE classes SET module = 'other' WHERE class_name = 'p.OtherFilter'");
            h.execute("UPDATE classes SET interfaces = '[\"jakarta.servlet.Filter\"]' WHERE class_name IN ('p.ConsumerFilter', 'p.OtherFilter')");
            h.execute("UPDATE classes SET superclass = 'org.springframework.web.filter.OncePerRequestFilter' WHERE class_name = 'p.TypeFilter'");
            h.execute("UPDATE class_members SET annotations = '[\"org.springframework.security.access.prepost.PreAuthorize\"]', "
                    + "annotation_details = ? WHERE name = 'create'",
                    "[{\"annotationName\":\"org.springframework.security.access.prepost.PreAuthorize\",\"targetKind\":\"METHOD\","
                            + "\"values\":{\"value\":\"@validator.canRelease(#app)\"}}]");
            h.execute("INSERT INTO class_annotations(class_id, annotation_name, direct) SELECT id, "
                    + "'org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity', 1 FROM classes WHERE class_name = 'p.SecurityConfig'");
            h.execute("INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', 'singleton', "
                    + "'[\"@Default\",\"@Qualifier(\\\"validator\\\")\"]', '[\"p.Validator\"]', 'spring' FROM classes WHERE class_name = 'p.Validator'");
            for (String type : new String[] {"p.UserCheck", "p.ConsumerCheck"})
                h.execute("INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, resolution_strategy, "
                        + "resolution_reason, resolution_confidence) SELECT b.id, 'CONSTRUCTOR_PARAM', ?, '[]', '<init>', 'RESOLVED', 'STATIC_SPRING', "
                        + "'UNIQUE_ELIGIBLE_CANDIDATE', 'HIGH' FROM beans b JOIN classes c ON c.id = b.class_id WHERE c.class_name = 'p.Validator'", type);
            h.execute("INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, framework) SELECT id, "
                    + "'PRODUCER_METHOD', 'singleton', '[]', '[\"org.springframework.boot.web.servlet.FilterRegistrationBean\"]', id, 'userFilter', 'spring' "
                    + "FROM classes WHERE class_name = 'p.FilterConfig'");
            h.execute("INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, profiles, framework) SELECT id, "
                    + "'PRODUCER_METHOD', 'singleton', '[]', '[\"org.springframework.security.web.SecurityFilterChain\"]', id, 'chain', '[\"auth\"]', 'spring' "
                    + "FROM classes WHERE class_name = 'p.SecurityConfig'");
        });
        return db;
    }
}
