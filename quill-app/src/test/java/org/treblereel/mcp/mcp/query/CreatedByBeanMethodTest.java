package org.treblereel.mcp.mcp.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-023 (Claude): list_injection_points on SpringSecurityUserInfoHolder answered NOT_A_BEAN
 * with no related bean, though two @Bean methods create it; Claude read AuthConfiguration whole.
 */
class CreatedByBeanMethodTest {
    @TempDir Path root;

    @Test
    void aClassCreatedByABeanMethodNamesThatMethodItsProfilesAndParameters() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; interface Holder {} class Service {} class SpringHolder implements Holder { SpringHolder(Service s) {} } "
                                + "class AuthConfig { Holder holder(Service service) { return new SpringHolder(service); } }");
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', '@Singleton', '[]', '[\"p.AuthConfig\"]', 'spring' FROM classes WHERE class_name = 'p.AuthConfig'");
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, profiles, framework) "
                                    + "SELECT id, 'PRODUCER_METHOD', '@Singleton', '[]', '[\"p.Holder\"]', id, 'holder', '[\"auth\"]', 'spring' FROM classes WHERE class_name = 'p.AuthConfig'");
                    handle.execute(
                            "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, resolution_strategy, "
                                    + "resolution_reason, resolution_confidence) SELECT b.id, 'METHOD_PARAM', 'p.Service', '[]', 'holder', 'UNKNOWN', "
                                    + "'STATIC_SPRING', 'NO_CONTEXT_CANDIDATE', 'LOW' FROM beans b JOIN classes c ON c.id = b.class_id WHERE c.class_name = 'p.AuthConfig' AND b.kind = 'CLASS'");
                });
        JsonNode answer =
                TestReads.read(
                        db,
                        reader ->
                                new StructureToolQueries()
                                        .getInjectionPoints(reader, "p.SpringHolder"));
        assertEquals("NOT_A_BEAN", answer.path("error_code").asText(), answer.toString());
        String created = answer.path("created_by_bean_methods").toString();
        assertTrue(created.contains("p.AuthConfig#holder"), answer.toString());
        assertTrue(created.contains("auth"), answer.toString());
        assertTrue(created.contains("p.Service"), answer.toString());
    }
}
