package org.treblereel.mcp.mcp.query;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.db.IndexFixture;
import org.treblereel.mcp.db.IndexWriter;
import org.treblereel.mcp.db.QuillDatabase;
import org.treblereel.mcp.mcp.TestReads;
import org.treblereel.mcp.model.BeanRecord;
import org.treblereel.mcp.model.ClassRecord;
import org.treblereel.mcp.model.DependencyRecord;
import org.treblereel.mcp.model.InjectionPointRecord;

/**
 * V66 CQ-030: Codex sent find_cycles scope "cdi" and a package name four times per session and got
 * Invalid scope.
 */
class CdiCycleQueriesTest {

    @TempDir Path root;
    static final ObjectMapper JSON = new ObjectMapper();

    private Jdbi index() throws Exception {
        Jdbi db = QuillDatabase.create(root.resolve("cycles.db"));
        IndexWriter.writeIncremental(
                db,
                new IndexFixture()
                        .classes(
                                List.of(
                                        new ClassRecord(
                                                0,
                                                "x.proxy.Api",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Api.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.proxy.Helper",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Helper.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.direct.Holder",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Holder.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.direct.Part",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Part.java",
                                                1,
                                                true,
                                                10),
                                        new ClassRecord(
                                                0,
                                                "x.other.Lone",
                                                "CLASS",
                                                "java.lang.Object",
                                                List.of(),
                                                "Lone.java",
                                                1,
                                                true,
                                                10)))
                        .beans(
                                List.of(
                                        bean(1, "@ApplicationScoped", "x.proxy.Api"),
                                        bean(2, "@Dependent", "x.proxy.Helper"),
                                        bean(3, "@Singleton", "x.direct.Holder"),
                                        bean(4, "@Dependent", "x.direct.Part"),
                                        bean(5, "@ApplicationScoped", "x.other.Lone")))
                        .injectionPoints(
                                List.of(
                                        point(1, "x.proxy.Helper", "helper", 2),
                                        point(2, "x.proxy.Api", "api", 1),
                                        point(3, "x.direct.Part", "part", 4),
                                        point(4, "x.direct.Holder", "holder", 3)))
                        .dependencies(
                                List.of(
                                        new DependencyRecord(
                                                3, 4, "CDI_INJECT", null, 1, List.of()),
                                        new DependencyRecord(
                                                4, 3, "CDI_INJECT", null, 1, List.of()),
                                        new DependencyRecord(
                                                1, 2, "CDI_INJECT", null, 1, List.of()),
                                        new DependencyRecord(
                                                2, 1, "CDI_INJECT", null, 1, List.of())))
                        .metadata(Map.of("index_id", "cycles"))
                        .snapshot());
        return db;
    }

    private static BeanRecord bean(int classId, String scope, String type) {
        return new BeanRecord(
                0,
                classId,
                "CLASS",
                scope,
                List.of("@Default", "@Any"),
                List.of(),
                false,
                null,
                null,
                null,
                null,
                List.of(type));
    }

    private static InjectionPointRecord point(int beanId, String type, String field, int resolved) {
        return InjectionPointRecord.staticAnalysis(
                0,
                beanId,
                "FIELD",
                type,
                List.of("@Default"),
                field,
                resolved,
                false,
                InjectionPointRecord.STATIC_CDI);
    }

    private static JsonNode cycles(Jdbi db, String scope) throws Exception {
        return TestReads.read(
                db,
                reader -> new CycleQueries().findCycles(reader, scope, null, false, false, 20, 0));
    }

    @Test
    void cdiScopeSeparatesProxiedCyclesFromCreationCycles() throws Exception {
        JsonNode result = cycles(index(), "cdi");
        assertEquals("cdi", result.path("scope").asText(), result.toString());
        assertEquals(2, result.path("total").asInt(), result.toString());
        JsonNode direct =
                result.path("cycles")
                        .valueStream()
                        .filter(cycle -> cycle.toString().contains("x.direct.Holder"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(direct.path("creation_cycle").asBoolean(), direct.toString());
        assertTrue(
                direct.path("edges")
                        .toString()
                        .contains(
                                "x.direct.Holder#part -> x.direct.Part (@Dependent, no client proxy)"),
                direct.toString());
        JsonNode proxied =
                result.path("cycles")
                        .valueStream()
                        .filter(cycle -> cycle.toString().contains("x.proxy.Api"))
                        .findFirst()
                        .orElseThrow();
        assertFalse(proxied.path("creation_cycle").asBoolean(), proxied.toString());
        assertTrue(
                proxied.path("edges")
                        .toString()
                        .contains(
                                "x.proxy.Helper#api -> x.proxy.Api (@ApplicationScoped, client proxy)"),
                proxied.toString());
        assertTrue(
                result.path("verdict_basis").asText().contains("client proxy"), result.toString());
    }

    @Test
    void aPackageScopeKeepsTheClassCyclesTouchingThatPackage() throws Exception {
        JsonNode result = cycles(index(), "x.direct");
        assertEquals("x.direct", result.path("package_filter").asText(), result.toString());
        assertEquals(1, result.path("total").asInt(), result.toString());
        assertTrue(result.path("cycles").toString().contains("x.direct.Part"), result.toString());
        assertEquals(0, cycles(index(), "x.other").path("total").asInt());
    }
}
