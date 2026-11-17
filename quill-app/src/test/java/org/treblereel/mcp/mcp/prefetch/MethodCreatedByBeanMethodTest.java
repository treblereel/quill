package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-023 (Claude): asked which UserService SpringSecurityUserInfoHolder#getUser() returns,
 * the method fact named only the Supplier field; Claude searched for whoever constructs the holder
 * and read AuthConfiguration whole to find the ObjectProvider it is built from.
 */
class MethodCreatedByBeanMethodTest {

    /** These packets are sized for this budget; the default limit may differ. */
    private static final int LEGACY_BUDGET = 5000;
    @TempDir Path root;

    @Test
    void aMethodOfAClassABeanMethodCreatesNamesThatMethodAndItsLazyParameter() throws Exception {
        var db = holderIndex();
        ObjectNode fact =
                new ObjectMapper()
                        .createObjectNode()
                        .put("class", "p.SpringHolder")
                        .put("name", "user")
                        .put("descriptor", "()Ljava/lang/String;");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, fact);
                    return null;
                });
        String created = fact.path("created_by_bean_methods").toString();
        assertTrue(
                created.contains("p.AuthConfig#holder") && created.contains("auth"),
                fact.toString());
        assertTrue(
                created.contains(
                        "org.springframework.beans.factory.ObjectProvider<p.Service> [AMBIGUOUS] lazy: looked up when getObject() runs"),
                created);
        // The lazily looked-up type's candidates, so the profiles deciding them need no other read.
        assertTrue(
                fact.path("ambiguous_candidates").toString().contains("AuthConfig#authService profiles [auth]"),
                fact.toString());
    }

    private org.jdbi.v3.core.Jdbi holderIndex() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; interface Holder { String user(); } class Service {} "
                                + "class SpringHolder implements Holder { SpringHolder(Object provider) {} public String user() { return null; } } "
                                + "class AuthConfig { Holder holder(Object provider) { return new SpringHolder(provider); } }");
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', 'singleton', '[]', '[\"p.AuthConfig\"]', 'spring' FROM classes WHERE class_name = 'p.AuthConfig'");
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, profiles, framework) "
                                    + "SELECT id, 'PRODUCER_METHOD', 'singleton', '[]', '[\"p.Holder\"]', id, 'holder', '[\"auth\"]', 'spring' FROM classes WHERE class_name = 'p.AuthConfig'");
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, profiles, framework) "
                                    + "SELECT id, 'PRODUCER_METHOD', 'singleton', '[]', '[\"p.Service\"]', id, 'authService', '[\"auth\"]', 'spring' FROM classes WHERE class_name = 'p.AuthConfig'");
                    String candidates =
                            handle.createQuery(
                                            "SELECT '[{\"beanId\":' || id || ',\"disposition\":\"ELIGIBLE\"}]' FROM beans WHERE member_name = 'authService'")
                                    .mapTo(String.class)
                                    .one();
                    handle.execute(
                            "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, resolution_strategy, "
                                    + "resolution_reason, resolution_confidence, resolution_candidates) SELECT b.id, 'METHOD_PARAM', 'org.springframework.beans.factory.ObjectProvider<p.Service>', '[]', 'holder', "
                                    + "'AMBIGUOUS', 'STATIC_SPRING', 'MULTIPLE_CONTEXT_CANDIDATES', 'MEDIUM', ? FROM beans b JOIN classes c ON c.id = b.class_id "
                                    + "WHERE c.class_name = 'p.AuthConfig' AND b.kind = 'CLASS'",
                            candidates);
                });
        return db;
    }

    /** Apollo AQ-014: the class fact of a non-bean class lacked its creator's candidates too. */
    @Test
    void aNonBeanClassFactListsItsCreatorsLazyParameterCandidates() throws Exception {
        var db = holderIndex();
        ObjectNode fact = new ObjectMapper().createObjectNode();
        TestReads.read(
                db,
                reader -> {
                    ClassPrefetchRelations.append(
                            reader, fact, reader.symbols.findClassByName("p.SpringHolder").orElseThrow());
                    return null;
                });
        assertTrue(fact.path("created_by_bean_methods").toString().contains("p.AuthConfig#holder"), fact.toString());
        assertTrue(
                fact.path("ambiguous_candidates").toString().contains("AuthConfig#authService profiles [auth]"),
                fact.toString());
    }

    /** Apollo AQ-035: cut whole, the creating methods marked the whole packet partial. */
    @Test
    void creatingMethodsGoAsOptionalUnlessTheQuestionAsksWhoCreatesIt() {
        var json = new ObjectMapper();
        ObjectNode packet = json.createObjectNode();
        ObjectNode fact =
                packet.putArray("facts")
                        .addObject()
                        .put("kind", "direct_method_impact")
                        .put("class", "c".repeat(3000));
        fact.putArray("created_by_bean_methods").add("x".repeat(1200));
        fact.put("superclass", "y".repeat(900));
        ObjectNode unasked = packet.deepCopy();
        PrefetchContext.boundContext(unasked, "What does X#run do at startup?", LEGACY_BUDGET);
        assertTrue(
                !unasked.path("facts").get(0).has("created_by_bean_methods")
                        && unasked.path("facts").get(0).has("superclass")
                        && !"partial".equals(unasked.path("status").asText()),
                unasked.toString());
        ObjectNode asked = packet.deepCopy();
        PrefetchContext.boundContext(asked, "Who creates X and with which parameters?", LEGACY_BUDGET);
        assertTrue(asked.path("facts").get(0).has("created_by_bean_methods"), asked.toString());
    }
}
