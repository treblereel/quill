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
 * Apollo AQ-006 (Codex): asked what is injected into ReleaseController's userInfoHolder under
 * github,auth, Codex read AuthConfiguration three times; the class fact said only "not resolved
 * [AMBIGUOUS]", with no candidates and no word on which the given profiles activate.
 */
class AmbiguousCandidatesTest {
    @TempDir Path root;

    @Test
    void anAmbiguousPointListsCandidatesAndTheProfilesInThePromptMarkThem() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; interface Holder {} class Controller { Controller(Holder holder) {} } "
                                + "class AuthConfig { Holder holder() { return null; } } class LdapConfig { Holder holder() { return null; } } "
                                + "class DefaultConfig { Holder holder() { return null; } }");
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', '@Singleton', '[]', '[\"p.Controller\"]', 'spring' FROM classes WHERE class_name = 'p.Controller'");
                    String[][] producers = {
                        {"p.AuthConfig", "[\"auth\"]"},
                        {"p.LdapConfig", "[\"ldap\"]"},
                        {"p.DefaultConfig", null}
                    };
                    for (String[] row : producers)
                        handle.execute(
                                "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, profiles, framework) "
                                        + "SELECT id, 'PRODUCER_METHOD', '@Singleton', '[]', '[\"p.Holder\"]', id, 'holder', ?, 'spring' FROM classes WHERE class_name = ?",
                                row[1],
                                row[0]);
                    handle.execute(
                            "INSERT INTO class_annotations(class_id, annotation_name, direct, annotation_values) SELECT id, "
                                    + "'com.acme.condition.ConditionalOnMissingProfile', 1, '{\"value\":[\"auth\",\"ldap\"]}' FROM classes WHERE class_name = 'p.DefaultConfig'");
                    String candidates =
                            handle.createQuery(
                                            "SELECT '[' || group_concat('{\"beanId\":' || b.id || ',\"disposition\":\"ELIGIBLE\"}') || ',{\"beanId\":1,\"disposition\":\"EXCLUDED\"}]' FROM beans b WHERE b.member_name = 'holder'")
                                    .mapTo(String.class)
                                    .one();
                    handle.execute(
                            "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, resolution_strategy, "
                                    + "resolution_reason, resolution_confidence, resolution_candidates) SELECT b.id, 'CONSTRUCTOR_PARAM', 'p.Holder', '[]', '<init>', "
                                    + "'AMBIGUOUS', 'STATIC_SPRING', 'MULTIPLE_CONTEXT_CANDIDATES', 'MEDIUM', ? FROM beans b JOIN classes c ON c.id = b.class_id "
                                    + "WHERE c.class_name = 'p.Controller' AND b.kind = 'CLASS'",
                            candidates);
                });
        ObjectNode fact = new ObjectMapper().createObjectNode();
        TestReads.read(
                db,
                reader -> {
                    ClassPrefetchRelations.append(
                            reader,
                            fact,
                            reader.symbols.findClassByName("p.Controller").orElseThrow());
                    return null;
                });
        String listed = fact.path("ambiguous_candidates").toString();
        assertTrue(listed.contains("AuthConfig#holder profiles [auth]"), fact.toString());
        // An EXCLUDED trace entry (bean 1, the controller's own CLASS bean) is no candidate.
        assertTrue(!listed.contains("Controller"), listed);
        assertTrue(
                listed.contains("DefaultConfig#holder")
                        && listed.contains("ConditionalOnMissingProfile"),
                listed);

        ObjectNode packet = new ObjectMapper().createObjectNode();
        packet.putArray("facts").add(fact);
        ProfileActivation.annotate(
                packet,
                "Profile: portal runs with spring.profiles.active=github,auth.\nWhat is injected?");
        String marked = fact.path("ambiguous_candidates").toString();
        assertTrue(
                marked.contains("AuthConfig#holder profiles [auth]")
                        && marked.contains("active under github,auth"),
                marked);
        assertTrue(marked.matches(".*LdapConfig#holder[^\"]*inactive under github,auth.*"), marked);
        assertTrue(
                marked.matches(".*DefaultConfig#holder[^\"]*inactive under github,auth.*"), marked);
        // The active candidate comes first, so halving a long list keeps it (Apollo AQ-018).
        assertTrue(
                fact.path("ambiguous_candidates")
                        .elements()
                        .next()
                        .get(0)
                        .asText()
                        .contains("AuthConfig#holder"),
                marked);
    }

    /** Apollo AQ-007/AQ-045: candidates took the budget from questions not about injection. */
    @Test
    void candidatesGoFirstUnlessTheQuestionAsksAboutInjection() {
        var json = new ObjectMapper();
        ObjectNode packet = json.createObjectNode();
        ObjectNode fact = packet.putArray("facts").addObject().put("kind", "CLASS");
        fact.putObject("ambiguous_candidates").putArray("p").add("x".repeat(3000));
        fact.put("superclass", "y".repeat(2500));
        PrefetchContext.boundContext(packet.deepCopy(), "Какая область видимости у X?");
        ObjectNode unasked = packet.deepCopy();
        PrefetchContext.boundContext(unasked, "Какая область видимости у X?");
        assertTrue(!unasked.path("facts").get(0).has("ambiguous_candidates"), unasked.toString());
        assertTrue(unasked.path("facts").get(0).has("superclass"), unasked.toString());
        ObjectNode asked = packet.deepCopy();
        PrefetchContext.boundContext(asked, "Что будет внедрено в X?");
        assertTrue(asked.path("facts").get(0).has("ambiguous_candidates"), asked.toString());
    }

    /**
     * Apollo AQ-018: apollo-portal's PasswordEncoder listed ConfigServiceAutoConfiguration's
     * passwordEncoder (apollo-configservice, visible only through apollo-assembly); with no profile
     * it read as active for the portal.
     */
    @Test
    void aCandidateFromAnotherModuleNamesItsModuleAndIsNotMarkedActive() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; interface Enc {} class Controller { Controller(Enc enc) {} } "
                                + "class AuthConfig { Enc enc() { return null; } } class OtherServiceConfig { Enc enc() { return null; } }");
        db.useHandle(
                handle -> {
                    handle.execute(
                            "UPDATE classes SET module = 'portal' WHERE class_name IN ('p.Controller', 'p.AuthConfig')");
                    handle.execute(
                            "UPDATE classes SET module = 'configservice' WHERE class_name = 'p.OtherServiceConfig'");
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', '@Singleton', '[]', '[\"p.Controller\"]', 'spring' FROM classes WHERE class_name = 'p.Controller'");
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, profiles, framework) "
                                    + "SELECT id, 'PRODUCER_METHOD', '@Singleton', '[]', '[\"p.Enc\"]', id, 'enc', '[\"auth\"]', 'spring' FROM classes WHERE class_name = 'p.AuthConfig'");
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, framework) "
                                    + "SELECT id, 'PRODUCER_METHOD', '@Singleton', '[]', '[\"p.Enc\"]', id, 'enc', 'spring' FROM classes WHERE class_name = 'p.OtherServiceConfig'");
                    String candidates =
                            handle.createQuery(
                                            "SELECT '[' || group_concat('{\"beanId\":' || b.id || ',\"disposition\":\"ELIGIBLE\"}') || ']' FROM beans b WHERE b.member_name = 'enc'")
                                    .mapTo(String.class)
                                    .one();
                    handle.execute(
                            "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, resolution_strategy, "
                                    + "resolution_reason, resolution_confidence, resolution_candidates) SELECT b.id, 'CONSTRUCTOR_PARAM', 'p.Enc', '[]', '<init>', "
                                    + "'AMBIGUOUS', 'STATIC_SPRING', 'MULTIPLE_CONTEXT_CANDIDATES', 'MEDIUM', ? FROM beans b JOIN classes c ON c.id = b.class_id "
                                    + "WHERE c.class_name = 'p.Controller' AND b.kind = 'CLASS'",
                            candidates);
                });
        ObjectNode fact = new ObjectMapper().createObjectNode();
        TestReads.read(
                db,
                reader -> {
                    ClassPrefetchRelations.append(
                            reader,
                            fact,
                            reader.symbols.findClassByName("p.Controller").orElseThrow());
                    return null;
                });
        ObjectNode packet = new ObjectMapper().createObjectNode();
        packet.putArray("facts").add(fact);
        ProfileActivation.annotate(packet, "spring.profiles.active=github,auth");
        String marked = fact.path("ambiguous_candidates").toString();
        assertTrue(
                marked.matches(".*OtherServiceConfig#enc module configservice[^\"]*other module.*"),
                marked);
        assertTrue(
                marked.matches(".*AuthConfig#enc profiles \\[auth\\] - active under github,auth.*"),
                marked);
    }

    /**
     * Apollo AQ-018: a @Bean method's PasswordEncoder parameter said only "not resolved
     * [AMBIGUOUS]".
     */
    @Test
    void aBeanMethodsAmbiguousParameterListsItsCandidates() throws Exception {
        var db =
                JavaEvidenceFixture.index(
                        root,
                        "package p; interface Enc {} class Service {} class AuthConfig { Service service(Enc enc) { return null; } Enc enc() { return null; } } "
                                + "class OidcConfig { Enc enc() { return null; } }");
        db.useHandle(
                handle -> {
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', '@Singleton', '[]', '[\"p.AuthConfig\"]', 'spring' FROM classes WHERE class_name = 'p.AuthConfig'");
                    handle.execute(
                            "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, framework) SELECT id, 'PRODUCER_METHOD', '@Singleton', '[]', '[\"p.Service\"]', id, 'service', 'spring' FROM classes WHERE class_name = 'p.AuthConfig'");
                    String[][] encoders = {
                        {"p.AuthConfig", "[\"auth\"]"}, {"p.OidcConfig", "[\"oidc\"]"}
                    };
                    for (String[] row : encoders)
                        handle.execute(
                                "INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, declaring_class_id, member_name, profiles, framework) "
                                        + "SELECT id, 'PRODUCER_METHOD', '@Singleton', '[]', '[\"p.Enc\"]', id, 'enc', ?, 'spring' FROM classes WHERE class_name = ?",
                                row[1],
                                row[0]);
                    String candidates =
                            handle.createQuery(
                                            "SELECT '[' || group_concat('{\"beanId\":' || b.id || ',\"disposition\":\"ELIGIBLE\"}') || ']' FROM beans b WHERE b.member_name = 'enc'")
                                    .mapTo(String.class)
                                    .one();
                    handle.execute(
                            "INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolution_status, resolution_strategy, "
                                    + "resolution_reason, resolution_confidence, resolution_candidates) SELECT b.id, 'METHOD_PARAM', 'p.Enc', '[]', 'service', "
                                    + "'AMBIGUOUS', 'STATIC_SPRING', 'MULTIPLE_CONTEXT_CANDIDATES', 'MEDIUM', ? FROM beans b JOIN classes c ON c.id = b.class_id "
                                    + "WHERE c.class_name = 'p.AuthConfig' AND b.kind = 'CLASS'",
                            candidates);
                });
        ObjectNode fact =
                new ObjectMapper()
                        .createObjectNode()
                        .put("class", "p.AuthConfig")
                        .put("name", "service")
                        .put("descriptor", "(Lp/Enc;)Lp/Service;");
        TestReads.read(
                db,
                reader -> {
                    MethodPrefetchRelations.append(reader, root, fact);
                    return null;
                });
        String listed = fact.path("ambiguous_candidates").toString();
        assertTrue(
                listed.contains("AuthConfig#enc profiles [auth]")
                        && listed.contains("OidcConfig#enc profiles [oidc]"),
                fact.toString());
    }
}
