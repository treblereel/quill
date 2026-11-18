package org.treblereel.mcp.mcp.prefetch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.treblereel.mcp.mcp.JavaEvidenceFixture;
import org.treblereel.mcp.mcp.TestReads;

/**
 * Apollo AQ-003/AQ-043/AQ-044, Cryostat CQ-007: injected_into lines repeated the class as the
 * injected type and listed every using method, so the budget kept 3 to 6 of 15 to 26 consumers.
 */
class InjectedIntoLineTest {
    @TempDir Path root;

    @Test
    void aLineOmitsTheClassAsTypeAndNamesAtMostThreeUsingMethods() throws Exception {
        Jdbi db = JavaEvidenceFixture.index(root, "package p; interface Holder { String user(); } "
                + "class Web { Holder holder; String a() { return holder.user(); } String b() { return holder.user(); } "
                + "String c() { return holder.user(); } String d() { return holder.user(); } String e() { return holder.user(); } }");
        db.useHandle(h -> {
            h.execute("UPDATE classes SET source_set = 'main', module = 'app'");
            for (String bean : new String[] {"p.Holder", "p.Web"})
                h.execute("INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', 'singleton', "
                        + "'[\"@Default\"]', json_array(class_name), 'spring' FROM classes WHERE class_name = ?", bean);
            h.execute("INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, resolution_status, "
                    + "resolution_strategy, resolution_reason, resolution_confidence) SELECT o.id, 'FIELD', 'p.Holder', '[\"@Default\"]', 'holder', "
                    + "t.id, 'RESOLVED', 'STATIC_SPRING', 'UNIQUE_ELIGIBLE_CANDIDATE', 'HIGH' FROM beans o JOIN classes oc ON oc.id = o.class_id, "
                    + "beans t JOIN classes tc ON tc.id = t.class_id WHERE oc.class_name = 'p.Web' AND tc.class_name = 'p.Holder'");
        });
        ObjectNode fact = new ObjectMapper().createObjectNode();
        TestReads.read(db, reader -> {
            ClassPrefetchRelations.append(reader, fact, reader.symbols.findClassByName("p.Holder").orElseThrow());
            return null;
        });
        assertEquals("p.Web#holder (FIELD) [RESOLVED] owner singleton, uses it in a, b, c (+2 more)",
                fact.path("injected_into").get(0).asText(), fact.toString());
    }

    /**
     * Apollo AQ-043: a consumer that never calls the bean (a configuration checking its type) sorted
     * after every caller, so the budget cut it while contract_callers already named the callers.
     */
    @Test
    void consumersThatNeverCallTheBeanComeBeforeCallers() throws Exception {
        Jdbi db = JavaEvidenceFixture.index(root, "package p; interface Holder { String user(); } "
                + "class Alpha { Holder holder; String a() { return holder.user(); } } "
                + "class Zeta { Holder holder; boolean plain() { return holder instanceof Object; } }");
        db.useHandle(h -> {
            h.execute("UPDATE classes SET source_set = 'main', module = 'app'");
            for (String bean : new String[] {"p.Holder", "p.Alpha", "p.Zeta"})
                h.execute("INSERT INTO beans(class_id, kind, scope, qualifiers, bean_types, framework) SELECT id, 'CLASS', 'singleton', "
                        + "'[\"@Default\"]', json_array(class_name), 'spring' FROM classes WHERE class_name = ?", bean);
            for (String owner : new String[] {"p.Alpha", "p.Zeta"})
                h.execute("INSERT INTO injection_points(bean_id, kind, target_type, qualifiers, field_name, resolved_bean_id, resolution_status, "
                        + "resolution_strategy, resolution_reason, resolution_confidence) SELECT o.id, 'FIELD', 'p.Holder', '[\"@Default\"]', 'holder', "
                        + "t.id, 'RESOLVED', 'STATIC_SPRING', 'UNIQUE_ELIGIBLE_CANDIDATE', 'HIGH' FROM beans o JOIN classes oc ON oc.id = o.class_id, "
                        + "beans t JOIN classes tc ON tc.id = t.class_id WHERE oc.class_name = ? AND tc.class_name = 'p.Holder'", owner);
        });
        ObjectNode fact = new ObjectMapper().createObjectNode();
        TestReads.read(db, reader -> {
            ClassPrefetchRelations.append(reader, fact, reader.symbols.findClassByName("p.Holder").orElseThrow());
            return null;
        });
        assertEquals("p.Zeta#holder (FIELD) [RESOLVED] owner singleton", fact.path("injected_into").get(0).asText(), fact.toString());
    }
}
