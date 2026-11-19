package org.treblereel.mcp.mcp.tools;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.db.ReadQueryContext;
import org.treblereel.mcp.mcp.query.SymbolUsageQueries;
import org.treblereel.mcp.model.*;

/**
 * Apollo AQ-041: a validator called only from @PreAuthorize SpEL was reported as "called only from
 * Java".
 */
class SpelExpressionReferencesTest {

    /**
     * Runs one index query and returns its JSON, as the tool methods these tests were written
     * against did.
     */
    private static String read(
            org.jdbi.v3.core.Jdbi jdbi,
            java.util.function.Function<
                            ReadQueryContext, ? extends com.fasterxml.jackson.databind.JsonNode>
                    query) {
        return ReadQueryContext.readWithGraph(jdbi, query).toString();
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PRE_AUTHORIZE =
            "org.springframework.security.access.prepost.PreAuthorize";
    private static final String CHECK = "(Ljava/lang/String;)Z";

    @TempDir Path tempDir;
    Jdbi jdbi;

    @BeforeEach
    void setUp() {
        jdbi = QuillDatabase.create(tempDir.resolve("spel.db"));
        var classes =
                List.of(
                        new ClassRecord(
                                0,
                                "com.example.PermissionValidator",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "src/main/java/com/example/PermissionValidator.java",
                                5,
                                true,
                                100),
                        new ClassRecord(
                                0,
                                "com.example.ReleaseController",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "src/main/java/com/example/ReleaseController.java",
                                9,
                                true,
                                100),
                        new ClassRecord(
                                0,
                                "com.example.AdminController",
                                "CLASS",
                                "java.lang.Object",
                                List.of(),
                                "src/main/java/com/example/AdminController.java",
                                3,
                                true,
                                100));
        var beans =
                List.of(
                                new BeanRecord(
                                        1,
                                        1,
                                        "CLASS",
                                        "@Singleton",
                                        List.of("@Default", "@Qualifier(\"permissionValidator\")"),
                                        List.of(),
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of("com.example.PermissionValidator")),
                                new BeanRecord(
                                        2,
                                        2,
                                        "CLASS",
                                        "@Singleton",
                                        List.of("@Default", "@Qualifier(\"releaseController\")"),
                                        List.of(),
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of("com.example.ReleaseController")),
                                new BeanRecord(
                                        3,
                                        3,
                                        "CLASS",
                                        "@Singleton",
                                        List.of("@Default", "@Qualifier(\"adminController\")"),
                                        List.of(),
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        List.of("com.example.AdminController")))
                        .stream()
                        .map(bean -> bean.withFramework(BeanRecord.SPRING))
                        .toList();
        var members =
                List.of(
                        new ClassMemberRecord(
                                1,
                                "METHOD",
                                "canRelease",
                                "canRelease(java.lang.String)",
                                CHECK,
                                "boolean",
                                List.of("java.lang.String"),
                                "public",
                                List.of()),
                        new ClassMemberRecord(
                                1,
                                "METHOD",
                                "canDelete",
                                "canDelete(java.lang.String)",
                                CHECK,
                                "boolean",
                                List.of("java.lang.String"),
                                "public",
                                List.of()),
                        new ClassMemberRecord(
                                2,
                                "METHOD",
                                "release",
                                "release(java.lang.String)",
                                "(Ljava/lang/String;)V",
                                "void",
                                List.of("java.lang.String"),
                                "public",
                                List.of(PRE_AUTHORIZE),
                                List.of(
                                        new MemberAnnotationRecord(
                                                PRE_AUTHORIZE,
                                                "METHOD",
                                                -1,
                                                "",
                                                "",
                                                Map.of(
                                                        "value",
                                                        "@permissionValidator.canRelease(#appId) and isAuthenticated()")))),
                        new ClassMemberRecord(
                                2,
                                "METHOD",
                                "delete",
                                "delete(java.lang.String)",
                                "(Ljava/lang/String;)V",
                                "void",
                                List.of("java.lang.String"),
                                "public",
                                List.of(PRE_AUTHORIZE),
                                List.of(
                                        new MemberAnnotationRecord(
                                                PRE_AUTHORIZE,
                                                "METHOD",
                                                -1,
                                                "",
                                                "",
                                                Map.of(
                                                        "value",
                                                        "@permissionValidator.canDelete(#appId)")))));
        var classAnnotations =
                List.of(
                        new ClassAnnotationRecord(
                                3,
                                PRE_AUTHORIZE,
                                true,
                                null,
                                Map.<String, Object>of(
                                        "value", "@permissionValidator . canRelease('admin')")));
        IndexWriter.writeIncremental(
                jdbi,
                new IndexFixture()
                        .classes(classes)
                        .beans(beans)
                        .classMembers(members)
                        .classAnnotations(classAnnotations)
                        .metadata(
                                Map.of(
                                        "indexed_at",
                                        "2026-11-14T10:00:00",
                                        "last_commit",
                                        "abc1234"))
                        .snapshot());
    }

    @Test
    void callHierarchyListsMethodAndClassLevelSpelCallers() throws Exception {
        String text =
                read(
                        jdbi,
                        r ->
                                new QuillToolQueries()
                                        .getCallHierarchy(
                                                r,
                                                "com.example.PermissionValidator",
                                                "canRelease",
                                                "inbound",
                                                20,
                                                0));
        JsonNode expressions = JSON.readTree(text).path("expression_references");
        assertEquals(2, expressions.path("total").asInt(), text);
        assertTrue(text.contains("ReleaseController") && text.contains("release"), text);
        assertTrue(text.contains("AdminController"), text);
        assertFalse(
                text.contains("canDelete(#appId)"),
                "another method's expression is not a caller: " + text);
        assertTrue(text.contains("listed in expression_references"), text);
    }

    @Test
    void methodUsagesCarryTheExpressionReferences() throws Exception {
        String text =
                read(
                        jdbi,
                        reader ->
                                new SymbolUsageQueries()
                                        .findSymbolUsages(
                                                reader,
                                                "com.example.PermissionValidator",
                                                "canDelete",
                                                "METHOD",
                                                null,
                                                null,
                                                20,
                                                0));
        JsonNode expressions = JSON.readTree(text).path("expression_references");
        assertEquals(1, expressions.path("total").asInt(), text);
        assertEquals("permissionValidator", expressions.path("bean_names").get(0).asText(), text);
    }

    @Test
    void aClassThatIsNoSpringBeanGetsNoExpressionSection() throws Exception {
        String text =
                read(
                        jdbi,
                        r ->
                                new QuillToolQueries()
                                        .getCallHierarchy(
                                                r,
                                                "com.example.ReleaseController",
                                                "release",
                                                "inbound",
                                                20,
                                                0));
        assertEquals(
                0, JSON.readTree(text).path("expression_references").path("total").asInt(), text);
    }
}
